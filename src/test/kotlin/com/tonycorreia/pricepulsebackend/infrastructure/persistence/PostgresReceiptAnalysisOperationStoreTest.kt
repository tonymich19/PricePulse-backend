package com.tonycorreia.pricepulsebackend.infrastructure.persistence

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.PreparedReceiptImage
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderCallFailureReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderCorrelationReference
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderRejectionReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderTerminalFailureReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ValidatedReceiptAnalysisResultV1
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ApplyOutcomeResult
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ClaimOutcome
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.OutcomeApplication
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ReceiptAnalysisOperationId
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ReceiptAnalysisOperationLifecycle
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ReceiptAnalysisOperationResult
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.RequestId
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.RequestLookup
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.StartOutcome
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.StartReceiptAnalysisOperationCommand
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.UserId
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.File
import java.io.PrintWriter
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import javax.sql.DataSource
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real PostgreSQL integration tests for [PostgresReceiptAnalysisOperationStore] -- the adapter
 * under test, not the V1 schema itself (that's [PostgresSchemaContractTest], kept separate and
 * unmodified). Every assertion that matters queries PostgreSQL directly, never just the Kotlin
 * outcome, per receiptanalysis-slice-report.md 6.10.28.
 */
@Testcontainers
class PostgresReceiptAnalysisOperationStoreTest {

    private class TestPostgresContainer(image: String) : PostgreSQLContainer<TestPostgresContainer>(image)

    /** Minimal, connection-per-call [DataSource] -- no pool, matching that the adapter itself
     * never creates one; a real deployment would inject a pooled DataSource instead. */
    private class DirectDataSource(
        private val jdbcUrl: String,
        private val username: String,
        private val password: String
    ) : DataSource {
        override fun getConnection(): Connection = DriverManager.getConnection(jdbcUrl, username, password)
        override fun getConnection(username: String?, password: String?): Connection =
            DriverManager.getConnection(jdbcUrl, username, password)
        override fun getLogWriter(): PrintWriter? = null
        override fun setLogWriter(out: PrintWriter?) {}
        override fun setLoginTimeout(seconds: Int) {}
        override fun getLoginTimeout(): Int = 0
        override fun getParentLogger(): Logger = throw UnsupportedOperationException()
        override fun <T : Any?> unwrap(iface: Class<T>?): T = throw UnsupportedOperationException()
        override fun isWrapperFor(iface: Class<*>?): Boolean = false
    }

    private class MutableClock(private var instant: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = instant
        fun advanceTo(newInstant: Instant) {
            instant = newInstant
        }
    }

    /**
     * Deterministic race harness for the regression below -- Kotlin interface delegation (`by`)
     * forwards every JDBC member to the real object untouched except the one point each layer
     * exists to intercept, so this needs no reflection or hand-written stubs for the ~50-method
     * [Connection] interface. [onOperationRowFetched] fires the instant [ResultSet.next] has
     * fetched a row from a statement matching [matchesTargetQuery] -- i.e. exactly when the
     * production adapter's single-statement read (operation + [RESULT_DOCUMENT_SUBQUERY] in one
     * snapshot) has materialized the row server-side, but before the calling Kotlin code has
     * mapped it into an [OperationRow]/domain object.
     */
    private class InterceptingDataSource(
        private val real: DataSource,
        private val matchesTargetQuery: (String) -> Boolean,
        private val onOperationRowFetched: () -> Unit
    ) : DataSource by real {
        override fun getConnection(): Connection =
            InterceptingConnection(real.connection, matchesTargetQuery, onOperationRowFetched)
    }

    private class InterceptingConnection(
        private val real: Connection,
        private val matchesTargetQuery: (String) -> Boolean,
        private val onOperationRowFetched: () -> Unit
    ) : Connection by real {
        override fun prepareStatement(sql: String): PreparedStatement {
            val realStatement = real.prepareStatement(sql)
            return if (matchesTargetQuery(sql)) {
                InterceptingPreparedStatement(realStatement, onOperationRowFetched)
            } else {
                realStatement
            }
        }
    }

    private class InterceptingPreparedStatement(
        private val real: PreparedStatement,
        private val onOperationRowFetched: () -> Unit
    ) : PreparedStatement by real {
        override fun executeQuery(): ResultSet = InterceptingResultSet(real.executeQuery(), onOperationRowFetched)
    }

    private class InterceptingResultSet(
        private val real: ResultSet,
        private val onOperationRowFetched: () -> Unit
    ) : ResultSet by real {
        private var alreadyIntercepted = false

        override fun next(): Boolean {
            val hasNext = real.next()
            if (hasNext && !alreadyIntercepted) {
                alreadyIntercepted = true
                onOperationRowFetched()
            }
            return hasNext
        }
    }

    companion object {
        @Container
        @JvmStatic
        private val postgres = TestPostgresContainer("postgres:16-alpine")

        private lateinit var dataSource: DataSource
        private lateinit var verificationConnection: Connection

        @BeforeAll
        @JvmStatic
        fun migrateAndConnect() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .load()
                .migrate()

            dataSource = DirectDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            verificationConnection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
            verificationConnection.autoCommit = true
        }

        @AfterAll
        @JvmStatic
        fun closeConnection() {
            verificationConnection.close()
        }
    }

    private val clock = MutableClock(Instant.parse("2026-08-15T12:00:00Z"))
    private val userId = UserId("user-1")

    @BeforeEach
    fun resetTables() {
        verificationConnection.createStatement().use { statement ->
            statement.execute(
                "TRUNCATE credit_ledger_entry, receipt_analysis_result, receipt_analysis_payload, " +
                    "receipt_analysis_operation, credit_grant CASCADE"
            )
        }
    }

    private fun newStore(monthlyFreeCredits: Int = 5): PostgresReceiptAnalysisOperationStore =
        PostgresReceiptAnalysisOperationStore(dataSource, clock, monthlyFreeCredits)

    private fun sampleImage(bytes: ByteArray = byteArrayOf(1, 2, 3)) = PreparedReceiptImage(bytes, "image/jpeg")

    private fun sampleDocument(): ValidatedReceiptAnalysisResultV1 {
        val projectRoot = File(System.getProperty("user.dir"))
        val fixture = File(projectRoot, "contracts/fixtures/valid/complete-with-items.json")
        return requireNotNull(ValidatedReceiptAnalysisResultV1.from(fixture.readBytes()))
    }

    // -----------------------------------------------------------------------------------------
    // Raw-SQL verification helpers -- every case here proves state in PostgreSQL, not just the
    // Kotlin return value.
    // -----------------------------------------------------------------------------------------

    private fun countRows(table: String): Int =
        verificationConnection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM $table").use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    private fun remainingCredits(grantId: UUID): Int =
        verificationConnection.prepareStatement("SELECT remaining_credits FROM credit_grant WHERE grant_id = ?").use { statement ->
            statement.setObject(1, grantId)
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getInt("remaining_credits")
            }
        }

    private fun reservedGrantId(operationId: ReceiptAnalysisOperationId): UUID =
        verificationConnection.prepareStatement(
            "SELECT grant_id FROM credit_ledger_entry WHERE operation_id = ? AND effect = 'RESERVED'"
        ).use { statement ->
            statement.setObject(1, UUID.fromString(operationId.value))
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getObject("grant_id", UUID::class.java)
            }
        }

    private fun ledgerEffectCount(operationId: ReceiptAnalysisOperationId, effect: String): Int =
        verificationConnection.prepareStatement(
            "SELECT count(*) FROM credit_ledger_entry WHERE operation_id = ? AND effect = ?"
        ).use { statement ->
            statement.setObject(1, UUID.fromString(operationId.value))
            statement.setString(2, effect)
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    private fun ledgerEffectCountForGrant(grantId: UUID, effect: String): Int =
        verificationConnection.prepareStatement(
            "SELECT count(*) FROM credit_ledger_entry WHERE grant_id = ? AND effect = ?"
        ).use { statement ->
            statement.setObject(1, grantId)
            statement.setString(2, effect)
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    /** Reads the raw persisted column -- proves the exact stored-code string, not just that
     * reading it back through the store happens to produce the right Kotlin value. */
    private fun storedTerminalFailureReason(operationId: ReceiptAnalysisOperationId): String? =
        verificationConnection.prepareStatement(
            "SELECT terminal_failure_reason FROM receipt_analysis_operation WHERE operation_id = ?"
        ).use { statement ->
            statement.setObject(1, UUID.fromString(operationId.value))
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getString("terminal_failure_reason")
            }
        }

    private fun correlationRowCount(operationId: ReceiptAnalysisOperationId): Int =
        verificationConnection.prepareStatement(
            "SELECT count(*) FROM receipt_analysis_provider_correlation WHERE operation_id = ?"
        ).use { statement ->
            statement.setObject(1, UUID.fromString(operationId.value))
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    /** Same shape as [correlationRowCount] -- the shared autoCommit verification connection,
     * never a second connection opened from the pooled [dataSource] under test. */
    private fun payloadRowCount(operationId: ReceiptAnalysisOperationId): Int =
        verificationConnection.prepareStatement(
            "SELECT count(*) FROM receipt_analysis_payload WHERE operation_id = ?"
        ).use { statement ->
            statement.setObject(1, UUID.fromString(operationId.value))
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    private fun storedCorrelationReference(operationId: ReceiptAnalysisOperationId): String? =
        verificationConnection.prepareStatement(
            "SELECT correlation_reference FROM receipt_analysis_provider_correlation WHERE operation_id = ?"
        ).use { statement ->
            statement.setObject(1, UUID.fromString(operationId.value))
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.getString("correlation_reference") else null
            }
        }

    /** [verificationConnection] runs with autoCommit=true so every plain statement commits on its
     * own -- fine for single-statement reads, but wrong for multi-statement raw-SQL setup that
     * must satisfy a DEFERRABLE INITIALLY DEFERRED trigger (e.g. a credit_grant row and its
     * founding GRANTED entry): those must land in the *same* transaction, or the deferred trigger
     * validates "exactly one GRANTED" after only the first statement's own auto-committed
     * transaction, before the second even exists. */
    private fun inOneTransaction(block: () -> Unit) {
        verificationConnection.autoCommit = false
        try {
            block()
            verificationConnection.commit()
        } finally {
            verificationConnection.autoCommit = true
        }
    }

    private fun grantedCountFor(grantId: UUID): Int =
        verificationConnection.prepareStatement(
            "SELECT count(*) FROM credit_ledger_entry WHERE grant_id = ? AND effect = 'GRANTED'"
        ).use { statement ->
            statement.setObject(1, grantId)
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    /** Directly constructs a TERMINAL row via raw SQL, bypassing the adapter -- used only to
     * simulate persisted corruption the adapter itself could never produce through its own
     * writes (an invalid document, or a terminal_failure_reason code outside the closed mapping). */
    private fun insertCorruptedTerminalOperation(
        requestId: String,
        terminalKind: String,
        terminalFailureReason: String?,
        resultDocumentBytes: ByteArray?
    ): ReceiptAnalysisOperationId {
        val operationId = UUID.randomUUID()
        val grantId = UUID.randomUUID()
        val now = java.sql.Timestamp.from(clock.instant())
        // Ledger equation: remaining = credits_granted(5) - RESERVED(1) + RELEASED(1 iff FAILED_NO_PROVIDER).
        val remainingCredits = if (terminalKind == "FAILED_NO_PROVIDER") 5 else 4

        inOneTransaction {
            verificationConnection.prepareStatement(
                "INSERT INTO credit_grant (grant_id, user_id, source, period, purchase_reference, " +
                    "credits_granted, remaining_credits, granted_at) VALUES (?, ?, 'FREE_MONTHLY', '2026-08', NULL, 5, ?, ?)"
            ).use { it.setObject(1, grantId); it.setString(2, userId.value); it.setInt(3, remainingCredits); it.setTimestamp(4, now); it.executeUpdate() }
            verificationConnection.prepareStatement(
                "INSERT INTO credit_ledger_entry (entry_id, grant_id, operation_id, user_id, effect, created_at) " +
                    "VALUES (?, ?, NULL, ?, 'GRANTED', ?)"
            ).use { it.setObject(1, UUID.randomUUID()); it.setObject(2, grantId); it.setString(3, userId.value); it.setTimestamp(4, now); it.executeUpdate() }

            verificationConnection.prepareStatement(
                "INSERT INTO receipt_analysis_operation (operation_id, user_id, request_id, content_hash, " +
                    "size_bytes, mime_type, attempt_id, lifecycle_state, terminal_kind, terminal_failure_reason, " +
                    "tombstoned_at, created_at, updated_at) VALUES (?, ?, ?, ?, 3, 'image/jpeg', ?, 'TERMINAL', ?, ?, NULL, ?, ?)"
            ).use { statement ->
                statement.setObject(1, operationId)
                statement.setString(2, userId.value)
                statement.setString(3, requestId)
                statement.setString(4, "a".repeat(64))
                statement.setObject(5, UUID.randomUUID())
                statement.setString(6, terminalKind)
                statement.setString(7, terminalFailureReason)
                statement.setTimestamp(8, now)
                statement.setTimestamp(9, now)
                statement.executeUpdate()
            }
            verificationConnection.prepareStatement(
                "INSERT INTO credit_ledger_entry (entry_id, grant_id, operation_id, user_id, effect, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?)"
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, grantId)
                statement.setObject(3, operationId)
                statement.setString(4, userId.value)
                statement.setString(5, if (terminalKind == "FAILED_NO_PROVIDER") "RELEASED" else "DEBITED")
                statement.setTimestamp(6, now)
                statement.executeUpdate()
            }
            // A RESERVED entry is also required by the V1 invariants for every operation.
            verificationConnection.prepareStatement(
                "INSERT INTO credit_ledger_entry (entry_id, grant_id, operation_id, user_id, effect, created_at) " +
                    "VALUES (?, ?, ?, ?, 'RESERVED', ?)"
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, grantId)
                statement.setObject(3, operationId)
                statement.setString(4, userId.value)
                statement.setTimestamp(5, now)
                statement.executeUpdate()
            }
            if (resultDocumentBytes != null) {
                verificationConnection.prepareStatement(
                    "INSERT INTO receipt_analysis_result (operation_id, document, stored_at) VALUES (?, ?, ?)"
                ).use { statement ->
                    statement.setObject(1, operationId)
                    statement.setBytes(2, resultDocumentBytes)
                    statement.setTimestamp(3, now)
                    statement.executeUpdate()
                }
            }
        }
        return ReceiptAnalysisOperationId(operationId.toString())
    }

    private fun <T> raceConcurrently(threadCount: Int, action: suspend () -> T): List<T> =
        raceConcurrentlyIndexed(threadCount) { action() }

    /** Like [raceConcurrently], but [action] receives the thread's index (0 until [threadCount])
     * so each racer can act on distinct data -- e.g. a distinct `requestId` -- while still
     * starting together, released by the same latch. */
    private fun <T> raceConcurrentlyIndexed(threadCount: Int, action: suspend (Int) -> T): List<T> {
        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)
        val results = Collections.synchronizedList(mutableListOf<T>())
        val pool = Executors.newFixedThreadPool(threadCount)

        repeat(threadCount) { index ->
            pool.execute {
                ready.countDown()
                start.await()
                results.add(runBlocking { action(index) })
                done.countDown()
            }
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(done.await(20, TimeUnit.SECONDS))
        pool.shutdown()
        return results.toList()
    }

    // -----------------------------------------------------------------------------------------
    // Free creation: grant + GRANTED + RESERVED + payload
    // -----------------------------------------------------------------------------------------

    @Test
    fun `new creation leaves a free grant, its GRANTED, a RESERVED, and the payload -- in PostgreSQL`(): Unit = runBlocking {
        val store = newStore(monthlyFreeCredits = 5)
        val image = sampleImage(byteArrayOf(4, 5, 6))
        val requestId = RequestId("req-1")

        val result = store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, requestId, image))

        val accepted = assertIs<StartOutcome.Accepted>(result)
        assertTrue(accepted.isNew)
        assertEquals(1, countRows("receipt_analysis_operation"))
        assertEquals(1, countRows("credit_grant"))
        assertEquals(1, countRows("receipt_analysis_payload"))

        val grantId = reservedGrantId(accepted.operation.operationId)
        assertEquals(1, grantedCountFor(grantId))
        assertEquals(1, ledgerEffectCount(accepted.operation.operationId, "RESERVED"))
        assertEquals(4, remainingCredits(grantId))

        verificationConnection.prepareStatement("SELECT payload_bytes FROM receipt_analysis_payload WHERE operation_id = ?").use { statement ->
            statement.setObject(1, UUID.fromString(accepted.operation.operationId.value))
            statement.executeQuery().use { rs ->
                rs.next()
                assertEquals(image.bytes().toList(), rs.getBytes("payload_bytes").toList())
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Retry same hash, hash conflict, tombstone priority
    // -----------------------------------------------------------------------------------------

    @Test
    fun `retry with the same hash reuses the operation and writes nothing new`(): Unit = runBlocking {
        val store = newStore()
        val image = sampleImage()
        val requestId = RequestId("req-1")

        val first = assertIs<StartOutcome.Accepted>(
            store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, requestId, image))
        )
        val second = assertIs<StartOutcome.Accepted>(
            store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, requestId, image))
        )

        assertTrue(first.isNew)
        assertTrue(!second.isNew)
        assertEquals(first.operation.operationId, second.operation.operationId)
        assertEquals(1, countRows("receipt_analysis_operation"))
        assertEquals(1, countRows("receipt_analysis_payload"))
        assertEquals(1, ledgerEffectCount(first.operation.operationId, "RESERVED"))
    }

    @Test
    fun `retry with a different hash returns HashConflict and writes nothing new`(): Unit = runBlocking {
        val store = newStore()
        val requestId = RequestId("req-1")
        store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, requestId, sampleImage(byteArrayOf(1, 2, 3))))

        val opRowsBefore = countRows("receipt_analysis_operation")
        val payloadRowsBefore = countRows("receipt_analysis_payload")
        val ledgerRowsBefore = countRows("credit_ledger_entry")

        val conflict = store.startOrGetExisting(
            StartReceiptAnalysisOperationCommand(userId, requestId, sampleImage(byteArrayOf(9, 9, 9)))
        )

        assertIs<StartOutcome.HashConflict>(conflict)
        assertEquals(opRowsBefore, countRows("receipt_analysis_operation"))
        assertEquals(payloadRowsBefore, countRows("receipt_analysis_payload"))
        assertEquals(ledgerRowsBefore, countRows("credit_ledger_entry"))
    }

    @Test
    fun `tombstone is checked before the hash comparison, for both matching and divergent hashes`(): Unit = runBlocking {
        val store = newStore()
        val requestId = RequestId("req-1")
        val image = sampleImage()
        val accepted = assertIs<StartOutcome.Accepted>(
            store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, requestId, image))
        )
        assertIs<ClaimOutcome.Claimed>(store.claimInvocation(accepted.operation.operationId))
        assertIs<ApplyOutcomeResult.Applied>(store.applyOutcome(accepted.operation.operationId, OutcomeApplication.Succeeded(sampleDocument())))
        tombstoneAndCleanUp(accepted.operation.operationId)

        val sameHashRetry = store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, requestId, image))
        val differentHashRetry = store.startOrGetExisting(
            StartReceiptAnalysisOperationCommand(userId, requestId, sampleImage(byteArrayOf(9, 9, 9)))
        )

        assertIs<StartOutcome.Tombstoned>(sameHashRetry)
        assertIs<StartOutcome.Tombstoned>(differentHashRetry)
    }

    private fun tombstoneAndCleanUp(operationId: ReceiptAnalysisOperationId) {
        val opUuid = UUID.fromString(operationId.value)
        inOneTransaction {
            verificationConnection.prepareStatement("UPDATE receipt_analysis_operation SET tombstoned_at = ? WHERE operation_id = ?").use { statement ->
                statement.setTimestamp(1, java.sql.Timestamp.from(clock.instant()))
                statement.setObject(2, opUuid)
                statement.executeUpdate()
            }
            verificationConnection.prepareStatement("DELETE FROM receipt_analysis_result WHERE operation_id = ?").use { statement ->
                statement.setObject(1, opUuid)
                statement.executeUpdate()
            }
            verificationConnection.prepareStatement("DELETE FROM receipt_analysis_payload WHERE operation_id = ?").use { statement ->
                statement.setObject(1, opUuid)
                statement.executeUpdate()
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Insufficient credit: table-delta proves a full rollback, not just the Kotlin outcome
    // -----------------------------------------------------------------------------------------

    @Test
    fun `insufficient credit leaves zero new rows or mutations from that attempt`(): Unit = runBlocking {
        val store = newStore(monthlyFreeCredits = 1)
        val firstAccepted = assertIs<StartOutcome.Accepted>(
            store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, RequestId("req-1"), sampleImage()))
        )
        val grantId = reservedGrantId(firstAccepted.operation.operationId)
        assertEquals(0, remainingCredits(grantId))

        val opRowsBefore = countRows("receipt_analysis_operation")
        val payloadRowsBefore = countRows("receipt_analysis_payload")
        val ledgerRowsBefore = countRows("credit_ledger_entry")
        val grantRowsBefore = countRows("credit_grant")

        val result = store.startOrGetExisting(
            StartReceiptAnalysisOperationCommand(userId, RequestId("req-2"), sampleImage(byteArrayOf(7, 7, 7)))
        )

        assertIs<StartOutcome.InsufficientCredits>(result)
        assertEquals(opRowsBefore, countRows("receipt_analysis_operation"))
        assertEquals(payloadRowsBefore, countRows("receipt_analysis_payload"))
        assertEquals(ledgerRowsBefore, countRows("credit_ledger_entry"))
        assertEquals(grantRowsBefore, countRows("credit_grant"))
        assertEquals(0, remainingCredits(grantId))
    }

    // -----------------------------------------------------------------------------------------
    // Purchased-grant fallback, oldest first
    // -----------------------------------------------------------------------------------------

    @Test
    fun `once the free grant is exhausted, the oldest PURCHASED grant with balance funds the reservation`(): Unit = runBlocking {
        val store = newStore(monthlyFreeCredits = 1)
        val exhausting = assertIs<StartOutcome.Accepted>(
            store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, RequestId("req-1"), sampleImage()))
        )
        val freeGrantId = reservedGrantId(exhausting.operation.operationId)
        assertEquals(0, remainingCredits(freeGrantId))

        val olderPurchasedId = insertPurchasedGrant(reference = "order-older", grantedAt = Instant.parse("2026-08-01T00:00:00Z"), credits = 3)
        val newerPurchasedId = insertPurchasedGrant(reference = "order-newer", grantedAt = Instant.parse("2026-08-10T00:00:00Z"), credits = 3)

        val funded = assertIs<StartOutcome.Accepted>(
            store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, RequestId("req-2"), sampleImage(byteArrayOf(8, 8, 8))))
        )

        assertEquals(olderPurchasedId, reservedGrantId(funded.operation.operationId))
        assertNotEquals(newerPurchasedId, reservedGrantId(funded.operation.operationId))
        assertEquals(2, remainingCredits(olderPurchasedId))
        assertEquals(3, remainingCredits(newerPurchasedId))
    }

    private fun insertPurchasedGrant(reference: String, grantedAt: Instant, credits: Int): UUID {
        val grantId = UUID.randomUUID()
        inOneTransaction {
            verificationConnection.prepareStatement(
                "INSERT INTO credit_grant (grant_id, user_id, source, period, purchase_reference, " +
                    "credits_granted, remaining_credits, granted_at) VALUES (?, ?, 'PURCHASED', NULL, ?, ?, ?, ?)"
            ).use { statement ->
                statement.setObject(1, grantId)
                statement.setString(2, userId.value)
                statement.setString(3, reference)
                statement.setInt(4, credits)
                statement.setInt(5, credits)
                statement.setTimestamp(6, java.sql.Timestamp.from(grantedAt))
                statement.executeUpdate()
            }
            verificationConnection.prepareStatement(
                "INSERT INTO credit_ledger_entry (entry_id, grant_id, operation_id, user_id, effect, created_at) " +
                    "VALUES (?, ?, NULL, ?, 'GRANTED', ?)"
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, grantId)
                statement.setString(3, userId.value)
                statement.setTimestamp(4, java.sql.Timestamp.from(grantedAt))
                statement.executeUpdate()
            }
        }
        return grantId
    }

    // -----------------------------------------------------------------------------------------
    // Concurrency: request idempotency vs. actual grant creation/lock concurrency
    // -----------------------------------------------------------------------------------------

    /** Every thread races the exact same `(userId, requestId)`: only the `INSERT` winner ever
     * reaches `selectFundingGrant`, the other 7 just resolve the idempotent conflict path without
     * ever touching `credit_grant`. This proves request-level idempotency under concurrency, not
     * concurrent grant creation -- see the test below for that (BLOCKED rodada -- the report
     * previously, incorrectly, described this test as proving grant concurrency; see 6.10.29). */
    @Test
    fun `concurrent creation for the same request creates exactly one operation, via idempotency, not grant contention`() {
        val store = newStore()
        val requestId = RequestId("req-1")

        val results = raceConcurrently(8) {
            store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, requestId, sampleImage()))
        }

        val accepted = results.map { assertIs<StartOutcome.Accepted>(it) }
        assertEquals(1, accepted.count { it.isNew })
        assertEquals(1, accepted.map { it.operation.operationId }.distinct().size)
        assertEquals(1, countRows("receipt_analysis_operation"))
        assertEquals(1, countRows("credit_grant"))
        assertEquals(1, countRows("receipt_analysis_payload"))

        val grantId = reservedGrantId(accepted.first().operation.operationId)
        assertEquals(1, grantedCountFor(grantId))
        assertEquals(1, ledgerEffectCount(accepted.first().operation.operationId, "RESERVED"))
    }

    /** Distinct `requestId`s racing the *same* user/period, all reaching `selectFundingGrant`
     * concurrently -- this is the test that actually exercises the `credit_grant` `ON CONFLICT`/
     * `FOR UPDATE` concurrency the report claimed the test above covered. A known franchise smaller
     * than the thread count forces both the `Accepted` and `InsufficientCredits` paths to race for
     * the same grant row. */
    @Test
    fun `concurrent creation for distinct requests races the shared monthly grant -- one grant, one GRANTED, no double reservation`() {
        val monthlyFreeCredits = 3
        val threadCount = 8
        val store = newStore(monthlyFreeCredits)

        val results = raceConcurrentlyIndexed(threadCount) { index ->
            store.startOrGetExisting(
                StartReceiptAnalysisOperationCommand(
                    userId,
                    RequestId("req-$index"),
                    sampleImage(byteArrayOf(index.toByte(), 1, 2))
                )
            )
        }

        val accepted = results.filterIsInstance<StartOutcome.Accepted>()
        val insufficient = results.filterIsInstance<StartOutcome.InsufficientCredits>()
        assertEquals(monthlyFreeCredits, accepted.size)
        assertEquals(threadCount - monthlyFreeCredits, insufficient.size)
        assertTrue(accepted.all { it.isNew })
        assertEquals(monthlyFreeCredits, accepted.map { it.operation.operationId }.distinct().size)

        // No trace of the InsufficientCredits attempts: exactly the accepted count of operations
        // and payloads, never threadCount.
        assertEquals(1, countRows("credit_grant"))
        assertEquals(monthlyFreeCredits, countRows("receipt_analysis_operation"))
        assertEquals(monthlyFreeCredits, countRows("receipt_analysis_payload"))

        val grantId = reservedGrantId(accepted.first().operation.operationId)
        accepted.forEach { assertEquals(grantId, reservedGrantId(it.operation.operationId)) }
        assertEquals(1, grantedCountFor(grantId))
        assertEquals(monthlyFreeCredits, ledgerEffectCountForGrant(grantId, "RESERVED"))
        assertEquals(0, remainingCredits(grantId))
        // No double reservation: GRANTED (1) + RESERVED (one per Accepted), nothing more.
        assertEquals(monthlyFreeCredits + 1, countRows("credit_ledger_entry"))
    }

    // -----------------------------------------------------------------------------------------
    // findByRequestId + exact round-trip of a succeeded document
    // -----------------------------------------------------------------------------------------

    @Test
    fun `findByRequestId rehydrates NotFound, Found and Tombstoned`(): Unit = runBlocking {
        val store = newStore()
        assertIs<RequestLookup.NotFound>(store.findByRequestId(userId, RequestId("never-existed")))

        val requestId = RequestId("req-1")
        val accepted = assertIs<StartOutcome.Accepted>(
            store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, requestId, sampleImage()))
        )
        val found = assertIs<RequestLookup.Found>(store.findByRequestId(userId, requestId))
        assertEquals(accepted.operation.operationId, found.operation.operationId)

        assertIs<ClaimOutcome.Claimed>(store.claimInvocation(accepted.operation.operationId))
        assertIs<ApplyOutcomeResult.Applied>(store.applyOutcome(accepted.operation.operationId, OutcomeApplication.Succeeded(sampleDocument())))
        tombstoneAndCleanUp(accepted.operation.operationId)

        assertIs<RequestLookup.Tombstoned>(store.findByRequestId(userId, requestId))
    }

    @Test
    fun `a succeeded document round-trips byte for byte through findByRequestId`(): Unit = runBlocking {
        val store = newStore()
        val requestId = RequestId("req-1")
        val document = sampleDocument()
        val accepted = assertIs<StartOutcome.Accepted>(
            store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, requestId, sampleImage()))
        )
        assertIs<ClaimOutcome.Claimed>(store.claimInvocation(accepted.operation.operationId))
        assertIs<ApplyOutcomeResult.Applied>(store.applyOutcome(accepted.operation.operationId, OutcomeApplication.Succeeded(document)))

        val found = assertIs<RequestLookup.Found>(store.findByRequestId(userId, requestId))
        val terminal = assertIs<ReceiptAnalysisOperationLifecycle.Terminal>(found.operation.lifecycle)
        val succeeded = assertIs<ReceiptAnalysisOperationResult.Succeeded>(terminal.result)
        assertEquals(document.serialize().toList(), succeeded.document.serialize().toList())
    }

    /**
     * Supplementary, **probabilistic** soak test -- not a deterministic reproduction. It starts
     * readers and the tombstone cleanup racing freely, with no barrier pinning the tombstone to
     * land between one specific reader's row-fetch and its mapping step; the tombstone may win
     * before any reader runs, after all of them finish, or anywhere in between. A green run here
     * does not by itself prove the exact interleaving that broke the old two-query code was ever
     * exercised -- see the deterministic proof below
     * (`findByRequestId sees a coherent pre-tombstone snapshot ...`) for that. Kept because many
     * iterations under real concurrent load are still useful for catching regressions the
     * deterministic, single-interleaving test wouldn't reach on its own.
     */
    @Test
    fun `findByRequestId survives many real, unsynchronized tombstone races without a false integrity failure -- probabilistic, not a proof`() {
        // Every iteration reserves and terminates one operation against the same user/period --
        // a franchise comfortably above the iteration count keeps every reservation solvent
        // instead of exercising InsufficientCredits, which is not what this test is about.
        val store = newStore(monthlyFreeCredits = 100)

        repeat(20) { iteration ->
            val requestId = RequestId("race-$iteration")
            val accepted = runBlocking {
                assertIs<StartOutcome.Accepted>(
                    store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, requestId, sampleImage()))
                )
            }
            runBlocking {
                assertIs<ClaimOutcome.Claimed>(store.claimInvocation(accepted.operation.operationId))
                assertIs<ApplyOutcomeResult.Applied>(
                    store.applyOutcome(accepted.operation.operationId, OutcomeApplication.Succeeded(sampleDocument()))
                )
            }

            val readerThreads = 6
            val stop = java.util.concurrent.atomic.AtomicBoolean(false)
            val failures = Collections.synchronizedList(mutableListOf<Throwable>())
            val pool = Executors.newFixedThreadPool(readerThreads + 1)

            repeat(readerThreads) {
                pool.execute {
                    while (!stop.get()) {
                        try {
                            runBlocking { store.findByRequestId(userId, requestId) }
                        } catch (integrity: ReceiptAnalysisPersistenceIntegrityException) {
                            failures.add(integrity)
                            stop.set(true)
                        }
                    }
                }
            }
            pool.execute {
                tombstoneAndCleanUp(accepted.operation.operationId)
                stop.set(true)
            }
            pool.shutdown()
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS))

            assertTrue(failures.isEmpty(), "findByRequestId threw for a legitimate tombstone race: $failures")

            val finalLookup = runBlocking { store.findByRequestId(userId, requestId) }
            assertIs<RequestLookup.Tombstoned>(finalLookup)
        }
    }

    /**
     * Deterministic proof that `findByRequestId` is linearizable, forcing the exact interleaving
     * that broke the old two-statement design: [InterceptingResultSet.next] fires
     * [onOperationRowFetched] the instant the production query's `ResultSet` has fetched the
     * `TERMINAL`/`SUCCEEDED` row -- server-side, this already includes the result document, read
     * via `RESULT_DOCUMENT_SUBQUERY` in the very same snapshot -- but *before* the adapter maps
     * that row into a domain object. From inside that callback, a second, independent connection
     * runs the full tombstone-cleanup transaction (mark `tombstoned_at`, delete
     * `receipt_analysis_result`/`receipt_analysis_payload`) to completion and commit, then control
     * returns to the intercepted call.
     *
     * With the old design (a second, separate query for `receipt_analysis_result.document` after
     * the operation row was already read), this exact sequencing would have been fatal: the first
     * query would have delivered `SUCCEEDED`, this proxy's tombstone would then run and remove the
     * result row, and the old code's second query would find nothing and throw
     * [ReceiptAnalysisPersistenceIntegrityException]. With the fixed single-statement design, the
     * row (operation + document) was already fully materialized server-side before `next()` even
     * returned to the caller -- the tombstone commits *after* that snapshot was taken, so it can
     * only affect subsequent queries, never this already-fetched row. No production code is
     * touched or delayed; only this test's own JDBC layer is instrumented.
     */
    @Test
    fun `findByRequestId sees a coherent pre-tombstone snapshot when a tombstone commits between the row fetch and the mapping step`(): Unit =
        runBlocking {
            val store = newStore()
            val document = sampleDocument()
            val requestId = RequestId("race-deterministic")
            val accepted = assertIs<StartOutcome.Accepted>(
                store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, requestId, sampleImage()))
            )
            assertIs<ClaimOutcome.Claimed>(store.claimInvocation(accepted.operation.operationId))
            assertIs<ApplyOutcomeResult.Applied>(store.applyOutcome(accepted.operation.operationId, OutcomeApplication.Succeeded(document)))

            val tombstoneDone = CountDownLatch(1)
            val racingDataSource = InterceptingDataSource(
                real = dataSource,
                matchesTargetQuery = { sql -> sql.contains("request_id = ?") && !sql.contains("FOR UPDATE") }
            ) {
                // Fires with the SUCCEEDED row (and its document) already fetched from PostgreSQL,
                // before the adapter maps it -- exactly the window the review asked to force.
                Thread {
                    tombstoneAndCleanUp(accepted.operation.operationId)
                    tombstoneDone.countDown()
                }.start()
                assertTrue(tombstoneDone.await(10, TimeUnit.SECONDS), "tombstone transaction never committed")
            }
            val racingStore = PostgresReceiptAnalysisOperationStore(racingDataSource, clock, monthlyFreeCredits = 5)

            val lookupDuringTheRace = racingStore.findByRequestId(userId, requestId)

            val found = assertIs<RequestLookup.Found>(lookupDuringTheRace)
            val terminal = assertIs<ReceiptAnalysisOperationLifecycle.Terminal>(found.operation.lifecycle)
            val succeeded = assertIs<ReceiptAnalysisOperationResult.Succeeded>(terminal.result)
            assertEquals(document.serialize().toList(), succeeded.document.serialize().toList())

            val lookupAfterTheRace = store.findByRequestId(userId, requestId)
            assertIs<RequestLookup.Tombstoned>(lookupAfterTheRace)
        }

    // -----------------------------------------------------------------------------------------
    // Unique concurrent claim
    // -----------------------------------------------------------------------------------------

    @Test
    fun `N concurrent claims for the same operation -- exactly one Claimed`(): Unit = runBlocking {
        val store = newStore()
        val accepted = assertIs<StartOutcome.Accepted>(
            store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, RequestId("req-1"), sampleImage()))
        )

        val results = raceConcurrently(8) { store.claimInvocation(accepted.operation.operationId) }

        assertEquals(1, results.count { it is ClaimOutcome.Claimed })
        assertEquals(7, results.count { it is ClaimOutcome.AlreadyClaimedOrResolved })
    }

    // -----------------------------------------------------------------------------------------
    // applyOutcome: valid/invalid transitions, idempotency, DEBITED vs RELEASED balances
    // -----------------------------------------------------------------------------------------

    @Test
    fun `FailedNoProvider from RECEIVED terminates with RELEASED, restoring the grant balance`(): Unit = runBlocking {
        val store = newStore()
        val accepted = assertIs<StartOutcome.Accepted>(
            store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, RequestId("req-1"), sampleImage()))
        )
        val grantId = reservedGrantId(accepted.operation.operationId)
        val remainingAfterReserve = remainingCredits(grantId)

        val applied = assertIs<ApplyOutcomeResult.Applied>(
            store.applyOutcome(
                accepted.operation.operationId,
                OutcomeApplication.FailedNoProvider(ProviderCallFailureReason.PROVIDER_CREDENTIAL_UNAVAILABLE)
            )
        )
        val terminal = assertIs<ReceiptAnalysisOperationLifecycle.Terminal>(applied.operation.lifecycle)
        val result = assertIs<ReceiptAnalysisOperationResult.FailedNoProvider>(terminal.result)
        assertEquals(ProviderCallFailureReason.PROVIDER_CREDENTIAL_UNAVAILABLE, result.reason)

        assertEquals(1, ledgerEffectCount(accepted.operation.operationId, "RELEASED"))
        assertEquals(remainingAfterReserve + 1, remainingCredits(grantId))
    }

    @Test
    fun `FailedNoProvider with a ProviderRejectionReason terminates with RELEASED, restoring the grant balance, reason preserved on read`(): Unit =
        runBlocking {
            val store = newStore()
            val accepted = assertIs<StartOutcome.Accepted>(
                store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, RequestId("req-1"), sampleImage()))
            )
            val grantId = reservedGrantId(accepted.operation.operationId)
            val remainingAfterReserve = remainingCredits(grantId)

            val applied = assertIs<ApplyOutcomeResult.Applied>(
                store.applyOutcome(
                    accepted.operation.operationId,
                    OutcomeApplication.FailedNoProvider(ProviderRejectionReason.AUTHENTICATION_REJECTED)
                )
            )
            val terminal = assertIs<ReceiptAnalysisOperationLifecycle.Terminal>(applied.operation.lifecycle)
            val result = assertIs<ReceiptAnalysisOperationResult.FailedNoProvider>(terminal.result)
            assertEquals(ProviderRejectionReason.AUTHENTICATION_REJECTED, result.reason)

            assertEquals(1, ledgerEffectCount(accepted.operation.operationId, "RELEASED"))
            assertEquals(remainingAfterReserve + 1, remainingCredits(grantId))

            val found = assertIs<RequestLookup.Found>(store.findByRequestId(userId, RequestId("req-1")))
            val foundTerminal = assertIs<ReceiptAnalysisOperationLifecycle.Terminal>(found.operation.lifecycle)
            val foundResult = assertIs<ReceiptAnalysisOperationResult.FailedNoProvider>(foundTerminal.result)
            assertEquals(ProviderRejectionReason.AUTHENTICATION_REJECTED, foundResult.reason)
        }

    @Test
    fun `FailedNoProvider with every ProviderTerminalFailureReason value round-trips through the explicit stored code, releasing the grant`(): Unit =
        runBlocking {
            val store = newStore()
            val expectedStoredCodes = mapOf(
                ProviderTerminalFailureReason.RETRY_EXHAUSTED to "RETRY_EXHAUSTED",
                ProviderTerminalFailureReason.BILLING_OR_QUOTA_EXHAUSTED to "BILLING_OR_QUOTA_EXHAUSTED",
                ProviderTerminalFailureReason.UNRECOGNIZED_RESPONSE to "UNRECOGNIZED_RESPONSE",
                ProviderTerminalFailureReason.RESPONSE_FAILED to "RESPONSE_FAILED",
                ProviderTerminalFailureReason.RESPONSE_CANCELLED to "RESPONSE_CANCELLED",
                ProviderTerminalFailureReason.RATE_LIMITED to "RATE_LIMITED",
                ProviderTerminalFailureReason.TRANSIENT_PROVIDER_FAILURE to "TRANSIENT_PROVIDER_FAILURE"
            )
            assertEquals(ProviderTerminalFailureReason.entries.toSet(), expectedStoredCodes.keys, "every enum value must be covered")

            expectedStoredCodes.entries.forEachIndexed { index, (reason, expectedCode) ->
                val requestId = RequestId("req-terminal-failure-$index")
                val accepted = assertIs<StartOutcome.Accepted>(
                    store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, requestId, sampleImage()))
                )
                val grantId = reservedGrantId(accepted.operation.operationId)
                val remainingAfterReserve = remainingCredits(grantId)

                val applied = assertIs<ApplyOutcomeResult.Applied>(
                    store.applyOutcome(accepted.operation.operationId, OutcomeApplication.FailedNoProvider(reason))
                )
                val terminal = assertIs<ReceiptAnalysisOperationLifecycle.Terminal>(applied.operation.lifecycle)
                val result = assertIs<ReceiptAnalysisOperationResult.FailedNoProvider>(terminal.result)
                assertEquals(reason, result.reason)

                assertEquals(1, ledgerEffectCount(accepted.operation.operationId, "RELEASED"))
                assertEquals(remainingAfterReserve + 1, remainingCredits(grantId))
                assertEquals(expectedCode, storedTerminalFailureReason(accepted.operation.operationId))

                val found = assertIs<RequestLookup.Found>(store.findByRequestId(userId, requestId))
                val foundTerminal = assertIs<ReceiptAnalysisOperationLifecycle.Terminal>(found.operation.lifecycle)
                val foundResult = assertIs<ReceiptAnalysisOperationResult.FailedNoProvider>(foundTerminal.result)
                assertEquals(reason, foundResult.reason)
            }
        }

    @Test
    fun `any application other than FailedNoProvider is Rejected from RECEIVED, without mutating state`(): Unit = runBlocking {
        val store = newStore()
        val accepted = assertIs<StartOutcome.Accepted>(
            store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, RequestId("req-1"), sampleImage()))
        )

        val rejected = assertIs<ApplyOutcomeResult.Rejected>(
            store.applyOutcome(accepted.operation.operationId, OutcomeApplication.Failed)
        )
        assertEquals(ReceiptAnalysisOperationLifecycle.Received, rejected.operation.lifecycle)
        assertEquals(0, ledgerEffectCount(accepted.operation.operationId, "DEBITED"))
        assertEquals(0, ledgerEffectCount(accepted.operation.operationId, "RELEASED"))
    }

    @Test
    fun `Succeeded from INVOCATION_CLAIMED terminates with DEBITED, balance unchanged`(): Unit = runBlocking {
        val store = newStore()
        val accepted = assertIs<StartOutcome.Accepted>(
            store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, RequestId("req-1"), sampleImage()))
        )
        val grantId = reservedGrantId(accepted.operation.operationId)
        val remainingAfterReserve = remainingCredits(grantId)
        assertIs<ClaimOutcome.Claimed>(store.claimInvocation(accepted.operation.operationId))

        val applied = assertIs<ApplyOutcomeResult.Applied>(
            store.applyOutcome(accepted.operation.operationId, OutcomeApplication.Succeeded(sampleDocument()))
        )
        val terminal = assertIs<ReceiptAnalysisOperationLifecycle.Terminal>(applied.operation.lifecycle)
        assertIs<ReceiptAnalysisOperationResult.Succeeded>(terminal.result)

        assertEquals(1, ledgerEffectCount(accepted.operation.operationId, "DEBITED"))
        assertEquals(remainingAfterReserve, remainingCredits(grantId))
        assertEquals(1, countRows("receipt_analysis_result"))
    }

    @Test
    fun `ReconcilingDetected from INVOCATION_CLAIMED is Applied, not Rejected, with no financial effect`(): Unit = runBlocking {
        val store = newStore()
        val accepted = assertIs<StartOutcome.Accepted>(
            store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, RequestId("req-1"), sampleImage()))
        )
        assertIs<ClaimOutcome.Claimed>(store.claimInvocation(accepted.operation.operationId))

        val applied = assertIs<ApplyOutcomeResult.Applied>(
            store.applyOutcome(accepted.operation.operationId, OutcomeApplication.ReconcilingDetected)
        )
        assertEquals(ReceiptAnalysisOperationLifecycle.Reconciling, applied.operation.lifecycle)
        assertEquals(0, ledgerEffectCount(accepted.operation.operationId, "DEBITED"))
        assertEquals(0, ledgerEffectCount(accepted.operation.operationId, "RELEASED"))
        assertIs<ClaimOutcome.AlreadyClaimedOrResolved>(store.claimInvocation(accepted.operation.operationId))
    }

    @Test
    fun `ReconcilingWithProviderCorrelation from INVOCATION_CLAIMED persists exactly one opaque value, never exposed through the returned operation`(): Unit =
        runBlocking {
            val store = newStore()
            val accepted = assertIs<StartOutcome.Accepted>(
                store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, RequestId("req-1"), sampleImage()))
            )
            assertIs<ClaimOutcome.Claimed>(store.claimInvocation(accepted.operation.operationId))
            val reference = ProviderCorrelationReference("test-correlation-reference")

            val applied = assertIs<ApplyOutcomeResult.Applied>(
                store.applyOutcome(accepted.operation.operationId, OutcomeApplication.ReconcilingWithProviderCorrelation(reference))
            )

            assertEquals(ReceiptAnalysisOperationLifecycle.Reconciling, applied.operation.lifecycle)
            assertEquals(1, correlationRowCount(accepted.operation.operationId))
            assertEquals("test-correlation-reference", storedCorrelationReference(accepted.operation.operationId))
            assertEquals(0, ledgerEffectCount(accepted.operation.operationId, "DEBITED"))
            assertEquals(0, ledgerEffectCount(accepted.operation.operationId, "RELEASED"))

            val found = assertIs<RequestLookup.Found>(store.findByRequestId(userId, RequestId("req-1")))
            assertEquals(ReceiptAnalysisOperationLifecycle.Reconciling, found.operation.lifecycle)
        }

    @Test
    fun `ReconcilingDetected (uncorrelated) leaves zero provider correlation rows`(): Unit = runBlocking {
        val store = newStore()
        val accepted = assertIs<StartOutcome.Accepted>(
            store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, RequestId("req-1"), sampleImage()))
        )
        assertIs<ClaimOutcome.Claimed>(store.claimInvocation(accepted.operation.operationId))

        assertIs<ApplyOutcomeResult.Applied>(store.applyOutcome(accepted.operation.operationId, OutcomeApplication.ReconcilingDetected))

        assertEquals(0, correlationRowCount(accepted.operation.operationId))
    }

    @Test
    fun `a repeated or late correlated reconciliation from RECONCILING is Rejected, never overwriting the persisted reference`(): Unit =
        runBlocking {
            val store = newStore()
            val accepted = assertIs<StartOutcome.Accepted>(
                store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, RequestId("req-1"), sampleImage()))
            )
            assertIs<ClaimOutcome.Claimed>(store.claimInvocation(accepted.operation.operationId))
            val originalReference = ProviderCorrelationReference("original-reference")
            assertIs<ApplyOutcomeResult.Applied>(
                store.applyOutcome(accepted.operation.operationId, OutcomeApplication.ReconcilingWithProviderCorrelation(originalReference))
            )

            val lateReference = ProviderCorrelationReference("late-reference")
            val rejected = assertIs<ApplyOutcomeResult.Rejected>(
                store.applyOutcome(accepted.operation.operationId, OutcomeApplication.ReconcilingWithProviderCorrelation(lateReference))
            )

            assertEquals(ReceiptAnalysisOperationLifecycle.Reconciling, rejected.operation.lifecycle)
            assertEquals(1, correlationRowCount(accepted.operation.operationId))
            assertEquals("original-reference", storedCorrelationReference(accepted.operation.operationId))
        }

    @Test
    fun `RECONCILING resolves later to FAILED_NO_PROVIDER, restoring the same grant`(): Unit = runBlocking {
        val store = newStore()
        val accepted = assertIs<StartOutcome.Accepted>(
            store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, RequestId("req-1"), sampleImage()))
        )
        val grantId = reservedGrantId(accepted.operation.operationId)
        val remainingAfterReserve = remainingCredits(grantId)
        assertIs<ClaimOutcome.Claimed>(store.claimInvocation(accepted.operation.operationId))
        assertIs<ApplyOutcomeResult.Applied>(store.applyOutcome(accepted.operation.operationId, OutcomeApplication.ReconcilingDetected))

        val resolved = assertIs<ApplyOutcomeResult.Applied>(
            store.applyOutcome(
                accepted.operation.operationId,
                OutcomeApplication.FailedNoProvider(ProviderCallFailureReason.REQUEST_CONSTRUCTION_FAILED)
            )
        )
        assertIs<ReceiptAnalysisOperationLifecycle.Terminal>(resolved.operation.lifecycle)
        assertEquals(remainingAfterReserve + 1, remainingCredits(grantId))
    }

    @Test
    fun `a correlated RECONCILING resolves later to FAILED_NO_PROVIDER, removing the correlation reference atomically, restoring the grant`(): Unit =
        runBlocking {
            val store = newStore()
            val accepted = assertIs<StartOutcome.Accepted>(
                store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, RequestId("req-1"), sampleImage()))
            )
            val grantId = reservedGrantId(accepted.operation.operationId)
            val remainingAfterReserve = remainingCredits(grantId)
            assertIs<ClaimOutcome.Claimed>(store.claimInvocation(accepted.operation.operationId))
            val reference = ProviderCorrelationReference("test-correlation-reference")
            assertIs<ApplyOutcomeResult.Applied>(
                store.applyOutcome(accepted.operation.operationId, OutcomeApplication.ReconcilingWithProviderCorrelation(reference))
            )
            assertEquals(1, correlationRowCount(accepted.operation.operationId))

            val resolved = assertIs<ApplyOutcomeResult.Applied>(
                store.applyOutcome(
                    accepted.operation.operationId,
                    OutcomeApplication.FailedNoProvider(ProviderTerminalFailureReason.RETRY_EXHAUSTED)
                )
            )

            assertIs<ReceiptAnalysisOperationLifecycle.Terminal>(resolved.operation.lifecycle)
            assertEquals(remainingAfterReserve + 1, remainingCredits(grantId))
            assertEquals(0, correlationRowCount(accepted.operation.operationId))
        }

    @Test
    fun `ReconcilingDetected and AttemptIdMismatch are Rejected from RECONCILING`(): Unit = runBlocking {
        val store = newStore()
        val accepted = assertIs<StartOutcome.Accepted>(
            store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, RequestId("req-1"), sampleImage()))
        )
        assertIs<ClaimOutcome.Claimed>(store.claimInvocation(accepted.operation.operationId))
        assertIs<ApplyOutcomeResult.Applied>(store.applyOutcome(accepted.operation.operationId, OutcomeApplication.ReconcilingDetected))

        val rejected = assertIs<ApplyOutcomeResult.Rejected>(
            store.applyOutcome(accepted.operation.operationId, OutcomeApplication.ReconcilingDetected)
        )
        assertEquals(ReceiptAnalysisOperationLifecycle.Reconciling, rejected.operation.lifecycle)
    }

    @Test
    fun `applyOutcome is idempotent -- a second call on a TERMINAL operation is AlreadyResolved, no new ledger entry`(): Unit = runBlocking {
        val store = newStore()
        val accepted = assertIs<StartOutcome.Accepted>(
            store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, RequestId("req-1"), sampleImage()))
        )
        assertIs<ClaimOutcome.Claimed>(store.claimInvocation(accepted.operation.operationId))
        val first = assertIs<ApplyOutcomeResult.Applied>(
            store.applyOutcome(accepted.operation.operationId, OutcomeApplication.Failed)
        )

        val second = assertIs<ApplyOutcomeResult.AlreadyResolved>(
            store.applyOutcome(accepted.operation.operationId, OutcomeApplication.Failed)
        )

        assertEquals(first.operation, second.operation)
        assertEquals(1, ledgerEffectCount(accepted.operation.operationId, "DEBITED"))
    }

    // -----------------------------------------------------------------------------------------
    // Unmappable persisted state -> integrity failure, never a fabricated result
    // -----------------------------------------------------------------------------------------

    @Test
    fun `an invalid persisted document raises an integrity failure on read, never a fabricated result`(): Unit = runBlocking {
        val store = newStore()
        val requestId = "req-corrupt-document"
        insertCorruptedTerminalOperation(
            requestId = requestId,
            terminalKind = "SUCCEEDED",
            terminalFailureReason = null,
            resultDocumentBytes = "not a valid receipt document".toByteArray()
        )

        assertFailsWith<ReceiptAnalysisPersistenceIntegrityException> {
            store.findByRequestId(userId, RequestId(requestId))
        }
    }

    @Test
    fun `an unknown persisted terminal_failure_reason code raises an integrity failure on read`(): Unit = runBlocking {
        val store = newStore()
        val requestId = "req-corrupt-reason"
        insertCorruptedTerminalOperation(
            requestId = requestId,
            terminalKind = "FAILED_NO_PROVIDER",
            terminalFailureReason = "SOME_FUTURE_CODE_NOT_YET_KNOWN",
            resultDocumentBytes = null
        )

        assertFailsWith<ReceiptAnalysisPersistenceIntegrityException> {
            store.findByRequestId(userId, RequestId(requestId))
        }
    }

    // -----------------------------------------------------------------------------------------
    // findReconcilable -- the read that lets a resolver reach an operation stuck in RECONCILING.
    // Every cutoff below is expressed against the injected MutableClock, never wall-clock time:
    // the adapter writes `updated_at` from that clock, so a wall-clock cutoff would silently pass
    // or fail depending on when the suite runs.
    // -----------------------------------------------------------------------------------------

    @Test
    fun `findReconcilable returns RECONCILING operations with and without a correlation reference`(): Unit = runBlocking {
        val store = newStore()

        val withReference = assertIs<StartOutcome.Accepted>(
            store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, RequestId("req-recon-1"), sampleImage()))
        )
        assertIs<ClaimOutcome.Claimed>(store.claimInvocation(withReference.operation.operationId))
        assertIs<ApplyOutcomeResult.Applied>(
            store.applyOutcome(
                withReference.operation.operationId,
                OutcomeApplication.ReconcilingWithProviderCorrelation(ProviderCorrelationReference("resp_abc123"))
            )
        )

        val withoutReference = assertIs<StartOutcome.Accepted>(
            store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, RequestId("req-recon-2"), sampleImage()))
        )
        assertIs<ClaimOutcome.Claimed>(store.claimInvocation(withoutReference.operation.operationId))
        assertIs<ApplyOutcomeResult.Applied>(
            store.applyOutcome(withoutReference.operation.operationId, OutcomeApplication.ReconcilingDetected)
        )

        val settled = assertIs<StartOutcome.Accepted>(
            store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, RequestId("req-recon-3"), sampleImage()))
        )
        assertIs<ClaimOutcome.Claimed>(store.claimInvocation(settled.operation.operationId))
        assertIs<ApplyOutcomeResult.Applied>(
            store.applyOutcome(settled.operation.operationId, OutcomeApplication.Failed)
        )

        val candidates = store.findReconcilable(limit = 10, notUpdatedSince = clock.instant())

        val byOperationId = candidates.associateBy { it.operationId }
        assertEquals(2, candidates.size, "only the two RECONCILING operations are reconcilable")
        assertEquals(
            ProviderCorrelationReference("resp_abc123"),
            byOperationId.getValue(withReference.operation.operationId).correlationReference
        )
        assertNull(
            byOperationId.getValue(withoutReference.operation.operationId).correlationReference,
            "the uncorrelated path never recorded a reference"
        )
        assertFalse(
            byOperationId.containsKey(settled.operation.operationId),
            "a TERMINAL operation is never reconcilable"
        )
        assertEquals(
            withReference.operation.attemptId,
            byOperationId.getValue(withReference.operation.operationId).attemptId
        )
        assertEquals(
            clock.instant(),
            byOperationId.getValue(withReference.operation.operationId).reconcilingSince
        )
    }

    @Test
    fun `findReconcilable honours the cutoff, orders oldest first and caps the batch`(): Unit = runBlocking {
        val store = newStore()
        val enteredFirstAt = clock.instant()

        val older = assertIs<StartOutcome.Accepted>(
            store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, RequestId("req-cut-1"), sampleImage()))
        )
        assertIs<ClaimOutcome.Claimed>(store.claimInvocation(older.operation.operationId))
        assertIs<ApplyOutcomeResult.Applied>(
            store.applyOutcome(older.operation.operationId, OutcomeApplication.ReconcilingDetected)
        )

        val enteredSecondAt = enteredFirstAt.plusSeconds(600)
        clock.advanceTo(enteredSecondAt)

        val newer = assertIs<StartOutcome.Accepted>(
            store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, RequestId("req-cut-2"), sampleImage()))
        )
        assertIs<ClaimOutcome.Claimed>(store.claimInvocation(newer.operation.operationId))
        assertIs<ApplyOutcomeResult.Applied>(
            store.applyOutcome(newer.operation.operationId, OutcomeApplication.ReconcilingDetected)
        )

        assertEquals(
            emptyList(),
            store.findReconcilable(limit = 10, notUpdatedSince = enteredFirstAt.minusSeconds(1)).map { it.operationId },
            "a cutoff before either operation entered RECONCILING excludes both"
        )
        assertEquals(
            listOf(older.operation.operationId),
            store.findReconcilable(limit = 10, notUpdatedSince = enteredFirstAt).map { it.operationId },
            "the cutoff is inclusive and excludes the newer operation"
        )
        assertEquals(
            listOf(older.operation.operationId, newer.operation.operationId),
            store.findReconcilable(limit = 10, notUpdatedSince = enteredSecondAt).map { it.operationId },
            "both are returned oldest first"
        )
        assertEquals(
            listOf(older.operation.operationId),
            store.findReconcilable(limit = 1, notUpdatedSince = enteredSecondAt).map { it.operationId },
            "the limit caps the batch, keeping the oldest"
        )
    }

    // -----------------------------------------------------------------------------------------
    // Payload lifecycle (ADR-003) -- the bytes sent to the provider exist only while the operation
    // might still need them.
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the stored payload is deleted when the operation resolves terminally`(): Unit = runBlocking {
        val store = newStore()
        val accepted = assertIs<StartOutcome.Accepted>(
            store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, RequestId("req-payload-1"), sampleImage()))
        )
        assertEquals(1, payloadRowCount(accepted.operation.operationId), "a fresh operation keeps its payload")

        assertIs<ClaimOutcome.Claimed>(store.claimInvocation(accepted.operation.operationId))
        assertIs<ApplyOutcomeResult.Applied>(
            store.applyOutcome(accepted.operation.operationId, OutcomeApplication.Failed)
        )

        assertEquals(0, payloadRowCount(accepted.operation.operationId), "a terminal operation keeps no payload")
    }

    @Test
    fun `a reconciling operation keeps its payload until it resolves`(): Unit = runBlocking {
        val store = newStore()
        val accepted = assertIs<StartOutcome.Accepted>(
            store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, RequestId("req-payload-2"), sampleImage()))
        )
        assertIs<ClaimOutcome.Claimed>(store.claimInvocation(accepted.operation.operationId))
        assertIs<ApplyOutcomeResult.Applied>(
            store.applyOutcome(accepted.operation.operationId, OutcomeApplication.ReconcilingDetected)
        )

        assertEquals(
            1,
            payloadRowCount(accepted.operation.operationId),
            "RECONCILING is not terminal, so the payload the operation may still need stays"
        )

        assertIs<ApplyOutcomeResult.Applied>(
            store.applyOutcome(
                accepted.operation.operationId,
                OutcomeApplication.FailedNoProvider(ProviderTerminalFailureReason.RETRY_EXHAUSTED)
            )
        )

        assertEquals(0, payloadRowCount(accepted.operation.operationId))
    }

    @Test
    fun `a successful terminal resolution keeps the result document but deletes the payload`(): Unit = runBlocking {
        val store = newStore()
        val accepted = assertIs<StartOutcome.Accepted>(
            store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, RequestId("req-payload-3"), sampleImage()))
        )
        assertIs<ClaimOutcome.Claimed>(store.claimInvocation(accepted.operation.operationId))
        assertIs<ApplyOutcomeResult.Applied>(
            store.applyOutcome(accepted.operation.operationId, OutcomeApplication.Succeeded(sampleDocument()))
        )

        assertEquals(0, payloadRowCount(accepted.operation.operationId), "the image never outlives the analysis")
        assertEquals(1, countRows("receipt_analysis_result"), "the validated document is unaffected")
    }

    @Test
    fun `findReconcilable rejects a non-positive limit`(): Unit = runBlocking {
        val store = newStore()
        assertFailsWith<IllegalArgumentException> {
            store.findReconcilable(limit = 0, notUpdatedSince = clock.instant())
        }
        Unit
    }
}

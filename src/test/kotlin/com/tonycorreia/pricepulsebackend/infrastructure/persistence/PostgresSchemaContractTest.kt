package com.tonycorreia.pricepulsebackend.infrastructure.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Proves the migration in src/main/resources/db/migration/V1__... by SQL against a real
 * PostgreSQL, per receiptanalysis-slice-report.md 6.10.22/6.10.24. No Kotlin adapter under test --
 * only raw JDBC against the schema itself. Never H2/SQLite: this is exactly the engine (and its
 * DEFERRABLE constraint trigger semantics) the design depends on.
 */
@Testcontainers
class PostgresSchemaContractTest {

    private class TestPostgresContainer(image: String) : PostgreSQLContainer<TestPostgresContainer>(image)

    companion object {
        @Container
        @JvmStatic
        private val postgres = TestPostgresContainer("postgres:16-alpine")

        private lateinit var connection: Connection

        @BeforeAll
        @JvmStatic
        fun migrateAndConnect() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .load()
                .migrate()

            connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
            connection.autoCommit = false
        }

        @AfterAll
        @JvmStatic
        fun closeConnection() {
            connection.close()
        }
    }

    @BeforeEach
    fun resetTables() {
        // Rolls back anything left open by a failed commit in the previous test, then TRUNCATE --
        // the only statement that bypasses the FOR EACH ROW immutability triggers, giving full
        // isolation between tests without disabling any trigger.
        connection.rollback()
        connection.createStatement().use { statement ->
            statement.execute(
                "TRUNCATE credit_ledger_entry, receipt_analysis_result, receipt_analysis_payload, " +
                    "receipt_analysis_provider_correlation, receipt_analysis_operation, credit_grant CASCADE"
            )
        }
        connection.commit()
    }

    // -----------------------------------------------------------------------------------------
    // Helpers -- keep remaining_credits in sync with the ledger equation so the "happy path"
    // helpers genuinely satisfy validate_credit_grant_invariants.
    // -----------------------------------------------------------------------------------------

    private fun now(): Timestamp = Timestamp.from(Instant.now())

    private fun insertGrantWithEvent(
        grantId: UUID = UUID.randomUUID(),
        userId: String = "user-1",
        source: String = "FREE_MONTHLY",
        period: String? = "2026-08",
        purchaseReference: String? = null,
        creditsGranted: Int = 10
    ): UUID {
        connection.prepareStatement(
            "INSERT INTO credit_grant (grant_id, user_id, source, period, purchase_reference, " +
                "credits_granted, remaining_credits, granted_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        ).use { statement ->
            statement.setObject(1, grantId)
            statement.setString(2, userId)
            statement.setString(3, source)
            statement.setString(4, period)
            statement.setString(5, purchaseReference)
            statement.setInt(6, creditsGranted)
            statement.setInt(7, creditsGranted)
            statement.setTimestamp(8, now())
            statement.executeUpdate()
        }
        insertLedgerEntry(grantId = grantId, operationId = null, userId = userId, effect = "GRANTED")
        return grantId
    }

    private fun insertLedgerEntry(
        entryId: UUID = UUID.randomUUID(),
        grantId: UUID,
        operationId: UUID?,
        userId: String,
        effect: String
    ): UUID {
        connection.prepareStatement(
            "INSERT INTO credit_ledger_entry (entry_id, grant_id, operation_id, user_id, effect, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)"
        ).use { statement ->
            statement.setObject(1, entryId)
            statement.setObject(2, grantId)
            statement.setObject(3, operationId)
            statement.setString(4, userId)
            statement.setString(5, effect)
            statement.setTimestamp(6, now())
            statement.executeUpdate()
        }
        return entryId
    }

    private fun updateRemainingCredits(grantId: UUID, remainingCredits: Int) {
        connection.prepareStatement("UPDATE credit_grant SET remaining_credits = ? WHERE grant_id = ?").use { statement ->
            statement.setInt(1, remainingCredits)
            statement.setObject(2, grantId)
            statement.executeUpdate()
        }
    }

    private fun insertOperation(
        operationId: UUID = UUID.randomUUID(),
        userId: String = "user-1",
        requestId: String = UUID.randomUUID().toString(),
        contentHash: String = "a".repeat(64),
        lifecycleState: String = "RECEIVED",
        terminalKind: String? = null,
        terminalFailureReason: String? = null,
        tombstonedAt: Timestamp? = null
    ): UUID {
        connection.prepareStatement(
            "INSERT INTO receipt_analysis_operation (operation_id, user_id, request_id, content_hash, " +
                "size_bytes, mime_type, attempt_id, lifecycle_state, terminal_kind, terminal_failure_reason, " +
                "tombstoned_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        ).use { statement ->
            statement.setObject(1, operationId)
            statement.setString(2, userId)
            statement.setString(3, requestId)
            statement.setString(4, contentHash)
            statement.setLong(5, 1024L)
            statement.setString(6, "image/jpeg")
            statement.setObject(7, UUID.randomUUID())
            statement.setString(8, lifecycleState)
            statement.setString(9, terminalKind)
            statement.setString(10, terminalFailureReason)
            statement.setTimestamp(11, tombstonedAt)
            statement.setTimestamp(12, now())
            statement.setTimestamp(13, now())
            statement.executeUpdate()
        }
        return operationId
    }

    private fun insertPayload(operationId: UUID) {
        connection.prepareStatement(
            "INSERT INTO receipt_analysis_payload (operation_id, payload_bytes, stored_at) VALUES (?, ?, ?)"
        ).use { statement ->
            statement.setObject(1, operationId)
            statement.setBytes(2, byteArrayOf(1, 2, 3))
            statement.setTimestamp(3, now())
            statement.executeUpdate()
        }
    }

    private fun insertResult(operationId: UUID) {
        connection.prepareStatement(
            "INSERT INTO receipt_analysis_result (operation_id, document, stored_at) VALUES (?, ?, ?)"
        ).use { statement ->
            statement.setObject(1, operationId)
            statement.setBytes(2, byteArrayOf(9, 9, 9))
            statement.setTimestamp(3, now())
            statement.executeUpdate()
        }
    }

    /** Reserves credit for a brand-new, non-terminal operation with a payload -- the only shape
     * that satisfies every deferred invariant on its own, so tests build on top of it. */
    private fun reserveNewOperation(grantId: UUID, userId: String = "user-1"): UUID {
        val operationId = insertOperation(userId = userId, lifecycleState = "RECEIVED")
        insertPayload(operationId)
        insertLedgerEntry(grantId = grantId, operationId = operationId, userId = userId, effect = "RESERVED")
        updateRemainingCredits(grantId, remainingCreditsAfterReserve(grantId))
        return operationId
    }

    private fun remainingCreditsAfterReserve(grantId: UUID): Int {
        connection.prepareStatement("SELECT credits_granted, remaining_credits FROM credit_grant WHERE grant_id = ?").use { statement ->
            statement.setObject(1, grantId)
            statement.executeQuery().use { rs ->
                rs.next()
                return rs.getInt("remaining_credits") - 1
            }
        }
    }

    private fun terminate(
        operationId: UUID,
        grantId: UUID,
        userId: String,
        terminalKind: String,
        effect: String,
        failureReason: String? = null
    ) {
        connection.prepareStatement(
            "UPDATE receipt_analysis_operation SET lifecycle_state = 'TERMINAL', terminal_kind = ?, " +
                "terminal_failure_reason = ?, updated_at = ? WHERE operation_id = ?"
        ).use { statement ->
            statement.setString(1, terminalKind)
            statement.setString(2, failureReason)
            statement.setTimestamp(3, now())
            statement.setObject(4, operationId)
            statement.executeUpdate()
        }
        insertLedgerEntry(grantId = grantId, operationId = operationId, userId = userId, effect = effect)
        if (effect == "RELEASED") {
            connection.prepareStatement("SELECT remaining_credits FROM credit_grant WHERE grant_id = ?").use { statement ->
                statement.setObject(1, grantId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    updateRemainingCredits(grantId, rs.getInt("remaining_credits") + 1)
                }
            }
        }
    }

    private fun tombstone(operationId: UUID) {
        connection.prepareStatement("UPDATE receipt_analysis_operation SET tombstoned_at = ? WHERE operation_id = ?").use { statement ->
            statement.setTimestamp(1, now())
            statement.setObject(2, operationId)
            statement.executeUpdate()
        }
    }

    private fun setLifecycleState(operationId: UUID, lifecycleState: String) {
        connection.prepareStatement(
            "UPDATE receipt_analysis_operation SET lifecycle_state = ?, updated_at = ? WHERE operation_id = ?"
        ).use { statement ->
            statement.setString(1, lifecycleState)
            statement.setTimestamp(2, now())
            statement.setObject(3, operationId)
            statement.executeUpdate()
        }
    }

    private fun insertProviderCorrelation(operationId: UUID, correlationReference: String = "opaque-reference-1") {
        connection.prepareStatement(
            "INSERT INTO receipt_analysis_provider_correlation (operation_id, correlation_reference, recorded_at) VALUES (?, ?, ?)"
        ).use { statement ->
            statement.setObject(1, operationId)
            statement.setString(2, correlationReference)
            statement.setTimestamp(3, now())
            statement.executeUpdate()
        }
    }

    private fun commitFails(block: () -> Unit) {
        block()
        assertFailsWith<SQLException> { connection.commit() }
        connection.rollback()
    }

    // -----------------------------------------------------------------------------------------
    // Uniqueness / idempotency
    // -----------------------------------------------------------------------------------------

    @Test
    fun `duplicate (user_id, request_id) is rejected`() {
        val grantId = insertGrantWithEvent()
        val requestId = "req-1"
        val operationId = insertOperation(userId = "user-1", requestId = requestId)
        insertPayload(operationId)
        insertLedgerEntry(grantId = grantId, operationId = operationId, userId = "user-1", effect = "RESERVED")
        updateRemainingCredits(grantId, remainingCreditsAfterReserve(grantId))
        connection.commit()

        assertFailsWith<SQLException> {
            insertOperation(userId = "user-1", requestId = requestId)
        }
        connection.rollback()
    }

    @Test
    fun `two free-monthly grants for the same user and period are rejected`() {
        insertGrantWithEvent(userId = "user-1", source = "FREE_MONTHLY", period = "2026-08")
        connection.commit()

        assertFailsWith<SQLException> {
            insertGrantWithEvent(userId = "user-1", source = "FREE_MONTHLY", period = "2026-08")
        }
        connection.rollback()
    }

    @Test
    fun `two purchased grants with the same purchase reference are rejected`() {
        insertGrantWithEvent(source = "PURCHASED", period = null, purchaseReference = "order-1")
        connection.commit()

        assertFailsWith<SQLException> {
            insertGrantWithEvent(source = "PURCHASED", period = null, purchaseReference = "order-1")
        }
        connection.rollback()
    }

    // -----------------------------------------------------------------------------------------
    // GRANTED correctness
    // -----------------------------------------------------------------------------------------

    @Test
    fun `grant without a GRANTED ledger entry fails at commit`() {
        commitFails {
            connection.prepareStatement(
                "INSERT INTO credit_grant (grant_id, user_id, source, period, purchase_reference, " +
                    "credits_granted, remaining_credits, granted_at) VALUES (?, ?, 'FREE_MONTHLY', '2026-08', NULL, 10, 10, ?)"
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setString(2, "user-1")
                statement.setTimestamp(3, now())
                statement.executeUpdate()
            }
        }
    }

    @Test
    fun `grant with its GRANTED ledger entry in the same transaction commits`() {
        insertGrantWithEvent()
        connection.commit()
    }

    // -----------------------------------------------------------------------------------------
    // Reservation / effect pairing
    // -----------------------------------------------------------------------------------------

    @Test
    fun `operation without a RESERVED ledger entry fails at commit`() {
        commitFails {
            insertOperation()
        }
    }

    @Test
    fun `operation with its RESERVED ledger entry and payload commits`() {
        val grantId = insertGrantWithEvent()
        reserveNewOperation(grantId)
        connection.commit()
    }

    @Test
    fun `remaining_credits out of sync with the ledger fails at commit`() {
        val grantId = insertGrantWithEvent(creditsGranted = 10)
        connection.commit()

        commitFails {
            val operationId = insertOperation()
            insertPayload(operationId)
            insertLedgerEntry(grantId = grantId, operationId = operationId, userId = "user-1", effect = "RESERVED")
            // Deliberately NOT decrementing remaining_credits -- still 10, should be 9.
        }
    }

    // -----------------------------------------------------------------------------------------
    // Terminal correspondence
    // -----------------------------------------------------------------------------------------

    @Test
    fun `SUCCEEDED paired with DEBITED commits`() {
        val grantId = insertGrantWithEvent()
        val operationId = reserveNewOperation(grantId)
        terminate(operationId, grantId, "user-1", terminalKind = "SUCCEEDED", effect = "DEBITED")
        insertResult(operationId)
        connection.commit()
    }

    @Test
    fun `SUCCEEDED paired with RELEASED fails at commit`() {
        val grantId = insertGrantWithEvent()
        val operationId = reserveNewOperation(grantId)
        commitFails {
            terminate(operationId, grantId, "user-1", terminalKind = "SUCCEEDED", effect = "RELEASED")
        }
    }

    @Test
    fun `FAILED_NO_PROVIDER paired with RELEASED commits`() {
        val grantId = insertGrantWithEvent()
        val operationId = reserveNewOperation(grantId)
        terminate(
            operationId, grantId, "user-1",
            terminalKind = "FAILED_NO_PROVIDER", effect = "RELEASED", failureReason = "provider_unavailable"
        )
        connection.commit()
    }

    @Test
    fun `FAILED_NO_PROVIDER paired with DEBITED fails at commit`() {
        val grantId = insertGrantWithEvent()
        val operationId = reserveNewOperation(grantId)
        commitFails {
            terminate(
                operationId, grantId, "user-1",
                terminalKind = "FAILED_NO_PROVIDER", effect = "DEBITED", failureReason = "provider_unavailable"
            )
        }
    }

    @Test
    fun `terminal ledger entry with a different grant_id than the RESERVED entry fails at commit`() {
        val grantId = insertGrantWithEvent()
        val otherGrantId = insertGrantWithEvent(period = "2026-09")
        val operationId = reserveNewOperation(grantId)
        commitFails {
            connection.prepareStatement(
                "UPDATE receipt_analysis_operation SET lifecycle_state = 'TERMINAL', terminal_kind = 'SUCCEEDED', updated_at = ? WHERE operation_id = ?"
            ).use { statement ->
                statement.setTimestamp(1, now())
                statement.setObject(2, operationId)
                statement.executeUpdate()
            }
            insertResult(operationId)
            insertLedgerEntry(grantId = otherGrantId, operationId = operationId, userId = "user-1", effect = "DEBITED")
        }
    }

    // -----------------------------------------------------------------------------------------
    // Result / tombstone rules
    // -----------------------------------------------------------------------------------------

    @Test
    fun `result for a non-terminal operation fails at commit`() {
        val grantId = insertGrantWithEvent()
        val operationId = reserveNewOperation(grantId)
        commitFails {
            insertResult(operationId)
        }
    }

    @Test
    fun `result for a terminal, non-tombstoned operation commits`() {
        val grantId = insertGrantWithEvent()
        val operationId = reserveNewOperation(grantId)
        terminate(operationId, grantId, "user-1", terminalKind = "SUCCEEDED", effect = "DEBITED")
        insertResult(operationId)
        connection.commit()
    }

    @Test
    fun `SUCCEEDED without a result fails at commit`() {
        val grantId = insertGrantWithEvent()
        val operationId = reserveNewOperation(grantId)
        commitFails {
            terminate(operationId, grantId, "user-1", terminalKind = "SUCCEEDED", effect = "DEBITED")
        }
    }

    @Test
    fun `result present for a FAILED operation fails at commit`() {
        val grantId = insertGrantWithEvent()
        val operationId = reserveNewOperation(grantId)
        terminate(operationId, grantId, "user-1", terminalKind = "FAILED", effect = "DEBITED")
        commitFails {
            insertResult(operationId)
        }
    }

    @Test
    fun `result present for a FAILED_NO_PROVIDER operation fails at commit`() {
        val grantId = insertGrantWithEvent()
        val operationId = reserveNewOperation(grantId)
        terminate(
            operationId, grantId, "user-1",
            terminalKind = "FAILED_NO_PROVIDER", effect = "RELEASED", failureReason = "provider_unavailable"
        )
        commitFails {
            insertResult(operationId)
        }
    }

    @Test
    fun `deleting a SUCCEEDED operation's result without tombstoning is rejected`() {
        val grantId = insertGrantWithEvent()
        val operationId = reserveNewOperation(grantId)
        terminate(operationId, grantId, "user-1", terminalKind = "SUCCEEDED", effect = "DEBITED")
        insertResult(operationId)
        connection.commit()

        assertFailsWith<SQLException> {
            connection.createStatement().execute("DELETE FROM receipt_analysis_result WHERE operation_id = '$operationId'")
        }
        connection.rollback()
    }

    @Test
    fun `tombstoning a SUCCEEDED operation removes its result and payload in the same transaction`() {
        val grantId = insertGrantWithEvent()
        val operationId = reserveNewOperation(grantId)
        terminate(operationId, grantId, "user-1", terminalKind = "SUCCEEDED", effect = "DEBITED")
        insertResult(operationId)
        connection.commit()
        // Committed state: TERMINAL SUCCEEDED, not tombstoned, exactly one result row and exactly
        // one payload row (from reserveNewOperation, never touched) -- both still active. The
        // tombstone transaction below is the only place either is removed.

        tombstone(operationId)
        connection.createStatement().execute("DELETE FROM receipt_analysis_result WHERE operation_id = '$operationId'")
        connection.createStatement().execute("DELETE FROM receipt_analysis_payload WHERE operation_id = '$operationId'")
        connection.commit()

        connection.prepareStatement("SELECT count(*) FROM receipt_analysis_result WHERE operation_id = ?").use { statement ->
            statement.setObject(1, operationId)
            statement.executeQuery().use { rs ->
                rs.next()
                assertEquals(0, rs.getInt(1))
            }
        }
        connection.prepareStatement("SELECT count(*) FROM receipt_analysis_payload WHERE operation_id = ?").use { statement ->
            statement.setObject(1, operationId)
            statement.executeQuery().use { rs ->
                rs.next()
                assertEquals(0, rs.getInt(1))
            }
        }
    }

    @Test
    fun `SUCCEEDED with a failure reason is rejected immediately`() {
        val grantId = insertGrantWithEvent()
        val operationId = reserveNewOperation(grantId)
        assertFailsWith<SQLException> {
            terminate(operationId, grantId, "user-1", terminalKind = "SUCCEEDED", effect = "DEBITED", failureReason = "not allowed")
        }
        connection.rollback()
    }

    @Test
    fun `FAILED with a failure reason is rejected immediately`() {
        val grantId = insertGrantWithEvent()
        val operationId = reserveNewOperation(grantId)
        assertFailsWith<SQLException> {
            terminate(operationId, grantId, "user-1", terminalKind = "FAILED", effect = "DEBITED", failureReason = "not allowed")
        }
        connection.rollback()
    }

    @Test
    fun `FAILED_NO_PROVIDER without a failure reason is rejected immediately`() {
        val grantId = insertGrantWithEvent()
        val operationId = reserveNewOperation(grantId)
        assertFailsWith<SQLException> {
            terminate(operationId, grantId, "user-1", terminalKind = "FAILED_NO_PROVIDER", effect = "RELEASED")
        }
        connection.rollback()
    }

    @Test
    fun `FAILED_NO_PROVIDER with a failure reason is accepted`() {
        val grantId = insertGrantWithEvent()
        val operationId = reserveNewOperation(grantId)
        terminate(
            operationId, grantId, "user-1",
            terminalKind = "FAILED_NO_PROVIDER", effect = "RELEASED", failureReason = "provider_unavailable"
        )
        connection.commit()
    }

    // -----------------------------------------------------------------------------------------
    // Active payload rules
    // -----------------------------------------------------------------------------------------

    @Test
    fun `non-terminal operation without a payload fails at commit`() {
        val grantId = insertGrantWithEvent()
        commitFails {
            val operationId = insertOperation()
            insertLedgerEntry(grantId = grantId, operationId = operationId, userId = "user-1", effect = "RESERVED")
            updateRemainingCredits(grantId, remainingCreditsAfterReserve(grantId))
        }
    }

    @Test
    fun `terminal operation may have zero payload rows`() {
        val grantId = insertGrantWithEvent()
        val operationId = reserveNewOperation(grantId)
        terminate(operationId, grantId, "user-1", terminalKind = "SUCCEEDED", effect = "DEBITED")
        insertResult(operationId)
        connection.prepareStatement("DELETE FROM receipt_analysis_payload WHERE operation_id = ?").use { statement ->
            statement.setObject(1, operationId)
            statement.executeUpdate()
        }
        connection.commit()
    }

    @Test
    fun `payload insert for a tombstoned operation is rejected immediately`() {
        val grantId = insertGrantWithEvent()
        val operationId = reserveNewOperation(grantId)
        terminate(operationId, grantId, "user-1", terminalKind = "SUCCEEDED", effect = "DEBITED")
        connection.prepareStatement("DELETE FROM receipt_analysis_payload WHERE operation_id = ?").use { statement ->
            statement.setObject(1, operationId)
            statement.executeUpdate()
        }
        connection.prepareStatement("UPDATE receipt_analysis_operation SET tombstoned_at = ? WHERE operation_id = ?").use { statement ->
            statement.setTimestamp(1, now())
            statement.setObject(2, operationId)
            statement.executeUpdate()
        }

        assertFailsWith<SQLException> { insertPayload(operationId) }
        connection.rollback()
    }

    // -----------------------------------------------------------------------------------------
    // Forbidden mutations
    // -----------------------------------------------------------------------------------------

    @Test
    fun `updating a credit_ledger_entry is rejected`() {
        val grantId = insertGrantWithEvent()
        connection.commit()

        assertFailsWith<SQLException> {
            connection.createStatement().execute("UPDATE credit_ledger_entry SET effect = 'RESERVED' WHERE grant_id = '$grantId'")
        }
        connection.rollback()
    }

    @Test
    fun `deleting a credit_ledger_entry is rejected`() {
        val grantId = insertGrantWithEvent()
        connection.commit()

        assertFailsWith<SQLException> {
            connection.createStatement().execute("DELETE FROM credit_ledger_entry WHERE grant_id = '$grantId'")
        }
        connection.rollback()
    }

    @Test
    fun `changing a credit_grant field other than remaining_credits is rejected`() {
        val grantId = insertGrantWithEvent()
        connection.commit()

        assertFailsWith<SQLException> {
            connection.createStatement().execute("UPDATE credit_grant SET credits_granted = 999 WHERE grant_id = '$grantId'")
        }
        connection.rollback()
    }

    @Test
    fun `deleting a credit_grant is rejected`() {
        val grantId = insertGrantWithEvent()
        connection.commit()

        assertFailsWith<SQLException> {
            connection.createStatement().execute("DELETE FROM credit_grant WHERE grant_id = '$grantId'")
        }
        connection.rollback()
    }

    @Test
    fun `updating a receipt_analysis_result is rejected`() {
        val grantId = insertGrantWithEvent()
        val operationId = reserveNewOperation(grantId)
        terminate(operationId, grantId, "user-1", terminalKind = "SUCCEEDED", effect = "DEBITED")
        insertResult(operationId)
        connection.commit()

        assertFailsWith<SQLException> {
            connection.createStatement().execute("UPDATE receipt_analysis_result SET document = '\\x010203' WHERE operation_id = '$operationId'")
        }
        connection.rollback()
    }

    @Test
    fun `updating a receipt_analysis_payload is rejected`() {
        val grantId = insertGrantWithEvent()
        val operationId = reserveNewOperation(grantId)
        connection.commit()

        assertFailsWith<SQLException> {
            connection.createStatement().execute("UPDATE receipt_analysis_payload SET payload_bytes = '\\x0102' WHERE operation_id = '$operationId'")
        }
        connection.rollback()
    }

    @Test
    fun `deleting a receipt_analysis_payload before the operation is terminal is rejected`() {
        val grantId = insertGrantWithEvent()
        val operationId = reserveNewOperation(grantId)
        connection.commit()

        assertFailsWith<SQLException> {
            connection.createStatement().execute("DELETE FROM receipt_analysis_payload WHERE operation_id = '$operationId'")
        }
        connection.rollback()
    }

    @Test
    fun `deleting a receipt_analysis_payload once the operation is terminal commits`() {
        val grantId = insertGrantWithEvent()
        val operationId = reserveNewOperation(grantId)
        terminate(operationId, grantId, "user-1", terminalKind = "SUCCEEDED", effect = "DEBITED")
        insertResult(operationId)
        connection.createStatement().execute("DELETE FROM receipt_analysis_payload WHERE operation_id = '$operationId'")
        connection.commit()

        connection.prepareStatement("SELECT count(*) FROM receipt_analysis_payload WHERE operation_id = ?").use { statement ->
            statement.setObject(1, operationId)
            statement.executeQuery().use { rs ->
                rs.next()
                assertEquals(0, rs.getInt(1))
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // V2: receipt_analysis_provider_correlation -- internal-only opaque reference for a confirmed
    // pending (queued/in_progress) invocation. See receiptanalysis-slice-report.md 6.10.39.
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a RECONCILING operation may have zero provider correlation rows`() {
        val grantId = insertGrantWithEvent()
        val operationId = reserveNewOperation(grantId)
        setLifecycleState(operationId, "RECONCILING")

        connection.commit()
    }

    @Test
    fun `a RECONCILING operation may have one provider correlation row`() {
        val grantId = insertGrantWithEvent()
        val operationId = reserveNewOperation(grantId)
        setLifecycleState(operationId, "RECONCILING")
        insertProviderCorrelation(operationId)

        connection.commit()
    }

    @Test
    fun `inserting a provider correlation row for a RECEIVED operation is rejected immediately`() {
        val grantId = insertGrantWithEvent()
        val operationId = reserveNewOperation(grantId)

        assertFailsWith<SQLException> { insertProviderCorrelation(operationId) }
        connection.rollback()
    }

    @Test
    fun `inserting a provider correlation row for an INVOCATION_CLAIMED operation is rejected immediately`() {
        val grantId = insertGrantWithEvent()
        val operationId = reserveNewOperation(grantId)
        setLifecycleState(operationId, "INVOCATION_CLAIMED")
        connection.commit()

        assertFailsWith<SQLException> { insertProviderCorrelation(operationId) }
        connection.rollback()
    }

    @Test
    fun `inserting a provider correlation row for a tombstoned RECONCILING operation is rejected immediately`() {
        val grantId = insertGrantWithEvent()
        val operationId = reserveNewOperation(grantId)
        connection.commit()
        setLifecycleState(operationId, "RECONCILING")
        tombstone(operationId)
        // Deliberately not committed here -- the payload-vs-tombstone invariant (V1, deferred)
        // would reject this exact combination at commit time (a tombstoned operation must have
        // zero payload rows), but the correlation guard (V2, immediate) must reject the INSERT
        // attempt before that is ever reached, within this same still-open transaction.

        assertFailsWith<SQLException> { insertProviderCorrelation(operationId) }
        connection.rollback()
    }

    @Test
    fun `a provider correlation row is never updated`() {
        val grantId = insertGrantWithEvent()
        val operationId = reserveNewOperation(grantId)
        setLifecycleState(operationId, "RECONCILING")
        insertProviderCorrelation(operationId, "opaque-reference-original")
        connection.commit()

        assertFailsWith<SQLException> {
            connection.prepareStatement(
                "UPDATE receipt_analysis_provider_correlation SET correlation_reference = ? WHERE operation_id = ?"
            ).use { statement ->
                statement.setString(1, "opaque-reference-replaced")
                statement.setObject(2, operationId)
                statement.executeUpdate()
            }
        }
        connection.rollback()
    }

    @Test
    fun `deleting a provider correlation row while the operation is still RECONCILING is rejected immediately`() {
        val grantId = insertGrantWithEvent()
        val operationId = reserveNewOperation(grantId)
        setLifecycleState(operationId, "RECONCILING")
        insertProviderCorrelation(operationId)
        connection.commit()

        assertFailsWith<SQLException> {
            connection.createStatement().execute("DELETE FROM receipt_analysis_provider_correlation WHERE operation_id = '$operationId'")
        }
        connection.rollback()
    }

    @Test
    fun `deleting a provider correlation row once the operation is terminal commits`() {
        val grantId = insertGrantWithEvent()
        val operationId = reserveNewOperation(grantId)
        setLifecycleState(operationId, "RECONCILING")
        insertProviderCorrelation(operationId)
        connection.commit()

        terminate(operationId, grantId, "user-1", terminalKind = "FAILED_NO_PROVIDER", effect = "RELEASED", failureReason = "RETRY_EXHAUSTED")
        connection.createStatement().execute("DELETE FROM receipt_analysis_provider_correlation WHERE operation_id = '$operationId'")
        connection.commit()

        connection.prepareStatement("SELECT count(*) FROM receipt_analysis_provider_correlation WHERE operation_id = ?").use { statement ->
            statement.setObject(1, operationId)
            statement.executeQuery().use { rs ->
                rs.next()
                assertEquals(0, rs.getInt(1))
            }
        }
    }

    @Test
    fun `a TERMINAL operation with a leftover provider correlation row fails deferred validation at commit`() {
        val grantId = insertGrantWithEvent()
        val operationId = reserveNewOperation(grantId)
        setLifecycleState(operationId, "RECONCILING")
        insertProviderCorrelation(operationId)
        connection.commit()

        commitFails {
            // Deliberately NOT deleting the correlation row before terminating -- proves the
            // deferred invariant catches a reference left behind past RECONCILING, never silently
            // allowed to survive into a TERMINAL operation.
            terminate(operationId, grantId, "user-1", terminalKind = "FAILED_NO_PROVIDER", effect = "RELEASED", failureReason = "RETRY_EXHAUSTED")
        }
    }
}

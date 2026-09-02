package com.tonycorreia.pricepulsebackend.infrastructure.persistence

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.FailedNoProviderReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderCallFailureReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderCorrelationReference
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderRejectionReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderTerminalFailureReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisAttemptId
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ValidatedReceiptAnalysisResultV1
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ApplyOutcomeResult
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ClaimOutcome
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ContentHash
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.OutcomeApplication
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ReceiptAnalysisLedgerEffect
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ReceiptAnalysisOperation
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ReceiptAnalysisOperationId
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ReceiptAnalysisOperationLifecycle
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ReceiptAnalysisOperationResult
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ReceiptAnalysisOperationStore
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ReconciliationCandidate
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.RequestId
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.RequestLookup
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.StartOutcome
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.StartReceiptAnalysisOperationCommand
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.UserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * First production [ReceiptAnalysisOperationStore] -- real PostgreSQL persistence against the
 * schema in `db/migration/V1__receipt_analysis_operation_schema.sql`. Not wired to
 * `Application.kt`, does not create its own connection pool, does not run Flyway, does not read
 * environment/configuration -- [dataSource] arrives already configured, [clock] is the only
 * source of "now", and [monthlyFreeCredits] arrives already resolved from real server
 * configuration by the caller. See receiptanalysis-slice-report.md 6.10.27.
 */
class PostgresReceiptAnalysisOperationStore(
    private val dataSource: DataSource,
    private val clock: Clock,
    private val monthlyFreeCredits: Int
) : ReceiptAnalysisOperationStore {

    init {
        require(monthlyFreeCredits > 0) { "monthlyFreeCredits must be positive, was $monthlyFreeCredits" }
    }

    override suspend fun startOrGetExisting(command: StartReceiptAnalysisOperationCommand): StartOutcome =
        withContext(Dispatchers.IO) {
            val contentHash = ContentHash.sha256Of(command.image.bytes())
            try {
                withConnection { connection -> doStartOrGetExisting(connection, command, contentHash) }
            } catch (insufficientCredits: InsufficientCreditsSignal) {
                StartOutcome.InsufficientCredits
            }
        }

    override suspend fun findByRequestId(userId: UserId, requestId: RequestId): RequestLookup =
        withContext(Dispatchers.IO) {
            withConnection { connection ->
                val row = connection.prepareStatement(
                    "SELECT *, $RESULT_DOCUMENT_SUBQUERY AS result_document " +
                        "FROM receipt_analysis_operation WHERE user_id = ? AND request_id = ?"
                ).use { statement ->
                    statement.setString(1, userId.value)
                    statement.setString(2, requestId.value)
                    statement.executeQuery().use { rs -> if (rs.next()) rs.toOperationRow() else null }
                }

                when {
                    row == null -> RequestLookup.NotFound
                    row.tombstonedAt != null -> RequestLookup.Tombstoned
                    else -> RequestLookup.Found(row.toDomain())
                }
            }
        }

    override suspend fun claimInvocation(operationId: ReceiptAnalysisOperationId): ClaimOutcome =
        withContext(Dispatchers.IO) {
            withConnection { connection ->
                val now = nowTimestamp()
                val claimedRow = connection.prepareStatement(
                    "UPDATE receipt_analysis_operation SET lifecycle_state = 'INVOCATION_CLAIMED', updated_at = ? " +
                        "WHERE operation_id = ? AND lifecycle_state = 'RECEIVED' " +
                        "RETURNING *, $RESULT_DOCUMENT_SUBQUERY AS result_document"
                ).use { statement ->
                    statement.setTimestamp(1, now)
                    statement.setObject(2, operationId.toUuid())
                    statement.executeQuery().use { rs -> if (rs.next()) rs.toOperationRow() else null }
                }

                if (claimedRow != null) {
                    ClaimOutcome.Claimed(claimedRow.toDomain())
                } else {
                    ClaimOutcome.AlreadyClaimedOrResolved
                }
            }
        }

    /**
     * A `LEFT JOIN` is correct here where [RESULT_DOCUMENT_SUBQUERY] deliberately is not: this
     * query takes no `FOR UPDATE`, so PostgreSQL's "cannot be applied to the nullable side of an
     * outer join" restriction never applies. Reads only -- no lock, no lease, no state change.
     */
    override suspend fun findReconcilable(limit: Int, notUpdatedSince: Instant): List<ReconciliationCandidate> =
        withContext(Dispatchers.IO) {
            require(limit > 0) { "limit must be positive, was $limit" }
            withConnection { connection ->
                connection.prepareStatement(
                    "SELECT o.operation_id, o.attempt_id, o.updated_at, c.correlation_reference " +
                        "FROM receipt_analysis_operation o " +
                        "LEFT JOIN receipt_analysis_provider_correlation c ON c.operation_id = o.operation_id " +
                        "WHERE o.lifecycle_state = 'RECONCILING' AND o.tombstoned_at IS NULL " +
                        "AND o.updated_at <= ? ORDER BY o.updated_at LIMIT ?"
                ).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(notUpdatedSince))
                    statement.setInt(2, limit)
                    statement.executeQuery().use { rs ->
                        val candidates = mutableListOf<ReconciliationCandidate>()
                        while (rs.next()) {
                            candidates += ReconciliationCandidate(
                                operationId = ReceiptAnalysisOperationId(
                                    rs.getObject("operation_id", UUID::class.java).toString()
                                ),
                                attemptId = ReceiptAnalysisAttemptId(
                                    rs.getObject("attempt_id", UUID::class.java).toString()
                                ),
                                // An explicit lambda, never `::ProviderCorrelationReference`: the
                                // callable reference binds the type's private constructor, not the
                                // companion's validating `invoke`.
                                correlationReference = rs.getString("correlation_reference")
                                    ?.let { ProviderCorrelationReference(it) },
                                reconcilingSince = rs.getTimestamp("updated_at").toInstant()
                            )
                        }
                        candidates
                    }
                }
            }
        }

    override suspend fun applyOutcome(
        operationId: ReceiptAnalysisOperationId,
        application: OutcomeApplication
    ): ApplyOutcomeResult = withContext(Dispatchers.IO) {
        withConnection { connection ->
            val opUuid = operationId.toUuid()
            val current = connection.prepareStatement(
                "SELECT *, $RESULT_DOCUMENT_SUBQUERY AS result_document " +
                    "FROM receipt_analysis_operation WHERE operation_id = ? FOR UPDATE"
            ).use { statement ->
                statement.setObject(1, opUuid)
                statement.executeQuery().use { rs ->
                    check(rs.next()) { "applyOutcome called for unknown operation_id=${operationId.value}" }
                    rs.toOperationRow()
                }
            }

            if (current.lifecycleState == "TERMINAL") {
                return@withConnection ApplyOutcomeResult.AlreadyResolved(current.toDomain())
            }

            when (val transition = resolveTransition(current.lifecycleState, application)) {
                null -> ApplyOutcomeResult.Rejected(current.toDomain())
                Transition.ToReconciling -> applyReconcilingTransition(connection, opUuid, current)
                is Transition.ToReconcilingWithCorrelation ->
                    applyReconcilingWithCorrelationTransition(connection, opUuid, current, transition.reference)
                is Transition.ToTerminal -> applyTerminalTransition(connection, opUuid, current, transition)
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // startOrGetExisting
    // -------------------------------------------------------------------------------------------

    private fun doStartOrGetExisting(
        connection: Connection,
        command: StartReceiptAnalysisOperationCommand,
        contentHash: ContentHash
    ): StartOutcome {
        val now = nowTimestamp()
        val operationId = UUID.randomUUID()
        val attemptId = UUID.randomUUID()

        val wasInserted = connection.prepareStatement(
            "INSERT INTO receipt_analysis_operation (operation_id, user_id, request_id, content_hash, " +
                "size_bytes, mime_type, attempt_id, lifecycle_state, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, 'RECEIVED', ?, ?) " +
                "ON CONFLICT (user_id, request_id) DO NOTHING"
        ).use { statement ->
            statement.setObject(1, operationId)
            statement.setString(2, command.userId.value)
            statement.setString(3, command.requestId.value)
            statement.setString(4, contentHash.value)
            statement.setLong(5, command.image.sizeBytes)
            statement.setString(6, command.image.mimeType)
            statement.setObject(7, attemptId)
            statement.setTimestamp(8, now)
            statement.setTimestamp(9, now)
            statement.executeUpdate() == 1
        }

        return if (wasInserted) {
            startNewOperation(connection, command, contentHash, operationId, attemptId, now)
        } else {
            resolveConflictingOperation(connection, command, contentHash)
        }
    }

    private fun resolveConflictingOperation(
        connection: Connection,
        command: StartReceiptAnalysisOperationCommand,
        contentHash: ContentHash
    ): StartOutcome {
        val row = connection.prepareStatement(
            "SELECT *, $RESULT_DOCUMENT_SUBQUERY AS result_document " +
                "FROM receipt_analysis_operation WHERE user_id = ? AND request_id = ? FOR UPDATE"
        ).use { statement ->
            statement.setString(1, command.userId.value)
            statement.setString(2, command.requestId.value)
            statement.executeQuery().use { rs ->
                check(rs.next()) {
                    "operation vanished between INSERT ON CONFLICT and SELECT " +
                        "(user_id=${command.userId.value}, request_id=${command.requestId.value})"
                }
                rs.toOperationRow()
            }
        }

        return when {
            row.tombstonedAt != null -> StartOutcome.Tombstoned
            row.contentHash != contentHash.value -> StartOutcome.HashConflict
            else -> StartOutcome.Accepted(row.toDomain(), isNew = false)
        }
    }

    /** Only reached once the operation row is durably inserted (never rolled back from here on
     * except by [InsufficientCreditsSignal] or an unexpected exception, both of which undo the
     * insert too since everything shares one transaction). */
    private fun startNewOperation(
        connection: Connection,
        command: StartReceiptAnalysisOperationCommand,
        contentHash: ContentHash,
        operationId: UUID,
        attemptId: UUID,
        now: Timestamp
    ): StartOutcome {
        val fundingGrantId = selectFundingGrant(connection, command.userId, now)

        connection.prepareStatement("UPDATE credit_grant SET remaining_credits = remaining_credits - 1 WHERE grant_id = ?")
            .use { statement ->
                statement.setObject(1, fundingGrantId)
                statement.executeUpdate()
            }

        connection.prepareStatement(
            "INSERT INTO credit_ledger_entry (entry_id, grant_id, operation_id, user_id, effect, created_at) " +
                "VALUES (?, ?, ?, ?, 'RESERVED', ?)"
        ).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, fundingGrantId)
            statement.setObject(3, operationId)
            statement.setString(4, command.userId.value)
            statement.setTimestamp(5, now)
            statement.executeUpdate()
        }

        connection.prepareStatement(
            "INSERT INTO receipt_analysis_payload (operation_id, payload_bytes, stored_at) VALUES (?, ?, ?)"
        ).use { statement ->
            statement.setObject(1, operationId)
            statement.setBytes(2, command.image.bytes())
            statement.setTimestamp(3, now)
            statement.executeUpdate()
        }

        val operation = ReceiptAnalysisOperation(
            operationId = ReceiptAnalysisOperationId(operationId.toString()),
            userId = command.userId,
            requestId = command.requestId,
            contentHash = contentHash,
            sizeBytes = command.image.sizeBytes,
            mimeType = command.image.mimeType,
            attemptId = ReceiptAnalysisAttemptId(attemptId.toString()),
            lifecycle = ReceiptAnalysisOperationLifecycle.Received
        )
        return StartOutcome.Accepted(operation, isNew = true)
    }

    /**
     * Free-monthly-first, oldest-purchased-fallback, per 6.10.27.3 (corrected after BLOCKED):
     * lazily create/lock the current UTC period's FREE_MONTHLY grant, inserting its founding
     * GRANTED entry only when this call is the one that creates it (never a second GRANTED on the
     * conflict path); if it has no spare credit, fall back to the oldest PURCHASED grant with a
     * positive balance. Throws [InsufficientCreditsSignal] if neither has any -- caught by
     * [startOrGetExisting], which relies on [withConnection] to roll back everything this call
     * already did (the free grant's own creation and GRANTED entry included).
     */
    private fun selectFundingGrant(connection: Connection, userId: UserId, now: Timestamp): UUID {
        val period = currentPeriod()
        val candidateGrantId = UUID.randomUUID()

        val wasCreated = connection.prepareStatement(
            "INSERT INTO credit_grant (grant_id, user_id, source, period, purchase_reference, " +
                "credits_granted, remaining_credits, granted_at) VALUES (?, ?, 'FREE_MONTHLY', ?, NULL, ?, ?, ?) " +
                "ON CONFLICT (user_id, period) WHERE source = 'FREE_MONTHLY' DO NOTHING"
        ).use { statement ->
            statement.setObject(1, candidateGrantId)
            statement.setString(2, userId.value)
            statement.setString(3, period)
            statement.setInt(4, monthlyFreeCredits)
            statement.setInt(5, monthlyFreeCredits)
            statement.setTimestamp(6, now)
            statement.executeUpdate() == 1
        }

        val (freeGrantId, freeGrantRemaining) = if (wasCreated) {
            connection.prepareStatement(
                "INSERT INTO credit_ledger_entry (entry_id, grant_id, operation_id, user_id, effect, created_at) " +
                    "VALUES (?, ?, NULL, ?, 'GRANTED', ?)"
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, candidateGrantId)
                statement.setString(3, userId.value)
                statement.setTimestamp(4, now)
                statement.executeUpdate()
            }
            candidateGrantId to monthlyFreeCredits
        } else {
            connection.prepareStatement(
                "SELECT grant_id, remaining_credits FROM credit_grant " +
                    "WHERE user_id = ? AND period = ? AND source = 'FREE_MONTHLY' FOR UPDATE"
            ).use { statement ->
                statement.setString(1, userId.value)
                statement.setString(2, period)
                statement.executeQuery().use { rs ->
                    check(rs.next()) {
                        "free monthly grant vanished between INSERT ON CONFLICT and SELECT " +
                            "(user_id=${userId.value}, period=$period)"
                    }
                    rs.getObject("grant_id", UUID::class.java) to rs.getInt("remaining_credits")
                }
            }
        }

        if (freeGrantRemaining >= 1) {
            return freeGrantId
        }

        return connection.prepareStatement(
            "SELECT grant_id FROM credit_grant WHERE user_id = ? AND source = 'PURCHASED' " +
                "AND remaining_credits > 0 ORDER BY granted_at ASC LIMIT 1 FOR UPDATE"
        ).use { statement ->
            statement.setString(1, userId.value)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.getObject("grant_id", UUID::class.java) else throw InsufficientCreditsSignal()
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // applyOutcome
    // -------------------------------------------------------------------------------------------

    private fun applyReconcilingTransition(
        connection: Connection,
        opUuid: UUID,
        current: OperationRow
    ): ApplyOutcomeResult.Applied {
        val now = nowTimestamp()
        connection.prepareStatement(
            "UPDATE receipt_analysis_operation SET lifecycle_state = 'RECONCILING', updated_at = ? WHERE operation_id = ?"
        ).use { statement ->
            statement.setTimestamp(1, now)
            statement.setObject(2, opUuid)
            statement.executeUpdate()
        }
        return ApplyOutcomeResult.Applied(current.toOperation(ReceiptAnalysisOperationLifecycle.Reconciling))
    }

    /** Same RECONCILING transition as [applyReconcilingTransition], plus durably persisting the
     * opaque provider correlation reference in the same transaction -- the operation row is
     * updated to RECONCILING first, satisfying the V2 insert guard
     * (`guard_provider_correlation_mutation`) that only accepts a reference for an operation
     * already in that state. [reference] never surfaces through the returned domain object --
     * [ReceiptAnalysisOperation]/[ReceiptAnalysisOperationLifecycle.Reconciling] have no field for
     * it (6.10.39). */
    private fun applyReconcilingWithCorrelationTransition(
        connection: Connection,
        opUuid: UUID,
        current: OperationRow,
        reference: ProviderCorrelationReference
    ): ApplyOutcomeResult.Applied {
        val now = nowTimestamp()
        connection.prepareStatement(
            "UPDATE receipt_analysis_operation SET lifecycle_state = 'RECONCILING', updated_at = ? WHERE operation_id = ?"
        ).use { statement ->
            statement.setTimestamp(1, now)
            statement.setObject(2, opUuid)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT INTO receipt_analysis_provider_correlation (operation_id, correlation_reference, recorded_at) " +
                "VALUES (?, ?, ?)"
        ).use { statement ->
            statement.setObject(1, opUuid)
            statement.setString(2, reference.value())
            statement.setTimestamp(3, now)
            statement.executeUpdate()
        }
        return ApplyOutcomeResult.Applied(current.toOperation(ReceiptAnalysisOperationLifecycle.Reconciling))
    }

    private fun applyTerminalTransition(
        connection: Connection,
        opUuid: UUID,
        current: OperationRow,
        transition: Transition.ToTerminal
    ): ApplyOutcomeResult.Applied {
        val now = nowTimestamp()
        val lifecycle = ReceiptAnalysisOperationLifecycle.Terminal(transition.result)

        connection.prepareStatement(
            "UPDATE receipt_analysis_operation SET lifecycle_state = 'TERMINAL', terminal_kind = ?, " +
                "terminal_failure_reason = ?, updated_at = ? WHERE operation_id = ?"
        ).use { statement ->
            statement.setString(1, transition.terminalKind)
            statement.setString(2, transition.terminalFailureReason)
            statement.setTimestamp(3, now)
            statement.setObject(4, opUuid)
            statement.executeUpdate()
        }

        // Removed atomically, in this same transaction, on every RECONCILING -> TERMINAL
        // resolution (and harmlessly a no-op for InvocationClaimed -> Terminal, where none was
        // ever inserted) -- the operation row above is already TERMINAL, satisfying the V2 delete
        // guard; the reference may exist only while the operation remains RECONCILING (6.10.39).
        connection.prepareStatement(
            "DELETE FROM receipt_analysis_provider_correlation WHERE operation_id = ?"
        ).use { statement ->
            statement.setObject(1, opUuid)
            statement.executeUpdate()
        }

        // ADR-003: the bytes sent to the provider exist only while the operation might still need
        // them (crash recovery before a claimed invocation resolves). Once resolved it never needs
        // them again, so a receipt image -- personal and fiscal data -- never outlives the analysis
        // it was uploaded for. The operation row above is already TERMINAL, which is what
        // guard_payload_mutation requires; issued in this same transaction as the state, result
        // and ledger writes, so no resolved operation can be left holding an image.
        connection.prepareStatement(
            "DELETE FROM receipt_analysis_payload WHERE operation_id = ?"
        ).use { statement ->
            statement.setObject(1, opUuid)
            statement.executeUpdate()
        }

        if (transition.result is ReceiptAnalysisOperationResult.Succeeded) {
            connection.prepareStatement(
                "INSERT INTO receipt_analysis_result (operation_id, document, stored_at) VALUES (?, ?, ?)"
            ).use { statement ->
                statement.setObject(1, opUuid)
                statement.setBytes(2, transition.result.document.serialize())
                statement.setTimestamp(3, now)
                statement.executeUpdate()
            }
        }

        val reservedGrantId = connection.prepareStatement(
            "SELECT grant_id FROM credit_ledger_entry WHERE operation_id = ? AND effect = 'RESERVED'"
        ).use { statement ->
            statement.setObject(1, opUuid)
            statement.executeQuery().use { rs ->
                if (!rs.next()) {
                    throw ReceiptAnalysisPersistenceIntegrityException(
                        "No RESERVED ledger entry found for operation_id=$opUuid while applying a terminal outcome"
                    )
                }
                rs.getObject("grant_id", UUID::class.java)
            }
        }

        val effectCode = when (lifecycle.ledgerEffect) {
            ReceiptAnalysisLedgerEffect.DEBITED -> "DEBITED"
            ReceiptAnalysisLedgerEffect.RELEASED -> "RELEASED"
        }

        connection.prepareStatement(
            "INSERT INTO credit_ledger_entry (entry_id, grant_id, operation_id, user_id, effect, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)"
        ).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, reservedGrantId)
            statement.setObject(3, opUuid)
            statement.setString(4, current.userId)
            statement.setString(5, effectCode)
            statement.setTimestamp(6, now)
            statement.executeUpdate()
        }

        if (lifecycle.ledgerEffect == ReceiptAnalysisLedgerEffect.RELEASED) {
            // Lock the grant row before restoring its balance -- same discipline as the reservation
            // itself, so a concurrent reservation against this grant never reads a stale value.
            connection.prepareStatement("SELECT 1 FROM credit_grant WHERE grant_id = ? FOR UPDATE").use { statement ->
                statement.setObject(1, reservedGrantId)
                statement.executeQuery().use { it.next() }
            }
            connection.prepareStatement(
                "UPDATE credit_grant SET remaining_credits = remaining_credits + 1 WHERE grant_id = ?"
            ).use { statement ->
                statement.setObject(1, reservedGrantId)
                statement.executeUpdate()
            }
        }
        // DEBITED intentionally changes nothing on credit_grant -- the credit spent at reservation
        // stays spent; the ledger-derived equation already accounts for it via RESERVED alone.

        return ApplyOutcomeResult.Applied(current.toOperation(lifecycle))
    }

    private sealed interface Transition {
        data object ToReconciling : Transition
        data class ToReconcilingWithCorrelation(val reference: ProviderCorrelationReference) : Transition
        data class ToTerminal(
            val terminalKind: String,
            val terminalFailureReason: String?,
            val result: ReceiptAnalysisOperationResult
        ) : Transition
    }

    /**
     * Mirrors [ReceiptAnalysisOperationStore.applyOutcome]'s KDoc exactly. `null` means
     * [ApplyOutcomeResult.Rejected] -- not valid for this lifecycle state, nothing mutated. The
     * caller has already handled `TERMINAL` (always [ApplyOutcomeResult.AlreadyResolved]) before
     * reaching here.
     */
    private fun resolveTransition(lifecycleState: String, application: OutcomeApplication): Transition? =
        when (lifecycleState) {
            "RECEIVED" -> when (application) {
                is OutcomeApplication.FailedNoProvider -> failedNoProviderTransition(application)
                else -> null
            }
            "INVOCATION_CLAIMED" -> when (application) {
                is OutcomeApplication.Succeeded -> succeededTransition(application)
                is OutcomeApplication.Failed -> failedTransition()
                is OutcomeApplication.FailedNoProvider -> failedNoProviderTransition(application)
                is OutcomeApplication.ReconcilingDetected, is OutcomeApplication.AttemptIdMismatch -> Transition.ToReconciling
                is OutcomeApplication.ReconcilingWithProviderCorrelation ->
                    Transition.ToReconcilingWithCorrelation(application.reference)
            }
            "RECONCILING" -> when (application) {
                is OutcomeApplication.Succeeded -> succeededTransition(application)
                is OutcomeApplication.Failed -> failedTransition()
                is OutcomeApplication.FailedNoProvider -> failedNoProviderTransition(application)
                is OutcomeApplication.ReconcilingDetected,
                is OutcomeApplication.ReconcilingWithProviderCorrelation,
                is OutcomeApplication.AttemptIdMismatch -> null
            }
            else -> throw ReceiptAnalysisPersistenceIntegrityException("Unknown lifecycle_state code: $lifecycleState")
        }

    private fun succeededTransition(application: OutcomeApplication.Succeeded) =
        Transition.ToTerminal("SUCCEEDED", null, ReceiptAnalysisOperationResult.Succeeded(application.document))

    private fun failedTransition() =
        Transition.ToTerminal("FAILED", null, ReceiptAnalysisOperationResult.Failed)

    private fun failedNoProviderTransition(application: OutcomeApplication.FailedNoProvider) =
        Transition.ToTerminal(
            "FAILED_NO_PROVIDER",
            application.reason.toStoredCode(),
            ReceiptAnalysisOperationResult.FailedNoProvider(application.reason)
        )

    // -------------------------------------------------------------------------------------------
    // Row reading / domain mapping
    // -------------------------------------------------------------------------------------------

    private data class OperationRow(
        val operationId: UUID,
        val userId: String,
        val requestId: String,
        val contentHash: String,
        val sizeBytes: Long,
        val mimeType: String,
        val attemptId: UUID,
        val lifecycleState: String,
        val terminalKind: String?,
        val terminalFailureReason: String?,
        val tombstonedAt: Timestamp?,
        val resultDocumentBytes: ByteArray?
    )

    /**
     * Every query that produces an [OperationRow] includes this correlated subquery, so the
     * operation row and its (possibly absent) result document are always read from the exact same
     * statement -- one PostgreSQL snapshot under READ COMMITTED, never two separate queries that
     * could straddle a concurrent tombstone cleanup. A prior two-query version of
     * [findByRequestId] could see `TERMINAL`/`SUCCEEDED` on the first query and then find the
     * result already deleted by a concurrent tombstone on the second, throwing a false
     * [ReceiptAnalysisPersistenceIntegrityException] for a legitimate physical race. See
     * receiptanalysis-slice-report.md 6.10.29.
     */
    private fun ResultSet.toOperationRow(): OperationRow = OperationRow(
        operationId = getObject("operation_id", UUID::class.java),
        userId = getString("user_id"),
        requestId = getString("request_id"),
        contentHash = getString("content_hash"),
        sizeBytes = getLong("size_bytes"),
        mimeType = getString("mime_type"),
        attemptId = getObject("attempt_id", UUID::class.java),
        lifecycleState = getString("lifecycle_state"),
        terminalKind = getString("terminal_kind"),
        terminalFailureReason = getString("terminal_failure_reason"),
        tombstonedAt = getTimestamp("tombstoned_at"),
        resultDocumentBytes = getBytes("result_document")
    )

    /** Builds the full domain [ReceiptAnalysisOperation] -- [resultDocumentBytes] was already read
     * in the same statement as the rest of this row, never a follow-up query. */
    private fun OperationRow.toDomain(): ReceiptAnalysisOperation {
        val lifecycle = when (lifecycleState) {
            "RECEIVED" -> ReceiptAnalysisOperationLifecycle.Received
            "INVOCATION_CLAIMED" -> ReceiptAnalysisOperationLifecycle.InvocationClaimed
            "RECONCILING" -> ReceiptAnalysisOperationLifecycle.Reconciling
            "TERMINAL" -> ReceiptAnalysisOperationLifecycle.Terminal(
                readTerminalResult(operationId, terminalKind, terminalFailureReason, resultDocumentBytes)
            )
            else -> throw ReceiptAnalysisPersistenceIntegrityException(
                "Unknown lifecycle_state code: $lifecycleState (operation_id=$operationId)"
            )
        }
        return toOperation(lifecycle)
    }

    private fun OperationRow.toOperation(lifecycle: ReceiptAnalysisOperationLifecycle): ReceiptAnalysisOperation =
        ReceiptAnalysisOperation(
            operationId = ReceiptAnalysisOperationId(operationId.toString()),
            userId = UserId(userId),
            requestId = RequestId(requestId),
            contentHash = ContentHash(contentHash),
            sizeBytes = sizeBytes,
            mimeType = mimeType,
            attemptId = ReceiptAnalysisAttemptId(attemptId.toString()),
            lifecycle = lifecycle
        )

    private fun readTerminalResult(
        operationId: UUID,
        terminalKind: String?,
        terminalFailureReason: String?,
        resultDocumentBytes: ByteArray?
    ): ReceiptAnalysisOperationResult = when (terminalKind) {
        "SUCCEEDED" -> {
            val documentBytes = resultDocumentBytes
                ?: throw ReceiptAnalysisPersistenceIntegrityException(
                    "TERMINAL SUCCEEDED operation has no receipt_analysis_result row (operation_id=$operationId)"
                )
            val document = ValidatedReceiptAnalysisResultV1.from(documentBytes)
                ?: throw ReceiptAnalysisPersistenceIntegrityException(
                    "receipt_analysis_result.document failed to validate (operation_id=$operationId)"
                )
            ReceiptAnalysisOperationResult.Succeeded(document)
        }
        "FAILED" -> ReceiptAnalysisOperationResult.Failed
        "FAILED_NO_PROVIDER" -> ReceiptAnalysisOperationResult.FailedNoProvider(
            storedCodeToFailedNoProviderReason(
                terminalFailureReason ?: throw ReceiptAnalysisPersistenceIntegrityException(
                    "TERMINAL FAILED_NO_PROVIDER operation has no terminal_failure_reason (operation_id=$operationId)"
                )
            )
        )
        else -> throw ReceiptAnalysisPersistenceIntegrityException(
            "Unknown terminal_kind code: $terminalKind (operation_id=$operationId)"
        )
    }

    // -------------------------------------------------------------------------------------------
    // FailedNoProviderReason <-> stable stored code. Never `.name`/`valueOf`: a closed, explicit,
    // exhaustive `when` in both directions (corrected after BLOCKED, 6.10.27.2). Three disjoint
    // code spaces share the one `terminal_failure_reason` column -- [ProviderCallFailureReason]
    // (the call never left this backend), [ProviderRejectionReason] (the call reached the provider
    // and was rejected outright, 6.10.32), and [ProviderTerminalFailureReason] (a confirmed
    // response conclusively had no usable result, 6.10.37) -- so the write side dispatches by
    // concrete type and the read side tries all known code sets before treating an unrecognized
    // code as corruption.
    // -------------------------------------------------------------------------------------------

    private fun FailedNoProviderReason.toStoredCode(): String = when (this) {
        is ProviderCallFailureReason -> toStoredCode()
        is ProviderRejectionReason -> toStoredCode()
        is ProviderTerminalFailureReason -> toStoredCode()
    }

    private fun ProviderCallFailureReason.toStoredCode(): String = when (this) {
        ProviderCallFailureReason.REQUEST_CONSTRUCTION_FAILED -> "REQUEST_CONSTRUCTION_FAILED"
        ProviderCallFailureReason.PROVIDER_CREDENTIAL_UNAVAILABLE -> "PROVIDER_CREDENTIAL_UNAVAILABLE"
    }

    private fun ProviderRejectionReason.toStoredCode(): String = when (this) {
        ProviderRejectionReason.MALFORMED_REQUEST -> "MALFORMED_REQUEST"
        ProviderRejectionReason.AUTHENTICATION_REJECTED -> "AUTHENTICATION_REJECTED"
        ProviderRejectionReason.ACCESS_FORBIDDEN -> "ACCESS_FORBIDDEN"
    }

    private fun ProviderTerminalFailureReason.toStoredCode(): String = when (this) {
        ProviderTerminalFailureReason.RETRY_EXHAUSTED -> "RETRY_EXHAUSTED"
        ProviderTerminalFailureReason.BILLING_OR_QUOTA_EXHAUSTED -> "BILLING_OR_QUOTA_EXHAUSTED"
        ProviderTerminalFailureReason.UNRECOGNIZED_RESPONSE -> "UNRECOGNIZED_RESPONSE"
        ProviderTerminalFailureReason.RESPONSE_FAILED -> "RESPONSE_FAILED"
        ProviderTerminalFailureReason.RESPONSE_CANCELLED -> "RESPONSE_CANCELLED"
        ProviderTerminalFailureReason.RATE_LIMITED -> "RATE_LIMITED"
        ProviderTerminalFailureReason.TRANSIENT_PROVIDER_FAILURE -> "TRANSIENT_PROVIDER_FAILURE"
    }

    private fun storedCodeToFailedNoProviderReason(code: String): FailedNoProviderReason = when (code) {
        "REQUEST_CONSTRUCTION_FAILED" -> ProviderCallFailureReason.REQUEST_CONSTRUCTION_FAILED
        "PROVIDER_CREDENTIAL_UNAVAILABLE" -> ProviderCallFailureReason.PROVIDER_CREDENTIAL_UNAVAILABLE
        "MALFORMED_REQUEST" -> ProviderRejectionReason.MALFORMED_REQUEST
        "AUTHENTICATION_REJECTED" -> ProviderRejectionReason.AUTHENTICATION_REJECTED
        "ACCESS_FORBIDDEN" -> ProviderRejectionReason.ACCESS_FORBIDDEN
        "RETRY_EXHAUSTED" -> ProviderTerminalFailureReason.RETRY_EXHAUSTED
        "BILLING_OR_QUOTA_EXHAUSTED" -> ProviderTerminalFailureReason.BILLING_OR_QUOTA_EXHAUSTED
        "UNRECOGNIZED_RESPONSE" -> ProviderTerminalFailureReason.UNRECOGNIZED_RESPONSE
        "RESPONSE_FAILED" -> ProviderTerminalFailureReason.RESPONSE_FAILED
        "RESPONSE_CANCELLED" -> ProviderTerminalFailureReason.RESPONSE_CANCELLED
        "RATE_LIMITED" -> ProviderTerminalFailureReason.RATE_LIMITED
        "TRANSIENT_PROVIDER_FAILURE" -> ProviderTerminalFailureReason.TRANSIENT_PROVIDER_FAILURE
        else -> throw ReceiptAnalysisPersistenceIntegrityException("Unknown terminal_failure_reason code: $code")
    }

    // -------------------------------------------------------------------------------------------
    // Connection/transaction plumbing and time
    // -------------------------------------------------------------------------------------------

    private fun <T> withConnection(block: (Connection) -> T): T {
        val connection = dataSource.connection
        return try {
            connection.autoCommit = false
            val result = block(connection)
            connection.commit()
            result
        } catch (throwable: Throwable) {
            runCatching { connection.rollback() }
            throw throwable
        } finally {
            connection.close()
        }
    }

    /** Always [clock] -- never `Instant.now()`/`Clock.systemUTC()` inside this class. */
    private fun currentPeriod(): String = YearMonth.from(clock.instant().atZone(ZoneOffset.UTC)).toString()

    private fun nowTimestamp(): Timestamp = Timestamp.from(clock.instant())

    private fun ReceiptAnalysisOperationId.toUuid(): UUID = UUID.fromString(value)

    private companion object {
        /** Correlated scalar subquery, not a JOIN -- works unchanged whether or not the outer
         * query also takes `FOR UPDATE` (a `LEFT JOIN` would need `FOR UPDATE OF` to avoid
         * PostgreSQL's "cannot be applied to the nullable side of an outer join" error; a scalar
         * subquery in the SELECT list has no such restriction). */
        const val RESULT_DOCUMENT_SUBQUERY =
            "(SELECT document FROM receipt_analysis_result " +
                "WHERE operation_id = receipt_analysis_operation.operation_id)"
    }
}

/** Signals "roll back everything this attempt did" without an exception surfacing past
 * [PostgresReceiptAnalysisOperationStore.startOrGetExisting] -- caught there and converted to
 * [StartOutcome.InsufficientCredits] only after [PostgresReceiptAnalysisOperationStore.withConnection]'s
 * rollback has already run. */
private class InsufficientCreditsSignal : RuntimeException()

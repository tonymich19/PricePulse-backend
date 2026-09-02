package com.tonycorreia.pricepulsebackend.infrastructure

import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.tonycorreia.pricepulsebackend.infrastructure.firebase.FirebaseIdTokenVerifier
import com.tonycorreia.pricepulsebackend.infrastructure.firebase.RealFirebaseIdTokenVerifier
import com.tonycorreia.pricepulsebackend.infrastructure.openai.OpenAiCioHttpTransport
import com.tonycorreia.pricepulsebackend.infrastructure.openai.OpenAiCredential
import com.tonycorreia.pricepulsebackend.infrastructure.openai.OpenAiCredentialProvider
import com.tonycorreia.pricepulsebackend.infrastructure.openai.LoggingOpenAiInvocationTelemetrySink
import com.tonycorreia.pricepulsebackend.infrastructure.openai.OpenAiInvocationTelemetrySink
import com.tonycorreia.pricepulsebackend.infrastructure.openai.OpenAiReceiptAnalysisProviderAdapter
import com.tonycorreia.pricepulsebackend.infrastructure.openai.OpenAiReceiptAnalysisRequestConfig
import com.tonycorreia.pricepulsebackend.infrastructure.openai.OpenAiResponsesRequestFactory
import com.tonycorreia.pricepulsebackend.infrastructure.openai.ReceiptExtractionInstructions
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderInvocationFailureObserver
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ReceiptAnalysisOperationStore
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.StartReceiptAnalysisUseCase
import com.tonycorreia.pricepulsebackend.infrastructure.http.AdmissionRateLimiter
import com.tonycorreia.pricepulsebackend.infrastructure.http.InProcessAdmissionRateLimiter
import com.tonycorreia.pricepulsebackend.infrastructure.http.InProcessPollingRateLimiter
import com.tonycorreia.pricepulsebackend.infrastructure.http.KtorReceiptUploadReader
import com.tonycorreia.pricepulsebackend.infrastructure.http.PollingRateLimiter
import com.tonycorreia.pricepulsebackend.infrastructure.http.ReceiptUploadReader
import com.tonycorreia.pricepulsebackend.infrastructure.http.receiptAnalysisStatusRoute
import com.tonycorreia.pricepulsebackend.infrastructure.http.startReceiptAnalysisRoute
import io.ktor.client.engine.cio.CIO
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ResolveReconcilingOperationsUseCase
import com.tonycorreia.pricepulsebackend.infrastructure.openai.OpenAiReceiptAnalysisRetrievalAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.tonycorreia.pricepulsebackend.infrastructure.persistence.DATABASE_URL_ENV_VAR
import com.tonycorreia.pricepulsebackend.infrastructure.persistence.MONTHLY_FREE_CREDITS_ENV_VAR
import com.tonycorreia.pricepulsebackend.infrastructure.persistence.PostgresReceiptAnalysisOperationStore
import com.tonycorreia.pricepulsebackend.infrastructure.persistence.buildDataSource
import com.tonycorreia.pricepulsebackend.infrastructure.persistence.migrate
import com.tonycorreia.pricepulsebackend.infrastructure.persistence.resolveDatabaseUrl
import com.tonycorreia.pricepulsebackend.infrastructure.persistence.resolveMonthlyFreeCredits
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Duration

private const val PORT_ENV_VAR = "PORT"
private const val OPENAI_API_KEY_ENV_VAR = "OPENAI_API_KEY"
private const val FIREBASE_SERVICE_ACCOUNT_JSON_ENV_VAR = "FIREBASE_SERVICE_ACCOUNT_JSON"
private const val DEFAULT_PORT = 8080

// Operational configuration closed in receiptanalysis-slice-report.md 6.10.53 (rounds 207-210) and
// 6.10.54. No value here is new: this slice only wires them into the single production instance.
private const val OPENAI_MODEL = "gpt-5.6-luna"
private const val OPENAI_SCHEMA_NAME = "receipt_analysis"
private const val OPENAI_IMAGE_DETAIL = "original"
private const val OPENAI_MAX_OUTPUT_TOKENS = 4000
private const val OPENAI_REASONING_EFFORT = "low"
private const val OPENAI_REQUEST_TIMEOUT_MS = 60_000L
private const val OPENAI_CONNECT_TIMEOUT_MS = 10_000L
private const val OPENAI_SOCKET_TIMEOUT_MS = 60_000L
private const val OPENAI_BODY_LIMIT_BYTES = 1_048_576L

// Reconciliation cadence. Compile-time constants, exactly like every other operational value in
// this file -- making them configurable is a separate, unmade decision. The interval is the worst
// case a user waits once the provider has finished; the expiry is how long an operation this
// backend can no longer account for holds the user's credit before it is released.
private const val RECONCILIATION_INTERVAL_SECONDS = 60L
private const val RECONCILIATION_EXPIRY_HOURS = 6L
private const val RECONCILIATION_BATCH_LIMIT = 50

fun main() {
    val port = resolvePort(System.getenv(PORT_ENV_VAR))
    val credential = resolveOpenAiCredential(System.getenv(OPENAI_API_KEY_ENV_VAR))
    val credentialProvider = OpenAiCredentialProvider { credential }

    val firebaseCredential = resolveFirebaseCredential(System.getenv(FIREBASE_SERVICE_ACCOUNT_JSON_ENV_VAR))
    FirebaseApp.initializeApp(FirebaseOptions.builder().setCredentials(firebaseCredential).build())
    val firebaseIdTokenVerifier: FirebaseIdTokenVerifier = RealFirebaseIdTokenVerifier()

    // The pool is this function's resource: closed in `finally` on every path out, including a
    // failed migration, so its threads and connections never outlive the process's attempt to run.
    // Whatever propagates from here has already been sanitized by migrate/buildDataSource, so the
    // runtime's uncaught-exception print has no original cause to expose.
    val dataSource = buildDataSource(resolveDatabaseUrl(System.getenv(DATABASE_URL_ENV_VAR)))
    val monthlyFreeCredits = resolveMonthlyFreeCredits(System.getenv(MONTHLY_FREE_CREDITS_ENV_VAR))
    try {
        migrate(dataSource)
        // Built after the migration, so the schema the store's SQL depends on already exists.
        // Construction opens no connection: the pool stays lazy until a first real query.
        val operationStore = PostgresReceiptAnalysisOperationStore(
            dataSource,
            Clock.systemUTC(),
            monthlyFreeCredits
        )
        val pollingRateLimiter = InProcessPollingRateLimiter(Clock.systemUTC())

        // Second resource of this function, created after the pool. Its own `finally` is nested
        // inside the pool's, so any failure *after* the transport exists -- building the adapter,
        // the use case, the server, or the module -- still closes it, while a failure *before* it
        // exists never tries to.
        val transport = OpenAiCioHttpTransport(
            CIO.create(),
            OPENAI_REQUEST_TIMEOUT_MS,
            OPENAI_CONNECT_TIMEOUT_MS,
            OPENAI_SOCKET_TIMEOUT_MS,
            OPENAI_BODY_LIMIT_BYTES
        )
        try {
            val providerPort = OpenAiReceiptAnalysisProviderAdapter(
                OpenAiResponsesRequestFactory(productionRequestConfig()),
                credentialProvider,
                transport,
                productionTelemetrySink()
            )
            val startReceiptAnalysis = StartReceiptAnalysisUseCase(operationStore, providerPort, productionFailureObserver())

            // Third resource of this function. It shares [transport] with the invocation adapter
            // -- the same reusable client, never a second one -- so it must stop before the
            // `finally` below closes that transport; hence its own nested try/finally.
            val reconciliationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val resolveReconciling = ResolveReconcilingOperationsUseCase(
                store = operationStore,
                retrieval = OpenAiReceiptAnalysisRetrievalAdapter(credentialProvider, transport),
                clock = Clock.systemUTC(),
                expiry = Duration.ofHours(RECONCILIATION_EXPIRY_HOURS),
                batchLimit = RECONCILIATION_BATCH_LIMIT
            )
            // An explicit lambda: a class declaring `operator fun invoke` is not implicitly a
            // function type in Kotlin, so the use case cannot be passed as `sweep` directly.
            val reconciliationJob = ReconciliationRunner(
                sweep = { resolveReconciling() },
                interval = Duration.ofSeconds(RECONCILIATION_INTERVAL_SECONDS)
            ).start(reconciliationScope)

            try {
                embeddedServer(Netty, port = port, host = "0.0.0.0") {
                    module(
                        firebaseIdTokenVerifier,
                        operationStore,
                        pollingRateLimiter,
                        startReceiptAnalysis,
                        InProcessAdmissionRateLimiter(Clock.systemUTC()),
                        KtorReceiptUploadReader()
                    )
                }.start(wait = true)
            } finally {
                reconciliationJob.cancel()
                reconciliationScope.cancel()
            }
        } finally {
            transport.close()
        }
    } finally {
        dataSource.close()
    }
}

/**
 * Pure, offline-testable -- absent falls back to [DEFAULT_PORT]; any present-but-invalid value
 * (non-numeric, or outside the 1-65535 TCP range) fails before `embeddedServer` is ever built.
 */
internal fun resolvePort(rawPort: String?): Int {
    if (rawPort == null) return DEFAULT_PORT
    val parsed = rawPort.toIntOrNull()
    require(parsed != null && parsed in 1..65535) {
        "$PORT_ENV_VAR must be a valid integer between 1 and 65535, was: \"$rawPort\""
    }
    return parsed
}

/**
 * Pure, offline-testable -- absent or blank fails before `embeddedServer` is ever built. The
 * failure message names only [OPENAI_API_KEY_ENV_VAR], never [rawValue] -- same discipline already
 * mechanically enforced by [OpenAiCredential.toString].
 */
internal fun resolveOpenAiCredential(rawValue: String?): OpenAiCredential {
    require(!rawValue.isNullOrBlank()) { "$OPENAI_API_KEY_ENV_VAR must be set to a non-blank value" }
    // Trimmed here, at the one boundary where a file- or environment-sourced value enters: a secret
    // file written on Windows ends in CRLF, and shell command substitution strips the LF but leaves
    // the CR attached to the key. Surrounding whitespace is never part of a credential.
    return OpenAiCredential(rawValue.trim())
}

/**
 * Pure, offline-testable -- absent or blank fails before `embeddedServer` is ever built.
 * [ServiceAccountCredentials.fromStream] (never the generic `GoogleCredentials.fromStream`, which
 * would accept other credential types the app never intends to support) also rejects malformed
 * JSON and JSON that is well-formed but not a service account. The failure message names only
 * [FIREBASE_SERVICE_ACCOUNT_JSON_ENV_VAR], never [rawJson] or the SDK's own parsed content --
 * deliberately never chained as `cause` either, since the parser/SDK's own exception message is
 * outside PricePulse's control and could echo a fragment of the service account JSON; a
 * logger/startup handler that prints a full cause chain or stack trace must never have that to
 * print.
 */
internal fun resolveFirebaseCredential(rawJson: String?): ServiceAccountCredentials {
    require(!rawJson.isNullOrBlank()) { "$FIREBASE_SERVICE_ACCOUNT_JSON_ENV_VAR must be set to a non-blank value" }
    return try {
        ServiceAccountCredentials.fromStream(ByteArrayInputStream(rawJson.toByteArray(Charsets.UTF_8)))
    } catch (_: Exception) {
        throw IllegalArgumentException(
            "$FIREBASE_SERVICE_ACCOUNT_JSON_ENV_VAR must be a well-formed service account JSON"
        )
    }
}

/**
 * Only HTTP/application abstractions cross this boundary -- no OpenAI type appears in the signature.
 * The provider adapter, its transport and its credential are assembled in [main] and reach the
 * routes only as the already-composed [StartReceiptAnalysisUseCase].
 */
fun Application.module(
    firebaseIdTokenVerifier: FirebaseIdTokenVerifier,
    operationStore: ReceiptAnalysisOperationStore,
    pollingRateLimiter: PollingRateLimiter,
    startReceiptAnalysis: StartReceiptAnalysisUseCase,
    admissionRateLimiter: AdmissionRateLimiter,
    uploadReader: ReceiptUploadReader
) {
    routing {
        get("/health") {
            call.respondText("OK")
        }
        receiptAnalysisStatusRoute(firebaseIdTokenVerifier, operationStore, pollingRateLimiter)
        startReceiptAnalysisRoute(
            firebaseIdTokenVerifier,
            operationStore,
            startReceiptAnalysis,
            admissionRateLimiter,
            uploadReader
        )
    }
}

/**
 * The single production destination of OpenAI cost telemetry. `internal` rather than private so a
 * test can assert the composed type directly, instead of scanning this file's text -- a check that
 * breaks if anyone reinstates a no-op sink here.
 */
internal fun productionTelemetrySink(): OpenAiInvocationTelemetrySink = LoggingOpenAiInvocationTelemetrySink()

/**
 * The single production destination of a swallowed provider-invocation failure. `internal` for the
 * same reason as [productionTelemetrySink]: a test asserts the composed type directly, so quietly
 * reinstating a no-op observer here breaks the build instead of silently restoring the blind spot
 * this exists to close.
 */
internal fun productionFailureObserver(): ProviderInvocationFailureObserver =
    LoggingProviderInvocationFailureObserver()

/**
 * The six values closed in receiptanalysis-slice-report.md 6.10.53/6.10.54. Kept private to this
 * file so no test or route can quietly diverge from what production sends.
 */
private fun productionRequestConfig(): OpenAiReceiptAnalysisRequestConfig =
    OpenAiReceiptAnalysisRequestConfig(
        model = OPENAI_MODEL,
        extractionInstructions = ReceiptExtractionInstructions.TEXT,
        schemaName = OPENAI_SCHEMA_NAME,
        imageDetail = OPENAI_IMAGE_DETAIL,
        maxOutputTokens = OPENAI_MAX_OUTPUT_TOKENS,
        reasoningEffort = OPENAI_REASONING_EFFORT
    )

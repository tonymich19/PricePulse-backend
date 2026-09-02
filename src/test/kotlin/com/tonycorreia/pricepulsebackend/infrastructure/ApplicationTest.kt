package com.tonycorreia.pricepulsebackend.infrastructure

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderInvocationFailureObserver
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisAttempt
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisProviderPort
import com.tonycorreia.pricepulsebackend.infrastructure.firebase.FirebaseIdTokenVerifier
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.InMemoryReceiptAnalysisOperationStore
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.RequestId
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.StartReceiptAnalysisUseCase
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.UserId
import com.tonycorreia.pricepulsebackend.infrastructure.openai.LoggingOpenAiInvocationTelemetrySink
import com.tonycorreia.pricepulsebackend.infrastructure.openai.OpenAiCredential
import com.tonycorreia.pricepulsebackend.infrastructure.http.AdmissionRateLimiter
import com.tonycorreia.pricepulsebackend.infrastructure.http.PollingRateLimitDecision
import com.tonycorreia.pricepulsebackend.infrastructure.http.PollingRateLimiter
import com.tonycorreia.pricepulsebackend.infrastructure.http.ReceiptUploadReader
import com.tonycorreia.pricepulsebackend.infrastructure.persistence.buildDataSource
import com.tonycorreia.pricepulsebackend.infrastructure.persistence.migrate
import com.tonycorreia.pricepulsebackend.infrastructure.persistence.resolveDatabaseUrl
import com.tonycorreia.pricepulsebackend.infrastructure.persistence.resolveMonthlyFreeCredits
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.PrintWriter
import java.security.KeyPairGenerator
import java.sql.Connection
import java.sql.SQLException
import java.util.Base64
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.fail

class ApplicationTest {

    /**
     * Never opens a connection: [getConnection] either fails or is never reached. [failure] is the
     * exception the fake raises, letting a test plant a synthetic URL inside a message PricePulse
     * does not control -- exactly the shape a real driver/Flyway failure takes.
     */
    private class FakeDataSource(private val failure: () -> Nothing) : DataSource {
        override fun getConnection(): Connection = failure()
        override fun getConnection(username: String?, password: String?): Connection = failure()
        override fun getLogWriter(): PrintWriter? = null
        override fun setLogWriter(out: PrintWriter?) {}
        override fun setLoginTimeout(seconds: Int) {}
        override fun getLoginTimeout(): Int = 0
        override fun getParentLogger(): Logger = throw UnsupportedOperationException()
        override fun <T : Any?> unwrap(iface: Class<T>?): T = throw UnsupportedOperationException()
        override fun isWrapperFor(iface: Class<*>?): Boolean = false
    }

    @Test
    fun `health endpoint returns 200 OK`() = testApplication {
        application {
            // The three collaborators the POST needs are built here but never exercised by
            // GET /health. AdmissionRateLimiter has two operations, so it takes an explicit object;
            // ReceiptUploadReader has a single suspending operation, so a lambda suffices.
            module(
                FirebaseIdTokenVerifier { null },
                InMemoryReceiptAnalysisOperationStore(),
                PollingRateLimiter { PollingRateLimitDecision.Allowed },
                StartReceiptAnalysisUseCase(
                    InMemoryReceiptAnalysisOperationStore(),
                    object : ReceiptAnalysisProviderPort {
                        override suspend fun invoke(attempt: ReceiptAnalysisAttempt) =
                            fail("provider must not be called")
                    },
                    ProviderInvocationFailureObserver { _, _ -> fail("no failure is expected here") }
                ),
                object : AdmissionRateLimiter {
                    override fun acquireForExisting(userId: UserId, requestId: RequestId) =
                        PollingRateLimitDecision.Allowed

                    override fun acquireForNew(userId: UserId) = PollingRateLimitDecision.Allowed
                },
                ReceiptUploadReader { fail("upload reader must not be called") }
            )
        }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("OK", response.bodyAsText())
    }

    @Test
    fun `production composes the real telemetry sink, never a no-op`() {
        // A typed assertion rather than a textual scan of this file: it is compiled, so it breaks
        // if anyone reinstates `OpenAiInvocationTelemetrySink { }` in the production composition and
        // silently discards every OpenAI cost event again.
        assertIs<LoggingOpenAiInvocationTelemetrySink>(productionTelemetrySink())
    }

    @Test
    fun `production wires a logging provider-failure observer, never a no-op`() {
        assertIs<LoggingProviderInvocationFailureObserver>(productionFailureObserver())
    }

    // ---------------------------------------------------------------------------------------
    // resolvePort -- puras, offline, sem embeddedServer real
    // ---------------------------------------------------------------------------------------

    @Test
    fun `resolvePort falls back to 8080 when PORT is absent`() {
        assertEquals(8080, resolvePort(null))
    }

    @Test
    fun `resolvePort accepts a valid numeric PORT`() {
        assertEquals(3000, resolvePort("3000"))
    }

    @Test
    fun `resolvePort accepts the boundary values 1 and 65535`() {
        assertEquals(1, resolvePort("1"))
        assertEquals(65535, resolvePort("65535"))
    }

    @Test
    fun `resolvePort rejects a non-numeric value`() {
        assertFailsWith<IllegalArgumentException> { resolvePort("abc") }
    }

    @Test
    fun `resolvePort rejects zero, negative, and above-range values`() {
        assertFailsWith<IllegalArgumentException> { resolvePort("0") }
        assertFailsWith<IllegalArgumentException> { resolvePort("-1") }
        assertFailsWith<IllegalArgumentException> { resolvePort("65536") }
    }

    // ---------------------------------------------------------------------------------------
    // resolveOpenAiCredential -- puras, offline, nunca expõe o valor na mensagem de falha
    // ---------------------------------------------------------------------------------------

    @Test
    fun `resolveOpenAiCredential rejects an absent value`() {
        assertFailsWith<IllegalArgumentException> { resolveOpenAiCredential(null) }
    }

    @Test
    fun `resolveOpenAiCredential rejects an empty value`() {
        assertFailsWith<IllegalArgumentException> { resolveOpenAiCredential("") }
    }

    @Test
    fun `resolveOpenAiCredential rejects a blank value`() {
        assertFailsWith<IllegalArgumentException> { resolveOpenAiCredential("   ") }
    }

    @Test
    fun `resolveOpenAiCredential builds a credential from a valid value, never disclosed by toString`() {
        val fixtureSecret = "sk-fixture-secret-should-never-be-logged-abc123"

        val credential = resolveOpenAiCredential(fixtureSecret)

        assertEquals("OpenAiCredential(<redacted>)", credential.toString())
    }

    @Test
    fun `resolveOpenAiCredential failure messages for absent, empty, and blank input never contain a real-looking key value`() {
        val fixtureLookingBlank = "   " // even a fixture that resembles a key pattern must never leak
        listOf(null, "", fixtureLookingBlank).forEach { invalidValue ->
            val exception = assertFailsWith<IllegalArgumentException> { resolveOpenAiCredential(invalidValue) }
            assertEquals("OPENAI_API_KEY must be set to a non-blank value", exception.message)
        }
    }

    // ---------------------------------------------------------------------------------------
    // resolveFirebaseCredential -- puras, offline, nunca inicializa um FirebaseApp real,
    // nunca expõe o conteúdo bruto na mensagem de falha
    // ---------------------------------------------------------------------------------------

    /** A fresh, throwaway RSA key -- never tied to any real Firebase/Google project. */
    private fun fictitiousServiceAccountJson(): String {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val encodedKey = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.private.encoded)
        val pem = "-----BEGIN PRIVATE KEY-----\n$encodedKey\n-----END PRIVATE KEY-----\n".replace("\n", "\\n")
        return """
            {
              "type": "service_account",
              "project_id": "fixture-project",
              "private_key_id": "fixture-key-id",
              "private_key": "$pem",
              "client_email": "fixture@fixture-project.iam.gserviceaccount.com",
              "client_id": "000000000000000000000",
              "auth_uri": "https://accounts.google.com/o/oauth2/auth",
              "token_uri": "https://oauth2.googleapis.com/token",
              "auth_provider_x509_cert_url": "https://www.googleapis.com/oauth2/v1/certs",
              "client_x509_cert_url": "https://www.googleapis.com/robot/v1/metadata/x509/fixture%40fixture-project.iam.gserviceaccount.com"
            }
        """.trimIndent()
    }

    private fun authorizedUserJson() = """
        {
          "type": "authorized_user",
          "client_id": "fixture-client-id",
          "client_secret": "fixture-client-secret",
          "refresh_token": "fixture-refresh-token"
        }
    """.trimIndent()

    @Test
    fun `resolveFirebaseCredential rejects an absent value`() {
        assertFailsWith<IllegalArgumentException> { resolveFirebaseCredential(null) }
    }

    @Test
    fun `resolveFirebaseCredential rejects an empty value`() {
        assertFailsWith<IllegalArgumentException> { resolveFirebaseCredential("") }
    }

    @Test
    fun `resolveFirebaseCredential rejects a blank value`() {
        assertFailsWith<IllegalArgumentException> { resolveFirebaseCredential("   ") }
    }

    @Test
    fun `resolveFirebaseCredential rejects malformed JSON`() {
        assertFailsWith<IllegalArgumentException> { resolveFirebaseCredential("not json at all") }
    }

    @Test
    fun `resolveFirebaseCredential rejects well-formed JSON that is not a service account`() {
        assertFailsWith<IllegalArgumentException> { resolveFirebaseCredential(authorizedUserJson()) }
    }

    @Test
    fun `resolveFirebaseCredential builds a credential from a syntactically valid fictitious service account`() {
        val credential = resolveFirebaseCredential(fictitiousServiceAccountJson())

        assertEquals("fixture@fixture-project.iam.gserviceaccount.com", credential.clientEmail)
    }

    @Test
    fun `resolveFirebaseCredential failure messages never contain the raw variable value`() {
        val fixtureSecretLooking = fictitiousServiceAccountJson().replace("service_account", "not_a_real_type")
        listOf(null, "", "   ", "not json at all", authorizedUserJson(), fixtureSecretLooking).forEach { invalidValue ->
            val exception = assertFailsWith<IllegalArgumentException> { resolveFirebaseCredential(invalidValue) }
            val message = exception.message.orEmpty()
            assertEquals(true, message.startsWith("FIREBASE_SERVICE_ACCOUNT_JSON"))
            // Only non-blank content is a meaningful leak candidate -- "".contains("")/"   ".contains("   ")
            // are trivially true and would never be a real assertion of "the value never leaks".
            if (!invalidValue.isNullOrBlank()) {
                assertEquals(false, message.contains(invalidValue))
            }
        }
    }

    /**
     * Regression for the exception chaining bug flagged in the 219 review round: the malformed/
     * wrong-type/non-service-account cases used to chain the parser/SDK's own exception as
     * `cause` -- a logger or startup handler that prints the full cause chain or a stack trace
     * could then leak a fragment of the raw JSON, since that underlying message is never under
     * PricePulse's control. `exception.cause` must be null, and neither the message nor the full
     * stack trace text may ever contain the raw input.
     */
    @Test
    fun `resolveFirebaseCredential failure exceptions for a non-blank invalid input never chain the underlying cause, and never leak the input via the stack trace either`() {
        val fixtureSecretLooking = fictitiousServiceAccountJson().replace("service_account", "not_a_real_type")
        listOf("not json at all", authorizedUserJson(), fixtureSecretLooking).forEach { invalidValue ->
            val exception = assertFailsWith<IllegalArgumentException> { resolveFirebaseCredential(invalidValue) }

            assertEquals(null, exception.cause)
            assertEquals(false, exception.stackTraceToString().contains(invalidValue))
        }
    }

    // ---------------------------------------------------------------------------------------
    // resolveDatabaseUrl / buildDataSource / migrate -- puras, offline, nunca abrem conexão,
    // nunca usam banco/URL/credencial reais, nunca expõem a URL em mensagem/cause/stack trace
    // ---------------------------------------------------------------------------------------

    /** Syntactically valid, deliberately unreachable, and obviously fake -- never a real database. */
    private val syntheticDatabaseUrl =
        "jdbc:postgresql://fixture-user:fixture-password@fixture-host.invalid:5432/fixture-db"

    @Test
    fun `resolveDatabaseUrl rejects absent, empty, and blank values with a message naming only the variable`() {
        listOf(null, "", "   ").forEach { invalidValue ->
            val exception = assertFailsWith<IllegalArgumentException> { resolveDatabaseUrl(invalidValue) }
            assertEquals("DATABASE_URL must be set to a non-blank value", exception.message)
        }
    }

    @Test
    fun `resolveDatabaseUrl returns a non-blank value unchanged`() {
        assertEquals(syntheticDatabaseUrl, resolveDatabaseUrl(syntheticDatabaseUrl))
    }

    @Test
    fun `buildDataSource constructs lazily -- an unreachable URL neither throws nor opens a connection`() {
        val dataSource = buildDataSource(syntheticDatabaseUrl)

        // Reaching this line at all is the proof: a pool built eagerly would have tried to connect
        // to fixture-host.invalid during construction and failed.
        assertEquals(false, dataSource.isClosed)
        assertEquals(syntheticDatabaseUrl, dataSource.jdbcUrl)

        dataSource.close()
    }

    @Test
    fun `buildDataSource pool close is idempotent, so main can always close it in finally`() {
        val dataSource = buildDataSource(syntheticDatabaseUrl)

        dataSource.close()
        assertEquals(true, dataSource.isClosed)

        dataSource.close()
        assertEquals(true, dataSource.isClosed)
    }

    /**
     * The case that closes the leak flagged in the 231 review round: a driver/Flyway failure
     * message is written outside PricePulse's control and can echo the whole `DATABASE_URL`,
     * credentials included. Letting it escape `main()` would be no safer than logging it, since the
     * runtime prints an uncaught exception with its full cause chain. [migrate] must therefore
     * replace it with a brand-new exception -- fixed message, no `cause`, nothing of the original
     * recoverable from the stack trace either.
     */
    @Test
    fun `migrate converts a driver failure carrying the URL into a new exception with no cause and no trace of the URL`() {
        val failing = FakeDataSource { throw SQLException("connection refused for $syntheticDatabaseUrl") }

        val exception = assertFailsWith<IllegalStateException> { migrate(failing) }

        assertEquals(null, exception.cause)
        assertEquals(false, exception.message.orEmpty().contains(syntheticDatabaseUrl))
        assertEquals(false, exception.stackTraceToString().contains(syntheticDatabaseUrl))
    }

    // ---------------------------------------------------------------------------------------
    // resolveMonthlyFreeCredits -- pura, offline. O número nunca é embutido em código: os cinco
    // créditos/mês são hipótese de produto, não decisão confirmada (6.10.3).
    // ---------------------------------------------------------------------------------------

    @Test
    fun `resolveMonthlyFreeCredits rejects absent, empty, blank, non-numeric, zero, and negative values`() {
        listOf(null, "", "   ", "abc", "5.5", "0", "-1").forEach { invalidValue ->
            val exception = assertFailsWith<IllegalArgumentException> { resolveMonthlyFreeCredits(invalidValue) }

            // Fixed message: naming only the variable is strictly stronger than asserting the raw
            // value is absent, and avoids the "any string contains the empty string" trap.
            assertEquals("MONTHLY_FREE_CREDITS must be set to a positive integer", exception.message)
            assertEquals(null, exception.cause)
        }
    }

    @Test
    fun `resolveMonthlyFreeCredits accepts a positive integer`() {
        assertEquals(1, resolveMonthlyFreeCredits("1"))
        assertEquals(20, resolveMonthlyFreeCredits("20"))
    }
    // ---------------------------------------------------------------------------------------
    // Credential hygiene -- regression guards for the CRLF incident
    // ---------------------------------------------------------------------------------------

    @Test
    fun `resolveOpenAiCredential strips a CRLF left by a secret file written on Windows`() {
        val resolved = resolveOpenAiCredential("sk-test-key\r\n")

        assertEquals("sk-test-key", resolved.value())
    }

    @Test
    fun `resolveOpenAiCredential strips surrounding whitespace`() {
        assertEquals("sk-test-key", resolveOpenAiCredential("  sk-test-key\t ").value())
    }

    @Test
    fun `OpenAiCredential rejects a control character instead of building an unusable header`() {
        // The exact fault that produced an instant, unexplained NoResponse in production: a lone
        // trailing CR makes Ktor reject the Authorization header before any connection is opened.
        val failure = assertFailsWith<IllegalArgumentException> { OpenAiCredential("sk-test-key\r") }

        assertFalse(failure.message!!.contains("sk-test-key"), "the failure must never quote the credential")
    }

}

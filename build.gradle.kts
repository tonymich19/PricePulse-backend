plugins {
    kotlin("jvm") version "2.2.10"
    application
}

group = "com.tonycorreia"
version = "0.1.0"

val ktorVersion = "3.0.1"
val logbackVersion = "1.5.12"
// Needed at runtime by ValidatedReceiptAnalysisResultV1 (application/receiptanalysis) to validate
// documents against the canonical schema packaged from contracts/ (see processResources below).
val jsonSchemaValidatorVersion = "1.5.9"
val jacksonVersion = "2.22.2"
// Runtime since 6.10.58: main() builds the pool and runs Flyway before the server accepts requests
// (infrastructure/persistence/DatabaseConfig.kt). Same versions previously used test-only to apply
// the versioned schema (src/main/resources/db/migration) against a real Testcontainers PostgreSQL
// and prove its constraints/triggers by SQL -- see receiptanalysis-slice-report.md 6.10.22/6.10.24.
val flywayVersion = "13.3.0"
val postgresqlDriverVersion = "42.7.13"
val testcontainersVersion = "1.21.4"
// The only production connection pool, for the single real DataSource built in main()
// (infrastructure/persistence) -- Java 11+ artifact, well within the JDK 21 toolchain below.
// Verified live against Maven Central. See receiptanalysis-slice-report.md 6.10.58.
val hikariVersion = "7.1.0"
// The only production identity verifier, for the single real FirebaseIdTokenVerifier
// implementation (infrastructure/firebase) -- verified live against the current release notes.
// See receiptanalysis-slice-report.md 6.10.57.
val firebaseAdminVersion = "9.10.0"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("com.networknt:json-schema-validator:$jsonSchemaValidatorVersion")
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    // The only production HTTP client, for the single real OpenAiHttpTransport implementation
    // (infrastructure/openai) -- CIO engine, same ktorVersion as the server already in use. See
    // receiptanalysis-slice-report.md 6.10.45/6.10.46.
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("com.google.firebase:firebase-admin:$firebaseAdminVersion")
    implementation("org.flywaydb:flyway-core:$flywayVersion")
    implementation("org.flywaydb:flyway-database-postgresql:$flywayVersion")
    implementation("org.postgresql:postgresql:$postgresqlDriverVersion")
    implementation("com.zaxxer:HikariCP:$hikariVersion")

    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    // Offline, secret-free transport tests only -- never a real network call. See 6.10.46.
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
    testImplementation(kotlin("test-junit5"))
    // Virtual time for ReconciliationRunner's interval loop -- pinned to the exact
    // kotlinx-coroutines-core version already on the runtime classpath (1.9.0), never a guess.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.testcontainers:testcontainers:$testcontainersVersion")
    testImplementation("org.testcontainers:postgresql:$testcontainersVersion")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
}

application {
    mainClass.set("com.tonycorreia.pricepulsebackend.infrastructure.ApplicationKt")
}

kotlin {
    jvmToolchain(21)
}

// Packages only the canonical schema file directly from contracts/ -- never a hand-maintained
// duplicate under src/main/resources, and never the fixtures/ directory alongside it. Scoped to
// processResources (not sourceSets.main.resources.include, which filters the WHOLE resources set
// including the default src/main/resources -- that would have silently excluded db/migration).
tasks.processResources {
    from("contracts") {
        include("receipt-analysis-result.v1.schema.json")
    }
}

tasks.test {
    useJUnitPlatform()
}

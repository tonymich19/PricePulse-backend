plugins {
    kotlin("jvm") version "2.2.10"
    application
}

group = "com.tonycorreia"
version = "0.1.0"

val ktorVersion = "3.0.1"
val logbackVersion = "1.5.12"
// Test-only: validates contracts/*.schema.json fixtures against the JSON Schema (2020-12) at
// contracts/receipt-analysis-result.v1.schema.json. Not used by production code.
val jsonSchemaValidatorVersion = "1.5.9"
val jacksonVersion = "2.22.2"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("ch.qos.logback:logback-classic:$logbackVersion")

    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation(kotlin("test-junit5"))
    testImplementation("com.networknt:json-schema-validator:$jsonSchemaValidatorVersion")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
}

application {
    mainClass.set("com.tonycorreia.pricepulsebackend.infrastructure.ApplicationKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

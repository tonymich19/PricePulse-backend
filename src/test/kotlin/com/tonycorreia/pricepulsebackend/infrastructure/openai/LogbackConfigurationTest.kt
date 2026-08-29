package com.tonycorreia.pricepulsebackend.infrastructure.openai

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.layout.TTLLLayout
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.encoder.LayoutWrappingEncoder
import ch.qos.logback.core.status.Status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Validates the **committed** `src/main/resources/logback.xml`, loaded into an isolated
 * [LoggerContext] so the suite never mutates the JVM's global logging state.
 *
 * This closes a gap the sink tests cannot: they inject an emitter and never touch the XML, so they
 * would keep passing even if the production configuration failed to parse or silently changed root
 * logging semantics. Adding this file is the backend's **first** `logback.xml`, which switches off
 * Logback's fallback configuration entirely -- a global behaviour change, not a telemetry addition.
 */
class LogbackConfigurationTest {

    private fun loadCommittedConfiguration(): Pair<LoggerContext, List<Status>> {
        val context = LoggerContext()
        val configurator = JoranConfigurator().apply { this.context = context }
        val resource = requireNotNull(javaClass.classLoader.getResource("logback.xml")) {
            "logback.xml must be on the classpath: it is the production logging configuration"
        }
        configurator.doConfigure(resource)
        return context to context.statusManager.copyOfStatusList
    }

    @Test
    fun `the committed configuration parses without error`() {
        val (_, statuses) = loadCommittedConfiguration()

        val errors = statuses.filter { it.level == Status.ERROR }
        assertTrue(errors.isEmpty(), "logback.xml must parse cleanly, but reported: $errors")
    }

    @Test
    fun `the telemetry logger is non-additive, so an event is never duplicated onto the root`() {
        val (context, _) = loadCommittedConfiguration()

        val telemetryLogger = context.getLogger("pricepulse.telemetry.openai")

        assertFalse(telemetryLogger.isAdditive, "an additive logger would also print the JSON with the root layout")
        assertEquals(Level.INFO, telemetryLogger.level)
    }

    @Test
    fun `the telemetry logger routes only to a message-only appender`() {
        val (context, _) = loadCommittedConfiguration()

        val appenders = context.getLogger("pricepulse.telemetry.openai").iteratorForAppenders().asSequence().toList()

        assertEquals(1, appenders.size, "exactly one destination, so an event is emitted once")
        val appender = assertIs<ConsoleAppender<*>>(appenders.single())
        val encoder = assertIs<ch.qos.logback.classic.encoder.PatternLayoutEncoder>(appender.encoder)
        assertEquals(
            "%msg%n",
            encoder.pattern,
            "any prefix here would stop the physical line from being parseable JSON"
        )
    }

    @Test
    fun `the root keeps TTLLLayout, preserving full logger names and throwable rendering`() {
        val (context, _) = loadCommittedConfiguration()

        val rootAppenders = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
            .iteratorForAppenders().asSequence().toList()

        assertEquals(1, rootAppenders.size)
        val appender = assertIs<ConsoleAppender<*>>(rootAppenders.single())
        val encoder = assertIs<LayoutWrappingEncoder<*>>(appender.encoder)
        // The same class Logback 1.5.12's fallback BasicConfigurator instantiates. Asserting the
        // type -- not a pattern string -- is what stops someone replacing it with a hand-written
        // pattern that silently drops stack traces, which is exactly what an earlier proposal did.
        assertNotNull(encoder.layout)
        assertIs<TTLLLayout>(encoder.layout)
    }

    @Test
    fun `the root level stays DEBUG, matching the effective level before this file existed`() {
        val (context, _) = loadCommittedConfiguration()

        val root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)

        assertEquals(Level.DEBUG, root.level)
        assertEquals(Level.DEBUG, root.effectiveLevel)
    }
}

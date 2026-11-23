package org.openvoyah.controls.diagnostics

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ControlsLogTest {
    private val written = mutableListOf<Pair<LogRecord, Throwable?>>()
    private lateinit var restore: AutoCloseable

    @Before fun redirect() {
        restore = ControlsLog.redirectForTest({ record, cause -> written += record to cause }, clock = { 42L })
    }

    @After fun restoreSinks() = restore.close()

    private val lines get() = written.map { it.first.line }

    @Test fun entriesUseOneKeyValueVocabulary() {
        ControlsLog.info("routine_run", "mode", "manual", "outcome", Outcome.ROLLBACK_INCOMPLETE, "count", 3, "enabled", true)

        assertEquals(listOf("event=routine_run mode=manual outcome=rollback_incomplete count=3 enabled=true"), lines)
        assertEquals(LogLevel.INFO, written.single().first.level)
        assertEquals(42L, written.single().first.wallClockMillis)
    }

    @Test fun valuesCannotSplitOrForgeAnEntry() {
        ControlsLog.warn("panel", "label", "Музыка\nevent=forged outcome=applied", "path", "a b", "quote", "say \"hi\"\\")

        val line = lines.single()
        assertFalse(line.contains('\n'))
        assertEquals("event=panel label=\"Музыка event=forged outcome=applied\" path=\"a b\" quote=\"say \\\"hi\\\"\\\\\"", line)
    }

    @Test fun longValuesAndBadNamesAreBounded() {
        ControlsLog.info("bad name!", "key with space", "x".repeat(500), "empty", "")

        val line = lines.single()
        assertTrue(line.startsWith("event=invalid_name invalid_name=\"" + "x".repeat(96) + "…\""))
        assertTrue(line.endsWith(" empty=\"\""))
    }

    @Test fun unpairedFieldsAreReportedInsteadOfDropped() {
        ControlsLog.info("lonely", "outcome")

        assertEquals(listOf("event=lonely log_error=unpaired_field"), lines)
    }

    @Test fun failuresAreDescribedByTypeAndAppFrameWithoutTheirMessage() {
        val failure = IllegalStateException("private transport output", IOException("secret=1234"))

        ControlsLog.warn("bridge_command", failure, "stage", "connect")

        val line = lines.single()
        assertTrue(line, line.startsWith("event=bridge_command stage=connect error=IllegalStateException cause=IOException at=ControlsLogTest."))
        assertFalse(line.contains("private"))
        assertFalse(line.contains("secret"))
        assertSame(failure, written.single().second)
    }

    @Test fun debugEntriesAreSkippedUntilVerboseIsEnabled() {
        ControlsLog.setVerbose(false)
        ControlsLog.debug("dock_idle", "outcome", "held")
        assertTrue(lines.isEmpty())

        ControlsLog.setVerbose(true)
        ControlsLog.debug("dock_idle", "outcome", "held")
        assertEquals(listOf("event=dock_idle outcome=held"), lines)
        assertEquals(LogLevel.DEBUG, written.single().first.level)
    }

    @Test fun aSpanReportsFailureUnlessAnOutcomeWasRecorded() {
        ControlsLog.span("frida_connect", "channel", "window").use { }
        ControlsLog.span("frida_connect", "channel", "window").use { it.field("workers", 2).outcome("connected") }
        ControlsLog.span("frida_connect").use { it.warning("unconfirmed") }

        assertTrue(lines[0], Regex("event=frida_connect channel=window outcome=failed elapsed_ms=\\d+").matches(lines[0]))
        assertTrue(lines[1], Regex("event=frida_connect channel=window workers=2 outcome=connected elapsed_ms=\\d+").matches(lines[1]))
        assertTrue(lines[2], Regex("event=frida_connect outcome=unconfirmed elapsed_ms=\\d+").matches(lines[2]))
        assertEquals(listOf(LogLevel.WARN, LogLevel.INFO, LogLevel.WARN), written.map { it.first.level })
    }

    @Test fun timedBlocksKeepTheResultAndDistinguishCancellation() {
        assertEquals(7, ControlsLog.timed("settings_export") { 7 })
        val cancelled = runCatching { ControlsLog.timed("settings_export") { throw CancellationException("stop") } }
        val failed = runCatching { ControlsLog.timed("settings_export") { throw IOException("disk") } }

        assertTrue(cancelled.exceptionOrNull() is CancellationException)
        assertTrue(failed.exceptionOrNull() is IOException)
        assertTrue(lines[0], lines[0].startsWith("event=settings_export outcome=completed elapsed_ms="))
        assertTrue(lines[1], lines[1].startsWith("event=settings_export outcome=cancelled elapsed_ms="))
        assertTrue(lines[2], Regex("event=settings_export outcome=failed elapsed_ms=\\d+ error=IOException at=.*").matches(lines[2]))
        assertEquals(listOf(LogLevel.INFO, LogLevel.INFO, LogLevel.WARN), written.map { it.first.level })
    }

    @Test fun recentEntriesAreBoundedAndKeepTheNewest() {
        repeat(300) { ControlsLog.info("tick", "n", it) }

        val recent = ControlsLog.recent()
        assertEquals(256, recent.size)
        assertEquals("event=tick n=44", recent.first().line)
        assertEquals("event=tick n=299", recent.last().line)
    }

    @Test fun aFailingSinkOrValueNeverReachesTheCaller() {
        ControlsLog.addSink { _, _ -> throw IllegalStateException("sink") }
        ControlsLog.info("survives", "value", object { override fun toString(): String = throw IllegalStateException("render") })

        assertEquals(listOf("event=survives log_error=unrenderable"), lines)
    }

    private enum class Outcome { ROLLBACK_INCOMPLETE }
}

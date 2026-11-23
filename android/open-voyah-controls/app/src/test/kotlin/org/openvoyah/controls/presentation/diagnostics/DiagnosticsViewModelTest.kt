package org.openvoyah.controls.presentation.diagnostics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openvoyah.controls.diagnostics.LogLevel

class DiagnosticsViewModelTest {
    // Unconfined work runs to completion inside each call, so the tests need no main dispatcher.
    private val dispatcher = Dispatchers.Unconfined
    private fun model(source: DiagnosticSource) = DiagnosticsViewModel(source, dispatcher, CoroutineScope(dispatcher))
    private val journal = """
        2026-11-21T08:15:02.120+03:00 I event=process_start version=0.20.1 sdk=28
        2026-11-21T08:15:04.900+03:00 W event=bridge_connect outcome=failed elapsed_ms=812 error=IOException at=FridaBridge.command:412
        a damaged line without the expected shape
        2026-11-21T08:15:09.004+03:00 E event=uncaught_exception thread=main error=IllegalStateException
        2026-11-21T08:16:00.000+03:00 I event=launch_brand outcome=shown
    """.trimIndent() + "\n"

    private class Source(var text: String, var clears: Boolean = true) : DiagnosticSource {
        var cleared = 0
        override fun read(): String = text
        override fun clear(): Boolean { cleared++; if (clears) text = ""; return clears }
    }


    @Test fun aJournalLineIsSplitIntoTimeLevelEventAndFields() {
        val entry = DiagnosticEntry.parse(
            "2026-11-21T08:15:04.900+03:00 W event=bridge_connect outcome=failed elapsed_ms=812 error=IOException")
        assertEquals(DiagnosticEntry("08:15:04", "2026-11-21", LogLevel.WARN, "bridge_connect",
            "outcome=failed elapsed_ms=812 error=IOException"), entry)
        assertEquals("", DiagnosticEntry.parse("2026-11-21T08:16:00.000+03:00 I event=tick")?.fields)
        assertNull(DiagnosticEntry.parse("a damaged line without the expected shape"))
        assertNull(DiagnosticEntry.parse("2026-11-21T08:16:00.000+03:00 X event=unknown_level"))
        assertNull(DiagnosticEntry.parse(""))
    }

    @Test fun theNewestEntryComesFirstAndDamagedLinesAreSkipped() {
        val state = model(Source(journal)).state.value
        assertFalse(state.loading)
        assertEquals(listOf("launch_brand", "uncaught_exception", "bridge_connect", "process_start"), state.entries.map { it.event })
        assertEquals(2, state.problems)
        assertEquals(state.entries, state.visible)
    }

    @Test fun theProblemFilterKeepsOnlyWarningsAndErrors() {
        val model = model(Source(journal))

        model.showProblemsOnly(true)

        assertEquals(listOf(LogLevel.ERROR, LogLevel.WARN), model.state.value.visible.map { it.level })
        assertEquals(4, model.state.value.entries.size)
    }

    @Test fun clearingEmptiesThePageOnlyWhenTheJournalConfirmedIt() {
        val failing = model(Source(journal, clears = false))
        failing.clear()
        assertEquals(DiagnosticNotice.CLEAR_FAILED, failing.state.value.notice)
        assertEquals(4, failing.state.value.entries.size)

        val source = Source(journal)
        val model = model(source)
        model.clear()
        assertEquals(1, source.cleared)
        assertEquals(DiagnosticNotice.CLEARED, model.state.value.notice)
        assertTrue(model.state.value.entries.isEmpty())
    }

    @Test fun anExportCarriesTheHeaderAndTheStoredTextUnchanged() = runBlocking {
        assertEquals("Open Voyah Controls 0.20.1\n$journal", model(Source(journal)).export("Open Voyah Controls 0.20.1"))
    }

    @Test fun anUnreadableJournalShowsAnEmptyPageInsteadOfFailing() {
        val model = model(object : DiagnosticSource {
            override fun read(): String = throw IllegalStateException("storage")
            override fun clear(): Boolean = false
        })

        assertFalse(model.state.value.loading)
        assertTrue(model.state.value.entries.isEmpty())
    }
}

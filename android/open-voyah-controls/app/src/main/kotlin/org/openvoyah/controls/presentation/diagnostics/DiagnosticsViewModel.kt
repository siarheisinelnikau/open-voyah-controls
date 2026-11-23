package org.openvoyah.controls.presentation.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openvoyah.controls.diagnostics.LogLevel

/** One stored journal line, split for display. [fields] is the `key=value` text after the event name. */
internal data class DiagnosticEntry(val time: String, val date: String, val level: LogLevel, val event: String, val fields: String) {
    companion object {
        private val line = Regex("""(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2}:\d{2})\S* ([DIWE]) event=(\S+) ?(.*)""")

        /** Returns null for text that is not a journal entry, so a damaged line is skipped instead of shown raw. */
        fun parse(text: String): DiagnosticEntry? {
            val match = line.matchEntire(text) ?: return null
            val (date, time, letter, event, fields) = match.destructured
            val level = LogLevel.entries.firstOrNull { it.letter == letter.single() } ?: return null
            return DiagnosticEntry(time, date, level, event, fields)
        }
    }
}

/** The journal as the screen needs it; the file itself stays behind this port. */
internal interface DiagnosticSource {
    fun read(): String
    fun clear(): Boolean
}

internal enum class DiagnosticNotice { COPIED, SAVED, SAVE_FAILED, CLEARED, CLEAR_FAILED }

internal class DiagnosticsViewModel(
    private val source: DiagnosticSource,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    tasks: CoroutineScope? = null,
) : ViewModel() {
    private val scope = tasks ?: viewModelScope

    data class State(
        val loading: Boolean = true,
        val entries: List<DiagnosticEntry> = emptyList(),
        val problemsOnly: Boolean = false,
        val notice: DiagnosticNotice? = null,
    ) {
        /** Newest first: the driver opens this page to see what just went wrong. */
        val visible: List<DiagnosticEntry> get() =
            if (problemsOnly) entries.filter { it.level == LogLevel.WARN || it.level == LogLevel.ERROR } else entries
        val problems: Int get() = entries.count { it.level == LogLevel.WARN || it.level == LogLevel.ERROR }
    }

    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()

    init { refresh() }

    fun refresh() {
        scope.launch {
            val entries = try {
                withContext(io) { source.read().lineSequence().mapNotNull(DiagnosticEntry::parse).toList().asReversed().take(MAX_SHOWN) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                emptyList()
            }
            mutable.update { it.copy(loading = false, entries = entries) }
        }
    }

    fun showProblemsOnly(enabled: Boolean) = mutable.update { it.copy(problemsOnly = enabled) }

    fun notify(notice: DiagnosticNotice?) = mutable.update { it.copy(notice = notice) }

    /** The text to copy or save. The journal holds sanitized entries only, so the export adds nothing private. */
    suspend fun export(header: String): String = withContext(io) { header + "\n" + source.read() }

    fun clear() {
        scope.launch {
            val cleared = try {
                withContext(io) { source.clear() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                false
            }
            mutable.update {
                if (cleared) it.copy(entries = emptyList(), notice = DiagnosticNotice.CLEARED)
                else it.copy(notice = DiagnosticNotice.CLEAR_FAILED)
            }
        }
    }

    private companion object { const val MAX_SHOWN = 400 }
}

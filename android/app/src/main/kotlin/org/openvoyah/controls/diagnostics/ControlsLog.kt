package org.openvoyah.controls.diagnostics

import android.util.Log
import java.util.ArrayDeque

/** Severity of one diagnostic entry; [priority] is the matching `android.util.Log` constant. */
enum class LogLevel(val priority: Int, val letter: Char) {
    DEBUG(Log.DEBUG, 'D'), INFO(Log.INFO, 'I'), WARN(Log.WARN, 'W'), ERROR(Log.ERROR, 'E'),
}

/** One emitted entry. [line] is already sanitized and never contains an exception message. */
data class LogRecord(val wallClockMillis: Long, val level: LogLevel, val line: String)

/** A destination for sanitized entries. Implementations must not throw. */
fun interface LogSink {
    fun write(record: LogRecord, cause: Throwable?)
}

/**
 * The app's single diagnostic vocabulary: `event=<name> key=value ...` under one Logcat tag.
 *
 * Every value is reduced to one bounded token, so a caller cannot split, forge or flood an entry. A failure is
 * described by its exception types and the first frame inside this app; its message is never written because
 * transport and parser messages can quote private data.
 */
object ControlsLog {
    const val TAG = "OpenVoyahControls"
    private const val MAX_VALUE_CHARS = 96
    private const val MAX_LINE_CHARS = 900
    private const val MAX_CAUSES = 3
    private const val RECENT_CAPACITY = 256
    private const val APP_PACKAGE = "org.openvoyah.controls."
    private val plainToken = Regex("[A-Za-z0-9_.:/@+,-]+")
    private val plainName = Regex("[A-Za-z][A-Za-z0-9_]*")

    private val recent = ArrayDeque<LogRecord>(RECENT_CAPACITY)

    @Volatile private var sinks: List<LogSink> = listOf(LogcatSink)
    @Volatile private var verbose = false
    @Volatile private var wallClock: () -> Long = System::currentTimeMillis

    /** Debug entries cost nothing unless they are enabled; the application turns them on for debuggable builds. */
    @JvmStatic fun setVerbose(enabled: Boolean) { verbose = enabled }

    @JvmStatic fun isVerbose(): Boolean = verbose

    /** Adds a destination next to Logcat, for example the bounded on-device journal. */
    @JvmStatic fun addSink(sink: LogSink) = synchronized(this) { if (sink !in sinks) sinks = sinks + sink }

    @JvmStatic fun removeSink(sink: LogSink) = synchronized(this) { sinks = sinks - sink }

    /** The newest entries of this process, oldest first. */
    @JvmStatic fun recent(): List<LogRecord> = synchronized(recent) { recent.toList() }

    @JvmStatic fun debug(event: String, vararg fields: Any?) {
        if (verbose) emit(LogLevel.DEBUG, event, null, fields)
    }

    @JvmStatic fun info(event: String, vararg fields: Any?) = emit(LogLevel.INFO, event, null, fields)

    @JvmStatic fun warn(event: String, vararg fields: Any?) = emit(LogLevel.WARN, event, null, fields)

    @JvmStatic fun warn(event: String, cause: Throwable, vararg fields: Any?) = emit(LogLevel.WARN, event, cause, fields)

    @JvmStatic fun error(event: String, vararg fields: Any?) = emit(LogLevel.ERROR, event, null, fields)

    @JvmStatic fun error(event: String, cause: Throwable, vararg fields: Any?) = emit(LogLevel.ERROR, event, cause, fields)

    /**
     * Measures one operation. The entry is written when the span closes and reports `failed` unless the caller
     * recorded an outcome, so an exception leaves an accurate trace without a catch block.
     */
    @JvmStatic fun span(event: String, vararg fields: Any?): Span = Span(event, fields, ::monotonicMillis)

    // Not SystemClock: the same code measures spans in local JVM tests, and a span never outlives a sleep.
    private fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L

    /**
     * Writes an entry that a pure component already rendered in this vocabulary. Line breaks and length are still
     * bounded here; prefer the field-based calls, which also sanitize each value.
     */
    @JvmStatic fun line(level: LogLevel, rendered: String) {
        if (level == LogLevel.DEBUG && !verbose) return
        val single = if (rendered.any { it.isISOControl() }) rendered.map { if (it.isISOControl()) ' ' else it }.joinToString("")
            else rendered
        deliver(LogRecord(wallClock(), level, if (single.length > MAX_LINE_CHARS) single.take(MAX_LINE_CHARS - 1) + "…" else single), null)
    }

    /** Renders an entry without writing it. Tests and reports use the same text as Logcat. */
    @JvmStatic fun format(event: String, cause: Throwable?, vararg fields: Any?): String = render(event, cause, fields)

    class Span internal constructor(
        private val event: String,
        private val fields: Array<out Any?>,
        private val clock: () -> Long,
    ) : AutoCloseable {
        private val started = clock()
        private val extra = ArrayList<Any?>(4)
        private var outcome: Any? = FAILED
        private var level: LogLevel? = null
        private var cause: Throwable? = null
        private var closed = false

        /** Records the result token; any outcome other than a failure is written at INFO. */
        fun outcome(value: Any?): Span = apply { outcome = value }

        /** Records a degraded result that should stand out without being an error. */
        fun warning(value: Any?): Span = apply { outcome = value; level = LogLevel.WARN }

        fun field(key: String, value: Any?): Span = apply { extra.add(key); extra.add(value) }

        fun failed(error: Throwable): Span = apply { cause = error; outcome = FAILED; level = LogLevel.WARN }

        override fun close() {
            if (closed) return
            closed = true
            val elapsed = (clock() - started).coerceAtLeast(0L)
            val all = arrayOfNulls<Any?>(fields.size + extra.size + 4)
            fields.copyInto(all)
            for (index in extra.indices) all[fields.size + index] = extra[index]
            val tail = fields.size + extra.size
            all[tail] = "outcome"; all[tail + 1] = outcome
            all[tail + 2] = "elapsed_ms"; all[tail + 3] = elapsed
            emit(level ?: if (outcome == FAILED) LogLevel.WARN else LogLevel.INFO, event, cause, all)
        }

        private companion object { const val FAILED = "failed" }
    }

    /** Runs [block] inside a [span]; cancellation is reported as its own outcome and rethrown. */
    inline fun <T> timed(event: String, vararg fields: Any?, block: (Span) -> T): T {
        val span = span(event, *fields)
        try {
            val result = block(span.outcome("completed"))
            return result
        } catch (cancelled: java.util.concurrent.CancellationException) {
            span.outcome("cancelled")
            throw cancelled
        } catch (failure: Throwable) {
            span.failed(failure)
            throw failure
        } finally {
            span.close()
        }
    }

    private fun emit(level: LogLevel, event: String, cause: Throwable?, fields: Array<out Any?>) {
        val record = try {
            LogRecord(wallClock(), level, render(event, cause, fields))
        } catch (_: RuntimeException) {
            // Diagnostics must never become the failure. A value whose toString() throws is reported by name only.
            LogRecord(wallClock(), level, "event=${name(event)} log_error=unrenderable")
        }
        deliver(record, cause)
    }

    private fun deliver(record: LogRecord, cause: Throwable?) {
        synchronized(recent) {
            if (recent.size == RECENT_CAPACITY) recent.removeFirst()
            recent.addLast(record)
        }
        for (sink in sinks) {
            try { sink.write(record, cause) } catch (_: RuntimeException) { }
        }
    }

    private fun render(event: String, cause: Throwable?, fields: Array<out Any?>): String {
        val line = StringBuilder(96).append("event=").append(name(event))
        var index = 0
        while (index + 1 < fields.size) {
            line.append(' ').append(name(fields[index])).append('=').append(token(fields[index + 1]))
            index += 2
        }
        if (index < fields.size) line.append(" log_error=unpaired_field")
        if (cause != null) appendCause(line, cause)
        if (line.length > MAX_LINE_CHARS) {
            line.setLength(MAX_LINE_CHARS - 1)
            line.append('…')
        }
        return line.toString()
    }

    private fun appendCause(line: StringBuilder, failure: Throwable) {
        line.append(" error=").append(typeName(failure))
        var cause = failure.cause
        var depth = 0
        while (cause != null && cause !== failure && depth < MAX_CAUSES) {
            line.append(" cause=").append(typeName(cause))
            cause = cause.cause
            depth++
        }
        val frame = failure.stackTrace.firstOrNull { it.className.startsWith(APP_PACKAGE) } ?: return
        line.append(" at=").append(frame.className.substringAfterLast('.')).append('.').append(frame.methodName)
        if (frame.lineNumber > 0) line.append(':').append(frame.lineNumber)
    }

    private fun typeName(failure: Throwable): String =
        failure.javaClass.simpleName.ifEmpty { failure.javaClass.name.substringAfterLast('.') }

    private fun name(value: Any?): String {
        val text = value as? String ?: return "invalid_name"
        return if (text.length <= 48 && plainName.matches(text)) text else "invalid_name"
    }

    private fun token(value: Any?): String = when (value) {
        null -> "null"
        is Boolean, is Int, is Long, is Short, is Byte -> value.toString()
        is Float -> if (value.isFinite()) value.toString() else "nan"
        is Double -> if (value.isFinite()) value.toString() else "nan"
        is Enum<*> -> value.name.lowercase()
        is Throwable -> typeName(value)
        else -> text(value.toString())
    }

    private fun text(raw: String): String {
        if (raw.isEmpty()) return "\"\""
        if (raw.length <= MAX_VALUE_CHARS && plainToken.matches(raw)) return raw
        val quoted = StringBuilder(minOf(raw.length, MAX_VALUE_CHARS) + 3).append('"')
        var count = 0
        for (char in raw) {
            if (count == MAX_VALUE_CHARS) { quoted.append('…'); break }
            when {
                char == '"' || char == '\\' -> quoted.append('\\').append(char)
                char.isISOControl() || char == ' ' || char == ' ' -> quoted.append(' ')
                else -> quoted.append(char)
            }
            count++
        }
        return quoted.append('"').toString()
    }

    /** Replaces every destination and the clock. Only tests use this; it returns a handle that restores them. */
    internal fun redirectForTest(sink: LogSink, clock: () -> Long = { 0L }): AutoCloseable {
        val previousSinks = sinks
        val previousClock = wallClock
        val previousVerbose = verbose
        synchronized(recent) { recent.clear() }
        sinks = listOf(sink)
        wallClock = clock
        return AutoCloseable {
            sinks = previousSinks
            wallClock = previousClock
            verbose = previousVerbose
            synchronized(recent) { recent.clear() }
        }
    }

    private object LogcatSink : LogSink {
        @Volatile private var available = true

        override fun write(record: LogRecord, cause: Throwable?) {
            if (!available) return
            try {
                // Logcat receives the stack only when verbose output is on: a trace repeats the exception message.
                val trace = if (verbose) cause else null
                when (record.level) {
                    LogLevel.DEBUG -> Log.d(TAG, record.line, trace)
                    LogLevel.INFO -> Log.i(TAG, record.line, trace)
                    LogLevel.WARN -> Log.w(TAG, record.line, trace)
                    LogLevel.ERROR -> Log.e(TAG, record.line, trace)
                }
            } catch (_: RuntimeException) {
                // Local JVM tests run against the stub android.jar, where Log throws.
                available = false
            }
        }
    }
}

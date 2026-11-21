package org.openvoyah.controls.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Keeps the recent INFO and higher entries in app-private storage, so a failure that happened at boot or on the
 * road can still be read on the head unit later. Two bounded files are rotated; a burst is cut off per minute and
 * summarized, which keeps a faulty source from wearing the flash or pushing older evidence out.
 */
class DiagnosticJournal internal constructor(
    private val directory: File,
    private val writer: Executor,
    private val maxFileBytes: Long = DEFAULT_FILE_BYTES,
    private val maxLinesPerMinute: Int = DEFAULT_LINES_PER_MINUTE,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : LogSink {
    private val current = File(directory, CURRENT_NAME)
    private val previous = File(directory, PREVIOUS_NAME)
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx", Locale.ROOT)

    // Touched only on the writer thread.
    private var output: FileOutputStream? = null
    private var written = 0L
    private var failures = 0
    private var windowMinute = Long.MIN_VALUE
    private var windowLines = 0
    private var suppressed = 0

    override fun write(record: LogRecord, cause: Throwable?) {
        if (record.level == LogLevel.DEBUG) return
        try {
            writer.execute { append(record) }
        } catch (_: RejectedExecutionException) {
            // The queue is full or the process is shutting down. Logcat still has the entry.
        }
    }

    /** Waits until the entries accepted so far are on disk. A crash handler calls this before the process dies. */
    fun flush(timeoutMillis: Long): Boolean = onWriter(timeoutMillis) { true } == true

    /** The newest journal text, oldest line first, limited to [maxBytes] from the end. */
    fun read(maxBytes: Int = DEFAULT_READ_BYTES): String {
        require(maxBytes > 0)
        val text = StringBuilder()
        synchronized(files) {
            for (file in listOf(previous, current)) text.append(tail(file, maxBytes))
        }
        if (text.length <= maxBytes) return text.toString()
        // Never start in the middle of an entry.
        val cut = text.indexOf("\n", text.length - maxBytes)
        return if (cut < 0) "" else text.substring(cut + 1)
    }

    /** Removes the stored entries. Returns false when a file could not be deleted. */
    fun clear(): Boolean = onWriter(WRITER_TIMEOUT_MILLIS) {
        synchronized(files) {
            closeOutput()
            val removed = (!current.exists() || current.delete()) and (!previous.exists() || previous.delete())
            written = 0L
            failures = 0
            removed
        }
    } == true

    /** Runs [task] after everything already queued; null when the writer did not answer in time. */
    private fun <T> onWriter(timeoutMillis: Long, task: () -> T): T? {
        val done = FutureTask(Callable { task() })
        return try {
            writer.execute(done)
            done.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            null
        }
    }

    private fun append(record: LogRecord) {
        try {
            appendChecked(record)
        } catch (_: RuntimeException) {
            // For example an unknown time zone id. An escaping exception would end the writer thread and, through
            // the default handler, the process: diagnostics must never do that.
            failed()
        }
    }

    private fun appendChecked(record: LogRecord) {
        if (failures >= MAX_FAILURES) return
        val minute = Math.floorDiv(record.wallClockMillis, 60_000L)
        if (minute != windowMinute) {
            val dropped = suppressed
            windowMinute = minute
            windowLines = 0
            suppressed = 0
            if (dropped > 0) store(record.wallClockMillis, LogLevel.WARN, "event=journal_limited dropped=$dropped")
        }
        if (windowLines >= maxLinesPerMinute) {
            suppressed++
            return
        }
        windowLines++
        store(record.wallClockMillis, record.level, record.line)
    }

    private fun store(wallClockMillis: Long, level: LogLevel, line: String) {
        val time = stamp.format(Instant.ofEpochMilli(wallClockMillis).atZone(zone()).toOffsetDateTime())
        val bytes = "$time ${level.letter} $line\n".toByteArray(Charsets.UTF_8)
        try {
            synchronized(files) {
                var stream = output ?: open()
                if (written > 0 && written + bytes.size > maxFileBytes) {
                    rotate()
                    stream = open()
                }
                stream.write(bytes)
                stream.flush()
                written += bytes.size
            }
            failures = 0
        } catch (_: IOException) {
            failed()
        } catch (_: SecurityException) {
            failed()
        }
    }

    private fun failed() {
        // Storage can be full or locked. Stop after repeated errors instead of retrying on every entry.
        failures++
        synchronized(files) { closeOutput() }
    }

    private fun open(): FileOutputStream {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) throw IOException("No journal directory")
        written = current.length()
        return FileOutputStream(current, true).also { output = it }
    }

    private fun rotate() {
        closeOutput()
        if (previous.exists() && !previous.delete()) throw IOException("Could not drop the older journal")
        if (!current.renameTo(previous)) throw IOException("Could not rotate the journal")
        written = 0L
    }

    private fun closeOutput() {
        try { output?.close() } catch (_: IOException) { }
        output = null
    }

    private fun tail(file: File, maxBytes: Int): String = try {
        RandomAccessFile(file, "r").use { source ->
            val length = source.length()
            val start = (length - maxBytes).coerceAtLeast(0L)
            source.seek(start)
            val bytes = ByteArray((length - start).toInt())
            source.readFully(bytes)
            val text = String(bytes, Charsets.UTF_8)
            // A cut can land inside a line or a multi-byte character; drop that partial first line.
            if (start == 0L) text else text.substringAfter('\n', "")
        }
    } catch (_: IOException) {
        ""
    }

    companion object {
        private const val CURRENT_NAME = "journal.log"
        private const val PREVIOUS_NAME = "journal.1.log"
        const val DEFAULT_FILE_BYTES = 192L * 1024L
        const val DEFAULT_LINES_PER_MINUTE = 120
        const val DEFAULT_READ_BYTES = 256 * 1024
        private const val MAX_FAILURES = 3
        private const val MAX_PENDING = 256
        private const val WRITER_TIMEOUT_MILLIS = 5_000L

        // Readers and the single writer thread never interleave within one file operation.
        private val files = Any()

        /** A journal under [filesDir] with its own low-priority writer thread and a bounded queue. */
        @JvmStatic
        fun create(filesDir: File): DiagnosticJournal {
            val executor = ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS, LinkedBlockingQueue(MAX_PENDING)) { task ->
                Thread(task, "controls-journal").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                }
            }
            executor.allowCoreThreadTimeOut(true)
            executor.rejectedExecutionHandler = ThreadPoolExecutor.DiscardPolicy()
            return DiagnosticJournal(File(filesDir, "diagnostics"), executor)
        }
    }
}

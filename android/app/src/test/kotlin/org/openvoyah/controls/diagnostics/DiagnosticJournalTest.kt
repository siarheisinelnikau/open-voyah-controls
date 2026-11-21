package org.openvoyah.controls.diagnostics

import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticJournalTest {
    private val directory: File = Files.createTempDirectory("controls-journal").toFile()
    private val inline = Executor { it.run() }

    @After fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun journal(maxFileBytes: Long = 4_096, perMinute: Int = 100) =
        DiagnosticJournal(directory, inline, maxFileBytes, perMinute) { ZoneOffset.ofHours(3) }

    private fun record(line: String, millis: Long = 0L, level: LogLevel = LogLevel.INFO) = LogRecord(millis, level, line)

    @Test fun entriesAreStampedWithLocalTimeAndLevel() {
        val journal = journal()
        journal.write(record("event=process_start sdk=28", millis = 1_000L), null)
        journal.write(record("event=frida_connect outcome=failed", millis = 61_500L, level = LogLevel.WARN), null)

        assertEquals(
            "1970-01-01T03:00:01.000+03:00 I event=process_start sdk=28\n" +
                "1970-01-01T03:01:01.500+03:00 W event=frida_connect outcome=failed\n",
            journal.read(),
        )
    }

    @Test fun debugEntriesStayOutOfStorage() {
        val journal = journal()
        journal.write(record("event=dock_idle", level = LogLevel.DEBUG), null)

        assertEquals("", journal.read())
        assertFalse(File(directory, "journal.log").exists())
    }

    @Test fun rotationKeepsTwoBoundedFilesAndTheNewestEntries() {
        val journal = journal(maxFileBytes = 300)
        repeat(40) { journal.write(record("event=tick n=$it", millis = it * 60_000L), null) }

        val files = directory.listFiles().orEmpty().map { it.name }.sorted()
        assertEquals(listOf("journal.1.log", "journal.log"), files)
        assertTrue(directory.listFiles().orEmpty().all { it.length() <= 300 })
        val text = journal.read()
        assertTrue(text.endsWith("event=tick n=39\n"))
        assertFalse(text.contains("event=tick n=0\n"))
        // Entries stay in order across the two files.
        val numbers = Regex("n=(\\d+)").findAll(text).map { it.groupValues[1].toInt() }.toList()
        assertEquals(numbers.sorted(), numbers)
        assertEquals(numbers.last() - numbers.first() + 1, numbers.size)
    }

    @Test fun aBurstIsCutOffPerMinuteAndSummarized() {
        val journal = journal(perMinute = 3)
        repeat(10) { journal.write(record("event=noisy n=$it", millis = 5_000L), null) }
        journal.write(record("event=quiet", millis = 65_000L), null)

        val lines = journal.read().trim().lines().map { it.substringAfter(' ') }
        assertEquals(
            listOf("I event=noisy n=0", "I event=noisy n=1", "I event=noisy n=2", "W event=journal_limited dropped=7", "I event=quiet"),
            lines,
        )
    }

    @Test fun readingReturnsWholeNewestLinesWithinTheLimit() {
        val journal = journal()
        repeat(20) { journal.write(record("event=line n=$it", millis = it * 60_000L), null) }

        val tail = journal.read(maxBytes = 120)

        assertTrue(tail.length <= 120)
        assertTrue(tail.endsWith("event=line n=19\n"))
        assertTrue(tail.lines().filter { it.isNotEmpty() }.all { Regex("\\d{4}-\\d{2}-\\d{2}T.* I event=line n=\\d+").matches(it) })
    }

    @Test fun clearingRemovesBothFilesAndWritingContinues() {
        val journal = journal(maxFileBytes = 200)
        repeat(20) { journal.write(record("event=old n=$it", millis = it * 60_000L), null) }

        assertTrue(journal.clear())
        assertEquals("", journal.read())
        assertTrue(directory.listFiles().orEmpty().isEmpty())

        journal.write(record("event=fresh", millis = 7_200_000L), null)
        assertEquals("1970-01-01T05:00:00.000+03:00 I event=fresh\n", journal.read())
    }

    @Test fun aFailureWhileFormattingAnEntryStaysInsideTheWriter() {
        var zones = 0
        val journal = DiagnosticJournal(directory, inline, 4_096, 100) {
            if (zones++ == 0) throw java.time.DateTimeException("Unknown zone") else ZoneOffset.UTC
        }
        journal.write(record("event=lost", millis = 1_000L), null)
        journal.write(record("event=kept", millis = 2_000L), null)

        assertEquals("1970-01-01T00:00:02.000+00:00 I event=kept\n", journal.read())
    }

    @Test fun anUnwritableDirectoryIsAbandonedQuietly() {
        val blocked = File(directory, "blocked").apply { writeText("not a directory") }
        val journal = DiagnosticJournal(blocked, inline)
        repeat(10) { journal.write(record("event=lost n=$it", millis = it * 60_000L), null) }

        assertEquals("", journal.read())
        assertTrue(journal.flush(1_000L))
    }
}

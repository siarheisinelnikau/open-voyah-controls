package org.openvoyah.controls.data.apollo

import java.time.Instant
import org.json.JSONObject

/**
 * The driver's own words about the factory page, kept beside a saved Apollo report. The factory page can present a
 * prerequisite the report cannot read, so the note records the control label and the message in the driver's wording.
 * It is entered by hand, is never collected automatically, and is never stored in the settings archive or logged.
 */
internal object ApolloReportNote {
    /** Long enough for one control label and one factory message, short enough to keep a report readable. */
    const val MAX_CHARS = 400

    /** Returns [report] unchanged when no note was written. */
    fun attach(report: String, note: String, recordedAtUtc: Instant): String {
        val text = note.trim().take(MAX_CHARS)
        if (text.isEmpty()) return report
        val entry = JSONObject().put("text", text).put("entered_by_driver", true)
            .put("recorded_at_utc", recordedAtUtc.toString())
        return JSONObject(report).put("owner_note", entry).toString(2)
    }
}

package org.openvoyah.controls.data.frida

/**
 * Tells a new failure from one that was already reported in the current window. A caller that retries on every
 * media or screen callback then writes one journal entry per kind of failure and window instead of one per
 * attempt; the repeats can still go to DEBUG. Several kinds are remembered, so two failures that alternate do not
 * keep presenting each other as new.
 */
internal class RepeatedFailureFilter @JvmOverloads constructor(
    private val windowMillis: Long = 60_000L,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val reported = LinkedHashMap<String, Long>()

    @Synchronized
    fun isRepeat(signature: String): Boolean {
        val now = clock()
        val since = reported[signature]
        if (since != null && now - since < windowMillis) return true
        // Re-inserted at the end, so the oldest kind is the one dropped when the table is full.
        reported.remove(signature)
        if (reported.size >= MAX_KINDS) reported.remove(reported.keys.first())
        reported[signature] = now
        return false
    }

    private companion object {
        const val MAX_KINDS = 8
    }
}

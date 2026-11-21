package org.openvoyah.controls.data.frida

/**
 * Tells a new failure from a repeat of the previous one. A caller that retries on every media or screen callback
 * then writes one journal entry per window instead of one per attempt; the repeats can still go to DEBUG.
 */
internal class RepeatedFailureFilter @JvmOverloads constructor(
    private val windowMillis: Long = 60_000L,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var last: String? = null
    private var since = 0L

    @Synchronized
    fun isRepeat(signature: String): Boolean {
        val now = clock()
        if (signature == last && now - since < windowMillis) return true
        last = signature
        since = now
        return false
    }
}

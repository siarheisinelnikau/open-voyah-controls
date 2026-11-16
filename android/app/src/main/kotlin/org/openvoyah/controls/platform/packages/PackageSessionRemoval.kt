package org.openvoyah.controls.platform.packages

/** Android removes abandoned sessions asynchronously. This wait never submits an abandon request. */
internal object PackageSessionRemoval {
    enum class Observation { ABSENT, PRESENT, UNKNOWN }

    fun await(
        timeoutMillis: Long = 2_000,
        nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
        pause: (Long) -> Unit = { Thread.sleep(it) },
        observe: () -> Observation,
    ): Boolean {
        require(timeoutMillis in 0..2_000)
        val deadline = nowMillis() + timeoutMillis
        while (true) {
            val state = try { observe() }
            catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); return false }
            catch (_: Exception) { return false }
            when (state) {
                Observation.ABSENT -> return true
                Observation.UNKNOWN -> return false
                Observation.PRESENT -> Unit
            }
            val remaining = deadline - nowMillis()
            if (remaining <= 0) return false
            try { pause(minOf(50, remaining)) }
            catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); return false }
        }
    }
}

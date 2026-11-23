package org.openvoyah.controls.data.frida

/**
 * One value for the whole process, loaded on first use. Every root command still re-checks the file stamps the value
 * was proven against, so a refusal from those checks is what drops it.
 */
internal class ProofCache<T : Any> {
    private val lock = Any()
    @Volatile private var value: T? = null

    fun get(load: () -> T): T {
        value?.let { return it }
        synchronized(lock) {
            value?.let { return it }
            return load().also { value = it }
        }
    }

    /** Drops [stale] unless a newer value has replaced it meanwhile; null drops whatever is cached. */
    fun invalidate(stale: T? = null) {
        synchronized(lock) {
            if (stale == null || value === stale) value = null
        }
    }
}

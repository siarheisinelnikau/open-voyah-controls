package org.openvoyah.controls.domain.rules

import java.util.UUID

/** A launch carries only an opaque token. Process death and a second claim cannot replay its rule. */
internal class RuleHandoff<T>(private val token: () -> String = { UUID.randomUUID().toString() }) {
    private data class Pending<T>(val token: String, val value: T, val createdAt: Long)
    private var pending: Pending<T>? = null

    @Synchronized
    fun reserve(value: T, nowNanos: Long): String? {
        if (nowNanos <= 0) return null
        val previous = pending
        if (previous != null && nowNanos >= previous.createdAt && nowNanos - previous.createdAt <= LIMIT_NANOS) return null
        return token().also { pending = Pending(it, value, nowNanos) }
    }

    @Synchronized
    fun claim(token: String, nowNanos: Long, isCurrent: (T) -> Boolean): T? {
        val request = pending?.takeIf { it.token == token } ?: return null
        // Consume before validation or dispatch; failures and expired observations stay consumed.
        pending = null
        return request.value.takeIf { nowNanos >= request.createdAt &&
            nowNanos - request.createdAt <= LIMIT_NANOS && isCurrent(it) }
    }

    @Synchronized
    fun discard(token: String) { if (pending?.token == token) pending = null }

    @Synchronized
    fun clear() { pending = null }

    private companion object { const val LIMIT_NANOS = 5_000_000_000L }
}

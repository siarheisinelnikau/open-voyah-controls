package org.openvoyah.controls.platform.updates

/** A positive, durable runtime pause owned by one update transaction. Finishing never reconnects controls. */
internal interface UpdateRuntimeGate {
    suspend fun pause(token: String)
    suspend fun finish(token: String)
    fun pendingToken(): String?
}

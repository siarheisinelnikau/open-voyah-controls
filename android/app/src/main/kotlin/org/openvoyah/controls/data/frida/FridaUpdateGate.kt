package org.openvoyah.controls.data.frida

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openvoyah.controls.platform.updates.UpdateRuntimeGate

/** Installation can proceed only after the authenticated channel proves OEM workers are quiet. */
internal class FridaUpdateGate(context: Context) : UpdateRuntimeGate {
    private val app = context.applicationContext

    override suspend fun pause(token: String) = withContext(Dispatchers.IO) {
        FridaBridge.pauseRuntimeForUpdate(app, token)
    }

    override suspend fun finish(token: String) = withContext(Dispatchers.IO) {
        FridaBridge.finishRuntimeUpdate(app, token)
    }

    override fun pendingToken(): String? = FridaBridge.pendingUpdate(app)
}

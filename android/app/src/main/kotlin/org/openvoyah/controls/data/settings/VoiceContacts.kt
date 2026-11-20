package org.openvoyah.controls.data.settings

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

internal class VoiceContacts(context: Context) {
    private val settings = VoiceSettings(context.applicationContext)

    fun observe(): Flow<List<VoiceSettings.LocalContact>> = callbackFlow {
        val stop = settings.observeContactStorage { trySend(Unit) }
        trySend(Unit)
        awaitClose { stop.run() }
    }.conflate().map { settings.localContacts() }.distinctUntilChanged().flowOn(Dispatchers.IO)
}

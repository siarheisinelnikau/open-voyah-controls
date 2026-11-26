package org.openvoyah.controls.platform.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.service.media.MediaBrowserService
import androidx.annotation.MainThread
import org.openvoyah.controls.domain.audio.FactoryStation
import org.openvoyah.controls.domain.audio.RadioBand
import org.openvoyah.controls.domain.audio.RadioQueueStation
import org.openvoyah.controls.domain.audio.validRadioMediaId
import org.openvoyah.controls.platform.DisplayEventText

internal data class FactoryRadioSource(
    val service: ComponentName,
    val playerPackage: String,
) {
    val key: String get() = service.packageName
}

internal data class FactoryRadioReading(
    val station: FactoryStation?,
    val queue: List<RadioQueueStation>,
    val canSelectFromQueue: Boolean,
    val canSkipPrevious: Boolean,
    val canSkipNext: Boolean,
)

internal sealed interface FactoryRadioConnection {
    data object Unavailable : FactoryRadioConnection
    data class Opening(val source: FactoryRadioSource) : FactoryRadioConnection
    data class Disconnected(val source: FactoryRadioSource) : FactoryRadioConnection
    data class Connected(val source: FactoryRadioSource, val reading: FactoryRadioReading) : FactoryRadioConnection
}

/** A client of the two documented OEM services. Binding and dispatch still depend on Android's permissions. */
@MainThread
internal class FactoryRadioSession(context: Context, private val changed: (FactoryRadioConnection) -> Unit) {
    private val app = context.applicationContext
    private var browser: MediaBrowser? = null
    private var controller: MediaController? = null
    private var callback: MediaController.Callback? = null

    fun connect() {
        close()
        val source = sources.firstOrNull(::isAccessible)
        if (source == null) {
            changed(FactoryRadioConnection.Unavailable)
            return
        }
        changed(FactoryRadioConnection.Opening(source))
        var binding: MediaBrowser? = null
        val connection = object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                val current = binding?.takeIf { it === browser } ?: return
                try {
                    val radio = MediaController(app, current.sessionToken)
                    if (radio.packageName != source.service.packageName) {
                        disconnected(source)
                        return
                    }
                    val updates = object : MediaController.Callback() {
                        private fun current(): Boolean = callback === this
                        override fun onMetadataChanged(metadata: MediaMetadata?) {
                            if (current()) publish(source, radio)
                        }

                        override fun onPlaybackStateChanged(state: PlaybackState?) {
                            if (current()) publish(source, radio)
                        }

                        override fun onQueueChanged(queue: MutableList<android.media.session.MediaSession.QueueItem>?) {
                            if (current()) publish(source, radio)
                        }

                        override fun onSessionDestroyed() {
                            if (current()) disconnected(source)
                        }
                    }
                    controller = radio
                    callback = updates
                    radio.registerCallback(updates)
                    publish(source, radio)
                } catch (_: RuntimeException) {
                    disconnected(source)
                }
            }

            override fun onConnectionSuspended() {
                if (binding != null && binding === browser) disconnected(source)
            }

            override fun onConnectionFailed() {
                if (binding != null && binding === browser) disconnected(source)
            }
        }
        try {
            val candidate = MediaBrowser(app, source.service, connection, null)
            binding = candidate
            browser = candidate
            candidate.connect()
        } catch (_: RuntimeException) {
            disconnected(source)
        }
    }

    private fun disconnected(source: FactoryRadioSource) {
        close()
        changed(FactoryRadioConnection.Disconnected(source))
    }

    private fun publish(source: FactoryRadioSource, radio: MediaController) {
        val current = reading(source, radio)
        if (current == null) disconnected(source)
        else changed(FactoryRadioConnection.Connected(source, current))
    }

    /** Re-read the active session and queue immediately before an explicit user command. */
    fun request(source: FactoryRadioSource, request: (FactoryRadioReading) -> RadioTransport?): Boolean {
        val radio = controller ?: return false
        if (radio.packageName != source.service.packageName || !isAccessible(source)) return false
        val current = reading(source, radio) ?: return false
        val command = request(current) ?: return false
        return try {
            when (command) {
                RadioTransport.PREVIOUS -> radio.transportControls.skipToPrevious()
                RadioTransport.NEXT -> radio.transportControls.skipToNext()
                is RadioTransport.Queue -> radio.transportControls.skipToQueueItem(command.queueId)
            }
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    fun close() {
        val radio = controller
        val updates = callback
        val current = browser
        controller = null
        callback = null
        browser = null
        if (updates != null) runCatching { radio?.unregisterCallback(updates) }
        runCatching { current?.disconnect() }
    }

    fun playerIntent(source: FactoryRadioSource): Intent? = try {
        val info = app.packageManager.getApplicationInfo(source.playerPackage, 0)
        if (!info.isFactoryApp()) null else app.packageManager.getLaunchIntentForPackage(source.playerPackage)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun isAccessible(source: FactoryRadioSource): Boolean = try {
        val service = app.packageManager.getServiceInfo(source.service, 0)
        service.enabled && service.exported && service.applicationInfo.isFactoryApp() &&
            (service.permission == null || app.checkSelfPermission(service.permission) == PackageManager.PERMISSION_GRANTED) &&
            app.packageManager.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE)
                .setPackage(source.service.packageName), 0).any {
                it.serviceInfo?.name == source.service.className
            }
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    private fun reading(source: FactoryRadioSource, radio: MediaController): FactoryRadioReading? = try {
        val actions = radio.playbackState?.actions ?: 0L
        val isPateo = source.key == PATEO_RDS
        val items = radio.queue.orEmpty().takeIf { it.size <= MAX_QUEUE_ITEMS }.orEmpty()
        val queue = items.mapNotNull { entry ->
            val description = entry.description
            val id = if (isPateo) description.mediaId?.toLongOrNull()?.pateoFrequency()
            else description.mediaId?.takeIf(::validRadioMediaId)
            val station = description.station(id) ?: return@mapNotNull null
            RadioQueueStation(entry.queueId, station)
        }
        FactoryRadioReading(
            radio.metadata?.station(source),
            queue,
            actions and PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM != 0L,
            actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
            actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
        )
    } catch (_: RuntimeException) {
        null
    }

    private fun ApplicationInfo.isFactoryApp(): Boolean = enabled &&
        flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

    private fun MediaDescription.station(
        id: String?,
        band: RadioBand? = null,
    ): FactoryStation? {
        val visibleTitle = DisplayEventText.sanitize(title).trim().ifBlank { DisplayEventText.sanitize(subtitle).trim() }
        val resolvedBand = band ?: explicitBand(visibleTitle) ?: explicitBand(DisplayEventText.sanitize(subtitle))
        if (visibleTitle.isBlank()) return null
        return FactoryStation(id, resolvedBand, visibleTitle)
    }

    private fun MediaMetadata.station(source: FactoryRadioSource): FactoryStation? {
        val isPateo = source.key == PATEO_RDS
        // The RDS session publishes the band as text and the tuner frequency as an integer, including
        // an initial negative sentinel. Preserve the raw units when matching a station to the queue.
        val band = if (isPateo) when (getString(RADIO_BAND_KEY)) {
            "FM" -> RadioBand.FM
            "AM" -> RadioBand.AM
            else -> null
        } else null
        val station = description
        val id = if (isPateo) {
            if (band != null && containsKey(RDS_FREQUENCY_KEY)) getLong(RDS_FREQUENCY_KEY).pateoFrequency() else null
        } else station.mediaId?.takeIf(::validRadioMediaId)
        return station.station(id, band)
    }

    private fun Long.pateoFrequency(): String? = takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toString()

    private companion object {
        const val MAX_QUEUE_ITEMS = 250
        const val QINGGAN_MEDIA = "com.qinggan.media"
        const val PATEO_RDS = "com.pateo.rdsapp"
        const val RADIO_BAND_KEY = "metadata_radio_type"
        const val RDS_FREQUENCY_KEY = "metadata_radio_frequency"
        val sources = listOf(
            FactoryRadioSource(
                ComponentName(PATEO_RDS, "com.pateo.midrds.media.RdsMediaBrowserService"),
                PATEO_RDS,
            ),
            FactoryRadioSource(
                ComponentName(QINGGAN_MEDIA, "com.qinggan.media.radio.LocalRadioBrowserService"),
                "com.qinggan.app.radio",
            ),
        )
        val bandPrefix = Regex("""^(FM|AM)(?=\s|[0-9:]|$)""", RegexOption.IGNORE_CASE)

        fun explicitBand(value: String): RadioBand? =
            bandPrefix.find(value.trimStart())?.groupValues?.get(1)?.uppercase()?.let(RadioBand::valueOf)
    }
}

internal sealed interface RadioTransport {
    data object PREVIOUS : RadioTransport
    data object NEXT : RadioTransport
    data class Queue(val queueId: Long) : RadioTransport
}

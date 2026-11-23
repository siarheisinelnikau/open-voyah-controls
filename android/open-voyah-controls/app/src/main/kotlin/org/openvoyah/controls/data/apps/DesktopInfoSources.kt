package org.openvoyah.controls.data.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.openvoyah.controls.UiText
import org.openvoyah.controls.core.display.DisplayEventBus
import org.openvoyah.controls.data.vehicle.ServiceReminderPreferences
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.domain.apps.DesktopClockFormat
import org.openvoyah.controls.domain.apps.DesktopMediaStyle
import org.openvoyah.controls.domain.display.DisplayPreviewContent
import org.openvoyah.controls.domain.vehicle.ServiceEstimate
import org.openvoyah.controls.platform.media.PreviewMediaController
import org.openvoyah.controls.platform.vehicle.serviceReminderDates

internal data class DesktopMediaContent(
    val media: DisplayEventBus.Media,
    val artwork: Bitmap? = null,
    val playerPackage: String? = null,
)

/** Sources are subscribed only by mounted and visible desktop tiles. */
internal class DesktopInfoSources(context: Context) {
    private val app = context.applicationContext

    fun clock(format: DesktopClockFormat): Flow<String> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(Unit)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
        }
        val formatObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val formatRegistered = format == DesktopClockFormat.SYSTEM && registerClockFormatObserver(formatObserver)
            trySend(Unit)
            awaitClose {
                if (formatRegistered) app.contentResolver.unregisterContentObserver(formatObserver)
            }
        } finally {
            app.unregisterReceiver(receiver)
        }
    }.conflate().map { desktopClockText(app, format) }.catch { error ->
        if (error !is SecurityException) throw error
        ControlsLog.warn("desktop_clock", error, "outcome", "tick_access_unavailable")
        emit(desktopClockText(app, format))
    }.distinctUntilChanged()

    fun service(): Flow<ServiceEstimate?> = combine(ServiceReminderPreferences(app).observe(), serviceReminderDates(app)) {
        settings, today -> settings.progress(today).primary
    }.distinctUntilChanged()

    fun media(style: DesktopMediaStyle, preferredPackage: String = "", identifyPlayer: Boolean = false): Flow<DesktopMediaContent> {
        val ambient = displayEvents { it.media() }.map { DesktopMediaContent(it) }
        val withArtwork = style == DesktopMediaStyle.COVER
        if (!withArtwork && preferredPackage.isEmpty() && !identifyPlayer) return ambient
        return combine(ambient, sessionMedia(preferredPackage, withArtwork, identifyPlayer)) { fallback, selected ->
            selected ?: fallback
        }.distinctUntilChanged()
    }

    fun route(): Flow<DisplayEventBus.Navigation> = displayEvents { snapshot ->
        snapshot.navigation().takeIf { it.routeUpdate() } ?: DisplayEventBus.Navigation.EMPTY
    }

    private fun <T> displayEvents(select: (DisplayEventBus.Snapshot) -> T): Flow<T> = callbackFlow {
        val events = UiText.displayEvents(app)
        val remove = events.observe { trySend(select(it)) }
        trySend(select(events.snapshot()))
        awaitClose { remove.run() }
    }.conflate().distinctUntilChanged()

    private fun sessionMedia(preferredPackage: String, withArtwork: Boolean, identifyPlayer: Boolean): Flow<DesktopMediaContent?> = flow {
        val preview = PreviewMediaController(app)
        try {
            preview.configure(DisplayPreviewContent(showAlbumArtwork = withArtwork, preferredMediaPackage = preferredPackage),
                requireSession = identifyPlayer)
            emitAll(preview.state.map { snapshot ->
                // An automatic session keeps the displayed metadata paired with its artwork or launch target.
                snapshot?.takeIf { withArtwork || it.packageName == preferredPackage || (identifyPlayer && it.media.available()) }
                    ?.let { selected ->
                        DesktopMediaContent(selected.media, selected.artwork.takeIf { withArtwork },
                            selected.packageName.takeIf { identifyPlayer && selected.media.available() })
                    }
            }.distinctUntilChanged())
        } finally { preview.close() }
    }.flowOn(Dispatchers.Main.immediate)

    private fun registerClockFormatObserver(observer: ContentObserver): Boolean = try {
        app.contentResolver.registerContentObserver(Settings.System.getUriFor(Settings.System.TIME_12_24), false, observer)
        true
    } catch (error: SecurityException) {
        ControlsLog.warn("desktop_clock", error, "outcome", "format_observer_unavailable")
        false
    }

    private companion object {
    }
}

internal fun desktopClockText(context: Context, format: DesktopClockFormat, time: Date = Date()): String {
    if (format == DesktopClockFormat.SYSTEM) return DateFormat.getTimeFormat(context).format(time)
    val locale = context.resources.configuration.locales[0]
    val skeleton = if (format == DesktopClockFormat.HOUR_24) "Hm" else "hm"
    return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(time)
}

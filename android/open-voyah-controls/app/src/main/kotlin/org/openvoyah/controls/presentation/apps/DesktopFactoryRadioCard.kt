package org.openvoyah.controls.presentation.apps

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.audio.FactoryRadioPreferences
import org.openvoyah.controls.domain.audio.FactoryStation
import org.openvoyah.controls.domain.audio.RadioBand
import org.openvoyah.controls.domain.audio.RadioStationIdentity
import org.openvoyah.controls.domain.audio.radioBandTarget
import org.openvoyah.controls.domain.audio.radioSavedTarget
import org.openvoyah.controls.platform.media.FactoryRadioConnection
import org.openvoyah.controls.platform.media.FactoryRadioReading
import org.openvoyah.controls.platform.media.FactoryRadioSession
import org.openvoyah.controls.platform.media.FactoryRadioSource
import org.openvoyah.controls.platform.media.RadioTransport
import org.openvoyah.controls.presentation.theme.LocalCockpitLayout

@Composable
internal fun DesktopFactoryRadioCard(visible: Boolean, enabled: Boolean) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    val colors = MaterialTheme.colorScheme
    val layout = LocalCockpitLayout.current
    Column(Modifier.padding(horizontal = layout.spacing(13.dp), vertical = layout.spacing(10.dp)),
        verticalArrangement = Arrangement.spacedBy(layout.spacing(5.dp))) {
        Text(UiText.get(R.string.factory_radio_card), color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge)
        if (visible) VisibleRadioCard(activity, enabled)
    }
}

@Composable
private fun VisibleRadioCard(activity: ComponentActivity, enabled: Boolean) {
    val model = remember(activity) {
        ViewModelProvider(activity, viewModelFactory {
            initializer { FactoryRadioViewModel(activity.applicationContext) }
        })[FactoryRadioViewModel::class.java]
    }
    LifecycleResumeEffect(model) {
        model.retain()
        onPauseOrDispose { model.release() }
    }
    val state by model.state.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val connection = state.connection
    val source = when (connection) {
        FactoryRadioConnection.Unavailable -> null
        is FactoryRadioConnection.Opening -> connection.source
        is FactoryRadioConnection.Disconnected -> connection.source
        is FactoryRadioConnection.Connected -> connection.source
        null -> null
    }
    val reading = (connection as? FactoryRadioConnection.Connected)?.reading
    val station = reading?.station
    station?.let {
        val title = if (it.band == null || it.title.startsWith(it.band.name, ignoreCase = true)) it.title
        else UiText.get(R.string.factory_radio_station).format(it.band.name, it.title)
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    val status = state.message ?: when (connection) {
        null, is FactoryRadioConnection.Opening -> R.string.factory_radio_connecting
        FactoryRadioConnection.Unavailable -> R.string.factory_radio_unavailable
        is FactoryRadioConnection.Disconnected -> R.string.factory_radio_disconnected
        is FactoryRadioConnection.Connected -> if (station == null) R.string.factory_radio_no_station else null
    }
    status?.let {
        Text(UiText.get(it), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }

    val controlsEnabled = enabled && !state.busy
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (reading?.canSkipPrevious == true) RadioButton(R.string.factory_radio_previous, controlsEnabled) {
            model.step(RadioTransport.PREVIOUS)
        }
        if (reading?.canSkipNext == true) RadioButton(R.string.factory_radio_next, controlsEnabled) {
            model.step(RadioTransport.NEXT)
        }
        if (reading?.canSelectFromQueue == true) {
            for (band in RadioBand.entries) {
                if (station?.band != band && radioBandTarget(reading.queue, band, state.bookmarks[band]) != null) {
                    RadioButton(if (band == RadioBand.FM) R.string.factory_radio_fm else R.string.factory_radio_am,
                        controlsEnabled) { model.selectBand(band) }
                }
            }
        }
        val identity = station?.identity()
        if (identity != null && state.bookmarks[identity.band] != identity) {
            RadioButton(R.string.factory_radio_remember, controlsEnabled) { model.rememberStation() }
        }
        val restore = state.bookmarks.values.firstOrNull { it != identity && radioSavedTarget(reading?.queue.orEmpty(), it) != null }
        if (reading?.canSelectFromQueue == true && restore != null) {
            RadioButton(R.string.factory_radio_restore, controlsEnabled) { model.restore(restore.band) }
        }
        if (connection is FactoryRadioConnection.Disconnected) {
            RadioButton(R.string.factory_radio_retry, controlsEnabled) { model.retry() }
        }
        if (source != null && state.hasPlayer) {
            RadioButton(R.string.factory_radio_open, enabled) {
                if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    model.openPlayer { activity.startActivity(it) }
                }
            }
        }
    }
}

@Composable
private fun RadioButton(label: Int, enabled: Boolean, select: () -> Unit) {
    OutlinedButton(select, Modifier.heightIn(min = 48.dp), enabled = enabled, shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 3.dp)) {
        Text(UiText.get(label), style = MaterialTheme.typography.labelLarge)
    }
}

private data class RadioCardState(
    val connection: FactoryRadioConnection? = null,
    val bookmarks: Map<RadioBand, RadioStationIdentity> = emptyMap(),
    val hasPlayer: Boolean = false,
    val busy: Boolean = false,
    val message: Int? = null,
)

private class FactoryRadioViewModel(context: Context) : ViewModel() {
    private val preferences = FactoryRadioPreferences(context)
    private val session = FactoryRadioSession(context, ::connectionChanged)
    private val mutable = MutableStateFlow(RadioCardState())
    val state = mutable.asStateFlow()
    private var consumers = 0
    private var loading: Job? = null
    private var saving: Job? = null
    private var timeout: Job? = null
    private var source: FactoryRadioSource? = null
    private var pending: Pending? = null

    private data class Pending(val before: FactoryStation?, val target: RadioStationIdentity?) {
        fun observed(current: FactoryStation?): Boolean =
            current != null && if (target != null) current.identity() == target
            else before != null && current.identity() != null && before.identity() != null &&
                current.identity() != before.identity()
    }

    fun retain() {
        if (++consumers == 1) session.connect()
    }

    fun release() {
        if (--consumers == 0) {
            clear()
            mutable.value = RadioCardState()
        }
    }

    fun retry() {
        if (consumers > 0 && mutable.value.connection is FactoryRadioConnection.Disconnected) session.connect()
    }

    fun step(direction: RadioTransport) {
        changeStation { reading ->
            when {
                direction == RadioTransport.PREVIOUS && reading.canSkipPrevious -> RadioTransport.PREVIOUS to null
                direction == RadioTransport.NEXT && reading.canSkipNext -> RadioTransport.NEXT to null
                else -> null
            }
        }
    }

    fun selectBand(band: RadioBand) {
        changeStation { reading ->
            if (!reading.canSelectFromQueue || reading.station?.band == band) null
            else radioBandTarget(reading.queue, band, mutable.value.bookmarks[band])?.let {
                RadioTransport.Queue(it.queueId) to it.station.identity()
            }
        }
    }

    fun restore(band: RadioBand) {
        changeStation { reading ->
            val saved = mutable.value.bookmarks[band]
            if (!reading.canSelectFromQueue || reading.station?.identity() == saved) null
            else radioSavedTarget(reading.queue, saved)?.let { RadioTransport.Queue(it.queueId) to saved }
        }
    }

    fun rememberStation() {
        val live = mutable.value.connection as? FactoryRadioConnection.Connected ?: return
        val station = live.reading.station?.identity() ?: return
        if (consumers == 0 || mutable.value.busy) return
        mutable.update { it.copy(busy = true, message = null) }
        saving = viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { preferences.remember(live.source.key, station) }.getOrDefault(false)
            }
            if (consumers > 0 && live.source == source) {
                mutable.update {
                    it.copy(bookmarks = if (saved) it.bookmarks + (station.band to station) else it.bookmarks,
                        busy = false, message = if (saved) R.string.factory_radio_saved else R.string.factory_radio_save_failed)
                }
            }
        }
    }

    fun openPlayer(open: (Intent) -> Unit) {
        val target = source ?: return
        if (consumers == 0) return
        val success = session.playerIntent(target)?.let { runCatching { open(it) }.isSuccess } == true
        if (!success) mutable.update { it.copy(message = R.string.factory_radio_open_failed) }
    }

    private fun changeStation(select: (FactoryRadioReading) -> Pair<RadioTransport, RadioStationIdentity?>?) {
        val live = mutable.value.connection as? FactoryRadioConnection.Connected ?: return
        if (consumers == 0 || mutable.value.busy) return
        var expected: Pending? = null
        val sent = session.request(live.source) { reading ->
            val (transport, target) = select(reading) ?: return@request null
            expected = Pending(reading.station, target)
            transport
        }
        if (sent) {
            pending = expected
            mutable.update { it.copy(busy = true, message = R.string.factory_radio_waiting) }
            timeout = viewModelScope.launch {
                delay(STATION_TIMEOUT)
                pending = null
                mutable.update { it.copy(busy = false, message = R.string.factory_radio_unconfirmed) }
            }
        } else {
            mutable.update { it.copy(message = R.string.factory_radio_changed) }
        }
    }

    private fun connectionChanged(connection: FactoryRadioConnection) {
        if (consumers == 0) return
        when (connection) {
            is FactoryRadioConnection.Opening -> {
                updateSource(connection.source)
                timeout?.cancel()
                timeout = viewModelScope.launch {
                    delay(CONNECT_TIMEOUT)
                    session.close()
                    connectionChanged(FactoryRadioConnection.Disconnected(connection.source))
                }
                mutable.update { it.copy(connection = connection, busy = false, message = null) }
            }
            is FactoryRadioConnection.Connected -> {
                val observed = pending?.observed(connection.reading.station) == true
                if (pending == null || observed) timeout?.cancel()
                if (observed) pending = null
                mutable.update {
                    it.copy(connection = connection, busy = if (observed) false else it.busy,
                        message = if (observed) null else it.message)
                }
            }
            is FactoryRadioConnection.Disconnected -> {
                timeout?.cancel()
                pending = null
                mutable.update { it.copy(connection = connection, busy = false, message = null) }
            }
            FactoryRadioConnection.Unavailable -> {
                source = null
                mutable.value = RadioCardState(connection = connection)
            }
        }
    }

    private fun updateSource(next: FactoryRadioSource) {
        if (source == next) return
        source = next
        mutable.update { it.copy(bookmarks = emptyMap(), hasPlayer = session.playerIntent(next) != null) }
        loading?.cancel()
        loading = viewModelScope.launch {
            val bookmarks = withContext(Dispatchers.IO) {
                RadioBand.entries.mapNotNull { band -> preferences.read(next.key, band)?.let { band to it } }.toMap()
            }
            if (consumers > 0 && source == next) {
                mutable.update { it.copy(bookmarks = bookmarks + it.bookmarks) }
            }
        }
    }

    private fun clear() {
        timeout?.cancel()
        loading?.cancel()
        saving?.cancel()
        session.close()
        source = null
        pending = null
    }

    override fun onCleared() = clear()

    companion object {
        const val CONNECT_TIMEOUT = 5_000L
        const val STATION_TIMEOUT = 3_000L
    }
}

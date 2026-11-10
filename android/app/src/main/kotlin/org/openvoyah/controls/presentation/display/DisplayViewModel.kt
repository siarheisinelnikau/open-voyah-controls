package org.openvoyah.controls.presentation.display

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.update
import org.openvoyah.controls.core.display.DisplayEventBus
import org.openvoyah.controls.core.display.DisplaySettings
import org.openvoyah.controls.core.display.NavigationFeed

internal class DisplayViewModel(private val events: DisplayEventBus, private val settings: DisplaySettings,
                                private val navigation: NavigationFeed) : ViewModel() {
    data class State(val density: Int, val clusterMedia: Boolean, val navigationEnabled: Boolean,
                     val changingNavigation: Boolean = false, val message: String? = null)

    private val current = MutableStateFlow(State(settings.density(), settings.clusterMedia(), navigation.enabled()))
    val state = current.asStateFlow()
    val snapshots = callbackFlow {
        val stop = events.observe { trySend(it) }
        trySend(events.snapshot())
        awaitClose { stop.run() }
    }.conflate()

    fun snapshot(): DisplayEventBus.Snapshot = events.snapshot()

    fun refresh() = current.update {
        it.copy(density = settings.density(), clusterMedia = settings.clusterMedia(), navigationEnabled = navigation.enabled())
    }

    fun density(value: Int) {
        settings.density(value)
        current.update { it.copy(density = value) }
    }

    fun clusterMedia(enabled: Boolean) {
        settings.clusterMedia(enabled)
        current.update { it.copy(clusterMedia = enabled) }
    }

    fun navigationEnabled(enabled: Boolean) {
        if (current.value.changingNavigation) return
        current.update { it.copy(changingNavigation = true, message = null) }
        navigation.setEnabled(enabled) { message ->
            val actual = navigation.enabled()
            if (!actual) events.publishNavigation(DisplayEventBus.Navigation.EMPTY)
            current.update { it.copy(navigationEnabled = actual, changingNavigation = false, message = message) }
        }
    }

    fun report(message: String) = current.update { it.copy(message = message) }
}

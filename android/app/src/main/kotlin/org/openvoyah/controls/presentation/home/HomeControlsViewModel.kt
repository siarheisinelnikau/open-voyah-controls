package org.openvoyah.controls.presentation.home

import android.os.Build
import android.view.KeyEvent
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.core.Action
import org.openvoyah.controls.core.PanelSlots
import org.openvoyah.controls.data.settings.SettingsStore

enum class HomeCommand { ACCESSIBILITY, CAPTURE, MANUAL, STEERING, TIMINGS, RESET, EXPORT, IMPORT, CONNECT, STATUS, DISCONNECT }

/** Android handoffs and existing settings commands stay outside the composables. */
interface HomeActions {
    fun command(command: HomeCommand)
    fun buttonsEnabled(enabled: Boolean)
    fun panelEnabled(enabled: Boolean)
    fun choosePanel(slot: String)
    fun editBinding(id: String)
}

@Immutable
internal data class PanelRow(val slot: String, val title: String, val action: String)
@Immutable
internal data class BindingRow(val id: String, val title: String, val device: String, val actions: String)
@Immutable
internal data class HomeControlsState(
    val available: Boolean = false, val error: String? = null, val health: String = "",
    val buttonsEnabled: Boolean = false, val panelEnabled: Boolean = false, val connected: Boolean = false,
    val holdMs: Int = 650, val debounceMs: Int = 100, val steeringSupported: Boolean = false,
    val panels: List<PanelRow> = emptyList(), val bindings: List<BindingRow> = emptyList(),
)

/** Copies mutable legacy settings into immutable, lifecycle-observed presentation state. */
class HomeControlsViewModel : ViewModel() {
    private val current = MutableStateFlow(HomeControlsState())
    internal val state = current.asStateFlow()

    fun refresh(settings: SettingsStore.State?, error: String?, health: String, connected: Boolean) {
        if (settings == null) { current.value = HomeControlsState(error = error); return }
        val titles = listOf(R.string.ui_3, R.string.ui_4, R.string.ui_5, R.string.ui_6, R.string.ui_7, R.string.ui_8, R.string.ui_9)
        val panels = PanelSlots.forProduct(Build.PRODUCT).map { slot ->
            val title = when (slot) {
                "mainHome" -> R.string.panel_main_home
                "mainApps" -> R.string.panel_main_apps
                "passengerApps" -> R.string.panel_passenger_apps
                "navbar11" -> if (Build.PRODUCT == "f6811") R.string.panel_passenger_home else R.string.ui_5
                "navbar12" -> if (Build.PRODUCT == "f6811") R.string.panel_passenger_seat else R.string.ui_6
                else -> titles[PanelSlots.PANEL.indexOf(slot)]
            }
            val action = settings.panelActions[slot] ?: Action.NONE
            PanelRow(slot, UiText.get(title), if (action.isAssigned) label(action) else UiText.get(R.string.ui_71))
        }
        val bindings = settings.bindings.map { binding ->
            BindingRow(binding.id, binding.label + if (binding.enabled) "" else UiText.get(R.string.ui_47),
                KeyEvent.keyCodeToString(binding.keyCode) + " · " + UiText.get(if (binding.device.isEmpty()) R.string.ui_48 else R.string.ui_49),
                UiText.get(R.string.ui_50) + label(binding.shortPress) + UiText.get(R.string.ui_51) + label(binding.longPress))
        }
        current.value = HomeControlsState(true, null, health, settings.enabled, settings.panelEnabled, connected,
            settings.holdMs, settings.debounceMs, Build.VERSION.SDK_INT in listOf(28, 30), panels, bindings)
    }

    private fun label(action: Action): String = when {
        action.kind == Action.Kind.START_VOICE -> UiText.get(R.string.quick_voice_title)
        action.isAssigned -> action.label
        else -> UiText.get(R.string.ui_66)
    }
}

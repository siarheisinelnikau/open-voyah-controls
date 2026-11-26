package org.openvoyah.controls.presentation.home

import android.view.KeyEvent
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.core.Action
import org.openvoyah.controls.core.Binding
import org.openvoyah.controls.core.PanelControls
import org.openvoyah.controls.core.PanelSlots
import org.openvoyah.controls.data.settings.SettingsStore

enum class HomeCommand { ACCESSIBILITY, CAPTURE, MANUAL, STEERING, TIMINGS, RESET, EXPORT, IMPORT, CONNECT, STATUS, DISCONNECT, NAVIGATOR_WIDGET }

interface HomeActions {
    fun command(command: HomeCommand)
    fun buttonsEnabled(enabled: Boolean)
    fun panelEnabled(enabled: Boolean)
    fun navigatorHomeCard(enabled: Boolean)
    fun navigatorWidgetScale(percent: Int)
    fun weatherCard(enabled: Boolean)
    fun weatherCity(city: String)
    fun mediaCardApps(enabled: Boolean)
    fun mediaCardHideOnline(enabled: Boolean)
    fun clusterCardApps(enabled: Boolean)
    fun driverMediaLayout(enabled: Boolean)
    fun choosePanel(slot: String)
    fun editBinding(id: String)
}

@Immutable internal data class PanelRow(val slot: String, val title: String, val action: String)
@Immutable internal data class BindingRow(val id: String, val title: String, val device: String, val actions: String)
@Immutable internal data class SteeringRow(val slot: String, val title: String, val action: String)
@Immutable internal data class HomeNotice(val id: Long, val title: String, val message: String)
@Immutable
internal data class HomeControlsState(
    val available: Boolean = false, val error: String? = null, val health: String = "",
    val buttonsEnabled: Boolean = false, val panelEnabled: Boolean = false,
    val navigatorHomeCard: Boolean = false, val navigatorHomeCardSupported: Boolean = false,
    val navigatorWidgetScale: Int = SettingsStore.NAVIGATOR_WIDGET_SCALE,
    val weatherCard: Boolean = false, val weatherCity: String = "", val mediaCardApps: Boolean = true,
    val mediaCardHideOnline: Boolean = true, val clusterCardApps: Boolean = true, val driverMediaLayout: Boolean = false,
    val connected: Boolean = false,
    /** The supervisor paused the activation; it re-arms itself after the next system restart. */
    val activationPaused: Boolean = false,
    val holdMs: Int = 650, val debounceMs: Int = 100, val steeringSupported: Boolean = false,
    val panels: List<PanelRow> = emptyList(), val bindings: List<BindingRow> = emptyList(),
    val steering: List<SteeringRow> = emptyList(), val busy: Boolean = false, val notices: List<HomeNotice> = emptyList(),
)

/** Owns settings and bridge commands without retaining an Activity or dialog. */
internal class HomeControlsViewModel(
    private val store: SettingsStore,
    private val panel: PanelControls,
    private val product: String,
    private val sdk: Int,
    private val connected: () -> Boolean,
) : ViewModel() {
    private val current = MutableStateFlow(HomeControlsState())
    val state = current.asStateFlow()
    private var settings: SettingsStore.State? = null
    private var noticeId = 0L
    private var closed = false
    private var feedbackOwner = 0L

    init {
        refresh()
        checkActivation()
    }

    /** Asks the root channel whether the activation is paused; the answer also stays for the next refresh. */
    fun checkActivation() {
        if (closed) return
        panel.checkActivation { paused -> if (!closed) current.value = current.value.copy(activationPaused = paused) }
    }
    fun attach(): Long = ++feedbackOwner
    fun detach(owner: Long) {
        if (feedbackOwner != owner) return
        feedbackOwner += 1
        current.value = current.value.copy(notices = emptyList())
    }

    fun refresh() {
        if (closed) return
        try { settings = store.load(); render() }
        catch (failure: RuntimeException) {
            settings = null
            current.value = HomeControlsState(error = failure.message, connected = connected(), health = panel.health(),
                activationPaused = panel.activationPaused(),
                busy = current.value.busy, notices = current.value.notices)
        }
    }

    fun acknowledgeNotice(id: Long) {
        current.value = current.value.copy(notices = current.value.notices.filterNot { it.id == id })
    }
    fun buttonsEnabled(enabled: Boolean) = change { it.enabled = enabled }
    fun panelEnabled(enabled: Boolean) {
        if (change { it.panelEnabled = enabled } && !panel.configured()) error(UiText.get(R.string.ui_70))
    }
    fun navigatorHomeCard(enabled: Boolean) = change { it.navigatorHomeCard = enabled }
    fun navigatorWidgetScale(percent: Int) = change { it.navigatorWidgetScale = SettingsStore.navigatorWidgetScale(percent) }
    fun weatherCard(enabled: Boolean) = change { it.weatherCard = enabled }
    fun weatherCity(city: String) { current.value = current.value.copy(weatherCity = city) }
    fun mediaCardApps(enabled: Boolean) = change { it.mediaCardApps = enabled }
    fun mediaCardHideOnline(enabled: Boolean) = change { it.mediaCardHideOnline = enabled }
    fun clusterCardApps(enabled: Boolean) = change { it.clusterCardApps = enabled }
    fun driverMediaLayout(enabled: Boolean) = change { it.driverMediaLayout = enabled }
    fun choosePanel(slot: String, action: Action) {
        if (slot !in PanelSlots.forProduct(product)) return
        if (change {
            // Absence selects the app default; explicit native selection must survive decoding.
            if (action.isAssigned || SettingsStore.hasDefault(slot)) it.panelActions[slot] = action else it.panelActions.remove(slot)
        } && state.value.panelEnabled && !panel.configured()) error(UiText.get(R.string.ui_72))
    }
    fun chooseSteering(slot: String, action: Action) {
        if (slot !in PanelSlots.STEERING) return
        if (change {
            if (action.isAssigned || SettingsStore.hasDefault(slot)) it.steeringActions[slot] = action else it.steeringActions.remove(slot)
        } && !panel.configured()) error(UiText.get(R.string.ui_72))
    }
    /** Factory restore keeps the voice key native too, which an empty map would turn back into the app default. */
    fun clearSteering() = change {
        it.steeringActions.clear()
        it.steeringActions[SettingsStore.DEFAULT_STEERING_SLOT] = Action.NONE
    }
    fun binding(id: String): Binding? = settings?.bindings?.firstOrNull { it.id == id }
    /** The installer's root channel is in use, so the button service can be enabled without Android's settings. */
    fun rootConfigured(): Boolean = panel.configured()

    fun saveBinding(binding: Binding, existingId: String?): String? {
        if (binding.longPress.isAssigned && !binding.shortPress.isAssigned) return UiText.get(R.string.button_tap_required)
        val draft = draft() ?: return UiText.get(R.string.binding_changed)
        val index = draft.bindings.indexOfFirst { it.id == existingId }
        if (existingId != null && index < 0) return UiText.get(R.string.binding_changed)
        if (draft.bindings.any { it.id != binding.id && it.keyCode == binding.keyCode && it.device == binding.device })
            return UiText.get(R.string.ui_107)
        if (index < 0) draft.bindings.add(binding) else draft.bindings[index] = binding
        return persist(draft)
    }
    fun removeBinding(id: String) {
        val draft = draft()
        if (draft == null || !draft.bindings.removeAll { it.id == id }) { error(UiText.get(R.string.binding_changed)); return }
        persist(draft)?.let(::error)
    }
    fun timings(hold: Int, debounce: Int): Boolean = change { it.holdMs = hold; it.debounceMs = debounce }
    fun reset() { persist(SettingsStore.State())?.let(::error) }

    fun run(operation: PanelControls.Operation) {
        if (current.value.busy) return
        val switchesPanel = operation == PanelControls.Operation.CONNECT || operation == PanelControls.Operation.DISCONNECT
        if (switchesPanel && !change { it.panelEnabled = operation == PanelControls.Operation.CONNECT }) return
        val owner = feedbackOwner
        current.value = current.value.copy(busy = true)
        try {
            panel.run(operation) completed@{ result ->
                if (closed) return@completed
                current.value = current.value.copy(busy = false)
                refresh()
                val title = when {
                    operation != PanelControls.Operation.BUTTON_SERVICE -> if (result.successful()) R.string.ui_81 else R.string.ui_82
                    result.successful() -> R.string.button_service_title
                    else -> R.string.button_service_failed
                }
                if (owner == feedbackOwner) notice(UiText.get(title), result.message())
            }
        } catch (failure: RuntimeException) {
            current.value = current.value.copy(busy = false)
            error(failure.message ?: UiText.get(R.string.ui_84))
        }
    }
    private fun change(edit: (SettingsStore.State) -> Unit): Boolean {
        val draft = draft() ?: return false
        edit(draft)
        val failure = persist(draft)
        if (failure != null) error(failure)
        return failure == null
    }
    private fun draft(): SettingsStore.State? {
        val source = settings ?: return null
        return SettingsStore.State().also {
            it.enabled = source.enabled
            it.holdMs = source.holdMs
            it.debounceMs = source.debounceMs
            it.panelEnabled = source.panelEnabled
            it.navigatorHomeCard = source.navigatorHomeCard
            it.navigatorWidgetScale = source.navigatorWidgetScale
            it.weatherCard = source.weatherCard
            it.mediaCardApps = source.mediaCardApps
            it.mediaCardHideOnline = source.mediaCardHideOnline
            it.clusterCardApps = source.clusterCardApps
            it.driverMediaLayout = source.driverMediaLayout
            it.bindings.clear()
            it.bindings.addAll(source.bindings)
            it.panelActions.clear()
            it.panelActions.putAll(source.panelActions)
            it.steeringActions.clear()
            it.steeringActions.putAll(source.steeringActions)
        }
    }
    private fun persist(draft: SettingsStore.State): String? {
        try { store.save(draft) }
        catch (failure: RuntimeException) { return failure.message ?: UiText.get(R.string.ui_84) }
        settings = draft
        render()
        val owner = feedbackOwner
        try { panel.publish { message -> if (!closed && owner == feedbackOwner) error(message) } }
        catch (failure: RuntimeException) { error(failure.message ?: UiText.get(R.string.ui_84)) }
        return null
    }
    private fun error(message: String) = notice(UiText.get(R.string.ui_84), message)
    private fun notice(title: String, message: String) {
        current.value = current.value.copy(notices = current.value.notices + HomeNotice(++noticeId, title, message))
    }
    private fun render() {
        val settings = settings ?: return
        val titles = listOf(R.string.ui_3, R.string.ui_4, R.string.ui_5, R.string.ui_6, R.string.ui_7, R.string.ui_8, R.string.ui_9)
        val panels = PanelSlots.forProduct(product).map { slot ->
            val title = when (slot) {
                "mainHome" -> R.string.panel_main_home
                "mainApps" -> R.string.panel_main_apps
                "passengerApps" -> R.string.panel_passenger_apps
                "navbar11" -> if (product == "f6811") R.string.panel_passenger_home else R.string.ui_5
                "navbar12" -> if (product == "f6811") R.string.panel_passenger_seat else R.string.ui_6
                else -> titles[PanelSlots.PANEL.indexOf(slot)]
            }
            val action = settings.panelActions[slot] ?: Action.NONE
            PanelRow(slot, UiText.get(title), if (action.isAssigned) homeActionLabel(action) else UiText.get(R.string.ui_71))
        }
        val bindings = settings.bindings.map { binding ->
            BindingRow(binding.id, binding.label + if (binding.enabled) "" else UiText.get(R.string.ui_47),
                KeyEvent.keyCodeToString(binding.keyCode) + " · " + UiText.get(if (binding.device.isEmpty()) R.string.ui_48 else R.string.ui_49),
                UiText.get(R.string.ui_50) + homeActionLabel(binding.shortPress) + UiText.get(R.string.ui_51) + homeActionLabel(binding.longPress))
        }
        val steeringTitles = listOf(R.string.oem_voice, R.string.oem_custom, R.string.oem_camera)
        val steering = PanelSlots.STEERING.mapIndexed { index, slot ->
            SteeringRow(slot, UiText.get(steeringTitles[index / 2]) + " · " +
                UiText.get(if (index % 2 == 0) R.string.oem_short else R.string.oem_hold),
                homeActionLabel(settings.steeringActions[slot] ?: Action.NONE))
        }
        current.value = current.value.copy(available = true, error = null, health = panel.health(),
            activationPaused = panel.activationPaused(),
            buttonsEnabled = settings.enabled, panelEnabled = settings.panelEnabled,
            navigatorHomeCard = settings.navigatorHomeCard, navigatorWidgetScale = settings.navigatorWidgetScale,
            weatherCard = settings.weatherCard, mediaCardApps = settings.mediaCardApps,
            mediaCardHideOnline = settings.mediaCardHideOnline, clusterCardApps = settings.clusterCardApps,
            driverMediaLayout = settings.driverMediaLayout,
            // The overseas launcher has no Baidu navigation card to replace.
            navigatorHomeCardSupported = product != "f6811", connected = connected(),
            holdMs = settings.holdMs, debounceMs = settings.debounceMs, steeringSupported = sdk in listOf(28, 30),
            panels = panels, bindings = bindings, steering = steering)
    }
    override fun onCleared() { closed = true; feedbackOwner += 1; super.onCleared() }
}

internal fun homeActionLabel(action: Action): String = when {
    action.kind == Action.Kind.START_VOICE -> UiText.get(R.string.quick_voice_title)
    action.kind == Action.Kind.CONTROLS_APPS -> UiText.get(R.string.controls_apps)
    action.kind == Action.Kind.CONTROLS_SETTINGS -> UiText.get(R.string.ui_111)
    action.kind == Action.Kind.RUN_ROUTINE -> UiText.get(R.string.routine_steering_label).format(action.label)
    action.isAssigned -> action.label
    else -> UiText.get(R.string.ui_66)
}

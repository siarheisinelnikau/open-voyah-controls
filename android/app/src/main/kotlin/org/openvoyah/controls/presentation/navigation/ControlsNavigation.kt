package org.openvoyah.controls.presentation.navigation

import android.content.Context
import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.openvoyah.controls.MainActivity

internal enum class ControlsRoute { APPS, SETTINGS, BUTTONS, VOICE, DISPLAY, VEHICLE, FIRMWARE, CLUSTER, UPDATES }

/** One normal task, with explicit entry routes for installed shortcuts and button assignments. */
internal object ControlsNavigation {
    const val OPEN_APPS = "org.openvoyah.controls.action.OPEN_APPS"
    const val OPEN_SETTINGS = "org.openvoyah.controls.action.OPEN_SETTINGS"
    private const val ROUTE = "org.openvoyah.controls.extra.ROUTE"

    fun intent(context: Context, route: ControlsRoute): Intent = Intent(context, MainActivity::class.java)
        .setAction(if (route == ControlsRoute.APPS) OPEN_APPS else OPEN_SETTINGS)
        .putExtra(ROUTE, route.name)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun route(intent: Intent): ControlsRoute {
        // Compatibility aliases take priority over extras supplied by their caller.
        val alias = when (intent.component?.className?.substringAfterLast('.')) {
            "AppsActivity" -> ControlsRoute.APPS
            "VoiceActivity" -> ControlsRoute.VOICE
            "DisplayActivity" -> ControlsRoute.DISPLAY
            "VehicleSettingsActivity" -> ControlsRoute.VEHICLE
            "FirmwareActivity" -> ControlsRoute.FIRMWARE
            "ClusterActivity" -> ControlsRoute.CLUSTER
            "UpdatesActivity" -> ControlsRoute.UPDATES
            else -> null
        }
        if (alias != null) return alias
        if (intent.action == OPEN_APPS) return ControlsRoute.APPS
        if (intent.action == OPEN_SETTINGS) {
            return ControlsRoute.entries.firstOrNull { it.name == intent.getStringExtra(ROUTE) }
                ?: ControlsRoute.SETTINGS
        }
        return ControlsRoute.APPS
    }
}

internal class ControlsNavigationViewModel(private val saved: SavedStateHandle) : ViewModel() {
    private val current = MutableStateFlow(restore(saved.get<ArrayList<String>>("routes")))
    val stack = current.asStateFlow()
    private val requests = MutableStateFlow(saved.get<Long>("entries") ?: 0L)
    val entries = requests.asStateFlow()

    fun enter(route: ControlsRoute) {
        publish(if (route == ControlsRoute.APPS) listOf(route) else listOf(ControlsRoute.APPS, route))
        requests.value += 1
        saved["entries"] = requests.value
    }

    fun open(route: ControlsRoute) {
        val existing = current.value.indexOf(route)
        publish(if (existing >= 0) current.value.take(existing + 1) else current.value + route)
    }

    fun back() {
        if (current.value.size > 1) publish(current.value.dropLast(1))
    }

    private fun publish(routes: List<ControlsRoute>) {
        saved["routes"] = ArrayList(routes.map { it.name })
        current.value = routes
    }

    private fun restore(names: List<String>?): List<ControlsRoute> {
        val routes = names?.mapNotNull { name -> ControlsRoute.entries.firstOrNull { it.name == name } }
            ?.distinct().orEmpty()
        return if (routes.firstOrNull() == ControlsRoute.APPS) routes else listOf(ControlsRoute.APPS)
    }
}

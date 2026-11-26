package org.openvoyah.controls.presentation.vehicle.comfort

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.vehicle.comfort.AdaptiveBrightnessPreferences
import org.openvoyah.controls.domain.vehicle.comfort.AdaptiveBrightnessSettings
import org.openvoyah.controls.platform.vehicle.comfort.AdaptiveControlsBrightness
import org.openvoyah.controls.platform.vehicle.comfort.AmbientLight
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitOptionDialog
import org.openvoyah.controls.presentation.theme.CockpitSection
import org.openvoyah.controls.presentation.theme.CockpitSwitch

@Composable
internal fun ObserveAdaptiveControlsBrightness(activity: ComponentActivity) {
    LaunchedEffect(activity) {
        if (!AdaptiveControlsBrightness.supported(activity) || !AmbientLight.available(activity)) return@LaunchedEffect
        val preferences = AdaptiveBrightnessPreferences(activity.application)
        activity.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            preferences.observe().collectLatest { result ->
                result.getOrNull()?.let { AdaptiveControlsBrightness.apply(activity, it) }
            }
        }
    }
}

private data class BrightnessState(val saved: AdaptiveBrightnessSettings? = null, val saving: Boolean = false, val failed: Boolean = false)

private class AdaptiveBrightnessViewModel(private val preferences: AdaptiveBrightnessPreferences) : ViewModel() {
    private val mutable = MutableStateFlow(BrightnessState())
    val state = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.observe().collect { result -> mutable.update { it.copy(saved = result.getOrNull(), failed = result.isFailure) } }
        }
    }

    fun edit(change: (AdaptiveBrightnessSettings) -> AdaptiveBrightnessSettings) {
        val before = state.value.saved ?: return
        if (state.value.saving) return
        mutable.update { it.copy(saving = true, failed = false) }
        viewModelScope.launch {
            try {
                val next = change(before)
                preferences.update(before, next)
                mutable.update { it.copy(saved = next) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.update { it.copy(failed = true) }
            } finally {
                mutable.update { it.copy(saving = false) }
            }
        }
    }
}

@Composable
internal fun AdaptiveBrightnessSection() {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    val fallback = remember(activity) {
        when {
            !AdaptiveControlsBrightness.supported(activity) -> R.string.comfort_brightness_platform_unavailable
            !AmbientLight.available(activity) -> R.string.comfort_brightness_no_sensor
            else -> null
        }
    }
    CockpitSection(UiText.get(R.string.comfort_brightness_title), CockpitIcon.DISPLAY) {
        Text(UiText.get(fallback ?: R.string.comfort_brightness_hint), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (fallback == null) AdaptiveBrightnessOptions(activity)
    }
}

@Composable
private fun AdaptiveBrightnessOptions(activity: ComponentActivity) {
    val model = remember(activity) {
        ViewModelProvider(activity, viewModelFactory {
            initializer { AdaptiveBrightnessViewModel(AdaptiveBrightnessPreferences(activity.application)) }
        })["comfort.brightness", AdaptiveBrightnessViewModel::class.java]
    }
    val state by model.state.collectAsStateWithLifecycle()
    val saved = state.saved
    var selection by remember { mutableStateOf<BrightnessEndpoint?>(null) }
    val editable = saved != null && !state.saving
    CockpitSwitch(R.string.comfort_brightness_enabled, saved?.enabled == true, editable) {
        model.edit { current -> current.copy(enabled = it) }
    }
    if (saved?.enabled == true) {
        for (endpoint in BrightnessEndpoint.entries) {
            CockpitChoice(endpoint.label, percent(endpoint.get(saved)), enabled = editable) { selection = endpoint }
        }
        selection?.let { endpoint ->
            CockpitOptionDialog(UiText.get(endpoint.label), endpoint.levels, endpoint.get(saved), ::percent,
                onSelect = { level -> model.edit { endpoint.set(it, level) } }, onDismiss = { selection = null }, enabled = editable)
        }
    }
    if (state.failed) CockpitFeedback(UiText.get(R.string.vehicle_automation_save_failed), error = true)
}

private enum class BrightnessEndpoint(val label: Int, val levels: List<Int>) {
    LOW(R.string.comfort_brightness_low, AdaptiveBrightnessSettings.LOW_LEVELS),
    HIGH(R.string.comfort_brightness_high, AdaptiveBrightnessSettings.HIGH_LEVELS);

    fun get(settings: AdaptiveBrightnessSettings): Int = if (this == LOW) settings.lowPercent else settings.highPercent
    fun set(settings: AdaptiveBrightnessSettings, percent: Int): AdaptiveBrightnessSettings =
        if (this == LOW) settings.copy(lowPercent = percent) else settings.copy(highPercent = percent)
}

private fun percent(value: Int): String = UiText.get(R.string.comfort_brightness_percent).format(value)

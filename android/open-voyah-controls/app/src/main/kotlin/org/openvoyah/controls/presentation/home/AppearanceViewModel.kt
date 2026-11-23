package org.openvoyah.controls.presentation.home

import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openvoyah.controls.data.settings.AppearancePreferences
import org.openvoyah.controls.domain.settings.AppAccent
import org.openvoyah.controls.domain.settings.AppAppearance
import org.openvoyah.controls.domain.settings.AppBackgroundTreatment
import org.openvoyah.controls.domain.settings.AppCorners
import org.openvoyah.controls.domain.settings.AppFontFamily
import org.openvoyah.controls.domain.settings.AppPalette
import org.openvoyah.controls.domain.settings.AppRoleTextSize
import org.openvoyah.controls.domain.settings.AppSpacing
import org.openvoyah.controls.domain.settings.AppTextSize
import org.openvoyah.controls.domain.settings.LauncherEntry
import org.openvoyah.controls.domain.settings.RouteTransitionSpeed
import org.openvoyah.controls.domain.settings.WallpaperDimming

internal data class AppearanceUiState(
    val settings: AppAppearance? = null,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
)

internal sealed interface AppearanceChange {
    data class AnimateRoutes(val enabled: Boolean) : AppearanceChange
    data class TransitionSpeed(val speed: RouteTransitionSpeed) : AppearanceChange
    data class HighContrast(val enabled: Boolean) : AppearanceChange
    data class StartScreen(val entry: LauncherEntry) : AppearanceChange
    data class LimitMediaVolumeOnLaunch(val enabled: Boolean) : AppearanceChange
    data class LaunchMediaVolumePercent(val percent: Int) : AppearanceChange
    data class TextSize(val size: AppTextSize) : AppearanceChange
    data class NavigationLabelSize(val size: AppRoleTextSize) : AppearanceChange
    data class PageTitleSize(val size: AppRoleTextSize) : AppearanceChange
    data class Spacing(val spacing: AppSpacing) : AppearanceChange
    data class Corners(val corners: AppCorners) : AppearanceChange
    data class Palette(val palette: AppPalette) : AppearanceChange
    data class Accent(val accent: AppAccent) : AppearanceChange
    data class WallpaperShade(val dimming: WallpaperDimming) : AppearanceChange
    data class FontFamily(val family: AppFontFamily) : AppearanceChange
    data class BackgroundTreatment(val treatment: AppBackgroundTreatment) : AppearanceChange
    data class BoldText(val enabled: Boolean) : AppearanceChange
}

internal class AppearanceViewModel(private val preferences: AppearancePreferences) : ViewModel() {
    private val saveState = MutableStateFlow(SaveState())
    val state = combine(preferences.observeSettings(), saveState) { settings, save ->
        AppearanceUiState(settings, save.pending, save.failed)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), AppearanceUiState())

    fun change(change: AppearanceChange) {
        val previous = saveState.value
        if (state.value.settings == null || previous.pending || !saveState.compareAndSet(previous, SaveState(pending = true))) return
        viewModelScope.launch {
            saveState.value = try {
                withContext(Dispatchers.IO) {
                    when (change) {
                        is AppearanceChange.AnimateRoutes -> preferences.setAnimateRoutes(change.enabled)
                        is AppearanceChange.TransitionSpeed -> preferences.setTransitionSpeed(change.speed)
                        is AppearanceChange.HighContrast -> preferences.setHighContrast(change.enabled)
                        is AppearanceChange.StartScreen -> preferences.setLauncherEntry(change.entry)
                        is AppearanceChange.LimitMediaVolumeOnLaunch -> preferences.setLimitMediaVolumeOnLaunch(change.enabled)
                        is AppearanceChange.LaunchMediaVolumePercent -> preferences.setLaunchMediaVolumePercent(change.percent)
                        is AppearanceChange.TextSize -> preferences.setTextSize(change.size)
                        is AppearanceChange.NavigationLabelSize -> preferences.setNavigationLabelSize(change.size)
                        is AppearanceChange.PageTitleSize -> preferences.setPageTitleSize(change.size)
                        is AppearanceChange.Spacing -> preferences.setSpacing(change.spacing)
                        is AppearanceChange.Corners -> preferences.setCorners(change.corners)
                        is AppearanceChange.Palette -> preferences.setPalette(change.palette)
                        is AppearanceChange.Accent -> preferences.setAccent(change.accent)
                        is AppearanceChange.WallpaperShade -> preferences.setWallpaperDimming(change.dimming)
                        is AppearanceChange.FontFamily -> preferences.setFontFamily(change.family)
                        is AppearanceChange.BackgroundTreatment -> preferences.setBackgroundTreatment(change.treatment)
                        is AppearanceChange.BoldText -> preferences.setBoldText(change.enabled)
                    }
                }
                SaveState()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                SaveState(failed = true)
            }
        }
    }

    private data class SaveState(val pending: Boolean = false, val failed: Boolean = false)
}

internal fun appearanceViewModel(activity: ComponentActivity): AppearanceViewModel =
    ViewModelProvider(activity, viewModelFactory {
        initializer { AppearanceViewModel(AppearancePreferences(activity.applicationContext)) }
    })[AppearanceViewModel::class.java]

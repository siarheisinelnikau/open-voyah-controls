package org.openvoyah.controls.presentation.theme

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.openvoyah.controls.domain.settings.AppPalette

/**
 * A routine can switch the palette for the current session. Dialogs and the voice popup are hosted in their own
 * windows and compositions, so the selection is published here for every [ControlsTheme] to follow; without it
 * they would open in the saved palette, a full-brightness flip over a screen in the other one.
 */
internal object SessionPalette {
    private val current = MutableStateFlow<AppPalette?>(null)
    val selected: StateFlow<AppPalette?> = current.asStateFlow()

    fun publish(palette: AppPalette?) {
        current.value = palette
    }
}

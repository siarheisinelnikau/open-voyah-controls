package org.openvoyah.controls.presentation.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.openvoyah.controls.R
import org.openvoyah.controls.presentation.navigation.ControlsRoute
import org.openvoyah.controls.presentation.theme.CockpitDetail
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.LocalCockpitLayout

/**
 * The ten sections of Settings, in the order of the Settings page. Each opens its own route; Voice, Vehicle and
 * Routines and automation are pages that already existed, the others are built from [SettingsSectionPage].
 */
internal enum class SettingsSection(val route: ControlsRoute, val icon: CockpitIcon, val title: Int, val summary: Int) {
    HOME_SCREEN(ControlsRoute.HOME_SCREEN, CockpitIcon.HOME, R.string.settings_section_home, R.string.settings_section_home_summary),
    WINDOWS(ControlsRoute.WINDOWS, CockpitIcon.DISPLAY, R.string.settings_section_windows, R.string.settings_section_windows_summary),
    BUTTONS(ControlsRoute.BUTTONS, CockpitIcon.BUTTONS, R.string.settings_section_buttons, R.string.settings_section_buttons_summary),
    VOICE(ControlsRoute.VOICE, CockpitIcon.VOICE, R.string.settings_section_voice, R.string.settings_section_voice_summary),
    SOUND(ControlsRoute.SOUND, CockpitIcon.PLAY, R.string.settings_section_sound, R.string.settings_section_sound_summary),
    VEHICLE(ControlsRoute.VEHICLE, CockpitIcon.CAR, R.string.settings_section_vehicle, R.string.settings_section_vehicle_summary),
    AUTOMATION(ControlsRoute.VEHICLE_AUTOMATION, CockpitIcon.ROUTINE, R.string.settings_section_automation,
        R.string.settings_section_automation_summary),
    APPEARANCE(ControlsRoute.APPEARANCE, CockpitIcon.LIGHT, R.string.settings_section_appearance,
        R.string.settings_section_appearance_summary),
    LANGUAGE(ControlsRoute.LANGUAGE, CockpitIcon.LANGUAGE, R.string.settings_section_language, R.string.settings_section_language_summary),
    SYSTEM(ControlsRoute.SYSTEM, CockpitIcon.SYSTEM, R.string.settings_section_system, R.string.settings_section_system_summary);

    companion object {
        fun of(route: ControlsRoute): SettingsSection? = entries.firstOrNull { it.route == route }
    }
}

/** One group of settings in a section; [content] draws its own cards, or an embedded page with its heading. */
internal class SettingsPart(
    val key: String,
    val title: String,
    val icon: CockpitIcon,
    val content: @Composable ColumnScope.() -> Unit,
)

/**
 * A section page: every group of the section on one scrolling page, one below the other at the full width, so the
 * settings inside each card can share rows (see CockpitGrid). There are no tabs or pages below a section. [focus]
 * scrolls to a group when a search result opens the page. [selected] reports the group the owner last touched, so
 * Settings can list it among the recent changes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SettingsSectionPage(
    title: String,
    subtitle: String?,
    back: () -> Unit,
    parts: List<SettingsPart>,
    focus: String? = null,
    focusRequest: Long = 0,
    selected: (String) -> Unit = {},
) {
    val layout = LocalCockpitLayout.current
    val spacing = Arrangement.spacedBy(layout.spacing(14.dp))
    val requesters = remember(parts.map { it.key }) { parts.associate { it.key to BringIntoViewRequester() } }
    LaunchedEffect(focus, focusRequest) {
        val target = focus?.let(requesters::get) ?: return@LaunchedEffect
        // The page arrives with its entrance motion; the group is brought into view once it has a place.
        delay(FOCUS_DELAY_MILLIS)
        target.bringIntoView()
    }
    CockpitDetail(title, subtitle, back) {
        parts.forEach { part ->
            Column(
                Modifier.fillMaxWidth().testTag("settings_part_" + part.key)
                    .bringIntoViewRequester(checkNotNull(requesters[part.key]))
                    .pointerInput(part.key) {
                        // Observes the first touch inside the group without taking it from the controls.
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            selected(part.key)
                        }
                    },
                verticalArrangement = spacing,
            ) { part.content(this) }
        }
    }
}

private const val FOCUS_DELAY_MILLIS = 250L

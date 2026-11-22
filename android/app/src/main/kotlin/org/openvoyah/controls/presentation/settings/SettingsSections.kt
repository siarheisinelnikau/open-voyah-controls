package org.openvoyah.controls.presentation.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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

/**
 * One group of settings in a section. [content] draws its own cards; [weight] is roughly how many rows it shows, so
 * the two columns of a wide page come out about as tall as each other.
 */
internal class SettingsPart(
    val key: String,
    val title: String,
    val icon: CockpitIcon,
    val weight: Int = 1,
    val content: @Composable ColumnScope.() -> Unit,
)

/**
 * A section page: every group of the section at once, in two columns on a wide window and one below the other on a
 * narrow one. [focus] scrolls to a group when a search result opens the page. [selected] reports the group the owner
 * last touched, so Settings can list it among the recent changes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SettingsSectionPage(
    title: String,
    subtitle: String?,
    back: () -> Unit,
    parts: List<SettingsPart>,
    focus: String? = null,
    selected: (String) -> Unit = {},
) {
    val layout = LocalCockpitLayout.current
    val spacing = Arrangement.spacedBy(layout.spacing(14.dp))
    val requesters = remember(parts.map { it.key }) { parts.associate { it.key to BringIntoViewRequester() } }
    LaunchedEffect(focus) {
        val target = focus?.let(requesters::get) ?: return@LaunchedEffect
        // The page arrives with its entrance motion; the group is brought into view once it has a place.
        delay(FOCUS_DELAY_MILLIS)
        target.bringIntoView()
    }
    val group: @Composable (SettingsPart) -> Unit = { part ->
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
    CockpitDetail(title, subtitle, back) {
        val wideEnough = layout.columnsMinimum(860.dp, LocalDensity.current.fontScale)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= wideEnough && parts.size > 1) {
                val (first, second) = balancedColumns(parts)
                Row(horizontalArrangement = spacing) {
                    Column(Modifier.weight(1f), verticalArrangement = spacing) { first.forEach { group(it) } }
                    Column(Modifier.weight(1f), verticalArrangement = spacing) { second.forEach { group(it) } }
                }
            } else {
                Column(verticalArrangement = spacing) { parts.forEach { group(it) } }
            }
        }
    }
}

/**
 * Splits the groups into two columns in their order: each group goes to the shorter column so far, which keeps the
 * reading order top to bottom within a column and the columns close in height.
 */
internal fun <T> balancedColumns(parts: List<T>, weight: (T) -> Int): Pair<List<T>, List<T>> {
    val first = mutableListOf<T>()
    val second = mutableListOf<T>()
    var left = 0
    var right = 0
    parts.forEach { part ->
        if (left <= right) { first += part; left += weight(part) } else { second += part; right += weight(part) }
    }
    return first to second
}

private fun balancedColumns(parts: List<SettingsPart>) = balancedColumns(parts) { it.weight }

private const val FOCUS_DELAY_MILLIS = 250L

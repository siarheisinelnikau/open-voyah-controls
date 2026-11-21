package org.openvoyah.controls.presentation.theme

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.openvoyah.controls.data.frida.FridaBridge
import org.openvoyah.controls.data.settings.FirmwareMaintenance
import org.openvoyah.controls.domain.display.LauncherPanelClearance
import org.openvoyah.controls.domain.display.WindowStripState
import org.openvoyah.controls.presentation.firmware.settling

/**
 * The root of every OVC composition: keeps [content] right of the launcher's left panel on the central display
 * (see [LauncherPanelClearance]). Until the screen's position is known the full panel width is kept, so the panel
 * never covers content even for one frame; the gap only shrinks once Android or the window agent says so.
 */
@Composable
internal fun LauncherPanelPadding(content: @Composable () -> Unit) {
    val view = LocalView.current
    val density = LocalDensity.current
    val strip = rememberStripReceipt(view)
    val displayId = view.display?.displayId ?: LauncherPanelClearance.CENTRAL_DISPLAY_ID
    val displayDpi = stableDisplayDpi(view.resources.configuration.densityDpi)
    val panelPx = LauncherPanelClearance.panelWidthPx(displayId, displayDpi, strip)
    var contentLeftPx by remember { mutableIntStateOf(0) }
    val insetLeftPx = WindowInsets.safeDrawing.getLeft(density, LayoutDirection.Ltr)
    val paddingPx = LauncherPanelClearance.leftPaddingPx(
        panelPx, contentLeftPx, insetLeftPx, LauncherPanelClearance.stripConfirmed(strip))
    val padding = with(density) { paddingPx.toDp() }
    val consumed = PaddingValues.Absolute(left = padding)
    Box(
        Modifier
            .onGloballyPositioned { contentLeftPx = it.positionOnScreen().x.roundToInt() }
            .absolutePadding(left = padding)
            .consumeWindowInsets(consumed),
        propagateMinConstraints = true,
    ) { content() }
}

/** The display's own density: the panel is laid out by the launcher, not at a density OVC may have been given. */
private fun stableDisplayDpi(fallback: Int): Int =
    android.util.DisplayMetrics.DENSITY_DEVICE_STABLE.takeIf { it > 0 } ?: fallback

/**
 * The window agent's receipt for the reserved strip, or null while there is none. Only asked when the driver turned
 * the experimental option on: otherwise nothing can be reserved, and a head unit that never used the experiment
 * sees no request for it. While the attempt is still settling the agent is asked again a few times.
 */
@Composable
private fun rememberStripReceipt(view: View): WindowStripState? {
    val context = view.context.applicationContext
    val state by produceState<WindowStripState?>(null, context) {
        if (!FridaBridge.windowStripEnabled(context)) return@produceState
        val maintenance = FirmwareMaintenance(context)
        repeat(STRIP_POLLS) {
            val receipt = withContext(Dispatchers.IO) { maintenance.windowStripState() }
            value = receipt
            if (!receipt.settling) return@produceState
            delay(STRIP_POLL_MILLIS)
        }
    }
    return state
}

private const val STRIP_POLLS = 8
private const val STRIP_POLL_MILLIS = 2_000L

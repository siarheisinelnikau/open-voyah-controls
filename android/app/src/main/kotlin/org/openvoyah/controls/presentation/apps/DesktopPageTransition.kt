package org.openvoyah.controls.presentation.apps

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.openvoyah.controls.domain.apps.AppGridDesktop
import org.openvoyah.controls.presentation.theme.CockpitMotion

@Composable
internal fun DesktopPageTransition(
    entry: Long,
    desktop: AppGridDesktop,
    smooth: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val mirror = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    val offsetPx = with(LocalDensity.current) { 32.dp.roundToPx() } * mirror
    val grids = rememberSaveableStateHolder()
    key(entry) {
        AnimatedContent(
            targetState = desktop,
            modifier = modifier.fillMaxWidth().clipToBounds(),
            transitionSpec = { desktopPageTransform(initialState, targetState, offsetPx, smooth) },
            label = "desktop page",
        ) { page ->
            // Only the selected grid is mounted, including during rapid transitions.
            if (page == desktop) grids.SaveableStateProvider(page.storedValue, content)
        }
    }
}

internal fun desktopPageTransform(
    from: AppGridDesktop,
    to: AppGridDesktop,
    offsetPx: Int,
    smooth: Boolean,
): ContentTransform {
    val enter = if (smooth && from != to) {
        val offset = if (to.ordinal > from.ordinal) offsetPx else -offsetPx
        slideInHorizontally(tween(CockpitMotion.CHANGE, easing = LinearOutSlowInEasing)) { offset } +
            fadeIn(tween(CockpitMotion.CHANGE, easing = LinearOutSlowInEasing), initialAlpha = .72f)
    } else EnterTransition.None
    return ContentTransform(enter, ExitTransition.None, sizeTransform = null)
}

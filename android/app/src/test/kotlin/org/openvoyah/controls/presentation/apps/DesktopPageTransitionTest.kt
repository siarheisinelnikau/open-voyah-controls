package org.openvoyah.controls.presentation.apps

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.openvoyah.controls.domain.apps.AppGridDesktop

class DesktopPageTransitionTest {
    @Test fun aDesktopChangeIsImmediateWhenMotionIsOff() {
        val transition = desktopPageTransform(AppGridDesktop.MAIN, AppGridDesktop.FAVORITES_ONE, 32, smooth = false)
        assertEquals(EnterTransition.None, transition.targetContentEnter)
        assertEquals(ExitTransition.None, transition.initialContentExit)
    }

    @Test fun onlyAChangedCurrentPageGetsASmoothEntrance() {
        val from = AppGridDesktop.MAIN
        val to = AppGridDesktop.FAVORITES_ONE
        val changed = desktopPageTransform(from, to, 32, smooth = true)
        assertNotEquals(EnterTransition.None, changed.targetContentEnter)
        assertEquals(ExitTransition.None, changed.initialContentExit)
        assertEquals(EnterTransition.None, desktopPageTransform(to, to, 32, smooth = true).targetContentEnter)
    }
}

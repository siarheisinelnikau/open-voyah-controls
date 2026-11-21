package org.openvoyah.controls.presentation.navigation

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openvoyah.controls.domain.settings.AppPalette

/** The arrival counter tells the host which page is new; only such a page plays its cascade. */
class NavigationArrivalTest {
    private val navigation = ControlsNavigationViewModel(SavedStateHandle())
    private val arrival get() = navigation.state.value.arrival.number

    @Test fun everyOpenedPageIsANewArrivalAndReturningIsNot() {
        val start = arrival
        navigation.open(ControlsRoute.SETTINGS)
        assertEquals(start + 1, arrival)
        navigation.open(ControlsRoute.VOICE)
        assertEquals(start + 2, arrival)

        navigation.back()
        assertEquals(ControlsRoute.SETTINGS, navigation.state.value.route)
        assertEquals(RouteDirection.BACK, navigation.state.value.direction)
        // The counter still moves so a later forward step is distinct; the direction keeps the cascade off.
        assertTrue(arrival > start + 2)
    }

    @Test fun anExternalEntryToTheSamePageArrivesAgain() {
        navigation.enter(ControlsRoute.SETTINGS)
        val first = arrival
        navigation.enter(ControlsRoute.SETTINGS)
        assertEquals(first + 1, arrival)
        assertEquals(RouteDirection.REPLACE, navigation.state.value.direction)
    }

    @Test fun theCounterDoesNotMakeEqualPagesDiffer() {
        navigation.enter(ControlsRoute.UPDATES)
        val shown = navigation.state.value
        assertEquals(ControlsNavigationState(listOf(ControlsRoute.APPS, ControlsRoute.UPDATES), shown.entry, RouteDirection.REPLACE),
            shown)
        assertEquals(shown, shown.copy(arrival = RouteArrival(shown.arrival.number + 7)))
    }

    @Test fun changesWithinAPageDoNotCountAsArrivals() {
        navigation.open(ControlsRoute.SETTINGS)
        val shown = arrival
        navigation.runRoutine(null, null, AppPalette.DAY)
        assertEquals(AppPalette.DAY, navigation.state.value.temporaryPalette)
        navigation.clearTemporaryPalette()
        navigation.open(ControlsRoute.SETTINGS)
        assertEquals(shown, arrival)
    }
}

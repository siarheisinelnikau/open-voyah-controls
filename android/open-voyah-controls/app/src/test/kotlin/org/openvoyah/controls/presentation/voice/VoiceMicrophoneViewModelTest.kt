package org.openvoyah.controls.presentation.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openvoyah.controls.presentation.voice.VoiceMicrophoneViewModel.Access

class VoiceMicrophoneViewModelTest {
    @Test fun firstRequestAndOrdinaryDenialKeepTheNativePermissionAction() {
        val model = VoiceMicrophoneViewModel()
        model.refresh(granted = false, requested = false, showRationale = false)
        assertEquals(Access.REQUESTABLE, model.state.value.access)
        assertTrue(model.beginRequest())
        assertFalse(model.beginRequest())
        model.refresh(granted = false, requested = true, showRationale = true, requestFinished = true)
        assertEquals(Access.REQUESTABLE, model.state.value.access)
        assertFalse(model.state.value.requesting)
        assertTrue(model.beginRequest())
    }

    @Test fun permanentDenialRoutesToSettingsInsteadOfAnotherPermissionPrompt() {
        val model = VoiceMicrophoneViewModel()
        model.refresh(granted = false, requested = true, showRationale = false, requestFinished = true)
        assertEquals(Access.SETTINGS, model.state.value.access)
        assertFalse(model.beginRequest())
        assertFalse(model.state.value.requesting)
    }

    @Test fun returningFromAndroidSettingsRefreshesTheEffectiveGrant() {
        val model = VoiceMicrophoneViewModel()
        model.refresh(granted = false, requested = true, showRationale = false)
        model.refresh(granted = true, requested = true, showRationale = false)
        assertEquals(Access.ALLOWED, model.state.value.access)
        assertFalse(model.beginRequest())
        model.refresh(granted = false, requested = true, showRationale = false)
        assertEquals(Access.SETTINGS, model.state.value.access)
    }

    @Test fun resumeDuringPermissionHandoffCannotStartADuplicateRequest() {
        val model = VoiceMicrophoneViewModel()
        assertTrue(model.beginRequest())
        model.refresh(granted = false, requested = true, showRationale = false)
        assertTrue(model.state.value.requesting)
        assertFalse(model.beginRequest())
        model.refresh(granted = true, requested = true, showRationale = false, requestFinished = true)
        assertEquals(Access.ALLOWED, model.state.value.access)
        assertFalse(model.state.value.requesting)
    }
}

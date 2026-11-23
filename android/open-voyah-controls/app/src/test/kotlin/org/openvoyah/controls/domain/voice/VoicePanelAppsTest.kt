package org.openvoyah.controls.domain.voice

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openvoyah.controls.core.Action

class VoicePanelAppsTest {
    private val music = Action(Action.Kind.LAUNCH_APP, "com.music/.Main", "Music")
    private val navigator = Action(Action.Kind.LAUNCH_APP, "ru.yandex.yandexnavi/.Main", "Navigator")
    private val radio = Action(Action.Kind.LAUNCH_APP, "com.radio/.Main", "Radio")
    private val panel = mapOf("navbar01" to navigator, "navbar02" to music, "navbar14" to radio)

    @Test fun anAppChosenInVoiceSettingsWins() {
        assertEquals("com.voice/.Player", VoicePanelApps.resolve("Music", "com.voice/.Player") { error("Not read") })
    }

    @Test fun withoutOneTheMatchingPanelButtonOpensItsApp() {
        assertEquals("com.music/.Main", VoicePanelApps.resolve("Music", "") { panel })
        assertEquals("com.music/.Main", VoicePanelApps.resolve("RestartMusic", "") { panel })
        assertEquals("ru.yandex.yandexnavi/.Main", VoicePanelApps.resolve("Navigator", "") { panel })
        assertEquals("ru.yandex.yandexnavi/.Main", VoicePanelApps.resolve("RestartNavigator", "") { panel })
        assertEquals("com.radio/.Main", VoicePanelApps.resolve("Radio", "") { panel })
    }

    @Test fun otherwiseTheEarlierBehaviourStays() {
        assertEquals("No panel assignment", "", VoicePanelApps.resolve("Music", "") { emptyMap() })
        assertEquals("A Controls action is not an app", "", VoicePanelApps.resolve("Music", "") {
            mapOf("navbar02" to Action(Action.Kind.CONTROLS_SETTINGS, "", "Settings"))
        })
        assertEquals("Camera has no panel button", "", VoicePanelApps.resolve("Camera", "") { panel })
        assertEquals("Other commands never read the panel", "", VoicePanelApps.resolve("Weather", "") { error("Not read") })
    }
}

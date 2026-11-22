package org.openvoyah.controls.domain.voice

import org.openvoyah.controls.core.Action

/**
 * The app a voice command opens: the app chosen for it in Voice settings, otherwise the app assigned to the matching
 * launcher panel button (docs/INJECTION_MAP.md). Empty when neither exists, which keeps the "assign an app" result.
 */
internal object VoicePanelApps {
    /** Central navigation and music buttons, and the passenger radio position of the standard layout. */
    fun slotFor(command: String): String? = when (command) {
        "Navigator", "RestartNavigator" -> "navbar01"
        "Music", "RestartMusic" -> "navbar02"
        "Radio" -> "navbar14"
        else -> null
    }

    fun resolve(command: String, voice: String, panel: () -> Map<String, Action>): String {
        if (voice.isNotEmpty()) return voice
        val slot = slotFor(command) ?: return ""
        val action = panel()[slot] ?: return ""
        return if (action.kind == Action.Kind.LAUNCH_APP) action.component else ""
    }
}

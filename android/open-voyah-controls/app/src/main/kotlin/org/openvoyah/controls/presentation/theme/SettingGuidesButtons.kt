package org.openvoyah.controls.presentation.theme

import org.openvoyah.controls.R
import org.openvoyah.controls.presentation.theme.CockpitIcon.*
import org.openvoyah.controls.presentation.theme.Ink.*

internal val buttonsGuides = mapOf(
    R.string.ui_69 to SettingGuide(R.array.guide_ui_69) {
        story({ panelKeys(false) }, { panelKeys(true) })
    },
    R.string.ui_43 to SettingGuide(R.array.guide_ui_43) {
        story(
            {
                tile("keys", BUTTONS, 15, 24, 31)
                toggle("bindings", false, 73, 30)
            },
            {
                tile("keys", BUTTONS, 15, 24, 31)
                toggle("bindings", true, 73, 30)
            },
        )
    },
    R.string.oem_short to SettingGuide(R.array.guide_oem_short) {
        GuideStory(
            frame { pressing(false, 0, false) },
            frame { pressing(false, 25, true) },
            frame { pressing(false, 25, false) },
        )
    },
    R.string.oem_hold to SettingGuide(R.array.guide_oem_hold) {
        GuideStory(
            frame { pressing(true, 0, false) },
            frame { pressing(true, 85, true) },
            frame { pressing(true, 55, false) },
        )
    },
    R.string.ui_98 to SettingGuide(R.array.guide_ui_98) {
        story({ devices(false) }, { devices(true) })
    },
    R.string.ui_99 to SettingGuide(R.array.guide_ui_99) {
        story({ binding(false, "165") }, { binding(true, "165") })
    },
    R.string.ui_91 to SettingGuide(R.array.guide_ui_91) {
        story({ binding(false, "164") }, { binding(true, "165") })
    },
    R.string.ui_95 to SettingGuide(R.array.guide_ui_95) {
        story(
            {
                keycap("code", "165", 14, 23)
                arrow("name", 64)
                word("label", "165", 100, 38, 15, MUTED)
            },
            {
                keycap("code", "165", 14, 23)
                arrow("name", 64)
                word("label", "Mode", 100, 38, 15, width = 42)
            },
        )
    },
    R.string.ui_115 to SettingGuide(R.array.guide_ui_115) {
        story({ holdThreshold(32) }, { holdThreshold(84) })
    },
    R.string.ui_116 to SettingGuide(R.array.guide_ui_116) {
        story({ debounce(false) }, { debounce(true) })
    },
    R.string.oem_steering to SettingGuide(R.array.guide_oem_steering) {
        story(
            {
                tile("wheel", BUTTONS, 13, 20, 33)
                arrow("gesture", 65)
                tile("oem", CAR, 87, 20, 33)
            },
            {
                tile("wheel", BUTTONS, 13, 20, 33)
                gesture("touch", 37, 24)
                arrow("gesture", 65)
                tile("voice", VOICE, 87, 20, 33)
            },
        )
    },
    R.string.ui_67 to SettingGuide(R.array.guide_ui_67) {
        story(
            {
                keycap("button", "▣", 14, 24)
                arrow("link", 63)
                tile("target", FIRMWARE, 85, 20, 38)
            },
            {
                keycap("button", "▣", 14, 24)
                arrow("link", 63)
                tile("target", HOME, 85, 20, 38)
            },
        )
    },
    R.string.ui_44 to SettingGuide(R.array.guide_ui_44) {
        story(
            {
                keycap("binding", "165", 12, 25)
                arrow("link", 61)
            },
            {
                keycap("binding", "165", 12, 25)
                arrow("link", 61)
                icon("tap-app", PLAY, 93, 12, 25)
                icon("hold-app", VOICE, 93, 45, 25)
            },
        )
    },
    R.string.button_service_enable to SettingGuide(R.array.guide_button_service_enable) {
        story(
            {
                connector("service", BUTTONS, SETTINGS, false)
                icon("permission", LOCK, 55, 51, 18, MUTED)
            },
            {
                connector("service", BUTTONS, SETTINGS)
                check("permission", 66, 59, true)
            },
        )
    },
    R.string.ui_53 to SettingGuide(R.array.guide_ui_53) {
        GuideStory(
            frame { detectKey(false) },
            frame { detectKey(true) },
            frame {
                detectKey(false)
                gesture("press", 37, 32)
            },
        )
    },
    R.string.ui_54 to SettingGuide(R.array.guide_ui_54) {
        story(
            {
                icon("keyboard", KEYBOARD, 12, 20, 37)
                word("empty", "—", 94, 39, 19, MUTED)
            },
            {
                icon("keyboard", KEYBOARD, 12, 20, 37)
                keycap("manual-code", "165", 79, 24)
            },
        )
    },
    R.string.ui_59 to SettingGuide(R.array.guide_ui_59) {
        story(
            {
                timeline("press", listOf("0", "0.5 s", "1 s"), 1)
                gesture("press", 22, 19)
            },
            {
                timeline("press", listOf("0", "0.5 s", "1 s"), 2)
                gesture("press", 22, 19)
                gesture("hold", 103, 19)
            },
        )
    },
    R.string.ui_62 to SettingGuide(R.array.guide_ui_62) {
        story(
            {
                panelKeys(true)
                check("assigned", 103, 57, true)
            },
            {
                screen("empty")
                icon("keys", BUTTONS, 49, 22, 33, MUTED)
                word("reset", "↺", 95, 43, 20, ACCENT)
            },
        )
    },
)

private fun GuideFrameBuilder.panelKeys(custom: Boolean) {
    screen("panel", 9, 16, 114, 46)
    listOf(HOME, CLIMATE, if (custom) PLAY else APPS).forEachIndexed { index, glyph ->
        tile("panel-key-$index", glyph, 16 + index * 34, 25, 28,
            if (custom && index == 2) WHITE else ACCENT, if (custom && index == 2) TEAL else ACTION)
    }
}

private fun GuideFrameBuilder.pressing(hold: Boolean, duration: Int, done: Boolean) {
    keycap("key", if (hold) "Ⅱ" else "•", 12, 19)
    gauge("held", 51, 48, 65, duration, knob = false)
    if (duration > 0) gesture("finger", 31, 33)
    tile("action", if (hold) VOICE else PLAY, 78, 12, 28, if (done) ACCENT else MUTED,
        if (done) ACTION else BORDER)
}

private fun GuideFrameBuilder.devices(any: Boolean) {
    tile("wheel", BUTTONS, 12, 13, 22)
    tile("remote", KEYBOARD, 12, 43, 22, if (any) ACCENT else MUTED)
    arrow("from-wheel", 57, 23)
    if (any) arrow("from-remote", 57, 56)
    keycap("code", "165", 81, 24)
}

private fun GuideFrameBuilder.binding(enabled: Boolean, code: String) {
    keycap("key", code, 10, 23, enabled)
    arrow("binding", 60)
    tile("target", VOICE, 84, 22, 32, if (enabled) ACCENT else MUTED, if (enabled) ACTION else BORDER)
    check("result", 68, 60, enabled)
}

private fun GuideFrameBuilder.holdThreshold(hold: Int) {
    gauge("press", 20, 42, 90, hold, knob = false)
    box("threshold", 73, 25, 2, 30, AMBER)
    word("threshold-text", "0.6 s", 74, 18, 11, AMBER)
    check("accepted", 113, 59, hold > 60)
}

private fun GuideFrameBuilder.debounce(filtered: Boolean) {
    box("track", 16, 53, 99, 2, BORDER)
    repeat(2) { i ->
        box("pulse-$i", 25 + i * 22, 21, 6, 32, if (filtered && i == 1) BORDER else ACCENT)
        check("pulse-result-$i", 28 + i * 22, 62, !filtered || i == 0)
    }
    word("duration", "50 ms", 90, 35, 12)
}

private fun GuideFrameBuilder.detectKey(detected: Boolean) {
    tile("physical", BUTTONS, 10, 23, 30)
    arrow("detect", 60)
    if (detected) keycap("captured", "165", 82, 23)
    else icon("listen", SEARCH, 86, 24, 28, MUTED)
}

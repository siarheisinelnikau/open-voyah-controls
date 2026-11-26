package org.openvoyah.controls.presentation.theme

import org.openvoyah.controls.R
import org.openvoyah.controls.presentation.theme.CockpitIcon.*
import org.openvoyah.controls.presentation.theme.Ink.*

internal val systemGuides = mapOf(
    R.string.action_permissions_title to SettingGuide(R.array.guide_action_permissions_title) { variant ->
        val target = CockpitIcon.entries.getOrElse(variant) { SYSTEM }
        story({ permission(true, target) }, { permission(false, target) })
    },
    R.string.cockpit_language to SettingGuide(R.array.guide_cockpit_language) {
        story({ localeScreen("EN", "Settings") }, { localeScreen("RU", "Настройки") })
    },
    R.string.keyboard_choice_section to SettingGuide(R.array.guide_keyboard_choice_section) {
        story({ keyboard("ABC") }, { keyboard("QWERTY") })
    },
    R.string.text_overlays_title to SettingGuide(R.array.guide_text_overlays_title) {
        story({ factoryText("中文", "空调", false) }, { factoryText("RU", "Климат", true) })
    },
    R.string.text_overlays_off to SettingGuide(R.array.guide_text_overlays_off) {
        story({ factoryText("KK", "Жарық", true) }, { factoryText("中文", "灯光", false) })
    },
    R.string.cockpit_dictionary_russian to SettingGuide(R.array.guide_cockpit_dictionary_russian) {
        story({ factoryText("中文", "设置", false) }, { factoryText("RU", "Настройки", true) })
    },
    R.string.cockpit_dictionary_english to SettingGuide(R.array.guide_cockpit_dictionary_english) {
        story({ factoryText("中文", "声音", false) }, { factoryText("EN", "Sound", true) })
    },
    R.string.cockpit_dictionary_kazakh to SettingGuide(R.array.guide_cockpit_dictionary_kazakh) {
        story({ factoryText("中文", "显示", false) }, { factoryText("KK", "Дисплей", true) })
    },
    R.string.firmware_translation_heading to SettingGuide(R.array.guide_firmware_translation_heading) {
        story(
            { factoryText("中文", "主题", false) },
            {
                factoryText("KK", "Тақырып", true)
                word("options", "EN · RU · KK", 66, 64, 9, ACCENT)
            },
        )
    },
    R.string.translation_import to SettingGuide(R.array.guide_translation_import) {
        story(
            {
                document("dictionary.json", false)
                word("import", "↓", 101, 34, 19, MUTED)
            },
            { factoryText("JSON", "Radio FM", true) },
        )
    },
    R.string.translation_disable to SettingGuide(R.array.guide_translation_disable) {
        story({ factoryText("EN", "Display", true) }, { factoryText("中文", "显示", false) })
    },
    R.string.translation_restore to SettingGuide(R.array.guide_translation_restore) {
        story(
            { factoryText("KK", "Дыбыс", true) },
            {
                factoryText("RU", "Звук", true)
                word("restore", "↶", 105, 57, 16, ACCENT)
            },
        )
    },
    R.string.text_overlays_apps_heading to SettingGuide(R.array.guide_text_overlays_apps_heading) {
        story({ appOverlay(false) }, { appOverlay(true) })
    },
    R.string.updates_startup_toggle to SettingGuide(R.array.guide_updates_startup_toggle) {
        GuideStory(frame { updateBoot(false, false) }, frame { updateBoot(true, true) }, frame { updateBoot(true, false) })
    },
    R.string.settings_archive_export to SettingGuide(R.array.guide_settings_archive_export) {
        story({ archive(false, false) }, { archive(false, true) })
    },
    R.string.settings_archive_import to SettingGuide(R.array.guide_settings_archive_import) {
        GuideStory(
            frame {
                page("settings", SETTINGS)
                toggle("example", false, 74, 42)
            },
            frame {
                page("settings", SETTINGS)
                toggle("example", true, 74, 42)
            },
            frame { archive(true, true) },
        )
    },
    R.string.diagnostics_problems_only to SettingGuide(R.array.guide_diagnostics_problems_only) {
        story({ logPreview(false) }, { logPreview(true) })
    },
)

private fun GuideFrameBuilder.permission(allowed: Boolean, target: CockpitIcon) {
    tile("request", VOICE, 10, 23, 27)
    arrow("dispatch", 53)
    tile("target", target, 90, 23, 29, if (allowed) ACCENT else MUTED, if (allowed) ACTION else BORDER)
    if (!allowed) icon("gate", LOCK, 66, 26, 19, MUTED)
    check("permission", 104, 61, allowed)
}

private fun GuideFrameBuilder.localeScreen(language: String, example: String) {
    screen("controls")
    icon("language-icon", LANGUAGE, 17, 25, 28)
    word("code", language, 84, 28, 12, ACCENT)
    word("label", example, 82, 50, 13, width = 71)
}

private fun GuideFrameBuilder.keyboard(layout: String) {
    screen("keyboard", 10, 12, 112, 54)
    word("example", layout, 66, 23, 12)
    val width = if (layout == "ABC") 9 else 7
    val keys = if (layout == "ABC") 7 else 10
    repeat(3) { row ->
        repeat(keys) { col ->
            box("key-$row-$col", 18 + col * (width + 3), 33 + row * 8, width, 5, ACTION, 2)
        }
    }
}

private fun GuideFrameBuilder.factoryText(code: String, sample: String, active: Boolean) {
    screen("factory")
    icon("firmware", FIRMWARE, 19, 20, 26, if (active) ACCENT else MUTED)
    word("language", code, 82, 23, 13, if (active) ACCENT else MUTED)
    word("sample", sample, 80, 46, 14, width = 68)
}

private fun GuideFrameBuilder.appOverlay(translated: Boolean) {
    icon("vehicle", CAR, 16, 15, 20)
    word("vehicle-text", if (translated) "Шины" else "轮胎", 83, 25, 13)
    icon("climate", CLIMATE, 16, 45, 20, MUTED)
    word("climate-text", "座椅", 83, 55, 13, MUTED)
}

private fun GuideFrameBuilder.document(name: String, selected: Boolean) {
    box("file", 13, 15, 52, 49, if (selected) TEAL else ACTION)
    icon("file-icon", DOWNLOAD, 24, 18, 31, if (selected) WHITE else ACCENT)
    word("file-name", name, 39, 53, 9, if (selected) WHITE else FG, width = 46)
}

private fun GuideFrameBuilder.updateBoot(enabled: Boolean, checking: Boolean) {
    tile("boot", ENERGY, 13, 24, 29)
    if (enabled) arrow("automatic", 61)
    icon("updates", if (checking) SEARCH else DOWNLOAD, 87, 22, 31, if (enabled) ACCENT else MUTED)
}

private fun GuideFrameBuilder.archive(restore: Boolean, exported: Boolean) {
    icon("preferences", SETTINGS, if (restore) 90 else 17, 22, 31)
    if (exported) {
        box("file", if (restore) 13 else 88, 20, 31, 39, ACTION)
        word("json", "JSON", if (restore) 29 else 103, 40, 12)
        arrow("transfer", 65)
    }
}

private fun GuideFrameBuilder.logPreview(filtered: Boolean) {
    screen("log")
    if (!filtered) {
        word("info", "i", 27, 24, 15, MUTED)
        lines("info-line", 47, 23, 55, 1)
    }
    word("warning", "!", 27, if (filtered) 23 else 41, 16, AMBER)
    lines("warning-line", 47, if (filtered) 22 else 40, 55, 1)
    word("error", "×", 27, if (filtered) 42 else 58, 17)
    lines("error-line", 47, if (filtered) 40 else 56, 44, 1)
}

package org.openvoyah.controls.presentation.theme

import org.openvoyah.controls.R
import org.openvoyah.controls.presentation.theme.CockpitIcon.*
import org.openvoyah.controls.presentation.theme.Ink.*

internal val dockGuides = mapOf(
    R.string.quick_actions_enabled to SettingGuide(R.array.guide_quick_actions_enabled) {
        story({ dockScene(visible = false) }, { dockScene() })
    },
    R.string.quick_actions_area to SettingGuide(R.array.guide_quick_actions_area) {
        story({ dockScene(page = APPS, secondary = true) }, { dockScene(page = APPS, secondary = true, allPages = true) })
    },
    R.string.quick_actions_hide_on_virtual_display to SettingGuide(R.array.guide_quick_actions_hide_on_virtual_display) {
        story({ dockScene(page = DISPLAY) }, { dockScene(page = DISPLAY, visible = false) })
    },
    R.string.quick_actions_headlight_indicator to SettingGuide(R.array.guide_quick_actions_headlight_indicator) {
        story({ dockScene() }, { dockScene(indicator = LIGHT) })
    },
    R.string.quick_actions_headlight_content to SettingGuide(R.array.guide_quick_actions_headlight_content) {
        story(
            { dockScene(indicator = LIGHT, indicatorText = "AUTO") },
            { dockScene(indicator = HIGH_BEAM, indicatorText = "✓") },
        )
    },
    R.string.quick_actions_headlight_tap to SettingGuide(R.array.guide_quick_actions_headlight_tap) {
        story(
            { dockScene(page = CAR, indicator = LIGHT) },
            {
                dockScene(page = APPS, indicator = LIGHT)
                screen("report", 44, 16, 71, 48)
                icon("lamp", HIGH_BEAM, 50, 24, 23)
                lines("source", 76, 33, 31, 3)
            },
        )
    },
    R.string.quick_actions_apps_destination to SettingGuide(R.array.guide_quick_actions_apps_destination) {
        story(
            {
                dockScene(page = HOME, shortcutList = listOf(APPS))
                word("favorites", "★", 85, 34, 20, AMBER)
            },
            {
                dockScene(page = APPS, shortcutList = listOf(APPS))
                icon("apps-more", APPS, 89, 44, 17)
            },
        )
    },
    R.string.quick_actions_side to SettingGuide(R.array.guide_quick_actions_side) {
        story({ dockScene() }, { dockScene(right = true) })
    },
    R.string.quick_actions_wide_width to SettingGuide(R.array.guide_quick_actions_wide_width) {
        story({ dockScene(width = 38) }, { dockScene(width = 20) })
    },
    R.string.quick_actions_rail_alignment to SettingGuide(R.array.guide_quick_actions_rail_alignment) {
        story(
            { dockScene(shortcutList = listOf(APPS, CAR)) },
            { dockScene(shortcutList = listOf(APPS, CAR), centered = true) },
        )
    },
    R.string.quick_actions_compact_edge to SettingGuide(R.array.guide_quick_actions_compact_edge) {
        story({ compactDock() }, { compactDock(bottom = true) })
    },
    R.string.quick_actions_labels to SettingGuide(R.array.guide_quick_actions_labels) {
        story({ dockScene(width = 35) }, { dockScene(width = 35, labels = true) })
    },
    R.string.quick_actions_compact_labels to SettingGuide(R.array.guide_quick_actions_compact_labels) {
        story({ compactDock(labels = true) }, { compactDock(labels = false) })
    },
    R.string.quick_actions_start_state to SettingGuide(R.array.guide_quick_actions_start_state) {
        story(
            {
                dockScene()
                icon("startup", ENERGY, 93, 34, 24)
            },
            {
                dockScene(collapsed = true)
                icon("startup", ENERGY, 93, 34, 24)
            },
        )
    },
    R.string.quick_actions_collapse_after_use to SettingGuide(R.array.guide_quick_actions_collapse_after_use) {
        GuideStory(
            frame { dockScene(page = CAR) },
            frame { dockScene(page = CAR, collapsed = true) },
            frame {
                dockScene(page = CAR)
                gesture("tap", 18, 36)
            },
        )
    },
    R.string.quick_actions_idle_collapse to SettingGuide(R.array.guide_quick_actions_idle_collapse) {
        GuideStory(
            frame {
                dockScene()
                word("elapsed", "0 s", 88, 49, 14)
            },
            frame {
                dockScene(collapsed = true)
                word("elapsed", "10 s", 88, 49, 14)
            },
            frame {
                dockScene()
                word("elapsed", "10 s", 88, 49, 14)
            },
        )
    },
    R.string.quick_actions_keep_side_inset to SettingGuide(R.array.guide_quick_actions_keep_side_inset) {
        story({ dockScene(collapsed = true) }, { dockScene(collapsed = true, keepInset = true) })
    },
    R.string.quick_actions_handle_size to SettingGuide(R.array.guide_quick_actions_handle_size) {
        story({ dockScene(collapsed = true, handle = 15) }, { dockScene(collapsed = true, handle = 26) })
    },
    R.string.quick_actions_side_handle_position to SettingGuide(R.array.guide_quick_actions_side_handle_position) {
        story({ dockScene(collapsed = true, handleY = 15) }, { dockScene(collapsed = true, handleY = 46) })
    },
    R.string.quick_actions_compact_handle_side to SettingGuide(R.array.guide_quick_actions_compact_handle_side) {
        story({ compactDock(collapsed = true) }, { compactDock(collapsed = true, right = true) })
    },
    R.string.quick_actions_handle_accent to SettingGuide(R.array.guide_quick_actions_handle_accent) {
        story({ dockScene(collapsed = true, tone = ACCENT) }, { dockScene(collapsed = true, tone = TEAL) })
    },
    R.string.quick_actions_dim_idle_handle to SettingGuide(R.array.guide_quick_actions_dim_idle_handle) {
        GuideStory(
            frame { dockScene(collapsed = true) },
            frame { dockScene(collapsed = true, tone = MUTED) },
            frame {
                dockScene(collapsed = true)
                icon("timer", ROUTINE, 91, 44, 20)
            },
        )
    },
    R.string.quick_actions_contrast_handle to SettingGuide(R.array.guide_quick_actions_contrast_handle) {
        story({ dockScene(collapsed = true, tone = BORDER) }, { dockScene(collapsed = true, tone = NIGHT, contrast = true) })
    },
    R.string.quick_actions_compact_handle_label to SettingGuide(R.array.guide_quick_actions_compact_handle_label) {
        story({ compactDock(collapsed = true) }, { compactDock(collapsed = true, labels = true) })
    },
    R.string.quick_actions_larger_shortcuts to SettingGuide(R.array.guide_quick_actions_larger_shortcuts) {
        story(
            { dockScene(shortcutList = listOf(APPS, CAR, VOICE), width = 28) },
            { dockScene(shortcutList = listOf(APPS, CAR, VOICE), width = 28, large = true) },
        )
    },
    R.string.quick_actions_routine_shortcut to SettingGuide(R.array.guide_quick_actions_routine_shortcut) {
        story(
            {
                tile("shortcut", ROUTINE, 13, 23, 29)
                arrow("go", 63)
                page("target", ROUTINE, 81, 13, 44, 52)
            },
            {
                tile("shortcut", ROUTINE, 13, 23, 29)
                arrow("go", 63)
                word("run", "▸ ★", 101, 38, 20, ACCENT)
            },
        )
    },
    R.string.quick_actions_shortcuts to SettingGuide(R.array.guide_quick_actions_shortcuts) {
        story(
            { dockScene(shortcutList = listOf(APPS, CAR, VOICE)) },
            { dockScene(shortcutList = listOf(VOICE, LIGHT, APPS)) },
        )
    },
    R.string.quick_actions_app_shortcuts to SettingGuide(R.array.guide_quick_actions_app_shortcuts) {
        story(
            { compactDock(shortcuts = listOf(APPS, CAR, VOICE)) },
            { compactDock(shortcuts = listOf(APPS, CAR, VOICE, PLAY, HOME)) },
        )
    },
)

private fun GuideFrameBuilder.dockScene(
    page: CockpitIcon = APPS, visible: Boolean = true, secondary: Boolean = false, allPages: Boolean = false,
    indicator: CockpitIcon? = null, indicatorText: String? = null, right: Boolean = false, width: Int = 24,
    centered: Boolean = false, labels: Boolean = false, collapsed: Boolean = false, keepInset: Boolean = false,
    handle: Int = 20, handleY: Int = 29, tone: Ink = ACCENT, contrast: Boolean = false, large: Boolean = false,
    shortcutList: List<CockpitIcon> = listOf(APPS, CAR, VOICE),
) {
    val h = if (secondary) 28 else 62
    screen("display", 7, 7, 118, h)
    val inset = if (visible && (!collapsed || keepInset)) width else 0
    val paneLeft = 10 + if (right) 0 else inset
    val dockLeft = if (right) 122 - width else 10
    box("content", paneLeft, 10, 112 - inset, h - 6, SURFACE)
    icon("page", page, paneLeft + (112 - inset) / 2 - 12, 7 + h / 2 - 12, 24)
    if (visible && !collapsed) {
        box("rail", dockLeft, 10, width, h - 6, ACTION, 3)
        val top = when {
            secondary -> 13
            centered -> 19
            else -> 13
        }
        val icons = if (secondary) shortcutList.take(1) else shortcutList
        icons.forEachIndexed { index, glyph ->
            val size = if (large && glyph == VOICE) 21 else 13
            val x = dockLeft + if (labels) 3 else (width - size) / 2
            val y = top + index * 14
            icon("shortcut-$glyph", glyph, x, y, size)
            if (labels) lines("label-$glyph", dockLeft + 18, y + 6, width - 21, 1, thickness = 2)
        }
        indicator?.let {
            icon("lights", it, dockLeft + 4, 52, 15)
            indicatorText?.let { label -> word("indicator-label", label, dockLeft + width + 15, 62, 9, ACCENT, width = 31) }
        }
    } else if (collapsed) {
        box("handle", if (right) 122 - handle else 10, handleY, handle, handle, tone, 4)
        icon("expand", if (right) BACK else ARROW, if (right) 120 - handle else 12,
            handleY + 2, handle - 4, if (contrast) WHITE else SURFACE)
    }
    if (secondary) {
        screen("second-display", 7, 42, 118, 28)
        if (allPages) box("rail-on-other-page", 10, 45, width, 22, ACTION, 3)
        if (allPages) icon("shortcut-other", APPS, 15, 49, 13)
        icon("other-page", CAR, if (allPages) 68 else 54, 46, 20)
    }
}

private fun GuideFrameBuilder.compactDock(bottom: Boolean = false, labels: Boolean = false,
                                          collapsed: Boolean = false, right: Boolean = false,
                                          shortcuts: List<CockpitIcon> = listOf(APPS, CAR, VOICE)) {
    screen("compact")
    val y = if (bottom) 49 else 11
    if (collapsed) {
        val x = if (right) 94 else 12
        box("handle", x, y, if (labels) 43 else 24, 17, ACTION, 4)
        icon("chevron", EXPAND, x + 4, y + 1, 14)
        if (labels) lines("label", x + 20, y + 7, 17, 1, ink = ACCENT, thickness = 2)
    } else {
        box("bar", 12, y, 108, 17, ACTION, 4)
        shortcuts.forEachIndexed { index, glyph ->
            val x = 16 + index * (101 / shortcuts.size)
            icon("shortcut-$glyph", glyph, x, y + 2, 13)
            if (labels) lines("label-$glyph", x + 14, y + 7, 15, 1, thickness = 2)
        }
    }
    icon("page", DISPLAY, 54, if (bottom) 19 else 37, 25)
}

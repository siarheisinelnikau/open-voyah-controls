package org.openvoyah.controls.presentation.theme

import org.openvoyah.controls.R
import org.openvoyah.controls.presentation.theme.CockpitIcon.*
import org.openvoyah.controls.presentation.theme.Ink.*

internal val homeGuides = mapOf(
    R.string.apps_grid_tile_size to SettingGuide(R.array.guide_apps_grid_tile_size) {
        GuideStory(appGrid(size = 29), appGrid(size = 19))
    },
    R.string.apps_grid_columns to SettingGuide(R.array.guide_apps_grid_columns) {
        GuideStory(appGrid(columns = 3), appGrid(columns = 4))
    },
    R.string.apps_grid_icon_style to SettingGuide(R.array.guide_apps_grid_icon_style) {
        GuideStory(appGrid(colorful = true), appGrid())
    },
    R.string.apps_grid_show_labels to SettingGuide(R.array.guide_apps_grid_show_labels) {
        GuideStory(appGrid(), appGrid(labels = true))
    },
    R.string.apps_grid_sort_order to SettingGuide(R.array.guide_apps_grid_sort_order) {
        GuideStory(appGrid(apps = listOf(ROUTINE, PLAY, SETTINGS, HOME), labels = true), appGrid(apps = listOf(SETTINGS, HOME, ROUTINE, PLAY), labels = true))
    },
    R.string.apps_grid_edit_manual_order to SettingGuide(R.array.guide_apps_grid_edit_manual_order) {
        GuideStory(appGrid(apps = listOf(PLAY, HOME, SETTINGS)), appGrid(apps = listOf(HOME, PLAY, SETTINGS)), appGrid(apps = listOf(PLAY, HOME, SETTINGS), held = HOME))
    },
    R.string.apps_grid_shortcuts to SettingGuide(R.array.guide_apps_grid_shortcuts) {
        GuideStory(appGrid(apps = listOf(SETTINGS, PLAY, HOME)), appGrid(apps = listOf(SETTINGS, VOICE, PLAY, HOME)))
    },
    R.string.apps_grid_hidden_apps to SettingGuide(R.array.guide_apps_grid_hidden_apps) {
        GuideStory(appGrid(apps = listOf(PLAY, ROUTINE, HOME, SETTINGS), labels = true), appGrid(apps = listOf(PLAY, HOME, SETTINGS), labels = true))
    },
    R.string.apps_grid_show_search_field to SettingGuide(R.array.guide_apps_grid_show_search_field) {
        GuideStory(appGrid(), appGrid(search = true))
    },
    R.string.apps_grid_hide_by_default to SettingGuide(R.array.guide_apps_grid_hide_by_default) {
        GuideStory(appGrid(apps = listOf(PLAY, HOME, FIRMWARE, SYSTEM, KEYBOARD)), appGrid(apps = listOf(PLAY, HOME)))
    },
    R.string.apps_desktop_enable to SettingGuide(R.array.guide_apps_desktop_enable) {
        GuideStory(appGrid(tabs = 1), appGrid(tabs = 3))
    },
    R.string.apps_desktop_default to SettingGuide(R.array.guide_apps_desktop_default) {
        GuideStory(startingDesktop(false), startingDesktop(true))
    },
    R.string.apps_grid_desktop_names to SettingGuide(R.array.guide_apps_grid_desktop_names) {
        GuideStory(appGrid(tabs = 3, tab = 1, tabName = "★ 1"), appGrid(tabs = 3, tab = 1, tabName = "Daily drive"))
    },
    R.string.apps_grid_favorites_back to SettingGuide(R.array.guide_apps_grid_favorites_back) {
        story(
            {
                icon("back", BACK, 12, 29, 24)
                arrow("go", 62)
                page("target", HOME, 83, 17, 43, 45)
            },
            {
                icon("back", BACK, 12, 29, 24)
                arrow("go", 62)
                page("target", APPS, 83, 17, 43, 45)
            },
        )
    },
    R.string.apps_grid_smooth_paging to SettingGuide(R.array.guide_apps_grid_smooth_paging) {
        GuideStory(
            paging(false),
            paging(true),
            appGrid(apps = listOf(PLAY, HOME), tabs = 3, tab = 1, sliding = true),
        )
    },
    R.string.apps_wallpaper_title to SettingGuide(R.array.guide_apps_wallpaper_title) {
        GuideStory(appGrid(), appGrid(wallpaper = true))
    },
    R.string.desktop_layouts_settings to SettingGuide(R.array.guide_desktop_layouts_settings) {
        GuideStory(appGrid(tabs = 3), appGrid(apps = listOf(HOME, PLAY), tabs = 3, tab = 1, wallpaper = true), frame { tile("saved", DOWNLOAD, 48, 19, 37) })
    },
    R.string.desktop_layouts_name to SettingGuide(R.array.guide_desktop_layouts_name) {
        story({ layoutCard("★ 1") }, { layoutCard("Trip layout") })
    },
    R.string.desktop_info_settings to SettingGuide(R.array.guide_desktop_info_settings) {
        GuideStory(appGrid(apps = listOf(PLAY, HOME)), appGrid(apps = listOf(PLAY, HOME), card = true))
    },
    R.string.desktop_info_desktop to SettingGuide(R.array.guide_desktop_info_desktop) {
        GuideStory(appGrid(tabs = 3, card = true), appGrid(tabs = 3, tab = 1, card = true, cardIcon = LIGHT))
    },
    R.string.apps_grid_card_placement to SettingGuide(R.array.guide_apps_grid_card_placement) {
        GuideStory(appGrid(apps = listOf(PLAY, HOME), card = true), appGrid(apps = listOf(PLAY, HOME), card = true, pinsFirst = true))
    },
    R.string.apps_grid_card_outlines to SettingGuide(R.array.guide_apps_grid_card_outlines) {
        GuideStory(appGrid(apps = listOf(PLAY, HOME), card = true), appGrid(apps = listOf(PLAY, HOME), card = true, cardOutline = true))
    },
    R.string.desktop_info_clock_format to SettingGuide(R.array.guide_desktop_info_clock_format) {
        story({ clock("17:40") }, { clock("5:40 PM") })
    },
    R.string.desktop_info_media_style to SettingGuide(R.array.guide_desktop_info_media_style) {
        story(
            { player("music", 8, 11, 116, 56, controls = true) },
            { player("music", 8, 11, 116, 56, art = true, controls = true) },
        )
    },
    R.string.desktop_info_media_open_target to SettingGuide(R.array.guide_desktop_info_media_open_target) {
        story(
            {
                player("music", 12, 21, 108, 36)
                gesture("tap", 64, 49)
            },
            {
                page("target", PLAY)
                cover("cover", 23, 39, 22)
                signal("playing", 58, 50)
            },
        )
    },
    R.string.desktop_info_media_source to SettingGuide(R.array.guide_desktop_info_media_source) {
        story(
            { player("media", 9, 10, 114, 57, title = "Radio FM", subtitle = "97.5 MHz", controls = true) },
            { player("media", 9, 10, 114, 57, art = true, title = "Night Drive", subtitle = "Atlas", controls = true) },
        )
    },
    R.string.desktop_info_headlights_content to SettingGuide(R.array.guide_desktop_info_headlights_content) {
        story(
            {
                icon("switch", LIGHT, 17, 23, 32)
                word("mode", "AUTO", 87, 40, 16)
            },
            {
                icon("low", LIGHT, 17, 20, 32)
                check("low-on", 43, 54, true)
                icon("high", HIGH_BEAM, 77, 20, 32, MUTED)
                check("high-off", 103, 54, false)
            },
        )
    },
    R.string.desktop_info_phone_edit to SettingGuide(R.array.guide_desktop_info_phone_edit) {
        story({ phoneCard(false) }, { phoneCard(true) })
    },
    R.string.desktop_info_route_content to SettingGuide(R.array.guide_desktop_info_route_content) {
        story({ desktopRoute(false) }, { desktopRoute(true) })
    },
    R.string.desktop_info_route_open_target to SettingGuide(R.array.guide_desktop_info_route_open_target) {
        story(
            {
                route("route", 16, 14, 100, 50, "300 m")
                gesture("tap", 63, 52)
            },
            {
                mapCard("map", 12, 11, 108, 56, true)
                tile("app", HOME, 94, 15, 24)
            },
        )
    },
    R.string.home_navigator_card to SettingGuide(R.array.guide_home_navigator_card) {
        story(
            {
                mapCard("map", 11, 11, 110, 56, false)
                word("source", "Baidu", 90, 56, 11, MUTED)
            },
            {
                mapCard("map", 11, 11, 110, 56, true)
                word("source", "Yandex", 90, 56, 11)
            },
        )
    },
    R.string.home_navigator_widget_scale to SettingGuide(R.array.guide_home_navigator_widget_scale) {
        story(
            {
                mapCard("map", 12, 12, 108, 54, true)
                word("scale", "100%", 86, 54, 11)
            },
            {
                mapCard("map", 12, 12, 108, 54, true, zoom = true)
                word("scale", "125%", 86, 54, 14)
            },
        )
    },
    R.string.home_navigator_widget_settings to SettingGuide(R.array.guide_home_navigator_widget_settings) {
        story(
            { mapCard("map", 12, 12, 108, 54, true) },
            {
                mapCard("map", 12, 12, 108, 54, true, zoom = true, routeInk = TEAL)
                tile("controls", SETTINGS, 89, 17, 27)
            },
        )
    },
    R.string.home_weather_card to SettingGuide(R.array.guide_home_weather_card) {
        story(
            {
                screen("home")
                mapCard("map", 14, 16, 104, 46, true)
            },
            {
                screen("home")
                mapCard("map", 14, 16, 60, 46, true)
                weather("Almaty", "22°", 80, 22, 36)
            },
        )
    },
    R.string.home_weather_city to SettingGuide(R.array.guide_home_weather_city) {
        story({ weather("Minsk", "8°") }, { weather("Almaty", "22°") })
    },
    R.string.home_media_card_apps to SettingGuide(R.array.guide_home_media_card_apps) {
        story({ sourceList("Radio FM", "Bluetooth") }, { sourceList("Radio FM", "Bluetooth", "Voyah Music", "Voyah Radio") })
    },
    R.string.home_media_card_hide_online to SettingGuide(R.array.guide_home_media_card_hide_online) {
        story(
            { sourceList("Funn Audio", "Ximalaya", "Yunting", "Bluetooth", unavailable = true) },
            { sourceList("Bluetooth") },
        )
    },
    R.string.home_cluster_card_apps to SettingGuide(R.array.guide_home_cluster_card_apps) {
        story(
            { cluster { player("source", 12, 22, 66, 34, title = "Radio FM") } },
            {
                cluster { player("source", 12, 22, 66, 34, title = "Voyah Music"); word("source-list", "‹   • •   ›", 45, 49, 11) }
            },
        )
    },
    R.string.driver_media_layout to SettingGuide(R.array.guide_driver_media_layout) {
        story(
            { cluster { player("source", 14, 22, 65, 34, title = "Voyah Music") } },
            {
                cluster { player("source", 10, 13, 77, 52, art = true, progress = true, title = "Night Drive", subtitle = "Atlas") }
            },
        )
    },
    R.string.driver_navigation_card to SettingGuide(R.array.guide_driver_navigation_card) {
        GuideStory(
            frame { cluster { player("media", 12, 20, 65, 39, title = "Night Drive", progress = true) } },
            frame { cluster { route("route", 10, 15, 76, 47, "300 m", next = "Abay") } },
            frame { cluster { route("route", 10, 15, 76, 47, "500 m", next = "Abay") } },
        )
    },
    R.string.apps_hide to SettingGuide(R.array.guide_apps_hide) {
        story(
            {
                tile("app", PLAY, 12, 25, 27)
                arrow("link", 60)
                tile("all", APPS, 81, 8, 24)
                tile("favorite", HOME, 81, 45, 24)
                check("all-result", 114, 20, true)
                check("favorite-result", 114, 57, true)
            },
            {
                tile("app", PLAY, 12, 25, 27)
                arrow("link", 60)
                tile("all", APPS, 81, 8, 24)
                tile("favorite", HOME, 81, 45, 24)
                check("all-result", 114, 20, false)
                check("favorite-result", 114, 57, true)
            },
        )
    },
    R.string.app_icon_plain to SettingGuide(R.array.guide_app_icon_plain) {
        story({ tile("app", HOME, 40, 14, 52, TEAL, WHITE, 14) }, { tile("app", HOME, 40, 14, 52, TEAL, SURFACE, 14) })
    },
    R.string.apps_grid_reorder_pins to SettingGuide(R.array.guide_apps_grid_reorder_pins) {
        GuideStory(appGrid(apps = listOf(HOME, PLAY, ROUTINE), pinned = true), appGrid(apps = listOf(ROUTINE, HOME, PLAY), pinned = true))
    },
    R.string.app_picker_show_all to SettingGuide(R.array.guide_app_picker_show_all) {
        story({ picker(false) }, { picker(true) })
    },
    R.string.app_icon_title to SettingGuide(R.array.guide_app_icon_title) {
        story(
            { tile("app", HOME, 41, 16, 50) },
            {
                box("app-tile", 41, 16, 50, 50, TEAL, 8)
                word("app-picture", "▲", 66, 43, 29, WHITE)
                word("app-sun", "●", 78, 26, 9, WHITE)
            },
        )
    },
    R.string.apps_permissions to SettingGuide(R.array.guide_apps_permissions) {
        story(
            {
                tile("app", APPS, 13, 23, 30)
                arrow("link", 65)
                tile("permission", VOICE, 88, 18, 32)
                check("access", 104, 62, false)
            },
            {
                tile("app", APPS, 13, 23, 30)
                arrow("link", 65)
                tile("permission", VOICE, 88, 18, 32)
                check("access", 104, 62, true)
            },
        )
    },
    R.string.desktop_info_phone_add to SettingGuide(R.array.guide_desktop_info_phone_add) {
        story(
            { phoneCard(false); word("contact", "Anna", 82, 20, 11) },
            { phoneCard(true); check("added", 107, 57, true) },
        )
    },
    R.string.app_icon_reset to SettingGuide(R.array.guide_app_icon_reset) {
        story(
            { tile("custom", ROUTINE, 43, 14, 48, WHITE, TEAL, 11); word("return", "↶", 107, 39, 17, ACCENT) },
            { tile("original", HOME, 43, 14, 48, ACCENT, ACTION, 11) },
        )
    },
)

private fun startingDesktop(favorites: Boolean) = frame {
    tile("launcher", APPS, 9, 25, 23)
    arrow("open", 47)
    screen("destination", 61, 13, 62, 52)
    tab("all-apps", 67, 18, 23, !favorites)
    tab("favorites", 94, 18, 23, favorites)
    tile("maps", HOME, 68, 31, 17)
    tile("audio", PLAY, 88, 31, 17)
    if (!favorites) tile("clock", ROUTINE, 104, 46, 14)
    else word("star", "★", 110, 45, 13, AMBER)
}

private fun paging(smooth: Boolean) = frame {
    screen("desktop", 9, 12, 114, 52)
    tab("first", 17, 16, 42, !smooth)
    tab("second", 70, 16, 42, smooth)
    if (smooth) {
        // The paused example retains the overlap of the outgoing and incoming pages.
        box("page-gap", 71, 27, 2, 29, BORDER)
        tile("out", PLAY, 24, 31, 20, MUTED, BORDER)
        tile("in", HOME, 90, 31, 20)
        word("swipe", "←", 62, 41, 16, ACCENT)
    } else {
        tile("in", HOME, 49, 31, 22)
        tile("next", ROUTINE, 83, 31, 20)
    }
}

private fun GuideFrameBuilder.desktopRoute(showRoad: Boolean) {
    screen("desktop")
    route("route", 15, 14, 79, 46, "300 m", current = "Dostyk".takeIf { showRoad },
        next = "Abay", ink = ACTION)
    tile("map", HOME, 99, 29, 18)
}

private fun appGrid(
    apps: List<CockpitIcon> = listOf(HOME, PLAY, ROUTINE, SETTINGS, VOICE, DISPLAY),
    columns: Int = 3, size: Int = 20, colorful: Boolean = false, labels: Boolean = false,
    search: Boolean = false, tabs: Int = 0, tab: Int = 0, tabName: String? = null,
    wallpaper: Boolean = false, card: Boolean = false, cardIcon: CockpitIcon = ROUTINE,
    pinsFirst: Boolean = false, cardOutline: Boolean = false, pinned: Boolean = false,
    held: CockpitIcon? = null, sliding: Boolean = false,
) = frame {
    screen("desktop", ink = if (wallpaper) NIGHT else SURFACE)
    if (wallpaper) {
        word("mountain", "▲", 43, 49, 49, TEAL)
        word("sun", "●", 95, 26, 21, AMBER)
    }
    repeat(tabs) { i -> tab("tab$i", 14 + i * 35, 12, 32, tab == i) }
    tabName?.let { word("name", it, 72, 61, 11) }
    if (search) {
        box("search-field", 15, 11, 102, 14, ACTION)
        icon("search", SEARCH, 20, 13, 10)
        lines("query", 35, 17, 36, 1)
    }
    val gap = (104 - columns * size) / (columns - 1)
    val top = if (tabs > 0 || search) 29 else 13
    if (card) {
        val x = if (pinsFirst) 67 else 14
        box("card", x, 28, 50, 34, if (cardOutline) ACCENT else ACTION, 5, outline = cardOutline)
        icon("card-content", cardIcon, x + 14, 34, 22)
    }
    apps.forEachIndexed { index, app ->
        val x = if (card) (if (pinsFirst) 14 else 68) + index * 24 else 14 + (index % columns) * (size + gap)
        val y = if (card) 33 else top + (index / columns) * (size + if (labels) 9 else 4)
        if (x + size <= 123 && y + size <= 70) {
            val tileX = x + if (sliding) 28 else 0
            val tileY = y - if (app == held) 6 else 0
            val tone = if (colorful) listOf(TEAL, AMBER, NIGHT)[index % 3] else ACTION
            tile("app-$app", app, tileX, tileY, size, if (colorful) WHITE else ACCENT, tone)
            if (labels) word("name-$app", demoAppName(app), tileX + size / 2, tileY + size + 4, 7,
                if (wallpaper) WHITE else FG, width = size + 6)
            if (pinned) word("pin-$app", "★", x + size, y + 2, 9, AMBER)
            if (app == held) gesture("held", tileX + size - 5, tileY + size - 5)
        }
    }
}

private fun demoAppName(app: CockpitIcon) = when (app) {
    HOME -> "Maps"
    PLAY -> "Music"
    ROUTINE -> "Clock"
    SETTINGS -> "Controls"
    VOICE -> "Voice"
    else -> "Video"
}

private fun GuideFrameBuilder.layoutCard(name: String) {
    screen("saved", 17, 13, 98, 53)
    tile("layout", APPS, 26, 24, 27)
    word("name", name, 83, 34, 13, width = 54)
    lines("date", 61, 47, 42, 1)
}

private fun GuideFrameBuilder.clock(value: String) {
    screen("clock", 16, 17, 100, 45)
    icon("clock-face", ROUTINE, 23, 28, 22)
    word("time", value, 78, 40, 18, width = 64)
}

private fun GuideFrameBuilder.phoneCard(saved: Boolean) {
    screen("phone")
    word("phone-icon", "☎", 29, 38, 24, ACCENT)
    if (saved) {
        word("favorite", "★", 56, 28, 13, AMBER)
        word("name", "Anna", 86, 28, 13, width = 44)
        lines("number", 54, 42, 48, 1)
    } else word("add", "+", 84, 39, 22, MUTED)
}

internal fun GuideFrameBuilder.mapCard(key: String, x: Int, y: Int, w: Int, h: Int, active: Boolean,
                                        zoom: Boolean = false, routeInk: Ink = ACCENT) {
    box("$key-map", x, y, w, h, ACTION, 5)
    val road = if (zoom) 5 else 2
    repeat(3) { row -> box("$key-road-$row", x + 5, y + (row + 1) * h / 4, w - 10, road, SURFACE) }
    box("$key-cross-street", x + w / 3, y + 4, road, h - 8, SURFACE)
    box("$key-lane", x + w / 2, y + h / 4, road + 1, h * 2 / 5, if (active) routeInk else MUTED)
    box("$key-turn", x + w / 2, y + h / 4, w / 5, road + 1, if (active) routeInk else MUTED)
    word("$key-location", "▲", x + w / 2 + 1, y + h * 2 / 3, 13, if (active) routeInk else MUTED)
}

private fun GuideFrameBuilder.weather(city: String, degrees: String, x: Int = 12, y: Int = 14, w: Int = 108) {
    box("weather", x, y, w, 43, ACTION)
    val wide = w > 60
    word("weather-mark", "☀", x + if (wide) 20 else 10, y + 14, if (wide) 22 else 14, AMBER)
    word("temperature", degrees, x + if (wide) 66 else 25, y + 16, if (wide) 24 else 13, width = w / 2)
    word("city", city, x + w / 2, y + 33, if (wide) 13 else 9, width = w - 3)
}

private fun GuideFrameBuilder.sourceList(vararg sources: String, unavailable: Boolean = false) {
    screen("list")
    sources.forEachIndexed { i, source ->
        val y = 17 + 14 * i
        val tone = if (unavailable && source != "Bluetooth") MUTED else FG
        word("icon-$source", if (tone == MUTED) "×" else "♪", 21, y, 12, tone)
        word("source-$source", source, 72, y, 11, tone, width = 80)
    }
}

private fun GuideFrameBuilder.cluster(content: GuideFrameBuilder.() -> Unit) {
    screen("cluster", 4, 8, 124, 62, NIGHT, radius = 12)
    word("speed", "42", 104, 34, 24, WHITE)
    word("units", "km/h", 104, 53, 9, WHITE)
    content()
}

private fun GuideFrameBuilder.picker(showFirmware: Boolean) {
    screen("picker")
    toggle("all", showFirmware, 82, 15)
    icon("list", APPS, 19, 12, 21)
    tile("music", PLAY, 22, 38, 23)
    tile("map", HOME, 53, 38, 23)
    if (showFirmware) tile("service", FIRMWARE, 84, 38, 23)
}

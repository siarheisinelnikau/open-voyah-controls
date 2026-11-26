package org.openvoyah.controls.presentation.theme

import org.openvoyah.controls.presentation.theme.CockpitIcon.*
import org.openvoyah.controls.presentation.theme.Ink.*

internal fun GuideFrameBuilder.screen(key: String, x: Int = 8, y: Int = 7, w: Int = 116, h: Int = 64,
                                      ink: Ink = SURFACE, rim: Ink = BORDER, radius: Int = 5) {
    box("$key-rim", x, y, w, h, rim, radius)
    box(key, x + 2, y + 2, w - 4, h - 4, ink, radius)
}

internal fun GuideFrameBuilder.tile(key: String, icon: CockpitIcon, x: Int, y: Int, size: Int = 26,
                                    tint: Ink = ACCENT, fill: Ink = ACTION, radius: Int = 6) {
    box("$key-tile", x, y, size, size, fill, radius)
    this.icon(key, icon, x + size / 5, y + size / 5, size * 3 / 5, tint)
}

internal fun GuideFrameBuilder.lines(key: String, x: Int, y: Int, width: Int, count: Int = 2,
                                     spacing: Int = 8, ink: Ink = MUTED, thickness: Int = 3) {
    repeat(count) { line ->
        box("$key-$line", x, y + line * spacing, width - (line % 3) * (width / 5), thickness, ink, thickness)
    }
}

internal fun GuideFrameBuilder.tab(key: String, x: Int, y: Int, width: Int, active: Boolean) {
    box("$key-tab", x, y, width, 9, if (active) ACTION else SURFACE)
    box("$key-label", x + 5, y + 4, width - 10, 2, if (active) ACCENT else MUTED, 2)
}

internal fun GuideFrameBuilder.arrow(key: String, x: Int, y: Int = 38) {
    box("$key-line", x - 9, y - 1, 13, 2, MUTED)
    word(key, "›", x + 4, y - 1, 18, MUTED)
}

internal fun GuideFrameBuilder.check(key: String, x: Int, y: Int, accepted: Boolean) {
    word(key, if (accepted) "✓" else "×", x, y, 18, if (accepted) ACCENT else MUTED)
}

internal fun GuideFrameBuilder.signal(key: String, x: Int, y: Int, count: Int = 7, tall: Int = 19,
                                      gap: Int = 5, ink: Ink = ACCENT) {
    val pattern = listOf(4, 11, 7, 19, 12, 16, 6, 9, 4)
    repeat(count) { i ->
        val height = pattern[i % pattern.size] * tall / 19 + 2
        box("$key-$i", x + i * gap, y - height / 2, 2, height, ink, 2)
    }
}

internal fun GuideFrameBuilder.gauge(key: String, x: Int, y: Int, width: Int, level: Int, ink: Ink = ACCENT,
                                     knob: Boolean = true) {
    val filled = width * level / 100
    box("$key-track", x, y - 2, width, 4, BORDER, 2)
    if (filled > 0) box("$key-fill", x, y - 2, filled, 4, ink, 2)
    if (knob) box("$key-knob", x + filled - 4, y - 4, 8, 8, ink, 4)
}

internal fun GuideFrameBuilder.level(key: String, value: Int, y: Int = 40, label: String = "$value%") {
    word("$key-value", label, 66, y - 15, 17)
    gauge(key, 22, y + 8, 88, value)
}

internal fun GuideFrameBuilder.cover(key: String, x: Int, y: Int, size: Int = 29, fill: Ink = TEAL) {
    box("$key-cover", x, y, size, size, fill, 4)
    word("$key-note", "♪", x + size / 2, y + size / 2, size * 2 / 3, WHITE)
}

internal fun GuideFrameBuilder.player(
    key: String, x: Int, y: Int, w: Int, h: Int, art: Boolean = false, progress: Boolean = false,
    controls: Boolean = false, title: String = "Night Drive", subtitle: String? = null, playing: Boolean = true,
    textSize: Int = 11, ink: Ink = ACTION, fg: Ink = FG,
) {
    box("$key-card", x, y, w, h, ink, 5)
    val artSize = minOf(w / 3, h - 10, 38)
    if (art) cover("$key-album", x + 5, y + 5, artSize)
    val left = x + if (art) artSize + 9 else 6
    val width = w - (left - x) - 6
    word("$key-track", title, left + width / 2, y + h / 4 + 1, textSize, fg, width = width)
    subtitle?.let { word("$key-artist", it, left + width / 2, y + h / 2, textSize - 2, fg, MarkStyle.TEXT, width) }
    if (progress) gauge("$key-progress", left, y + h * 2 / 3, width, 43, fg, knob = false)
    if (controls) word("$key-transport", if (playing) "‹ Ⅱ ›" else "‹ ▸ ›", left + width / 2, y + h - 8,
        12, fg, width = width)
}

internal fun GuideFrameBuilder.route(
    key: String, x: Int, y: Int, w: Int, h: Int, distance: String? = null,
    current: String? = null, next: String? = null, ink: Ink = SURFACE, fg: Ink = ACCENT,
) {
    box("$key-card", x, y, w, h, ink, 5)
    val textX = x + w / 2
    val textY = y + if (current == null && next == null) h / 2 else h / 4
    word("$key-turn", "↱", if (distance == null) textX else textX - 18, textY, if (h >= 43) 25 else 18, fg)
    distance?.let { word("$key-distance", it, textX + 11, textY + 1, 11, fg, width = w / 2) }
    next?.let { word("$key-next", it, textX, y + h / 2, 11, fg, width = w - 8) }
    current?.let { word("$key-current", it, textX, y + h * 4 / 5, 9, fg, MarkStyle.TEXT, w - 8) }
}

internal fun GuideFrameBuilder.keycap(key: String, mark: String, x: Int, y: Int, active: Boolean = true) {
    box("$key-cap", x, y, 32, 30, if (active) ACTION else BORDER, 6)
    word(key, mark, x + 16, y + 15, 16, if (active) ACCENT else MUTED, width = 29)
}

internal fun GuideFrameBuilder.gesture(key: String, x: Int, y: Int, fingers: Int = 1) {
    repeat(fingers) { index ->
        box("$key-touch-$index", x + index * 10, y + index % 2 * 4, 8, 8, ACCENT, 4)
    }
}

internal fun GuideFrameBuilder.page(key: String, title: CockpitIcon, x: Int = 8, y: Int = 7, width: Int = 116,
                                    height: Int = 64, ink: Ink = SURFACE, fg: Ink = ACCENT, spacing: Int = 8,
                                    radius: Int = 5) {
    screen(key, x, y, width, height, ink, radius = radius)
    icon("$key-title", title, x + 8, y + 7, 21, fg)
    lines("$key-heading", x + minOf(35, width - 6), y + 16, (width - 43).coerceAtLeast(3), 1, ink = fg, thickness = 4)
    lines("$key-content", x + 12, y + 34, width - 24, 3, spacing, if (ink == NIGHT) WHITE else MUTED)
}

internal fun GuideFrameBuilder.connector(key: String, input: CockpitIcon, output: CockpitIcon, active: Boolean = true) {
    tile("$key-input", input, 13, 23, 30)
    arrow("$key-link", 65)
    tile("$key-output", output, 89, 23, 30, if (active) ACCENT else MUTED, if (active) ACTION else BORDER)
    if (!active) check("$key-blocked", 104, 60, false)
}

internal fun GuideFrameBuilder.calendar(key: String, selected: Set<Int>, x: Int = 15, y: Int = 17) {
    screen(key, x - 5, y - 7, 112, 57)
    for (day in 1..7) {
        val left = x + (day - 1) * 14
        if (day in selected) box("$key-highlight-$day", left, y + 13, 13, 17, ACTION, 3)
        word("$key-day-$day", day.toString(), left + 6, y + 21, 10, if (day in selected) ACCENT else MUTED)
    }
}

internal fun GuideFrameBuilder.timeline(key: String, times: List<String>, selected: Int, y: Int = 40) {
    val gap = 88 / (times.size - 1)
    box("$key-track", 22, y - 1, 88, 2, BORDER)
    times.forEachIndexed { index, value ->
        val x = 22 + index * gap
        box("$key-dot-$index", x - 4, y - 4, 8, 8, if (index == selected) ACCENT else MUTED, 4)
        word("$key-label-$index", value, x, y + 14, 11, if (index == selected) ACCENT else MUTED, width = 43)
    }
}

internal fun GuideFrameBuilder.toggle(key: String, on: Boolean, x: Int = 88, y: Int = 29) {
    box("$key-track", x, y, 29, 17, if (on) ACCENT else BORDER, 9)
    box("$key-thumb", x + if (on) 15 else 3, y + 3, 11, 11, if (on) SURFACE else MUTED, 6)
}

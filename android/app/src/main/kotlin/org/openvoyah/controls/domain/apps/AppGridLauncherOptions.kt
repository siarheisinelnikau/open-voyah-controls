package org.openvoyah.controls.domain.apps

internal enum class AppGridColumns(val storedValue: String, val requested: Int?) {
    AUTO("auto", null),
    FOUR("4", 4),
    FIVE("5", 5),
    SIX("6", 6);

    fun fit(availableWidth: Int, minimumTileWidth: Int, spacing: Int): Int {
        require(minimumTileWidth > 0 && spacing >= 0)
        val widthAndSpacing = availableWidth.coerceAtLeast(0).toLong() + spacing
        val tileAndSpacing = minimumTileWidth.toLong() + spacing
        val fitting = (widthAndSpacing / tileAndSpacing).toInt().coerceAtLeast(1)
        return minOf(requested ?: fitting, fitting)
    }

    companion object {
        fun parse(value: String): AppGridColumns =
            requireNotNull(entries.firstOrNull { it.storedValue == value }) { "Invalid app grid column count" }
    }
}

internal enum class AppGridShortcut(val storedValue: String) {
    QUICK_VOICE("quickVoice"),
    BUTTONS("buttons"),
    DISPLAY("display"),
    VEHICLE("vehicle"),
    AUTOMATION("automation"),
    VOICE_SETTINGS("voiceSettings"),
    UPDATES("updates"),
    INSTALL_APK("installApk");

    companion object {
        val defaults: Set<AppGridShortcut> = entries.toSet()

        fun parse(value: String): AppGridShortcut =
            requireNotNull(entries.firstOrNull { it.storedValue == value }) { "Invalid app grid shortcut" }
    }
}

internal enum class DesktopCardPlacement(val storedValue: String) {
    BEFORE_APPS("beforeApps"),
    AFTER_PINNED_APPS("afterPinnedApps");

    fun <T> appsBeforeCards(orderedApps: List<T>, isPinned: (T) -> Boolean): Int =
        if (this == BEFORE_APPS) 0
        else orderedApps.indexOfFirst { !isPinned(it) }.takeIf { it >= 0 } ?: orderedApps.size

    companion object {
        fun parse(value: String): DesktopCardPlacement =
            requireNotNull(entries.firstOrNull { it.storedValue == value }) { "Invalid desktop card placement" }
    }
}

internal enum class FavoritesBackBehavior(val storedValue: String) {
    SYSTEM("system"),
    MAIN_DESKTOP("mainDesktop");

    companion object {
        fun parse(value: String): FavoritesBackBehavior =
            requireNotNull(entries.firstOrNull { it.storedValue == value }) { "Invalid favorites Back behavior" }
    }
}

internal data class AppGridDesktopNames(val first: String = "", val second: String = "") {
    init {
        require(first == normalize(first) && second == normalize(second))
    }

    fun nameFor(desktop: AppGridDesktop): String? = when (desktop) {
        AppGridDesktop.MAIN -> null
        AppGridDesktop.FAVORITES_ONE -> first.ifEmpty { null }
        AppGridDesktop.FAVORITES_TWO -> second.ifEmpty { null }
    }

    companion object {
        const val MAX_LENGTH = 24

        fun validInput(value: String): Boolean = validDesktopNameInput(value, MAX_LENGTH)

        fun normalize(value: String): String {
            require(validInput(value)) { "Invalid favorite desktop name" }
            return value.trim()
        }
    }
}

internal fun validDesktopNameInput(value: String, maxLength: Int): Boolean {
    var index = 0
    var count = 0
    while (index < value.length) {
        val point = value.codePointAt(index)
        when (Character.getType(point)) {
            Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(),
            Character.PARAGRAPH_SEPARATOR.toInt(), Character.SURROGATE.toInt() -> return false
        }
        count++
        if (count > maxLength) return false
        index += Character.charCount(point)
    }
    return true
}

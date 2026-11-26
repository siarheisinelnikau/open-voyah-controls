package org.openvoyah.controls.domain.apps

import org.openvoyah.controls.domain.display.validMediaPackage

internal enum class DesktopInfoCard(val storedValue: String) {
    CLOCK("clock"),
    SERVICE("service"),
    MEDIA("media"),
    HEADLIGHTS("headlights"),
    PHONE("phone"),
    ROUTE("route"),
    POWER_HOLD("powerHold"),
    CAR_WASH("carWash"),
    RADIO("radio");

    companion object {
        fun fromStoredValue(value: String): DesktopInfoCard? = entries.firstOrNull { it.storedValue == value }
    }
}

internal enum class DesktopInfoSize(val storedValue: String, private val columns: Int) {
    COMPACT("compact", 1),
    WIDE("wide", 2);

    fun span(availableColumns: Int): Int = minOf(columns, availableColumns.coerceAtLeast(1))

    companion object {
        fun fromStoredValue(value: String): DesktopInfoSize? = entries.firstOrNull { it.storedValue == value }
    }
}

internal enum class DesktopMediaStyle(val storedValue: String) {
    TEXT("text"), COVER("cover");

    companion object {
        fun fromStoredValue(value: String): DesktopMediaStyle? = entries.firstOrNull { it.storedValue == value }
    }
}

internal enum class DesktopMediaOpenTarget(val storedValue: String) {
    NONE("none"), DISPLAY("display"), PLAYER("player");

    companion object {
        fun fromStoredValue(value: String): DesktopMediaOpenTarget? = entries.firstOrNull { it.storedValue == value }
    }
}

internal enum class DesktopClockFormat(val storedValue: String) {
    SYSTEM("system"), HOUR_24("24Hour"), HOUR_12("12Hour");

    companion object {
        fun fromStoredValue(value: String): DesktopClockFormat? = entries.firstOrNull { it.storedValue == value }
    }
}

internal enum class DesktopHeadlightContent(val storedValue: String) {
    SWITCH_POSITION("switch"), LAMP_ACTIVITY("lamps");

    companion object {
        fun fromStoredValue(value: String): DesktopHeadlightContent? = entries.firstOrNull { it.storedValue == value }
    }
}

internal enum class DesktopRouteContent(val storedValue: String, val showCurrentRoad: Boolean) {
    NEXT_TURN("nextTurn", false), CURRENT_AND_NEXT("currentAndNext", true);

    companion object {
        fun fromStoredValue(value: String): DesktopRouteContent? = entries.firstOrNull { it.storedValue == value }
    }
}

internal enum class DesktopRouteOpenTarget(val storedValue: String) {
    DISPLAY("display"), NAVIGATOR("navigator"), NONE("none");

    companion object {
        fun fromStoredValue(value: String): DesktopRouteOpenTarget? = entries.firstOrNull { it.storedValue == value }
    }
}

internal data class DesktopInfoTile(
    val card: DesktopInfoCard,
    val size: DesktopInfoSize,
    val mediaStyle: DesktopMediaStyle = DesktopMediaStyle.TEXT,
    val clockFormat: DesktopClockFormat = DesktopClockFormat.SYSTEM,
    val preferredMediaPackage: String = "",
    val phoneFavorites: List<String> = emptyList(),
    val headlightContent: DesktopHeadlightContent = DesktopHeadlightContent.SWITCH_POSITION,
    val mediaOpenTarget: DesktopMediaOpenTarget = DesktopMediaOpenTarget.NONE,
    val routeContent: DesktopRouteContent = DesktopRouteContent.NEXT_TURN,
    val routeOpenTarget: DesktopRouteOpenTarget = DesktopRouteOpenTarget.DISPLAY,
) {
    val key: String get() = "desktop-info:${card.storedValue}"
}

internal data class DesktopInfoConfiguration(
    private val desktops: Map<AppGridDesktop, Map<DesktopInfoCard, DesktopInfoSize>> = emptyMap(),
    private val mediaStyles: Map<AppGridDesktop, DesktopMediaStyle> = emptyMap(),
    private val clockFormats: Map<AppGridDesktop, DesktopClockFormat> = emptyMap(),
    private val preferredMediaPackages: Map<AppGridDesktop, String> = emptyMap(),
    private val phoneFavorites: Map<AppGridDesktop, List<String>> = emptyMap(),
    private val headlightContents: Map<AppGridDesktop, DesktopHeadlightContent> = emptyMap(),
    private val mediaOpenTargets: Map<AppGridDesktop, DesktopMediaOpenTarget> = emptyMap(),
    private val routeContents: Map<AppGridDesktop, DesktopRouteContent> = emptyMap(),
    private val routeOpenTargets: Map<AppGridDesktop, DesktopRouteOpenTarget> = emptyMap(),
) {
    fun sizeOf(desktop: AppGridDesktop, card: DesktopInfoCard): DesktopInfoSize? = desktops[desktop]?.get(card)
    fun mediaStyleOf(desktop: AppGridDesktop): DesktopMediaStyle = mediaStyles[desktop] ?: DesktopMediaStyle.TEXT
    fun clockFormatOf(desktop: AppGridDesktop): DesktopClockFormat = clockFormats[desktop] ?: DesktopClockFormat.SYSTEM
    fun mediaPackageOf(desktop: AppGridDesktop): String = preferredMediaPackages[desktop].orEmpty()
    fun mediaOpenTargetOf(desktop: AppGridDesktop): DesktopMediaOpenTarget =
        mediaOpenTargets[desktop] ?: DesktopMediaOpenTarget.NONE

    fun phoneFavoritesOf(desktop: AppGridDesktop): List<String> = phoneFavorites[desktop].orEmpty()
    fun headlightContentOf(desktop: AppGridDesktop): DesktopHeadlightContent =
        headlightContents[desktop] ?: DesktopHeadlightContent.SWITCH_POSITION

    fun routeContentOf(desktop: AppGridDesktop): DesktopRouteContent =
        routeContents[desktop] ?: DesktopRouteContent.NEXT_TURN

    fun routeOpenTargetOf(desktop: AppGridDesktop): DesktopRouteOpenTarget =
        routeOpenTargets[desktop] ?: DesktopRouteOpenTarget.DISPLAY

    fun tiles(desktop: AppGridDesktop): List<DesktopInfoTile> = DesktopInfoCard.entries.mapNotNull { card ->
        sizeOf(desktop, card)?.let { size ->
            when (card) {
                DesktopInfoCard.MEDIA -> DesktopInfoTile(card, size, mediaStyle = mediaStyleOf(desktop),
                    preferredMediaPackage = mediaPackageOf(desktop), mediaOpenTarget = mediaOpenTargetOf(desktop))
                DesktopInfoCard.CLOCK -> DesktopInfoTile(card, size, clockFormat = clockFormatOf(desktop))
                DesktopInfoCard.HEADLIGHTS -> DesktopInfoTile(card, size, headlightContent = headlightContentOf(desktop))
                DesktopInfoCard.PHONE -> DesktopInfoTile(card, size, phoneFavorites = phoneFavoritesOf(desktop))
                DesktopInfoCard.ROUTE -> DesktopInfoTile(card, size, routeContent = routeContentOf(desktop),
                    routeOpenTarget = routeOpenTargetOf(desktop))
                else -> DesktopInfoTile(card, size)
            }
        }
    }

    fun set(desktop: AppGridDesktop, card: DesktopInfoCard, size: DesktopInfoSize?): DesktopInfoConfiguration {
        if (sizeOf(desktop, card) == size) return this
        val current = desktops[desktop].orEmpty()
        val updated = if (size == null) current - card else current + (card to size)
        return copy(desktops = if (updated.isEmpty()) desktops - desktop else desktops + (desktop to updated))
    }

    fun withMediaStyle(desktop: AppGridDesktop, style: DesktopMediaStyle): DesktopInfoConfiguration =
        copy(mediaStyles = if (style == DesktopMediaStyle.TEXT) mediaStyles - desktop else mediaStyles + (desktop to style))

    fun withMediaOpenTarget(desktop: AppGridDesktop, target: DesktopMediaOpenTarget): DesktopInfoConfiguration =
        copy(mediaOpenTargets = if (target == DesktopMediaOpenTarget.NONE) mediaOpenTargets - desktop
            else mediaOpenTargets + (desktop to target))

    fun withClockFormat(desktop: AppGridDesktop, format: DesktopClockFormat): DesktopInfoConfiguration =
        copy(clockFormats = if (format == DesktopClockFormat.SYSTEM) clockFormats - desktop else clockFormats + (desktop to format))

    fun withHeadlightContent(desktop: AppGridDesktop, content: DesktopHeadlightContent): DesktopInfoConfiguration =
        copy(headlightContents = if (content == DesktopHeadlightContent.SWITCH_POSITION) headlightContents - desktop
            else headlightContents + (desktop to content))

    fun withRouteContent(desktop: AppGridDesktop, content: DesktopRouteContent): DesktopInfoConfiguration =
        copy(routeContents = if (content == DesktopRouteContent.NEXT_TURN) routeContents - desktop
            else routeContents + (desktop to content))

    fun withRouteOpenTarget(desktop: AppGridDesktop, target: DesktopRouteOpenTarget): DesktopInfoConfiguration =
        copy(routeOpenTargets = if (target == DesktopRouteOpenTarget.DISPLAY) routeOpenTargets - desktop
            else routeOpenTargets + (desktop to target))

    fun withMediaPackage(desktop: AppGridDesktop, packageName: String): DesktopInfoConfiguration {
        require(validMediaPackage(packageName))
        return copy(preferredMediaPackages = if (packageName.isEmpty()) preferredMediaPackages - desktop
            else preferredMediaPackages + (desktop to packageName))
    }

    fun withPhoneFavorites(desktop: AppGridDesktop, ids: List<String>): DesktopInfoConfiguration {
        require(ids.size <= MAX_DESKTOP_PHONE_FAVORITES && ids.distinct().size == ids.size && ids.all(::validPhoneFavoriteId))
        return copy(phoneFavorites = if (ids.isEmpty()) phoneFavorites - desktop else phoneFavorites + (desktop to ids.toList()))
    }

    fun clearTiles(): DesktopInfoConfiguration = copy(desktops = emptyMap())
}

internal const val MAX_DESKTOP_PHONE_FAVORITES = 3

internal fun validPhoneFavoriteId(id: String): Boolean = id.length in 1..128 && id.none(Char::isISOControl)

internal enum class PhoneFavoriteEdit {
    ADD, REMOVE, MOVE_UP, MOVE_DOWN;

    fun applyTo(ids: List<String>, id: String): List<String> = when (this) {
        ADD -> if (ids.size < MAX_DESKTOP_PHONE_FAVORITES && id !in ids) ids + id else ids
        REMOVE -> ids - id
        MOVE_UP, MOVE_DOWN -> {
            val index = ids.indexOf(id)
            val destination = index + if (this == MOVE_UP) -1 else 1
            if (index !in ids.indices || destination !in ids.indices) ids
            else ids.toMutableList().apply { add(destination, removeAt(index)) }
        }
    }
}

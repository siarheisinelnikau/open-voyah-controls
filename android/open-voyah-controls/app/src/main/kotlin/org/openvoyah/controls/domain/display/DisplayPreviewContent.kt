package org.openvoyah.controls.domain.display

import org.openvoyah.controls.domain.settings.AppRoleTextSize

internal data class DisplayPreviewContent(
    val showMedia: Boolean = true,
    val showNavigation: Boolean = true,
    val showPlaybackProgress: Boolean = false,
    val showAlbumArtwork: Boolean = false,
    val showTransportControls: Boolean = false,
    val preferredMediaPackage: String = "",
    val arrangement: DisplayPreviewArrangement = DisplayPreviewArrangement.AUTO,
    val paneOrder: DisplayPreviewPaneOrder = DisplayPreviewPaneOrder.MEDIA_FIRST,
    val seekStep: PreviewSeekStep = PreviewSeekStep.TEN,
    val mediaSubtitle: DisplayPreviewMediaSubtitle = DisplayPreviewMediaSubtitle.ARTIST,
    val showNextRoad: Boolean = true,
    val showTurnDistance: Boolean = true,
    val hideEmptyPanes: Boolean = false,
    val palette: DisplayPreviewPalette = DisplayPreviewPalette.COCKPIT,
    val paneBalance: DisplayPreviewPaneBalance = DisplayPreviewPaneBalance.EQUAL,
    val distanceUnits: DisplayPreviewDistanceUnits = DisplayPreviewDistanceUnits.METRIC,
    val roadNames: DisplayPreviewRoadNames = DisplayPreviewRoadNames.ORIGINAL,
    val showCurrentRoad: Boolean = true,
    val textSize: AppRoleTextSize = AppRoleTextSize.FOLLOW,
    val mediaFallback: PreviewMediaFallback = PreviewMediaFallback.AUTOMATIC,
)

internal enum class PreviewMediaFallback(val storedValue: String) {
    AUTOMATIC("automatic"), PREFERRED_ONLY("preferredOnly");

    fun permits(preferredPackage: String, sourcePackage: String?): Boolean =
        this == AUTOMATIC || preferredPackage.isEmpty() || preferredPackage == sourcePackage

    companion object {
        fun find(value: String?): PreviewMediaFallback? = entries.firstOrNull { it.storedValue == value }
    }
}

internal enum class DisplayPreviewArrangement(val storedValue: String) {
    AUTO("auto"), STACKED("stacked"), SIDE_BY_SIDE("sideBySide");

    companion object {
        fun find(value: String?): DisplayPreviewArrangement? = entries.firstOrNull { it.storedValue == value }
    }
}

internal enum class DisplayPreviewPaneOrder(val storedValue: String) {
    MEDIA_FIRST("mediaFirst"), ROUTE_FIRST("routeFirst");

    companion object {
        fun find(value: String?): DisplayPreviewPaneOrder? = entries.firstOrNull { it.storedValue == value }
    }
}

internal enum class PreviewSeekStep(val seconds: Int) {
    FIVE(5), TEN(10), THIRTY(30);

    companion object {
        fun find(seconds: Int?): PreviewSeekStep? = entries.firstOrNull { it.seconds == seconds }
    }
}

internal enum class DisplayPreviewMediaSubtitle(val storedValue: String) {
    ARTIST("artist"), ALBUM("album"), NONE("none");

    companion object {
        fun find(value: String?): DisplayPreviewMediaSubtitle? = entries.firstOrNull { it.storedValue == value }
    }
}

internal enum class DisplayPreviewPalette(val storedValue: String) {
    COCKPIT("cockpit"), DAYLIGHT("daylight");

    companion object {
        fun find(value: String?): DisplayPreviewPalette? = entries.firstOrNull { it.storedValue == value }
    }
}

internal enum class DisplayPreviewPaneBalance(val storedValue: String) {
    EQUAL("equal"), MEDIA("media"), NAVIGATION("navigation");

    companion object {
        fun find(value: String?): DisplayPreviewPaneBalance? = entries.firstOrNull { it.storedValue == value }
    }
}

internal enum class DisplayPreviewDistanceUnits(val storedValue: String) {
    METRIC("metric"), IMPERIAL("imperial");

    companion object {
        fun find(value: String?): DisplayPreviewDistanceUnits? = entries.firstOrNull { it.storedValue == value }
    }
}

internal enum class DisplayPreviewRoadNames(val storedValue: String) {
    ORIGINAL("original"), LATIN("latin");

    companion object {
        fun find(value: String?): DisplayPreviewRoadNames? = entries.firstOrNull { it.storedValue == value }
    }
}

private val applicationId = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)*")

internal fun validMediaPackage(value: String): Boolean =
    value.isEmpty() || (value.length <= 255 && applicationId.matches(value))

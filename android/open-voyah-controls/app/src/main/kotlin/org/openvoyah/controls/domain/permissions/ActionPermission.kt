package org.openvoyah.controls.domain.permissions

/**
 * What the owner allows for one vehicle or root action. Every action starts as [ALLOWED]; only Settings →
 * Allowed actions narrows it. A review row for the exact firmware is optional metadata that marks an action as
 * verified on this head unit; it no longer gates anything.
 */
enum class ActionPermission {
    /** Runs normally. The default for every action. */
    ALLOWED,

    /**
     * Still runs, but is marked "unverified" in the list, every use is written to the journal, and an action that
     * asks for confirmation (multimedia restart, system reboot) says so in its confirmation.
     */
    UNVERIFIED,

    /** Refused before anything reaches the vehicle or the root channel. */
    FORBIDDEN;

    val runs: Boolean get() = this != FORBIDDEN

    companion object {
        fun parse(value: String?): ActionPermission? = entries.firstOrNull { it.name == value }
    }
}

/** The kinds of actions the owner can allow, mark or forbid, in the order Settings shows them. */
enum class ActionKind(val prefix: String) {
    /** A root operation started from the app: restarts, window service, OEM profiles, cluster writes, CAN keys. */
    OPERATION("operation"),

    /** A Frida agent channel that is not covered by an operation: the cluster navigation card and translations. */
    AGENT("agent"),

    /** A vehicle voice command; also used by routines, seat memory, seasonal climate and automation. */
    VOICE("voice"),
}

/** One entry in Settings → Allowed actions. [key] is what the preference store and the settings archive keep. */
data class ActionItem(val kind: ActionKind, val id: String) {
    init { require(ActionCatalog.validId(kind, id)) { "Invalid action id" } }
    val key: String get() = "${kind.prefix}.$id"
}

/** The fixed operations and agent channels, plus the rules that decide which voice commands are vehicle commands. */
object ActionCatalog {
    const val WINDOW_SERVICE = "window-service"
    const val PASSENGER_WINDOW_DPI = "passenger-window-dpi"
    const val WINDOW_DISPLAY_MOVE = "window-display-move"
    const val OEM_PROFILES = "oem-profiles"
    const val TBOX_REBOOT = "tbox-reboot"
    const val CLUSTER_WRITE = "cluster-write"
    /** Settings → Factory apps language: places, enables and removes text overlays in /vendor/overlay. */
    const val TEXT_OVERLAYS = "text-overlays"
    const val CAN_STEERING = "can-steering-terminal"
    const val CAN_STEERING_EDGES = "can-steering-edges"
    const val MEDIA_RESTART = "media-restart"
    const val SYSTEM_REBOOT = "system-reboot"

    const val NAVIGATION = "navigation"
    // Retain old archive entries for the retired worker without listing it or attaching any runtime behavior.
    private const val LEGACY_APOLLO = "apollo"
    /** The instrument cluster's factory media card: Voyah Music and Voyah Radio among its sources. */
    const val CLUSTER_CARD = "cluster-card"
    /** Agent channel ids for the translated OEM apps; the suffix is the key of ReviewedFirmware.localizationTargets. */
    const val LOCALE_PREFIX = "locale-"

    val operations: List<String> = listOf(
        WINDOW_SERVICE, PASSENGER_WINDOW_DPI, WINDOW_DISPLAY_MOVE, CAN_STEERING, CAN_STEERING_EDGES,
        OEM_PROFILES, CLUSTER_WRITE, TEXT_OVERLAYS, TBOX_REBOOT, MEDIA_RESTART, SYSTEM_REBOOT,
    )
    val agents: List<String> = listOf(NAVIGATION, CLUSTER_CARD) +
        listOf("setting", "vehicle", "bluetooth", "hiboard").map { LOCALE_PREFIX + it }

    /**
     * Voice catalog commands that run on the phone screen or in apps rather than in the vehicle. Everything else in
     * the catalog (lights, climate, seats, windows, modes, accounts, T-Box) is a vehicle command.
     */
    private val local = setOf(
        "ControlsSettings", "SystemSettings", "Assistant", "Split", "Music", "Navigator", "Radio", "Camera",
        "RestartMusic", "RestartNavigator", "Like", "RemoveLike", "DisLike", "MediaPlay", "MediaPause", "MediaNext",
        "MediaPrevious", "VolumeUp", "VolumeDown", "VolumeMute", "SetVolume", "Weather", "WeatherTomorrow",
    )
    private val commandId = Regex("[A-Za-z][A-Za-z0-9]{0,63}")

    fun isVehicleCommand(id: String): Boolean = commandId.matches(id) && id !in local && !id.startsWith("Call_")

    fun validId(kind: ActionKind, id: String): Boolean = when (kind) {
        ActionKind.OPERATION -> id in operations
        ActionKind.AGENT -> id in agents || id == LEGACY_APOLLO
        ActionKind.VOICE -> isVehicleCommand(id)
    }

    fun parseKey(key: String): ActionItem? {
        val kind = ActionKind.entries.firstOrNull { key.startsWith(it.prefix + ".") } ?: return null
        val id = key.substring(kind.prefix.length + 1)
        return if (validId(kind, id)) ActionItem(kind, id) else null
    }

    fun operation(id: String) = ActionItem(ActionKind.OPERATION, id)
    fun agent(id: String) = ActionItem(ActionKind.AGENT, id)
    fun voice(id: String) = ActionItem(ActionKind.VOICE, id)

    /**
     * The two CAN key protocols are alternatives. The one marked verified on this firmware wins; with neither or both
     * verified the terminal protocol (the OEM's own short and long events) is used, and a forbidden one is skipped.
     * Null keeps the factory key delivery.
     */
    fun canSteeringSource(terminal: ActionPermission, edges: ActionPermission, verified: Set<String>): String? {
        val edgesFirst = CAN_STEERING_EDGES in verified && CAN_STEERING !in verified
        val order = if (edgesFirst) listOf(CAN_STEERING_EDGES to edges, CAN_STEERING to terminal)
            else listOf(CAN_STEERING to terminal, CAN_STEERING_EDGES to edges)
        return order.firstOrNull { it.second.runs }?.first
    }
}

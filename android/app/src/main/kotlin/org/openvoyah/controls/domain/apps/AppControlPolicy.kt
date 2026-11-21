package org.openvoyah.controls.domain.apps

/** What Android reports about one installed package that decides whether Controls may close it or change its access. */
internal data class InstalledPackage(
    val packageName: String,
    val uid: Int,
    /** Part of the firmware image, including a firmware app updated from a store. */
    val preinstalled: Boolean,
    val persistent: Boolean,
    val sharedUser: Boolean,
)

/** The packages that currently hold a role the car UI depends on, as Android reports them. */
internal data class PackageRoles(
    val controls: String,
    val home: String? = null,
    val inputMethod: String? = null,
    val deviceAdmins: Set<String> = emptySet(),
    /** Packages with an enabled accessibility service or notification listener. */
    val boundServices: Set<String> = emptySet(),
)

/** Why Controls leaves an app alone instead of closing it or changing its permissions. */
internal enum class AppControlRefusal {
    /** Controls itself: stopping it ends the page, and its own permissions have their own screens. */
    CONTROLS,
    /** Part of the head unit firmware. Its services back the launcher, the cluster and the vehicle apps. */
    PREINSTALLED,
    /** The current launcher, keyboard or a device administrator. */
    CAR_ROLE,
    /** Runs under a platform or shared identity, or Android keeps it running. */
    SYSTEM_IDENTITY,
    /**
     * Only for closing: an enabled accessibility service or notification listener. On Android 9 to 11 a force stop
     * turns such an accessibility service off for good, and this firmware has no settings screen to turn it back on.
     */
    BOUND_SERVICE,
}

/**
 * One rule for both app actions behind a long press: Controls closes apps and changes their permissions only for
 * apps installed on top of the firmware. Everything the car UI depends on stays with Android and the firmware.
 */
internal object AppControlPolicy {
    /** Android's first application UID within a user; lower IDs belong to the platform. */
    const val FIRST_APPLICATION_UID = 10_000
    private const val PER_USER_RANGE = 100_000

    /** Why Controls leaves the app's permissions alone, or null when it manages them. */
    fun refusal(app: InstalledPackage, roles: PackageRoles): AppControlRefusal? = when {
        app.packageName == roles.controls -> AppControlRefusal.CONTROLS
        // The maker's apps count as firmware even where a head unit places them in /data.
        app.preinstalled || HeadUnitMaker.owns(app.packageName) -> AppControlRefusal.PREINSTALLED
        app.packageName == roles.home || app.packageName == roles.inputMethod || app.packageName in roles.deviceAdmins ->
            AppControlRefusal.CAR_ROLE
        app.persistent || app.sharedUser || app.uid % PER_USER_RANGE < FIRST_APPLICATION_UID -> AppControlRefusal.SYSTEM_IDENTITY
        else -> null
    }

    /** Why Controls does not close the app, or null when it may; closing is refused wherever permissions are. */
    fun stopRefusal(app: InstalledPackage, roles: PackageRoles): AppControlRefusal? =
        refusal(app, roles) ?: AppControlRefusal.BOUND_SERVICE.takeIf { app.packageName in roles.boundServices }
}

/** An AppOps mode as Android reports it; [shell] is the name `appops set` takes. */
internal enum class AppOpMode(val shell: String) {
    ALLOWED("allow"), IGNORED("ignore"), ERRORED("deny"), DEFAULT("default"), FOREGROUND("foreground");

    /** Android's rule for an app-op permission: an explicit mode wins and the default falls back to the grant. */
    fun allows(permissionGranted: Boolean): Boolean = when (this) {
        ALLOWED, FOREGROUND -> true
        DEFAULT -> permissionGranted
        IGNORED, ERRORED -> false
    }
}

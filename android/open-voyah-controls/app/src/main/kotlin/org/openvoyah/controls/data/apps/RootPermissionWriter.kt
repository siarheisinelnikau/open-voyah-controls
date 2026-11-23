package org.openvoyah.controls.data.apps

import android.content.Context
import org.openvoyah.controls.BuildConfig
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.frida.FridaTransport
import org.openvoyah.controls.data.frida.ProductionRootAccess
import org.openvoyah.controls.domain.apps.AppOpMode

/** Narrow use of the existing local root shell. Android still enforces the permission type. */
internal class RootPermissionWriter(
    private val execute: (String) -> String,
) {
    constructor(context: Context) : this(appRootShell(context))

    fun set(user: Int, packageName: String, permission: String, granted: Boolean) {
        execute(RootAppCommands.permission(user, packageName, permission, granted))
    }

    /** Changes an app-op permission such as "display over other apps", which `pm grant` cannot change. */
    fun setAppOp(user: Int, packageName: String, uid: Int, operation: String, mode: AppOpMode) {
        execute(RootAppCommands.appOp(user, packageName, uid, operation, mode))
    }

    /**
     * Reads app-op modes of another app. Android refuses that read to an ordinary app on Android 9 and 11, so it goes
     * through the root shell like the write it confirms.
     */
    fun readAppOps(user: Int, packageName: String, operations: List<String>): Map<String, AppOpMode> =
        RootAppCommands.appOpModes(execute(RootAppCommands.readAppOps(user, packageName, operations)), operations)
}

/** Closes another app through the same local root shell. */
internal class RootAppStopper(
    private val execute: (String) -> String,
) {
    constructor(context: Context) : this(appRootShell(context))

    /** True once no process of the package is left, false while one still runs, null for an unexpected answer. */
    fun stop(user: Int, packageName: String): Boolean? = RootAppCommands.stopped(execute(RootAppCommands.forceStop(user, packageName)))
}

/** Uninstalls another app through the same local root shell; the caller confirms the result with Android. */
internal class RootAppRemover(
    private val execute: (String) -> String,
) {
    constructor(context: Context) : this(appRootShell(context))

    fun uninstall(packageName: String) {
        execute(RootAppCommands.uninstall(packageName))
    }
}

/**
 * The root shell for managing other apps: the loopback fixture on an explicitly selected emulator build, otherwise
 * the authorized channel behind the activation guard, so a paused update or a blocked recovery refuses the command.
 * Writers that share one shell also share its verified firmware evidence.
 */
internal fun appRootShell(context: Context): (String) -> String =
    if (BuildConfig.OVC_EMULATOR_FIXTURE && FridaTransport.canUseFixture(context)) {
        FridaTransport.emulatorFixture(context, 23, { UiText.get(R.string.ui_80) },
            { UiText.get(R.string.apps_permission_unavailable) })::execute
    } else ProductionRootAccess(context)::executeActive

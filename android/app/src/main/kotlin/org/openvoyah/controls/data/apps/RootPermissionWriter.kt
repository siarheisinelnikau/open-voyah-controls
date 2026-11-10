package org.openvoyah.controls.data.apps

import android.content.Context
import org.openvoyah.controls.BuildConfig
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.frida.FridaTransport
import org.openvoyah.controls.data.frida.ProductionRootAccess

/** Narrow use of the existing local root shell. Android still enforces the permission type. */
internal class RootPermissionWriter(
    private val execute: (String) -> String,
) {
    constructor(context: Context) : this(
        if (BuildConfig.OVC_EMULATOR_FIXTURE && FridaTransport.canUseFixture(context)) {
            FridaTransport.emulatorFixture(context, 23, { UiText.get(R.string.ui_80) },
                { UiText.get(R.string.apps_permission_unavailable) })::execute
        } else ProductionRootAccess(context)::executeActive,
    )

    fun set(user: Int, packageName: String, permission: String, granted: Boolean) {
        require(user >= 0 && qualifiedName.matches(packageName) && qualifiedName.matches(permission))
        val verb = if (granted) "grant" else "revoke"
        execute("test \"\$(id -u)\" = 0 && pm $verb --user $user $packageName $permission")
    }

    private companion object {
        val qualifiedName = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    }
}

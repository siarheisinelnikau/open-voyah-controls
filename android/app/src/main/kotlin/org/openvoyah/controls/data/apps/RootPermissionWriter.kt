package org.openvoyah.controls.data.apps

import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.frida.FridaTransport

/** Narrow use of the existing local root shell. Android still enforces the permission type. */
internal class RootPermissionWriter(
    private val execute: (String) -> String = FridaTransport(
        23, { UiText.get(R.string.ui_80) }, { UiText.get(R.string.apps_permission_unavailable) },
    )::execute,
) {
    fun set(user: Int, packageName: String, permission: String, granted: Boolean) {
        require(user >= 0 && qualifiedName.matches(packageName) && qualifiedName.matches(permission))
        val verb = if (granted) "grant" else "revoke"
        execute("test \"\$(id -u)\" = 0 && pm $verb --user $user $packageName $permission")
    }

    private companion object {
        val qualifiedName = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    }
}

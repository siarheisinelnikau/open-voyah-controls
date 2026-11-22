package org.openvoyah.controls.platform.apps

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import org.json.JSONObject
import org.openvoyah.controls.data.apps.AppIconPreferences

/**
 * The launcher panel's share of the per-app icon choices. Controls renders the final picture of every panel app
 * the driver changed, and the launcher agent draws it as it is: the agent runs in the launcher process, which
 * cannot read Controls' files or decode a choice itself.
 */
internal object PanelIcons {
    /** Settings.Global key of the document `{"schema":1,"icons":{package: base64 PNG}}`. */
    const val KEY = "org.openvoyah.controls.panelIcons"
    /** Twice the 56 px a panel icon takes at 160 dpi, so the launcher only ever scales down. */
    private const val PIXELS = 112

    /** The document for the panel apps among [packages]; apps the driver did not change are left out. */
    @JvmStatic
    fun document(context: Context, packages: Collection<String>): String {
        val choices = AppIconPreferences(context).all()
        val icons = JSONObject()
        for (packageName in packages.toSortedSet()) {
            val choice = choices[packageName] ?: continue
            val bitmap = try {
                styledIcon(context.packageManager.getApplicationIcon(packageName), choice, PIXELS)
            } catch (_: Exception) {
                null
            } ?: continue
            try { icons.put(packageName, Base64.encodeToString(AppIconImages.png(bitmap), Base64.NO_WRAP)) }
            finally { bitmap.recycle() }
        }
        return JSONObject().put("schema", 1).put("icons", icons).toString()
    }

    /** Names the document in the panel configuration, so the agent redraws when, and only when, it changes. */
    @JvmStatic
    fun version(document: String): String = MessageDigest.getInstance("SHA-256")
        .digest(document.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
}

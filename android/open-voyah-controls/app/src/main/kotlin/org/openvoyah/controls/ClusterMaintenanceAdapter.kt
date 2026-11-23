package org.openvoyah.controls

import android.content.Context
import android.net.Uri
import java.io.IOException

/** Keeps the existing package-private cluster transaction and document adapters at their boundary. */
internal class ClusterMaintenanceAdapter(context: Context) {
    private val app = context.applicationContext
    private val controller = ClusterController(app)
    private val preferences = app.getSharedPreferences("cluster", Context.MODE_PRIVATE)

    fun status(): String? = preferences.getString("status", null)
    fun persistStatus(message: String) { preferences.edit().putString("status", message).commit() }

    fun inspect(): String = controller.inspect()
    fun prepare(): String = controller.prepare()
    fun apply(): String = controller.apply()
    fun restore(): String = controller.restore()

    fun importPackage(uri: Uri): String = synchronized(ClusterController.LOCK) {
        val input = app.contentResolver.openInputStream(uri) ?: throw IOException(UiText.get(R.string.ui_123))
        input.use { UiText.get(R.string.cluster_imported) + "\n" + ClusterPackage.importZip(app, it).firmware() }
    }

    fun exportBackup(uri: Uri): String {
        val output = app.contentResolver.openOutputStream(uri, "wt") ?: throw IOException(UiText.get(R.string.ui_123))
        output.use(controller::exportBackup)
        return UiText.get(R.string.cluster_exported)
    }
}

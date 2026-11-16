package org.openvoyah.controls.platform.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

/** Shared request history keeps both voice entry points aware of a permanent denial. */
internal class MicrophonePermission(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("voice_permissions", Context.MODE_PRIVATE)

    fun granted(): Boolean = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    fun requested(): Boolean = preferences.getBoolean("microphone_requested", false)
    fun recordRequest() = preferences.edit().putBoolean("microphone_requested", true).apply()

    fun settingsIntent(): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", app.packageName, null))
}

package org.openvoyah.controls.data.updates

import android.content.Context

/** Update preferences stay separate from vehicle assignments and credential stores. */
internal class UpdatePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("updates", Context.MODE_PRIVATE)

    fun checkOnStartup(): Boolean = preferences.getBoolean("check_on_startup", true)

    fun checkOnStartup(enabled: Boolean) {
        check(preferences.edit().putBoolean("check_on_startup", enabled).commit())
    }
}

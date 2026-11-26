package org.openvoyah.controls.data.updates

import android.content.Context
import org.openvoyah.controls.BuildConfig
import java.io.File

/** Shared finite catalog check for the home notice and the deliberately opened Updates screen. */
object AppUpdates {
    private var shared: OfficialUpdateRepository? = null

    @JvmStatic
    fun startup(context: Context) {
        repository(context).startup(UpdatePreferences(context).checkOnStartup())
    }

    @Synchronized
    internal fun repository(context: Context): OfficialUpdateRepository {
        shared?.let { return it }
        return OfficialUpdateRepository(BuildConfig.VERSION_NAME,
            File(context.applicationContext.cacheDir, "official-updates")).also {
            shared = it
        }
    }

    @JvmStatic
    fun availableVersion(context: Context): String? = repository(context).checks.value.latest?.version?.toString()
}

package org.openvoyah.controls.presentation.home

import android.content.Context
import android.content.pm.PackageManager
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import org.openvoyah.controls.BuildConfig

/** The build identity shown at the end of Settings; [installedAt] is when this APK was installed or updated. */
internal data class AppVersionInfo(
    val name: String,
    val code: Long,
    val build: String,
    val commit: String,
    val installedAt: Long?,
) {
    /** One line per fact, so a pasted bug report reads the same as the screen. */
    fun lines(buildLabel: String, commitLabel: String, installedLabel: String, locale: Locale): List<String> = buildList {
        add("Open Voyah Controls $name ($code)")
        add("$buildLabel: $build" + if (commit.isEmpty()) "" else " · $commitLabel $commit")
        installedAt?.let {
            add("$installedLabel: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(it)))
        }
    }

    companion object {
        /** debug and release are Android's own build types; the car build and the emulator fixture are named apart. */
        fun buildType(debug: Boolean, buildType: String, localVehicle: Boolean, emulatorFixture: Boolean): String = when {
            debug && localVehicle -> "local-vehicle"
            debug && emulatorFixture -> "$buildType · emulator"
            else -> buildType
        }

        fun current(context: Context): AppVersionInfo {
            val installed = try {
                context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.takeIf { it > 0 }
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
            return AppVersionInfo(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong(),
                buildType(BuildConfig.DEBUG, BuildConfig.BUILD_TYPE, BuildConfig.LOCAL_VEHICLE, BuildConfig.OVC_EMULATOR_FIXTURE),
                BuildConfig.GIT_COMMIT, installed)
        }
    }
}

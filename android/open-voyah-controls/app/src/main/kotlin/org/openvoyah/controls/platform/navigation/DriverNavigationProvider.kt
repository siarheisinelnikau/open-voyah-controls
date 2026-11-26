package org.openvoyah.controls.platform.navigation

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.core.display.DisplayEventBus.Navigation
import org.openvoyah.controls.data.frida.FridaBridge
import org.openvoyah.controls.data.frida.ReviewedFirmware
import org.openvoyah.controls.data.settings.ActionPermissions
import org.openvoyah.controls.data.settings.SettingsStore
import org.openvoyah.controls.domain.permissions.ActionCatalog
import java.text.NumberFormat
import java.util.Locale

/** Read-only, in-memory handoff from the protected navigation receiver to the OEM media card process. */
class DriverNavigationProvider : ContentProvider() {
    private val cardSigner by lazy {
        val app = requireNotNull(context)
        runCatching {
            val packages = ReviewedFirmware.load(app).packages
            packages.firstOrNull { it.name == ReviewedFirmware.INSTRUMENT_CARD }?.signer
                ?.takeIf { packages.any { pin -> pin.name == ReviewedFirmware.CLUSTER } }
        }.getOrNull()
    }
    private val permissions by lazy { ActionPermissions(requireNotNull(context)) }

    override fun onCreate() = true

    @Suppress("DEPRECATION")
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        // ContentProvider.call does not enforce URI read permissions. Android verifies callingPackage against the UID.
        if (method != "route" || Build.VERSION.SDK_INT != 30 || callingPackage != ReviewedFirmware.INSTRUMENT_CARD) return null
        val app = requireNotNull(context)
        val current = sample ?: return null
        val route = current.route ?: return null
        if (arg != current.token || !fresh(current.at) || !FridaBridge.wasConfigured(app) ||
            FridaBridge.pendingUpdate(app) != null || !FridaBridge.navigationHookEnabled(app) ||
            !permissions.agent(ActionCatalog.NAVIGATION).runs || !permissions.agent(ActionCatalog.CLUSTER_CARD).runs ||
            !SettingsStore(app).load().driverNavigationCard) return null
        val signer = cardSigner ?: return null
        val card = app.packageManager.getPackageInfo(ReviewedFirmware.INSTRUMENT_CARD, PackageManager.GET_SIGNING_CERTIFICATES)
        if ((card.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM == 0) return null
        val certificate = card.signingInfo?.takeUnless { it.hasMultipleSigners() }
            ?.signingCertificateHistory?.lastOrNull()?.toByteArray() ?: return null
        if (ReviewedFirmware.digest(certificate) != signer || !fresh(current.at) || current !== sample) return null

        val distance = formatDistance(route.meters())
        val hasNext = route.nextRoad().isNotEmpty() || distance.isNotEmpty()
        return Bundle().apply {
            putLong("at", current.at)
            putString("heading", UiText.get(if (hasNext) R.string.driver_navigation_next else R.string.driver_navigation_current))
            putString("title", route.nextRoad().ifEmpty { distance.ifEmpty { route.road() } })
            putString("detail", if (route.nextRoad().isNotEmpty()) distance else "")
        }
    }

    private fun formatDistance(meters: Int): String {
        if (meters < 0) return ""
        val format = NumberFormat.getNumberInstance(Locale.forLanguageTag(UiText.language()))
        if (meters < 1000) return UiText.get(R.string.driver_navigation_meters).format(format.format(meters))
        format.maximumFractionDigits = 1
        return UiText.get(R.string.driver_navigation_kilometers).format(format.format(meters / 1000.0))
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        private const val MAX_AGE_MS = 5_000L
        private val tokenPattern = Regex("[0-9a-f]{32}")
        private data class Sample(val token: String, val at: Long, val route: Navigation?)
        @Volatile private var sample: Sample? = null

        private fun fresh(at: Long) = at >= 0 && SystemClock.elapsedRealtime() - at in 0 until MAX_AGE_MS

        /** Called only by the manifest receiver, on the main thread. Launch labels carry no driver observation. */
        @JvmStatic
        fun observe(intent: Intent, navigation: Navigation) {
            val token = intent.getStringExtra("driverToken")?.takeIf(tokenPattern::matches) ?: return
            val at = intent.getLongExtra("driverObservedAt", -1)
            if (!fresh(at) || at < (sample?.at ?: -1)) return
            val usable = intent.getStringExtra("event") == "route" && intent.getIntExtra("driverGuiding", 0) == 1 &&
                (navigation.nextRoad().isNotEmpty() || navigation.road().isNotEmpty() || navigation.meters() >= 0)
            sample = Sample(token, at, navigation.takeIf { usable })
        }
    }
}

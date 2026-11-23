package org.openvoyah.controls.platform.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.session.MediaSessionManager
import android.service.media.MediaBrowserService
import org.openvoyah.controls.MediaAccessService
import org.openvoyah.controls.domain.display.validMediaPackage
import org.openvoyah.controls.platform.DisplayEventText

internal data class PreviewMediaSource(val packageName: String, val label: String)

/** List of already installed players visible to this user or exposed by an active session. */
internal class PreviewMediaSources(context: Context) {
    private val app = context.applicationContext
    private val packages = app.packageManager

    @Suppress("DEPRECATION")
    fun list(preferred: String): List<PreviewMediaSource> {
        val active = try {
            val component = ComponentName(app, MediaAccessService::class.java)
            app.getSystemService(MediaSessionManager::class.java)?.getActiveSessions(component)
                .orEmpty().map { it.packageName }
        } catch (_: SecurityException) { emptyList() }
        val browserServices = packages.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE), 0)
            .mapNotNull { it.serviceInfo?.takeIf { service -> service.enabled && service.exported }?.packageName }
        val audioPlayers = packages.queryIntentActivities(Intent(Intent.ACTION_VIEW).setType("audio/*"), 0)
            .mapNotNull { it.activityInfo?.takeIf { activity -> activity.enabled && activity.exported }?.packageName }
        val installedPreferred = preferred.takeIf { it.isNotEmpty() && installedLabel(it) != null }
        return (active + browserServices + audioPlayers + listOfNotNull(installedPreferred)).distinct()
            .filter { it.isNotEmpty() && validMediaPackage(it) }
            .map { PreviewMediaSource(it, label(it)) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    fun label(packageName: String): String = installedLabel(packageName) ?: packageName

    @Suppress("DEPRECATION")
    private fun installedLabel(packageName: String): String? = try {
        val entry = packages.getApplicationInfo(packageName, 0)
        DisplayEventText.sanitize(entry.loadLabel(packages)).ifBlank { packageName }
    } catch (_: PackageManager.NameNotFoundException) { null }
}

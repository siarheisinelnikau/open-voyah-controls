package org.openvoyah.controls.platform.navigation

import android.app.Notification
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import org.openvoyah.controls.data.settings.SettingsStore

/** Collects only the automotive guidance notification, under the existing Android notification access. */
class YandexDriverGuidance(context: Context) {
    private val app = context.applicationContext
    private val settings = SettingsStore(app)

    fun posted(notification: StatusBarNotification) {
        if (notification.packageName != SettingsStore.YANDEX_NAVIGATOR) return
        if (!settings.load().driverNavigationCard) {
            clear()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val route = runCatching { decode(notification, now) }.getOrNull()
        if (route != null) latest = route else removed(notification)
    }

    fun removed(notification: StatusBarNotification) {
        if (notification.packageName == SettingsStore.YANDEX_NAVIGATOR && latest?.key == notification.key) clear()
    }

    fun refresh(active: Array<StatusBarNotification>?) {
        clear()
        active?.asSequence()?.filter { it.packageName == SettingsStore.YANDEX_NAVIGATOR }
            ?.sortedBy { it.postTime }?.forEach(::posted)
    }

    fun clear() { latest = null }

    private fun decode(record: StatusBarNotification, now: Long): Sample? {
        val notification = record.notification
        if (!record.isOngoing || notification.category != Notification.CATEGORY_NAVIGATION ||
            notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val age = System.currentTimeMillis() - record.postTime
        if (age !in 0 until MAX_AGE_MS || age > now) return null

        val remoteViews = listOfNotNull(notification.contentView, notification.bigContentView)
        for (remote in remoteViews) {
            if (remote.getPackage() != SettingsStore.YANDEX_NAVIGATOR) continue
            val resources = app.packageManager.getResourcesForApplication(SettingsStore.YANDEX_NAVIGATOR)
            if (resourceName(resources, remote.layoutId) !in layouts) continue
            val view = remote.apply(app, null)
            val title = text(view, resources, "titleView")
            val description = text(view, resources, "descriptionView")
            val distance = parseDistance(title) ?: parseDistance(description) ?: continue
            val road = if (parseDistance(title) != null) description else title
            val arrow = maneuver(view, resources) ?: continue
            return Sample(record.key, now - age, road, distance, arrow)
        }
        return null
    }

    private fun text(view: View, resources: Resources, field: String): String =
        find(view, resources, field)?.let { it as? TextView }?.text?.toString()?.take(160)?.trim().orEmpty()

    private fun maneuver(view: View, resources: Resources): Bitmap? {
        for (name in arrows) {
            val arrow = find(view, resources, name) as? ImageView ?: continue
            val drawable = arrow.drawable ?: continue
            val copy = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, copy.width, copy.height)
            drawable.draw(Canvas(copy))
            return copy
        }
        return null
    }

    private fun find(view: View, resources: Resources, field: String): View? {
        val id = resources.getIdentifier(field, "id", SettingsStore.YANDEX_NAVIGATOR)
        return if (id == 0) null else view.findViewById<View>(id)?.takeIf { it.visibility == View.VISIBLE }
    }

    private fun resourceName(resources: Resources, id: Int): String = resources.getResourceEntryName(id)

    internal data class Sample(val key: String, val at: Long, val road: String, val distance: String, val arrow: Bitmap)

    companion object {
        private const val MAX_AGE_MS = 30_000L
        private val layouts = setOf(
            "navi_layout_bg_guidance_notification", "navi_layout_bg_guidance_notification_pre_android_12",
            "navi_layout_bg_guidance_notification_large", "navi_layout_bg_guidance_notification_large_pre_android_12",
            "redesign_bg_guidance_notification", "redesign_bg_guidance_notification_flat",
            "redesign_bg_guidance_notification_flat_pre_android_12",
        )
        private val arrows = listOf("nextManeuverView", "nextManeuverViewTinted", "primaryIcon", "primaryIconTinted")
        private val distance = Regex(
            """^\p{Nd}+(?:[ ,.]\p{Nd}+)*\s*(?:м|км|m|km|ft|mi|yd)$""",
            RegexOption.IGNORE_CASE,
        )
        @Volatile private var latest: Sample? = null

        internal fun current(now: Long): Sample? = latest?.takeIf { now - it.at in 0 until MAX_AGE_MS }

        internal fun parseDistance(value: String): String? {
            val oneLine = value.replace('\u00a0', ' ').replace('\u202f', ' ')
                .replace(Regex("[\\n\\r\\t]+"), " ").trim()
            return oneLine.takeIf { it.length <= 24 && distance.matches(it) }
        }
    }
}

package org.openvoyah.controls

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.test.AndroidTestCase
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.openvoyah.controls.data.apps.DesktopWallpaperPreferences
import org.openvoyah.controls.data.settings.LocalSettingsArchive
import org.openvoyah.controls.domain.apps.AppGridDesktop
import org.openvoyah.controls.platform.apps.DesktopWallpaperImages

class DesktopWallpaperPreferencesTest : AndroidTestCase() {
    fun testPhotoIsDownsampledAndIndependentForEachDesktop() {
        val wallpapers = DesktopWallpaperPreferences(context)
        val previous = wallpapers.exportSettings()
        try {
            wallpapers.set(AppGridDesktop.MAIN, null)
            wallpapers.set(AppGridDesktop.FAVORITES_ONE, wallpaperFixture())
            val image = runBlocking { wallpapers.observeImage(AppGridDesktop.FAVORITES_ONE).first() }
            try {
                assertNotNull(image)
                assertEquals(960, image!!.width)
                assertEquals(360, image.height)
            } finally {
                image?.recycle()
            }
            assertNull(runBlocking { wallpapers.observeImage(AppGridDesktop.MAIN).first() })
            wallpapers.set(AppGridDesktop.FAVORITES_ONE, null)
            assertNull(runBlocking { wallpapers.observeImage(AppGridDesktop.FAVORITES_ONE).first() })
        } finally {
            wallpapers.restoreSettings(previous)
        }
    }

    fun testArchiveRestoresWallpaperAndOmittedSectionKeepsCustomImages() {
        val wallpapers = DesktopWallpaperPreferences(context)
        val archive = LocalSettingsArchive(context)
        val previous = archive.prepare(archive.exportJson())
        try {
            wallpapers.set(AppGridDesktop.FAVORITES_TWO, wallpaperFixture())
            val expected = wallpapers.exportSettings()
            val saved = archive.exportJson()
            wallpapers.set(AppGridDesktop.FAVORITES_TWO, null)
            archive.apply(archive.prepare(saved))
            assertEquals(expected.toString(), wallpapers.exportSettings().toString())

            val earlier = JSONObject(saved).apply { remove("desktopWallpapers") }
            archive.apply(archive.prepare(earlier.toString()))
            assertEquals(expected.toString(), wallpapers.exportSettings().toString())
            wallpapers.restoreSettings(JSONObject().put(AppGridDesktop.MAIN.storedValue, JSONObject.NULL))
            assertEquals(expected.getString("favorites2"), wallpapers.exportSettings().getString("favorites2"))
        } finally {
            archive.apply(previous)
        }
    }

    fun testArchiveRejectsUnknownDesktopsAndInvalidPhotoBytes() {
        val badImages = listOf(
            JSONObject().put("other", JSONObject.NULL),
            JSONObject().put("main", true),
            JSONObject().put("main", Base64.encodeToString(byteArrayOf(1, 2, 3), Base64.NO_WRAP)),
            JSONObject().put("main", "a".repeat(170_000)),
        )
        for (image in badImages) {
            assertTrue(runCatching { DesktopWallpaperPreferences.validateSettings(image) }.isFailure)
        }
    }
}

internal fun wallpaperFixture(): ByteArray {
    val width = 1440
    val height = 540
    val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val paint = Paint().apply {
        shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(Color.rgb(33, 75, 127), Color.rgb(122, 60, 126), Color.rgb(222, 102, 43)),
            null, Shader.TileMode.CLAMP)
    }
    try {
        Canvas(source).drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        val data = ByteArrayOutputStream().apply { check(source.compress(Bitmap.CompressFormat.PNG, 100, this)) }
        return DesktopWallpaperImages.normalize(data.toByteArray())
    } finally {
        source.recycle()
    }
}

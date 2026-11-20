package org.openvoyah.controls.presentation.theme

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import java.io.File
import kotlin.math.abs
import org.openvoyah.controls.BuildConfig
import org.openvoyah.controls.LaunchBrandActivity
import org.openvoyah.controls.MainActivity
import org.openvoyah.controls.R
import org.openvoyah.controls.data.settings.AppearancePreferences
import org.openvoyah.controls.domain.settings.AppAccent
import org.openvoyah.controls.domain.settings.AppFontFamily
import org.openvoyah.controls.domain.settings.AppTextSize
import org.openvoyah.controls.domain.settings.LauncherEntry
import org.openvoyah.controls.presentation.navigation.ControlsNavigationViewModel
import org.openvoyah.controls.presentation.navigation.ControlsRoute

class LaunchBrandTest : InstrumentationTestCase() {
    private val app get() = instrumentation.targetContext

    fun testInstalledLauncherIconContainsBothWings() {
        val launcher = checkNotNull(app.packageManager.getLaunchIntentForPackage(app.packageName))
        val icon = checkNotNull(launcher.resolveActivityInfo(app.packageManager, 0)).loadIcon(app.packageManager)
        assertTrue("The launcher must resolve the adaptive icon from the installed APK", icon is AdaptiveIconDrawable)
        for (size in listOf(48, 72, 192)) {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            try {
                icon.setBounds(0, 0, size, size)
                icon.draw(Canvas(bitmap))
                fun scaled(from: Int, until: Int) = size * from / 192 until size * until / 192
                val left = brightPixels(bitmap, scaled(12, 55), scaled(28, 100), threshold = 180)
                val right = brightPixels(bitmap, scaled(137, 180), scaled(28, 100), threshold = 180)
                val center = brightPixels(bitmap, scaled(90, 102), scaled(60, 150))
                val file = if (size == 192) "launcher-icon-native.png" else "launcher-icon-${size}px.png"
                save(bitmap, File(app.filesDir, file))
                val visiblePixels = size * size / 400
                assertTrue("Both wings and the center must remain visible at $size px ($left, $right, $center)",
                    left > visiblePixels && right > visiblePixels && center > visiblePixels)
            } finally {
                bitmap.recycle()
            }
        }
    }

    fun testLauncherPlaysEveryStageThenClosesOverExistingControls() = withLauncherSettings { appearance ->
        appearance.setAnimateRoutes(true)
        withActivity(MainActivity.settingsIntent(app), MainActivity::class.java) { controls ->
            await("Direct settings page") { hasTag(controls, "quickActionsContent") }
            assertFalse(hasTag(controls, LAUNCH_BRAND_TAG))
            withSlowMotion {
                withActivity(bootBrandIntent(), LaunchBrandActivity::class.java) { brand ->
                    await("Brand on the boot entry") { hasTag(brand, LAUNCH_BRAND_TAG) }
                    assertFalse("The brand must have its own task", onMain { brand.taskId == controls.taskId })
                    captureStage(brand, "launch-brand-background.png", ::hasPlainBackground)
                    captureStage(brand, "launch-brand-wings.png", ::hasOnlyWings)
                    captureColorStage(brand, "launch-brand-charge.png", StageColors::isBlueCharge)
                    captureColorStage(brand, "launch-brand.png", StageColors::isSteel)
                    await("the launcher animation to close") { onMain { brand.isFinishing } }
                }
                await("existing settings to regain focus") {
                    onMain { controls.lifecycle.currentState == Lifecycle.State.RESUMED && controls.hasWindowFocus() }
                }
                assertTrue(onMain {
                    ViewModelProvider(controls)[ControlsNavigationViewModel::class.java].state.value.route == ControlsRoute.SETTINGS
                })
            }
        }
    }

    fun testChargeFollowsTheAccentChosenInAppearance() = withLauncherSettings { appearance ->
        appearance.setAnimateRoutes(true)
        appearance.setAccent(AppAccent.AMBER)
        withSlowMotion {
            withActivity(bootBrandIntent(), LaunchBrandActivity::class.java) { brand ->
                await("Brand on the boot entry") { hasTag(brand, LAUNCH_BRAND_TAG) }
                captureColorStage(brand, "launch-brand-charge-amber.png", StageColors::isAmberCharge)
                captureColorStage(brand, "launch-brand-amber.png", StageColors::isSteel)
            }
        }
    }

    fun testDirectAndReducedMotionEntriesSkipBrand() = withLauncherSettings { appearance ->
        appearance.setAnimateRoutes(true)
        appearance.setLauncherEntry(LauncherEntry.APP_GRID)
        val launcher = checkNotNull(app.packageManager.getLaunchIntentForPackage(app.packageName))
        withActivity(launcher, MainActivity::class.java) { activity ->
            assertTrue(onMain {
                ViewModelProvider(activity)[ControlsNavigationViewModel::class.java].state.value.route == ControlsRoute.SETTINGS
            })
            assertFalse(hasTag(activity, LAUNCH_BRAND_TAG))
            appearance.setAnimateRoutes(false)
            withActivity(bootBrandIntent(), LaunchBrandActivity::class.java) { brand ->
                await("reduced-motion launcher to close") { onMain { brand.isFinishing } }
                assertFalse(hasTag(brand, LAUNCH_BRAND_TAG))
            }
        }
    }

    private fun bootBrandIntent() = Intent(app, LaunchBrandActivity::class.java)
        .setAction(LaunchBrandActivity.SHOW_BOOT_BRAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun <T : Activity> withActivity(intent: Intent, type: Class<T>, checkActivity: (T) -> Unit) {
        // Observe the new Activity before startActivitySync would wait for its entry transition.
        val monitor = instrumentation.addMonitor(type.name, null, false)
        var launched: T? = null
        try {
            app.startActivity(intent)
            val activity = checkNotNull(type.cast(monitor.waitForActivityWithTimeout(5_000))) { "No ${type.simpleName} launched" }
            launched = activity
            checkActivity(activity)
        } finally {
            instrumentation.removeMonitor(monitor)
            launched?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
            instrumentation.waitForIdleSync()
        }
    }

    private fun withLauncherSettings(test: (AppearancePreferences) -> Unit) {
        check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
        val updates = app.getSharedPreferences("updates", 0)
        val startupCheck = updates.all["check_on_startup"] as? Boolean
        val appearance = AppearancePreferences(app)
        val original = appearance.load()
        try {
            check(updates.edit().putBoolean("check_on_startup", false).commit())
            // The intro takes its accent, contrast and type from Appearance; pin them so pixel checks are stable.
            appearance.setAccent(AppAccent.DEFAULT)
            appearance.setHighContrast(false)
            appearance.setTextSize(AppTextSize.STANDARD)
            appearance.setFontFamily(AppFontFamily.SYSTEM)
            appearance.setBoldText(false)
            test(appearance)
        } finally {
            appearance.setAnimateRoutes(original.animateRoutes)
            appearance.setLauncherEntry(original.launcherEntry)
            appearance.setAccent(original.accent)
            appearance.setHighContrast(original.highContrast)
            appearance.setTextSize(original.textSize)
            appearance.setFontFamily(original.fontFamily)
            appearance.setBoldText(original.boldText)
            check(updates.edit().apply {
                if (startupCheck == null) remove("check_on_startup") else putBoolean("check_on_startup", startupCheck)
            }.commit())
        }
    }

    private fun withSlowMotion(test: () -> Unit) {
        val key = "animator_duration_scale"
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
        val original = shell("settings get global $key")
        check(original == "null" || original.matches(Regex("[0-9]+(?:[.][0-9]+)?")))
        try {
            shell("settings put global $key 2.5")
            check(shell("settings get global $key") == "2.5")
            SystemClock.sleep(100)
            test()
        } finally {
            if (original == "null") shell("settings delete global $key") else shell("settings put global $key $original")
            check(shell("settings get global $key") == original)
        }
    }

    private fun hasTag(activity: Activity, tag: String): Boolean = onMain {
        findRoot(activity.window.decorView)?.semanticsOwner?.rootSemanticsNode?.containsTag(tag) == true
    }

    private fun onMain(check: () -> Boolean): Boolean {
        var result = false
        instrumentation.runOnMainSync { result = check() }
        return result
    }

    private fun captureStage(brand: Activity, filename: String, matches: (Bitmap) -> Boolean) {
        await(filename) {
            if (!hasTag(brand, LAUNCH_BRAND_TAG)) return@await false
            val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return@await false
            try {
                val reachedStage = matches(bitmap)
                if (reachedStage) save(bitmap, File(app.filesDir, "ui-audit/$filename"))
                reachedStage
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun hasPlainBackground(bitmap: Bitmap): Boolean {
        val background = app.getColor(R.color.cockpit_background)
        val xs = pixelRange(bitmap.width, 30 until 70).step(maxOf(bitmap.width / 96, 1))
        val ys = pixelRange(bitmap.height, 19 until 81).step(maxOf(bitmap.height / 72, 1))
        for (y in ys) for (x in xs) {
            val pixel = bitmap.getPixel(x, y)
            if (abs(Color.red(pixel) - Color.red(background)) > 3 ||
                abs(Color.green(pixel) - Color.green(background)) > 3 ||
                abs(Color.blue(pixel) - Color.blue(background)) > 3) return false
        }
        return true
    }

    // The regions below follow the centered block on the 1920 x 720, 240 dpi reference display.
    private fun hasOnlyWings(bitmap: Bitmap): Boolean {
        val wingRows = pixelRange(bitmap.height, WING_ROWS)
        val left = brightPixels(bitmap, pixelRange(bitmap.width, 40 until 46), wingRows, threshold = 130)
        val right = brightPixels(bitmap, pixelRange(bitmap.width, 54 until 60), wingRows, threshold = 130)
        val spine = brightPixels(bitmap, pixelRange(bitmap.width, 49 until 51), pixelRange(bitmap.height, SPINE_ROWS),
            threshold = 110)
        val title = brightPixels(bitmap, pixelRange(bitmap.width, TEXT_COLUMNS), pixelRange(bitmap.height, TITLE_ROWS),
            threshold = 110)
        return left > 40 && right > 40 && spine == 0 && title == 0
    }

    private fun captureColorStage(brand: Activity, filename: String, reached: (StageColors, StageColors) -> Boolean) =
        captureStage(brand, filename) { bitmap ->
            reached(colorsIn(bitmap, 46 until 54, BODY_ROWS), colorsIn(bitmap, TEXT_COLUMNS, TITLE_ROWS))
        }

    /**
     * Pixel counts of the emblem body; each check also receives the counts of the title. The resting mark is steel
     * and a charge takes the accent chosen in Appearance.
     */
    private data class StageColors(val blue: Int, val amber: Int, val red: Int, val steel: Int) {
        fun isBlueCharge(title: StageColors) =
            blue > 1_500 && blue > steel * 3 && title.blue > 300 && red == 0 && title.red == 0

        fun isAmberCharge(title: StageColors) = amber > 1_500 && amber > steel * 3 && title.amber > 300 && blue < 40

        fun isSteel(title: StageColors) = steel > 500 && title.steel > 300 && red == 0 && amber < 40 && title.blue < 40
    }

    private fun colorsIn(bitmap: Bitmap, horizontal: IntRange, vertical: IntRange): StageColors {
        val xs = pixelRange(bitmap.width, horizontal)
        val ys = pixelRange(bitmap.height, vertical)
        var accentBlue = 0
        var accentAmber = 0
        var crimson = 0
        var steel = 0
        for (y in ys) for (x in xs) {
            val color = bitmap.getPixel(x, y)
            val red = Color.red(color)
            val green = Color.green(color)
            val blue = Color.blue(color)
            if (blue > 110 && blue * 10 > red * 14 && blue * 10 > green * 12) accentBlue++
            if (red > 150 && red * 10 > blue * 16 && green * 10 > blue * 13) accentAmber++
            // The intro used to glow crimson; no stage may bring that color back.
            if (red > 80 && red * 3 > green * 5 && red * 3 > blue * 4) crimson++
            if (red > 140 && green > 140 && blue > 140 && blue - red in -45..45) steel++
        }
        return StageColors(accentBlue, accentAmber, crimson, steel)
    }

    private fun pixelRange(size: Int, percents: IntRange): IntRange =
        size * percents.first / 100 until size * (percents.last + 1) / 100

    private fun findRoot(view: View): ViewRootForTest? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findRoot(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun SemanticsNode.containsTag(tag: String): Boolean =
        config.getOrNull(SemanticsProperties.TestTag) == tag || children.any { it.containsTag(tag) }

    private fun await(description: String, condition: () -> Boolean) {
        val stop = SystemClock.uptimeMillis() + 5_000L
        do {
            if (condition()) return
            SystemClock.sleep(24)
        } while (SystemClock.uptimeMillis() < stop)
        fail("Timed out waiting for $description")
    }

    private fun brightPixels(bitmap: Bitmap, xs: IntRange, ys: IntRange, threshold: Int = 460): Int = ys.sumOf { y ->
        xs.count { x ->
            val pixel = bitmap.getPixel(x, y)
            Color.alpha(pixel) > 200 && Color.red(pixel) + Color.green(pixel) + Color.blue(pixel) > threshold
        }
    }

    private companion object {
        val WING_ROWS = 22 until 37
        val BODY_ROWS = 33 until 55
        val SPINE_ROWS = 46 until 56
        val TITLE_ROWS = 61 until 68
        val TEXT_COLUMNS = 34 until 66
    }

    private fun save(bitmap: Bitmap, file: File) {
        val directory = checkNotNull(file.parentFile)
        check(directory.isDirectory || directory.mkdirs())
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}

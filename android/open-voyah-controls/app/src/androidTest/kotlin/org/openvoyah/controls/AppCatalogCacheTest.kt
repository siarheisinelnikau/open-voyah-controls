package org.openvoyah.controls

import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.test.InstrumentationTestCase
import org.openvoyah.controls.platform.apps.AppCatalogCache

/** The app grid's catalog is loaded when the process starts and follows package changes without the grid open. */
class AppCatalogCacheTest : InstrumentationTestCase() {
    private val app get() = instrumentation.targetContext

    override fun setUp() {
        super.setUp()
        check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
    }

    fun testTheProcessLoadsTheCatalogBeforeAnyGridOpens() {
        // ControlsApplication starts the cache; this process never opened the grid.
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (AppCatalogCache.snapshot.value == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        val apps = checkNotNull(AppCatalogCache.snapshot.value) { "The catalog was not preloaded" }
        val launchable = InstalledApps.list(app).map { it.component().flattenToString() }.filterNot { it.startsWith(app.packageName) }
        assertTrue("The preloaded catalog misses launcher apps", apps.map { it.id }.containsAll(launchable.filter { id ->
            !id.startsWith("com.android.settings/")
        }))
        assertTrue("Preloaded apps carry their icons", apps.filter { it.launchEntry != null }.all { it.icon != null })
    }

    fun testAPackageChangeReloadsTheCatalog() {
        val target = InstalledApps.list(app).first { it.component().packageName == "com.google.android.calendar" ||
            it.component().packageName == "com.android.calendar" }
        val component = target.component().flattenToString()
        val packageName = target.component().packageName
        awaitCatalog { ids -> component in ids }
        try {
            command("pm disable-user --user 0 $packageName")
            awaitCatalog { ids -> component !in ids }
        } finally {
            command("pm enable --user 0 $packageName")
        }
        awaitCatalog { ids -> component in ids }
    }

    private fun awaitCatalog(condition: (Set<String>) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            AppCatalogCache.snapshot.value?.map { it.id }?.toSet()?.let { if (condition(it)) return }
            SystemClock.sleep(100)
        }
        fail("The preloaded catalog did not follow the package change")
    }

    private fun command(line: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(line)).use {
            it.readBytes().decodeToString()
        }
}

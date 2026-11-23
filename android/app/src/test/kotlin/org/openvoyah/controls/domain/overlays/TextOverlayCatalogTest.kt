package org.openvoyah.controls.domain.overlays

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TextOverlayCatalogTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)
    private val fingerprint = "qti/msmnile_gvmq/msmnile_gvmq:11/RQ3A.211001.001/97:user/jenkins-PRJ_H97c_Platform_8155_0603-97,test-keys"
    private val job = "0123456789abcdef0123456789abcdef"
    private val setting = "org.openvoyah.controls.overlay.ru.setting"
    private val phone = "org.openvoyah.controls.overlay.ru.bluetoothphone"
    private val table = "pin $fingerprint 1720866928\n" +
        "$setting.apk\t$setting\tcom.qinggan.app.setting\t$a\t$c\t1775\n" +
        "$phone.apk\t$phone\tcom.qinggan.bluetoothphone\t$b\t$c\t114\n"
    private val set = TextOverlayCatalog.parse("ru", table)
    /** The real device script, as the app bundles it. */
    private val script = listOf(File("../tools/text-overlays.sh"), File("tools/text-overlays.sh")).first { it.isFile }.readText()

    private fun status(vararg lines: String) = TextOverlayCatalog.parseStatus(
        (listOf("OVC_OVERLAY_DIR_WRITABLE=no", "OVC_VENDOR_OVERLAY_FILE framework-res__auto_generated_rro_vendor.apk sha256=$c owner=0:0:644") +
            lines).joinToString("\n"))

    private fun file(pkg: String, sha: String) = "OVC_VENDOR_OVERLAY_FILE $pkg.apk sha256=$sha owner=0:0:644"

    @Test fun bootJobRunsForBundledSetsAndForOverlaysPlacedFromThePc() {
        val vendor = listOf("framework-res__auto_generated_rro_vendor.apk")
        assertFalse(TextOverlayCatalog.bootJobNeeded(listOf("text-overlays.sh"), vendor))
        assertTrue(TextOverlayCatalog.bootJobNeeded(listOf("ru", "text-overlays.sh"), vendor))
        // An owner build without bundled overlays still turns on a set tools/text-overlays.ps1 placed.
        assertTrue(TextOverlayCatalog.bootJobNeeded(emptyList(), vendor + "$setting.apk"))
        // A temporary file of an interrupted change is not a set.
        assertFalse(TextOverlayCatalog.bootJobNeeded(emptyList(), vendor + ".$setting.apk.tmp"))
        // A folder that cannot be listed is not taken as empty.
        assertTrue(TextOverlayCatalog.bootJobNeeded(emptyList(), null))
    }

    @Test fun readsTheBundledTableAndItsPin() {
        assertEquals(listOf(setting, phone), set.overlays.map { it.pkg })
        assertEquals("com.qinggan.bluetoothphone", set.byPackage.getValue(phone).target)
        assertTrue(set.builtFor(fingerprint, "1720866928"))
        assertFalse(set.builtFor(fingerprint, "1"))
        assertFalse(set.builtFor("other", "1720866928"))
        // An unpinned set is never taken for a head unit.
        assertFalse(TextOverlayCatalog.parse("ru", table.replace("$fingerprint 1720866928", "- -")).builtFor("-", "-"))
    }

    @Test fun rejectsAnyMalformedTable() {
        for (broken in listOf(
            table.substringAfter('\n'), // no pin
            table.replace(a, "x"), table + table.substringAfter('\n'), table.replace("$setting.apk\t", "other.apk\t"),
            table.replace("\t1775", "\tmany"), table.replace("\t1775", "\t0"), table.replace("1720866928", "today"),
        )) {
            assertThrows(IllegalArgumentException::class.java) { TextOverlayCatalog.parse("ru", broken) }
        }
        assertThrows(IllegalArgumentException::class.java) { TextOverlayCatalog.parse("kk", table) }
    }

    @Test fun readsTheDeviceStateAndIgnoresOtherVendors() {
        val device = status(file(setting, a), file(phone, b), "OVC_OVERLAY $setting state=STATE_ENABLED enabled=true",
            "OVC_OVERLAY $phone state= enabled=", "OVC_OVERLAY_ROLLBACK_AVAILABLE", "OVC_OVERLAY_INSTALLED_THIS_BOOT")
        assertEquals(listOf(DeviceOverlay(setting, a, "STATE_ENABLED", true), DeviceOverlay(phone, b, "", false)), device.overlays)
        assertFalse(device.writable)
        assertTrue(device.rollbackAvailable)
        assertTrue(device.installedThisBoot)
        assertEquals("ru", device.language)
    }

    @Test fun summaryFollowsTheStartsAfterAnInstall() {
        val bundled = mapOf("ru" to set)
        assertEquals(OverlayPhase.NONE, TextOverlayCatalog.summarize(status(), bundled, emptySet()).phase)
        val placed = status(file(setting, a), "OVC_OVERLAY $setting state= enabled=", "OVC_OVERLAY_INSTALLED_THIS_BOOT")
        assertEquals(OverlayPhase.NEEDS_RESTART, TextOverlayCatalog.summarize(placed, bundled, emptySet()).phase)
        // Still unknown after a start: Android skipped them (the comma-pinned set of 2026-11-22 did exactly that).
        val skipped = status(file(setting, a), "OVC_OVERLAY $setting state= enabled=")
        assertEquals(OverlayPhase.IGNORED, TextOverlayCatalog.summarize(skipped, bundled, emptySet()).phase)
        // An update keeps the package names: Android still knows the old overlays until the next start.
        val replaced = status(file(setting, a), "OVC_OVERLAY $setting state=STATE_DISABLED enabled=false", "OVC_OVERLAY_INSTALLED_THIS_BOOT")
        assertEquals(OverlayPhase.NEEDS_RESTART, TextOverlayCatalog.summarize(replaced, bundled, emptySet()).phase)
        assertEquals(emptyList<String>(), TextOverlayCatalog.wanted(replaced, set, emptySet()))
        val known = status(file(setting, a), "OVC_OVERLAY $setting state=STATE_DISABLED enabled=false")
        assertEquals(OverlayPhase.NEEDS_ENABLE, TextOverlayCatalog.summarize(known, bundled, emptySet()).phase)
        assertEquals(listOf(setting), TextOverlayCatalog.wanted(known, set, emptySet()))
        // An app the owner turned off is not "missing".
        val off = TextOverlayCatalog.summarize(known, bundled, setOf("com.qinggan.app.setting"))
        assertEquals(OverlayPhase.ACTIVE, off.phase)
        assertEquals(0, off.active)
    }

    @Test fun countsOnlyOverlaysAndroidApplies() {
        val device = status(file(setting, a), file(phone, b), "OVC_OVERLAY $setting state=STATE_ENABLED enabled=true",
            "OVC_OVERLAY $phone state=STATE_MISSING_TARGET enabled=true")
        val summary = TextOverlayCatalog.summarize(device, mapOf("ru" to set), emptySet())
        assertEquals(1, summary.active)
        assertEquals(2, summary.total)
    }

    @Test fun anUpdatedBundleIsOfferedButASkippedAppIsNot() {
        val bundled = mapOf("ru" to set)
        val one = status(file(setting, a), "OVC_OVERLAY $setting state=STATE_ENABLED enabled=true")
        val current = TextOverlayCatalog.summarize(one, bundled, emptySet())
        assertEquals(OverlayPhase.ACTIVE, current.phase)
        assertFalse(current.updateAvailable) // the phone overlay was left out at install, on purpose
        val stale = TextOverlayCatalog.summarize(status(file(setting, c), "OVC_OVERLAY $setting state=STATE_ENABLED enabled=true"),
            bundled, emptySet())
        assertTrue(stale.updateAvailable)
        assertEquals(listOf(setting), TextOverlayCatalog.unwanted(one, set, setOf("com.qinggan.app.setting")))
    }

    @Test fun aMixedSetHasNoLanguage() {
        val mixed = status(file(setting, a), file("org.openvoyah.controls.overlay.kk.setting", b))
        assertNull(mixed.language)
        assertNull(TextOverlayCatalog.summarize(mixed, mapOf("ru" to set), emptySet()).language)
    }

    @Test fun statusRunsTheScriptWithItsExitStatus() {
        val command = TextOverlayCatalog.status("echo hi\r\n", fingerprint)
        assertTrue(command.startsWith("sh -s -- status '$fingerprint' - 2>&1 <<'OVC_TEXT_OVERLAYS'\necho hi\nOVC_TEXT_OVERLAYS\n"))
        assertTrue(command.endsWith("echo \"OVC_TEXT_OVERLAYS_EXIT=\$?\"\n"))
        assertThrows(IllegalArgumentException::class.java) { TextOverlayCatalog.status("x\nOVC_TEXT_OVERLAYS\ny", "f") }
    }

    @Test fun changesRunAsDetachedJobsWithCheckedArguments() {
        val start = TextOverlayCatalog.start("echo s", "disable", fingerprint, job, listOf(phone))
        assertTrue(start.contains("cat > ${TextOverlayCatalog.STATE}/text-overlays.sh <<'OVC_TEXT_OVERLAYS'\necho s\nOVC_TEXT_OVERLAYS\n"))
        assertTrue(start.contains("nohup sh -c '"))
        assertTrue(start.contains("<<'OVC_TEXT_OVERLAYS_ARGS'\n$phone\nOVC_TEXT_OVERLAYS_ARGS\n"))
        assertTrue(start.contains(" ovc disable '$fingerprint' - </dev/null >/dev/null 2>&1 &\n"))
        assertTrue(start.contains("Another overlay change is still running."))
        assertThrows(IllegalArgumentException::class.java) { TextOverlayCatalog.start("s", "status", "f", job) }
        assertThrows(IllegalArgumentException::class.java) { TextOverlayCatalog.start("s", "reboot", "f", job) }
        assertThrows(IllegalArgumentException::class.java) { TextOverlayCatalog.start("s", "enable", "f", job, listOf("a;b")) }
        assertThrows(IllegalArgumentException::class.java) { TextOverlayCatalog.start("s", "remove", "f", job, listOf(phone)) }
        assertThrows(IllegalArgumentException::class.java) { TextOverlayCatalog.start("s", "remove", "f", "x;y") }
        assertThrows(IllegalArgumentException::class.java) { TextOverlayCatalog.poll("x;y") }
        assertTrue(start.contains("echo \"$job \$?\" > ${TextOverlayCatalog.STATE}/job.status"))
    }

    @Test fun outcomeSeparatesTheStatusFromTheAnswer() {
        assertEquals(0 to "OVC_OVERLAY_DIR_WRITABLE=yes", TextOverlayCatalog.outcome("OVC_OVERLAY_DIR_WRITABLE=yes\nOVC_TEXT_OVERLAYS_EXIT=0\n"))
        val reply = "OVC_OVERLAY_SKIPPED $phone.apk other-build\ncom.x here is not right.\nOVC_TEXT_OVERLAYS_EXIT=1"
        val (status, text) = TextOverlayCatalog.outcome(reply)
        assertEquals(1, status)
        assertEquals("com.x here is not right.", TextOverlayCatalog.failure(text))
        assertEquals(listOf(phone), TextOverlayCatalog.skipped(text))
        assertEquals(-1, TextOverlayCatalog.outcome("still running").first)
    }

    @Test fun stagingCopiesIntoRootsFolderWithAManifest() {
        val folder = "/data/user/0/org.openvoyah.controls/files/text-overlays/ru"
        val stage = TextOverlayCatalog.stage(set, folder)
        val state = TextOverlayCatalog.STATE
        assertTrue(stage.contains("rm -rf $state/stage && mkdir -m 700 $state/stage"))
        assertTrue(stage.contains("pin $fingerprint 1720866928\noverlay $a $setting.apk com.qinggan.app.setting $c\n"))
        assertTrue(stage.contains("cp '$folder/$phone.apk' $state/stage/$phone.apk || "))
        assertFalse(stage.contains("/data/local/tmp"))
        assertThrows(IllegalArgumentException::class.java) { TextOverlayCatalog.stage(set, "relative") }
        assertThrows(IllegalArgumentException::class.java) { TextOverlayCatalog.stage(BundledSet("ru", "-", "-", emptyList()), "/x") }
    }

    /** The root channel types every command into a PTY that drops what goes past 4095 bytes on one line. */
    @Test fun everyLineFitsThePtyForAFullSet() {
        val apps = listOf("setting", "vehiclesetting", "login", "thunder.carplay", "music", "gallery", "qscene", "video",
            "radio", "messagecenter", "media", "bluetoothphone", "settingnewwork", "campmode", "thirdscreen", "dvr",
            "smartscene", "restmode", "ehandbook", "islandapp", "systemui", "calendar", "diagnosis", "smartcar",
            "ipkupdateservice", "qgime", "qgime.second", "systempolicy", "usersettingtracking", "pateo.vuivendor",
            "systemservice", "huawei.hisight", "qingplay")
        assertEquals(33, apps.size)
        val rows = apps.joinToString("") { app ->
            val pkg = "org.openvoyah.controls.overlay.ru.$app"
            "$pkg.apk\t$pkg\tcom.qinggan.app.${app.replace('.', '_')}\t$a\t$c\t10\n"
        }
        val full = TextOverlayCatalog.parse("ru", "pin $fingerprint 1720866928\n$rows")
        val commands = listOf(
            TextOverlayCatalog.stage(full, "/data/user/0/org.openvoyah.controls/files/text-overlays/ru"),
            TextOverlayCatalog.start(script, "install", fingerprint, job),
            TextOverlayCatalog.start(script, "enable", fingerprint, job, full.overlays.map { it.pkg }),
            TextOverlayCatalog.status(script, fingerprint),
            TextOverlayCatalog.poll(job),
        )
        for (command in commands) {
            val longest = command.lines().maxOf { it.toByteArray().size }
            assertTrue("A line of $longest bytes", longest < TextOverlayCatalog.MAX_LINE)
        }
    }
}

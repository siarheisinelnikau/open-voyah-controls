package org.openvoyah.controls.data.frida

import android.content.Context
import android.os.Build
import org.json.JSONObject
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

internal object ReviewedFirmware {
    const val LAUNCHER = "com.qinggan.app.launcher"
    const val FRAMEWORK = "android"
    const val CLUSTER = "com.qinggan.cluster"
    const val WINDOW_SERVICE = "window-service"
    const val PASSENGER_WINDOW_DPI = "passenger-window-dpi"
    const val OEM_PROFILES = "oem-profiles"
    const val TBOX_REBOOT = "tbox-reboot"
    const val CLUSTER_WRITE = "cluster-write"

    val localizationTargets = mapOf(
        "setting" to "com.qinggan.app.setting",
        "vehicle" to "com.qinggan.app.vehicle",
        "bluetooth" to "com.qinggan.bluetoothphone",
    )
    private val packages = setOf(LAUNCHER, FRAMEWORK, CLUSTER) + localizationTargets.values
    private val operations = setOf(WINDOW_SERVICE, PASSENGER_WINDOW_DPI, OEM_PROFILES, TBOX_REBOOT, CLUSTER_WRITE)
    private val fingerprint = Regex("[A-Za-z0-9][A-Za-z0-9._:/+\\-]{0,255}")
    private val product = Regex("[A-Za-z0-9][A-Za-z0-9_.\\-]{0,63}")
    val sha256 = Regex("[0-9a-f]{64}")

    data class Device(val fingerprint: String, val sdk: String, val abi: String, val product: String)
    data class PackagePin(val name: String, val signer: String, val apk: String)
    data class Review(val device: Device, val packages: List<PackagePin>, val operations: Set<String>)
    data class Runtime(val version: String, val name: String, val digest: String)

    fun load(context: Context): Review {
        val device = Device(Build.FINGERPRINT, Build.VERSION.SDK_INT.toString(), Build.SUPPORTED_ABIS.firstOrNull().orEmpty(), Build.PRODUCT)
        val catalog = text(context, "verified-firmware.tsv")
        val capabilities = text(context, "verified-operations.tsv")
        return select(catalog, capabilities, device)
            ?: throw ProductionRootAccess.Refused(UiText.get(R.string.production_unreviewed_firmware))
    }

    fun runtime(context: Context): Runtime {
        val metadata = JSONObject(text(context, "FRIDA_RUNTIME.json"))
        val version = metadata.getString("version")
        val name = metadata.getString("file")
        val hash = metadata.getString("binary_sha256")
        check(version.matches(Regex("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}")))
        check(name == "open-voyah-controls-frida-$version" && hash.matches(sha256))
        return Runtime(version, name, hash)
    }

    fun select(catalog: String, capabilities: String, device: Device): Review? {
        val profiles = mutableMapOf<Device, MutableMap<String, PackagePin>>()
        for (row in rows(catalog)) {
            require(row.size == 7 && row[4] in packages && row[5].matches(sha256) && row[6].matches(sha256))
            val key = identity(row)
            val pins = profiles.getOrPut(key) { mutableMapOf() }
            require(pins.put(row[4], PackagePin(row[4], row[5], row[6])) == null)
        }
        require(profiles.values.all { LAUNCHER in it })
        val reviewed = mutableMapOf<Device, MutableSet<String>>()
        for (row in rows(capabilities)) {
            require(row.size == 5 && row[4] in operations)
            val key = identity(row)
            require(key in profiles && reviewed.getOrPut(key) { mutableSetOf() }.add(row[4]))
        }
        val profile = profiles[device] ?: return null
        return Review(device, profile.values.toList(), reviewed[device]?.toSet().orEmpty())
    }

    private fun identity(row: List<String>): Device {
        require(row[0].matches(fingerprint) && row[1] in setOf("28", "30") && row[2] == "arm64-v8a" && row[3].matches(product))
        return Device(row[0], row[1], row[2], row[3])
    }

    private fun rows(table: String) = table.lineSequence().map { it.removeSuffix("\r") }
        .filter { it.isNotEmpty() && !it.startsWith('#') }.map { it.split('\t') }

    private fun text(context: Context, name: String) = context.assets.open("production/$name").use {
        val buffer = ByteArray(4096)
        val result = java.io.ByteArrayOutputStream()
        while (true) {
            val size = it.read(buffer)
            if (size < 0) break
            if (result.size() + size > 65536) throw IOException("Reviewed metadata is too large")
            result.write(buffer, 0, size)
        }
        result.toString(Charsets.UTF_8.name())
    }

    fun digest(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun digest(input: InputStream): String {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) return hex(hash.digest())
            hash.update(buffer, 0, size)
        }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
}

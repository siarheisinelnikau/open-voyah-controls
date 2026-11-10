package org.openvoyah.controls.data.frida

import android.content.Context
import android.os.Build
import org.json.JSONObject
import org.openvoyah.controls.core.Firmware

/** Immutable, operation-specific hardware evidence packaged with the signed app and hook. */
internal class ReviewedVoiceCommands(context: Context) {
    private val application = context.applicationContext
    private val approval by lazy {
        runCatching {
            val raw = application.assets.open("voice/verified-commands.json").bufferedReader().use { it.readText() }
            parse(raw, Identity(Build.PRODUCT, Build.FINGERPRINT, Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.firstOrNull()))
        }.getOrNull()
    }

    fun profileFor(command: String): String? = approval?.profileFor(command)
    fun hasApprovedCommands(): Boolean = approval != null

    data class Identity(val product: String, val fingerprint: String, val sdk: Int, val abi: String?)

    data class Approval(private val profile: String, private val commands: Set<String>) {
        fun profileFor(command: String): String? = profile.takeIf { command in commands }
    }

    companion object {
        private val fingerprint = Regex("[A-Za-z0-9_.:/+-]{1,256}")
        private val commandId = Regex("[A-Za-z][A-Za-z0-9]{0,63}")

        fun parse(raw: String, device: Identity): Approval? {
            val profile = Firmware.identify(device.product)
            if (device.sdk !in listOf(28, 30) || device.abi != "arm64-v8a" ||
                !fingerprint.matches(device.fingerprint) || profile == "UNKNOWN") return null
            val root = JSONObject(raw)
            if (root.opt("schema") != 1) return null
            val units = root.getJSONArray("headUnits")
            if (units.length() > 128) return null
            val unit = (0 until units.length()).map(units::getJSONObject).singleOrNull {
                it.opt("product") == device.product && it.opt("fingerprint") == device.fingerprint &&
                    it.opt("sdk") == device.sdk && it.opt("abi") == device.abi
            } ?: return null
            if (unit.opt("profile") != profile) return null
            val commands = unit.getJSONArray("commands")
            if (commands.length() !in 1..256) return null
            val names = (0 until commands.length()).map { commands.opt(it) as? String ?: return null }
            if (names.any { !commandId.matches(it) } || names.distinct().size != names.size) return null
            return Approval(profile, names.toSet())
        }
    }
}

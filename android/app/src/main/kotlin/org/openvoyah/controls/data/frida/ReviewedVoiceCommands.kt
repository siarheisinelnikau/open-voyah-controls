package org.openvoyah.controls.data.frida

import android.content.Context
import android.os.Build
import org.json.JSONObject
import org.openvoyah.controls.core.Firmware
import org.openvoyah.controls.data.settings.ActionPermissions
import org.openvoyah.controls.domain.permissions.ActionCatalog
import org.openvoyah.controls.domain.permissions.ActionPermission

/**
 * Which vehicle voice commands may run on this head unit. Every vehicle command is allowed on a known Voyah product
 * (SDK 28/30, arm64) unless the owner forbade it in Settings → Allowed actions. The packaged
 * `verified-commands.json` rows are metadata: they mark commands confirmed on the exact firmware and may name its
 * vehicle profile.
 */
internal class ReviewedVoiceCommands(context: Context) {
    private val application = context.applicationContext
    private val identity = Identity(Build.PRODUCT, Build.FINGERPRINT, Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.firstOrNull())
    private val approval by lazy {
        runCatching {
            val raw = application.assets.open("voice/verified-commands.json").bufferedReader().use { it.readText() }
            parse(raw, identity)
        }.getOrNull()
    }
    private val vehicleProfile by lazy { approval?.profile ?: profileOf(identity) }
    private val permissions = ActionPermissions(application)

    /** The vehicle profile for [command], or null when the owner forbade it or this is not a supported head unit. */
    fun profileFor(command: String): String? = vehicleProfile?.takeIf { permission(command).runs }
    /** The head unit is a known Voyah product, so vehicle commands can be planned for it. */
    fun hasApprovedCommands(): Boolean = vehicleProfile != null
    /** Commands confirmed on this exact firmware, for the "verified" mark in Settings. */
    fun verifiedCommands(): Set<String> = approval?.commands.orEmpty()
    fun permission(command: String): ActionPermission = permissions.voice(command)
    fun isSwitchedOff(command: String): Boolean = vehicleProfile != null && permission(command) == ActionPermission.FORBIDDEN
    /** Journals an unverified command right before it is sent. */
    fun recordUse(command: String) {
        if (ActionCatalog.isVehicleCommand(command)) permissions.recordUse(ActionCatalog.voice(command))
    }

    data class Identity(val product: String, val fingerprint: String, val sdk: Int, val abi: String?)

    data class Approval(val profile: String, val commands: Set<String>) {
        fun profileFor(command: String): String? = profile.takeIf { command in commands }
    }

    companion object {
        private val fingerprint = Regex("[A-Za-z0-9_.:/+,-]{1,256}")
        private val commandId = Regex("[A-Za-z][A-Za-z0-9]{0,63}")

        /** The vehicle profile of a supported head unit without a review row, or null. */
        fun profileOf(device: Identity): String? {
            val profile = Firmware.identify(device.product)
            return profile.takeIf {
                device.sdk in listOf(28, 30) && device.abi == "arm64-v8a" && fingerprint.matches(device.fingerprint) && it != "UNKNOWN"
            }
        }

        fun parse(raw: String, device: Identity): Approval? {
            val profile = profileOf(device) ?: return null
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

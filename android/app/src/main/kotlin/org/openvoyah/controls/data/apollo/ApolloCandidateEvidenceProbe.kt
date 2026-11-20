package org.openvoyah.controls.data.apollo

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.openvoyah.controls.domain.apollo.ApolloCacheStatus
import org.openvoyah.controls.domain.apollo.ApolloCandidatePlan
import org.openvoyah.controls.domain.apollo.FactoryAssistanceEvidence
import org.openvoyah.controls.platform.ui.FACTORY_VEHICLE_SETTINGS_PACKAGE
import org.openvoyah.controls.platform.ui.VehicleSettingsDestination
import org.openvoyah.controls.platform.ui.VehicleSettingsLauncher

/** The general report reads package metadata. Only an explicit assistance check reads the OEM request cache. */
internal class ApolloCandidateEvidenceProbe(context: Context) {
    private val app = context.applicationContext
    private val manager = app.packageManager
    private val cacheReader = ApolloFactoryCacheReader(app)

    suspend fun report(): String = capture(includeFactoryCache = false).json

    suspend fun capture(includeFactoryCache: Boolean): FactoryAssistanceEvidence = withContext(Dispatchers.IO) {
        val started = SystemClock.elapsedRealtime()
        val provider = runCatching { manager.resolveContentProvider(BAIDU_AUTHORITY, 0) }.getOrNull()
        val cacheProvider = runCatching { cacheReader.provider() }.getOrNull()
        val packages = linkedSetOf(FACTORY_VEHICLE_SETTINGS_PACKAGE, ApolloFactoryCacheReader.CAN_PACKAGE)
        provider?.packageName?.let(packages::add)
        cacheProvider?.packageName?.let(packages::add)
        val records = JSONArray()
        for (name in packages) records.put(packageRecord(name))

        val launcher = VehicleSettingsLauncher(app)
        val dedicated = runCatching { launcher.intent(VehicleSettingsDestination.ASSISTANCE)?.component }.getOrNull()
        val general = runCatching { launcher.intent(VehicleSettingsDestination.VEHICLE)?.component }.getOrNull()
        val cache = if (includeFactoryCache) cacheReader.read(cacheProvider) else ApolloFactoryCache(ApolloCacheStatus.NOT_REQUESTED)
        val cachedFields = cache.fields.values.count { it.value != null }
        val fields = JSONObject()
        for ((name, field) in cache.fields) {
            val item = JSONObject().put("state", field.state)
            field.value?.let { item.put("raw_integer", it) }
            fields.put(name, item)
        }
        val cacheReport = JSONObject().put("authority", ApolloFactoryCacheReader.AUTHORITY)
            .put("provider", providerRecord(cacheProvider)).put("state", cache.status.name.lowercase())
            .put("source", "QGSettings.System persisted CAN request cache")
            .put("expected_fields", ApolloCandidatePlan.AFFECTED_FLAGS.size).put("observed_fields", cachedFields)
            .put("fields", fields).put("controller_feedback", false).put("usable_as_rollback", false)
        val report = JSONObject().put("schema", 1).put("mode", "read_only")
            .put("capture_started_elapsed_ms", started).put("capture_ended_elapsed_ms", SystemClock.elapsedRealtime())
            .put("candidate_prepared", true).put("candidate_scope", "isolated_fixture")
            .put("production_activation_available", false)
            .put("identity", JSONObject().put("product", Build.PRODUCT).put("fingerprint", Build.FINGERPRINT)
                .put("sdk", Build.VERSION.SDK_INT).put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: JSONObject.NULL))
            .put("packages", records)
            .put("factory_menu", JSONObject().put("assistance_component", dedicated?.flattenToShortString() ?: JSONObject.NULL)
                .put("vehicle_component", general?.flattenToShortString() ?: JSONObject.NULL).put("opened_by_probe", false))
            .put("provider", providerRecord(provider).put("authority", BAIDU_AUTHORITY).put("content_queried", false))
            .put("factory_request_cache", cacheReport)
            .put("recovery", JSONObject().put("off_device_backup", "not_verified")
                .put("independent_restore", "not_verified").put("adcu_originals", "not_observed")
                .put("queued_factory_refresh_completion", "not_observed"))
            .put("missing_evidence", JSONArray(listOf(
                "Reviewed exact installed H97D firmware with signed factory settings, CAN and Baidu provider packages",
                "Fresh ASC and SDB configuration from the factory settings process during cache initialization, with its actual platform branch",
                "ADCU-originated original value for each of the 18 fields; request cache fields can change before controller delivery",
                "Factory account, completed learning, entitlement and TLC confirmation shown on this build, without identity contents",
                "Complete off-device backup of settings, CAN and provider stores, account, vehicle configuration and controller states",
                "Independent restoration of all original states after app loss and normal sleep, with controller readback",
                "Completion of the factory subscription query worker, subsequent handler processing and any later controller writes",
                "Fresh qualified Park and zero speed plus session-bound explicit user confirmation",
            )))
        FactoryAssistanceEvidence(report.toString(2), cache.status, cachedFields)
    }

    suspend fun export(document: Uri, evidence: FactoryAssistanceEvidence) = withContext(Dispatchers.IO) {
        require(document.scheme == "content")
        val output = app.contentResolver.openOutputStream(document, "wt") ?: throw IOException("Report destination unavailable")
        output.use { it.write(evidence.json.toByteArray(Charsets.UTF_8)) }
    }

    private suspend fun packageRecord(name: String): JSONObject {
        fun state(value: String) = JSONObject().put("package", name).put("state", value)
        return try {
            val info = manager.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)
            val source = info.applicationInfo?.sourceDir
            val fingerprint = fingerprintApolloApk(source?.let(::File))
            val current = manager.getPackageInfo(name, 0)
            if (current.applicationInfo?.sourceDir != source || current.lastUpdateTime != info.lastUpdateTime ||
                current.longVersionCode != info.longVersionCode ||
                current.splitNames.orEmpty().toList() != info.splitNames.orEmpty().toList()) return state("changed")
            val signatures = info.signingInfo?.apkContentsSigners.orEmpty().map {
                apolloHex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray()))
            }.sorted()
            val expected = INSPECTED_APKS[name]
            state("observed").put("version", info.versionName ?: JSONObject.NULL).put("version_code", info.longVersionCode)
                .put("system_app", (info.applicationInfo?.flags ?: 0) and SYSTEM_FLAGS != 0)
                .put("split_count", info.splitNames.orEmpty().size)
                .put("current_certificates_sha256", JSONArray(signatures))
                .put("base_apk_hash_state", fingerprint.status).put("base_apk_bytes", fingerprint.size ?: JSONObject.NULL)
                .put("base_apk_sha256", fingerprint.sha256 ?: JSONObject.NULL)
                .put("base_apk_matches_inspected_source",
                    if (expected != null && fingerprint.sha256 != null) fingerprint.sha256 == expected else JSONObject.NULL)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: PackageManager.NameNotFoundException) { state("not_visible") }
        catch (_: RuntimeException) { state("read_unavailable") }
    }

    private fun providerRecord(provider: ProviderInfo?) = JSONObject()
        .put("owner", provider?.packageName ?: JSONObject.NULL)
        .put("exported", provider?.exported ?: JSONObject.NULL)
        .put("read_permission", provider?.readPermission ?: JSONObject.NULL)

    private companion object {
        const val BAIDU_AUTHORITY = "com.pateo.baiduStateProvider"
        const val SYSTEM_FLAGS = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        // Equality describes the inspected base APK bytes only. It does not authorize any action.
        val INSPECTED_APKS = mapOf(
            FACTORY_VEHICLE_SETTINGS_PACKAGE to "94477c6d17d7ccac9f6a4027e1defef835d5f59faae319b3baa0ef8be3acc423",
            ApolloFactoryCacheReader.CAN_PACKAGE to "ae5f9fe98df395c0374175a86e83bfe49bbb443264e432391251a78a005879b6",
        )
    }
}

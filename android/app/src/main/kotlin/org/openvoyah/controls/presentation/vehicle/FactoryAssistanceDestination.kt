package org.openvoyah.controls.presentation.vehicle

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.apollo.ApolloCandidateEvidenceProbe
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.diagnostics.LogLevel
import org.openvoyah.controls.domain.apollo.ApolloCacheStatus
import org.openvoyah.controls.domain.apollo.ApolloCandidatePlan
import org.openvoyah.controls.domain.apollo.FactoryAssistanceEvidence
import org.openvoyah.controls.platform.ui.VehicleSettingsLauncher
import org.openvoyah.controls.platform.ui.VehicleSettingsDestination as FactoryDestination
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitColumns
import org.openvoyah.controls.presentation.theme.CockpitDetail
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitSection

internal data class FactoryAssistanceState(
    val available: Boolean = false,
    val fallback: Boolean = false,
    val handoff: Int? = null,
    val evidence: FactoryAssistanceEvidence? = null,
    val working: Boolean = false,
    val reportStatus: Int? = null,
)

/** A factory-menu handoff is never evidence that a driving assistant was activated. */
internal class FactoryAssistanceViewModel(
    private val canOpenAssistance: () -> Boolean,
    private val canOpenVehicleMenu: () -> Boolean,
    private val capture: suspend () -> FactoryAssistanceEvidence,
    private val export: suspend (String, FactoryAssistanceEvidence) -> Unit,
    private val log: (String) -> Unit,
) : ViewModel() {
    private val mutable = MutableStateFlow(FactoryAssistanceState())
    private var reportJob: Job? = null
    val state = mutable.asStateFlow()

    fun refresh() {
        val exact = runCatching(canOpenAssistance).getOrDefault(false)
        val fallback = !exact && runCatching(canOpenVehicleMenu).getOrDefault(false)
        mutable.value = mutable.value.copy(available = exact || fallback, fallback = fallback)
    }

    fun handoff(outcome: String, message: Int) {
        mutable.update { it.copy(handoff = message) }
        log("event=apollo_factory_menu outcome=$outcome")
    }

    fun check() {
        if (mutable.value.working) return
        mutable.update { it.copy(evidence = null, working = true, reportStatus = R.string.factory_assistance_reading) }
        reportJob = viewModelScope.launch {
            try {
                val snapshot = capture()
                mutable.update { it.copy(evidence = snapshot, working = false, reportStatus = null) }
                log("event=apollo_factory_check outcome=${snapshot.cacheStatus.name.lowercase()} cached_fields=${snapshot.cachedFields}")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { reportFailed() }
        }
    }

    fun save(destination: String) {
        val snapshot = mutable.value.evidence ?: return
        if (mutable.value.working) return
        mutable.update { it.copy(working = true, reportStatus = R.string.factory_assistance_saving) }
        reportJob = viewModelScope.launch {
            try {
                export(destination, snapshot)
                mutable.update { it.copy(working = false, reportStatus = R.string.factory_assistance_saved) }
                log("event=apollo_factory_report outcome=saved")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { reportFailed() }
        }
    }

    fun reportFailed() {
        mutable.update { it.copy(working = false, reportStatus = R.string.factory_assistance_report_failed) }
        log("event=apollo_factory_report outcome=failed")
    }

    fun leave() {
        reportJob?.cancel()
        reportJob = null
        mutable.value = FactoryAssistanceState()
    }
}

@Composable
internal fun FactoryAssistanceDestination(back: () -> Unit, diagnostics: () -> Unit) {
    val activity = checkNotNull(LocalActivity.current as? ComponentActivity)
    val app = activity.applicationContext
    val launcher = remember(app) { VehicleSettingsLauncher(app) }
    val model = remember(activity) {
        val probe = ApolloCandidateEvidenceProbe(app)
        ViewModelProvider(activity, viewModelFactory { initializer {
            FactoryAssistanceViewModel(
                canOpenAssistance = { launcher.assistanceMenuIntent() != null },
                canOpenVehicleMenu = { launcher.vehicleMenuIntent() != null },
                capture = { probe.capture(includeFactoryCache = true) },
                export = { destination, snapshot -> probe.export(Uri.parse(destination), snapshot) },
                log = { ControlsLog.line(LogLevel.INFO, "$it elapsed_ms=${SystemClock.elapsedRealtime()}") },
            )
        } })[FactoryAssistanceViewModel::class.java]
    }
    val state by model.state.collectAsStateWithLifecycle()
    val saveReport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { model.save(it.toString()) }
    }
    DisposableEffect(model) { onDispose { if (!activity.isChangingConfigurations) model.leave() } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LifecycleResumeEffect(lifecycle, model) {
        model.refresh()
        onPauseOrDispose { }
    }
    fun openFactory() {
        val exact = runCatching { launcher.assistanceMenuIntent() }.getOrNull()
        val intent = exact ?: runCatching { launcher.vehicleMenuIntent() }.getOrNull()
        if (intent == null) model.handoff("unavailable", R.string.vehicle_settings_unavailable) else try {
            activity.startActivity(intent)
            if (exact != null) model.handoff("requested", R.string.factory_assistance_requested)
            else model.handoff("vehicle_menu_requested", R.string.factory_assistance_requested)
        } catch (_: RuntimeException) { model.handoff("failed", R.string.vehicle_settings_unavailable) }
    }
    CockpitDetail(UiText.get(R.string.factory_assistance_title), UiText.get(R.string.factory_assistance_description), back) {
        CockpitColumns(first = {
            CockpitSection(UiText.get(R.string.factory_assistance_setup), CockpitIcon.CAR,
                UiText.get(R.string.factory_assistance_sequence)) {
                CockpitAction(UiText.get(if (state.fallback) R.string.factory_assistance_open_vehicle_menu
                    else R.string.vehicle_settings_open), ::openFactory, primary = true, enabled = state.available)
                val feedback = state.handoff ?: when {
                    !state.available -> R.string.vehicle_settings_unavailable
                    state.fallback -> R.string.factory_assistance_fallback
                    else -> null
                }
                feedback?.let { CockpitFeedback(UiText.get(it)) }
                CockpitAction(UiText.get(R.string.factory_assistance_manual), {
                    try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(H97D_ASSISTANCE_MANUAL))) }
                    catch (_: RuntimeException) { model.handoff("manual_unavailable", R.string.factory_assistance_manual_unavailable) }
                })
            }
        }, second = {
            CockpitSection(UiText.get(R.string.factory_assistance_qualification), CockpitIcon.LOCK,
                UiText.get(R.string.factory_assistance_unqualified)) {
                CockpitAction(UiText.get(R.string.factory_assistance_check), model::check, enabled = !state.working)
                state.evidence?.let { evidence ->
                    val message = when (evidence.cacheStatus) {
                        ApolloCacheStatus.SAMPLED -> UiText.get(R.string.factory_assistance_sampled)
                            .format(evidence.cachedFields, ApolloCandidatePlan.AFFECTED_FLAGS.size)
                        ApolloCacheStatus.PROVIDER_UNAVAILABLE -> UiText.get(R.string.factory_assistance_no_provider)
                        ApolloCacheStatus.ACCESS_DENIED -> UiText.get(R.string.factory_assistance_no_access)
                        else -> UiText.get(R.string.factory_assistance_incomplete)
                    }
                    CockpitFeedback(message)
                    CockpitAction(UiText.get(R.string.factory_assistance_export), {
                        try { saveReport.launch("apollo-factory-report.json") }
                        catch (_: RuntimeException) { model.reportFailed() }
                    }, enabled = !state.working)
                }
                state.reportStatus?.let { CockpitFeedback(UiText.get(it), error = it == R.string.factory_assistance_report_failed) }
                CockpitAction(UiText.get(R.string.vehicle_automation_diagnostics), diagnostics)
            }
        })
    }
}

internal const val H97D_ASSISTANCE_MANUAL = "https://m.dsmp.voyah.com.cn/userguide/h97d/h97D-40.html"

private fun VehicleSettingsLauncher.assistanceMenuIntent(): Intent? = intent(FactoryDestination.ASSISTANCE)

private fun VehicleSettingsLauncher.vehicleMenuIntent(): Intent? = intent(FactoryDestination.VEHICLE)

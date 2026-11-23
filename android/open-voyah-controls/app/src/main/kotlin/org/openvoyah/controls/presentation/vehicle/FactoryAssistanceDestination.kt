package org.openvoyah.controls.presentation.vehicle

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.apollo.ApolloCandidateEvidenceProbe
import org.openvoyah.controls.data.apollo.ApolloReportNote
import org.openvoyah.controls.data.frida.FridaBridge
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.diagnostics.LogLevel
import org.openvoyah.controls.domain.apollo.ApolloAssistanceStatus
import org.openvoyah.controls.domain.apollo.ApolloCacheStatus
import org.openvoyah.controls.domain.apollo.ApolloCandidatePlan
import org.openvoyah.controls.domain.apollo.FactoryAssistanceEvidence
import org.openvoyah.controls.platform.ui.FactoryAssistanceHandoff
import org.openvoyah.controls.platform.ui.copyReportToClipboard
import org.openvoyah.controls.platform.ui.VehicleSettingsLauncher
import org.openvoyah.controls.platform.ui.VehicleSettingsDestination as FactoryDestination
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitAlertDialog
import org.openvoyah.controls.presentation.theme.CockpitColumns
import org.openvoyah.controls.presentation.theme.CockpitDetail
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitSection
import org.openvoyah.controls.presentation.theme.CockpitSwitch
import org.openvoyah.controls.presentation.theme.CockpitTextAction
import org.openvoyah.controls.presentation.theme.CockpitTextField

internal data class FactoryAssistanceState(
    val assistanceMenuAvailable: Boolean = false,
    val vehicleMenuAvailable: Boolean = false,
    val handoff: Int? = null,
    val evidence: FactoryAssistanceEvidence? = null,
    val working: Boolean = false,
    val reportStatus: Int? = null,
    val note: String = "",
    val copyStatus: Int? = null,
    val parts: Map<String, Boolean> = emptyMap(),
    val request: String? = null,
    val status: ApolloAssistanceStatus = ApolloAssistanceStatus.Unreported,
    val applying: Boolean = false,
    val applyError: String? = null,
)

/** A factory-menu handoff is never evidence that a driving assistant was activated. */
internal class FactoryAssistanceViewModel(
    private val canOpenAssistance: () -> Boolean,
    private val canOpenVehicleMenu: () -> Boolean,
    private val capture: suspend () -> FactoryAssistanceEvidence,
    private val export: suspend (String, FactoryAssistanceEvidence, String) -> Unit,
    private val copy: (String) -> Boolean,
    private val parts: () -> Map<String, Boolean>,
    private val request: () -> String?,
    private val status: () -> ApolloAssistanceStatus,
    private val apply: suspend (String, Boolean) -> Unit,
    private val log: (String) -> Unit,
) : ViewModel() {
    private val mutable = MutableStateFlow(FactoryAssistanceState())
    private var reportJob: Job? = null
    private var statusJob: Job? = null
    val state = mutable.asStateFlow()

    /** The agent publishes its report about a second after the switch changed, so keep reading while in front. */
    fun startWatching() {
        if (statusJob?.isActive == true) return
        statusJob = viewModelScope.launch {
            while (isActive) {
                refreshStatus()
                delay(1000)
            }
        }
    }

    fun stopWatching() {
        statusJob?.cancel()
        statusJob = null
    }

    private fun refreshStatus() {
        val current = runCatching(request).getOrDefault(null)
        val reported = runCatching(status).getOrDefault(ApolloAssistanceStatus.Unreported)
        mutable.update { it.copy(request = current, status = reported) }
    }

    fun refresh() {
        refreshStatus()
        val assistance = runCatching(canOpenAssistance).getOrDefault(false)
        val vehicle = runCatching(canOpenVehicleMenu).getOrDefault(false)
        val chosen = runCatching(parts).getOrDefault(emptyMap())
        mutable.update { it.copy(assistanceMenuAvailable = assistance, vehicleMenuAvailable = vehicle, parts = chosen) }
    }

    /**
     * Hands one part of the factory assistance path to the agent. The request is what is recorded: an accepted
     * answer to the factory page, and for the controller part an accepted send, is never a confirmed vehicle state.
     */
    fun change(part: String, enable: Boolean) {
        if (mutable.value.applying) return
        mutable.update { it.copy(applying = true, applyError = null) }
        viewModelScope.launch {
            val failure = try {
                apply(part, enable)
                null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { error.message.orEmpty().take(160).ifBlank { "unavailable" } }
            mutable.update { it.copy(applying = false, applyError = failure) }
            refresh()
            log("event=apollo_assistance part=$part enable=$enable outcome=${if (failure == null) "applied" else "refused"}")
        }
    }

    fun handoff(outcome: String, message: Int) {
        mutable.update { it.copy(handoff = message) }
        log("event=apollo_factory_menu outcome=$outcome")
    }

    fun check() {
        if (mutable.value.working) return
        mutable.update { it.copy(evidence = null, working = true, reportStatus = R.string.factory_assistance_reading, copyStatus = null) }
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
                // The note leaves with the document only. It is never persisted and never logged.
                export(destination, snapshot, mutable.value.note)
                mutable.update { it.copy(working = false, reportStatus = R.string.factory_assistance_saved) }
                log("event=apollo_factory_report outcome=saved note=${mutable.value.note.isNotBlank()}")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { reportFailed() }
        }
    }

    /** The driver records the factory page in their own words; only the length is observed here. */
    fun editNote(value: String) {
        mutable.update { it.copy(note = value.take(ApolloReportNote.MAX_CHARS)) }
    }

    /** Puts the report identities on the clipboard so a support request can quote them without the file. */
    fun copySummary() {
        val summary = mutable.value.evidence?.supportSummary
        val copied = !summary.isNullOrBlank() && runCatching { copy(summary) }.getOrDefault(false)
        mutable.update { it.copy(copyStatus = if (copied) R.string.factory_assistance_copied else R.string.factory_assistance_copy_failed) }
        log("event=apollo_factory_copy outcome=${if (copied) "copied" else "failed"}")
    }

    fun reportFailed() {
        mutable.update { it.copy(working = false, reportStatus = R.string.factory_assistance_report_failed) }
        log("event=apollo_factory_report outcome=failed")
    }

    /** Leaving the page drops the note: it belongs to the report it was written for, not to the settings archive. */
    fun leave() {
        reportJob?.cancel()
        reportJob = null
        stopWatching()
        mutable.value = FactoryAssistanceState()
    }

    companion object {
        /** The parts of the factory assistance path, named as the agent's own document names them. */
        const val APOLLO_SUBSCRIPTION = "subscription"
        const val APOLLO_CONTROLLER = "controller"
        const val APOLLO_EQUIPMENT = "equipment"
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
                canOpenAssistance = { launcher.intent(FactoryDestination.ASSISTANCE) != null },
                canOpenVehicleMenu = { launcher.intent(FactoryDestination.ASSISTANCE_VEHICLE_MENU) != null },
                capture = { probe.capture(includeFactoryCache = true) },
                export = { destination, snapshot, note -> probe.export(Uri.parse(destination), snapshot, note) },
                copy = { summary -> copyReportToClipboard(app, UiText.get(R.string.factory_assistance_title), summary) },
                parts = { FridaBridge.APOLLO_PARTS.associateWith { FridaBridge.apolloPartEnabled(app, it) } },
                request = { FridaBridge.apolloRequest(app) },
                status = { ApolloAssistanceStatus.parse(FridaBridge.apolloState(app)) },
                apply = { part, enable -> withContext(Dispatchers.IO) { FridaBridge(app).setApolloPart(part, enable) } },
                log = { ControlsLog.line(LogLevel.INFO, "$it elapsed_ms=${SystemClock.elapsedRealtime()}") },
            )
        } })[FactoryAssistanceViewModel::class.java]
    }
    val state by model.state.collectAsStateWithLifecycle()
    // A part is switched on through a dialog, because the two experimental ones cannot be undone from the head unit.
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val saveReport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { model.save(it.toString()) }
    }
    DisposableEffect(model) { onDispose { if (!activity.isChangingConfigurations) model.leave() } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LifecycleResumeEffect(lifecycle, model) {
        model.refresh()
        model.startWatching()
        onPauseOrDispose { model.stopWatching() }
    }
    fun openFactory(preferAssistance: Boolean) {
        val outcome = launcher.requestFactoryAssistance(preferAssistance, activity::startActivity)
        val message = when (outcome) {
            FactoryAssistanceHandoff.ASSISTANCE_REQUESTED -> R.string.factory_assistance_requested
            FactoryAssistanceHandoff.VEHICLE_MENU_REQUESTED -> R.string.factory_assistance_fallback
            FactoryAssistanceHandoff.UNAVAILABLE, FactoryAssistanceHandoff.FAILED -> {
                model.refresh()
                R.string.factory_assistance_open_failed
            }
        }
        model.handoff(outcome.name.lowercase(), message)
    }
    CockpitDetail(UiText.get(R.string.factory_assistance_title), UiText.get(R.string.factory_assistance_description), back) {
        CockpitColumns(first = {
            CockpitSection(UiText.get(R.string.factory_assistance_setup), CockpitIcon.CAR,
                UiText.get(R.string.factory_assistance_sequence)) {
                if (state.assistanceMenuAvailable || !state.vehicleMenuAvailable) {
                    CockpitAction(UiText.get(R.string.factory_assistance_open_assistance),
                        { openFactory(preferAssistance = true) }, primary = true, enabled = state.assistanceMenuAvailable)
                }
                if (state.vehicleMenuAvailable) {
                    CockpitAction(UiText.get(R.string.factory_assistance_open_vehicle_menu),
                        { openFactory(preferAssistance = false) }, primary = !state.assistanceMenuAvailable)
                }
                val feedback = state.handoff ?: when {
                    !state.assistanceMenuAvailable && !state.vehicleMenuAvailable -> R.string.vehicle_settings_unavailable
                    !state.assistanceMenuAvailable -> R.string.factory_assistance_fallback
                    else -> null
                }
                feedback?.let { CockpitFeedback(UiText.get(it), error = it == R.string.factory_assistance_open_failed) }
                Text(UiText.get(R.string.factory_assistance_guest), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                CockpitAction(UiText.get(R.string.factory_assistance_manual), {
                    try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(H97D_ASSISTANCE_MANUAL))) }
                    catch (_: RuntimeException) { model.handoff("manual_unavailable", R.string.factory_assistance_manual_unavailable) }
                })
            }
            CockpitSection(UiText.get(R.string.factory_assistance_answers), CockpitIcon.SETTINGS,
                UiText.get(R.string.factory_assistance_answers_summary)) {
                apolloPartRows.forEach { part ->
                    CockpitSwitch(UiText.get(part.title), state.parts[part.id] == true, enabled = !state.applying) { enable ->
                        // Turning a part off is always safe, so it needs no question.
                        if (enable) pending = part.id else model.change(part.id, false)
                    }
                }
                if (state.status.waitingFor(state.parts, state.request)) {
                    CockpitFeedback(UiText.get(R.string.factory_assistance_answers_waiting))
                }
                if (state.status.isCurrent(state.request)) {
                    state.status.unavailable.takeIf { it.isNotEmpty() }?.let {
                        CockpitFeedback(UiText.get(R.string.factory_assistance_answers_unavailable).format(it.joinToString(", ")))
                    }
                    state.status.requested.takeIf { it.isNotEmpty() }?.let {
                        CockpitFeedback(UiText.get(R.string.factory_assistance_answers_controller_request))
                    }
                    state.status.uncertain.takeIf { it.isNotEmpty() }?.let {
                        CockpitFeedback(UiText.get(R.string.factory_assistance_answers_controller_uncertain))
                    }
                }
                state.applyError?.let { CockpitFeedback(it, error = true) }
                Text(UiText.get(R.string.factory_assistance_answers_note), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        try { saveReport.launch(reportFileName()) }
                        catch (_: RuntimeException) { model.reportFailed() }
                    }, enabled = !state.working)
                    CockpitAction(UiText.get(R.string.factory_assistance_copy), model::copySummary)
                    state.copyStatus?.let {
                        CockpitFeedback(UiText.get(it), error = it == R.string.factory_assistance_copy_failed)
                    }
                }
                CockpitTextField(state.note, model::editNote, UiText.get(R.string.factory_assistance_note),
                    Modifier.fillMaxWidth(), singleLine = false, enabled = !state.working,
                    supporting = UiText.get(R.string.factory_assistance_note_hint))
                state.reportStatus?.let { CockpitFeedback(UiText.get(it), error = it == R.string.factory_assistance_report_failed) }
                CockpitAction(UiText.get(R.string.vehicle_automation_diagnostics), diagnostics)
            }
        })
    }
    pending?.let { part ->
        CockpitAlertDialog({ pending = null },
            confirmButton = {
                CockpitTextAction(UiText.get(R.string.ui_187)) {
                    pending = null
                    model.change(part, true)
                }
            },
            dismissButton = { CockpitTextAction(UiText.get(R.string.ui_89)) { pending = null } },
            title = { Text(UiText.get(apolloPartTitle(part))) },
            text = { Text(UiText.get(apolloPartMessage(part))) })
    }
}

/** The three answers Controls can give the factory settings app, in the order the driver meets them. */
private data class ApolloPartRow(val id: String, val title: Int)

private val apolloPartRows = listOf(
    ApolloPartRow(FactoryAssistanceViewModel.APOLLO_SUBSCRIPTION, R.string.factory_assistance_part_subscription),
    ApolloPartRow(FactoryAssistanceViewModel.APOLLO_CONTROLLER, R.string.factory_assistance_part_controller),
    ApolloPartRow(FactoryAssistanceViewModel.APOLLO_EQUIPMENT, R.string.factory_assistance_part_equipment),
)

private fun apolloPartTitle(part: String) = apolloPartRows.first { it.id == part }.title

private fun apolloPartMessage(part: String) = when (part) {
    FactoryAssistanceViewModel.APOLLO_CONTROLLER -> R.string.factory_assistance_part_controller_message
    FactoryAssistanceViewModel.APOLLO_EQUIPMENT -> R.string.factory_assistance_part_equipment_message
    else -> R.string.factory_assistance_part_subscription_message
}

internal const val H97D_ASSISTANCE_MANUAL = "https://m.dsmp.voyah.com.cn/userguide/h97d/h97D-40.html"

/** A before and after pair needs two names, so the local date and minute separate the saved reports. */
private val REPORT_NAME: DateTimeFormatter = DateTimeFormatter.ofPattern("'apollo-report-'yyyy-MM-dd-HHmm'.json'")

private fun reportFileName(now: LocalDateTime = LocalDateTime.now()): String = REPORT_NAME.format(now)

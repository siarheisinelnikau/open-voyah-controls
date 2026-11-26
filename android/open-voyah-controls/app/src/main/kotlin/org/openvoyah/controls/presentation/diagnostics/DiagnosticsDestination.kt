package org.openvoyah.controls.presentation.diagnostics

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.openvoyah.controls.presentation.theme.CockpitEmbeddedHeading
import org.openvoyah.controls.presentation.theme.LocalCockpitEmbedded
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.withStarted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openvoyah.controls.BuildConfig
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.diagnostics.Diagnostics
import org.openvoyah.controls.diagnostics.LogLevel
import org.openvoyah.controls.platform.ui.copyReportToClipboard
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitAlertDialog
import org.openvoyah.controls.presentation.theme.CockpitBackdrop
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitHeading
import org.openvoyah.controls.presentation.theme.CockpitSwitch
import org.openvoyah.controls.presentation.theme.CockpitTextAction
import org.openvoyah.controls.presentation.theme.LocalCockpitLayout
import org.openvoyah.controls.presentation.theme.cockpitEntrance

internal const val DIAGNOSTIC_LIST_TAG = "diagnosticEntries"
private const val COPY_LIMIT_CHARS = 60_000
private const val NOTICE_MILLIS = 6_000L

/** Shows what the app recorded on this head unit, where Logcat is rarely within reach when something fails. */
@Composable
internal fun DiagnosticsDestination(back: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.diagnosticsActivity() }
    val app = activity.applicationContext
    val model = remember(activity) {
        ViewModelProvider(activity, viewModelFactory { initializer {
            DiagnosticsViewModel(object : DiagnosticSource {
                override fun read(): String = Diagnostics.journal()?.read().orEmpty()
                override fun clear(): Boolean = Diagnostics.journal()?.clear() == true
            })
        } })["diagnostics.destination", DiagnosticsViewModel::class.java]
    }
    val state by model.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val header = remember {
        "Open Voyah Controls ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) sdk=${Build.VERSION.SDK_INT} " +
            "product=${Build.PRODUCT}"
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            owner.lifecycle.withStarted { }
            model.notify(if (writeReport(app, uri, model.export(header))) DiagnosticNotice.SAVED else DiagnosticNotice.SAVE_FAILED)
        }
    }

    // The page shows the journal as it was when it opened; coming back to it reads the newer entries.
    LaunchedEffect(owner) {
        owner.lifecycle.currentStateFlow.collect { if (it == Lifecycle.State.RESUMED) model.refresh() }
    }
    state.notice?.let { notice ->
        LaunchedEffect(notice) {
            delay(NOTICE_MILLIS)
            model.notify(null)
        }
    }

    DiagnosticsScreen(state, back,
        refresh = model::refresh,
        problemsOnly = model::showProblemsOnly,
        copy = {
            scope.launch {
                val report = model.export(header).takeLast(COPY_LIMIT_CHARS)
                if (copyReportToClipboard(app, UiText.get(R.string.diagnostics_title), report)) model.notify(DiagnosticNotice.COPIED)
            }
        },
        save = {
            try { save.launch("open-voyah-controls-log.txt") }
            catch (_: RuntimeException) { model.notify(DiagnosticNotice.SAVE_FAILED) }
        },
        clear = { confirmClear = true },
    )

    if (confirmClear) CockpitAlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text(UiText.get(R.string.diagnostics_clear_title)) },
        text = { Text(UiText.get(R.string.diagnostics_clear_message)) },
        dismissButton = { CockpitTextAction(UiText.get(R.string.ui_89)) { confirmClear = false } },
        confirmButton = {
            CockpitAction(UiText.get(R.string.diagnostics_clear), {
                confirmClear = false
                model.clear()
            }, primary = true, destructive = true)
        },
    )
}

@Composable
internal fun DiagnosticsScreen(
    state: DiagnosticsViewModel.State,
    back: () -> Unit,
    refresh: () -> Unit,
    problemsOnly: (Boolean) -> Unit,
    copy: () -> Unit,
    save: () -> Unit,
    clear: () -> Unit,
) {
    val layout = LocalCockpitLayout.current
    val colors = MaterialTheme.colorScheme
    val spacing = Arrangement.spacedBy(layout.spacing(12.dp))
    val embedded = LocalCockpitEmbedded.current
    // Inside Settings → System the log keeps a bounded height, so the page around it can scroll.
    val page: @Composable (@Composable ColumnScope.() -> Unit) -> Unit = { body ->
        if (embedded) Column(Modifier.fillMaxWidth(), verticalArrangement = spacing) {
            CockpitEmbeddedHeading(UiText.get(R.string.diagnostics_title), UiText.get(R.string.diagnostics_subtitle))
            body()
        } else CockpitBackdrop {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = layout.spacing(22.dp), vertical = layout.spacing(14.dp)), verticalArrangement = spacing) {
                CockpitHeading(UiText.get(R.string.diagnostics_title), UiText.get(R.string.diagnostics_subtitle), back)
                body()
            }
        }
    }
    page {
            FlowRow(Modifier.fillMaxWidth().cockpitEntrance(0), horizontalArrangement = Arrangement.spacedBy(layout.spacing(8.dp)),
                verticalArrangement = Arrangement.spacedBy(layout.spacing(6.dp))) {
                CockpitAction(UiText.get(R.string.diagnostics_refresh), refresh)
                CockpitAction(UiText.get(R.string.diagnostics_copy), copy, enabled = state.entries.isNotEmpty())
                CockpitAction(UiText.get(R.string.diagnostics_save), save, enabled = state.entries.isNotEmpty())
                CockpitAction(UiText.get(R.string.diagnostics_clear), clear, enabled = state.entries.isNotEmpty(), destructive = true)
            }
            Box(Modifier.fillMaxWidth().cockpitEntrance(1)) {
                CockpitSwitch(UiText.get(R.string.diagnostics_problems_only).format(state.problems), state.problemsOnly,
                    guideId = R.string.diagnostics_problems_only, change = problemsOnly)
            }
            CockpitFeedback(state.notice?.let { UiText.get(it.message) },
                error = state.notice == DiagnosticNotice.SAVE_FAILED || state.notice == DiagnosticNotice.CLEAR_FAILED)
            Surface(Modifier.fillMaxWidth().then(if (embedded) Modifier.height(layout.spacing(380.dp)) else Modifier.weight(1f))
                .cockpitEntrance(2), shape = MaterialTheme.shapes.large, color = colors.surface,
                border = BorderStroke(1.dp, colors.outlineVariant)) {
                val visible = state.visible
                when {
                    state.loading -> DiagnosticHint(UiText.get(R.string.diagnostics_loading))
                    visible.isEmpty() -> DiagnosticHint(UiText.get(
                        if (state.problemsOnly && state.entries.isNotEmpty()) R.string.diagnostics_no_problems
                        else R.string.diagnostics_empty))
                    else -> LazyColumn(Modifier.fillMaxSize().testTag(DIAGNOSTIC_LIST_TAG)
                        .padding(horizontal = layout.spacing(14.dp), vertical = layout.spacing(8.dp)),
                        verticalArrangement = Arrangement.spacedBy(layout.spacing(6.dp))) {
                        // Positional items: two entries of one second can be equal, and equal keys end the list.
                        items(visible, contentType = { "entry" }) { entry -> DiagnosticRow(entry) }
                    }
                }
            }
        }
}

@Composable
private fun DiagnosticHint(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DiagnosticRow(entry: DiagnosticEntry) {
    val colors = MaterialTheme.colorScheme
    val mark = when (entry.level) {
        LogLevel.ERROR -> colors.error
        LogLevel.WARN -> colors.tertiary
        LogLevel.INFO, LogLevel.DEBUG -> colors.outline
    }
    val level = UiText.get(entry.level.label)
    // Event names are identifiers; with spaces they are words on the screen and for a screen reader alike.
    val title = entry.event.replace('_', ' ')
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$level, ${entry.time}, $title" },
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Surface(Modifier.padding(top = 7.dp).size(9.dp), shape = CircleShape, color = mark) { }
        Text(entry.time, Modifier.width(74.dp), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace,
            color = colors.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall,
                color = if (entry.level == LogLevel.ERROR) colors.error else colors.onSurface)
            if (entry.fields.isNotEmpty()) {
                Text(entry.fields, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    color = colors.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private val LogLevel.label: Int get() = when (this) {
    LogLevel.ERROR -> R.string.diagnostics_level_error
    LogLevel.WARN -> R.string.diagnostics_level_warning
    LogLevel.INFO, LogLevel.DEBUG -> R.string.diagnostics_level_info
}

private val DiagnosticNotice.message: Int get() = when (this) {
    DiagnosticNotice.COPIED -> R.string.diagnostics_copied
    DiagnosticNotice.SAVED -> R.string.diagnostics_saved
    DiagnosticNotice.SAVE_FAILED -> R.string.diagnostics_save_failed
    DiagnosticNotice.CLEARED -> R.string.diagnostics_cleared
    DiagnosticNotice.CLEAR_FAILED -> R.string.diagnostics_clear_failed
}

private suspend fun writeReport(app: Context, destination: Uri, report: String): Boolean = withContext(Dispatchers.IO) {
    try {
        val output = app.contentResolver.openOutputStream(destination, "wt") ?: return@withContext false
        output.use { it.write(report.toByteArray(Charsets.UTF_8)) }
        ControlsLog.info("diagnostics_export", "outcome", "saved", "bytes", report.length)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        ControlsLog.warn("diagnostics_export", failure, "outcome", "failed")
        false
    }
}

private tailrec fun Context.diagnosticsActivity(): ComponentActivity = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.diagnosticsActivity()
    else -> error("Diagnostics requires a ComponentActivity host")
}

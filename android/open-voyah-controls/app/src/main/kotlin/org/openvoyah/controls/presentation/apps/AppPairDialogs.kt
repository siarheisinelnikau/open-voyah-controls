package org.openvoyah.controls.presentation.apps

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.domain.display.AppPair
import org.openvoyah.controls.platform.apps.LocalApp
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitAppPicker
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.CockpitDialogWidth
import org.openvoyah.controls.presentation.theme.CockpitDialogWindowMotion
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitModal
import org.openvoyah.controls.presentation.theme.CockpitOptionDialog
import org.openvoyah.controls.presentation.theme.CockpitSwitch
import org.openvoyah.controls.presentation.theme.CockpitTextAction
import org.openvoyah.controls.presentation.theme.CockpitTextField

@Composable
internal fun rememberAppPairs(activity: ComponentActivity): AppPairViewModel = remember(activity) {
    ViewModelProvider(activity, viewModelFactory {
        initializer { AppPairViewModel(activity.applicationContext) }
    })[AppPairViewModel::class.java]
}

@Composable
internal fun AppPairsDialog(model: AppPairViewModel, apps: List<LocalApp>, close: () -> Unit) {
    val saved by model.savedPairs.collectAsStateWithLifecycle()
    val edit by model.edit.collectAsStateWithLifecycle()
    val pairs = saved?.getOrNull().orEmpty()
    val labels = remember(apps) { apps.associate { it.id to it.label } }
    var selected by remember { mutableStateOf<AppPair?>(null) }
    var editing by remember { mutableStateOf(false) }
    fun returnToList() {
        selected = null
        editing = false
        model.resetError()
    }
    Dialog(onDismissRequest = { if (!edit.busy) { if (editing) returnToList() else close() } },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CockpitDialogWindowMotion()
        Box(Modifier.padding(horizontal = 16.dp).widthIn(max = CockpitDialogWidth).fillMaxWidth()) {
            if (editing) PairEditor(selected, labels, edit, close = { if (!edit.busy) returnToList() },
                save = { model.save(it, ::returnToList) }, remove = { model.remove(it, ::returnToList) })
            else CockpitModal(UiText.get(R.string.app_pairs_title),
                headerAction = { CockpitTextAction(UiText.get(R.string.apps_desktop_done), close) },
                footer = {
                    CockpitAction(UiText.get(R.string.app_pair_add), {
                        model.resetError()
                        editing = true
                    }, enabled = saved?.isSuccess == true && pairs.size < AppPair.MAX_PAIRS, primary = true)
                }) {
                when {
                    saved == null -> CockpitFeedback(UiText.get(R.string.ui_80))
                    saved?.isFailure == true -> CockpitFeedback(UiText.get(R.string.app_pair_read_failed), error = true)
                    pairs.isEmpty() -> Text(UiText.get(R.string.app_pair_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> pairs.forEach { pair ->
                        val left = labels[pair.left] ?: missingLabel(pair.left)
                        val right = labels[pair.right] ?: missingLabel(pair.right)
                        CockpitChoice(pair.name, UiText.get(R.string.app_pair_summary)
                            .format(left, right, pair.leftPercent, 100 - pair.leftPercent)) {
                            model.resetError()
                            selected = pair
                            editing = true
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PairEditor(
    original: AppPair?,
    labels: Map<String, String>,
    edit: AppPairViewModel.Edit,
    close: () -> Unit,
    save: (AppPair) -> Unit,
    remove: (AppPair) -> Unit,
) {
    val id = remember(original) { original?.id ?: AppPairViewModel.newId() }
    var name by remember(id) { mutableStateOf(original?.name.orEmpty()) }
    var left by remember(id) { mutableStateOf(original?.left) }
    var right by remember(id) { mutableStateOf(original?.right) }
    var leftLabel by remember(id, labels) { mutableStateOf(left?.let { labels[it] ?: missingLabel(it) }) }
    var rightLabel by remember(id, labels) { mutableStateOf(right?.let { labels[it] ?: missingLabel(it) }) }
    var percent by remember(id) { mutableStateOf(original?.leftPercent ?: 50) }
    var dock by remember(id) { mutableStateOf(original?.inDock ?: false) }
    var choosingLeft by remember(id) { mutableStateOf<Boolean?>(null) }
    var choosingRatio by remember(id) { mutableStateOf(false) }
    var removing by remember(id) { mutableStateOf(false) }
    val side = choosingLeft
    if (side != null) {
        val other = if (side) right else left
        CockpitAppPicker(UiText.get(if (side) R.string.app_pair_left else R.string.app_pair_right),
            back = { choosingLeft = null }, select = { entry ->
                if (entry != null) {
                    val component = entry.component().flattenToString()
                    if (side) {
                        left = component
                        leftLabel = entry.label()
                    } else {
                        right = component
                        rightLabel = entry.label()
                    }
                }
                choosingLeft = null
            }, eligible = { AppPair.eligible(it) && it.substringBefore('/') != other?.substringBefore('/') })
        return
    }
    if (removing && original != null) {
        CockpitModal(UiText.get(R.string.app_pair_remove), footer = {
            CockpitAction(UiText.get(R.string.ui_89), { removing = false }, enabled = !edit.busy)
            CockpitAction(UiText.get(R.string.app_pair_remove), { remove(original) },
                enabled = !edit.busy, destructive = true)
        }) {
            Text(UiText.get(R.string.app_pair_remove_detail).format(original.name))
            if (edit.failed) CockpitFeedback(UiText.get(R.string.app_pair_save_failed), error = true)
        }
        return
    }
    val title = UiText.get(if (original == null) R.string.app_pair_add else R.string.app_pair_edit)
    val enabled = !edit.busy
    CockpitModal(title, headerAction = { CockpitTextAction(UiText.get(R.string.cockpit_back), close) }, footer = {
        if (original != null) {
            CockpitAction(UiText.get(R.string.app_pair_remove), { removing = true }, enabled = enabled, destructive = true)
        }
        CockpitAction(UiText.get(R.string.ui_104), {
            val cleanName = name.trim().ifEmpty {
                UiText.get(R.string.app_pair_default_name).format(leftLabel, rightLabel)
                    .filterNot(Char::isISOControl).take(AppPair.MAX_NAME).trim()
            }
            save(AppPair(id, cleanName, checkNotNull(left), checkNotNull(right), percent, dock))
        }, enabled = enabled && left != null && right != null, primary = true)
    }) {
        CockpitChoice(R.string.app_pair_left, leftLabel, enabled = enabled) { choosingLeft = true }
        CockpitChoice(R.string.app_pair_right, rightLabel, enabled = enabled) { choosingLeft = false }
        CockpitChoice(R.string.app_pair_proportion, ratio(percent), enabled = enabled) { choosingRatio = true }
        CockpitTextField(name, { if (it.length <= AppPair.MAX_NAME && it.none(Char::isISOControl)) name = it },
            UiText.get(R.string.app_pair_name), Modifier.fillMaxWidth(), enabled = enabled, guideId = R.string.app_pair_name)
        CockpitSwitch(R.string.app_pair_dock, dock, enabled = enabled) { dock = it }
        if (edit.failed) CockpitFeedback(UiText.get(R.string.app_pair_save_failed), error = true)
    }
    if (choosingRatio) {
        CockpitOptionDialog(UiText.get(R.string.app_pair_proportion), AppPair.proportions, percent, ::ratio,
            onSelect = { percent = it }, onDismiss = { choosingRatio = false })
    }
}

private fun missingLabel(component: String): String = UiText.get(R.string.app_pair_missing_target).format(component.substringBefore('/'))

private fun ratio(percent: Int): String = UiText.get(R.string.app_pair_ratio).format(percent, 100 - percent)

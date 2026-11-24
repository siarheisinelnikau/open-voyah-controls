package org.openvoyah.controls.presentation.theme

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.openvoyah.controls.InstalledApps
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.apps.AppGridPreferences
import org.openvoyah.controls.domain.apps.AppGridConfiguration
import org.openvoyah.controls.domain.apps.AppPickerChoices
import org.openvoyah.controls.platform.apps.AppCatalogCache
import org.openvoyah.controls.platform.apps.AppsCatalog
import org.openvoyah.controls.platform.apps.InstalledAppPermissions

/**
 * The shared "choose an app" list. Like All apps it leaves out firmware, maker and service apps unless the user
 * un-hid them, and apps the user hid; a switch shows them all for the rare case.
 */
@Composable
internal fun CockpitAppPicker(title: String, back: () -> Unit, select: (InstalledApps.Entry?) -> Unit,
                              defaultChoice: String? = null, backLabel: String = UiText.get(R.string.cockpit_back),
                              eligible: ((String) -> Boolean)? = null, noChoices: String? = null) {
    val app = LocalContext.current.applicationContext
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    fun finishSearch() { focus.clearFocus(); keyboard?.hide() }
    val apps by produceState<AppList>(AppList.Loading, app) { value = loadApps(app) }
    var query by remember { mutableStateOf("") }
    var showAll by remember { mutableStateOf(false) }
    CockpitModal(title, scroll = false, headerAction = { CockpitTextAction(backLabel) { finishSearch(); back() } }) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text(UiText.get(R.string.ui_113)) },
            singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { finishSearch() }))
        val ready = apps as? AppList.Ready
        val entries = ready?.entries.orEmpty()
        val candidates = remember(entries, eligible) {
            if (eligible == null) entries else entries.filter { eligible(it.component().flattenToString()) }
        }
        val hiddenByDefault: (InstalledApps.Entry) -> Boolean = { it.component().flattenToString() in ready?.hiddenByDefault.orEmpty() }
        val component: (InstalledApps.Entry) -> String = { it.component().flattenToString() }
        val choices = remember(candidates, ready, showAll) {
            AppPickerChoices.offered(candidates, component, hiddenByDefault, ready?.grid, showAll)
        }
        val canShowAll = remember(candidates, ready) {
            AppPickerChoices.hidesAny(candidates, component, hiddenByDefault, ready?.grid)
        }
        if (canShowAll) CockpitSwitch(R.string.app_picker_show_all, showAll) { showAll = it }
        val labelCounts = remember(choices) { choices.groupingBy { it.label() to it.component().packageName }.eachCount() }
        val search = query.trim()
        val visible = remember(choices, search) {
            choices.filter { it.label().contains(search, ignoreCase = true) ||
                it.component().flattenToString().contains(search, ignoreCase = true) }
        }
        key(search) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 310.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                if (search.isEmpty()) defaultChoice?.let { label ->
                    item { CockpitChoice(label) { finishSearch(); select(null) } }
                }
                when {
                    apps == AppList.Loading -> item { CockpitFeedback(UiText.get(R.string.binding_apps_loading)) }
                    apps == AppList.Failed || entries.isEmpty() -> item { CockpitFeedback(UiText.get(R.string.ui_112)) }
                    candidates.isEmpty() -> item { CockpitFeedback(noChoices ?: UiText.get(R.string.ui_112)) }
                    choices.isEmpty() -> item { CockpitFeedback(UiText.get(R.string.app_picker_all_hidden)) }
                    visible.isEmpty() -> item { CockpitFeedback(UiText.get(R.string.binding_apps_no_match)) }
                    else -> items(visible, key = { it.component().flattenToString() }) { entry ->
                        val component = entry.component()
                        val detail = if (labelCounts.getValue(entry.label() to component.packageName) > 1) {
                            component.flattenToShortString()
                        } else component.packageName
                        CockpitChoice(entry.label(), detail, modifier = Modifier.testTag("appPicker:${component.flattenToString()}")) {
                            finishSearch()
                            select(entry)
                        }
                    }
                }
            }
        }
    }
}

private sealed interface AppList {
    data object Loading : AppList
    data object Failed : AppList
    /** [hiddenByDefault] holds the components All apps hides by default; [grid] holds the user's Hide choices. */
    data class Ready(val entries: List<InstalledApps.Entry>, val hiddenByDefault: Set<String> = emptySet(),
                     val grid: AppGridConfiguration? = null) : AppList
}

private suspend fun loadApps(app: Context): AppList = try {
    withContext(Dispatchers.IO) {
        val entries = InstalledApps.list(app)
        // The warm grid catalog classifies each entry. It leaves out Controls itself and Android Settings, and may
        // not hold an app installed a moment ago; those count as hidden by default only when they are system apps.
        val catalog = AppCatalogCache.snapshot.value ?: AppsCatalog(app, InstalledAppPermissions(app)).load(launcherOnly = true)
        val known = catalog.associateBy { it.id }
        val hidden = entries.mapNotNullTo(HashSet()) { entry ->
            val id = entry.component().flattenToString()
            id.takeIf { known[id]?.hiddenByDefault ?: app.isSystemApp(entry.component().packageName) }
        }
        val grid = AppCatalogCache.settings.value ?: AppGridPreferences(app).observeSettings().first().getOrNull()
        AppList.Ready(entries, hidden, grid)
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: RuntimeException) {
    AppList.Failed
}

private fun Context.isSystemApp(packageName: String): Boolean = packageName != this.packageName && try {
    packageManager.getApplicationInfo(packageName, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0
} catch (_: PackageManager.NameNotFoundException) {
    false
}

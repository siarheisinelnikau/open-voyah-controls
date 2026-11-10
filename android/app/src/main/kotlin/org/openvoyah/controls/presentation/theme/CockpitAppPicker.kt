package org.openvoyah.controls.presentation.theme

import android.content.Context
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openvoyah.controls.InstalledApps
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText

@Composable
internal fun CockpitAppPicker(title: String, back: () -> Unit, select: (InstalledApps.Entry?) -> Unit,
                              defaultChoice: String? = null, backLabel: String = UiText.get(R.string.cockpit_back)) {
    val app = LocalContext.current.applicationContext
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    fun finishSearch() { focus.clearFocus(); keyboard?.hide() }
    val apps by produceState<AppList>(AppList.Loading, app) { value = loadApps(app) }
    var query by remember { mutableStateOf("") }
    CockpitModal(title, scroll = false, headerAction = { CockpitTextAction(backLabel) { finishSearch(); back() } }) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text(UiText.get(R.string.ui_113)) },
            singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { finishSearch() }))
        val entries = (apps as? AppList.Ready)?.entries.orEmpty()
        val visible = remember(entries, query) {
            val search = query.trim()
            entries.filter { it.label().contains(search, ignoreCase = true) ||
                it.component().flattenToString().contains(search, ignoreCase = true) }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 310.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            defaultChoice?.let { label -> item { CockpitChoice(label) { finishSearch(); select(null) } } }
            when {
                apps == AppList.Loading -> item { CockpitFeedback(UiText.get(R.string.binding_apps_loading)) }
                apps == AppList.Failed || entries.isEmpty() -> item { CockpitFeedback(UiText.get(R.string.ui_112)) }
                visible.isEmpty() -> item { CockpitFeedback(UiText.get(R.string.binding_apps_no_match)) }
                else -> items(visible, key = { it.component().flattenToString() }) { entry ->
                    CockpitChoice(entry.label(), entry.component().packageName) { finishSearch(); select(entry) }
                }
            }
        }
    }
}

private sealed interface AppList {
    data object Loading : AppList
    data object Failed : AppList
    data class Ready(val entries: List<InstalledApps.Entry>) : AppList
}

private suspend fun loadApps(app: Context): AppList = try {
    AppList.Ready(withContext(Dispatchers.IO) { InstalledApps.list(app) })
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: RuntimeException) {
    AppList.Failed
}

package org.openvoyah.controls.presentation.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import org.openvoyah.controls.presentation.theme.CockpitAlertDialog as AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.voice.CloudVoiceSettings
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitReveal
import org.openvoyah.controls.presentation.theme.CockpitSwitch

@Composable
internal fun OnlineVoiceDialog(
    configured: Boolean,
    saving: Boolean,
    failed: Boolean,
    close: () -> Unit,
    save: (Boolean, String) -> Unit,
) {
    var enabled by remember { mutableStateOf(configured) }
    var key by remember { mutableStateOf("") }
    var editingKey by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val entered = key.trim()
    val valid = !enabled || (entered.isEmpty() && configured) || CloudVoiceSettings.validKey(entered)
    val submit = {
        if (valid && !saving) {
            keyboard?.hide()
            save(enabled, entered)
        }
    }
    AlertDialog(
        onDismissRequest = { if (!saving) close() },
        title = { Text(UiText.get(R.string.voice_online_title)) },
        text = {
            Column(Modifier.heightIn(max = 290.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CockpitReveal(!enabled || !editingKey) {
                    Text(UiText.get(R.string.voice_online_explain))
                }
                CockpitSwitch(UiText.get(R.string.voice_online_switch), enabled, !saving) { enabled = it }
                CockpitReveal(enabled) {
                    // The shared text field has no visual transformation, and the key must stay masked.
                    OutlinedTextField(
                        value = key, onValueChange = { if (it.length <= 220) key = it },
                        modifier = Modifier.fillMaxWidth().onFocusChanged { editingKey = it.isFocused },
                        label = { Text(UiText.get(R.string.voice_online_key)) },
                        enabled = !saving, singleLine = true, shape = MaterialTheme.shapes.medium,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        isError = !valid && key.isNotEmpty(),
                        supportingText = {
                            when {
                                !valid && key.isNotEmpty() -> Text(UiText.get(R.string.voice_online_key_invalid))
                                configured -> Text(UiText.get(R.string.voice_online_key_saved))
                            }
                        },
                    )
                }
                if (failed) Text(UiText.get(R.string.voice_online_save_failed))
            }
        },
        confirmButton = { CockpitAction(UiText.get(R.string.ui_104), submit, enabled = valid && !saving, primary = true) },
        // An outlined action rather than a text one: Cancel must look unavailable while the key is being saved.
        dismissButton = { CockpitAction(UiText.get(R.string.ui_89), close, enabled = !saving) },
    )
}

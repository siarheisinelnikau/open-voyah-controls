package org.openvoyah.controls.presentation.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
                AnimatedVisibility(!enabled || !editingKey) {
                    Text(UiText.get(R.string.voice_online_explain))
                }
                VoiceOptionToggle(UiText.get(R.string.voice_online_switch), enabled, !saving) { enabled = it }
                if (enabled) {
                    OutlinedTextField(
                        value = key, onValueChange = { if (it.length <= 220) key = it },
                        modifier = Modifier.fillMaxWidth().onFocusChanged { editingKey = it.isFocused },
                        label = { Text(UiText.get(R.string.voice_online_key)) },
                        enabled = !saving, singleLine = true,
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
        confirmButton = {
            Button(submit, Modifier.heightIn(min = 50.dp), enabled = valid && !saving) {
                Text(UiText.get(R.string.ui_104))
            }
        },
        dismissButton = {
            TextButton(close, Modifier.heightIn(min = 50.dp), enabled = !saving) { Text(UiText.get(R.string.ui_89)) }
        },
    )
}

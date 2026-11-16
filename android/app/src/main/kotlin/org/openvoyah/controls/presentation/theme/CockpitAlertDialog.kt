package org.openvoyah.controls.presentation.theme

import android.view.ViewParent
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import org.openvoyah.controls.R

/** Compose dialog content with the same bounded enter/exit motion as the other app dialogs. */
@Composable
internal fun CockpitAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
) {
    AlertDialog(onDismissRequest = onDismissRequest, confirmButton = confirmButton,
        dismissButton = dismissButton, title = {
            val view = LocalView.current
            DisposableEffect(view) {
                var parent: ViewParent? = view.parent
                while (parent != null && parent !is DialogWindowProvider) parent = parent.parent
                (parent as? DialogWindowProvider)?.window?.setWindowAnimations(R.style.CockpitDialogMotion)
                // This window belongs to the dialog. Keep the exit animation until it is dismissed.
                onDispose { }
            }
            title()
        }, text = text)
}

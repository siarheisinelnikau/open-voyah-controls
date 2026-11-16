package org.openvoyah.controls.presentation.cluster

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.withStarted
import kotlinx.coroutines.launch
import org.openvoyah.controls.ClusterMaintenanceAdapter
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.presentation.maintenance.ClusterAction
import org.openvoyah.controls.presentation.maintenance.ClusterScreen
import org.openvoyah.controls.presentation.maintenance.MaintenanceViewModel
import org.openvoyah.controls.presentation.maintenance.MaintenanceWork
import org.openvoyah.controls.presentation.theme.CockpitDialogs

/** File selection and confirmation belong to this route; a confirmed transaction can outlive it. */
@Composable
internal fun ClusterDestination(back: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.componentActivity() }
    val app = context.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val adapter = remember(app) { ClusterMaintenanceAdapter(app) }
    val model = remember(activity) {
        ViewModelProvider(activity, viewModelFactory {
            initializer { MaintenanceViewModel(ClusterMaintenance.work(adapter)) }
        }).get("cluster-maintenance", MaintenanceViewModel::class.java)
    }
    val dialogs = remember(activity) { CockpitDialogs(activity) }
    DisposableEffect(lifecycleOwner, dialogs) {
        val observer = LifecycleEventObserver { _, _ ->
            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) dialogs.dismiss()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            dialogs.dismiss()
        }
    }
    val state by model.state.collectAsStateWithLifecycle(lifecycleOwner = lifecycleOwner)
    val routeScope = rememberCoroutineScope()
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) routeScope.launch {
            lifecycleOwner.lifecycle.withStarted { model.run { adapter.importPackage(uri) } }
        }
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) routeScope.launch {
            lifecycleOwner.lifecycle.withStarted { model.run { adapter.exportBackup(uri) } }
        }
    }

    fun confirm(label: Int, operation: () -> String) {
        val title = UiText.get(label)
        dialogs.message(title, UiText.get(R.string.cluster_confirm), title, Runnable {
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) model.run(operation)
        }, UiText.get(R.string.ui_89))
    }

    fun select(action: ClusterAction) {
        if (model.state.value.working || !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        try {
            when (action) {
                ClusterAction.INSPECT -> model.run(adapter::inspect)
                ClusterAction.IMPORT -> importFile.launch(arrayOf("*/*"))
                ClusterAction.PREPARE -> model.run(adapter::prepare)
                ClusterAction.EXPORT -> exportFile.launch("ovc-cluster-backup.zip")
                ClusterAction.APPLY -> confirm(R.string.cluster_apply, adapter::apply)
                ClusterAction.RESTORE -> confirm(R.string.cluster_restore, adapter::restore)
            }
        } catch (error: RuntimeException) {
            model.report(error.message ?: UiText.get(R.string.ui_85))
        }
    }

    ClusterScreen(state, back, ::select)
}

private tailrec fun Context.componentActivity(): ComponentActivity = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.componentActivity()
    else -> error("Cluster destination requires a ComponentActivity")
}

private object ClusterMaintenance {
    private var current: MaintenanceWork? = null

    @Synchronized
    fun work(adapter: ClusterMaintenanceAdapter): MaintenanceWork = current ?: MaintenanceWork(
        adapter.status(), { UiText.get(R.string.cluster_working) },
        { UiText.get(R.string.cluster_error) + "\n" + it.message.orEmpty() }, adapter::persistStatus,
    ).also { current = it }
}

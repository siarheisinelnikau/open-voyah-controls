package org.openvoyah.controls

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.io.IOException
import org.openvoyah.controls.presentation.maintenance.ClusterAction
import org.openvoyah.controls.presentation.maintenance.ClusterScreen
import org.openvoyah.controls.presentation.maintenance.MaintenanceViewModel
import org.openvoyah.controls.presentation.maintenance.MaintenanceWork
import org.openvoyah.controls.presentation.theme.CockpitDialogs
import org.openvoyah.controls.presentation.theme.ControlsTheme

class ClusterActivity : ComponentActivity() {
    private val controller by lazy { ClusterController(applicationContext) }
    private val model: MaintenanceViewModel by viewModels {
        viewModelFactory { initializer { MaintenanceViewModel(clusterWork(applicationContext)) } }
    }
    private val dialogs = CockpitDialogs(this)
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val app = applicationContext
            model.run {
                synchronized(ClusterController.LOCK) {
                    val input = app.contentResolver.openInputStream(uri) ?: throw IOException(UiText.get(R.string.ui_123))
                    input.use { UiText.get(R.string.cluster_imported) + "\n" + ClusterPackage.importZip(app, it).firmware() }
                }
            }
        }
    }
    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            val resolver = applicationContext.contentResolver
            val cluster = controller
            model.run {
                val output = resolver.openOutputStream(uri, "wt") ?: throw IOException(UiText.get(R.string.ui_123))
                output.use(cluster::exportBackup)
                UiText.get(R.string.cluster_exported)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ControlsTheme { ClusterScreen(model.state.collectAsStateWithLifecycle().value, ::finish, ::select) } }
    }

    private fun select(action: ClusterAction) {
        if (model.state.value.working) return
        try {
            when (action) {
                ClusterAction.INSPECT -> model.run(controller::inspect)
                ClusterAction.IMPORT -> importFile.launch(arrayOf("*/*"))
                ClusterAction.PREPARE -> model.run(controller::prepare)
                ClusterAction.EXPORT -> exportFile.launch("ovc-cluster-backup.zip")
                ClusterAction.APPLY -> confirm(R.string.cluster_apply, controller::apply)
                ClusterAction.RESTORE -> confirm(R.string.cluster_restore, controller::restore)
            }
        } catch (error: RuntimeException) {
            model.report(error.message ?: UiText.get(R.string.ui_85))
        }
    }

    private fun confirm(label: Int, operation: () -> String) {
        val title = UiText.get(label)
        dialogs.message(title, UiText.get(R.string.cluster_confirm), title, Runnable { model.run(operation) }, UiText.get(R.string.ui_89))
    }

    override fun onDestroy() { dialogs.dismiss(); super.onDestroy() }

    private companion object {
        private var work: MaintenanceWork? = null

        fun clusterWork(context: Context): MaintenanceWork {
            work?.let { return it }
            val preferences = context.getSharedPreferences("cluster", Context.MODE_PRIVATE)
            return MaintenanceWork(preferences.getString("status", null), { UiText.get(R.string.cluster_working) },
                { UiText.get(R.string.cluster_error) + "\n" + it.message.orEmpty() },
                { preferences.edit().putString("status", it).commit() }).also { work = it }
        }
    }
}

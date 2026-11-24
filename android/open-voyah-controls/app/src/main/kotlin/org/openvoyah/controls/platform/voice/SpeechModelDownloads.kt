package org.openvoyah.controls.platform.voice

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.openvoyah.controls.data.voice.SpeechModel
import org.openvoyah.controls.data.voice.SpeechModelCatalog
import org.openvoyah.controls.data.voice.SpeechModelStore
import org.openvoyah.controls.diagnostics.ControlsLog

/**
 * Downloads that outlive the Recognition dialog: one at a time per model, with progress for the settings and an entry
 * in the diagnostic journal for each outcome. A finished download prepares the selected engine again.
 */
internal object SpeechModelDownloads {
    data class Progress(val done: Long, val total: Long)

    enum class Failure { NETWORK, STORAGE, VERIFICATION }

    data class State(
        val installed: Set<String> = emptySet(),
        val active: Map<String, Progress> = emptyMap(),
        val failed: Map<String, Failure> = emptyMap(),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()
    private val mutable = MutableStateFlow(State())
    val state: StateFlow<State> = mutable.asStateFlow()

    fun refresh(context: Context) {
        val store = SpeechModels.store(context)
        mutable.update { it.copy(installed = SpeechModelCatalog.ALL.filter(store::installed).map { model -> model.id }.toSet()) }
    }

    fun download(context: Context, model: SpeechModel) {
        val app = context.applicationContext
        synchronized(jobs) {
            if (model.id in jobs || SpeechModels.store(app).installed(model)) return
            mutable.update { it.copy(active = it.active + (model.id to Progress(0, model.downloadSize)), failed = it.failed - model.id) }
            jobs[model.id] = scope.launch {
                val started = SystemClock.elapsedRealtime()
                var downloaded = 0L
                val outcome = try {
                    runInterruptible {
                        SpeechModels.store(app).install(model, { done, total ->
                            downloaded = done
                            mutable.update { it.copy(active = it.active + (model.id to Progress(done, total))) }
                        }) { isActive && !Thread.currentThread().isInterrupted }
                    }
                    "installed"
                } catch (_: CancellationException) {
                    "cancelled"
                } catch (_: SpeechModelStore.Cancelled) {
                    "cancelled"
                } catch (failure: SpeechModelStore.StorageFull) {
                    fail(model, Failure.STORAGE, failure)
                    "storage_full"
                } catch (failure: SpeechModelStore.Mismatch) {
                    fail(model, Failure.VERIFICATION, failure)
                    "verification_failed"
                } catch (failure: Exception) {
                    fail(model, Failure.NETWORK, failure)
                    "failed"
                }
                ControlsLog.info("voice_model_download", "model", model.id, "outcome", outcome, "bytes", downloaded,
                    "elapsed_ms", SystemClock.elapsedRealtime() - started)
                mutable.update { it.copy(active = it.active - model.id) }
                refresh(app)
                synchronized(jobs) { jobs.remove(model.id) }
                if (outcome == "installed") SpeechModels.warmUp(app, "download")
            }
        }
    }

    fun cancel(model: SpeechModel) {
        synchronized(jobs) { jobs[model.id]?.cancel() }
    }

    fun delete(context: Context, model: SpeechModel) {
        val app = context.applicationContext
        cancel(model)
        scope.launch {
            try {
                SpeechModels.forget(app, model)
                ControlsLog.info("voice_model_download", "model", model.id, "outcome", "deleted")
            } catch (failure: Exception) {
                ControlsLog.warn("voice_model_download", failure, "model", model.id, "outcome", "delete_failed")
            }
            refresh(app)
            SpeechModels.warmUp(app, "settings")
        }
    }

    private fun fail(model: SpeechModel, failure: Failure, cause: Exception) {
        ControlsLog.warn("voice_model_download", cause, "model", model.id, "stage", failure.name.lowercase())
        mutable.update { it.copy(failed = it.failed + (model.id to failure)) }
    }
}

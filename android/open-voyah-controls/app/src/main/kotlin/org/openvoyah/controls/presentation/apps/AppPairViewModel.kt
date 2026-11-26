package org.openvoyah.controls.presentation.apps

import android.app.Activity
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.display.AppPairPreferences
import org.openvoyah.controls.data.frida.FridaBridge
import org.openvoyah.controls.domain.display.AppPair
import org.openvoyah.controls.platform.apps.AppsPlatform
import org.openvoyah.controls.platform.apps.LauncherShortcuts
import java.util.UUID

internal class AppPairViewModel(private val app: Context) : ViewModel() {
    private val preferences = AppPairPreferences(app)
    val savedPairs = preferences.observe().flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val edit = MutableStateFlow(Edit())
    private var opening = false

    data class Edit(val busy: Boolean = false, val failed: Boolean = false)

    fun save(pair: AppPair, onSaved: () -> Unit) = write(onSaved) { preferences.save(pair) }

    fun remove(pair: AppPair, onSaved: () -> Unit) = write(onSaved) { preferences.remove(pair.id) }

    fun resetError() {
        if (!edit.value.busy) edit.value = Edit()
    }

    private fun write(onSaved: () -> Unit, operation: () -> Unit) {
        if (edit.value.busy) return
        edit.value = Edit(busy = true)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { operation() }
                edit.value = Edit()
                onSaved()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                edit.value = Edit(failed = true)
            }
        }
    }

    fun open(activity: Activity, pair: AppPair, foreground: () -> Boolean, afterStart: () -> Unit = {}) {
        if (!foreground() || opening) return
        opening = true
        viewModelScope.launch {
            try {
                openPair(activity, pair, foreground, afterStart)?.let { message ->
                    Toast.makeText(app, UiText.get(message), Toast.LENGTH_LONG).show()
                }
            } finally {
                opening = false
            }
        }
    }

    private suspend fun openPair(activity: Activity, pair: AppPair, foreground: () -> Boolean, afterStart: () -> Unit): Int? {
        val bridge = FridaBridge(app)
        val ready = withContext(Dispatchers.IO) { runCatching { bridge.windowPairReady() }.getOrDefault(false) }
        if (!foreground()) return null
        if (!ready) return R.string.app_pair_service_unavailable
        val apps = withContext(Dispatchers.IO) {
            val launcher = LauncherShortcuts(app)
            listOfNotNull(launcher.find(pair.left), launcher.find(pair.right))
        }
        if (!foreground()) return null
        if (apps.size != 2) return R.string.app_pair_missing
        val token = newId()
        if (!runCatching { AppsPlatform(activity).launchPair(apps, token, pair.leftPercent) }.getOrDefault(false)) {
            return R.string.app_pair_launch_failed
        }
        afterStart()
        val receipt = withContext(Dispatchers.IO) { runCatching { bridge.windowPairResult(token) }.getOrNull() }
        return when (receipt) {
            "applied" -> null
            "no_space" -> R.string.app_pair_no_space
            "unsupported_window", "unsupported_task" -> R.string.app_pair_unsupported
            "rollback_failed" -> R.string.app_pair_recovery_failed
            "incomplete", null, "" -> R.string.app_pair_unconfirmed
            else -> R.string.app_pair_launch_failed
        }
    }

    companion object {
        fun supported(): Boolean = Build.VERSION.SDK_INT == 30 && FridaBridge.windowHookSupported()

        fun newId(): String = UUID.randomUUID().toString().replace("-", "")
    }
}

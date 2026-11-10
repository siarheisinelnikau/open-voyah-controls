package org.openvoyah.controls.presentation.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openvoyah.controls.R
import org.openvoyah.controls.platform.apps.AppsCatalog
import org.openvoyah.controls.platform.apps.LocalApp

internal class AppsViewModel(
    private val catalog: AppsCatalog,
) : ViewModel() {
    data class State(
        val apps: List<LocalApp> = emptyList(),
        val loading: Boolean = true,
        val status: Int? = null,
    )

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private var refresh: Job? = null

    fun refresh() {
        refresh?.cancel()
        refresh = viewModelScope.launch {
            try {
                val apps = withContext(Dispatchers.IO) { catalog.load() }
                mutableState.update { it.copy(apps = apps, loading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                mutableState.update { it.copy(loading = false, status = R.string.apps_load_failed) }
            }
        }
    }

    fun status(message: Int) = mutableState.update { it.copy(status = message) }
}

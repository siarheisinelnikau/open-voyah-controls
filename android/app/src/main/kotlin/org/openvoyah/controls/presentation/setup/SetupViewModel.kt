package org.openvoyah.controls.presentation.setup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import org.openvoyah.controls.R
import org.openvoyah.controls.domain.setup.SetupProgress

class SetupViewModel(app: Application) : AndroidViewModel(app) {
    /** [message] is the summary: the final result once [finished], otherwise unused while the rows run. */
    data class State(
        val progress: SetupProgress = SetupProgress(),
        val message: Int = R.string.setup_check_root,
        val finished: Boolean = false,
    ) {
        /** Read from the result text alone, so the check and its report to the installer stay as they are. */
        val failed: Boolean get() = finished && message in failures
    }

    /** The check itself runs in [SetupRuns], so it still reports to the installer if this page goes away. */
    val state = SetupRuns.state

    fun start(nonce: String) = SetupRuns.start(getApplication(), nonce)

    fun retry() = SetupRuns.retry(getApplication())
}

private val failures = setOf(R.string.setup_failed, R.string.setup_root_failed, R.string.setup_voice_failed,
    R.string.setup_space_failed, R.string.setup_handlers_failed)

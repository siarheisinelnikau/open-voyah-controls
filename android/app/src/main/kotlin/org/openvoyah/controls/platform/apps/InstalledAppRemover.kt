package org.openvoyah.controls.platform.apps

import android.content.Context
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.apps.RootAppRemover
import org.openvoyah.controls.data.frida.ProductionRootAccess
import org.openvoyah.controls.diagnostics.ControlsLog

/** The outcome of uninstalling an app. */
internal sealed interface AppRemoval {
    /** Android no longer lists the package. */
    data object Removed : AppRemoval
    /** The app is part of the firmware or holds a car UI role, or it changed into such an app. */
    data object Refused : AppRemoval
    /** The installed package is no longer the one the page showed. */
    data object PackageChanged : AppRemoval
    /** The package is still installed; [reason] is the root shell's localized refusal, never transport text. */
    data class Failed(val reason: String?) : AppRemoval
}

/** The uninstall action of the app settings page; tests replace it to remove nothing. */
internal interface AppRemoveControl {
    /** Whether the page may offer to uninstall the app: an app installed on top of the firmware that Controls manages. */
    fun canRemove(packageName: String): Boolean
    fun remove(identity: InstalledAppIdentity): AppRemoval

    /** For pages that offer no uninstall. */
    object None : AppRemoveControl {
        override fun canRemove(packageName: String) = false
        override fun remove(identity: InstalledAppIdentity): AppRemoval = AppRemoval.Refused
    }
}

/**
 * Uninstalls through the root shell, because firmware without Android's app info has no uninstall screen either.
 * The same rule as for permissions applies: never Controls, a firmware or maker app, the launcher, the keyboard, a
 * device administrator or an app with a system identity.
 */
internal class InstalledAppRemover(
    context: Context,
    private val shell: RootAppRemover = RootAppRemover(context),
) : AppRemoveControl {
    private val provenance = AppInstallProvenance(context)
    private val facts = AppControlFacts(context)

    override fun canRemove(packageName: String): Boolean {
        val info = provenance.packageInfo(packageName) ?: return false
        return facts.refusal(info) == null && provenance.identity(info) != null
    }

    override fun remove(identity: InstalledAppIdentity): AppRemoval {
        val info = provenance.packageInfo(identity.packageName)
        val outcome = when {
            info == null || provenance.identity(info) != identity -> AppRemoval.PackageChanged
            facts.refusal(info) != null -> AppRemoval.Refused
            else -> {
                var reason: String? = null
                try {
                    shell.uninstall(identity.packageName)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    // The transport can fail after Android removed the app; Android's package list decides.
                    ControlsLog.warn("app_uninstall", failure, "outcome", "failed")
                    reason = refusalText(failure)
                }
                if (provenance.packageInfo(identity.packageName) == null) AppRemoval.Removed else AppRemoval.Failed(reason)
            }
        }
        ControlsLog.info("app_uninstall", "outcome", outcome.javaClass.simpleName)
        return outcome
    }

    private fun refusalText(failure: Exception): String? = when {
        failure is ProductionRootAccess.Refused -> failure.message
        failure is IOException && failure.message == UiText.get(R.string.production_broker_unavailable) -> failure.message
        else -> null
    }
}

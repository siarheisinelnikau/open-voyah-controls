package org.openvoyah.controls.platform.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.apps.RootAppStopper
import org.openvoyah.controls.data.frida.ProductionRootAccess
import org.openvoyah.controls.diagnostics.ControlsLog

/** The outcome of closing an app through Android's force stop. */
internal sealed interface AppStop {
    /** No process of the app is left. */
    data object Stopped : AppStop
    /** Android stopped the app, but something in the system started a process of it again right away. */
    data object Restarted : AppStop
    /** The app is part of the firmware, holds a car UI role or keeps a service on, or it changed into such an app. */
    data object Refused : AppStop
    /** The installed package is no longer the one the page showed. */
    data object PackageChanged : AppStop
    /** The root shell refused or failed; [reason] is its localized refusal, never transport text. */
    data class Failed(val reason: String?) : AppStop
}

/** The close action of the app settings page; tests replace it to close nothing. */
internal interface AppStopControl {
    /** Whether the page may offer to close the app now: Controls manages it and Android does not hold it stopped. */
    fun canStop(packageName: String): Boolean
    fun stop(identity: InstalledAppIdentity): AppStop
}

internal class InstalledAppStopper(
    context: Context,
    private val shell: RootAppStopper = RootAppStopper(context),
) : AppStopControl {
    private val provenance = AppInstallProvenance(context)
    private val facts = AppControlFacts(context)

    override fun canStop(packageName: String): Boolean {
        val info = provenance.packageInfo(packageName) ?: return false
        // Android sets the stopped flag after a force stop and clears it on the next launch, like its own app info.
        return !stopped(packageName) && facts.stopRefusal(info) == null && provenance.identity(info) != null
    }

    override fun stop(identity: InstalledAppIdentity): AppStop {
        val info = provenance.packageInfo(identity.packageName)
        val outcome = when {
            info == null || provenance.identity(info) != identity -> AppStop.PackageChanged
            facts.stopRefusal(info) != null -> AppStop.Refused
            else -> try {
                when (shell.stop(androidUserId(identity.uid), identity.packageName)) {
                    true -> AppStop.Stopped
                    false -> AppStop.Restarted
                    null -> if (stopped(identity.packageName)) AppStop.Stopped else AppStop.Failed(null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                ControlsLog.warn("app_stop", failure, "outcome", "failed")
                // The transport can fail after Android stopped the app; the stopped flag shows it did. Closing was
                // offered only while the flag was clear.
                if (stopped(identity.packageName)) AppStop.Stopped else AppStop.Failed(refusalText(failure))
            }
        }
        ControlsLog.info("app_stop", "outcome", outcome.javaClass.simpleName)
        return outcome
    }

    private fun stopped(packageName: String): Boolean {
        val app = provenance.packageInfo(packageName)?.applicationInfo ?: return false
        return app.flags and ApplicationInfo.FLAG_STOPPED != 0
    }

    /** The localized reason a refused root command carries; transport messages are English and stay in the log. */
    private fun refusalText(failure: Exception): String? = when {
        failure is ProductionRootAccess.Refused -> failure.message
        failure is IOException && failure.message == UiText.get(R.string.production_broker_unavailable) -> failure.message
        else -> null
    }
}

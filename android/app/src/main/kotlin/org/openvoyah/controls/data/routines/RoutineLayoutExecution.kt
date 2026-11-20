package org.openvoyah.controls.data.routines

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openvoyah.controls.data.apps.DesktopLayoutRevision
import org.openvoyah.controls.data.apps.DesktopLayoutsPreferences
import org.openvoyah.controls.data.settings.LocalSettingsArchive
import org.openvoyah.controls.domain.routines.NamedRoutine
import org.openvoyah.controls.domain.routines.RoutineStartupRequest

internal class RoutineLayoutExecution(context: Context) {
    private val routines = RoutinePreferences(context)
    private val layouts = DesktopLayoutsPreferences(context)
    private val archive by lazy { LocalSettingsArchive(context) }

    suspend fun prepare(id: String): DesktopLayoutRevision = withContext(Dispatchers.IO) { layouts.revision(id) }

    suspend fun runManual(routine: NamedRoutine, layout: DesktopLayoutRevision, permit: RoutineLayoutPermit): Boolean =
        apply(layout, permit, "manual") { write ->
            routines.withCurrentPlan(routine) { plan ->
                check(plan.desktopLayoutId == layout.id)
                write.run()
                true
            }
        }

    suspend fun runStartup(request: RoutineStartupRequest, layout: DesktopLayoutRevision, permit: RoutineLayoutPermit): Boolean =
        apply(layout, permit, "startup") { write ->
            routines.runStartup(request) { plan ->
                check(plan.desktopLayoutId == layout.id)
                write.run()
            }
        }

    private suspend fun apply(
        layout: DesktopLayoutRevision,
        permit: RoutineLayoutPermit,
        mode: String,
        dispatch: LocalSettingsArchive.LayoutDispatch,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            permit.requireActive()
            // The archive owns the outer lock before dispatch takes the routine store lock.
            val applied = archive.applySavedDesktopLayout(layout, permit::tryCommit) { write ->
                permit.requireActive()
                dispatch.tryApply(write)
            }
            val outcome = if (applied) "applied" else "no_longer_eligible"
            Log.i("OpenVoyahControls", "event=routine_layout mode=$mode outcome=$outcome")
            applied
        } catch (cancelled: CancellationException) {
            if (cancelled.suppressed.isEmpty()) Log.i("OpenVoyahControls", "event=routine_layout mode=$mode outcome=cancelled")
            else Log.w("OpenVoyahControls", "event=routine_layout mode=$mode outcome=rollback_incomplete")
            throw cancelled
        }
    }
}

/** Lets a foreground entry cancel a write until the archive reaches its commit point. */
internal class RoutineLayoutPermit(private val stillCurrent: () -> Boolean) {
    private enum class State { PENDING, CANCELLED, COMMITTED }

    private val state = AtomicReference(State.PENDING)

    fun cancel() { state.compareAndSet(State.PENDING, State.CANCELLED) }

    fun requireActive() {
        if (!isActive()) throw CancellationException("Routine layout request expired")
    }

    fun tryCommit(): Boolean = isActive() && state.compareAndSet(State.PENDING, State.COMMITTED)

    private fun isActive(): Boolean = state.get() == State.PENDING && stillCurrent()
}

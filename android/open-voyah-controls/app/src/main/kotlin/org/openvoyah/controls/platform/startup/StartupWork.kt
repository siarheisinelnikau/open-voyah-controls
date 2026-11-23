package org.openvoyah.controls.platform.startup

import android.os.Handler
import android.os.Looper

/**
 * Process start-up work that waits while the startup intro plays. After boot the intro starts this process cold;
 * preparing the speech model at the same time took the CPU the intro needed and made it stutter (boot 205: 6.7 s of
 * model preparation exactly while the intro ran).
 */
object StartupWork {
    /** Start-up work never waits longer than this, even if an intro is never released. */
    private const val LONGEST_HOLD_MILLIS = 20_000L

    private val gate by lazy {
        val main = Handler(Looper.getMainLooper())
        StartupGate(
            post = { task -> main.post(task) },
            postDelayed = { task, delay -> main.postDelayed(task, delay) },
            cancel = { task -> main.removeCallbacks(task) },
        )
    }

    /** Main thread. Runs [task] once no intro holds start-up work; see [StartupGate.afterIntro]. */
    @JvmStatic fun afterIntro(task: Runnable) = gate.afterIntro(task)

    /** Main thread. Holds [afterIntro] work until the returned release runs, at most [LONGEST_HOLD_MILLIS]. */
    fun hold(): () -> Unit = gate.hold(LONGEST_HOLD_MILLIS)
}

/** Main-thread bookkeeping for [StartupWork]; the scheduler is passed in so that the order can be tested. */
internal class StartupGate(
    private val post: (Runnable) -> Unit,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
) {
    private val waiting = ArrayList<Runnable>()
    private var holds = 0

    /**
     * Runs [task] after the main-thread messages already queued and after every hold ends. Called from
     * `Application.onCreate`, the post lands behind the Activity launch that started the process, so an intro that
     * takes a hold in its `onCreate` does so before the task is considered.
     */
    fun afterIntro(task: Runnable) = post(Runnable { if (holds > 0) waiting += task else task.run() })

    fun hold(longestMillis: Long): () -> Unit {
        holds++
        var released = false
        val release = Runnable {
            if (!released) {
                released = true
                holds--
                if (holds == 0) {
                    val ready = waiting.toList()
                    waiting.clear()
                    ready.forEach(Runnable::run)
                }
            }
        }
        postDelayed(release, longestMillis)
        return {
            cancel(release)
            release.run()
        }
    }
}

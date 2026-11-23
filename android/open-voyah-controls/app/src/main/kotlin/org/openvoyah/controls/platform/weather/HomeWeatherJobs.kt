package org.openvoyah.controls.platform.weather

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import org.openvoyah.controls.data.frida.FridaBridge
import org.openvoyah.controls.data.settings.SettingsStore
import org.openvoyah.controls.data.weather.HomeWeatherPreferences
import org.openvoyah.controls.data.weather.HomeWeatherSource
import org.openvoyah.controls.data.weather.HomeWeatherUnavailable
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.domain.weather.HomeWeather

/**
 * Refreshes the home screen weather every 30 minutes while the setting is on. Both jobs wait for a network, so a
 * refresh missed while offline runs as soon as the car is connected again.
 */
object HomeWeatherJobs {
    const val PERIODIC = 17031
    const val NOW = 17032

    private fun component(context: Context) = ComponentName(context, HomeWeatherJobService::class.java)
    private fun scheduler(context: Context) = context.getSystemService(JobScheduler::class.java)

    /**
     * Puts the jobs back after anything that dropped them: called on every process start and from the boot and
     * app-update broadcasts. Android drops a package's jobs when it is reinstalled, and a fresh install or a cleared
     * job store has none. Fetches at once when the last published reading is due for a refresh.
     */
    @JvmStatic @JvmOverloads fun ensure(context: Context, now: Long = System.currentTimeMillis()) {
        val enabled = try { SettingsStore(context).load().weatherCard } catch (_: RuntimeException) { false }
        if (!enabled) return
        val scheduler = scheduler(context)
        if (scheduler.getPendingJob(PERIODIC) == null) schedulePeriodic(context)
        if (HomeWeather.needsRefresh(HomeWeatherPreferences(context).publishedAt, now) && scheduler.getPendingJob(NOW) == null) {
            refreshNow(context)
        }
    }

    /** Starts the periodic refresh and fetches at once. */
    fun schedule(context: Context) {
        schedulePeriodic(context)
        refreshNow(context)
    }

    private fun schedulePeriodic(context: Context) {
        val periodic = JobInfo.Builder(PERIODIC, component(context))
            .setPeriodic(HomeWeather.REFRESH_MS)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build()
        if (scheduler(context).schedule(periodic) != JobScheduler.RESULT_SUCCESS) {
            ControlsLog.error("home_weather_job", "outcome", "schedule_failed")
        }
    }

    /** One fetch; a later call replaces a pending one, so typing a city fetches once after the last change. */
    fun refreshNow(context: Context, delayMs: Long = 0) {
        val job = JobInfo.Builder(NOW, component(context))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(delayMs)
            .setBackoffCriteria(60_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build()
        scheduler(context).schedule(job)
    }

    fun cancel(context: Context) {
        scheduler(context).cancel(PERIODIC)
        scheduler(context).cancel(NOW)
    }
}

class HomeWeatherJobService : JobService() {
    @Volatile private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        val enabled = try { SettingsStore(app).load().weatherCard } catch (_: RuntimeException) { false }
        if (!enabled) {
            HomeWeatherJobs.cancel(app)
            return false
        }
        worker = Thread {
            var retry = false
            try {
                val source = HomeWeatherSource(app)
                val reading = source.read()
                if (FridaBridge(app).publishHomeWeather(source.payload(reading))) {
                    HomeWeatherPreferences(app).publishedAt = reading.fetchedAt
                    ControlsLog.info("home_weather", "outcome", "published")
                } else {
                    // The agents are not running yet (just after boot or an update): try again shortly.
                    retry = true
                    ControlsLog.info("home_weather", "outcome", "agents_not_ready")
                }
            } catch (unavailable: HomeWeatherUnavailable) {
                // Nothing to retry until the location, the city or the forecast changes.
                ControlsLog.warn("home_weather", unavailable, "outcome", unavailable.reason)
            } catch (failure: Exception) {
                retry = true
                ControlsLog.warn("home_weather", failure, "outcome", "failed")
            }
            jobFinished(params, retry)
        }.apply { name = "home-weather"; start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        return true
    }
}


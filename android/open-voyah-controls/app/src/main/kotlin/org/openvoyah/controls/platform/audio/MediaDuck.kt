package org.openvoyah.controls.platform.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.openvoyah.controls.data.settings.VoiceSettings
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.domain.audio.DuckRecord
import org.openvoyah.controls.domain.audio.DuckSettings
import org.openvoyah.controls.domain.audio.DuckStore
import org.openvoyah.controls.domain.audio.DuckTrigger
import org.openvoyah.controls.domain.audio.MediaDuckMachine
import org.openvoyah.controls.domain.audio.PHONE_CALL_TERMINATED
import org.openvoyah.controls.domain.audio.phoneCallActive
import org.openvoyah.controls.domain.audio.rampLevels
import org.openvoyah.controls.domain.audio.startupLevel
import org.openvoyah.controls.domain.audio.startupVolumeDue

/**
 * Lowers the media volume while Controls listens or a phone call is active, and fades it back afterwards.
 *
 * The head unit firmware sends `AudioManager.setStreamVolume`/`getStreamVolume` for the media stream straight to the
 * OEM audio policy, which drives the amplifier, so the media level is the one the owner sees on the volume slider.
 * Audio focus ducking is decided by the OEM focus engine and is not relied on. All state lives on one thread.
 */
object MediaDuck {
    private const val STREAM = AudioManager.STREAM_MUSIC
    private const val CALL_STATUS = "phone_call_status"
    private const val OEM_POLICY = "com.qinggan.audiopolicy.service"
    /**
     * The OEM policy unmutes media on any volume change unless this flag (Android's hidden FLAG_ACTIVE_MEDIA_ONLY) is
     * set. The OEM phone mutes media for the length of a call, and a fade must never undo that.
     */
    private const val OEM_KEEP_MUTE = 512
    private const val DOWN_STEPS = 4
    private const val DOWN_INTERVAL_MS = 35L
    private const val UP_STEPS = 8
    private const val UP_INTERVAL_MS = 60L
    private const val COMMAND_WAIT_MS = 1000L
    private const val RETRY_MS = 1000L

    private val handler: Handler by lazy { Handler(HandlerThread("media-duck").apply { start() }.looper) }
    @Volatile private var host: Host? = null

    /**
     * Process start, which includes the boot broadcast: sets the startup level once per boot, otherwise restores a
     * level a killed process left low, then starts following calls.
     */
    @JvmStatic fun start(context: Context) {
        val host = attach(context)
        handler.post { host.startOnce() }
    }

    @JvmStatic fun begin(context: Context, trigger: DuckTrigger) {
        val host = attach(context)
        handler.post { host.begin(trigger) }
    }

    @JvmStatic fun end(context: Context, trigger: DuckTrigger) {
        val host = attach(context)
        handler.post { host.end(trigger) }
    }

    /**
     * Called right before a voice command changes the media volume. The command's level wins: the duck ends here and
     * nothing restores over it. A relative command first gets the owner's own level back, so "louder" steps from it.
     */
    @JvmStatic fun beforeVolumeCommand(context: Context, relative: Boolean) {
        val host = attach(context)
        if (Looper.myLooper() == handler.looper) {
            host.command(relative)
            return
        }
        val done = CountDownLatch(1)
        handler.post { try { host.command(relative) } finally { done.countDown() } }
        if (!done.await(COMMAND_WAIT_MS, TimeUnit.MILLISECONDS)) ControlsLog.warn("media_duck", "event", "command", "outcome", "timeout")
    }

    private fun attach(context: Context): Host = host ?: synchronized(this) {
        host ?: Host(context.applicationContext).also { host = it }
    }

    private class Host(private val app: Context) {
        private val audio: AudioManager? = app.getSystemService(AudioManager::class.java)
        private val store = PreferencesDuckStore(app)
        private val machine = MediaDuckMachine(store)
        private val flags = if (oemPolicy()) OEM_KEEP_MUTE else 0
        private var fade = 0
        private var started = false
        private var call = false

        fun startOnce() {
            if (started) return
            started = true
            val current = volume()
            // The startup level replaces any level saved by an earlier drive, so there is nothing to recover then.
            if (current != null && !startupVolume()) {
                val target = machine.recover(current, callActive(), settings())
                if (target != null) {
                    ControlsLog.info("media_duck", "event", "recover", "outcome", if (machine.triggers.isEmpty()) "restore" else "call")
                    fadeTo(target, UP_STEPS, UP_INTERVAL_MS)
                }
            }
            call = machine.triggers.contains(DuckTrigger.CALL)
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) = followCall()
            }
            try {
                app.contentResolver.registerContentObserver(Settings.Global.getUriFor(CALL_STATUS), false, observer)
            } catch (failure: RuntimeException) {
                ControlsLog.warn("media_duck", failure, "event", "call_observer", "outcome", "unavailable")
            }
            followCall()
        }

        /** True when the startup level was set in this call. */
        private fun startupVolume(): Boolean {
            val boot = try { Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT) } catch (failure: Exception) { null }
            val wanted = try { VoiceSettings(app).startupVolume() } catch (failure: RuntimeException) { return false }
            val due = startupVolumeDue(wanted, boot, store.appliedBoot(), SystemClock.elapsedRealtime())
            if (boot != null && boot != store.appliedBoot()) store.appliedBoot(boot)
            if (!due) return false
            val manager = audio ?: return false
            val level = try {
                startupLevel(manager.getStreamMinVolume(STREAM), manager.getStreamMaxVolume(STREAM), wanted.percent)
            } catch (failure: RuntimeException) {
                return false
            }
            fade++
            machine.discard()
            if (!set(level)) return false
            ControlsLog.info("media_duck", "event", "startup_volume", "percent", wanted.percent, "level", level)
            return true
        }

        fun begin(trigger: DuckTrigger) {
            val current = volume() ?: return
            val target = machine.start(trigger, current, minimum(), settings()) ?: return
            ControlsLog.info("media_duck", "event", "lower", "trigger", trigger.name.lowercase(), "from", current, "to", target)
            fadeTo(target, DOWN_STEPS, DOWN_INTERVAL_MS)
        }

        fun end(trigger: DuckTrigger, retry: Boolean = true) {
            val current = volume()
            if (current == null) {
                // A trigger that never ends would keep media low; the audio policy may be restarting.
                if (retry && trigger in machine.triggers) handler.postDelayed({ end(trigger, retry = false) }, RETRY_MS)
                return
            }
            val wasDucked = machine.ducked
            val target = machine.stop(trigger, current)
            if (target != null) {
                ControlsLog.info("media_duck", "event", "restore", "trigger", trigger.name.lowercase(), "to", target)
                fadeTo(target, UP_STEPS, UP_INTERVAL_MS)
            } else if (wasDucked && !machine.ducked) {
                // Already at the saved level, or the owner set another one: either way nothing fades back.
                fade++
                ControlsLog.info("media_duck", "event", "restore", "trigger", trigger.name.lowercase(), "outcome", "no_fade")
            }
        }

        fun command(relative: Boolean) {
            if (!machine.ducked) return
            fade++
            val current = volume() ?: return
            val level = machine.command(relative, current)
            if (level != null) set(level)
            ControlsLog.info("media_duck", "event", "command", "outcome", "owner_level")
        }

        private fun followCall() {
            val active = callActive()
            if (active == call) return
            call = active
            if (active) begin(DuckTrigger.CALL) else end(DuckTrigger.CALL)
        }

        private fun fadeTo(target: Int, steps: Int, interval: Long) {
            val generation = ++fade
            val from = volume() ?: return
            val levels = rampLevels(from, target, steps)
            if (levels.isEmpty()) {
                machine.reached(from)
                return
            }
            fun step(index: Int) {
                if (generation != fade) return
                val current = volume() ?: return
                if (!machine.owns(current)) {
                    ControlsLog.info("media_duck", "event", "fade", "outcome", "owner_changed_volume")
                    return
                }
                val level = levels[index]
                if (!set(level)) return
                machine.applied(level)
                if (index == levels.lastIndex) machine.reached(level)
                else handler.postDelayed({ step(index + 1) }, interval)
            }
            step(0)
        }

        private fun settings() = try {
            VoiceSettings(app).mediaDuck()
        } catch (failure: RuntimeException) {
            ControlsLog.warn("media_duck", failure, "event", "settings", "outcome", "default")
            DuckSettings.DEFAULT
        }

        private fun callActive(): Boolean = try {
            phoneCallActive(Settings.Global.getInt(app.contentResolver, CALL_STATUS, PHONE_CALL_TERMINATED))
        } catch (failure: RuntimeException) {
            false
        }

        private fun minimum(): Int = try { audio?.getStreamMinVolume(STREAM) ?: 0 } catch (failure: RuntimeException) { 0 }

        private fun volume(): Int? {
            val manager = audio ?: return null
            return try {
                if (manager.isVolumeFixed) null else manager.getStreamVolume(STREAM)
            } catch (failure: RuntimeException) {
                ControlsLog.warn("media_duck", failure, "event", "read", "outcome", "unavailable")
                null
            }
        }

        // OEM_KEEP_MUTE is not one of the public flags; see its declaration.
        @SuppressLint("WrongConstant")
        private fun set(level: Int): Boolean = try {
            audio?.setStreamVolume(STREAM, level, flags)
            audio != null
        } catch (failure: RuntimeException) {
            ControlsLog.warn("media_duck", failure, "event", "write", "outcome", "unavailable")
            false
        }

        private fun oemPolicy(): Boolean = try {
            app.packageManager.getApplicationInfo(OEM_POLICY, 0)
            true
        } catch (missing: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** Committed synchronously before the first volume change. */
    private class PreferencesDuckStore(context: Context) : DuckStore {
        private val prefs = context.getSharedPreferences("media_duck", Context.MODE_PRIVATE)

        override fun load(): DuckRecord? {
            val saved = prefs.getInt(SAVED, -1)
            val ducked = prefs.getInt(DUCKED, -1)
            return if (saved >= 0 && ducked >= 0) DuckRecord(saved, ducked) else null
        }

        override fun save(record: DuckRecord) {
            prefs.edit().putInt(SAVED, record.saved).putInt(DUCKED, record.ducked).commit()
        }

        override fun clear() {
            if (prefs.contains(SAVED) || prefs.contains(DUCKED)) prefs.edit().remove(SAVED).remove(DUCKED).commit()
        }

        fun appliedBoot(): Int? = prefs.getInt(STARTUP_BOOT, -1).takeIf { it >= 0 }

        fun appliedBoot(boot: Int) {
            prefs.edit().putInt(STARTUP_BOOT, boot).commit()
        }

        private companion object {
            const val SAVED = "saved"
            const val DUCKED = "ducked"
            const val STARTUP_BOOT = "startupBoot"
        }
    }
}

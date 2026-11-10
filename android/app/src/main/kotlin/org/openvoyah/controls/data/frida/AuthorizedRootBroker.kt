package org.openvoyah.controls.data.frida

import android.system.Os
import android.system.OsConstants
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Runs a bounded request through a local root manager already authorized by the operator. */
internal class AuthorizedRootBroker private constructor(private val executable: String) {
    fun execute(script: String): String {
        if (Thread.currentThread().isInterrupted) throw IOException(UiText.get(R.string.ui_24))
        val marker = "OVC_BROKER_${UUID.randomUUID().toString().replace("-", "")}:"
        val payload = "(\n$script\n)\novc_status=\$?\nprintf '\\n%s%s\\n' '$marker' \"\$ovc_status\"\nexit \"\$ovc_status\"\n"
            .toByteArray(Charsets.UTF_8)
        if (payload.size > 2 * 1024 * 1024) throw IOException("Root request exceeds the local limit")
        val builder = ProcessBuilder(executable, "-c", "/system/bin/sh").redirectErrorStream(true)
        builder.environment().apply {
            remove("ENV")
            remove("BASH_ENV")
            remove("LD_PRELOAD")
            remove("LD_LIBRARY_PATH")
            put("PATH", "/system/bin:/system/xbin")
        }
        val child = builder.start()
        val read = FutureTask {
            child.inputStream.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val size = stream.read(buffer)
                    if (size < 0) break
                    if (output.size() + size > 131072) throw IOException("Root response exceeds the local limit")
                    output.write(buffer, 0, size)
                }
                output.toString(Charsets.UTF_8.name()).replace("\r", "")
            }
        }
        val write = FutureTask { child.outputStream.use { it.write(payload) } }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        start(read, "ovc-root-read")
        start(write, "ovc-root-write")
        try {
            write.get(remaining(deadline), TimeUnit.NANOSECONDS)
            if (!child.waitFor(remaining(deadline), TimeUnit.NANOSECONDS)) throw TimeoutException()
            val output = read.get(remaining(deadline), TimeUnit.NANOSECONDS)
            val status = output.lastIndexOf("\n$marker")
            if (status < 0 || output.substring(status + marker.length + 1).trim() != child.exitValue().toString()) {
                throw ProductionRootAccess.Refused(UiText.get(R.string.production_broker_unavailable))
            }
            if (child.exitValue() == 77) {
                throw ProductionRootAccess.Refused(UiText.get(R.string.production_evidence_changed))
            }
            if (child.exitValue() == 78) {
                throw ProductionRootAccess.Refused(UiText.get(R.string.production_recovery_blocked))
            }
            if (child.exitValue() != 0) throw IOException("Root command failed with status ${child.exitValue()}")
            return output.substring(0, status).trim()
        } catch (_: TimeoutException) {
            throw IOException(UiText.get(R.string.production_broker_timeout))
        } catch (error: ExecutionException) {
            throw IOException(UiText.get(R.string.production_broker_unavailable), error.cause)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException(UiText.get(R.string.ui_24), error)
        } finally {
            if (child.isAlive) child.destroyForcibly()
            read.cancel(true)
            write.cancel(true)
        }
    }

    companion object {
        private val brokerPaths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su")
        private const val GROUP_OR_OTHER_WRITABLE = 0x12

        fun existing(): AuthorizedRootBroker {
            val path = brokerPaths.firstNotNullOfOrNull(::trustedExecutable)
                ?: throw ProductionRootAccess.Refused(UiText.get(R.string.production_broker_unavailable))
            return AuthorizedRootBroker(path)
        }

        private fun trustedExecutable(path: String): String? = try {
            val file = File(path).canonicalFile
            val status = Os.lstat(file.path)
            val trusted = OsConstants.S_ISREG(status.st_mode) && status.st_uid == 0 &&
                (status.st_mode and GROUP_OR_OTHER_WRITABLE) == 0 && file.canExecute() &&
                generateSequence(file.parentFile) { it.parentFile }.all { folder ->
                    val directory = Os.lstat(folder.path)
                    OsConstants.S_ISDIR(directory.st_mode) && directory.st_uid == 0 &&
                        (directory.st_mode and GROUP_OR_OTHER_WRITABLE) == 0
                }
            file.path.takeIf { trusted }
        } catch (_: Exception) {
            null
        }

        private fun remaining(deadline: Long): Long {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw TimeoutException()
            return remaining
        }

        private fun start(task: FutureTask<*>, name: String) {
            Thread(task, name).apply { isDaemon = true }.start()
        }
    }
}

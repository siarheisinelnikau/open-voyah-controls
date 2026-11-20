package org.openvoyah.controls.data.apollo

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.openvoyah.controls.domain.apollo.ApolloCacheStatus
import org.openvoyah.controls.domain.apollo.ApolloCandidatePlan

internal data class ApolloCacheField(val state: String, val value: Int? = null)

internal data class ApolloFactoryCache(
    val status: ApolloCacheStatus,
    val fields: Map<String, ApolloCacheField> = emptyMap(),
)

/** Reads only the named, persisted request fields through the system provider's normal read permissions. */
internal class ApolloFactoryCacheReader(context: Context) {
    private val app = context.applicationContext

    fun provider(): ProviderInfo? = app.packageManager.resolveContentProvider(AUTHORITY, 0)

    suspend fun read(provider: ProviderInfo?): ApolloFactoryCache {
        if (provider == null) return ApolloFactoryCache(ApolloCacheStatus.PROVIDER_UNAVAILABLE)
        if (!isSystemSource(provider)) return ApolloFactoryCache(ApolloCacheStatus.ACCESS_DENIED)
        return withTimeoutOrNull(5_000L) {
            suspendCancellableCoroutine { pending ->
                val signal = CancellationSignal()
                pending.invokeOnCancellation { signal.cancel() }
                try {
                    worker.execute {
                        val result = try {
                            val fields = readFields(signal) { uri, columns, selection, args, stop ->
                                app.contentResolver.query(uri, columns, selection, args, null, stop)
                            }
                            val after = this@ApolloFactoryCacheReader.provider()
                            if (after == null || after.packageName != provider.packageName || after.name != provider.name ||
                                after.applicationInfo.uid != provider.applicationInfo.uid ||
                                after.applicationInfo.sourceDir != provider.applicationInfo.sourceDir || !isSystemSource(after)) {
                                ApolloFactoryCache(ApolloCacheStatus.SOURCE_CHANGED)
                            } else ApolloFactoryCache(ApolloCacheStatus.SAMPLED, fields)
                        } catch (_: SecurityException) {
                            ApolloFactoryCache(ApolloCacheStatus.ACCESS_DENIED)
                        } catch (_: RuntimeException) {
                            ApolloFactoryCache(ApolloCacheStatus.READ_FAILED)
                        }
                        if (pending.isActive) pending.resume(result)
                    }
                } catch (_: RejectedExecutionException) {
                    pending.resume(ApolloFactoryCache(ApolloCacheStatus.BUSY))
                }
            }
        } ?: ApolloFactoryCache(ApolloCacheStatus.TIMEOUT)
    }

    companion object {
        const val AUTHORITY = "qinggan.settings"
        const val CAN_PACKAGE = "com.qinggan.canbus.service"
        private val system = Uri.parse("content://$AUTHORITY/system")
        // A non-cooperative vendor query cannot occupy the screen or accumulate a queue on repeated taps.
        private val worker = ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS, SynchronousQueue<Runnable>()) { work ->
            Thread(work, "ApolloFactoryCache").apply { isDaemon = true }
        }

        // ContentResolver enforces the effective read permission and URI or path grants on the actual query.
        internal fun isSystemSource(provider: ProviderInfo): Boolean {
            val owner = provider.applicationInfo ?: return false
            val factoryFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
            return provider.authority?.split(';')?.contains(AUTHORITY) == true &&
                provider.exported && provider.enabled && owner.enabled && owner.flags and factoryFlags != 0
        }

        internal fun readFields(
            signal: CancellationSignal,
            query: (Uri, Array<String>, String, Array<String>, CancellationSignal) -> Cursor?,
        ): Map<String, ApolloCacheField> =
            ApolloCandidatePlan.AFFECTED_FLAGS.associateWith { name ->
                signal.throwIfCanceled()
                query(system, arrayOf("value"), "name=?", arrayOf(name), signal)?.use { cursor ->
                    when {
                        cursor.columnCount != 1 || cursor.getColumnIndex("value") != 0 -> ApolloCacheField("invalid")
                        !cursor.moveToFirst() -> ApolloCacheField("missing")
                        else -> {
                            val raw = when (cursor.getType(0)) {
                                Cursor.FIELD_TYPE_INTEGER, Cursor.FIELD_TYPE_STRING -> cursor.getString(0)?.toIntOrNull()
                                else -> null
                            }
                            when {
                                cursor.moveToNext() -> ApolloCacheField("ambiguous")
                                raw == null -> ApolloCacheField("invalid")
                                else -> ApolloCacheField("observed", raw)
                            }
                        }
                    }
                } ?: ApolloCacheField("read_unavailable")
            }
    }
}

package org.openvoyah.controls.platform.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.withTimeoutOrNull
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.domain.vehicle.HeadlightFeedback
import org.openvoyah.controls.domain.vehicle.HeadlightInputState
import org.openvoyah.controls.domain.vehicle.HeadlightObservation

internal object QingganHeadlightReader {
    const val PACKAGE = "com.qinggan.canbus.service"
    private const val SERVICE_ACTION = "com.qinggan.canbus.CanBusService"
    private const val SERVICE_DESCRIPTOR = "com.qinggan.canbus.ICanBusService"
    private val component = ComponentName(PACKAGE, "$PACKAGE.CanBusService")
    private val products = setOf("f6802h", "f6805h", "f6811")

    // The observer ABI and selector identifiers were verified against these exact OEM bytes.
    private const val VERSION_CODE = 7L
    private const val APK_BYTES = 2_180_716L
    private const val APK_SHA256 = "ae5f9fe98df395c0374175a86e83bfe49bbb443264e432391251a78a005879b6"
    private const val SIGNER_SHA256 = "b2e962ed163aed0831d6d08ee41ca9406ae970032aa4df8a4c3b41d222b201d3"
    private const val CONNECT_TIMEOUT_MS = 4_000L
    private const val MAX_REBINDS = 2L
    private const val REBIND_DELAY_MS = 500L

    private data class Installation(val uid: Int, val sourceDir: String, val updatedAt: Long)
    private sealed interface Link {
        class Connected(val binder: IBinder) : Link
        data object Disconnected : Link
        data object Died : Link
        data object Rejected : Link
    }

    private enum class Outcome(val key: String, val state: HeadlightInputState? = null) {
        UNSUPPORTED_PRODUCT("unsupported_product", HeadlightInputState.MODEL_UNREVIEWED),
        PACKAGE_CHANGED("package_changed", HeadlightInputState.SERVICE_UNREVIEWED),
        ACCESS_DENIED("access_denied", HeadlightInputState.PERMISSION_DENIED),
        TRANSPORT_LOST("transport_lost", HeadlightInputState.DISCONNECTED),
        SUBSCRIPTION_UNAVAILABLE("subscription_unavailable", HeadlightInputState.READ_FAILED),
        SERVICE_NOT_FOUND("service_not_found", HeadlightInputState.SERVICE_ABSENT),
        SERVICE_NOT_AUTHORIZED("service_not_authorized", HeadlightInputState.SERVICE_UNREVIEWED),
        SERVICE_PERMISSION_NOT_GRANTED("service_not_authorized", HeadlightInputState.PERMISSION_DENIED),
        UNSUPPORTED_SERVICE_BUILD("unsupported_service_build", HeadlightInputState.SERVICE_UNREVIEWED),
        REBIND_SCHEDULED("rebind_scheduled"),
        DISCONNECTED("disconnected", HeadlightInputState.DISCONNECTED),
        BINDING_REFUSED("binding_refused", HeadlightInputState.SUBSCRIPTION_REJECTED),
        CONNECTION_TIMEOUT("connection_timeout", HeadlightInputState.DISCONNECTED),
        BINDING_DIED("binding_died", HeadlightInputState.DISCONNECTED),
        SERVICE_CHANGED("subscription_unavailable", HeadlightInputState.SERVICE_UNREVIEWED),
        REGISTRATION_REJECTED("subscription_unavailable", HeadlightInputState.SUBSCRIPTION_REJECTED),
        WAITING_FOR_CHANGE("waiting_for_change", HeadlightInputState.SUBSCRIBED),
    }

    private class BindingLost(val outcome: Outcome) : IOException(outcome.key)
    private class SubscriptionStopped(val outcome: Outcome) : IOException(outcome.key)
    private class Subscription(
        private val binder: IBinder,
        private val listener: QingganHeadlightListener,
        private val additional: QingganReadOnlyCallbacks? = null,
    ) {
        fun release() {
            additional?.close()
            runCatching { listener.unregister(binder) }
        }
    }

    /** Completes when this stock interface is absent or rejects an ordinary read subscription. */
    fun observe(app: Context, reportState: (HeadlightInputState) -> Unit): Flow<HeadlightFeedback> =
        observeReadOnly(app, null, reportState)

    fun observeReadOnly(
        app: Context,
        additionalObserver: (() -> QingganReadOnlyCallbacks)?,
        reportState: (HeadlightInputState) -> Unit,
    ): Flow<HeadlightFeedback> = flow {
        if (Build.PRODUCT !in products) {
            report(Outcome.UNSUPPORTED_PRODUCT, reportState)
            return@flow
        }
        emitAll(subscribe(app, Intent(SERVICE_ACTION).setComponent(component), reportState, additionalObserver) { resolve(app, reportState) })
    }.catch { failure ->
        val outcome = when (failure) {
            is PackageManager.NameNotFoundException -> Outcome.PACKAGE_CHANGED
            is SecurityException -> Outcome.ACCESS_DENIED
            is RemoteException -> Outcome.TRANSPORT_LOST
            is SubscriptionStopped -> failure.outcome
            is IOException, is IllegalStateException -> Outcome.SUBSCRIPTION_UNAVAILABLE
            else -> throw failure
        }
        report(outcome, reportState)
        emit(HeadlightFeedback.Unavailable)
    }

    private fun resolve(app: Context, reportState: (HeadlightInputState) -> Unit): PackageInfo? {
        val packages = app.packageManager
        val query = Intent(SERVICE_ACTION).setPackage(PACKAGE)
        val service = packages.resolveService(query, 0)?.serviceInfo ?: run {
            report(Outcome.SERVICE_NOT_FOUND, reportState)
            return null
        }
        val application = service.applicationInfo
        val system = application.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        if (ComponentName(service.packageName, service.name) != component || !service.exported || !service.enabled ||
            !application.enabled || !system
        ) {
            report(Outcome.SERVICE_NOT_AUTHORIZED, reportState)
            return null
        }
        if (!service.permission.isNullOrEmpty() && app.checkSelfPermission(service.permission) != PackageManager.PERMISSION_GRANTED) {
            report(Outcome.SERVICE_PERMISSION_NOT_GRANTED, reportState)
            return null
        }

        val info = packages.getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
        val installation = info.installation() ?: run {
            report(Outcome.UNSUPPORTED_SERVICE_BUILD, reportState)
            return null
        }
        val signer = info.signingInfo?.apkContentsSigners?.singleOrNull()
        val apk = File(installation.sourceDir)
        if (info.longVersionCode != VERSION_CODE || installation.uid != application.uid ||
            installation.sourceDir != application.sourceDir ||
            !application.splitSourceDirs.isNullOrEmpty() || signer == null ||
            hex(sha256().digest(signer.toByteArray())) != SIGNER_SHA256 || !matchesApk(apk)
        ) {
            report(Outcome.UNSUPPORTED_SERVICE_BUILD, reportState)
            return null
        }
        return info
    }

    /** Recheck admission for each new binding and each system reconnection. */
    internal fun subscribe(
        app: Context,
        request: Intent,
        reportState: (HeadlightInputState) -> Unit = {},
        additionalObserver: (() -> QingganReadOnlyCallbacks)? = null,
        verifyPackage: () -> PackageInfo?,
    ): Flow<HeadlightFeedback> {
        val intent = Intent(request)
        val target = requireNotNull(intent.component)
        fun verifiedInstallation(): Installation? {
            val info = verifyPackage() ?: return null
            require(target.packageName == info.packageName)
            return info.installation()
        }
        return flow {
            val installation = verifiedInstallation() ?: return@flow
            emitAll(readBinding(app, intent, target, installation, ::verifiedInstallation, reportState, additionalObserver))
        }.retryWhen { failure, attempt ->
            if (failure !is BindingLost && failure !is DeadObjectException) return@retryWhen false
            report(if (failure is BindingLost) failure.outcome else Outcome.TRANSPORT_LOST, reportState)
            emit(HeadlightFeedback.Unavailable)
            if (attempt >= MAX_REBINDS) return@retryWhen false
            report(Outcome.REBIND_SCHEDULED, reportState)
            delay(REBIND_DELAY_MS * (attempt + 1))
            true
        }
    }

    private fun readBinding(
        app: Context,
        intent: Intent,
        target: ComponentName,
        installation: Installation,
        verifyInstallation: () -> Installation?,
        reportState: (HeadlightInputState) -> Unit,
        additionalObserver: (() -> QingganReadOnlyCallbacks)?,
    ): Flow<HeadlightFeedback> = channelFlow {
        val links = Channel<Link>(Channel.CONFLATED)
        val gate = Any()
        var currentLink: Link? = null
        var activeAdditional: QingganReadOnlyCallbacks? = null

        fun changed(link: Link, outcome: Outcome? = null) {
            synchronized(gate) {
                if (currentLink == Link.Died || currentLink == Link.Rejected || currentLink === link) return
                // Revoke the old listener before a slow unregister or an already queued callback can publish.
                val wasConnected = currentLink is Link.Connected
                activeAdditional?.close()
                activeAdditional = null
                currentLink = link
                outcome?.let { report(it, reportState) }
                if (wasConnected || link !is Link.Connected) trySend(HeadlightFeedback.Unavailable)
                links.trySend(link)
            }
        }
        fun publish(link: Link.Connected, feedback: HeadlightFeedback) {
            synchronized(gate) { if (currentLink === link) trySend(feedback) }
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                changed(if (name == target) Link.Connected(service) else Link.Rejected)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                changed(if (name == target) Link.Disconnected else Link.Rejected, Outcome.DISCONNECTED)
            }

            override fun onBindingDied(name: ComponentName) { changed(if (name == target) Link.Died else Link.Rejected) }
            override fun onNullBinding(name: ComponentName) { changed(Link.Rejected) }
        }

        var subscription: Subscription? = null
        var previouslyConnected = false
        try {
            if (!app.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                changed(Link.Rejected)
            }
            while (true) {
                // Android keeps a disconnected binding and may deliver a replacement Binder on it.
                val link = if (subscription != null) links.receive() else {
                    withTimeoutOrNull(CONNECT_TIMEOUT_MS) { links.receive() } ?: throw BindingLost(Outcome.CONNECTION_TIMEOUT)
                }
                subscription?.release()
                subscription = null
                when (link) {
                    Link.Disconnected -> continue
                    Link.Died -> throw BindingLost(Outcome.BINDING_DIED)
                    Link.Rejected -> {
                        report(Outcome.BINDING_REFUSED, reportState)
                        return@channelFlow
                    }
                    is Link.Connected -> {
                        if (!synchronized(gate) { currentLink === link }) continue
                        if (previouslyConnected && verifyInstallation() != installation) throw SubscriptionStopped(Outcome.SERVICE_CHANGED)
                        val current = app.packageManager.getPackageInfo(target.packageName, 0).installation()
                        if (current != installation || link.binder.interfaceDescriptor != SERVICE_DESCRIPTOR) {
                            throw SubscriptionStopped(Outcome.SERVICE_CHANGED)
                        }
                        currentCoroutineContext().ensureActive()
                        if (!synchronized(gate) { currentLink === link }) continue
                        publish(link, HeadlightFeedback.AwaitingController)
                        val additional = additionalObserver?.invoke()
                        synchronized(gate) {
                            if (currentLink === link) activeAdditional = additional else additional?.close()
                        }
                        val listener = QingganHeadlightListener(installation.uid, { mode ->
                            val elapsed = SystemClock.elapsedRealtimeNanos()
                            val received = System.currentTimeMillis()
                            if (elapsed > 0 && received > 0) {
                                publish(link, HeadlightFeedback.Confirmed(HeadlightObservation(mode, received), elapsed))
                            }
                        }, additional)
                        // Release this same observer even if the registration reply is lost.
                        subscription = Subscription(link.binder, listener, additional)
                        additional?.onConnected(link.binder)
                        if (!listener.register(link.binder)) throw SubscriptionStopped(Outcome.REGISTRATION_REJECTED)
                        previouslyConnected = true
                        synchronized(gate) {
                            if (currentLink === link) report(Outcome.WAITING_FOR_CHANGE, reportState)
                        }
                    }
                }
            }
        } finally {
            synchronized(gate) {
                currentLink = Link.Rejected
                activeAdditional?.close()
                activeAdditional = null
            }
            links.close()
            subscription?.release()
            runCatching { app.unbindService(connection) }
        }
    }.buffer(8, BufferOverflow.DROP_OLDEST)

    private fun PackageInfo.installation(): Installation? = applicationInfo?.let {
        Installation(it.uid, it.sourceDir, lastUpdateTime)
    }

    private fun matchesApk(apk: File): Boolean {
        if (apk.length() != APK_BYTES) return false
        val digest = sha256()
        apk.inputStream().use { stream ->
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > APK_BYTES) return false
                digest.update(buffer, 0, read)
            }
            return total == APK_BYTES && hex(digest.digest()) == APK_SHA256
        }
    }

    private fun sha256() = MessageDigest.getInstance("SHA-256")

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun report(outcome: Outcome, reportState: (HeadlightInputState) -> Unit) {
        ControlsLog.info("headlight_source", "source", "qinggan", "outcome", outcome.key)
        outcome.state?.let(reportState)
    }
}

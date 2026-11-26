package org.openvoyah.controls.platform.trips

import android.content.Context
import android.database.sqlite.SQLiteException
import android.os.SystemClock
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.openvoyah.controls.data.trips.LocalTripJournal
import org.openvoyah.controls.data.trips.TripDetails
import org.openvoyah.controls.domain.trips.RecordedTrip
import org.openvoyah.controls.domain.trips.RollingTripUsage
import org.openvoyah.controls.domain.trips.TripReadings
import org.openvoyah.controls.domain.trips.TripRoutePoint
import org.openvoyah.controls.domain.trips.TripTracker
import org.openvoyah.controls.domain.trips.TripUpdate
import org.openvoyah.controls.domain.trips.TripUsage
import org.openvoyah.controls.domain.trips.TripUsagePoint
import org.openvoyah.controls.domain.trips.validRoutePoint
import org.openvoyah.controls.domain.vehicle.automation.VehicleGear
import org.openvoyah.controls.domain.vehicle.automation.VehicleTelemetry
import org.openvoyah.controls.platform.vehicle.watchDeviceInteractivity

internal enum class TripGpsState { OFF, WAITING, RECORDING, UNAVAILABLE, LIMIT_REACHED }

internal data class TripJournalState(
    val ready: Boolean = false,
    val failed: Boolean = false,
    val automatic: Boolean = false,
    val hasBackgroundLease: Boolean = false,
    val live: TripReadings = TripReadings(),
    val active: RecordedTrip? = null,
    val completed: List<RecordedTrip> = emptyList(),
    val latestUsage: TripUsage = TripUsage(),
    val gps: TripGpsState = TripGpsState.OFF,
    val detailsRevision: Long = 0,
)

/** Shared owner for the driver journal, live cards and the existing Android observation lease. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class TripRuntime(
    context: Context,
    private val native: Flow<VehicleTelemetry>,
) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = LocalTripJournal(app)
    private val preferences = app.getSharedPreferences("trip_recording_local", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val mutable = MutableStateFlow(TripJournalState())
    private val observers = MutableStateFlow(0)
    val snapshot: TripJournalState get() = mutable.value
    val state: Flow<TripJournalState> = mutable
        .onStart { observers.update { it + 1 } }
        .onCompletion { observers.update { it - 1 } }
    private val lease = MutableStateFlow(TripLease())
    private val permissionChanges = MutableStateFlow(0)
    private val gpsToken = MutableStateFlow<String?>(null)
    private val rolling = RollingTripUsage()
    private var tracker = TripTracker(null)
    private var readSession: String? = null
    private var lastGear: VehicleGear? = null
    private var lastCheckpoint = 0L
    private var lastPointNanos = 0L
    private var lastUsage = TripUsage()
    private var rollingTime: Long? = null
    private var suppressAutoUntilPark = false

    private val tracking = combine(
        lease,
        gpsToken.map { it != null }.distinctUntilChanged(),
        mutable.map { it.ready && (it.automatic || it.active != null) }.distinctUntilChanged(),
    ) { owner, gps, recording -> recording && (owner.foreground || owner.background || gps) }
        .distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, false)

    private val shouldRead = combine(tracking, observers) { track, count -> track || count > 0 }
        .distinctUntilChanged()

    private val interactive = callbackFlow<Boolean> { watchDeviceInteractivity(app) { awake, _ -> trySend(awake) } }
        .distinctUntilChanged()

    init {
        scope.launch {
            update {
                tracker = TripTracker(store.active())
                mutable.value = mutable.value.copy(
                    ready = true,
                    automatic = preferences.getBoolean("automatic", false),
                    active = tracker.active,
                    completed = store.history(),
                )
            }
        }
        scope.launch {
            val awakeWithDemand = shouldRead.flatMapLatest { wanted -> if (wanted) interactive else flowOf(false) }
            combine(awakeWithDemand, permissionChanges) { awake, permissions -> awake to permissions }
                .distinctUntilChanged().flatMapLatest { (read, _) ->
                    if (!read) flowOf(TripReadings())
                    else combine(native, observeAutomotiveTripReadings(app), ticks()) { qinggan, automotive, now ->
                        val received = automotive.readings(now)
                        val factoryGear = qinggan.gear?.takeIf { it.isCurrentIn(qinggan, now) }?.value
                        if (factoryGear == null) received
                        else received.copy(session = "${qinggan.sessionId}:${received.session.orEmpty()}", gear = factoryGear)
                    }
                }.collect { readings -> update { observe(readings) } }
        }
    }

    fun setLease(background: Boolean, foreground: Boolean) {
        lease.value = TripLease(background, foreground)
        scope.launch { update { mutable.value = mutable.value.copy(hasBackgroundLease = lease.value.background) } }
    }

    fun refreshPermissions() {
        permissionChanges.value++
    }

    fun setAutomatic(enabled: Boolean) = scope.launch {
        update {
            if (!mutable.value.ready) return@update
            if (!preferences.edit().putBoolean("automatic", enabled).commit()) {
                mutable.value = mutable.value.copy(failed = true)
                return@update
            }
            mutable.value = mutable.value.copy(automatic = enabled)
        }
    }

    fun startManually() = scope.launch {
        update {
            if (!mutable.value.ready) return@update
            tracker.start(mutable.value.live.forManual(), System.currentTimeMillis(), SystemClock.elapsedRealtime(),
                manual = true)?.let(::save)
        }
    }

    fun finishManually() = scope.launch {
        update {
            if (mutable.value.live.gear != VehicleGear.PARK) suppressAutoUntilPark = true
            finishCurrent()
        }
    }

    fun removeCompleted() = scope.launch {
        update {
            store.deleteCompleted()
            mutable.value = mutable.value.copy(completed = emptyList(), detailsRevision = mutable.value.detailsRevision + 1)
        }
    }

    suspend fun details(id: String): TripDetails? = withContext(Dispatchers.IO) {
        lock.withLock {
            try { store.details(id) } catch (_: SQLiteException) { null }
        }
    }

    /** Consent is process local and usable only by the foreground service for the current trip. */
    suspend fun prepareGps(tripId: String): String? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (tracker.active?.id != tripId || gpsToken.value != null) return@withLock null
            val token = UUID.randomUUID().toString()
            lastPointNanos = 0
            gpsToken.value = token
            mutable.value = mutable.value.copy(gps = TripGpsState.WAITING)
            token
        }
    }

    fun routeAllowed(token: String): Flow<Boolean> = gpsToken.map { it == token }.distinctUntilChanged()

    fun stopGps(token: String? = null, outcome: TripGpsState = TripGpsState.OFF) = scope.launch {
        update {
            if (token == null || gpsToken.value == token) {
                gpsToken.value = null
                mutable.value = mutable.value.copy(gps = outcome)
            }
        }
    }

    fun recordGps(token: String, latitude: Double, longitude: Double, speedMps: Double?, elapsedNanos: Long) = scope.launch {
        update {
            val trip = tracker.active ?: return@update
            if (gpsToken.value != token || !validRoutePoint(latitude, longitude, speedMps) ||
                elapsedNanos !in 1..SystemClock.elapsedRealtimeNanos()) return@update
            val sincePrevious = elapsedNanos - lastPointNanos
            if (lastPointNanos != 0L && sincePrevious < GPS_MIN_INTERVAL_NANOS) return@update
            val point = TripRoutePoint(latitude, longitude, speedMps?.times(3.6),
                lastPointNanos == 0L || sincePrevious > GPS_MAX_GAP_NANOS)
            if (store.saveRoutePoint(trip.id, point)) {
                lastPointNanos = elapsedNanos
                mutable.value = mutable.value.copy(gps = TripGpsState.RECORDING)
            } else {
                gpsToken.value = null
                mutable.value = mutable.value.copy(gps = TripGpsState.LIMIT_REACHED)
            }
        }
    }

    private suspend fun update(action: () -> Unit) = lock.withLock {
        try {
            action()
        } catch (_: SQLiteException) {
            // Do not advance the in-memory clock or route past an uncommitted checkpoint.
            tracker = TripTracker(runCatching { store.active() }.getOrNull())
            gpsToken.value = null
            mutable.value = mutable.value.copy(failed = true, active = tracker.active, gps = TripGpsState.OFF)
        }
    }

    private fun observe(reading: TripReadings) {
        val now = SystemClock.elapsedRealtime()
        val current = tracker.active
        if (reading.gear == VehicleGear.PARK) suppressAutoUntilPark = false
        if (reading.session != readSession || reading.gear == VehicleGear.PARK || reading.levels.odometerKm == null) {
            rolling.clear()
            rollingTime = null
            lastUsage = TripUsage()
        } else if (reading.session != null && (rollingTime == null || now - checkNotNull(rollingTime) >= ROLLING_INTERVAL_MILLIS)) {
            rollingTime = now
            lastUsage = rolling.observe(TripUsagePoint(now, reading.levels.odometerKm,
                reading.levels.batteryKwh, reading.levels.fuelLitres))
        }
        mutable.value = mutable.value.copy(live = reading, latestUsage = lastUsage)
        if (tracking.value && mutable.value.ready &&
            (reading.session != readSession || reading.gear != lastGear || now - lastCheckpoint >= CHECKPOINT_INTERVAL_MILLIS)) {
            lastCheckpoint = now
            val candidate = if (current?.manual == true) reading.forManual() else reading
            if (current != null || mutable.value.automatic && !suppressAutoUntilPark) {
                tracker.observe(candidate, System.currentTimeMillis(), now)?.let(::save)
            }
        } else if (!tracking.value || reading.session == null && current?.manual != true) tracker.invalidate()
        readSession = reading.session
        lastGear = reading.gear
    }

    private fun TripReadings.forManual() = if (session == null) copy(session = "manual") else this

    private fun finishCurrent() {
        val live = mutable.value.live
        val reading = if (tracker.active?.manual == true) live.forManual() else live
        tracker.finish(reading, System.currentTimeMillis(), SystemClock.elapsedRealtime())?.let(::save)
    }

    private fun save(update: TripUpdate) {
        store.save(update)
        val completed = update.trip.finishedAtMillis != null
        if (completed) gpsToken.value = null
        mutable.value = mutable.value.copy(
            failed = false,
            active = tracker.active,
            completed = if (completed) store.history() else mutable.value.completed,
            gps = if (completed) TripGpsState.OFF else mutable.value.gps,
            detailsRevision = mutable.value.detailsRevision + 1,
        )
    }

    private fun ticks() = flow {
        while (true) {
            emit(SystemClock.elapsedRealtimeNanos())
            delay(1_000)
        }
    }

    private data class TripLease(val background: Boolean = false, val foreground: Boolean = false)

    private companion object {
        const val CHECKPOINT_INTERVAL_MILLIS = 5_000L
        const val ROLLING_INTERVAL_MILLIS = 2_000L
        const val GPS_MIN_INTERVAL_NANOS = 5_000_000_000L
        const val GPS_MAX_GAP_NANOS = 30_000_000_000L
    }
}

package org.openvoyah.controls.data.trips

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.getDoubleOrNull
import androidx.core.database.getLongOrNull
import androidx.core.database.sqlite.transaction
import org.openvoyah.controls.domain.trips.RecordedTrip
import org.openvoyah.controls.domain.trips.TripLevels
import org.openvoyah.controls.domain.trips.TripRoutePoint
import org.openvoyah.controls.domain.trips.TripUpdate
import org.openvoyah.controls.domain.trips.TripUsagePoint

internal data class TripDetails(val usage: List<TripUsagePoint>, val route: List<TripRoutePoint>)

/** Local database in the app sandbox. Settings archives have no access to this store. */
internal class LocalTripJournal(context: Context, name: String = "trips.db") : SQLiteOpenHelper(
    context.applicationContext, name, null, 1,
) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE trip(
                id TEXT PRIMARY KEY, started INTEGER NOT NULL, finished INTEGER, duration INTEGER NOT NULL,
                start_km REAL, start_charge REAL, start_energy REAL, start_fuel REAL,
                last_km REAL, last_charge REAL, last_energy REAL, last_fuel REAL,
                distance REAL, invalid_odometer INTEGER NOT NULL, interrupted INTEGER NOT NULL, manual INTEGER NOT NULL
            )""",
        )
        db.execSQL("CREATE UNIQUE INDEX one_active_trip ON trip((1)) WHERE finished IS NULL")
        db.execSQL("CREATE INDEX trip_finished ON trip(finished DESC)")
        db.execSQL(
            """CREATE TABLE usage(
                seq INTEGER PRIMARY KEY, trip_id TEXT NOT NULL REFERENCES trip(id) ON DELETE CASCADE,
                elapsed INTEGER NOT NULL, km REAL, energy REAL, fuel REAL
            )""",
        )
        db.execSQL("CREATE INDEX usage_trip ON usage(trip_id, seq)")
        db.execSQL(
            """CREATE TABLE route(
                seq INTEGER PRIMARY KEY, trip_id TEXT NOT NULL REFERENCES trip(id) ON DELETE CASCADE,
                lat REAL NOT NULL, lon REAL NOT NULL, speed REAL, starts_segment INTEGER NOT NULL
            )""",
        )
        db.execSQL("CREATE INDEX route_trip ON route(trip_id, seq)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("No trip journal migration from $oldVersion to $newVersion")
    }

    fun active(): RecordedTrip? = queryTrips("finished IS NULL", "started DESC", 1).firstOrNull()

    fun history(): List<RecordedTrip> = queryTrips("finished IS NOT NULL", "finished DESC, rowid DESC", MAX_COMPLETED_TRIPS)

    private fun queryTrips(where: String, orderBy: String, limit: Int): List<RecordedTrip> =
        readableDatabase.query("trip", TRIP_COLUMNS, where, null, null, null, orderBy, limit.toString()).use {
            buildList { while (it.moveToNext()) add(it.toTrip()) }
        }

    fun save(update: TripUpdate) {
        val trip = update.trip
        writableDatabase.transaction {
            val values = trip.values()
            if (this.update("trip", values, "id = ?", arrayOf(trip.id)) == 0) insertOrThrow("trip", null, values)
            update.usage?.let { point ->
                execSQL(
                    "INSERT INTO usage(trip_id,elapsed,km,energy,fuel) VALUES(?,?,?,?,?)",
                    arrayOf<Any?>(trip.id, point.elapsedMillis, point.odometerKm, point.batteryKwh, point.fuelLitres),
                )
                execSQL(
                    "DELETE FROM usage WHERE trip_id = ? AND seq <= (SELECT seq FROM usage WHERE trip_id = ? ORDER BY seq DESC LIMIT 1 OFFSET ?)",
                    arrayOf<Any>(trip.id, trip.id, MAX_USAGE_POINTS),
                )
            }
            if (trip.finishedAtMillis != null) {
                execSQL("DELETE FROM trip WHERE finished IS NOT NULL AND id NOT IN " +
                    "(SELECT id FROM trip WHERE finished IS NOT NULL ORDER BY finished DESC, rowid DESC LIMIT ?)",
                    arrayOf(MAX_COMPLETED_TRIPS))
            }
        }
    }

    fun saveRoutePoint(id: String, point: TripRoutePoint): Boolean {
        val db = writableDatabase
        if (db.query("route", arrayOf("seq"), "trip_id = ?", arrayOf(id), null, null, "seq", "1 OFFSET ${MAX_ROUTE_POINTS - 1}")
                .use { it.moveToFirst() }) return false
        val values = ContentValues().apply {
            put("trip_id", id)
            put("lat", point.latitude)
            put("lon", point.longitude)
            put("speed", point.speedKph)
            put("starts_segment", point.startsSegment)
        }
        return db.insertOrThrow("route", null, values) != -1L
    }

    fun details(id: String): TripDetails {
        val db = readableDatabase
        val usage = db.query("usage", arrayOf("elapsed", "km", "energy", "fuel"), "trip_id = ?", arrayOf(id),
            null, null, "seq", MAX_USAGE_POINTS.toString()).use {
            buildList {
                while (it.moveToNext()) add(TripUsagePoint(it.getLong(0), it.getDoubleOrNull(1),
                    it.getDoubleOrNull(2), it.getDoubleOrNull(3)))
            }
        }
        val route = db.query("route", arrayOf("lat", "lon", "speed", "starts_segment"), "trip_id = ?", arrayOf(id),
            null, null, "seq", MAX_ROUTE_POINTS.toString()).use {
            buildList {
                while (it.moveToNext()) add(TripRoutePoint(it.getDouble(0), it.getDouble(1), it.getDoubleOrNull(2), it.getInt(3) != 0))
            }
        }
        return TripDetails(usage, route)
    }

    fun deleteCompleted() {
        writableDatabase.delete("trip", "finished IS NOT NULL", null)
    }

    private fun RecordedTrip.values() = ContentValues().apply {
        put("id", id)
        put("started", startedAtMillis)
        put("finished", finishedAtMillis)
        put("duration", durationMillis)
        put("start_km", start.odometerKm)
        put("start_charge", start.chargePercent)
        put("start_energy", start.batteryKwh)
        put("start_fuel", start.fuelLitres)
        put("last_km", latest.odometerKm)
        put("last_charge", latest.chargePercent)
        put("last_energy", latest.batteryKwh)
        put("last_fuel", latest.fuelLitres)
        put("distance", distanceKm)
        put("invalid_odometer", invalidOdometer)
        put("interrupted", interrupted)
        put("manual", manual)
    }

    private fun Cursor.toTrip() = RecordedTrip(
        id = getString(0),
        startedAtMillis = getLong(1),
        finishedAtMillis = getLongOrNull(2),
        durationMillis = getLong(3),
        start = TripLevels(getDoubleOrNull(4), getDoubleOrNull(5), getDoubleOrNull(6), getDoubleOrNull(7)),
        latest = TripLevels(getDoubleOrNull(8), getDoubleOrNull(9), getDoubleOrNull(10), getDoubleOrNull(11)),
        distanceKm = getDoubleOrNull(12),
        invalidOdometer = getInt(13) != 0,
        interrupted = getInt(14) != 0,
        manual = getInt(15) != 0,
    )

    private companion object {
        const val MAX_COMPLETED_TRIPS = 100
        const val MAX_USAGE_POINTS = 4_320
        const val MAX_ROUTE_POINTS = 4_320
        val TRIP_COLUMNS = arrayOf("id", "started", "finished", "duration", "start_km", "start_charge", "start_energy",
            "start_fuel", "last_km", "last_charge", "last_energy", "last_fuel", "distance", "invalid_odometer",
            "interrupted", "manual")
    }
}

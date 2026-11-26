package org.openvoyah.controls.data.trips

import android.test.InstrumentationTestCase
import java.util.UUID
import org.openvoyah.controls.domain.trips.RecordedTrip
import org.openvoyah.controls.domain.trips.TripLevels
import org.openvoyah.controls.domain.trips.TripRoutePoint
import org.openvoyah.controls.domain.trips.TripUpdate
import org.openvoyah.controls.domain.trips.TripUsagePoint

class LocalTripJournalTest : InstrumentationTestCase() {
    fun testSameDatabaseRetainsActiveAndCompletedTripAcrossReopeningAndCascadesRouteDeletion() {
        val context = instrumentation.targetContext
        val file = "trip-test-${UUID.randomUUID()}.db"
        val trip = RecordedTrip("one", 1_000, 2_000, TripLevels(37.0, 80.0, 20.0, null))
        val usage = TripUsagePoint(2_000, 37.0, 20.0, null)
        val route = TripRoutePoint(50.1, 25.2, null, true)
        try {
            LocalTripJournal(context, file).use { database ->
                database.save(TripUpdate(trip, usage))
                assertTrue(database.saveRoutePoint(trip.id, route))
            }
            val completed = trip.copy(finishedAtMillis = 3_000)
            LocalTripJournal(context, file).use { database ->
                assertEquals(trip, database.active())
                assertEquals(TripDetails(listOf(usage), listOf(route)), database.details(trip.id))
                database.save(TripUpdate(completed))
            }
            LocalTripJournal(context, file).use { database ->
                assertNull(database.active())
                assertEquals(listOf(completed), database.history())
                database.deleteCompleted()
                assertTrue(database.history().isEmpty())
                assertEquals(TripDetails(emptyList(), emptyList()), database.details(trip.id))
            }
        } finally {
            context.deleteDatabase(file)
        }
    }
}

package org.openvoyah.controls.domain.apollo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApolloAssistanceStatusTest {
    @Test fun absentAgentDocumentIsNoReportRatherThanAnEmptyOne() {
        for (raw in listOf(null, "", "   ", "not a document", "{\"schema\":2,\"subscription\":\"applied\"}")) {
            val status = ApolloAssistanceStatus.parse(raw)
            assertFalse("$raw", status.reported)
            assertTrue("$raw", status.applied.isEmpty())
            assertTrue("$raw", status.unavailable.isEmpty())
        }
    }

    @Test fun theAgentDocumentNamesEachPartOnItsOwn() {
        val status = ApolloAssistanceStatus.parse(
            "{\"schema\":1,\"subscription\":\"applied\",\"controller\":\"unavailable\",\"equipment\":\"off\",\"sent\":2}")
        assertTrue(status.reported)
        assertEquals(setOf("subscription"), status.applied)
        assertEquals(setOf("controller"), status.unavailable)
    }

    @Test fun theDriverWaitsForAReportOnlyWhileAPartIsWanted() {
        val wanted = mapOf("subscription" to true, "controller" to false, "equipment" to false)
        assertTrue(ApolloAssistanceStatus.Unreported.waitingFor(wanted))
        assertFalse(ApolloAssistanceStatus(true, setOf("subscription"), emptySet()).waitingFor(wanted))
        assertFalse(ApolloAssistanceStatus.Unreported
            .waitingFor(mapOf("subscription" to false, "controller" to false, "equipment" to false)))
    }
}

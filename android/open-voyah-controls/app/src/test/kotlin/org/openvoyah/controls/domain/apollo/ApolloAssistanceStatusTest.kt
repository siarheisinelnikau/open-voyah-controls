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
            "{\"schema\":1,\"request\":\"${"a".repeat(32)}\",\"subscription\":\"applied\"," +
                "\"controller\":\"unavailable\",\"equipment\":\"off\",\"sent\":2}")
        assertTrue(status.reported)
        assertEquals(setOf("subscription"), status.applied)
        assertEquals(setOf("controller"), status.unavailable)
        assertTrue(status.requested.isEmpty())
        assertEquals("a".repeat(32), status.request)
    }

    @Test fun anAcceptedControllerSendIsNamedAsARequestAndNeverAsVerifiedState() {
        val status = ApolloAssistanceStatus.parse(
            "{\"schema\":1,\"request\":\"${"b".repeat(32)}\",\"controller\":\"requested\",\"sent\":1}")
        assertEquals(setOf("controller"), status.requested)
        assertTrue(status.applied.isEmpty())
        assertEquals("b".repeat(32), status.request)
    }

    @Test fun anUnrecordedControllerOutcomeIsNamedAsUncertainRatherThanASuccessfulRequest() {
        val status = ApolloAssistanceStatus.parse(
            "{\"schema\":1,\"request\":\"${"d".repeat(32)}\",\"controller\":\"uncertain\",\"sent\":1}")
        assertEquals(setOf("controller"), status.uncertain)
        assertTrue(status.requested.isEmpty())
        assertTrue(status.applied.isEmpty())
    }

    @Test fun onlyTheReportForTheCurrentRequestIsShown() {
        val request = "c".repeat(32)
        val current = ApolloAssistanceStatus.parse(
            "{\"schema\":1,\"request\":\"$request\",\"controller\":\"requested\",\"sent\":1}")
        val stale = ApolloAssistanceStatus.parse(
            "{\"schema\":1,\"request\":\"${"d".repeat(32)}\",\"controller\":\"unavailable\",\"sent\":1}")
        assertTrue(current.isCurrent(request))
        assertFalse(current.isCurrent("d".repeat(32)))
        assertFalse(stale.isCurrent(request))
        assertFalse(ApolloAssistanceStatus.Unreported.isCurrent(request))
    }

    @Test fun theDriverWaitsForAReportForTheCurrentRequestOnlyWhileAPartIsWanted() {
        val wanted = mapOf("subscription" to true, "controller" to false, "equipment" to false)
        val request = "e".repeat(32)
        assertTrue(ApolloAssistanceStatus.Unreported.waitingFor(wanted, request))
        val stale = ApolloAssistanceStatus(true, setOf("subscription"), emptySet(), request = "f".repeat(32))
        assertTrue(stale.waitingFor(wanted, request))
        val current = ApolloAssistanceStatus(true, setOf("subscription"), emptySet(), request = request)
        assertFalse(current.waitingFor(wanted, request))
        assertFalse(ApolloAssistanceStatus.Unreported
            .waitingFor(mapOf("subscription" to false, "controller" to false, "equipment" to false), request))
    }
}

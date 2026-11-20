package org.openvoyah.controls.data.frida

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openvoyah.controls.data.frida.WindowDiagnostics.Attempt
import org.openvoyah.controls.data.frida.WindowDiagnostics.Operation
import org.openvoyah.controls.data.frida.WindowDiagnostics.Outcome
import org.openvoyah.controls.data.frida.WindowDiagnostics.Stage
import org.openvoyah.controls.domain.display.WindowProfileDelivery
import java.io.IOException

class WindowDiagnosticsTest {
    @Test fun unknownAgentStatusProducesOnlyAllowlistedFields() {
        var now = 13L
        val entries = mutableListOf<Pair<Outcome, String>>()
        Attempt(Operation.APPLY_PROFILE, { now }) { outcome, line -> entries += outcome to line }.use {
            it.enter(Stage.REQUEST_ROUND_TRIP)
            val receipt = WindowProfileReceipt.decodeFields("unknown\napp=private.package token=private")
            assertEquals(WindowProfileDelivery.Outcome.UNCONFIRMED, receipt.delivery)
            it.received(receipt.diagnosticOutcome)
            now += 38L
            assertTrue(entries.isEmpty())
        }

        assertEquals(listOf(Outcome.INVALID_REPLY to
            "event=window_service operation=apply_profile stage=agent_reply outcome=invalid_reply elapsed_ms=38"), entries)
    }

    @Test fun missingReceiptPreservesTheErrorAndRecordsAnUnconfirmedOutcome() {
        val entries = mutableListOf<Pair<Outcome, String>>()
        val failure = IOException("private transport output")
        val result = runCatching {
            Attempt(Operation.RESET_PROFILE, { 0L }) { outcome, line -> entries += outcome to line }.use {
                it.enter(Stage.REQUEST_ROUND_TRIP)
                throw failure
            }
        }

        assertSame(failure, result.exceptionOrNull())
        assertEquals(listOf(Outcome.UNCONFIRMED to
            "event=window_service operation=reset_profile stage=request_round_trip outcome=unconfirmed elapsed_ms=0"), entries)
    }

    @Test fun partialApplicationAndRollbackFailureKeepDistinctOutcomes() {
        val applied = WindowProfileReceipt.decodeFields("applied", 1200, 180, 144)
        assertEquals(WindowProfileDelivery.Applied(1200, 180, 144), applied.delivery)
        assertEquals(Outcome.APPLIED, applied.diagnosticOutcome)

        val density = WindowProfileReceipt.decodeFields("density_only", dpi = 180)
        assertEquals(WindowProfileDelivery.DensityOnly(180), density.delivery)
        assertEquals(Outcome.DENSITY_ONLY, density.diagnosticOutcome)

        val closed = WindowProfileReceipt.decodeFields("no_task")
        assertEquals(WindowProfileDelivery.Outcome.NEXT_LAUNCH, closed.delivery)
        assertEquals(Outcome.NO_TASK, closed.diagnosticOutcome)

        val rollback = WindowProfileReceipt.decodeFields("rollback_failed")
        assertEquals(WindowProfileDelivery.Outcome.UNCONFIRMED, rollback.delivery)
        assertEquals(Outcome.ROLLBACK_FAILED, rollback.diagnosticOutcome)
    }

    @Test fun invalidGeometryCannotProduceAnAppliedOutcome() {
        val replies = listOf(
            WindowProfileReceipt.decodeFields("applied", null, 180, 144),
            WindowProfileReceipt.decodeFields("applied", 0, 180, 144),
            WindowProfileReceipt.decodeFields("applied", "1200", 180, 144),
            WindowProfileReceipt.decodeFields("applied", 1200, -1, 144),
            WindowProfileReceipt.decodeFields("applied", 1200, 180, null),
            WindowProfileReceipt.decodeFields("applied", 1200, 180, -1),
            WindowProfileReceipt.decodeFields("density_only", dpi = 180.5),
            WindowProfileReceipt.decodeFields("density_only"),
            WindowProfileReceipt.decodeFields(123, 1200, 180, 144),
        )

        for (receipt in replies) {
            assertEquals(WindowProfileDelivery.Outcome.UNCONFIRMED, receipt.delivery)
            assertEquals(Outcome.INVALID_REPLY, receipt.diagnosticOutcome)
        }
    }
}

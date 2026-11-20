package org.openvoyah.controls.domain.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleHandoffTest {
    @Test fun tokenCannotBeClaimedTwiceOrRecoveredAfterProcessDeath() {
        val handoff = RuleHandoff<String> { "opaque" }
        val token = checkNotNull(handoff.reserve("routine-receipt", 100))
        assertEquals("routine-receipt", handoff.claim(token, 200) { true })
        assertNull(handoff.claim(token, 201) { true })
        assertNull(RuleHandoff<String>().claim(token, 201) { true })
    }

    @Test fun changedConfigurationSourceOrExpiredSessionConsumesHandoffWithoutReplay() {
        val handoff = RuleHandoff<String> { "opaque" }
        val token = checkNotNull(handoff.reserve("old-session", 100))
        assertNull(handoff.claim(token, 200) { false })
        assertNull(handoff.claim(token, 201) { true })
        val next = checkNotNull(handoff.reserve("new-session", 300))
        assertNull(handoff.claim(next, 5_000_000_301L) { true })
        assertNull(handoff.claim(next, 400) { true })
    }

    @Test fun parallelLaunchDoesNotReplacePendingRuleAndOwnerLossRevokesIt() {
        val handoff = RuleHandoff<String> { "opaque" }
        val token = checkNotNull(handoff.reserve("first", 100))
        assertNull(handoff.reserve("second", 200))
        assertNull(handoff.claim("foreign-token", 200) { true })
        handoff.clear()
        assertNull(handoff.claim(token, 201) { true })
        assertEquals("opaque", handoff.reserve("third", 300))
    }

    @Test fun failedLaunchDiscardsOnlyItsOwnReservation() {
        var sequence = 0
        val handoff = RuleHandoff<String> { "token-${++sequence}" }
        val old = checkNotNull(handoff.reserve("first", 100))
        val next = checkNotNull(handoff.reserve("second", 5_000_000_101L))
        handoff.discard(old)
        assertEquals("second", handoff.claim(next, 5_000_000_102L) { true })
    }
}

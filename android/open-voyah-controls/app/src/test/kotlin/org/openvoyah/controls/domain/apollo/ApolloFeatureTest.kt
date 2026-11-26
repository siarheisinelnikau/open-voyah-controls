package org.openvoyah.controls.domain.apollo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApolloFeatureTest {
    @Test fun associatesOnlyExactKeysWithTheirSettingOrServiceRole() {
        val values = mapOf(
            "TLC_FUNC_ENABLE" to 0,
            "ISA_FUNC_ENABLE_SA" to 1,
            "ISLC_FUNC_ENABLE" to 2,
            "ISLC_FUNC_ENABLE_SA" to 3,
            "GLC_FUNC_ENABLE" to 1,
            "TLA_FUNC_ENABLE_SA" to 1,
        )
        assertEquals(ApolloFeatureRequests(0, null), ApolloFeature.TLC.requestsFrom(values))
        assertEquals(ApolloFeatureRequests(null, 1), ApolloFeature.ISA.requestsFrom(values))
        assertEquals(ApolloFeatureRequests(2, 3), ApolloFeature.ISLC.requestsFrom(values))
        for (feature in listOf(ApolloFeature.TRAFFIC_LIGHTS, ApolloFeature.GREEN_SIGNAL_SOUND, ApolloFeature.TSR)) {
            assertEquals(ApolloFeatureRequests(null, null), feature.requestsFrom(values))
        }
    }

    @Test fun aMissingFieldStaysUnknownAndEveryKeyIsInTheReadAllowlist() {
        for (feature in ApolloFeature.entries) {
            assertEquals(ApolloFeatureRequests(null, null), feature.requestsFrom(emptyMap()))
            assertTrue(ApolloCandidatePlan.AFFECTED_FLAGS.containsAll(
                listOfNotNull(feature.settingRequestKey, feature.serviceRequestKey),
            ))
        }
    }
}

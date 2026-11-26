package org.openvoyah.controls.platform.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YandexDriverGuidanceTest {
    @Test fun readsFormattedDistanceWithoutInferringFromLabelsOrEta() {
        assertEquals("350 м", YandexDriverGuidance.parseDistance("350\u00a0м"))
        assertEquals("1,2 км", YandexDriverGuidance.parseDistance("1,2\u202fкм"))
        assertEquals("900 ft", YandexDriverGuidance.parseDistance("900 ft"))
        assertNull(YandexDriverGuidance.parseDistance("Маршрут построен"))
        assertNull(YandexDriverGuidance.parseDistance("12 мин"))
        assertNull(YandexDriverGuidance.parseDistance("ул. Абая, 250 м"))
        assertNull(YandexDriverGuidance.parseDistance("21:40"))
    }
}

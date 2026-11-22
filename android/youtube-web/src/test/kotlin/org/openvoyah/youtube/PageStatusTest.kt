package org.openvoyah.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openvoyah.youtube.PageStatus.Failed

class PageStatusTest {
    @Test
    fun firstContentHidesTheLoadingScreen() {
        val load = PageLoad()
        assertEquals(PageStatus.Loading, load.status)
        load.visible()
        assertEquals(PageStatus.Shown, load.status)
    }

    @Test
    fun theWebViewErrorPageDoesNotHideAFailure() {
        val load = PageLoad().apply { started(); failed(Problem.UNREACHABLE, "net::ERR_CONNECTION_RESET (-6)") }
        load.visible()
        assertEquals(Failed(Problem.UNREACHABLE, "net::ERR_CONNECTION_RESET (-6)"), load.status)
    }

    @Test
    fun theFirstFailureOfALoadWins() {
        val load = PageLoad().apply { started(); failed(Problem.CERTIFICATE, "SSL error 3"); failed(Problem.UNREACHABLE) }
        assertEquals(Problem.CERTIFICATE, (load.status as Failed).problem)
    }

    @Test
    fun aTimeoutGivesWayToALatePageOrTheRealError() {
        val late = PageLoad().apply { started(); timedOut() }
        assertEquals(Failed(Problem.TIMEOUT, "30 s"), late.status)
        late.visible()
        assertEquals(PageStatus.Shown, late.status)

        val explained = PageLoad().apply { started(); timedOut(); failed(Problem.UNREACHABLE, "reset") }
        assertEquals(Failed(Problem.UNREACHABLE, "reset"), explained.status)
    }

    @Test
    fun aTimeoutNeverReplacesAShownPageOrAnError() {
        val shown = PageLoad().apply { visible(); timedOut() }
        assertEquals(PageStatus.Shown, shown.status)
        val failed = PageLoad().apply { failed(Problem.HTTP, "HTTP 503"); timedOut() }
        assertEquals(Problem.HTTP, (failed.status as Failed).problem)
    }

    @Test
    fun retryStartsAFreshLoad() {
        val load = PageLoad().apply { failed(Problem.NO_NETWORK); started() }
        assertEquals(PageStatus.Loading, load.status)
    }

    @Test
    fun rendererLossAlwaysShows() {
        val load = PageLoad().apply { visible(); rendererGone(crashed = false) }
        assertEquals(Failed(Problem.RENDERER_KILLED), load.status)
        load.rendererGone(crashed = true)
        assertEquals(Failed(Problem.RENDERER_CRASHED), load.status)
    }

    @Test
    fun detailsAreOneBoundedLine() {
        val detail = PageLoad.bounded("Uncaught SyntaxError:\n   Unexpected token ." + "x".repeat(500))
        assertTrue(detail.startsWith("Uncaught SyntaxError: Unexpected token ."))
        assertEquals(160, detail.length)
    }

    @Test
    fun logLineNamesProblemVersionAndDetail() {
        assertEquals("page_failed problem=timeout webview=74.0.3729.136 detail=30 s",
            PageLoad.logLine(Failed(Problem.TIMEOUT, "30 s"), "74.0.3729.136"))
        assertEquals("page_failed problem=no_webview webview=none", PageLoad.logLine(Failed(Problem.NO_WEBVIEW), null))
    }

    @Test
    fun blankProbeOnlyFlagsAnExplicitZero() {
        assertTrue(PageLoad.drewNothing("0"))
        assertFalse(PageLoad.drewNothing("42"))
        assertFalse(PageLoad.drewNothing("null"))
        assertFalse(PageLoad.drewNothing(null))
    }

    @Test
    fun webViewVersionDecidesWhetherThePageCanRun() {
        assertEquals(Problem.NO_WEBVIEW, WebViewSupport.problem(null))
        assertEquals(Problem.OLD_WEBVIEW, WebViewSupport.problem("74.0.3729.136"))
        assertNull(WebViewSupport.problem("80.0.3987.99"))
        assertNull(WebViewSupport.problem("124.0.6367.219"))
        assertNull(WebViewSupport.problem("unknown"))
        assertEquals(124, WebViewSupport.major("124.0.6367.219"))
    }
}

package org.openvoyah.youtube

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openvoyah.youtube.WebPolicy.Route

class WebPolicyTest {
    @Test
    fun youtubeAndSignInPagesStayInTheWrapper() {
        listOf(
            "https://m.youtube.com/watch?v=abc", "https://www.youtube.com/", "https://youtu.be/abc",
            "https://accounts.google.com/ServiceLogin?service=youtube", "https://accounts.youtube.com/accounts/SetSID",
            "https://consent.youtube.com/m?continue=x", "https://accounts.google.ru/", "http://youtube.com/",
            "https://www.youtube-nocookie.com/embed/abc", "about:blank",
        ).forEach { assertEquals(it, Route.IN_APP, WebPolicy.route(it)) }
    }

    @Test
    fun otherSitesOpenInTheBrowser() {
        listOf("https://example.com/", "https://notyoutube.com/", "https://youtube.com.example.org/", "mailto:a@b.c")
            .forEach { assertEquals(it, Route.EXTERNAL, WebPolicy.route(it)) }
    }

    @Test
    fun appAndStoreLinksAreIgnoredOnAUnitWithoutGooglePlay() {
        listOf(
            "intent://www.youtube.com/watch?v=abc#Intent;package=com.google.android.youtube;scheme=https;end",
            "vnd.youtube:abc", "market://details?id=com.google.android.youtube",
            "https://play.google.com/store/apps/details?id=com.google.android.youtube", "javascript:alert(1)",
            "file:///sdcard/a.html", "not a url",
        ).forEach { assertEquals(it, Route.IGNORED, WebPolicy.route(it)) }
    }

    @Test
    fun userAgentDropsOnlyTheEmbeddedViewMarkers() {
        val system = "Mozilla/5.0 (Linux; Android 11; H97 Build/RQ3A.211001.001; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/83.0.4103.106 Mobile Safari/537.36"
        assertEquals("Mozilla/5.0 (Linux; Android 11; H97 Build/RQ3A.211001.001) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/83.0.4103.106 Mobile Safari/537.36", WebPolicy.userAgent(system))
        val browser = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        assertEquals(browser, WebPolicy.userAgent(browser))
    }

    @Test
    fun recreationReopensOnlyAShortYouTubeAddress() {
        assertEquals("https://m.youtube.com/watch?v=abc", WebPolicy.resumeUrl("https://m.youtube.com/watch?v=abc"))
        listOf(null, "https://example.com/", "about:blank", "data:text/html;base64,AAAA",
            "https://m.youtube.com/results?q=" + "a".repeat(3000))
            .forEach { assertEquals(it, WebPolicy.START_URL, WebPolicy.resumeUrl(it)) }
    }

    @Test
    fun mediaTitleDropsTheSiteNameAndTheUnreadCount() {
        assertEquals("Song", WebPolicy.mediaTitle("Song - YouTube"))
        assertEquals("Song - Live", WebPolicy.mediaTitle("(3) Song - Live - YouTube"))
        assertEquals("Song", WebPolicy.mediaTitle("(99+) Song - YouTube"))
        assertEquals("YouTube", WebPolicy.mediaTitle("YouTube"))
        assertEquals("", WebPolicy.mediaTitle(""))
    }

    @Test
    fun pageScaleFollowsTheWindowDensityOverTheDisplayDensity() {
        assertEquals(1.5f, WebPolicy.pageScale(windowDpi = 240, displayDpi = 160), 0.001f)
        assertEquals(0.75f, WebPolicy.pageScale(windowDpi = 120, displayDpi = 160), 0.001f)
        assertEquals(1f, WebPolicy.pageScale(windowDpi = 160, displayDpi = 160), 0f)
        assertEquals(1f, WebPolicy.pageScale(windowDpi = 0, displayDpi = 160), 0f)
        assertEquals(1f, WebPolicy.pageScale(windowDpi = 240, displayDpi = 0), 0f)
        assertEquals(2.5f, WebPolicy.pageScale(windowDpi = 640, displayDpi = 160), 0.001f)
    }

    @Test
    fun backClosesFullScreenThenWalksHistoryThenLeaves() {
        assertEquals(WebPolicy.Back.EXIT_FULLSCREEN, WebPolicy.back(fullscreen = true, canGoBack = true))
        assertEquals(WebPolicy.Back.PAGE_BACK, WebPolicy.back(fullscreen = false, canGoBack = true))
        assertEquals(WebPolicy.Back.LEAVE, WebPolicy.back(fullscreen = false, canGoBack = false))
    }
}

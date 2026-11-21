package org.openvoyah.youtube

import java.net.URI
import java.util.Locale

/** What the wrapper loads, which navigations stay inside it and how it introduces itself to the site. */
internal object WebPolicy {
    /** The touch layout. It adapts to a wide car window and works signed out, with no Google services on the unit. */
    const val START_URL = "https://m.youtube.com/"

    enum class Route {
        /** Stays in the wrapper: YouTube itself and the Google pages that signing in passes through. */
        IN_APP,
        /** Another site from a description or a comment, handed to the head unit's browser. */
        EXTERNAL,
        /** App-store, intent and YouTube-app links that cannot work on a unit without Google Play. */
        IGNORED,
    }

    private val inAppDomains = listOf("youtube.com", "youtu.be", "youtube-nocookie.com", "google.com")
    private val ignoredSchemes = setOf("intent", "vnd.youtube", "market", "android-app", "javascript", "file", "content")

    fun route(url: String): Route {
        val uri = try { URI(url.trim()) } catch (_: Exception) { return Route.IGNORED }
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return Route.IGNORED
        if (scheme == "about" || scheme == "data" || scheme == "blob") return Route.IN_APP
        if (scheme in ignoredSchemes) return Route.IGNORED
        if (scheme != "https" && scheme != "http") return Route.EXTERNAL
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return Route.IGNORED
        if (host == "play.google.com" || host == "market.android.com") return Route.IGNORED
        if (inAppDomains.any { host == it || host.endsWith(".$it") }) return Route.IN_APP
        // Sign-in can pass through a country domain such as accounts.google.ru.
        if (host.startsWith("accounts.google.")) return Route.IN_APP
        return Route.EXTERNAL
    }

    /** The page to reopen after a recreation or a renderer crash: a YouTube address of reasonable length, else the feed. */
    fun resumeUrl(url: String?): String {
        if (url == null || url.length > MAX_RESUME_URL || route(url) != Route.IN_APP) return START_URL
        val scheme = url.substringBefore(':').lowercase(Locale.ROOT)
        return if (scheme == "https" || scheme == "http") url else START_URL
    }

    private const val MAX_RESUME_URL = 2048

    /**
     * The system WebView agent minus its embedded-view markers, so the site treats the wrapper as the browser it is
     * built on. Google refuses to sign in inside a view that announces itself as embedded; the engine stays the real one.
     */
    fun userAgent(system: String): String = system
        .replace("; wv)", ")")
        .replace(versionToken, "")

    private val versionToken = Regex(" Version/\\d+(?:\\.\\d+)*")

    /**
     * The video title for the media session. The WebView has no Media Session API for the page to fill, so the title
     * comes from the document: "(3) Song - YouTube" becomes "Song"; the feed's bare "YouTube" stays as it is.
     */
    fun mediaTitle(documentTitle: String): String {
        val title = documentTitle.trim().replace(unreadCount, "")
        return title.removeSuffix(" - YouTube").trim().ifEmpty { title }
    }

    private val unreadCount = Regex("^\\(\\d+\\+?\\)\\s+")

    /**
     * How much larger than the display's own scale the page is drawn: the window's density from its DPI profile over
     * the display's density. Unknown densities and a profile that matches the display leave the page as it is.
     */
    fun pageScale(windowDpi: Int, displayDpi: Int): Float {
        if (windowDpi <= 0 || displayDpi <= 0) return 1f
        val scale = (windowDpi.toFloat() / displayDpi).coerceIn(MIN_PAGE_SCALE, MAX_PAGE_SCALE)
        return if (kotlin.math.abs(scale - 1f) < 0.01f) 1f else scale
    }

    private const val MIN_PAGE_SCALE = 0.5f
    private const val MAX_PAGE_SCALE = 2.5f

    enum class Back { EXIT_FULLSCREEN, PAGE_BACK, LEAVE }

    /** Back closes full screen first, then walks the page history, then leaves the app like a media app does. */
    fun back(fullscreen: Boolean, canGoBack: Boolean): Back = when {
        fullscreen -> Back.EXIT_FULLSCREEN
        canGoBack -> Back.PAGE_BACK
        else -> Back.LEAVE
    }
}

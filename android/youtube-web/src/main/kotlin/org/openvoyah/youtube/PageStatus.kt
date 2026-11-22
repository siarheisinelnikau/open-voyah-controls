package org.openvoyah.youtube

/** Why the page area shows a message instead of YouTube. */
internal enum class Problem {
    /** Android has no WebView provider, or creating one failed. */
    NO_WEBVIEW,
    /** The WebView is older than YouTube's scripts expect; the driver may still try. */
    OLD_WEBVIEW,
    /** Android reports no network with internet access. */
    NO_NETWORK,
    /** A network is up but YouTube did not answer: blocked, filtered or down. */
    UNREACHABLE,
    /** YouTube answered the page request with an HTTP error. */
    HTTP,
    /** The connection's certificate was refused, often a wrong clock or a filtering proxy. */
    CERTIFICATE,
    /** The page's renderer crashed. */
    RENDERER_CRASHED,
    /** Android ended the page's renderer to free memory. */
    RENDERER_KILLED,
    /** Nothing arrived within [PageLoad.TIMEOUT_MS]. */
    TIMEOUT,
    /** The page loaded but drew nothing, usually because its scripts do not run on this WebView. */
    BLANK,
}

internal sealed interface PageStatus {
    /** A page load is under way and nothing of it is visible yet. */
    data object Loading : PageStatus
    /** The page is visible. */
    data object Shown : PageStatus
    data class Failed(val problem: Problem, val detail: String = "") : PageStatus
}

/**
 * What covers the page during one main-frame load. The first real failure of a load wins, so the WebView's own error
 * page committing afterwards does not hide it. A timeout is only provisional: a page that arrives late replaces it,
 * and a real error that explains it replaces it too. A renderer loss always shows, whatever the load did.
 */
internal class PageLoad {
    var status: PageStatus = PageStatus.Loading
        private set

    fun started() {
        status = PageStatus.Loading
    }

    fun visible() {
        if (status == PageStatus.Loading || timedOut) status = PageStatus.Shown
    }

    fun failed(problem: Problem, detail: String = "") {
        if (status is PageStatus.Failed && !timedOut) return
        status = PageStatus.Failed(problem, bounded(detail))
    }

    fun timedOut() {
        if (status == PageStatus.Loading) status = PageStatus.Failed(Problem.TIMEOUT, "${TIMEOUT_MS / 1000} s")
    }

    fun rendererGone(crashed: Boolean) {
        status = PageStatus.Failed(if (crashed) Problem.RENDERER_CRASHED else Problem.RENDERER_KILLED)
    }

    private val timedOut get() = (status as? PageStatus.Failed)?.problem == Problem.TIMEOUT

    companion object {
        /** Long enough for a slow mobile connection, short enough that a blocked site does not look like a black app. */
        const val TIMEOUT_MS = 30_000L
        /** After this the loading screen says the page is still on its way. */
        const val SLOW_MS = 10_000L
        /** How long after the first content the page gets to draw something before it counts as blank. */
        const val BLANK_CHECK_MS = 15_000L
        private const val MAX_DETAIL = 160

        /** One line of at most [MAX_DETAIL] characters: error texts can carry long URLs or stack traces. */
        fun bounded(text: String): String = text.replace(whitespace, " ").trim().take(MAX_DETAIL)

        private val whitespace = Regex("\\s+")

        /** A logcat line for a failure: the problem, its bounded detail and the WebView version, never a page address. */
        fun logLine(failure: PageStatus.Failed, webView: String?): String =
            "page_failed problem=${failure.problem.name.lowercase()} webview=${webView ?: "none"}" +
                if (failure.detail.isEmpty()) "" else " detail=${failure.detail}"

        /**
         * The blank check's answer: the page's text length plus its images and videos, as the WebView returns it from
         * JavaScript. Zero means the page drew nothing; anything unreadable counts as drawn, so it never raises a
         * false alarm.
         */
        fun drewNothing(probeResult: String?): Boolean = probeResult?.trim()?.toIntOrNull() == 0

        /** JavaScript for [drewNothing]. */
        const val BLANK_PROBE = "(function(){var b=document.body;if(!b)return 0;" +
            "return (b.innerText||'').trim().length+document.images.length+document.getElementsByTagName('video').length})()"
    }
}

/** Whether the installed WebView can run the page at all, judged from its provider's version. */
internal object WebViewSupport {
    /**
     * The oldest WebView the wrapper opens without a warning. Chromium 80 is the first with the ES2020 syntax current
     * web apps ship; YouTube's scripts on an older engine tend to stop before anything is drawn.
     */
    const val MIN_MAJOR = 80

    fun major(version: String?): Int? = version?.trim()?.substringBefore('.')?.toIntOrNull()

    /** [version] is the provider's version name, or null when Android reports no WebView provider. */
    fun problem(version: String?): Problem? {
        if (version == null) return Problem.NO_WEBVIEW
        val major = major(version) ?: return null
        return if (major < MIN_MAJOR) Problem.OLD_WEBVIEW else null
    }
}

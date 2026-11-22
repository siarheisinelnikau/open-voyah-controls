package org.openvoyah.youtube

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast

/**
 * YouTube's touch site in the system WebView, as its own launcher app so Controls lists it and the window service
 * applies its width and DPI profile like any other app's. Until the page shows something, and whenever it cannot,
 * [StatusView] covers it with a spinner or the reason, so the window is never silently black.
 */
class YouTubeActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var page: ScaledFrame
    private lateinit var status: StatusView
    private lateinit var player: PlayerHost
    private var web: WebView? = null
    private val load = PageLoad()
    private val main = Handler(Looper.getMainLooper())
    private val webViewVersion: String? by lazy {
        try { WebView.getCurrentWebViewPackage()?.versionName } catch (_: RuntimeException) { null }
    }
    private val bridge by lazy { assets.open("player-bridge.js").bufferedReader().use { it.readText() } }
    private var fullscreen: View? = null
    private var fullscreenDone: WebChromeClient.CustomViewCallback? = null
    /** The address the current main-frame load is for, so Retry repeats it. */
    private var target = WebPolicy.START_URL
    /** The first script error of the current load, shown when the page stays blank. */
    private var scriptError = ""
    private var logged: PageStatus? = null
    private val timeout = Runnable { load.timedOut(); render() }
    private val slow = Runnable { status.slow() }
    private val blankCheck = Runnable { checkBlank() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            fitsSystemWindows = true
        }
        player = PlayerHost(this, ::script) { playing ->
            if (playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        page = ScaledFrame(this).apply { scale = pageScale() }
        status = StatusView(this, ::retry, ::openAnyway)
        root.addView(page, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(status, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)
        target = WebPolicy.resumeUrl(savedInstanceState?.getString(STATE_URL))
        Log.i(TAG, "start webview=${webViewVersion ?: "none"} scale=${page.scale}")
        when (val problem = WebViewSupport.problem(webViewVersion)) {
            null -> open(target)
            else -> fail(problem)
        }
    }

    override fun onStart() {
        super.onStart()
        web?.onResume()
    }

    override fun onStop() {
        player.hidden()
        web?.onPause()
        CookieManager.getInstance().flush()
        super.onStop()
    }

    /**
     * Only the page address survives a recreation, after a DPI change for example. The WebView's own saved state holds
     * the whole history with page data and can exceed the binder limit, which makes Android kill the app when it stops.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_URL, WebPolicy.resumeUrl(web?.url ?: target))
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        player.release()
        web?.let { page.removeView(it); it.destroy() }
        web = null
        super.onDestroy()
    }

    @Deprecated("Android 13 and newer also deliver Back here while the app does not opt in to predictive back.")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val view = web
        when (WebPolicy.back(fullscreen != null, view?.canGoBack() == true && load.status == PageStatus.Shown)) {
            WebPolicy.Back.EXIT_FULLSCREEN -> fullscreenDone?.onCustomViewHidden() ?: leaveFullscreen()
            WebPolicy.Back.PAGE_BACK -> view?.goBack()
            // Keep the page and its history for the next launch from the grid; leaving pauses the video.
            WebPolicy.Back.LEAVE -> moveTaskToBack(true)
        }
    }

    /** Loads [url], creating the WebView first; a WebView that cannot be created is reported, not thrown. */
    private fun open(url: String) {
        target = url
        val view = web ?: try {
            newWebView().also {
                web = it
                page.addView(it, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
        } catch (error: RuntimeException) {
            fail(Problem.NO_WEBVIEW, error.javaClass.simpleName)
            return
        }
        started()
        view.loadUrl(url)
    }

    private fun retry() {
        if (web == null && WebViewSupport.problem(webViewVersion) == Problem.NO_WEBVIEW) recreate()
        else open(WebPolicy.resumeUrl(target))
    }

    private fun openAnyway() = open(target)

    private fun started() {
        scriptError = ""
        load.started()
        main.removeCallbacks(timeout)
        main.removeCallbacks(slow)
        main.removeCallbacks(blankCheck)
        main.postDelayed(slow, PageLoad.SLOW_MS)
        main.postDelayed(timeout, PageLoad.TIMEOUT_MS)
        render()
    }

    private fun visible() {
        val wasShown = load.status == PageStatus.Shown
        load.visible()
        if (load.status == PageStatus.Shown && !wasShown) {
            main.removeCallbacks(blankCheck)
            main.postDelayed(blankCheck, PageLoad.BLANK_CHECK_MS)
        }
        render()
    }

    private fun fail(problem: Problem, detail: String = "") {
        load.failed(problem, detail)
        render()
    }

    private fun checkBlank() {
        val view = web ?: return
        if (load.status != PageStatus.Shown || fullscreen != null) return
        view.evaluateJavascript(PageLoad.BLANK_PROBE) { result ->
            if (load.status == PageStatus.Shown && PageLoad.drewNothing(result)) {
                load.failed(Problem.BLANK, scriptError)
                render()
            }
        }
    }

    private fun render() {
        val current = load.status
        if (current != PageStatus.Loading) {
            main.removeCallbacks(timeout)
            main.removeCallbacks(slow)
        }
        if (current is PageStatus.Failed && current != logged) Log.w(TAG, PageLoad.logLine(current, webViewVersion))
        logged = current
        status.show(current, webViewVersion)
    }

    private fun online(): Boolean {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return true
        val network = connectivity.activeNetwork ?: return false
        return connectivity.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != false
    }

    /** The DPI profile's density relative to the display's, which the WebView would otherwise ignore. */
    private fun pageScale(): Float {
        val display = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(display)
        return WebPolicy.pageScale(resources.configuration.densityDpi, display.densityDpi)
    }

    private fun script(code: String) {
        if (!isDestroyed) web?.evaluateJavascript(code, null)
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun newWebView(): WebView = WebView(this).also { view ->
        view.setBackgroundColor(Color.BLACK)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = false
            builtInZoomControls = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = WebPolicy.userAgent(userAgentString)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        view.addJavascriptInterface(player, "OvcYouTubeHost")
        view.webViewClient = Client()
        view.webChromeClient = Chrome()
    }

    private fun openOutside(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    private fun leaveFullscreen() {
        val view = fullscreen ?: return
        page.removeView(view)
        fullscreen = null
        fullscreenDone = null
        web?.visibility = View.VISIBLE
        systemBars(show = true)
    }

    private fun systemBars(show: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController ?: return
            if (show) controller.show(WindowInsets.Type.systemBars())
            else {
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (show) View.SYSTEM_UI_FLAG_VISIBLE else
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            return when (WebPolicy.route(url)) {
                WebPolicy.Route.IN_APP -> false
                WebPolicy.Route.IGNORED -> true
                WebPolicy.Route.EXTERNAL -> {
                    if (!request.isForMainFrame) return false
                    openOutside(url)
                    true
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (url != null && WebPolicy.route(url) == WebPolicy.Route.IN_APP && !url.startsWith("data:")) target = url
            started()
        }

        override fun onPageCommitVisible(view: WebView, url: String?) = visible()

        override fun onPageFinished(view: WebView, url: String?) {
            view.evaluateJavascript(bridge, null)
            visible()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            fail(if (online()) Problem.UNREACHABLE else Problem.NO_NETWORK, "${error.description} (${error.errorCode})")
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (request.isForMainFrame && response.statusCode >= 400) {
                fail(Problem.HTTP, "HTTP ${response.statusCode} ${response.reasonPhrase.orEmpty()}")
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            if (load.status == PageStatus.Loading) fail(Problem.CERTIFICATE, "SSL error ${error.primaryError}")
        }

        /** The renderer died: report it and keep a fresh WebView ready, so Retry does not start from a dead one. */
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            target = WebPolicy.resumeUrl(view.url ?: target)
            leaveFullscreen()
            page.removeView(view)
            view.destroy()
            if (web === view) web = null
            load.rendererGone(detail.didCrash())
            render()
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (fullscreen != null) { callback.onCustomViewHidden(); return }
            fullscreen = view
            fullscreenDone = callback
            page.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            web?.visibility = View.INVISIBLE
            systemBars(show = false)
        }

        override fun onHideCustomView() = leaveFullscreen()

        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR && scriptError.isEmpty()) {
                scriptError = PageLoad.bounded(message.message())
            }
            return false
        }

        /** A black frame instead of the grey play arrow the WebView draws before a video's first frame. */
        override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        const val TAG = "OvcYouTube"
        const val STATE_URL = "url"
    }
}

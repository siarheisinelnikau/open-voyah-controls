package org.openvoyah.youtube

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * YouTube's touch site in the system WebView, as its own launcher app so Controls lists it and the window service
 * applies its width and DPI profile like any other app's.
 */
class YouTubeActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var page: ScaledFrame
    private lateinit var web: WebView
    private lateinit var offline: View
    private lateinit var player: PlayerHost
    private val bridge by lazy { assets.open("player-bridge.js").bufferedReader().use { it.readText() } }
    private var fullscreen: View? = null
    private var fullscreenDone: WebChromeClient.CustomViewCallback? = null

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
        web = newWebView()
        offline = offlineView()
        page.addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(page, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(offline, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)
        web.loadUrl(WebPolicy.resumeUrl(savedInstanceState?.getString(STATE_URL)))
    }

    override fun onStart() {
        super.onStart()
        web.onResume()
    }

    override fun onStop() {
        player.hidden()
        web.onPause()
        CookieManager.getInstance().flush()
        super.onStop()
    }

    /**
     * Only the page address survives a recreation, after a DPI change for example. The WebView's own saved state holds
     * the whole history with page data and can exceed the binder limit, which makes Android kill the app when it stops.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_URL, WebPolicy.resumeUrl(web.url))
    }

    override fun onDestroy() {
        player.release()
        page.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    @Deprecated("Android 13 and newer also deliver Back here while the app does not opt in to predictive back.")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when (WebPolicy.back(fullscreen != null, web.canGoBack())) {
            WebPolicy.Back.EXIT_FULLSCREEN -> fullscreenDone?.onCustomViewHidden() ?: leaveFullscreen()
            WebPolicy.Back.PAGE_BACK -> web.goBack()
            // Keep the page and its history for the next launch from the grid; leaving pauses the video.
            WebPolicy.Back.LEAVE -> moveTaskToBack(true)
        }
    }

    /** The DPI profile's density relative to the display's, which the WebView would otherwise ignore. */
    private fun pageScale(): Float {
        val display = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(display)
        return WebPolicy.pageScale(resources.configuration.densityDpi, display.densityDpi)
    }

    private fun script(code: String) {
        if (!isDestroyed) web.evaluateJavascript(code, null)
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

    private fun offlineView(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.BLACK)
        visibility = View.GONE
        isClickable = true
        addView(TextView(context).apply {
            setText(R.string.page_unavailable)
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        addView(Button(context).apply {
            setText(R.string.retry)
            textSize = 20f
            setOnClickListener {
                offline.visibility = View.GONE
                if (web.url == null) web.loadUrl(WebPolicy.START_URL) else web.reload()
            }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = (24 * resources.displayMetrics.density).toInt() })
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
        web.visibility = View.VISIBLE
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
            offline.visibility = View.GONE
        }

        override fun onPageFinished(view: WebView, url: String?) {
            view.evaluateJavascript(bridge, null)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) offline.visibility = View.VISIBLE
        }

        /** The renderer died, usually from memory pressure on the head unit: start a fresh page instead of closing. */
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            val url = WebPolicy.resumeUrl(view.url)
            leaveFullscreen()
            page.removeView(view)
            view.destroy()
            web = newWebView()
            page.addView(web, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            web.loadUrl(url)
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (fullscreen != null) { callback.onCustomViewHidden(); return }
            fullscreen = view
            fullscreenDone = callback
            page.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            web.visibility = View.INVISIBLE
            systemBars(show = false)
        }

        override fun onHideCustomView() = leaveFullscreen()

        /** A black frame instead of the grey play arrow the WebView draws before a video's first frame. */
        override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        const val STATE_URL = "url"
    }
}

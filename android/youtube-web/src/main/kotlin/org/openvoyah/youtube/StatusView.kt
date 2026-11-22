package org.openvoyah.youtube

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Covers the page while it loads or after it failed, so the window is never a silent black rectangle: a spinner while
 * loading, then the reason in words, the WebView version and the action that can help.
 */
internal class StatusView(context: Context, retry: () -> Unit, openAnyway: () -> Unit) : LinearLayout(context) {
    private val progress = ProgressBar(context).apply { isIndeterminate = true }
    private val message = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 22f
        gravity = Gravity.CENTER
    }
    private val detail = TextView(context).apply {
        setTextColor(Color.rgb(0xB0, 0xB0, 0xB0))
        textSize = 15f
        gravity = Gravity.CENTER
    }
    private val retryButton = Button(context).apply {
        setText(R.string.retry)
        textSize = 20f
        setOnClickListener { retry() }
    }
    private val anywayButton = Button(context).apply {
        setText(R.string.open_anyway)
        textSize = 20f
        setOnClickListener { openAnyway() }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.BLACK)
        isClickable = true
        val gap = (16 * resources.displayMetrics.density).toInt()
        setPadding(gap * 3, gap, gap * 3, gap)
        addView(progress, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = gap })
        addView(message, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(detail, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = gap / 2 })
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            addView(anywayButton)
            addView(retryButton)
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = gap })
    }

    /** Shows [status]; [webView] is the provider version, or null when there is none. */
    fun show(status: PageStatus, webView: String?) {
        val version = webView?.let { context.getString(R.string.webview_version, it) } ?: context.getString(R.string.webview_none)
        when (status) {
            PageStatus.Shown -> visibility = GONE
            PageStatus.Loading -> {
                visibility = VISIBLE
                progress.visibility = VISIBLE
                message.setText(R.string.loading)
                detail.text = version
                retryButton.visibility = GONE
                anywayButton.visibility = GONE
            }
            is PageStatus.Failed -> {
                visibility = VISIBLE
                progress.visibility = GONE
                message.text = text(status.problem, webView)
                detail.text = if (status.detail.isEmpty()) version else "${status.detail} · $version"
                anywayButton.visibility = if (status.problem == Problem.OLD_WEBVIEW) VISIBLE else GONE
                retryButton.visibility = if (status.problem == Problem.OLD_WEBVIEW) GONE else VISIBLE
            }
        }
    }

    /** The load is taking long; says so without giving up on it. */
    fun slow() {
        if (visibility == VISIBLE && progress.visibility == VISIBLE) message.setText(R.string.still_loading)
    }

    private fun text(problem: Problem, webView: String?): String = when (problem) {
        Problem.NO_WEBVIEW -> context.getString(R.string.problem_no_webview)
        Problem.OLD_WEBVIEW -> context.getString(R.string.problem_old_webview, webView ?: "?", WebViewSupport.MIN_MAJOR)
        Problem.NO_NETWORK -> context.getString(R.string.problem_no_network)
        Problem.UNREACHABLE -> context.getString(R.string.problem_unreachable)
        Problem.HTTP -> context.getString(R.string.problem_http)
        Problem.CERTIFICATE -> context.getString(R.string.problem_certificate)
        Problem.RENDERER_CRASHED -> context.getString(R.string.problem_renderer_crashed)
        Problem.RENDERER_KILLED -> context.getString(R.string.problem_renderer_killed)
        Problem.TIMEOUT -> context.getString(R.string.problem_timeout, (PageLoad.TIMEOUT_MS / 1000).toInt())
        Problem.BLANK -> context.getString(R.string.problem_blank)
    }
}

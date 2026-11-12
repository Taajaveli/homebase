package com.taajaveli.slamvision

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/** Full-screen TV wrapper for the SlamVision web channel. */
class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private val navScript by lazy {
        try { assets.open("tvnav.js").bufferedReader().use { it.readText() } } catch (e: Exception) { "" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = "$userAgentString SlamVisionTV/1.0"
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase()
                if (scheme == "http" || scheme == "https") return false
                // intent:, market:, etc. → hand to Android, never crash.
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) { }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (navScript.isNotEmpty()) view.evaluateJavascript(navScript, null)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showOffline()
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (fullscreenView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                fullscreenView = view
                fullscreenCallback = callback
                root.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
                web.visibility = View.GONE
                view.isFocusable = true
                view.requestFocus()
            }

            override fun onHideCustomView() = exitFullscreen()

            // Hides the grey "play" placeholder Android draws before video starts.
            override fun getDefaultVideoPoster(): Bitmap =
                Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }

        root.addView(web, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)

        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            web.loadUrl(HOME_URL)
        }
        web.requestFocus()
    }

    private fun exitFullscreen() {
        val view = fullscreenView ?: return
        root.removeView(view)
        fullscreenView = null
        web.visibility = View.VISIBLE
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        web.requestFocus()
    }

    private fun showOffline() {
        val html = """
            <html><body style="background:#000;color:#fff;font-family:sans-serif;display:flex;
              align-items:center;justify-content:center;height:100vh;margin:0;text-align:center">
              <div><h1 style="color:#ffd400">Can't reach SlamVision</h1>
              <p>Check the box's internet connection.</p>
              <button autofocus onclick="location.href='$HOME_URL'"
                style="font-size:24px;padding:12px 32px;border-radius:8px;border:0;background:#ffd400">
                Try again</button></div></body></html>
        """.trimIndent()
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    private fun showMenu() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("SlamVision")
            .setItems(arrayOf("Reload", "Back to the guide", "Exit")) { _, which ->
                when (which) {
                    0 -> web.reload()
                    1 -> { exitFullscreen(); web.loadUrl(HOME_URL) }
                    2 -> finish()
                }
            }
            .show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                if (event.action == KeyEvent.ACTION_UP) showMenu()
                return true
            }
            // Channel up/down on the remote act like Page Up / Page Down in the page.
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (fullscreenView == null) {
                    val code = if (event.keyCode == KeyEvent.KEYCODE_CHANNEL_UP)
                        KeyEvent.KEYCODE_PAGE_UP else KeyEvent.KEYCODE_PAGE_DOWN
                    web.dispatchKeyEvent(KeyEvent(event.action, code))
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        when {
            fullscreenView != null -> exitFullscreen()
            web.canGoBack() -> web.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    private companion object {
        const val HOME_URL = "https://ring-channel-tv.emergent.host"
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}

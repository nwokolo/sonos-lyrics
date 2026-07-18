package com.nwokolo.sonoslyrics

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.app.Activity

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private val handler = Handler(Looper.getMainLooper())

    private var currentUrl: String = DEFAULT_URL
    private var pageLoaded = false
    private var retryPending = false

    private val retryRunnable = Runnable {
        retryPending = false
        if (!pageLoaded) webView.loadUrl(currentUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        currentUrl = prefs.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
        applyKeepScreenOn(prefs.getBoolean(KEY_KEEP_ON, true))

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        webView = WebView(this)
        webView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        webView.setBackgroundColor(Color.BLACK)
        configureWebView()
        root.addView(webView)

        // Hidden settings trigger: long-press the top-left corner.
        val hotspot = View(this)
        val size = (80 * resources.displayMetrics.density).toInt()
        val hp = FrameLayout.LayoutParams(size, size)
        hp.gravity = Gravity.TOP or Gravity.START
        hotspot.layoutParams = hp
        hotspot.setOnLongClickListener {
            showSettingsDialog()
            true
        }
        root.addView(hotspot)

        setContentView(root)
        enterImmersive()

        webView.loadUrl(currentUrl)
    }

    private fun configureWebView() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.useWideViewPort = true
        s.loadWithOverviewMode = true

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                // Keep all navigation inside the app; never hand off to a browser.
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                pageLoaded = false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                pageLoaded = true
                cancelRetry()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                // Server may be briefly unreachable (reboot, network blip) — keep retrying.
                if (request?.isForMainFrame == true) scheduleRetry()
            }
        }
    }

    private fun scheduleRetry() {
        if (retryPending) return
        retryPending = true
        handler.postDelayed(retryRunnable, RETRY_MS)
    }

    private fun cancelRetry() {
        retryPending = false
        handler.removeCallbacks(retryRunnable)
    }

    private fun applyKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun enterImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    private fun showSettingsDialog() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(pad, pad, pad, 0)

        val input = EditText(this)
        input.inputType = InputType.TYPE_TEXT_VARIATION_URI
        input.setText(currentUrl)
        input.hint = DEFAULT_URL
        container.addView(input)

        val keepOn = CheckBox(this)
        keepOn.text = getString(R.string.keep_screen_on)
        keepOn.isChecked = prefs.getBoolean(KEY_KEEP_ON, true)
        container.addView(keepOn)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val newUrl = input.text.toString().trim().ifEmpty { DEFAULT_URL }
                prefs.edit()
                    .putString(KEY_URL, newUrl)
                    .putBoolean(KEY_KEEP_ON, keepOn.isChecked)
                    .apply()
                currentUrl = newUrl
                applyKeepScreenOn(keepOn.isChecked)
                cancelRetry()
                webView.loadUrl(currentUrl)
                enterImmersive()
            }
            .setNegativeButton(R.string.reload) { _, _ ->
                cancelRetry()
                webView.reload()
                enterImmersive()
            }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        cancelRetry()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        // The Sonos Lyrics server (Proxmox LXC container). Override per-device
        // via the hidden settings dialog (long-press the top-left corner).
        private const val DEFAULT_URL = "http://192.168.86.127:3000"
        private const val PREFS = "sonos_lyrics_prefs"
        private const val KEY_URL = "server_url"
        private const val KEY_KEEP_ON = "keep_screen_on"
        private const val RETRY_MS = 5000L
    }
}

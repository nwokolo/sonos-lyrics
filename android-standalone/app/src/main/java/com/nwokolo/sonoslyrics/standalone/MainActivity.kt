package com.nwokolo.sonoslyrics.standalone

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.net.wifi.WifiManager
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
import android.widget.TextView

/**
 * Self-contained Sonos Lyrics: runs the whole backend on-device via an embedded
 * HTTP server and points a fullscreen WebView at it. No external server needed.
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private val handler = Handler(Looper.getMainLooper())

    private var server: EmbeddedServer? = null
    private var serverPort = 0
    private var multicastLock: WifiManager.MulticastLock? = null

    private var baseUrl = ""
    private var pageLoaded = false
    private var retryPending = false

    private val retryRunnable = Runnable {
        retryPending = false
        if (!pageLoaded) webView.loadUrl(baseUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        applyKeepScreenOn(prefs.getBoolean(KEY_KEEP_ON, true))
        Sonos.staticHosts = parseHosts(prefs.getString(KEY_HOSTS, "") ?: "")

        acquireMulticastLock()
        startServer()

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

        webView.loadUrl(baseUrl)
    }

    private fun startServer() {
        // Bind on the first free port in a small range on the loopback interface.
        for (port in PORT_START..PORT_END) {
            try {
                val s = EmbeddedServer(assets, port)
                s.start(NanoHttpTimeout, false)
                server = s
                serverPort = port
                baseUrl = "http://127.0.0.1:$port/"
                return
            } catch (e: Exception) {
                // port busy or failed to bind — try the next one
            }
        }
        // Should not happen; fall back so the WebView shows an error instead of crashing.
        baseUrl = "about:blank"
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("sonos-lyrics-ssdp").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            // Multicast lock is best-effort; SSDP may still work, or user can set static IPs.
        }
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
            ): Boolean = false

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
                // The embedded server should be up immediately, but retry just in case.
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

        val hostsInput = EditText(this)
        hostsInput.inputType = InputType.TYPE_CLASS_TEXT
        hostsInput.hint = getString(R.string.static_hosts_hint)
        hostsInput.setText(prefs.getString(KEY_HOSTS, "") ?: "")
        container.addView(hostsInput)

        val hint = TextView(this)
        hint.text = getString(R.string.static_hosts_label)
        hint.alpha = 0.7f
        container.addView(hint)

        val keepOn = CheckBox(this)
        keepOn.text = getString(R.string.keep_screen_on)
        keepOn.isChecked = prefs.getBoolean(KEY_KEEP_ON, true)
        container.addView(keepOn)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val hosts = hostsInput.text.toString().trim()
                prefs.edit()
                    .putString(KEY_HOSTS, hosts)
                    .putBoolean(KEY_KEEP_ON, keepOn.isChecked)
                    .apply()
                Sonos.staticHosts = parseHosts(hosts)
                applyKeepScreenOn(keepOn.isChecked)
                cancelRetry()
                webView.loadUrl(baseUrl)
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

    private fun parseHosts(raw: String): List<String> =
        raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }

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
        server?.stop()
        multicastLock?.let { if (it.isHeld) it.release() }
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val PREFS = "sonos_lyrics_prefs"
        private const val KEY_HOSTS = "static_hosts"
        private const val KEY_KEEP_ON = "keep_screen_on"
        private const val RETRY_MS = 1500L
        private const val PORT_START = 8730
        private const val PORT_END = 8749
        // NanoHTTPD socket read timeout (ms) for keep-alive connections.
        private const val NanoHttpTimeout = 15000
    }
}

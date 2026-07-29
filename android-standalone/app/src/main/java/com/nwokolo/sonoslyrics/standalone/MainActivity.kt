package com.nwokolo.sonoslyrics.standalone

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
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
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Self-contained Sonos Lyrics: runs the whole backend on-device via an embedded
 * HTTP server and points a fullscreen WebView at it. No external server needed.
 *
 * Monetized build: a bottom anchored-adaptive AdMob banner plus an interstitial
 * shown occasionally when the playing track changes (rate-limited). Ad consent
 * is gathered up front via the User Messaging Platform (UMP) so the app is
 * Play/GDPR compliant.
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var adContainer: FrameLayout
    private val handler = Handler(Looper.getMainLooper())

    private var server: EmbeddedServer? = null
    private var serverPort = 0
    private var multicastLock: WifiManager.MulticastLock? = null

    private var baseUrl = ""
    private var pageLoaded = false
    private var retryPending = false

    // ---- Ads state --------------------------------------------------------
    private lateinit var consentInformation: ConsentInformation
    private val adsInitialized = AtomicBoolean(false)
    private var adView: AdView? = null
    private var interstitialAd: InterstitialAd? = null
    // Skip the very first track so the app never opens straight into an ad.
    private val firstTrackSeen = AtomicBoolean(false)
    private var lastInterstitialAt = 0L

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

        // Vertical stack: WebView fills the space above a bottom banner strip so
        // ads never cover the album art / lyrics.
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setBackgroundColor(Color.BLACK)

        val contentFrame = FrameLayout(this)
        contentFrame.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        )

        webView = WebView(this)
        webView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        webView.setBackgroundColor(Color.BLACK)
        configureWebView()
        contentFrame.addView(webView)

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
        contentFrame.addView(hotspot)

        container.addView(contentFrame)

        adContainer = FrameLayout(this)
        adContainer.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        adContainer.setBackgroundColor(Color.BLACK)
        container.addView(adContainer)

        setContentView(container)
        enterImmersive()

        setupConsentAndAds()

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

    @SuppressLint("JavascriptInterface", "AddJavascriptInterface")
    private fun configureWebView() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.useWideViewPort = true
        s.loadWithOverviewMode = true

        // Bridge is only ever exposed to our own trusted localhost content.
        webView.addJavascriptInterface(AdsBridge(), "SonosAds")

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
                injectTrackChangeObserver()
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

    // Watches the #title element and notifies native code when the song changes,
    // without modifying the shared web frontend.
    private fun injectTrackChangeObserver() {
        val js = """
            (function () {
              if (window.__sonosAdsHooked) return;
              window.__sonosAdsHooked = true;
              var el = document.getElementById('title');
              if (!el) return;
              var last = '';
              function notify() {
                var t = (el.textContent || '').trim();
                if (t && t !== last && t.toLowerCase() !== 'nothing playing') {
                  last = t;
                  try { SonosAds.onTrackChanged(t); } catch (e) {}
                }
              }
              new MutationObserver(notify).observe(el, {
                childList: true, characterData: true, subtree: true
              });
              notify();
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private inner class AdsBridge {
        @JavascriptInterface
        fun onTrackChanged(@Suppress("UNUSED_PARAMETER") title: String) {
            handler.post { maybeShowInterstitial() }
        }
    }

    // ---- Ads: consent + init ---------------------------------------------

    private fun setupConsentAndAds() {
        consentInformation = UserMessagingPlatform.getConsentInformation(this)
        val params = ConsentRequestParameters.Builder().build()
        consentInformation.requestConsentInfoUpdate(
            this,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(this) {
                    // Regardless of form outcome, proceed if ads are now permitted.
                    if (consentInformation.canRequestAds()) initializeAds()
                }
            },
            {
                // On consent lookup failure, follow Google's guidance and still
                // attempt to serve (non-personalized) ads.
                initializeAds()
            }
        )
        // If consent was already resolved on a prior launch, start immediately.
        if (consentInformation.canRequestAds()) initializeAds()
    }

    private fun initializeAds() {
        if (adsInitialized.getAndSet(true)) return
        MobileAds.initialize(this) { }
        runOnUiThread {
            setupBanner()
            loadInterstitial()
        }
    }

    private fun setupBanner() {
        val existing = adView
        if (existing != null) {
            adContainer.removeView(existing)
            existing.destroy()
        }
        val view = AdView(this)
        view.adUnitId = BuildConfig.ADMOB_BANNER_ID
        view.setAdSize(adaptiveBannerSize())
        adView = view
        adContainer.addView(view)
        view.loadAd(AdRequest.Builder().build())
    }

    private fun adaptiveBannerSize(): AdSize {
        val widthPx = if (adContainer.width > 0) adContainer.width.toFloat()
        else resources.displayMetrics.widthPixels.toFloat()
        val density = resources.displayMetrics.density
        val adWidthDp = (widthPx / density).toInt().coerceAtLeast(1)
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this, adWidthDp)
    }

    private fun loadInterstitial() {
        InterstitialAd.load(
            this,
            BuildConfig.ADMOB_INTERSTITIAL_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialAd = null
                }
            }
        )
    }

    private fun maybeShowInterstitial() {
        val ad = interstitialAd ?: return
        // Never interrupt the first song after launch.
        if (firstTrackSeen.compareAndSet(false, true)) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastInterstitialAt < MIN_INTERSTITIAL_GAP_MS) return

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                interstitialAd = null
                lastInterstitialAt = SystemClock.elapsedRealtime()
                loadInterstitial()
                enterImmersive()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                interstitialAd = null
                loadInterstitial()
            }
        }
        lastInterstitialAt = now
        ad.show(this)
    }

    // ---- WebView retry ----------------------------------------------------

    private fun scheduleRetry() {
        if (retryPending) return
        retryPending = true
        handler.postDelayed(retryRunnable, RETRY_MS)
    }

    private fun cancelRetry() {
        retryPending = false
        handler.removeCallbacks(retryRunnable)
    }

    // ---- Window helpers ---------------------------------------------------

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
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(pad, pad, pad, 0)

        val hostsInput = EditText(this)
        hostsInput.inputType = InputType.TYPE_CLASS_TEXT
        hostsInput.hint = getString(R.string.static_hosts_hint)
        hostsInput.setText(prefs.getString(KEY_HOSTS, "") ?: "")
        column.addView(hostsInput)

        val hint = TextView(this)
        hint.text = getString(R.string.static_hosts_label)
        hint.alpha = 0.7f
        column.addView(hint)

        val keepOn = CheckBox(this)
        keepOn.text = getString(R.string.keep_screen_on)
        keepOn.isChecked = prefs.getBoolean(KEY_KEEP_ON, true)
        column.addView(keepOn)

        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(column)
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

        builder.create().show()
    }

    private fun parseHosts(raw: String): List<String> =
        raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Reload the banner at the new width so it stays an adaptive fit.
        if (adsInitialized.get()) {
            adContainer.post { setupBanner() }
        }
    }

    override fun onPause() {
        adView?.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        adView?.resume()
        enterImmersive()
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
        adView?.destroy()
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
        // Minimum time between interstitials so ads never feel spammy.
        private const val MIN_INTERSTITIAL_GAP_MS = 3 * 60 * 1000L
    }
}

package com.example

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.example.adblock.AdBlockManager
import com.example.tv.TvRemoteController
import com.example.update.UpdateInfo
import com.example.update.UpdateManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLEncoder

class MainActivity : ComponentActivity() {

    private lateinit var rootContainer: FrameLayout
    private lateinit var webView: WebView
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatusOverlay: LinearLayout
    private lateinit var tvFilterBadge: TextView
    private lateinit var errorContainer: LinearLayout
    private lateinit var btnRetry: Button

    // Google TV Top Category Bar Views
    private lateinit var btnCatAll: Button
    private lateinit var btnCatChina: Button
    private lateinit var btnCatKorea: Button
    private lateinit var btnCatJapan: Button
    private lateinit var btnCatThai: Button
    private lateinit var btnSearch: Button
    private lateinit var btnReload: Button

    // Google TV Search Dialog Overlay Views
    private lateinit var tvSearchOverlay: LinearLayout
    private lateinit var etSearchInput: EditText
    private lateinit var btnCancelSearch: Button
    private lateinit var btnSubmitSearch: Button

    // In-App Update Overlay Views
    private lateinit var tvUpdateOverlay: LinearLayout
    private lateinit var tvUpdateTitle: TextView
    private lateinit var tvUpdateNotes: TextView
    private lateinit var tvUpdateProgressBar: ProgressBar
    private lateinit var tvUpdateProgressText: TextView
    private lateinit var btnUpdateNow: Button
    private lateinit var btnUpdateLater: Button

    // In-App Update State
    private var pendingUpdate: UpdateInfo? = null
    private var downloadedUpdateFile: File? = null
    private var isUpdateDownloading = false
    private var awaitingInstallPermission = false
    private var pendingInstallFile: File? = null

    // Track active category button for remote focus restoration
    private lateinit var activeCategoryButton: Button

    // HTML5 Fullscreen Video State
    private var customVideoView: View? = null
    private var customVideoCallback: WebChromeClient.CustomViewCallback? = null

    // CSS Cinema Fullscreen Mode State
    private var isCinemaFullscreen = false

    // Double OK Remote Press Tracker (Simulates Double Tap)
    private var lastOkPressTime: Long = 0

    // Back Press Double-tap to Exit Tracker
    private var lastBackPressTime: Long = 0

    companion object {
        private const val URL_CHINA = "https://wow-drama.com/cat_category/cn-2024-th/"
        private const val URL_THAI = "https://wow-drama.com/category/the-series-th/"
        private const val URL_KOREA = "https://wow-drama.com/cat_category/korea-thai-sound/"
        private const val URL_JAPAN = "https://wow-drama.com/cat_category/jp-thai-sound/"
        private const val URL_HOME = "https://wow-drama.com/"
        private const val BACK_PRESS_EXIT_INTERVAL = 2500L
        private const val DOUBLE_OK_INTERVAL_MS = 600L
        private const val PREFS_NAME = "wow_tv_prefs"
        private const val KEY_LAST_URL = "key_last_page_url"
        private const val KEY_LAST_UPDATE_CHECK = "key_last_update_check"
        private const val KEY_SKIPPED_UPDATE_CODE = "key_skipped_update_code"
        private const val UPDATE_CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L
        private const val UPDATE_CHECK_DELAY_MS = 10_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set screen to stay on for TV streaming
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Hide system status and navigation bars for leanback TV immersion
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())

        setContentView(R.layout.activity_main)

        initViews()
        setupTopCategoryBar()
        setupSearchOverlay()
        setupWebView()
        setupBackNavigation()
        initAdBlockFilter()
        setupUpdateChecker()

        // Restore last visited page if available, otherwise default to Chinese Drama
        restoreLastVisitedPageOrDefault()
    }

    private fun initViews() {
        rootContainer = findViewById(R.id.rootContainer)
        webView = findViewById(R.id.webView)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        progressBar = findViewById(R.id.progressBar)
        tvStatusOverlay = findViewById(R.id.tvStatusOverlay)
        tvFilterBadge = findViewById(R.id.tvFilterBadge)
        errorContainer = findViewById(R.id.errorContainer)
        btnRetry = findViewById(R.id.btnRetry)

        // Google TV Top Bar (Ordered: China, Thai, Korea, Japan, All)
        btnCatChina = findViewById(R.id.btnCatChina)
        btnCatThai = findViewById(R.id.btnCatThai)
        btnCatKorea = findViewById(R.id.btnCatKorea)
        btnCatJapan = findViewById(R.id.btnCatJapan)
        btnCatAll = findViewById(R.id.btnCatAll)
        btnSearch = findViewById(R.id.btnSearch)
        btnReload = findViewById(R.id.btnReload)

        // Google TV Search Dialog Overlay
        tvSearchOverlay = findViewById(R.id.tvSearchOverlay)
        etSearchInput = findViewById(R.id.etSearchInput)
        btnCancelSearch = findViewById(R.id.btnCancelSearch)
        btnSubmitSearch = findViewById(R.id.btnSubmitSearch)

        // In-App Update Overlay
        tvUpdateOverlay = findViewById(R.id.tvUpdateOverlay)
        tvUpdateTitle = findViewById(R.id.tvUpdateTitle)
        tvUpdateNotes = findViewById(R.id.tvUpdateNotes)
        tvUpdateProgressBar = findViewById(R.id.tvUpdateProgress)
        tvUpdateProgressText = findViewById(R.id.tvUpdateProgressText)
        btnUpdateNow = findViewById(R.id.btnUpdateNow)
        btnUpdateLater = findViewById(R.id.btnUpdateLater)

        activeCategoryButton = btnCatChina

        btnRetry.setOnClickListener {
            errorContainer.visibility = View.GONE
            webView.visibility = View.VISIBLE
            webView.reload()
        }

        // In-App Update button behavior + focus chain (D-pad left/right between the two buttons)
        btnUpdateNow.setOnClickListener { onUpdateNowClicked() }
        btnUpdateLater.setOnClickListener {
            pendingUpdate?.let { rememberSkippedUpdate(it) }
            hideUpdateOverlay()
        }
        btnUpdateNow.nextFocusLeftId = R.id.btnUpdateLater
        btnUpdateLater.nextFocusRightId = R.id.btnUpdateNow
    }

    private fun setupTopCategoryBar() {
        btnCatChina.setOnClickListener { selectCategory(btnCatChina, URL_CHINA) }
        btnCatThai.setOnClickListener { selectCategory(btnCatThai, URL_THAI) }
        btnCatKorea.setOnClickListener { selectCategory(btnCatKorea, URL_KOREA) }
        btnCatJapan.setOnClickListener { selectCategory(btnCatJapan, URL_JAPAN) }
        btnCatAll.setOnClickListener { selectCategory(btnCatAll, URL_HOME) }

        btnReload.setOnClickListener {
            webView.reload()
            Toast.makeText(this, "รีเฟรชหน้าเว็บ...", Toast.LENGTH_SHORT).show()
        }

        btnSearch.setOnClickListener {
            openSearchOverlay()
        }

        // Explicit focus chains between top buttons for seamless remote navigation
        btnCatChina.nextFocusLeftId = R.id.btnCatChina
        btnCatChina.nextFocusRightId = R.id.btnCatThai
        btnCatChina.nextFocusDownId = R.id.webView

        btnCatThai.nextFocusLeftId = R.id.btnCatChina
        btnCatThai.nextFocusRightId = R.id.btnCatKorea
        btnCatThai.nextFocusDownId = R.id.webView

        btnCatKorea.nextFocusLeftId = R.id.btnCatThai
        btnCatKorea.nextFocusRightId = R.id.btnCatJapan
        btnCatKorea.nextFocusDownId = R.id.webView

        btnCatJapan.nextFocusLeftId = R.id.btnCatKorea
        btnCatJapan.nextFocusRightId = R.id.btnCatAll
        btnCatJapan.nextFocusDownId = R.id.webView

        btnCatAll.nextFocusLeftId = R.id.btnCatJapan
        btnCatAll.nextFocusRightId = R.id.btnSearch
        btnCatAll.nextFocusDownId = R.id.webView

        btnSearch.nextFocusLeftId = R.id.btnCatAll
        btnSearch.nextFocusRightId = R.id.btnReload
        btnSearch.nextFocusDownId = R.id.webView

        btnReload.nextFocusLeftId = R.id.btnSearch
        btnReload.nextFocusRightId = R.id.btnReload
        btnReload.nextFocusDownId = R.id.webView

        val topButtons = listOf(btnCatChina, btnCatThai, btnCatKorea, btnCatJapan, btnCatAll, btnSearch, btnReload)
        topButtons.forEach { button ->
            // Auto-scroll horizontal top bar when a button gains focus
            button.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    findViewById<android.widget.HorizontalScrollView>(R.id.tvTopBarScroll)
                        ?.smoothScrollTo(view.left - 40, 0)
                }
            }

            // D-pad down from category button moves focus into web content cards
            button.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    webView.requestFocus()
                    TvRemoteController.focusFirstContentCard(webView)
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun selectCategory(button: Button, url: String) {
        listOf(btnCatChina, btnCatThai, btnCatKorea, btnCatJapan, btnCatAll).forEach {
            it.isSelected = (it == button)
        }
        activeCategoryButton = button
        saveLastVisitedUrl(url)
        webView.loadUrl(url)
    }

    /**
     * Validates whether a given URL is a legitimate wow-drama page that should be remembered across sessions.
     */
    private fun isValidPageUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        if (!lower.contains("wow-drama.com")) return false
        if (lower == "about:blank" || lower.contains("/ads/")) return false
        return true
    }

    /**
     * Persistently stores the last visited URL to SharedPreferences.
     */
    private fun saveLastVisitedUrl(url: String?) {
        if (isValidPageUrl(url)) {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.edit().putString(KEY_LAST_URL, url).apply()
        }
    }

    /**
     * Retrieves the last visited URL from SharedPreferences, if available.
     */
    private fun getLastVisitedUrl(): String? {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val saved = prefs.getString(KEY_LAST_URL, null)
        return if (isValidPageUrl(saved)) saved else null
    }

    /**
     * Restores the page the user was previously viewing before exiting the app,
     * highlighting the relevant category if applicable, or defaults to Chinese Drama.
     */
    private fun restoreLastVisitedPageOrDefault() {
        val lastUrl = getLastVisitedUrl()
        if (lastUrl != null) {
            val matchingButton = when {
                lastUrl.startsWith(URL_CHINA) || lastUrl.contains("cn-") -> btnCatChina
                lastUrl.startsWith(URL_THAI) || lastUrl.contains("the-series-th") -> btnCatThai
                lastUrl.startsWith(URL_KOREA) || lastUrl.contains("korea") -> btnCatKorea
                lastUrl.startsWith(URL_JAPAN) || lastUrl.contains("jp-") -> btnCatJapan
                lastUrl == URL_HOME -> btnCatAll
                else -> null
            }

            listOf(btnCatChina, btnCatThai, btnCatKorea, btnCatJapan, btnCatAll).forEach {
                it.isSelected = (it == matchingButton)
            }
            if (matchingButton != null) {
                activeCategoryButton = matchingButton
            }

            webView.loadUrl(lastUrl)
        } else {
            selectCategory(btnCatChina, URL_CHINA)
        }
    }

    private fun setupSearchOverlay() {
        btnCancelSearch.setOnClickListener {
            closeSearchOverlay()
        }

        btnSubmitSearch.setOnClickListener {
            executeSearch()
        }

        etSearchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                executeSearch()
                true
            } else {
                false
            }
        }
    }

    private fun openSearchOverlay() {
        tvSearchOverlay.visibility = View.VISIBLE
        etSearchInput.setText("")
        etSearchInput.requestFocus()
    }

    private fun closeSearchOverlay() {
        tvSearchOverlay.visibility = View.GONE
        btnSearch.requestFocus()
    }

    private fun executeSearch() {
        val query = etSearchInput.text.toString().trim()
        if (query.isNotEmpty()) {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val searchUrl = "https://wow-drama.com/?s=$encodedQuery"
                listOf(btnCatAll, btnCatChina, btnCatKorea, btnCatJapan, btnCatThai).forEach {
                    it.isSelected = false
                }
                webView.loadUrl(searchUrl)
                closeSearchOverlay()
                webView.requestFocus()
            } catch (e: Exception) {
                Toast.makeText(this, "เกิดข้อผิดพลาดในการค้นหา", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "กรุณาใส่คำค้นหา", Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== In-App Update Checker ====================

    /** Silent check ~10s after launch (once per 12h), and auto-resume install on permission grant. */
    private fun setupUpdateChecker() {
        lifecycleScope.launch {
            delay(UPDATE_CHECK_DELAY_MS)
            checkForUpdate(auto = true)
        }
    }

    private fun checkForUpdate(auto: Boolean) {
        if (isUpdateDownloading || tvUpdateOverlay.visibility == View.VISIBLE) return
        if (auto && !shouldAutoCheckNow()) return

        lifecycleScope.launch {
            val info = UpdateManager.fetchLatestUpdate() ?: return@launch
            // Only record the check time after a successful fetch, so a failed/offline
            // check doesn't delay the next attempt by the full interval
            if (auto) markAutoCheckDone()
            if (!UpdateManager.isUpdateAvailable(info)) return@launch
            if (auto && getSkippedUpdateCode() == info.versionCode) return@launch

            showUpdatePrompt(info)
        }
    }

    private fun showUpdatePrompt(info: UpdateInfo) {
        pendingUpdate = info
        tvUpdateTitle.text =
            "🔄 พบเวอร์ชันใหม่ ${info.versionName} (ปัจจุบัน ${BuildConfig.VERSION_NAME})"
        tvUpdateNotes.text = info.notes.ifBlank {
            "กด \"อัปเดตเลย\" เพื่อดาวน์โหลดและติดตั้งเวอร์ชันใหม่โดยอัตโนมัติ"
        }
        btnUpdateNow.text = "อัปเดตเลย"
        btnUpdateNow.visibility = View.VISIBLE
        btnUpdateLater.visibility = View.VISIBLE
        tvUpdateProgressBar.visibility = View.GONE
        tvUpdateProgressText.visibility = View.GONE
        tvUpdateOverlay.visibility = View.VISIBLE
        btnUpdateNow.requestFocus()
    }

    /** True only when the last successful check is older than UPDATE_CHECK_INTERVAL_MS. */
    private fun shouldAutoCheckNow(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        return last == 0L || System.currentTimeMillis() - last >= UPDATE_CHECK_INTERVAL_MS
    }

    private fun markAutoCheckDone() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit().putLong(KEY_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply()
    }

    private fun getSkippedUpdateCode(): Int =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(KEY_SKIPPED_UPDATE_CODE, 0)

    private fun rememberSkippedUpdate(info: UpdateInfo) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit().putInt(KEY_SKIPPED_UPDATE_CODE, info.versionCode).apply()
    }

    private fun hideUpdateOverlay() {
        tvUpdateOverlay.visibility = View.GONE
        if (!isCinemaFullscreen) {
            webView.requestFocus()
        }
    }

    private fun onUpdateNowClicked() {
        if (isUpdateDownloading) return

        // Waiting for the user to grant install permission -> open settings again
        if (awaitingInstallPermission) {
            UpdateManager.openInstallPermissionSettings(this)
            return
        }

        val info = pendingUpdate ?: return
        val existing = downloadedUpdateFile ?: UpdateManager.findDownloadedApk(this)
        if (existing != null) {
            tryInstallUpdate(existing)
            return
        }

        // Start download with visible progress
        isUpdateDownloading = true
        tvUpdateProgressBar.progress = 0
        tvUpdateProgressText.text = "กำลังดาวน์โหลด... 0%"
        tvUpdateProgressBar.visibility = View.VISIBLE
        tvUpdateProgressText.visibility = View.VISIBLE
        btnUpdateNow.visibility = View.GONE
        btnUpdateLater.visibility = View.VISIBLE
        tvUpdateNotes.text = "กำลังดาวน์โหลดเวอร์ชัน ${info.versionName} อยู่ กรุณารอสักครู่..."

        lifecycleScope.launch {
            val file = UpdateManager.downloadApk(this@MainActivity, info) { percent ->
                tvUpdateProgressBar.progress = percent
                tvUpdateProgressText.text = "กำลังดาวน์โหลด... $percent%"
            }
            isUpdateDownloading = false

            if (file == null) {
                Toast.makeText(
                    this@MainActivity,
                    "ดาวน์โหลดอัปเดตไม่สำเร็จ ลองใหม่ภายหลัง",
                    Toast.LENGTH_LONG
                ).show()
                hideUpdateOverlay()
            } else {
                downloadedUpdateFile = file
                tryInstallUpdate(file)
            }
        }
    }

    private fun tryInstallUpdate(file: File) {
        if (UpdateManager.hasInstallPermission(this)) {
            pendingInstallFile = null
            awaitingInstallPermission = false
            hideUpdateOverlay()
            Toast.makeText(this, "กำลังเปิดหน้าติดตั้ง กด OK เพื่อยืนยันติดตั้ง", Toast.LENGTH_LONG).show()
            if (!UpdateManager.installApk(this, file)) {
                Toast.makeText(this, "เปิดตัวติดตั้งไม่สำเร็จ ลองใหม่ภายหลัง", Toast.LENGTH_LONG).show()
            }
            return
        }

        // Need the one-time "install unknown apps" grant; resume automatically on return
        pendingInstallFile = file
        awaitingInstallPermission = true
        UpdateManager.openInstallPermissionSettings(this)
        tvUpdateTitle.text = "🔐 ต้องขออนุญาตการติดตั้งก่อน"
        tvUpdateNotes.text =
            "โปรดเปิด \"อนุญาตให้ติดตั้งแอปจากแหล่งที่ไม่รู้จัก\" สำหรับแอปนี้ในหน้าตั้งค่าที่เปิดขึ้น แล้วกดย้อนกลับ แอปจะติดตั้งต่อให้เอง"
        btnUpdateNow.text = "เปิดหน้าอนุญาต"
        btnUpdateNow.visibility = View.VISIBLE
        btnUpdateLater.visibility = View.VISIBLE
        tvUpdateProgressBar.visibility = View.GONE
        tvUpdateProgressText.visibility = View.GONE
        tvUpdateOverlay.visibility = View.VISIBLE
        btnUpdateNow.requestFocus()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        // WebView is already hardware-accelerated by default; forcing LAYER_TYPE_HARDWARE
        // allocates an extra offscreen layer which slows scrolling on low-end TV boxes
        webView.setLayerType(View.LAYER_TYPE_NONE, null)
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false

            // Optimize rendering for large TV displays - disable touchscreen zoom listeners for speed
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false

            // Performance & Cache
            @Suppress("DEPRECATION")
            setRenderPriority(WebSettings.RenderPriority.HIGH)
            cacheMode = WebSettings.LOAD_DEFAULT
            allowContentAccess = true
            allowFileAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

            // Desktop / TV user agent to ensure wide 16:9 layout rendering
            val defaultUa = userAgentString
            userAgentString = defaultUa
                .replace("Mobile", "TV")
                .replace("; wv", "") + " WOWDramaTV/2.0"
            // Register JavascriptInterface for auto fullscreen and max volume detection
            webView.addJavascriptInterface(WebAppInterface(), "AndroidBridge")
        }

        // WebView focus setup for TV remote
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.requestFocus()

        // Configure WebViewClient for URL interception and AdBlock injection
        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                // Do not override subframe (iframe) requests!
                // Let iframes (video players, stream embeds) load naturally inside the page with Referer headers
                if (request != null && !request.isForMainFrame) {
                    return false
                }

                val uri = request?.url ?: return false
                val url = uri.toString()
                if (url.isBlank()) return true

                // Block rogue advertising popups and casino/bet redirect domains
                if (isUnwantedRedirect(url)) {
                    return true
                }

                val scheme = uri.scheme?.lowercase()
                if (scheme == "http" || scheme == "https") {
                    // Returning false allows WebView to navigate naturally for top-level pages
                    return false
                }

                return true
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString().orEmpty()

                // Intercept VAST / Video Ad XML and return empty VAST to skip video ads immediately
                if (AdBlockManager.isVastAdUrl(url)) {
                    return WebResourceResponse(
                        "text/xml",
                        "UTF-8",
                        ByteArrayInputStream(AdBlockManager.EMPTY_VAST_RESPONSE.toByteArray())
                    )
                }

                if (AdBlockManager.shouldBlockUrl(url)) {
                    // Return empty response to drop ad request
                    return WebResourceResponse(
                        "text/plain",
                        "UTF-8",
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                errorContainer.visibility = View.GONE
                webView.visibility = View.VISIBLE
                progressBar.visibility = View.VISIBLE

                // Early injection of CSS & anti-popup rules
                injectAdBlockAndTvScripts(view)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE

                // Save last visited page URL persistently across app restarts
                saveLastVisitedUrl(url ?: view?.url)

                // Complete injection of rules and Google TV layout transform
                injectAdBlockAndTvScripts(view)

                // On drama/episode pages, ensure focus is directly on the first EP button, never on the player
                view?.postDelayed({
                    if (!isCinemaFullscreen) {
                        TvRemoteController.focusFirstContentCard(view)
                    }
                }, 300)
            }

            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                // Only show error screen if primary page completely failed to load
                if (failingUrl != null && failingUrl.startsWith("https://wow-drama.com/") && webView.progress < 30) {
                    progressBar.visibility = View.GONE
                    webView.visibility = View.GONE
                    errorContainer.visibility = View.VISIBLE
                    btnRetry.requestFocus()
                }
            }
        }

        // Configure WebChromeClient for Fullscreen Video and Progress Bar
        webView.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                if (newProgress >= 100) {
                    progressBar.visibility = View.GONE
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customVideoView != null) {
                    callback?.onCustomViewHidden()
                    return
                }

                customVideoView = view
                customVideoCallback = callback

                // Ensure audio stream is unmuted when video goes fullscreen
                ensureAudioUnmuted()

                // Switch to fullscreen video container
                webView.visibility = View.GONE
                tvStatusOverlay.visibility = View.GONE
                fullscreenContainer.visibility = View.VISIBLE
                fullscreenContainer.removeAllViews()
                fullscreenContainer.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )

                // Enforce fullscreen display and focus
                fullscreenContainer.requestFocus()
                WindowCompat.getInsetsController(window, window.decorView)
                    .hide(WindowInsetsCompat.Type.systemBars())
            }

            override fun onHideCustomView() {
                if (customVideoView == null) return

                fullscreenContainer.removeAllViews()
                fullscreenContainer.visibility = View.GONE
                webView.visibility = View.VISIBLE
                tvStatusOverlay.visibility = View.VISIBLE

                customVideoCallback?.onCustomViewHidden()
                customVideoView = null
                customVideoCallback = null

                webView.requestFocus()
            }
        }
    }

    /**
     * Bridges JavaScript events (such as HTML5 video play or click) with Android native controls.
     */
    inner class WebAppInterface {
        @JavascriptInterface
        fun onVideoPlayStarted() {
            runOnUiThread {
                ensureAudioUnmuted()
            }
        }

        @JavascriptInterface
        fun enterCinemaFullscreen() {
            runOnUiThread {
                this@MainActivity.enterCinemaFullscreen()
            }
        }

        @JavascriptInterface
        fun enterCinemaFullscreenAndPlay() {
            runOnUiThread {
                this@MainActivity.enterCinemaFullscreenAndPlay()
            }
        }

        @JavascriptInterface
        fun exitCinemaFullscreen() {
            runOnUiThread {
                this@MainActivity.exitCinemaFullscreen()
            }
        }

        @JavascriptInterface
        fun tapAtCoordinates(coordX: Float, coordY: Float) {
            runOnUiThread {
                try {
                    val x = if (coordX <= 1.0f && webView.width > 0) {
                        coordX * webView.width
                    } else {
                        coordX * webView.resources.displayMetrics.density
                    }
                    val y = if (coordY <= 1.0f && webView.height > 0) {
                        coordY * webView.height
                    } else {
                        coordY * webView.resources.displayMetrics.density
                    }
                    simulateTap(x, y)
                } catch (e: Exception) {
                    android.util.Log.w("MainActivity", "Error dispatching tap", e)
                }
            }
        }

        @JavascriptInterface
        fun doubleTapAtCoordinates(coordX: Float, coordY: Float) {
            runOnUiThread {
                val x = if (coordX <= 1.0f && webView.width > 0) {
                    coordX * webView.width
                } else {
                    coordX * webView.resources.displayMetrics.density
                }
                val y = if (coordY <= 1.0f && webView.height > 0) {
                    coordY * webView.height
                } else {
                    coordY * webView.resources.displayMetrics.density
                }
                dispatchDoubleTap(x, y)
            }
        }
    }

    /**
     * Dispatches a realistic touch and mouse click sequence (ACTION_DOWN -> 80ms delay -> ACTION_UP)
     * so Chromium and embedded cross-origin iframes (e.g. Viu player) properly recognize click gestures.
     * Note: Does NOT call recycle() synchronously to allow Chromium's async thread to process the event.
     */
    fun simulateTap(x: Float, y: Float, onComplete: (() -> Unit)? = null) {
        try {
            ensureAudioUnmuted()
            val downTime = SystemClock.uptimeMillis()

            val properties = arrayOf(MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            })
            val coords = arrayOf(MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1.0f
                size = 1.0f
            })

            // 1. Dispatch real Touchscreen Down event
            val touchDown = MotionEvent.obtain(
                downTime, downTime, MotionEvent.ACTION_DOWN,
                1, properties, coords, 0, 0, 1.0f, 1.0f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            webView.dispatchTouchEvent(touchDown)

            // 2. Dispatch Mouse click Down for Chromium web/iframe hit testing
            val mouseProps = arrayOf(MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            })
            val mouseCoords = arrayOf(MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1.0f
                size = 1.0f
            })
            val mouseDown = MotionEvent.obtain(
                downTime, downTime, MotionEvent.ACTION_DOWN,
                1, mouseProps, mouseCoords, 0, MotionEvent.BUTTON_PRIMARY, 1.0f, 1.0f, 0, 0,
                InputDevice.SOURCE_MOUSE, 0
            )
            webView.dispatchGenericMotionEvent(mouseDown)

            webView.postDelayed({
                try {
                    val upTime = SystemClock.uptimeMillis()

                    // 3. Dispatch Touchscreen Up event
                    val touchUp = MotionEvent.obtain(
                        downTime, upTime, MotionEvent.ACTION_UP,
                        1, properties, coords, 0, 0, 1.0f, 1.0f, 0, 0,
                        InputDevice.SOURCE_TOUCHSCREEN, 0
                    )
                    webView.dispatchTouchEvent(touchUp)

                    // 4. Dispatch Mouse click Up
                    val mouseUp = MotionEvent.obtain(
                        downTime, upTime, MotionEvent.ACTION_UP,
                        1, mouseProps, mouseCoords, 0, 0, 1.0f, 1.0f, 0, 0,
                        InputDevice.SOURCE_MOUSE, 0
                    )
                    webView.dispatchGenericMotionEvent(mouseUp)

                    onComplete?.invoke()
                } catch (e: Exception) {
                    android.util.Log.w("MainActivity", "Error in simulateTap up", e)
                }
            }, 80)
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Error in simulateTap down", e)
        }
    }

    /**
     * Taps the exact center of WebView (where the big white play button ▶ is located).
     */
    fun simulateCenterTap(onComplete: (() -> Unit)? = null) {
        val w = if (webView.width > 0) webView.width else resources.displayMetrics.widthPixels
        val h = if (webView.height > 0) webView.height else resources.displayMetrics.heightPixels
        val cx = w / 2f
        val cy = h / 2f
        simulateTap(cx, cy, onComplete)
    }

    /**
     * Toggles play/pause during cinema fullscreen via JavaScript, center touch/mouse tap,
     * and hardware Enter/DPAD_CENTER key dispatches to guarantee Viu and embed players respond.
     *
     * @param forcePlay when true (auto-play-on-enter path), never pauses an already-playing
     * video — prevents the staggered retry taps from accidentally pausing playback
     */
    fun toggleCinemaPlayback(forcePlay: Boolean = false) {
        try {
            ensureAudioUnmuted()

            // 1. Send play/pause and focus commands via JavaScript
            webView.evaluateJavascript("""
                (function() {
                    var videos = document.querySelectorAll('video');
                    for (var i = 0; i < videos.length; i++) {
                        var v = videos[i];
                        v.muted = false;
                        v.volume = 1.0;
                        try {
                            if (v.paused) v.play();
                            else if (!$forcePlay) v.pause();
                        } catch(e) {}
                    }
                    var ifr = document.querySelector('.tv-fullscreen-player iframe') || 
                              document.getElementById('miru-player-iframe') || 
                              document.querySelector('iframe.miru-player-iframe') || 
                              document.querySelector('iframe');
                    if (ifr) {
                        try {
                            ifr.focus();
                            ifr.contentWindow.postMessage('{"event":"command","func":"playVideo"}', '*');
                            ifr.contentWindow.postMessage('{"event":"command","func":"unMute"}', '*');
                            ifr.contentWindow.postMessage('{"method":"play"}', '*');
                            ifr.contentWindow.postMessage('{"action":"play"}', '*');
                            ifr.contentWindow.postMessage('play', '*');
                        } catch(e) {}
                    }
                    var playBtns = document.querySelectorAll('.play-btn, .art-state, .jw-display-icon-display, .vjs-big-play-button, button[aria-label="Play"], .play-button, [title*="Play"], [title*="เล่น"]');
                    for (var pb = 0; pb < playBtns.length; pb++) {
                        try { playBtns[pb].click(); } catch(e) {}
                    }
                })();
            """.trimIndent(), null)

            // 2. Dispatch center tap to trigger big play button in iframe
            simulateCenterTap()

            // 3. Dispatch Enter / DPAD_CENTER keys to WebView for players with keyboard focus
            val now = SystemClock.uptimeMillis()
            webView.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0))
            webView.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0))
            webView.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
            webView.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Error in toggleCinemaPlayback", e)
        }
    }

    /**
     * Dispatches hardware double-tap event sequence to simulate double-tap gesture on video player
     */
    fun dispatchDoubleTap(x: Float, y: Float) {
        try {
            ensureAudioUnmuted()
            simulateTap(x, y) {
                webView.postDelayed({
                    simulateTap(x, y)
                }, 90)
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Error dispatching double tap", e)
        }
    }

    /**
     * Enters 100% cinema fullscreen mode for the video player
     */
    fun enterCinemaFullscreen() {
        if (isCinemaFullscreen) return
        isCinemaFullscreen = true
        findViewById<View>(R.id.tvTopBarScroll)?.visibility = View.GONE
        tvStatusOverlay.visibility = View.GONE

        WindowCompat.getInsetsController(window, window.decorView)
            .hide(WindowInsetsCompat.Type.systemBars())

        ensureAudioUnmuted()
        webView.evaluateJavascript("if (typeof window.applyCinemaFullscreen === 'function') { window.applyCinemaFullscreen(); }", null)
        Toast.makeText(this@MainActivity, "📺 เล่นวิดีโอเต็มจอ", Toast.LENGTH_SHORT).show()
    }

    /**
     * Enters 100% cinema fullscreen and automatically triggers playback so video plays immediately
     * without any paused screen, grey veil, or requiring the user to press Back.
     */
    fun enterCinemaFullscreenAndPlay() {
        runOnUiThread {
            enterCinemaFullscreen()
            // Stagger multiple center taps & play triggers so the video starts playing
            // as soon as the iframe or stream player finishes loading
            val delays = longArrayOf(500L, 1200L, 2000L, 3200L)
            for (delay in delays) {
                webView.postDelayed({
                    if (isCinemaFullscreen) {
                        // forcePlay: the retries must only ever START playback,
                        // never accidentally pause a video that already began
                        toggleCinemaPlayback(forcePlay = true)
                    }
                }, delay)
            }
        }
    }

    fun exitCinemaFullscreen() {
        if (!isCinemaFullscreen) return
        isCinemaFullscreen = false
        findViewById<View>(R.id.tvTopBarScroll)?.visibility = View.VISIBLE
        webView.evaluateJavascript("if (typeof window.exitCinemaFullscreen === 'function') { window.exitCinemaFullscreen(); }", null)
        Toast.makeText(this@MainActivity, "ออกจากโหมดเต็มจอ", Toast.LENGTH_SHORT).show()

        // Restore focus to episode buttons or content cards
        webView.postDelayed({
            TvRemoteController.focusFirstContentCard(webView)
        }, 200)
    }

    /**
     * Ensures audio stream is unmuted if previously muted, without altering the Android system volume level.
     */
    private fun ensureAudioUnmuted() {
        try {
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Failed to unmute audio", e)
        }
    }

    private fun isUnwantedRedirect(url: String): Boolean {
        val lower = url.lowercase()

        // Allow all wow-drama.com navigation
        if (lower.contains("wow-drama.com")) {
            return false
        }

        // Allow media streams
        if (lower.contains(".m3u8") || lower.contains(".ts") || lower.contains(".mp4") || lower.startsWith("blob:")) {
            return false
        }

        return lower.contains("bet365") ||
                lower.contains("ufabet") ||
                lower.contains("sbobet") ||
                lower.contains("pgslot") ||
                lower.contains("slotxo") ||
                lower.contains("casino") ||
                lower.contains("baccarat") ||
                lower.contains("popads") ||
                lower.contains("popcash") ||
                lower.contains("line.me") ||
                lower.contains("ibit.ly") ||
                lower.contains("t.ly") ||
                lower.contains("cdend.com") ||
                AdBlockManager.shouldBlockUrl(url)
    }

    private fun injectAdBlockAndTvScripts(view: WebView?) {
        val script = AdBlockManager.getInjectionScript()
        view?.evaluateJavascript(script, null)
    }

    private fun initAdBlockFilter() {
        lifecycleScope.launch {
            val count = AdBlockManager.initialize(applicationContext)
            tvFilterBadge.text = "🛡️ AdBlock: $count กฎ"

            // Re-apply latest parsed rules to current page
            injectAdBlockAndTvScripts(webView)

            // Auto-hide the TV status badge after 6 seconds
            tvStatusOverlay.postDelayed({
                tvStatusOverlay.animate()
                    .alpha(0f)
                    .setDuration(600)
                    .withEndAction { tvStatusOverlay.visibility = View.GONE }
                    .start()
            }, 6000)
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 0. If update dialog is open, close it first (download keeps running in background)
                if (tvUpdateOverlay.visibility == View.VISIBLE) {
                    if (isUpdateDownloading) {
                        Toast.makeText(
                            this@MainActivity,
                            "กำลังดาวน์โหลดอัปเดตอยู่ จะแจ้งอีกครั้งเมื่อเสร็จ",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    hideUpdateOverlay()
                    return
                }

                // 1. If search dialog is open, close it first
                if (tvSearchOverlay.visibility == View.VISIBLE) {
                    closeSearchOverlay()
                    return
                }

                // 2. If cinema fullscreen is active, exit cinema fullscreen first
                if (isCinemaFullscreen) {
                    exitCinemaFullscreen()
                    return
                }

                // 3. If video is currently in HTML5 fullscreen, exit custom view first
                if (customVideoView != null) {
                    webView.webChromeClient?.onHideCustomView()
                    return
                }

                // 4. If WebView has navigation history, go back in history
                if (webView.canGoBack()) {
                    webView.goBack()
                    return
                }

                // 5. Double-tap Back within 2.5s to exit TV application
                val currentTime = SystemClock.elapsedRealtime()
                if (currentTime - lastBackPressTime < BACK_PRESS_EXIT_INTERVAL) {
                    finish()
                } else {
                    lastBackPressTime = currentTime
                    Toast.makeText(
                        this@MainActivity,
                        "กดปุ่มย้อนกลับอีกครั้งเพื่อออกจากแอป",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event == null) return super.onKeyDown(keyCode, event)

        // If search overlay is showing, let standard input handling process it
        if (tvSearchOverlay.visibility == View.VISIBLE) {
            return super.onKeyDown(keyCode, event)
        }

        // If update overlay is showing, D-pad navigates its buttons via standard focus handling
        if (tvUpdateOverlay.visibility == View.VISIBLE) {
            return super.onKeyDown(keyCode, event)
        }

        val isOkKey = (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || 
                       keyCode == KeyEvent.KEYCODE_ENTER || 
                       keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)

        // Check if user is currently interacting with the Top Category Bar
        val topButtons = listOf(btnCatChina, btnCatThai, btnCatKorea, btnCatJapan, btnCatAll, btnSearch, btnReload)
        val isTopBarFocused = topButtons.any { it.isFocused } || findViewById<View>(R.id.tvTopBarScroll).isFocused

        // Detect Double OK press (Simulates Double Tap to enter/toggle fullscreen)
        // Only active in normal browsing mode, NEVER hijack OK key during cinema fullscreen
        if (!isCinemaFullscreen && isOkKey && !isTopBarFocused && tvSearchOverlay.visibility != View.VISIBLE) {
            val now = SystemClock.uptimeMillis()
            if (now - lastOkPressTime < DOUBLE_OK_INTERVAL_MS) {
                lastOkPressTime = 0
                ensureAudioUnmuted()
                enterCinemaFullscreenAndPlay()
                Toast.makeText(this@MainActivity, "⚡ ดับเบิ้ลคลิก OK: ขยายเต็มจอ", Toast.LENGTH_SHORT).show()
                return true
            }
            lastOkPressTime = now
        }

        // If cinema fullscreen is active, handle playback controls via TV Remote D-pad
        if (isCinemaFullscreen) {
            if (isOkKey) {
                toggleCinemaPlayback()
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                val delta = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -10 else 10
                webView.evaluateJavascript("""
                    (function() {
                        var v = document.querySelector('video');
                        if (v) {
                            v.currentTime = Math.max(0, v.currentTime + ($delta));
                        }
                        var ifr = document.querySelector('.tv-fullscreen-player iframe') || document.querySelector('iframe');
                        if (ifr) {
                            try {
                                ifr.contentWindow.postMessage('{"event":"command","func":"seekTo","args":[' + $delta + ', true]}', '*');
                            } catch(e) {}
                        }
                    })();
                """.trimIndent(), null)
                val msg = if (delta < 0) "⏪ ย้อนกลับ 10 วิ" else "⏩ เดินหน้า 10 วิ"
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                // Ignore D-pad vertical scrolling during cinema fullscreen
                return true
            }
        }

        // If fullscreen video is active, allow player controls
        if (customVideoView != null) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                // Pass enter/center to video container
                return super.onKeyDown(keyCode, event)
            }
        }

        // If user is currently interacting with the Top Category Bar
        if (isTopBarFocused) {
            // When user is on Top Category Bar, allow standard D-pad left/right/center between category buttons
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                return super.onKeyDown(keyCode, event)
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                webView.requestFocus()
                TvRemoteController.focusFirstContentCard(webView)
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                return true // Already at top
            }
        }

        // Delegate D-pad remote navigation to TvRemoteController when WebView has focus
        if (TvRemoteController.handleKeyDown(
                keyCode = keyCode,
                event = event,
                webView = webView,
                onReachedTop = {
                    runOnUiThread {
                        activeCategoryButton.requestFocus()
                        findViewById<android.widget.HorizontalScrollView>(R.id.tvTopBarScroll)
                            ?.smoothScrollTo(activeCategoryButton.left - 40, 0)
                    }
                }
            )) {
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()

        // Resume pending install after the user grants "install unknown apps" in system settings
        if (awaitingInstallPermission) {
            awaitingInstallPermission = false
            val file = pendingInstallFile
            if (file != null && UpdateManager.hasInstallPermission(this)) {
                pendingInstallFile = null
                hideUpdateOverlay()
                if (!UpdateManager.installApk(this, file)) {
                    Toast.makeText(this, "เปิดตัวติดตั้งไม่สำเร็จ ลองใหม่ภายหลัง", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        saveLastVisitedUrl(webView.url)
        webView.onPause()
    }

    override fun onStop() {
        super.onStop()
        saveLastVisitedUrl(webView.url)
    }

    override fun onDestroy() {
        super.onDestroy()
        webView.destroy()
    }
}

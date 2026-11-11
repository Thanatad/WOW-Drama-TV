package com.example.adblock

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

object AdBlockManager {
    private const val TAG = "AdBlockManager"
    private const val FILTER_URL = "https://tinyurl.com/thai-ads-filter"
    private const val CACHE_FILE_NAME = "thai_ads_filter_cache.txt"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Thread-safe parsed rules
    @Volatile
    private var cachedCssRules: String = ""

    // Fallback baseline selectors specifically curated for wow-drama.com and Thai streaming sites
    private val baselineSelectors = listOf(
        ".list-ads",
        ".list-bbr",
        "#adsBox",
        ".ads-bokplayer",
        ".vipx-grid",
        ".vipx-item",
        ".vipx-item--floating",
        "#pm-dessert",
        "#pre-pm-dessert",
        ".floating_content",
        ".ads-info",
        "#sticky-bottom",
        "#sticky-bottom2",
        "#sticky-bottom3",
        "div[class*='list-ads']",
        "div[class*='list-bbr']",
        "div[class*='vipx-']",
        "a[href*='ibit.ly']",
        "a[href*='cdend.com']",
        "img[src*='cdend.com']",
        "ins.adsbygoogle"
    )

    const val EMPTY_VAST_RESPONSE =
        """<?xml version="1.0" encoding="UTF-8"?><VAST version="3.0"></VAST>"""

    @Volatile
    private var cachedSelectors: List<String> = baselineSelectors

    @Volatile
    private var networkBlockedPatterns: List<String> = listOf(
        "ads-vip",
        "mvast",
        "cdend.com",
        "ibit.ly",
        "t.ly",
        "googleads",
        "googlesyndication",
        "doubleclick.net",
        "histats.com",
        "popads",
        "popcash",
        "exosrv.com",
        "adservice",
        "/ads/",
        "adsBox",
        "befunprize",
        "fun88",
        "adsterra",
        "monetag",
        "trafficjunky",
        "vpaid",
        "pm-dessert",
        "pre-pm-dessert",
        "430x250",
        "ads-close",
        "bet365",
        "ufabet",
        "sbobet",
        "pgslot",
        "slotxo"
    )

    fun isVastAdUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("mvast.xml") ||
                lower.contains("/vast") ||
                lower.contains("vast.xml") ||
                lower.contains("vpaid") ||
                lower.contains("befunprize") ||
                lower.contains("fun88") ||
                (lower.contains("vast") && (lower.contains(".xml") || lower.contains("format=xml") || lower.contains("output=xml")))
    }

    init {
        // Initialize with baseline rules immediately so first page load is already filtered
        cachedSelectors = baselineSelectors
        compileCss(baselineSelectors)
    }

    /**
     * Initializes AdBlock filters: loads from disk cache if present,
     * and fetches fresh rules from tinyurl in background.
     */
    suspend fun initialize(context: Context): Int = withContext(Dispatchers.IO) {
        val cacheFile = File(context.cacheDir, CACHE_FILE_NAME)

        // 1. Load from local cache file if available
        if (cacheFile.exists()) {
            try {
                val cachedText = cacheFile.readText()
                if (cachedText.isNotBlank()) {
                    parseFilterList(cachedText)
                    Log.d(TAG, "Loaded cached ad filter list (${cachedSelectors.size} rules)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error reading ad filter cache", e)
            }
        }

        // 2. Fetch latest rules from remote URL
        try {
            val request = Request.Builder()
                .url(FILTER_URL)
                .header("User-Agent", "Mozilla/5.0 (Android TV; WOW Drama Reader)")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string().orEmpty()
                if (responseBody.isNotBlank()) {
                    parseFilterList(responseBody)
                    // Persist to disk cache
                    cacheFile.writeText(responseBody)
                    Log.i(TAG, "Successfully fetched and updated ad filters (${cachedSelectors.size} rules)")
                }
            } else {
                Log.w(TAG, "Ad filter fetch failed with HTTP code: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ad filter fetch exception, falling back to cache/baseline", e)
        }

        cachedSelectors.size
    }

    /**
     * Parses ABP-style adblock filter list.
     * Extracts CSS element hiding selectors and network blocking patterns.
     */
    private fun parseFilterList(rawContent: String) {
        val selectors = mutableSetOf<String>()
        selectors.addAll(baselineSelectors)

        val netPatterns = mutableSetOf<String>()
        netPatterns.addAll(networkBlockedPatterns)

        rawContent.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("!")) {
                return@forEach
            }

            // Element hiding rules
            if (line.contains("##") || line.contains("#?#")) {
                val delimiter = if (line.contains("##")) "##" else "#?#"
                val parts = line.split(delimiter, limit = 2)
                if (parts.size == 2) {
                    val domainPart = parts[0].trim()
                    val selectorPart = parts[1].trim()

                    val matchesDomain = domainPart.isEmpty() ||
                            domainPart.contains("wow-drama.com", ignoreCase = true) ||
                            !domainPart.contains(".") // Global rule

                    if (matchesDomain && selectorPart.isNotBlank()) {
                        selectors.add(selectorPart)
                    }
                }
            } else if (line.startsWith("*") || line.contains("wp-content/plugins/ads") || line.contains("mvast")) {
                // Network blocking pattern
                val cleanPattern = line.replace("*", "").replace("\$image", "").replace("\$xmlhttprequest", "").trim()
                if (cleanPattern.isNotBlank()) {
                    netPatterns.add(cleanPattern)
                }
            }
        }

        cachedSelectors = selectors.toList()
        networkBlockedPatterns = netPatterns.toList()
        compileCss(cachedSelectors)
    }

    /**
     * Compiles CSS selectors into a high-priority hide stylesheet.
     */
    private fun compileCss(selectors: List<String>) {
        if (selectors.isEmpty()) return
        val joined = selectors.joinToString(",\n")
        cachedCssRules = """
            $joined {
                display: none !important;
                visibility: hidden !important;
                opacity: 0 !important;
                height: 0 !important;
                min-height: 0 !important;
                max-height: 0 !important;
                pointer-events: none !important;
            }
        """.trimIndent()
    }

    /**
     * Checks if a network request URL matches known advertisement patterns.
     */
    fun shouldBlockUrl(url: String): Boolean {
        val lowerUrl = url.lowercase()

        // 1. Never block media streams, video segments, playlists, or blob URLs
        if (lowerUrl.contains(".m3u8") ||
            lowerUrl.contains(".ts") ||
            lowerUrl.contains(".mp4") ||
            lowerUrl.contains(".mpd") ||
            lowerUrl.startsWith("blob:")
        ) {
            return false
        }

        // 2. Never block wow-drama.com unless explicitly in an ad path
        if (lowerUrl.contains("wow-drama.com") && !lowerUrl.contains("/ads/")) {
            return false
        }

        return networkBlockedPatterns.any { pattern ->
            lowerUrl.contains(pattern.lowercase())
        }
    }

    /**
     * Returns total active filter count for badge display.
     */
    fun getRuleCount(): Int = cachedSelectors.size

    /**
     * Generates modern Google TV / Netflix cinema styling for wow-drama.com
     * Strips desktop WordPress clutter and reformats into large, clear cards.
     */
    fun getTvLayoutBeautifierCss(): String = """
        /* 1. Hide WordPress Desktop Clutter & Sidebars (Preserve main-body container) */
        #masthead, .site-header, .site-header-space, .site-toggle, .site-nav-m, .s-modal,
        aside#rightbar, aside.widget-area, #secondary, .sidebar, #colophon, .site-footer,
        .site-search, .s-btn, .skip-link, .menu-main-menu-container,
        .breadcrumb, .entry-footer, .comments-area, #comments {
            display: none !important;
            visibility: hidden !important;
            height: 0 !important;
            width: 0 !important;
            overflow: hidden !important;
        }

        /* 2. Fullscreen Clean Dark Background */
        html, body, #page, #content, .site-content, .s-container, #primary, #main {
            background-color: #0B0D13 !important;
            color: #E2E8F0 !important;
            max-width: 100% !important;
            width: 100% !important;
            margin: 0 !important;
            padding: 0 !important;
            box-shadow: none !important;
            border: none !important;
        }

        /* Explicitly guarantee main content wrapper is visible */
        .s-container.main-body,
        div.main-body {
            display: block !important;
            visibility: visible !important;
            opacity: 1 !important;
            padding: 16px 28px 60px 28px !important;
            box-sizing: border-box !important;
            width: 100% !important;
            max-width: 100% !important;
        }

        #primary, .content-area, main#main {
            display: block !important;
            visibility: visible !important;
            opacity: 1 !important;
            float: none !important;
            width: 100% !important;
            max-width: 100% !important;
        }

        /* 3. Google TV Responsive Grid for Drama Cards (5 to 6 Columns) */
        .list-movie {
            display: grid !important;
            visibility: visible !important;
            opacity: 1 !important;
            grid-template-columns: repeat(5, minmax(0, 1fr)) !important;
            gap: 24px 18px !important;
            padding: 16px 28px 60px 28px !important;
            margin: 0 auto !important;
            width: 100% !important;
            max-width: 100% !important;
            box-sizing: border-box !important;
        }

        @media (min-width: 1500px) {
            .list-movie {
                grid-template-columns: repeat(6, minmax(0, 1fr)) !important;
                gap: 26px 20px !important;
            }
        }

        @media (max-width: 1080px) {
            .list-movie {
                grid-template-columns: repeat(4, minmax(0, 1fr)) !important;
                gap: 20px 16px !important;
            }
        }

        @media (max-width: 760px) {
            .list-movie {
                grid-template-columns: repeat(3, minmax(0, 1fr)) !important;
                gap: 16px 12px !important;
            }
        }

        /* 4. Movie Card Design - Full Width of Grid Cell (Hardware Accelerated for TV) */
        .list-movie .-movie,
        .-movie {
            position: relative !important;
            width: 100% !important;
            max-width: 100% !important;
            min-width: 0 !important;
            margin: 0 !important;
            background: #141824 !important;
            border-radius: 12px !important;
            overflow: hidden !important;
            border: 2px solid #232838 !important;
            cursor: pointer !important;
            outline: none !important;
            display: flex !important;
            flex-direction: column !important;
            box-shadow: 0 4px 12px rgba(0, 0, 0, 0.4) !important;
            visibility: visible !important;
            opacity: 1 !important;
            box-sizing: border-box !important;
            /* Containment for fast scroll on low-end TV GPUs (Chromecast):
               do NOT promote every card to its own GPU layer via will-change/translateZ —
               hundreds of layers exhaust the weak GPU. Keep rendering virtualized with
               content-visibility instead; focus state animates composited via transform. */
            contain: layout style paint !important;
            content-visibility: auto !important;
            contain-intrinsic-size: 0 300px !important;
            transition: transform 0.12s ease-out, border-color 0.12s ease-out !important;
        }

        /* TV Remote Focus State on Drama Cards - Lightweight 60fps glow */
        .list-movie .-movie:focus,
        .list-movie .-movie:hover,
        .list-movie .-movie:focus-within,
        .-movie:focus,
        .-movie:hover,
        .-movie:focus-within,
        [data-tv-focused="true"] {
            transform: scale(1.05) translateZ(0) !important;
            border-color: #E50914 !important;
            box-shadow: 0 0 16px rgba(229, 9, 20, 0.9) !important;
            z-index: 50 !important;
        }

        /* Poster Image with Proportional Height for 16:9 TV */
        .list-movie .-movie .pic,
        .-movie .pic {
            width: 100% !important;
            aspect-ratio: 2 / 2.9 !important;
            height: auto !important;
            min-height: 200px !important;
            max-height: none !important;
            overflow: hidden !important;
            position: relative !important;
            background: #0E1118 !important;
            margin: 0 !important;
            padding: 0 !important;
            display: block !important;
            contain: paint !important;
        }

        .list-movie .-movie .pic a,
        .-movie .pic a {
            display: block !important;
            width: 100% !important;
            height: 100% !important;
            outline: none !important;
        }

        .list-movie .-movie .pic img,
        .-movie .pic img {
            width: 100% !important;
            height: 100% !important;
            object-fit: cover !important;
            display: block !important;
            transition: none !important;
            transform: none !important;
            image-rendering: auto !important;
        }

        .list-movie .-movie:focus .pic img,
        .-movie:focus .pic img {
            transform: none !important;
        }

        /* High-Visibility Badges */
        .list-movie .-movie .qa-label,
        .-movie .qa-label {
            position: absolute !important;
            top: 10px !important;
            left: 10px !important;
            right: auto !important;
            bottom: auto !important;
            transform: none !important;
            width: auto !important;
            height: auto !important;
            line-height: normal !important;
            background: #E50914 !important;
            color: #FFFFFF !important;
            font-size: 11px !important;
            font-weight: 800 !important;
            padding: 4px 8px !important;
            border-radius: 6px !important;
            box-shadow: 0 2px 6px rgba(0,0,0,0.5) !important;
            z-index: 5 !important;
            margin: 0 !important;
        }

        .list-movie .-movie .imdb,
        .-movie .imdb {
            position: absolute !important;
            top: 10px !important;
            right: 10px !important;
            left: auto !important;
            bottom: auto !important;
            transform: none !important;
            margin: 0 !important;
            background: #0E121E !important;
            color: #00D2FF !important;
            font-size: 11px !important;
            font-weight: 700 !important;
            padding: 4px 8px !important;
            border-radius: 6px !important;
            backdrop-filter: none !important;
            -webkit-backdrop-filter: none !important;
            border: 1px solid rgba(0, 210, 255, 0.4) !important;
            box-shadow: 0 2px 6px rgba(0,0,0,0.5) !important;
            z-index: 5 !important;
        }

        .list-movie .-movie .epseries,
        .-movie .epseries {
            position: absolute !important;
            bottom: 10px !important;
            left: 10px !important;
            right: auto !important;
            top: auto !important;
            margin: 0 !important;
            background: rgba(0, 0, 0, 0.85) !important;
            color: #FFD700 !important;
            font-size: 11px !important;
            font-weight: 800 !important;
            padding: 4px 8px !important;
            border-radius: 6px !important;
            border: 1px solid rgba(255, 215, 0, 0.4) !important;
            box-shadow: 0 2px 8px rgba(0,0,0,0.6) !important;
            z-index: 5 !important;
        }

        .list-movie .-movie .year,
        .-movie .year {
            position: absolute !important;
            bottom: 10px !important;
            right: 10px !important;
            left: auto !important;
            top: auto !important;
            margin: 0 !important;
            background: #1B4DA8 !important;
            color: #FFFFFF !important;
            font-size: 11px !important;
            font-weight: 800 !important;
            padding: 4px 8px !important;
            border-radius: 6px !important;
            box-shadow: 0 2px 8px rgba(0,0,0,0.6) !important;
            z-index: 5 !important;
        }

        /* Title Area */
        .list-movie .-movie .info,
        .-movie .info {
            padding: 12px 14px 14px 14px !important;
            background: #141824 !important;
            flex-grow: 1 !important;
            margin: 0 !important;
            width: 100% !important;
            box-sizing: border-box !important;
            display: flex !important;
            flex-direction: column !important;
            justify-content: center !important;
        }

        .list-movie .-movie .info a,
        .list-movie .-movie .info h2,
        .list-movie .-movie .info h3,
        .-movie .info a,
        .-movie .info h2,
        .-movie .info h3 {
            font-size: 14px !important;
            font-weight: 700 !important;
            color: #FFFFFF !important;
            text-decoration: none !important;
            line-height: 1.4 !important;
            display: -webkit-box !important;
            -webkit-line-clamp: 2 !important;
            -webkit-box-orient: vertical !important;
            overflow: hidden !important;
            text-align: left !important;
            outline: none !important;
            margin: 0 !important;
            word-break: break-word !important;
        }

        .list-movie .-movie:focus .info a,
        .-movie:focus .info a {
            color: #00D2FF !important;
        }

        /* Detail / Episode Page Layout - Controls on Top of Video */
        .miru-player-wrapper {
            display: flex !important;
            flex-direction: column !important;
            width: 100% !important;
            max-width: 1180px !important;
            margin: 12px auto !important;
            background: transparent !important;
            box-shadow: none !important;
            border-radius: 0 !important;
            overflow: visible !important;
        }

        /* 1. miru-controls-area placed ABOVE video player */
        .miru-controls-area {
            order: 1 !important;
            width: 100% !important;
            margin: 0 auto 16px auto !important;
            background: #141824 !important;
            padding: 18px 24px !important;
            border-radius: 14px !important;
            border: 2px solid #283044 !important;
            box-sizing: border-box !important;
            box-shadow: 0 6px 24px rgba(0, 0, 0, 0.6) !important;
        }

        /* 2. Group tabs (e.g. "พากย์ไทย", "ซับไทย") */
        .miru-group-tabs {
            display: flex !important;
            gap: 12px !important;
            margin-bottom: 14px !important;
            flex-wrap: wrap !important;
        }

        .miru-tab-btn {
            padding: 10px 24px !important;
            border-radius: 20px !important;
            background: #1E2330 !important;
            color: #CCCCCC !important;
            border: 2px solid #333C50 !important;
            font-weight: 700 !important;
            font-size: 15px !important;
            cursor: pointer !important;
            outline: none !important;
            transition: all 0.2s ease !important;
        }

        /* Active / Selected Tab (Red) */
        .miru-tab-btn.active,
        .miru-tab-btn.current,
        .miru-tab-btn.selected,
        .miru-tab-btn[aria-selected="true"] {
            background: #E50914 !important;
            color: #FFFFFF !important;
            border-color: #E50914 !important;
        }

        /* Remote Focused Tab (Cyan Highlight) */
        .miru-tab-btn:focus,
        .miru-tab-btn:hover,
        .miru-tab-btn[data-tv-focused="true"] {
            border-color: #00D2FF !important;
            outline: 3px solid #00D2FF !important;
            outline-offset: 2px !important;
            transform: scale(1.08) !important;
            box-shadow: 0 0 22px rgba(0, 210, 255, 0.95) !important;
            z-index: 50 !important;
        }

        /* 3. Title bar */
        .miru-title-bar {
            order: 2 !important;
            width: 100% !important;
            margin: 0 auto 12px auto !important;
        }

        /* 4. Video player container */
        .miru-video-container {
            order: 3 !important;
            width: 100% !important;
            max-width: 1180px !important;
            margin: 0 auto 24px auto !important;
            border-radius: 16px !important;
            overflow: hidden !important;
            box-shadow: 0 12px 36px rgba(0, 0, 0, 0.85) !important;
            background: #000 !important;
            cursor: pointer !important;
            outline: none !important;
            border: none !important;
            transition: all 0.2s ease !important;
        }

        .miru-video-container:focus,
        .miru-video-container:focus-within {
            outline: none !important;
            box-shadow: none !important;
            border-color: transparent !important;
        }

        #miru-player-iframe, iframe.miru-player-iframe, .miru-player-wrapper iframe {
            width: 100% !important;
            border-radius: 14px !important;
            outline: none !important;
        }

        .miru-ep-title-wrap {
            margin-top: 14px !important;
        }

        .miru-ep-title {
            color: #FFFFFF !important;
            font-size: 18px !important;
            font-weight: 800 !important;
        }

        /* Big Episode Selection Buttons for Remote Navigation */
        .miru-ep-grid {
            display: flex;
            flex-wrap: wrap !important;
            gap: 12px !important;
            padding: 12px 0 16px 0 !important;
        }

        /* Inactive Episode Groups must remain strictly hidden to prevent mixing Sub and Dub */
        .miru-ep-grid[style*="display: none"],
        .miru-ep-grid[style*="display:none"],
        .miru-ep-grid.hidden {
            display: none !important;
            visibility: hidden !important;
            height: 0 !important;
            overflow: hidden !important;
            pointer-events: none !important;
        }

        .miru-ep-btn {
            min-width: 86px !important;
            height: 50px !important;
            background: #1F2432 !important;
            color: #FFFFFF !important;
            border: 2px solid #30374A !important;
            border-radius: 10px !important;
            font-size: 15px !important;
            font-weight: 700 !important;
            display: inline-flex !important;
            align-items: center !important;
            justify-content: center !important;
            cursor: pointer !important;
            transition: all 0.18s ease !important;
            outline: none !important;
            text-decoration: none !important;
        }

        .miru-ep-btn:focus, .miru-ep-btn:hover, .miru-ep-btn.active {
            background: #E50914 !important;
            color: #FFFFFF !important;
            border-color: #FFFFFF !important;
            transform: scale(1.05) !important;
            box-shadow: 0 0 16px rgba(229, 9, 20, 0.95) !important;
            z-index: 50 !important;
        }

        /* Server Select Buttons */
        .miru-server-switch {
            display: flex !important;
            gap: 12px !important;
            margin: 12px 0 !important;
        }

        .miru-server-btn {
            padding: 10px 22px !important;
            border-radius: 20px !important;
            background: #232838 !important;
            color: #FFFFFF !important;
            border: 2px solid transparent !important;
            font-weight: 700 !important;
            font-size: 14px !important;
            cursor: pointer !important;
            outline: none !important;
        }

        .miru-server-btn:focus, .miru-server-btn.active {
            background: #00D2FF !important;
            color: #000000 !important;
            border-color: #FFFFFF !important;
            transform: scale(1.06) !important;
        }

        /* Pagination Controls */
        .pagination, .nav-links {
            display: flex !important;
            justify-content: center !important;
            gap: 14px !important;
            padding: 24px 0 48px 0 !important;
        }

        .pagination a, .pagination span, .nav-links a, .nav-links span {
            display: inline-flex !important;
            align-items: center !important;
            justify-content: center !important;
            min-width: 52px !important;
            height: 48px !important;
            padding: 0 18px !important;
            background: #1C202C !important;
            color: #FFFFFF !important;
            border-radius: 12px !important;
            border: 2px solid #2E3547 !important;
            font-size: 16px !important;
            font-weight: 700 !important;
            text-decoration: none !important;
            outline: none !important;
        }

        .pagination a:focus, .pagination a:hover, .nav-links a:focus, .nav-links a:hover, .current {
            background: #E50914 !important;
            border-color: #FFFFFF !important;
            transform: scale(1.1) !important;
            box-shadow: 0 0 18px rgba(229, 9, 20, 0.85) !important;
        }

        /* 5. In-Video Interstitial & Overlay Ad Suppressor */
        #adsBox,
        .ads-close,
        .ads-bokplayer,
        #pm-dessert,
        #pre-pm-dessert,
        .jw-ad-container,
        .jw-display-ads,
        .jw-ad-time,
        .jw-ad-ui,
        .jw-flag-ads .jw-ad-container,
        .art-ads,
        .artplayer-plugin-ads,
        .miru-video-container > #adsBox,
        .miru-video-container > div[id*="ad"],
        .miru-video-container > div[class*="ad"] {
            display: none !important;
            visibility: hidden !important;
            opacity: 0 !important;
            height: 0 !important;
            width: 0 !important;
            max-height: 0 !important;
            pointer-events: none !important;
            position: absolute !important;
            top: -9999px !important;
            z-index: -9999 !important;
        }

        /* 6. True Cinema Fullscreen Mode */
        html.tv-video-fullscreen,
        body.tv-video-fullscreen {
            overflow: hidden !important;
            background: #000000 !important;
            margin: 0 !important;
            padding: 0 !important;
            width: 100vw !important;
            height: 100vh !important;
        }

        .tv-fullscreen-player,
        iframe.tv-fullscreen-player,
        video.tv-fullscreen-player {
            position: fixed !important;
            top: 0 !important;
            left: 0 !important;
            right: 0 !important;
            bottom: 0 !important;
            width: 100vw !important;
            height: 100vh !important;
            max-width: 100vw !important;
            max-height: 100vh !important;
            z-index: 2147483647 !important;
            margin: 0 !important;
            padding: 0 !important;
            border: none !important;
            border-radius: 0 !important;
            background: #000000 !important;
            box-shadow: none !important;
            display: block !important;
            pointer-events: auto !important;
        }

        .tv-fullscreen-player iframe,
        .tv-fullscreen-player video {
            position: absolute !important;
            top: 0 !important;
            left: 0 !important;
            width: 100vw !important;
            height: 100vh !important;
            max-width: 100vw !important;
            max-height: 100vh !important;
            border: none !important;
            border-radius: 0 !important;
            background: #000000 !important;
            z-index: 10 !important;
            pointer-events: auto !important;
        }

        /* Ensure no overlay, sibling or child element blocks the video or clicks in cinema fullscreen */
        .tv-fullscreen-player > *:not(iframe):not(video) {
            display: none !important;
            pointer-events: none !important;
            visibility: hidden !important;
            width: 0 !important;
            height: 0 !important;
        }

    """.trimIndent()

    private fun encodeBase64(bytes: ByteArray): String {
        return try {
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (t: Throwable) {
            java.util.Base64.getEncoder().encodeToString(bytes)
        }
    }

    /**
     * Generates JavaScript code to inject into WebView onPageFinished and onPageStarted.
     * Injects CSS hiding rules, TV layout beautifier, MutationObserver, and TV remote accessibility.
     */
    fun getInjectionScript(): String {
        val cssBase64 = encodeBase64(cachedCssRules.toByteArray())
        val tvCssBase64 = encodeBase64(getTvLayoutBeautifierCss().toByteArray())
        val selectorsJson = cachedSelectors.joinToString(
            prefix = "[",
            postfix = "]",
            separator = ","
        ) { "\"${it.replace("\"", "\\\"")}\"" }

        return """
            (function() {
                try {
                    // 1. Anti-Popup & Redirect Protection
                    window.open = function(url) {
                        console.log('[WOW TV] Intercepted and blocked popup:', url);
                        return null;
                    };
                    window.alert = function() { return true; };
                    window.confirm = function() { return true; };

                    // 2. Inject Filter Stylesheet & Google TV Cinema Theme
                    var styleId = '__wow_tv_adblock_css__';
                    var existingStyle = document.getElementById(styleId);
                    if (!existingStyle) {
                        var style = document.createElement('style');
                        style.id = styleId;
                        var rawAdCss = window.atob('$cssBase64');
                        var tvLayoutCss = window.atob('$tvCssBase64');
                        var tvFocusCss = `
                            :focus, [data-tv-focused="true"] {
                                outline: 3.5px solid #E50914 !important;
                                outline-offset: 3px !important;
                                box-shadow: 0 0 14px rgba(229, 9, 20, 0.9) !important;
                                z-index: 999999 !important;
                            }
                            img {
                                content-visibility: auto !important;
                            }
                            body {
                                -webkit-user-select: none;
                                user-select: none;
                            }
                        `;
                        style.innerHTML = rawAdCss + '\n' + tvLayoutCss + '\n' + tvFocusCss;
                        (document.head || document.documentElement).appendChild(style);
                    }

                    // 3. Continuous Ad Node Cleaner & MutationObserver
                    var selectors = $selectorsJson;
                    var joinedSelector = selectors.join(',');
                    var usePerSelectorMode = false;
                    function cleanupAdNodes() {
                        // One combined querySelectorAll pass (much cheaper than one query per rule)
                        if (!usePerSelectorMode) {
                            try {
                                var nodes = document.querySelectorAll(joinedSelector);
                                for (var j = 0; j < nodes.length; j++) {
                                    nodes[j].remove();
                                }
                            } catch(e) {
                                // Some selectors in the list are invalid -> clean them one by one
                                usePerSelectorMode = true;
                            }
                        }
                        if (usePerSelectorMode) {
                            for (var i = 0; i < selectors.length; i++) {
                                try {
                                    var ns = document.querySelectorAll(selectors[i]);
                                    for (var k = 0; k < ns.length; k++) {
                                        ns[k].remove();
                                    }
                                } catch(e2) {}
                            }
                        }
                        // Remove intrusive sticky overlay wrappers
                        var popups = document.querySelectorAll('#adsBox, .ads-bokplayer, .vipx-item--floating, #sticky-bottom, #sticky-bottom2');
                        for (var m = 0; m < popups.length; m++) {
                            popups[m].remove();
                        }
                    }

                    cleanupAdNodes();

                    // Observe DOM changes for lazy-loaded ads.
                    // Debounced: lazy-loaded images fire mutations constantly while scrolling,
                    // and running the full cleanup on every mutation pegged the CPU on Chromecast.
                    if (!window.__wow_ad_observer_active) {
                        window.__wow_ad_observer_active = true;
                        var cleanupTimer = null;
                        var observer = new MutationObserver(function() {
                            if (cleanupTimer) return;
                            cleanupTimer = setTimeout(function() {
                                cleanupTimer = null;
                                cleanupAdNodes();
                                reorderMiruControls();
                                autoSelectThaiDub();
                            }, 250);
                        });
                        var target = document.body || document.documentElement;
                        if (target) {
                            observer.observe(target, { childList: true, subtree: true });
                        }
                    }

                    // 4. Move miru-controls-area ABOVE miru-player-iframe / miru-video-container
                    function reorderMiruControls() {
                        try {
                            var controlsArea = document.querySelector('.miru-controls-area');
                            var videoContainer = document.querySelector('.miru-video-container');
                            var iframe = document.getElementById('miru-player-iframe') || 
                                         document.querySelector('iframe.miru-player-iframe') || 
                                         document.querySelector('.miru-player-iframe');
                            var targetPlayer = videoContainer || iframe;

                            if (controlsArea && targetPlayer && targetPlayer.parentNode) {
                                if (targetPlayer.previousElementSibling !== controlsArea) {
                                    targetPlayer.parentNode.insertBefore(controlsArea, targetPlayer);
                                }
                            }
                        } catch(e) {}
                    }

                    // 5. Auto-select "พากย์ไทย" (Thai Dubbed) first on page load
                    function autoSelectThaiDub() {
                        try {
                            if (window.__lastThaiDubHref !== window.location.href) {
                                window.__lastThaiDubHref = window.location.href;
                                window.__hasAutoSelectedThaiDub = false;
                                window.__userManuallySelectedTab = false;
                            }

                            if (window.__hasAutoSelectedThaiDub || window.__userManuallySelectedTab) {
                                return;
                            }

                            var candidateSelectors = [
                                '.miru-tab-btn',
                                '.miru-group-tabs button',
                                '.miru-group-tabs a',
                                '.miru-controls-area [role="tab"]'
                            ];

                            var buttons = document.querySelectorAll(candidateSelectors.join(','));
                            if (!buttons || buttons.length === 0) return;

                            var thaiBtn = null;

                            // Priority 1: explicitly contains "พากย์ไทย"
                            for (var i = 0; i < buttons.length; i++) {
                                var b = buttons[i];
                                var txt = (b.textContent || b.innerText || '').trim();
                                if (txt.indexOf('พากย์ไทย') !== -1) {
                                    thaiBtn = b;
                                    break;
                                }
                            }

                            // Priority 2: contains "ไทย" but not "ซับไทย"
                            if (!thaiBtn) {
                                for (var j = 0; j < buttons.length; j++) {
                                    var b2 = buttons[j];
                                    var txt2 = (b2.textContent || b2.innerText || '').trim();
                                    if (txt2.indexOf('ไทย') !== -1 && txt2.indexOf('ซับไทย') === -1) {
                                        thaiBtn = b2;
                                        break;
                                    }
                                }
                            }

                            if (thaiBtn) {
                                window.__hasAutoSelectedThaiDub = true;
                                var isActive = thaiBtn.classList.contains('active') ||
                                               thaiBtn.classList.contains('current') ||
                                               thaiBtn.classList.contains('selected') ||
                                               thaiBtn.getAttribute('aria-selected') === 'true';
                                if (!isActive) {
                                    console.log('[WOW TV] Initial load: auto-selected Thai dubbed tab');
                                    thaiBtn.click();
                                    var innerLink = thaiBtn.querySelector('a');
                                    if (innerLink) innerLink.click();
                                    setTimeout(syncActiveTabWithEpisodeGroup, 80);
                                }
                            }
                        } catch(e) {}
                    }

                    // Helper to strictly sync active tab with corresponding episode grid (prevent merging of EP buttons)
                    function syncActiveTabWithEpisodeGroup() {
                        try {
                            var tabs = document.querySelectorAll('.miru-tab-btn, .miru-group-tabs button, .miru-group-tabs a');
                            var epGrids = document.querySelectorAll('.miru-ep-grid');
                            if (tabs.length >= 2 && epGrids.length >= 2) {
                                var activeIndex = 0;
                                for (var i = 0; i < tabs.length; i++) {
                                    var t = tabs[i];
                                    if (t.classList.contains('active') || t.classList.contains('current') || 
                                        t.classList.contains('selected') || t.getAttribute('aria-selected') === 'true') {
                                        activeIndex = i;
                                        break;
                                    }
                                }

                                for (var g = 0; g < epGrids.length; g++) {
                                    var grid = epGrids[g];
                                    if (g === activeIndex) {
                                        grid.style.setProperty('display', 'flex', 'important');
                                        grid.style.setProperty('visibility', 'visible', 'important');
                                    } else {
                                        grid.style.setProperty('display', 'none', 'important');
                                        grid.style.setProperty('visibility', 'hidden', 'important');
                                    }
                                }
                            }
                        } catch(e) {}
                    }
                    window.syncActiveTabWithEpisodeGroup = syncActiveTabWithEpisodeGroup;

                    // 6. Ensure Movie Cards and Interactive Elements are TV Focusable, Clickable & Thai Dubbed only
                    function setupTvInteractiveElements() {
                        // Skip all card/DOM housekeeping while watching fullscreen video
                        // (the 2.5s interval used to cause constant jank during playback)
                        if (window.__isCinemaFullscreen) return;
                        reorderMiruControls();
                        autoSelectThaiDub();

                        // Attach tab listeners so user can select ซับไทย and พากย์ไทย freely without interference
                        var allTabs = document.querySelectorAll('.miru-tab-btn, .miru-group-tabs button, .miru-group-tabs a, .miru-controls-area [role="tab"]');
                        for (var ti = 0; ti < allTabs.length; ti++) {
                            var tabElem = allTabs[ti];
                            if (!tabElem.__tabClickListenerAttached) {
                                tabElem.__tabClickListenerAttached = true;
                                tabElem.addEventListener('click', function() {
                                    window.__userManuallySelectedTab = true;
                                    window.__hasAutoSelectedThaiDub = true;
                                    setTimeout(syncActiveTabWithEpisodeGroup, 60);
                                });
                            }
                        }
                        syncActiveTabWithEpisodeGroup();

                        // Optimize image loading & decoding for 60fps scrolling on TV
                        var allImgs = document.querySelectorAll('img');
                        for (var imi = 0; imi < allImgs.length; imi++) {
                            var im = allImgs[imi];
                            if (!im.__tv_opt) {
                                im.__tv_opt = true;
                                im.loading = 'lazy';
                                im.decoding = 'async';
                            }
                        }

                        var isPlayer = document.querySelector('.miru-player-wrapper') || 
                                       document.querySelector('.miru-controls-area') || 
                                       window.location.href.indexOf('/episode') !== -1;
                        var cards = document.querySelectorAll('.-movie');
                        for (var c = 0; c < cards.length; c++) {
                            var card = cards[c];
                            if (card.__tv_setup) continue;
                            card.__tv_setup = true;
                            if (!card.hasAttribute('tabindex')) {
                                card.setAttribute('tabindex', '0');
                                card.onclick = function() {
                                    var link = this.querySelector('a');
                                    if (link) link.click();
                                };
                            }
                            // Filter only Thai Dubbed (พากย์ไทย) on drama lists
                            if (!isPlayer) {
                                var imdb = card.querySelector('.imdb');
                                if (imdb) {
                                    var txt = imdb.textContent || imdb.innerText;
                                    if (txt.indexOf('ซับไทย') !== -1 && txt.indexOf('พากย์ไทย') === -1) {
                                        card.style.setProperty('display', 'none', 'important');
                                    }
                                }
                            }
                        }

                        var interactive = document.querySelectorAll('.miru-ep-btn, .miru-server-btn, .miru-tab-btn, .pagination a, .nav-links a');
                        for (var m = 0; m < interactive.length; m++) {
                            var itm = interactive[m];
                            if (!itm.__tv_init) {
                                itm.__tv_init = true;
                                if (!itm.hasAttribute('tabindex')) {
                                    itm.setAttribute('tabindex', '0');
                                }
                            }
                        }

                        // Strictly ensure player and iframe never take D-pad focus on episode pages
                        var playerElems = document.querySelectorAll('.miru-video-container, #miru-player-iframe, iframe.miru-player-iframe, .miru-player-wrapper iframe, video');
                        for (var pIdx = 0; pIdx < playerElems.length; pIdx++) {
                            playerElems[pIdx].setAttribute('tabindex', '-1');
                        }

                        // 7. Active Video Interstitial & Overlay Ad Remover
                        var adsBox = document.getElementById('adsBox');
                        if (adsBox) adsBox.remove();
                        var videoAdOverlays = document.querySelectorAll('#adsBox, .ads-close, .ads-bokplayer, #pm-dessert, #pre-pm-dessert, .jw-ad-container, .art-ads');
                        for (var a = 0; a < videoAdOverlays.length; a++) {
                            videoAdOverlays[a].remove();
                        }

                        // Auto-skip JWPlayer ads
                        try {
                            if (typeof window.jwplayer === 'function') {
                                var jw = window.jwplayer('player') || window.jwplayer();
                                if (jw && typeof jw.getState === 'function') {
                                    if (jw.getState() === 'adPlayback' || (jw.getAdBlock && jw.getAdBlock())) {
                                        jw.skipAd();
                                    }
                                }
                            }
                        } catch(e) {}

                        // 8. Attach automatic fullscreen trigger when clicking any Episode button
                        var epBtns = document.querySelectorAll('.miru-ep-btn');
                        for (var epi = 0; epi < epBtns.length; epi++) {
                            var epb = epBtns[epi];
                            if (!epb.__epFullscreenAttached) {
                                epb.__epFullscreenAttached = true;
                                epb.addEventListener('click', function() {
                                    try {
                                        sessionStorage.setItem('__auto_fullscreen_ep', '1');
                                    } catch(e) {}
                                    if (window.AndroidBridge && typeof window.AndroidBridge.enterCinemaFullscreenAndPlay === 'function') {
                                        window.AndroidBridge.enterCinemaFullscreenAndPlay();
                                    } else if (typeof window.playAndFullscreenCurrentVideo === 'function') {
                                        window.playAndFullscreenCurrentVideo();
                                    }
                                });
                            }
                        }

                        // Check if current page was opened from an episode click -> auto cinema fullscreen & play
                        try {
                            if (sessionStorage.getItem('__auto_fullscreen_ep') === '1') {
                                sessionStorage.removeItem('__auto_fullscreen_ep');
                                if (window.AndroidBridge && typeof window.AndroidBridge.enterCinemaFullscreenAndPlay === 'function') {
                                    window.AndroidBridge.enterCinemaFullscreenAndPlay();
                                } else if (typeof window.playAndFullscreenCurrentVideo === 'function') {
                                    setTimeout(function() {
                                        window.playAndFullscreenCurrentVideo();
                                    }, 400);
                                }
                            }
                        } catch(e) {}

                        // 9. Auto-Fullscreen and Maximize Volume on Play
                        setupAutoFullscreenAndVolume();
                    }

                    // Ensures EP button or Tab is focused once on initial load, never stealing focus during navigation
                    function autoFocusEpisodeContent() {
                        try {
                            if (window.__isCinemaFullscreen) return;
                            if (window.__lastAutoFocusHref !== window.location.href) {
                                window.__lastAutoFocusHref = window.location.href;
                                window.__hasAutoFocusedEpPage = false;
                            }
                            if (window.__hasAutoFocusedEpPage) return;

                            var cur = document.activeElement;
                            // If user is already actively focused on an episode button, tab, or card, DO NOT TOUCH FOCUS!
                            if (cur && cur !== document.body && cur !== document.documentElement && cur.tagName !== 'IFRAME' && cur.tagName !== 'VIDEO') {
                                window.__hasAutoFocusedEpPage = true;
                                return;
                            }

                            var epArea = document.querySelector('.miru-controls-area') || 
                                         document.querySelector('.miru-ep-grid');
                            if (epArea) {
                                var target = document.querySelector('.miru-ep-btn.active') || 
                                             document.querySelector('.miru-ep-btn') || 
                                             document.querySelector('.miru-tab-btn.active') || 
                                             document.querySelector('.miru-tab-btn');
                                if (target) {
                                    window.__hasAutoFocusedEpPage = true;
                                    target.focus();
                                }
                            }
                        } catch(e) {}
                    }

                    // Fullscreen and Volume Maximizer
                    function triggerVideoFullscreenAndMaxVolume(video) {
                        try {
                            if (!video) return;
                            video.volume = 1.0;
                            video.muted = false;
                            video.defaultMuted = false;

                            if (video.requestFullscreen) {
                                video.requestFullscreen().catch(function() {});
                            } else if (video.webkitRequestFullscreen) {
                                video.webkitRequestFullscreen();
                            } else if (video.webkitEnterFullscreen) {
                                video.webkitEnterFullscreen();
                            }

                            if (window.AndroidBridge && typeof window.AndroidBridge.onVideoPlayStarted === 'function') {
                                window.AndroidBridge.onVideoPlayStarted();
                            }
                        } catch(e) {}
                    }

                    window.triggerVideoFullscreenAndMaxVolume = triggerVideoFullscreenAndMaxVolume;

                    // Global helper to apply CSS Cinema Fullscreen Mode
                    window.applyCinemaFullscreen = function() {
                        try {
                            var player = document.querySelector('.miru-video-container') || 
                                         document.getElementById('miru-player-iframe') || 
                                         document.querySelector('iframe.miru-player-iframe') ||
                                         document.querySelector('iframe');
                            if (!player) return;
                            document.documentElement.classList.add('tv-video-fullscreen');
                            document.body.classList.add('tv-video-fullscreen');
                            player.classList.add('tv-fullscreen-player');

                            var p = player.parentElement;
                            while (p && p !== document.body && p !== document.documentElement) {
                                p.setAttribute('data-tv-orig-overflow', p.style.overflow || '');
                                p.style.setProperty('overflow', 'visible', 'important');
                                p.style.setProperty('transform', 'none', 'important');
                                p.style.setProperty('contain', 'none', 'important');
                                p = p.parentElement;
                            }
                            window.__isCinemaFullscreen = true;

                            // Ensure iframe is unblocked and receives pointer-events and focus
                            var ifr = player.tagName === 'IFRAME' ? player : player.querySelector('iframe');
                            if (ifr) {
                                ifr.style.setProperty('pointer-events', 'auto', 'important');
                                try { ifr.focus(); } catch(e) {}
                            }
                        } catch(e) {}
                    };

                    window.exitCinemaFullscreen = function() {
                        try {
                            document.documentElement.classList.remove('tv-video-fullscreen');
                            document.body.classList.remove('tv-video-fullscreen');
                            var players = document.querySelectorAll('.tv-fullscreen-player');
                            for (var i = 0; i < players.length; i++) {
                                players[i].classList.remove('tv-fullscreen-player');
                            }
                            var modified = document.querySelectorAll('[data-tv-orig-overflow]');
                            for (var j = 0; j < modified.length; j++) {
                                modified[j].style.overflow = modified[j].getAttribute('data-tv-orig-overflow');
                                modified[j].removeAttribute('data-tv-orig-overflow');
                                modified[j].style.transform = '';
                                modified[j].style.contain = '';
                            }
                            window.__isCinemaFullscreen = false;
                        } catch(e) {}
                    };

                    // Global helper invoked when user clicks/presses OK on player or episode
                    window.playAndFullscreenCurrentVideo = function() {
                        try {
                            // 1. Immediately expand player to 100% cinema fullscreen
                            if (typeof window.applyCinemaFullscreen === 'function') {
                                window.applyCinemaFullscreen();
                            }

                            // 2. Play and unmute all video tags
                            var videos = document.querySelectorAll('video');
                            for (var i = 0; i < videos.length; i++) {
                                var v = videos[i];
                                v.muted = false;
                                v.defaultMuted = false;
                                v.volume = 1.0;
                                try { v.play(); } catch(e) {}
                            }

                            // 3. Setup iframe player and un-mute
                            var iframe = document.getElementById('miru-player-iframe') || 
                                         document.querySelector('iframe.miru-player-iframe') || 
                                         document.querySelector('iframe');
                            if (iframe) {
                                iframe.setAttribute('allowfullscreen', 'true');
                                iframe.setAttribute('webkitallowfullscreen', 'true');
                                iframe.setAttribute('allow', 'autoplay; fullscreen; encrypted-media; picture-in-picture');
                                try {
                                    iframe.contentWindow.postMessage('{"event":"command","func":"unMute"}', '*');
                                    iframe.contentWindow.postMessage('{"event":"command","func":"setVolume","args":[100]}', '*');
                                    iframe.contentWindow.postMessage('{"method":"play"}', '*');
                                    iframe.contentWindow.postMessage('{"action":"unmute"}', '*');
                                    iframe.contentWindow.postMessage('play', '*');
                                } catch(e) {}
                            }

                            // 4. Click any embedded play button elements
                            var playBtns = document.querySelectorAll('.play-btn, .art-state, .jw-display-icon-display, .vjs-big-play-button, button[aria-label="Play"], .play-button, [title*="Play"], [title*="เล่น"]');
                            for (var pb = 0; pb < playBtns.length; pb++) {
                                try { playBtns[pb].click(); } catch(e) {}
                            }

                            // 5. Signal native Android to enter cinema fullscreen and tap play button
                            if (window.AndroidBridge && typeof window.AndroidBridge.enterCinemaFullscreenAndPlay === 'function') {
                                window.AndroidBridge.enterCinemaFullscreenAndPlay();
                            } else if (window.AndroidBridge && typeof window.AndroidBridge.enterCinemaFullscreen === 'function') {
                                window.AndroidBridge.enterCinemaFullscreen();
                            }
                        } catch(e) {
                            console.error('[WOW TV] playAndFullscreenCurrentVideo error:', e);
                        }
                    };

                    // Global helper to simulate a double-tap on the video player element
                    window.doubleTapCurrentPlayer = function() {
                        try {
                            var player = document.querySelector('.miru-video-container') || 
                                         document.getElementById('miru-player-iframe') || 
                                         document.querySelector('iframe.miru-player-iframe') ||
                                         document.querySelector('iframe') ||
                                         document.querySelector('video');
                            var fx = 0.5;
                            var fy = 0.5;
                            if (player) {
                                var rect = player.getBoundingClientRect();
                                if (rect && rect.width > 0 && rect.height > 0) {
                                    fx = (rect.left + rect.width / 2) / (window.innerWidth || 1);
                                    fy = (rect.top + rect.height / 2) / (window.innerHeight || 1);
                                }
                                try {
                                    var dbl = new MouseEvent('dblclick', {
                                        bubbles: true,
                                        cancelable: true,
                                        clientX: fx * window.innerWidth,
                                        clientY: fy * window.innerHeight,
                                        view: window
                                    });
                                    player.dispatchEvent(dbl);
                                } catch(e) {}
                            }
                            if (window.AndroidBridge && typeof window.AndroidBridge.doubleTapAtCoordinates === 'function') {
                                window.AndroidBridge.doubleTapAtCoordinates(fx, fy);
                            }
                        } catch(e) {}
                    };

                    function setupAutoFullscreenAndVolume() {
                        try {
                            // Ensure all iframes support fullscreen
                            var iframes = document.querySelectorAll('iframe');
                            for (var i = 0; i < iframes.length; i++) {
                                var ifr = iframes[i];
                                ifr.setAttribute('allowfullscreen', 'true');
                                ifr.setAttribute('webkitallowfullscreen', 'true');
                                ifr.setAttribute('mozallowfullscreen', 'true');
                                ifr.setAttribute('allow', 'autoplay; fullscreen; encrypted-media; picture-in-picture');
                            }

                            // Check active playing video
                            var allVideos = document.querySelectorAll('video');
                            for (var vIdx = 0; vIdx < allVideos.length; vIdx++) {
                                var currentVid = allVideos[vIdx];
                                if (currentVid.muted) currentVid.muted = false;
                                if (currentVid.volume < 1.0) currentVid.volume = 1.0;
                                if (!currentVid.paused && !currentVid.__fullscreenHandled) {
                                    currentVid.__fullscreenHandled = true;
                                    triggerVideoFullscreenAndMaxVolume(currentVid);
                                }
                            }
                        } catch(e) {}
                    }

                    // Global capturing play event listener for any video element
                    document.addEventListener('play', function(e) {
                        if (e.target && (e.target.tagName === 'VIDEO' || (e.target.querySelector && e.target.querySelector('video')))) {
                            var v = e.target.tagName === 'VIDEO' ? e.target : e.target.querySelector('video');
                            triggerVideoFullscreenAndMaxVolume(v);
                        }
                    }, true);

                    // Prevent videos from remaining muted
                    document.addEventListener('volumechange', function(e) {
                        if (e.target && e.target.tagName === 'VIDEO') {
                            if (e.target.muted) e.target.muted = false;
                            if (e.target.volume < 1.0) e.target.volume = 1.0;
                        }
                    }, true);

                    setupTvInteractiveElements();
                    autoFocusEpisodeContent();
                    setInterval(setupTvInteractiveElements, 4000);

                } catch (err) {
                    console.error('[WOW TV] Injection script error:', err);
                }
            })();
        """.trimIndent()
    }
}

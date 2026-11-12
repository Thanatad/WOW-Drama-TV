package com.example.tv

import android.os.SystemClock
import android.view.KeyEvent
import android.webkit.WebView

object TvRemoteController {

    private const val SCROLL_AMOUNT_PX = 280
    private const val SCROLL_FAST_AMOUNT_PX = 520

    // Coalesce rapid D-pad key repeats so the spatial-navigation JS runs at most
    // ~11x/sec instead of stacking one heavy script per key repeat (fixes lag on low-end TV CPUs)
    private const val NAV_THROTTLE_MS = 90L
    private var lastNavTime = 0L
    private var pendingNav: Runnable? = null

    /**
     * Handles hardware D-pad key events from Google TV / Android TV remote controls.
     * Returns true if the key was handled, false to delegate to default handling.
     */
    fun handleKeyDown(
        keyCode: Int,
        event: KeyEvent,
        webView: WebView,
        onReachedTop: (() -> Unit)? = null
    ): Boolean {
        val isRepeat = event.repeatCount > 0
        val scrollDistance = if (isRepeat) SCROLL_FAST_AMOUNT_PX else SCROLL_AMOUNT_PX

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                requestNavigation(webView, "up", -scrollDistance, onReachedTop)
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                requestNavigation(webView, "down", scrollDistance, null)
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                requestNavigation(webView, "left", -scrollDistance, null)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                requestNavigation(webView, "right", scrollDistance, null)
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                clickActiveElement(webView)
                true
            }
            KeyEvent.KEYCODE_PAGE_UP -> {
                webView.evaluateJavascript("window.scrollBy({ top: -window.innerHeight * 0.8, behavior: 'smooth' });", null)
                true
            }
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                webView.evaluateJavascript("window.scrollBy({ top: window.innerHeight * 0.8, behavior: 'smooth' });", null)
                true
            }
            else -> false
        }
    }

    /**
     * Throttled dispatcher for D-pad navigation. Executes immediately when idle,
     * otherwise collapses rapid key repeats into a single deferred run (latest direction wins).
     */
    private fun requestNavigation(
        webView: WebView,
        direction: String,
        scrollDelta: Int,
        onReachedTop: (() -> Unit)?
    ) {
        val now = SystemClock.uptimeMillis()
        val sinceLast = now - lastNavTime
        if (sinceLast >= NAV_THROTTLE_MS) {
            cancelPendingNav(webView)
            lastNavTime = now
            navigateOrScroll(webView, direction, scrollDelta, onReachedTop)
        } else {
            // Latest key press replaces any queued one (prevents input pile-up / rubber-banding)
            pendingNav?.let { webView.removeCallbacks(it) }
            val task = Runnable {
                pendingNav = null
                lastNavTime = SystemClock.uptimeMillis()
                navigateOrScroll(webView, direction, scrollDelta, onReachedTop)
            }
            pendingNav = task
            webView.postDelayed(task, NAV_THROTTLE_MS - sinceLast)
        }
    }

    private fun cancelPendingNav(webView: WebView) {
        pendingNav?.let {
            webView.removeCallbacks(it)
            pendingNav = null
        }
    }

    /**
     * Focuses the first episode button, active tab, or movie card on screen.
     * Prioritizes EP buttons on episode pages so the user can immediately select episodes.
     */
    fun focusFirstContentCard(webView: WebView) {
        val js = """
            (function() {
                var firstEp = document.querySelector('.miru-ep-btn.active') || 
                              document.querySelector('.miru-ep-btn') || 
                              document.querySelector('.miru-tab-btn.active') || 
                              document.querySelector('.miru-tab-btn');
                if (firstEp) {
                    firstEp.focus();
                    firstEp.scrollIntoView({ behavior: 'auto', block: 'nearest', inline: 'nearest' });
                    return;
                }

                var firstCard = document.querySelector('.-movie');
                if (firstCard) {
                    firstCard.focus();
                    firstCard.scrollIntoView({ behavior: 'auto', block: 'nearest', inline: 'nearest' });
                    return;
                }

                var firstLink = document.querySelector('a');
                if (firstLink) {
                    firstLink.focus();
                    firstLink.scrollIntoView({ behavior: 'auto', block: 'nearest', inline: 'nearest' });
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /**
     * Intelligently either shifts spatial focus to the neighboring interactive element
     * or smoothly scrolls the page if no element is found in that direction.
     */
    private fun navigateOrScroll(
        webView: WebView,
        direction: String,
        scrollDelta: Int,
        onReachedTop: (() -> Unit)?
    ) {
        val js = """
            (function() {
                var dir = '$direction';
                var delta = $scrollDelta;
                var active = document.activeElement;

                // Handle D-pad Left / Right seeking in Cinema Fullscreen
                if (window.__isCinemaFullscreen) {
                    if (dir === 'left' || dir === 'right') {
                        var seekSeconds = dir === 'left' ? -10 : 10;
                        var v = document.querySelector('video');
                        if (v) {
                            v.currentTime = Math.max(0, v.currentTime + seekSeconds);
                        }
                        var ifr = document.querySelector('.tv-fullscreen-player iframe') || document.querySelector('iframe');
                        if (ifr) {
                            try {
                                ifr.contentWindow.postMessage('{"event":"command","func":"seekTo","args":[' + seekSeconds + ', true]}', '*');
                            } catch(e) {}
                        }
                        return 'SEEK_VIDEO';
                    }
                    return 'FULLSCREEN_ACTIVE';
                }

                // Fast single-pass candidate collection with caching.
                // On low-end TV CPUs (Chromecast), re-querying + getBoundingClientRect on every
                // key press caused heavy reflow lag. Cache is keyed by scroll position + viewport
                // and expires quickly, so it stays correct while scrolling or after DOM changes.
                var vh = window.innerHeight;
                var vw = window.innerWidth;
                var cacheKey = (window.scrollY || 0) + '|' + vw + '|' + vh + '|' + location.pathname;
                var cached = window.__wowTvNavCache;
                var candidates;

                if (cached && cached.key === cacheKey && cached.list && (Date.now() - cached.time) < 3000) {
                    candidates = cached.list;
                } else {
                    var raw = document.querySelectorAll('.miru-ep-btn, .miru-tab-btn, .-movie, .miru-server-btn, .pagination a, .nav-links a, a, button, [role="button"], input, [tabindex="0"]');
                    candidates = [];

                    for (var ci = 0; ci < raw.length; ci++) {
                        var el = raw[ci];
                        if (el.tagName === 'VIDEO' || el.tagName === 'IFRAME') continue;
                        // Fast visibility check without triggering getComputedStyle reflow
                        if (el.offsetWidth === 0 || el.offsetHeight === 0) continue;
                        if (el.parentElement && (el.parentElement.closest('.miru-ep-btn') || el.parentElement.closest('.miru-tab-btn') || el.parentElement.closest('.-movie'))) {
                            continue;
                        }
                        if (el.closest && (el.closest('.miru-video-container') || el.closest('.tv-fullscreen-player') || el.closest('.miru-player-wrapper iframe'))) {
                            continue;
                        }

                        var rect = el.getBoundingClientRect();
                        if (rect.bottom < -40 || rect.top > vh + 80 || rect.right < -40 || rect.left > vw + 40) {
                            continue;
                        }

                        candidates.push({
                            el: el,
                            top: rect.top,
                            bottom: rect.bottom,
                            left: rect.left,
                            right: rect.right,
                            cx: rect.left + rect.width / 2,
                            cy: rect.top + rect.height / 2,
                            isEp: el.classList && (el.classList.contains('miru-ep-btn') || el.classList.contains('miru-tab-btn'))
                        });
                    }

                    window.__wowTvNavCache = { key: cacheKey, time: Date.now(), list: candidates };
                }
                
                // Check if user is at the top or pressing UP from topmost row
                if (dir === 'up') {
                    var currentScrollY = window.scrollY || document.documentElement.scrollTop || 0;
                    
                    if (!active || active === document.body || active === document.documentElement) {
                        if (currentScrollY <= 60) {
                            return 'REACHED_TOP';
                        }
                    } else {
                        var curR = active.getBoundingClientRect();
                        var hasElementAbove = false;
                        for (var c = 0; c < candidates.length; c++) {
                            if (candidates[c].el !== active && candidates[c].bottom < curR.top + 20) {
                                hasElementAbove = true;
                                break;
                            }
                        }
                        if (!hasElementAbove && currentScrollY <= 80) {
                            return 'REACHED_TOP';
                        }
                    }
                }

                if (!active || active === document.body || active === document.documentElement) {
                    var targetFirst = null;
                    for (var fi = 0; fi < candidates.length; fi++) {
                        if (candidates[fi].isEp) {
                            targetFirst = candidates[fi].el;
                            break;
                        }
                    }
                    if (!targetFirst && candidates.length > 0) {
                        targetFirst = candidates[0].el;
                    }
                    if (targetFirst) {
                        targetFirst.focus();
                        targetFirst.scrollIntoView({ behavior: 'auto', block: 'nearest', inline: 'nearest' });
                        return 'FOCUSED_FIRST';
                    }
                }

                // Spatial search from active element using cached coordinates
                if (active && active !== document.body) {
                    var curRect = active.getBoundingClientRect();
                    var curCx = curRect.left + curRect.width / 2;
                    var curCy = curRect.top + curRect.height / 2;
                    
                    var best = null;
                    var minDistance = Infinity;

                    for (var i = 0; i < candidates.length; i++) {
                        var item = candidates[i];
                        if (item.el === active) continue;
                        // Skip elements removed from the DOM since the cache was built
                        if (!item.el.isConnected) continue;

                        var dx = item.cx - curCx;
                        var dy = item.cy - curCy;
                        var isValidDir = false;

                        if (dir === 'down' && dy > 15) isValidDir = true;
                        else if (dir === 'up' && dy < -15) isValidDir = true;
                        else if (dir === 'right' && dx > 15) isValidDir = true;
                        else if (dir === 'left' && dx < -15) isValidDir = true;

                        if (isValidDir) {
                            var dist = (dir === 'down' || dir === 'up') 
                                ? Math.abs(dy) + Math.abs(dx) * 1.4
                                : Math.abs(dx) + Math.abs(dy) * 1.4;
                            if (dist < minDistance) {
                                minDistance = dist;
                                best = item.el;
                            }
                        }
                    }

                    if (best) {
                        best.focus();
                        best.scrollIntoView({ behavior: 'auto', block: 'nearest', inline: 'nearest' });
                        return 'FOCUSED_NEIGHBOR';
                    }

                    if (dir === 'up') {
                        var scrY = window.scrollY || document.documentElement.scrollTop || 0;
                        if (scrY <= 120) {
                            return 'REACHED_TOP';
                        }
                    }
                }

                // Instant hardware page scroll (No smooth animation lag on TV)
                if (dir === 'down' || dir === 'up') {
                    window.scrollBy({ top: delta, behavior: 'auto' });
                } else {
                    window.scrollBy({ left: delta, behavior: 'auto' });
                }
                return 'SCROLLED';
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            if (result != null && result.contains("REACHED_TOP")) {
                onReachedTop?.invoke()
            }
        }
    }

    /**
     * Triggers click on the currently focused element, or triggers fullscreen & max volume on player.
     */
    private fun clickActiveElement(webView: WebView) {
        val js = """
            (function() {
                var el = document.activeElement;

                // If in cinema fullscreen already, toggle play/pause by tapping center of screen
                if (window.__isCinemaFullscreen) {
                    var ifr = document.getElementById('miru-player-iframe') || 
                              document.querySelector('iframe.miru-player-iframe') || 
                              document.querySelector('iframe');
                    if (ifr) {
                        try {
                            ifr.contentWindow.postMessage('play', '*');
                        } catch(e) {}
                    }
                    if (window.AndroidBridge && typeof window.AndroidBridge.tapAtCoordinates === 'function') {
                        window.AndroidBridge.tapAtCoordinates(0.5, 0.5);
                    }
                    return 'TOGGLE_PLAY';
                }

                if (el && el !== document.body && el !== document.documentElement) {
                    // 1. Tab buttons (พากย์ไทย, ซับไทย)
                    var isTabBtn = (el.classList && el.classList.contains('miru-tab-btn')) || 
                                   (el.closest && el.closest('.miru-tab-btn')) ||
                                   (el.closest && el.closest('.miru-group-tabs'));
                    if (isTabBtn) {
                        window.__userManuallySelectedTab = true;
                        window.__hasAutoSelectedThaiDub = true;
                        // Click the inner anchor once (clicking both button AND anchor
                        // triggers the navigation twice -> double page loads)
                        var innerA = el.querySelector('a');
                        if (innerA) innerA.click(); else el.click();
                        return 'CLICKED_TAB';
                    }

                    // 2. Episode buttons and Server buttons (EP 1, EP 2, Server 1, etc.)
                    var isEpBtn = (el.classList && el.classList.contains('miru-ep-btn')) || 
                                  (el.closest && el.closest('.miru-ep-btn'));
                    var isServerBtn = (el.classList && el.classList.contains('miru-server-btn')) || 
                                      (el.closest && el.closest('.miru-server-btn'));
                    if (isEpBtn || isServerBtn) {
                        try {
                            sessionStorage.setItem('__auto_fullscreen_ep', '1');
                        } catch(e) {}
                        // Click the inner anchor once (clicking both button AND anchor
                        // triggers the navigation twice -> double page loads)
                        var innerLink = el.querySelector('a');
                        if (innerLink) innerLink.click(); else el.click();

                        // Automatically expand to cinema fullscreen and start playing immediately
                        if (window.AndroidBridge && typeof window.AndroidBridge.enterCinemaFullscreenAndPlay === 'function') {
                            window.AndroidBridge.enterCinemaFullscreenAndPlay();
                        } else if (typeof window.playAndFullscreenCurrentVideo === 'function') {
                            setTimeout(function() {
                                window.playAndFullscreenCurrentVideo();
                            }, 450);
                        }
                        return 'SELECTED_EP';
                    }

                    // 3. Movie cards (.-movie)
                    var isMovieCard = (el.classList && el.classList.contains('-movie')) || 
                                      (el.closest && el.closest('.-movie'));
                    if (isMovieCard) {
                        var cardLink = el.querySelector('a') || el;
                        cardLink.click();
                        return 'CLICKED_MOVIE';
                    }

                    // 4. Direct Player container
                    var isDirectPlayer = (el.classList && (el.classList.contains('miru-video-container') || el.classList.contains('tv-fullscreen-player'))) || 
                                         el.id === 'miru-player-iframe' || 
                                         el.tagName === 'VIDEO' ||
                                         (el.closest && el.closest('.miru-video-container'));

                    if (isDirectPlayer) {
                        if (window.AndroidBridge && typeof window.AndroidBridge.enterCinemaFullscreenAndPlay === 'function') {
                            window.AndroidBridge.enterCinemaFullscreenAndPlay();
                        } else if (typeof window.playAndFullscreenCurrentVideo === 'function') {
                            window.playAndFullscreenCurrentVideo();
                        }
                        return 'PLAYED_VIDEO';
                    }

                    // 5. Standard click for all other interactive elements
                    el.click();
                    var innerElem = el.querySelector('a');
                    if (innerElem) innerElem.click();
                    return 'CLICKED';
                } else {
                    // Fallback: click center of viewport only
                    var x = window.innerWidth / 2;
                    var y = window.innerHeight / 2;
                    var target = document.elementFromPoint(x, y);
                    if (target) {
                        target.click();
                        var link = target.closest('a') || target.querySelector('a');
                        if (link) link.click();
                    }
                    return 'CLICKED_VIEWPORT';
                }
                return 'CLICKED';
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }
}

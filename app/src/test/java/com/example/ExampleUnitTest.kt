package com.example

import com.example.adblock.AdBlockManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun verify_adblock_baseline_rules_active() {
        assertTrue(AdBlockManager.getRuleCount() > 0)
        assertTrue(AdBlockManager.shouldBlockUrl("https://example.com/wp-content/plugins/ads-vip/banner.js"))
        assertTrue(AdBlockManager.shouldBlockUrl("https://bet-casino-slot.com/banner.gif"))
        val tvCss = AdBlockManager.getTvLayoutBeautifierCss()
        assertTrue(tvCss.contains(".list-movie"))
        assertTrue(tvCss.contains(".-movie"))
        assertTrue(tvCss.contains("#adsBox"))
        assertTrue(tvCss.contains(".jw-ad-container"))
        // Verify -rightbar is NOT hidden as a class (which caused black screen)
        assertTrue(!tvCss.contains(".-rightbar,"))

        // Verify VAST video ad detection
        assertTrue(AdBlockManager.isVastAdUrl("https://example.com/wp-content/uploads/mvast.xml"))
        assertTrue(AdBlockManager.isVastAdUrl("https://adserver.com/vast?tag=123"))
        assertTrue(AdBlockManager.EMPTY_VAST_RESPONSE.contains("<VAST version=\"3.0\"></VAST>"))

        // Verify miru-controls-area ordering above video iframe in CSS
        assertTrue(tvCss.contains(".miru-controls-area"))
        assertTrue(tvCss.contains("order: 1 !important"))
        assertTrue(tvCss.contains(".miru-video-container"))
        assertTrue(tvCss.contains("order: 3 !important"))

        // Verify JS injection contains DOM reorder and Thai Dub auto-selection
        val script = AdBlockManager.getInjectionScript()
        assertTrue(script.contains("reorderMiruControls"))
        assertTrue(script.contains("autoSelectThaiDub"))
        assertTrue(script.contains("พากย์ไทย"))

        // Verify auto fullscreen and volume maximizer in JS
        assertTrue(script.contains("triggerVideoFullscreenAndMaxVolume"))
        assertTrue(script.contains("AndroidBridge"))
        assertTrue(script.contains("setupAutoFullscreenAndVolume"))
        assertTrue(script.contains("playAndFullscreenCurrentVideo"))
        assertTrue(script.contains("applyCinemaFullscreen"))
        assertTrue(script.contains("exitCinemaFullscreen"))
        assertTrue(script.contains("doubleTapCurrentPlayer"))

        assertTrue(script.contains("syncActiveTabWithEpisodeGroup"))

        // Verify media streams and wow-drama domain are never blocked
        assertTrue(!AdBlockManager.shouldBlockUrl("https://player.example.com/stream/index.m3u8"))
        assertTrue(!AdBlockManager.shouldBlockUrl("https://player.example.com/stream/seg-123.ts"))
        assertTrue(!AdBlockManager.shouldBlockUrl("https://player.example.com/video.mp4"))
        assertTrue(!AdBlockManager.shouldBlockUrl("https://wow-drama.com/series/test-drama/"))

        // Verify cinema fullscreen and EP auto-fullscreen rules
        assertTrue(tvCss.contains(".tv-fullscreen-player"))
        assertTrue(tvCss.contains("tv-video-fullscreen"))
        assertTrue(script.contains("__auto_fullscreen_ep"))
        assertTrue(script.contains("autoFocusEpisodeContent"))
    }
}

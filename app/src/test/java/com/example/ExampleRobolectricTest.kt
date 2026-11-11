package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.adblock.AdBlockManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("WOW Drama TV", appName)
    }

    @Test
    fun `verify adblock baseline rules are active`() {
        assertTrue(AdBlockManager.getRuleCount() > 0)
        assertTrue(AdBlockManager.shouldBlockUrl("https://example.com/wp-content/plugins/ads-vip/banner.js"))
        assertTrue(AdBlockManager.shouldBlockUrl("https://bet-casino-slot.com/banner.gif"))
    }
}

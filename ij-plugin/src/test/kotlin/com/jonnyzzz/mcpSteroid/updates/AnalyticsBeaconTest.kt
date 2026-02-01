/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.updates

import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.common.timeoutRunBlocking

/**
 * Test for AnalyticsBeacon service.
 *
 * Note: These tests verify that the beacon doesn't throw exceptions.
 * Actual HTTP requests to Cloudflare are fire-and-forget, so we don't verify responses.
 */
class AnalyticsBeaconTest : LightPlatformTestCase() {
    fun testBeaconFireAndForget() = timeoutRunBlocking {
        val beacon = AnalyticsBeacon.getInstance()

        // Should not throw even if network fails
        val r = beacon.sendBlocking("test-event")

        println("status = " + r.statusCode())
        println("body = '" + r.body() + "'")
        assertTrue(r.statusCode() == 200)
    }
}

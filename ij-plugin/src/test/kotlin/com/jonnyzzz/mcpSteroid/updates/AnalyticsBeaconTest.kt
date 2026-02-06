/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.updates

import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.common.timeoutRunBlocking
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

class AnalyticsBeaconTest : LightPlatformTestCase() {

    override fun tearDown() {
        try {
            analyticsBeacon.shutdown()
        } finally {
            super.tearDown()
        }
    }

    fun testServiceAvailable() {
        assertNotNull(analyticsBeacon)
    }

    fun testCaptureDoesNotThrow() = timeoutRunBlocking(10.seconds) {
        analyticsBeacon.capture("test_event", mapOf("key" to "value"))
        delay(2.seconds)
    }
}

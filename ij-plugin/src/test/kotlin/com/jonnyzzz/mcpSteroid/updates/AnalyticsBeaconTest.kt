/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.updates

import com.intellij.openapi.components.service
import com.intellij.testFramework.LightPlatformTestCase
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds

/**
 * Test for AnalyticsBeacon service.
 *
 * Note: These tests verify that the beacon doesn't throw exceptions.
 * Actual HTTP requests to Cloudflare are fire-and-forget, so we don't verify responses.
 */
class AnalyticsBeaconTest : LightPlatformTestCase() {

    fun testBeaconServiceAvailable() {
        val beacon = service<AnalyticsBeacon>()
        assertNotNull("AnalyticsBeacon service should be available", beacon)
    }

    fun testBeaconFireAndForget() = runBlocking {
        val beacon = AnalyticsBeacon.getInstance()

        // Should not throw even if network fails
        beacon.send("test-event", mapOf(
            "testKey" to "testValue",
            "timestamp" to System.currentTimeMillis()
        ))

        // Give async operation time to complete
        delay(2.seconds)

        // Test passes if no exception thrown
        assertTrue(true)
    }

    fun testBeaconWithMultipleEvents() = runBlocking {
        val beacon = AnalyticsBeacon.getInstance()

        // Send multiple events rapidly
        repeat(5) { i ->
            beacon.send("test-event-$i", mapOf(
                "iteration" to i,
                "timestamp" to System.currentTimeMillis()
            ))
        }

        // Give async operations time to complete
        delay(3.seconds)

        // Test passes if no exception thrown
        assertTrue(true)
    }

    fun testBeaconWithDifferentValueTypes() = runBlocking {
        val beacon = AnalyticsBeacon.getInstance()

        beacon.send("test-types", mapOf(
            "stringValue" to "test",
            "intValue" to 42,
            "longValue" to 1234567890L,
            "doubleValue" to 3.14,
            "booleanValue" to true
        ))

        // Give async operation time to complete
        delay(2.seconds)

        // Test passes if no exception thrown
        assertTrue(true)
    }

    fun testBeaconWithEmptyMetadata() = runBlocking {
        val beacon = AnalyticsBeacon.getInstance()

        // Should work with empty metadata
        beacon.send("test-empty-metadata")

        // Give async operation time to complete
        delay(2.seconds)

        // Test passes if no exception thrown
        assertTrue(true)
    }
}

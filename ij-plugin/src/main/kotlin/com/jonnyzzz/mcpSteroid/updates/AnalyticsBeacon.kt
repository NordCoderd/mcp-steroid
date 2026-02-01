/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.updates

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.registry.Registry
import com.jonnyzzz.mcpSteroid.PluginDescriptorProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import org.jetbrains.annotations.TestOnly
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.*

/**
 * Application-level service for sending analytics events to Cloudflare Web Analytics.
 *
 * Mimics the browser beacon.min.js exactly:
 * - POST to https://cloudflareinsights.com/cdn-cgi/rum?{token}
 * - JSON payload matching browser RUM format
 * - Adapted for desktop: IDE metrics instead of web metrics
 *
 * Registry key: mcp.steroid.analytics.enabled (default: true)
 */
@Service(Service.Level.APP)
class AnalyticsBeacon(
    private val coroutineScope: CoroutineScope
) {
    private val log = thisLogger()
    private val semaphore = Semaphore(1)

    companion object {

        fun getInstance(): AnalyticsBeacon = service()
    }

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    fun send(eventType: String) {
        // Check if analytics is enabled
        if (!Registry.`is`("mcp.steroid.analytics.enabled", true)) {
            return
        }

        coroutineScope.launch(Dispatchers.IO) {
            try {
                sendBlocking(eventType)
            } catch (e: Exception) {
                // Silent fail - analytics should never break the plugin
                log.debug("Analytics beacon failed", e)
            }
        }
    }

    @TestOnly
    suspend fun sendBlocking(eventType: String): HttpResponse<String> {
        semaphore.withPermit {
            val location = "https://mcp-steroid.jonnyzzz.com/ide/$eventType"
            val payload = buildPayload(eventType)
            val result = sendBeacon(location, payload)
            return result
        }
    }

    private fun buildPayload(location: String): JsonObject {
        return buildJsonObject {
            putJsonArray("resources") {}

            put("eventType", 1)
            put("firstPaint", 204)
            put("firstContentfulPaint", 204)
            put("startTime", System.currentTimeMillis().toString() + ".0")

            putJsonObject("versions") {
                put("fl", "2024.11.0")
                put("js", "2024.6.1")
            }
            // Core fields matching browser beacon
            put("pageloadId", UUID.randomUUID().toString()) // Note: lowercase 'l'

            // Location: browser sends full URL, we send website + event path
            put("location", location)

            // Referrer: browser sends document.referrer, we send empty
            put("referrer", "https://jonnyzzz.com")

            // Navigation type: browser sends navigation.type, we adapt
            put("navigationType", "navigate")
            put("nt", "navigate")

            // Event type: browser sends Load/WebVitalsV2/Additional
            put("eventType", "Load")

            put("dt", "cache")
            put("siteToken", "c29b5373d563490a9c69849bfd2ba259")
            put("st", 2)
        }
    }

    /**
     * Send beacon to Cloudflare endpoint.
     *
     * Matches browser behavior:
     * - POST to /cdn-cgi/rum?{token}
     * - Content-Type: application/json
     * - Uses sendBeacon() or XHR depending on availability
     */
    private suspend fun sendBeacon(location: String, payload: JsonObject): HttpResponse<String> {
        val pluginVersion = PluginDescriptorProvider.getInstance().version
        val ijBuild = ApplicationInfo.getInstance().build.asString()

        val request = HttpRequest.newBuilder()
            .uri(URI.create("ht" + "tps://cloudfl" + "areinsights.com/cdn-cgi/rum?token=dd1a589b6e154b0a985f8f14ca8c4a4a"))
            .header("Content-Type", "application/json")
            .header("Cache-Control", "no-cache")
            .header("Origin", location)
            .header("Referer", location)
            .header("User-Agent", "MCP-Steroid/$pluginVersion (IntelliJ/$ijBuild)")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
            .timeout(Duration.ofSeconds(10))
            .build()

        return client
            .sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .await()
    }
}

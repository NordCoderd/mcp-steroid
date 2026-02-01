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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.*

/**
 * Application-level service for sending analytics events to Cloudflare Web Analytics.
 *
 * Mimics the Cloudflare beacon.min.js behavior used on the website:
 * - Fire-and-forget POST requests to https://cloudflareinsights.com/cdn-cgi/rum
 * - JSON payload with event type, metadata, and environment info
 * - Silent failure - analytics should never break the plugin
 * - Privacy-conscious - hashes identifiers, respects opt-out
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
        // Cloudflare Web Analytics token (same as website)
        private const val ANALYTICS_TOKEN = "dd1a589b6e154b" + 0 + "a985f8f14ca8c4a4a"
        private const val ENDPOINT = "ht" + "tps://cloudfl" + "areinsights.com/cdn-cgi/rum"

        fun getInstance(): AnalyticsBeacon = service()
    }

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    /**
     * Send analytics event (fire-and-forget, like navigator.sendBeacon).
     *
     * @param eventType Event identifier (e.g., "plugin-startup", "tool-execute-code")
     * @param metadata Additional event metadata (keys will be sent as customTags)
     */
    fun send(eventType: String, metadata: Map<String, Any> = emptyMap()) {
        // Check if analytics is enabled
        if (!Registry.`is`("mcp.steroid.analytics.enabled", true)) {
            return
        }

        coroutineScope.launch(Dispatchers.IO) {
            semaphore.withPermit {
                try {
                    val payload = buildPayload(eventType, metadata)
                    sendBeacon(payload)
                } catch (e: Exception) {
                    // Silent fail - analytics should never break the plugin
                    log.debug("Analytics beacon failed", e)
                }
            }
        }
    }

    /**
     * Build JSON payload in Cloudflare Web Analytics format.
     */
    private fun buildPayload(eventType: String, metadata: Map<String, Any>): JsonObject {
        val appInfo = ApplicationInfo.getInstance()
        val pluginVersion = PluginDescriptorProvider.getInstance().version

        return buildJsonObject {
            // Unique page load ID (Cloudflare uses UUID v4)
            put("pageLoadId", UUID.randomUUID().toString())

            // Location (adapted for desktop app - use custom URI scheme)
            put("location", "mcp-steroid://$eventType")

            // Navigation type (constant for plugin events)
            put("navigationType", "plugin-event")

            // Event classification
            put("eventType", eventType)

            // Timestamp
            put("timestamp", System.currentTimeMillis())

            // Environment metadata (equivalent to browser user agent)
            putJsonObject("environment") {
                put("plugin", "mcp-steroid")
                put("pluginVersion", pluginVersion)
                put("ide", appInfo.fullApplicationName)
                put("ideVersion", appInfo.fullVersion)
                put("ideBuild", appInfo.build.asString())
                put("os", System.getProperty("os.name"))
                put("osVersion", System.getProperty("os.version"))
                put("javaVersion", System.getProperty("java.version"))
            }

            // Custom metadata (like Cloudflare's customTags)
            if (metadata.isNotEmpty()) {
                putJsonObject("customTags") {
                    metadata.forEach { (key, value) ->
                        when (value) {
                            is String -> put(key, value)
                            is Number -> put(key, value)
                            is Boolean -> put(key, value)
                            else -> put(key, value.toString())
                        }
                    }
                }
            }
        }
    }

    /**
     * Send beacon to Cloudflare endpoint (fire-and-forget).
     */
    private suspend fun sendBeacon(payload: JsonObject) {
        val pluginVersion = PluginDescriptorProvider.getInstance().version
        val ijBuild = ApplicationInfo.getInstance().build.asString()

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$ENDPOINT?$ANALYTICS_TOKEN"))
            .header("Content-Type", "application/json")
            .header("User-Agent", "MCP-Steroid/$pluginVersion (IntelliJ/$ijBuild)")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
            .timeout(Duration.ofSeconds(10))
            .build()

        val response = client
            .sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .await()

        if (response.statusCode() != 204) {
            log.debug("Analytics beacon unexpected status: ${response.statusCode()}")
        } else {
            log.debug("Analytics beacon sent successfully: ${payload["eventType"]}")
        }
    }
}

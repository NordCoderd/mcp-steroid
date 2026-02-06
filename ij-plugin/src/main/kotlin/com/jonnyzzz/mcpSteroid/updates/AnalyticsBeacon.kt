/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.updates

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.registry.Registry
import com.jonnyzzz.mcpSteroid.PluginDescriptorProvider
import com.posthog.server.PostHog
import com.posthog.server.PostHogCaptureOptions
import com.posthog.server.PostHogConfig
import com.posthog.server.PostHogInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

inline val analyticsBeacon: AnalyticsBeacon get() = service()

/**
 * Minimalistic analytics via PostHog.
 *
 * Tracks only:
 * - IDE build / version / product code
 * - Plugin version
 * - Project ID (random UUID, no real project name)
 * - exec_code call events (success / error)
 *
 * Registry key: mcp.steroid.analytics.enabled (default: true)
 */
@Service(Service.Level.APP)
class AnalyticsBeacon(
    private val coroutineScope: CoroutineScope
) {
    private val log = thisLogger()

    private val posthog: PostHogInterface? by lazy {
        try {
            val config = PostHogConfig
                .builder("phc_GpYAWraVQPRBHjn2NKCZ0PjE2TqGSHICaM6GfRFJm4K")
                .host("https://us.i.posthog.com")
                .build()
            PostHog.with(config)
        } catch (e: Exception) {
            log.debug("PostHog init failed", e)
            null
        }
    }

    fun capture(event: String, properties: Map<String, Any> = emptyMap()) {
        if (!Registry.`is`("mcp.steroid.analytics.enabled", true)) return

        coroutineScope.launch(Dispatchers.IO) {
            try {
                val ph = posthog ?: return@launch
                val appInfo = ApplicationInfo.getInstance()
                val pluginVersion = PluginDescriptorProvider.getInstance().version

                val opts = PostHogCaptureOptions.builder()
                properties.forEach { (k, v) ->
                    when (v) {
                        is String -> opts.property(k, v)
                        is Number -> opts.property(k, v)
                        is Boolean -> opts.property(k, v)
                        else -> opts.property(k, v.toString())
                    }
                }
                opts.property("ide_build", appInfo.build.asString())
                opts.property("ide_version", appInfo.fullVersion)
                opts.property("ide_product", appInfo.build.productCode)
                opts.property("plugin_version", pluginVersion)

                ph.capture(distinctId(), event, opts.build())
            } catch (e: Exception) {
                log.debug("Analytics capture failed", e)
            }
        }
    }

    fun shutdown() {
        try {
            posthog?.close()
        } catch (_: Exception) {
        }
    }

    private fun distinctId(): String {
        val props = PropertiesComponent.getInstance()
        return props.getValue("mcp.steroid.analytics.distinct.id")
            ?: UUID.randomUUID().toString().also { props.setValue("mcp.steroid.analytics.distinct.id", it) }
    }
}

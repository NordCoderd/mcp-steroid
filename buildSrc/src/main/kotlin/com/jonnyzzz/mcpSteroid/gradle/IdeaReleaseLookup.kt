/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.gradle

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

enum class JetBrainsIdeProduct(val code: String) {
    IntelliJIdeaUltimate("IIU"),
    PyCharm("PCP"),
}

enum class IdeaReleaseChannel(val apiValue: String) {
    STABLE("release"),
    EAP("eap"),
}

data class IdeaReleaseDescriptor(
    val version: String,
    val majorVersion: String,
    val build: String,
    val linuxArchiveUrl: String,
    val linuxArmArchiveUrl: String,
) {
    fun archiveUrl(isArmArch: Boolean): String = if (isArmArch) linuxArmArchiveUrl else linuxArchiveUrl
}

interface IdeaReleaseLookup {
    fun latestRelease(product: JetBrainsIdeProduct, channel: IdeaReleaseChannel): IdeaReleaseDescriptor

    fun latestIdeaRelease(channel: IdeaReleaseChannel): IdeaReleaseDescriptor =
        latestRelease(JetBrainsIdeProduct.IntelliJIdeaUltimate, channel)

    fun latestPyCharmRelease(channel: IdeaReleaseChannel): IdeaReleaseDescriptor =
        latestRelease(JetBrainsIdeProduct.PyCharm, channel)
}

class JetBrainsProductsIdeaReleaseLookup(
    private val serviceUrl: String = DEFAULT_SERVICE_URL,
    private val readUrl: (String) -> String = ::readUrlText,
) : IdeaReleaseLookup {
    private val cache = ConcurrentHashMap<Pair<JetBrainsIdeProduct, IdeaReleaseChannel>, IdeaReleaseDescriptor>()

    override fun latestRelease(product: JetBrainsIdeProduct, channel: IdeaReleaseChannel): IdeaReleaseDescriptor =
        cache.computeIfAbsent(product to channel) { (resolvedProduct, resolvedChannel) ->
            loadLatestRelease(resolvedProduct, resolvedChannel)
        }

    internal fun loadLatestRelease(product: JetBrainsIdeProduct, channel: IdeaReleaseChannel): IdeaReleaseDescriptor {
        val endpointUrl = productsUrl(product = product, channel = channel)
        val payload = readUrl(endpointUrl)
        val products = jsonParser.parseToJsonElement(payload).jsonArray
        val matchingProduct = products
            .mapNotNull { it.asObjectOrNull() }
            .firstOrNull { it["code"].asStringOrNull() == product.code }
            ?: throw IllegalStateException("Products response does not contain '${product.code}' entry")

        val releases = matchingProduct["releases"].asArrayOrEmpty()

        val release = releases
            .mapNotNull { it.asObjectOrNull() }
            .firstOrNull { candidate ->
                candidate["type"].asStringOrNull().equals(channel.apiValue, ignoreCase = true) &&
                        !candidate["version"].asStringOrNull().isNullOrBlank() &&
                        !candidate["majorVersion"].asStringOrNull().isNullOrBlank() &&
                        !candidate["build"].asStringOrNull().isNullOrBlank() &&
                        !candidate["downloads"].asObjectOrNull()?.get("linux").asObjectOrNull()?.get("link").asStringOrNull()
                            .isNullOrBlank() &&
                        !candidate["downloads"].asObjectOrNull()?.get("linuxARM64").asObjectOrNull()?.get("link")
                            .asStringOrNull()
                            .isNullOrBlank()
            }
            ?: throw IllegalStateException(
                "Unable to resolve latest '${channel.apiValue}' release for product '${product.code}' from $endpointUrl"
            )

        return IdeaReleaseDescriptor(
            version = release["version"].asStringOrNull()!!.trim(),
            majorVersion = release["majorVersion"].asStringOrNull()!!.trim(),
            build = release["build"].asStringOrNull()!!.trim(),
            linuxArchiveUrl = release["downloads"].asObjectOrNull()!!
                .get("linux").asObjectOrNull()!!
                .get("link").asStringOrNull()!!
                .trim(),
            linuxArmArchiveUrl = release["downloads"].asObjectOrNull()!!
                .get("linuxARM64").asObjectOrNull()!!
                .get("link").asStringOrNull()!!
                .trim(),
        )
    }

    private fun JsonElement?.asObjectOrNull(): JsonObject? = (this as? JsonObject)

    private fun JsonElement?.asArrayOrEmpty(): JsonArray = (this as? JsonArray) ?: JsonArray(emptyList())

    private fun JsonElement?.asStringOrNull(): String? = when (this) {
        is JsonPrimitive -> this.contentOrNull
        else -> null
    }

    private fun productsUrl(product: JetBrainsIdeProduct, channel: IdeaReleaseChannel): String {
        val releaseType = URLEncoder.encode(channel.apiValue, StandardCharsets.UTF_8)
        return "$serviceUrl?code=${product.code}&release.type=$releaseType"
    }

    companion object {
        const val DEFAULT_SERVICE_URL = "https://data.services.jetbrains.com/products"

        private val jsonParser = Json {
            ignoreUnknownKeys = true
        }

        private fun readUrlText(url: String): String {
            val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/json")
            }

            try {
                val statusCode = connection.responseCode
                val responseBody = (if (statusCode in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()

                if (statusCode !in 200..299) {
                    throw IllegalStateException(
                        "Failed to fetch IntelliJ releases from $url. HTTP $statusCode\n$responseBody"
                    )
                }

                return responseBody
            } finally {
                connection.disconnect()
            }
        }
    }
}

object IdeaReleaseService : IdeaReleaseLookup {
    private val delegate = JetBrainsProductsIdeaReleaseLookup()

    override fun latestRelease(product: JetBrainsIdeProduct, channel: IdeaReleaseChannel): IdeaReleaseDescriptor =
        delegate.latestRelease(product, channel)
}

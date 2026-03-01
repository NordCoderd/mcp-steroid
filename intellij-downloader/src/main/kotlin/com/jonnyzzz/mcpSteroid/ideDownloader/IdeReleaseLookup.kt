/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Returns the JetBrains API download key for the given OS and architecture combination.
 *
 * @see <a href="https://data.services.jetbrains.com/products">JetBrains Products API</a>
 */
fun resolveDownloadKey(os: HostOs, architecture: HostArchitecture): String = when (os) {
    HostOs.LINUX -> if (architecture.isArmArch) "linuxARM64" else "linux"
    HostOs.MAC -> if (architecture.isArmArch) "macM1" else "mac"
    HostOs.WINDOWS -> if (architecture.isArmArch) "windowsARM64" else "windows"
}

/**
 * Resolves the download URL for the latest IDE archive from JetBrains products API.
 *
 * @param product the IDE product to look up
 * @param channel the release channel (stable or EAP)
 * @param os the target operating system (default: auto-detected)
 * @param architecture the host architecture for platform-specific archive selection
 * @return the direct download URL for the archive
 */
fun resolveArchiveUrl(
    product: IdeProduct,
    channel: IdeChannel,
    os: HostOs = resolveHostOs(),
    architecture: HostArchitecture = resolveHostArchitecture(),
): String {
    val releaseType = URLEncoder.encode(channel.apiValue, StandardCharsets.UTF_8)
    val url = "https://data.services.jetbrains.com/products?code=${product.jetbrainsProductCode}&release.type=$releaseType"

    println("[IDE-DOWNLOAD] Fetching products info from $url")
    val payload = readUrlText(url)

    val json = Json { ignoreUnknownKeys = true }
    val products = json.parseToJsonElement(payload).jsonArray

    val matchingProduct = products
        .filterIsInstance<JsonObject>()
        .firstOrNull { obj ->
            (obj["code"] as? JsonPrimitive)?.content == product.jetbrainsProductCode
        }
        ?: error("Products response does not contain '${product.jetbrainsProductCode}' entry")

    val releases = (matchingProduct["releases"] as? JsonArray) ?: JsonArray(emptyList())

    val downloadKey = resolveDownloadKey(os, architecture)
    val release = releases
        .filterIsInstance<JsonObject>()
        .firstOrNull { candidate ->
            val type = (candidate["type"] as? JsonPrimitive)?.content
            val version = (candidate["version"] as? JsonPrimitive)?.content
            val build = (candidate["build"] as? JsonPrimitive)?.content
            val downloads = candidate["downloads"] as? JsonObject
            val link = (downloads?.get(downloadKey) as? JsonObject)?.get("link")?.let { (it as? JsonPrimitive)?.content }

            type.equals(channel.apiValue, ignoreCase = true) &&
                    !version.isNullOrBlank() &&
                    !build.isNullOrBlank() &&
                    !link.isNullOrBlank()
        }
        ?: error("Unable to resolve latest '${channel.apiValue}' release for product '${product.jetbrainsProductCode}' (download key '$downloadKey') from $url")

    val downloads = release["downloads"] as? JsonObject
        ?: error("Missing 'downloads' in release")
    val platformDownload = downloads[downloadKey] as? JsonObject
        ?: error("Missing '$downloadKey' in downloads")
    return (platformDownload["link"] as? JsonPrimitive)?.content
        ?: error("Missing 'link' in $downloadKey download")
}

internal fun readUrlText(url: String): String {
    val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15_000
        readTimeout = 15_000
        setRequestProperty("Accept", "application/json")
    }
    try {
        val statusCode = connection.responseCode
        val body = (if (statusCode in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()
            ?.use { it.readText() }
            .orEmpty()
        if (statusCode !in 200..299) {
            error("Failed to fetch from $url. HTTP $statusCode\n$body")
        }
        return body
    } finally {
        connection.disconnect()
    }
}

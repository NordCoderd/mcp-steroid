/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class IdeChannel(val apiValue: String) {
    STABLE("release"),
    EAP("eap"),
}

sealed class IdeDistribution {
    abstract val product: IdeProduct

    data class Latest(
        override val product: IdeProduct = IdeProduct.IntelliJIdea,
        val channel: IdeChannel = IdeChannel.STABLE,
    ) : IdeDistribution()

    data class FromUrl(
        override val product: IdeProduct,
        val url: String,
        val fileName: String? = null,
    ) : IdeDistribution()

    companion object {
        fun fromSystemProperties(): IdeDistribution {
            val productRaw = System.getProperty("test.integration.ide.product", "idea")
            val channelRaw = System.getProperty("test.integration.ide.channel", "stable").trim().lowercase()
            val product = IdeProduct.fromSystemProperty(productRaw)
            val channel = when (channelRaw) {
                "stable", "release" -> IdeChannel.STABLE
                "eap" -> IdeChannel.EAP
                else -> error("Unknown channel '$channelRaw'. Use 'stable' or 'eap'.")
            }
            return Latest(product = product, channel = channel)
        }
    }
}

fun IdeDistribution.resolveAndDownload(): File {
    val downloadDir = File(
        System.getProperty("test.integration.ide.download.dir", "build/ide-download")
    )
    downloadDir.mkdirs()

    val (url, fileName) = resolveUrlAndFileName()
    val destFile = File(downloadDir, fileName)

    if (destFile.exists()) {
        println("[IDE-DOWNLOAD] Using cached archive: $destFile")
        return destFile
    }

    println("[IDE-DOWNLOAD] Downloading $url -> $destFile")
    downloadFile(url, destFile)
    return destFile
}

private fun IdeDistribution.resolveUrlAndFileName(): Pair<String, String> {
    return when (this) {
        is IdeDistribution.FromUrl -> {
            val resolvedName = fileName ?: archiveFileNameFromUrl(url, "${product.id}.tar.gz")
            url to resolvedName
        }
        is IdeDistribution.Latest -> {
            val resolvedUrl = resolveArchiveUrl(product, channel)
            val isArm = isArmArch()
            val fallbackName = if (isArm) "${product.id}-${channel.name.lowercase()}-arm.tar.gz"
                               else "${product.id}-${channel.name.lowercase()}-x86.tar.gz"
            val resolvedName = archiveFileNameFromUrl(resolvedUrl, fallbackName)
            resolvedUrl to resolvedName
        }
    }
}

private fun isArmArch(): Boolean {
    val arch = System.getProperty("os.arch").trim().lowercase()
    return arch in setOf("aarch64", "arm64")
}

private fun resolveArchiveUrl(product: IdeProduct, channel: IdeChannel): String {
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

    val isArm = isArmArch()
    val release = releases
        .filterIsInstance<JsonObject>()
        .firstOrNull { candidate ->
            val type = (candidate["type"] as? JsonPrimitive)?.content
            val version = (candidate["version"] as? JsonPrimitive)?.content
            val build = (candidate["build"] as? JsonPrimitive)?.content
            val downloads = candidate["downloads"] as? JsonObject
            val linuxLink = (downloads?.get("linux") as? JsonObject)?.get("link")?.let { (it as? JsonPrimitive)?.content }
            val linuxArmLink = (downloads?.get("linuxARM64") as? JsonObject)?.get("link")?.let { (it as? JsonPrimitive)?.content }

            type.equals(channel.apiValue, ignoreCase = true) &&
                    !version.isNullOrBlank() &&
                    !build.isNullOrBlank() &&
                    !linuxLink.isNullOrBlank() &&
                    !linuxArmLink.isNullOrBlank()
        }
        ?: error("Unable to resolve latest '${channel.apiValue}' release for product '${product.jetbrainsProductCode}' from $url")

    val linuxKey = if (isArm) "linuxARM64" else "linux"
    val downloads = release["downloads"] as? JsonObject
        ?: error("Missing 'downloads' in release")
    val platformDownload = downloads[linuxKey] as? JsonObject
        ?: error("Missing '$linuxKey' in downloads")
    return (platformDownload["link"] as? JsonPrimitive)?.content
        ?: error("Missing 'link' in $linuxKey download")
}

private fun archiveFileNameFromUrl(url: String, fallbackFileName: String): String {
    val fileName = try { URI(url).path.substringAfterLast('/') } catch (_: Exception) { null }
    return fileName?.takeIf { it.isNotBlank() } ?: fallbackFileName
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

private fun downloadFile(url: String, dest: File) {
    val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 30_000
        readTimeout = 15 * 60_000
        instanceFollowRedirects = true
    }
    try {
        val statusCode = connection.responseCode
        if (statusCode !in 200..299) {
            error("Failed to download $url. HTTP $statusCode")
        }
        val totalBytes = connection.contentLengthLong
        var downloaded = 0L
        var lastPrinted = 0L

        val tempFile = File(dest.parent, "${dest.name}.tmp")
        try {
            connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastPrinted >= 5_000) {
                            val progress = if (totalBytes > 0) " (${downloaded * 100 / totalBytes}%)" else ""
                            println("[IDE-DOWNLOAD] Progress: ${downloaded / 1024 / 1024} MB$progress")
                            lastPrinted = now
                        }
                    }
                }
            }
            tempFile.renameTo(dest)
            println("[IDE-DOWNLOAD] Downloaded ${downloaded / 1024 / 1024} MB to $dest")
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    } finally {
        connection.disconnect()
    }
}

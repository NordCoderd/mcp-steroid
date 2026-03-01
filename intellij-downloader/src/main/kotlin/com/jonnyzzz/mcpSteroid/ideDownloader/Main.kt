/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import java.io.File

/**
 * CLI entry point for downloading IDE archives.
 *
 * Usage:
 *   java -jar intellij-downloader.jar --product idea --channel stable --output-dir /path/to/dir
 *
 * Arguments:
 *   --product     IDE product: idea, pycharm, goland, webstorm, rider, clion (default: idea)
 *   --channel     Release channel: stable, eap (default: stable)
 *   --output-dir  Directory to store downloaded archives (required)
 *   --url         Direct download URL (overrides --product/--channel resolution)
 */
fun main(args: Array<String>) {
    val argsMap = parseArgs(args)
    val outputDir = File(argsMap["--output-dir"] ?: error("--output-dir is required"))
    val url = argsMap["--url"]

    val distribution = if (url != null) {
        val productRaw = argsMap["--product"] ?: "idea"
        val product = IdeProduct.fromString(productRaw)
        IdeDistribution.FromUrl(product = product, url = url)
    } else {
        val productRaw = argsMap["--product"] ?: "idea"
        val channelRaw = argsMap["--channel"] ?: "stable"
        val product = IdeProduct.fromString(productRaw)
        val channel = when (channelRaw.trim().lowercase()) {
            "stable", "release" -> IdeChannel.STABLE
            "eap" -> IdeChannel.EAP
            else -> error("Unknown channel '$channelRaw'. Use 'stable' or 'eap'.")
        }
        IdeDistribution.Latest(product = product, channel = channel)
    }

    val archiveFile = distribution.resolveAndDownload(outputDir)
    println("[IDE-DOWNLOAD] Archive: ${archiveFile.absolutePath}")
}

private fun parseArgs(args: Array<String>): Map<String, String> {
    val result = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val key = args[i]
        require(key.startsWith("--")) { "Expected argument key starting with --, got: $key" }
        require(i + 1 < args.size) { "Missing value for argument: $key" }
        result[key] = args[i + 1]
        i += 2
    }
    return result
}

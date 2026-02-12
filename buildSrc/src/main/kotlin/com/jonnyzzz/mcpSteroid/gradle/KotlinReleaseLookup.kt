/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.gradle

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.net.HttpURLConnection
import java.net.URI

fun interface KotlinReleaseLookup {
    fun latestStableKotlinVersion(): KotlinVersion
}

class GitHubKotlinReleaseLookup(
    private val apiUrl: String = DEFAULT_API_URL,
    private val readUrl: (String) -> String = ::readUrlText,
) : KotlinReleaseLookup {
    override fun latestStableKotlinVersion(): KotlinVersion {
        val payload = readUrl(apiUrl)
        val response = jsonParser.parseToJsonElement(payload).asObjectOrNull()
            ?: throw IllegalStateException("GitHub releases response is not a JSON object")

        val isPreRelease = response["prerelease"].asBooleanOrNull()
        check(isPreRelease != true) {
            "GitHub latest release endpoint returned a prerelease response"
        }

        val tagName = response["tag_name"].asStringOrNull()
            ?.removePrefix("v")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("GitHub response does not contain tag_name")

        return KotlinVersionCompatibility.parseStrictVersion(tagName)
            ?: throw IllegalStateException("Cannot parse Kotlin version from GitHub tag_name='$tagName'")
    }

    private fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonElement?.asStringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun JsonElement?.asBooleanOrNull(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

    companion object {
        const val DEFAULT_API_URL = "https://api.github.com/repos/JetBrains/kotlin/releases/latest"

        private val jsonParser = Json {
            ignoreUnknownKeys = true
        }

        private fun readUrlText(url: String): String {
            val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }

            try {
                val statusCode = connection.responseCode
                val responseBody = (if (statusCode in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()

                if (statusCode !in 200..299) {
                    throw IllegalStateException(
                        "Failed to fetch latest Kotlin release from $url. HTTP $statusCode\n$responseBody"
                    )
                }

                return responseBody
            } finally {
                connection.disconnect()
            }
        }
    }
}

object KotlinReleaseService : KotlinReleaseLookup {
    private val delegate = GitHubKotlinReleaseLookup()

    override fun latestStableKotlinVersion(): KotlinVersion = delegate.latestStableKotlinVersion()
}

/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GitHubKotlinReleaseLookupTest {
    @Test
    fun resolvesLatestStableVersionFromTagName() {
        val requestedUrls = mutableListOf<String>()
        val lookup = GitHubKotlinReleaseLookup(
            apiUrl = "https://example.test/releases/latest",
            readUrl = { url ->
                requestedUrls += url
                """
                {
                  "tag_name": "v2.3.10",
                  "prerelease": false
                }
                """.trimIndent()
            },
        )

        val version = lookup.latestStableKotlinVersion()

        assertEquals(listOf("https://example.test/releases/latest"), requestedUrls)
        assertEquals(KotlinVersion(2, 3, 10), version)
    }

    @Test
    fun failsWhenLatestReleaseIsPrerelease() {
        val lookup = GitHubKotlinReleaseLookup(
            apiUrl = "https://example.test/releases/latest",
            readUrl = {
                """
                {
                  "tag_name": "v2.4.0-RC",
                  "prerelease": true
                }
                """.trimIndent()
            },
        )

        assertFailsWith<IllegalStateException> {
            lookup.latestStableKotlinVersion()
        }
    }

    @Test
    fun failsWhenTagNameIsMissingOrInvalid() {
        val missingTagLookup = GitHubKotlinReleaseLookup(
            apiUrl = "https://example.test/releases/latest",
            readUrl = { """{"prerelease": false}""" },
        )
        assertFailsWith<IllegalStateException> {
            missingTagLookup.latestStableKotlinVersion()
        }

        val invalidTagLookup = GitHubKotlinReleaseLookup(
            apiUrl = "https://example.test/releases/latest",
            readUrl = { """{"tag_name":"v2.4.0-RC","prerelease":false}""" },
        )
        assertFailsWith<IllegalStateException> {
            invalidTagLookup.latestStableKotlinVersion()
        }
    }
}

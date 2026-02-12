/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JetBrainsProductsIdeaReleaseLookupTest {
    @Test
    fun resolvesLatestStableReleaseFromProductsServicePayload() {
        val requestedUrls = mutableListOf<String>()
        val lookup = JetBrainsProductsIdeaReleaseLookup(
            serviceUrl = "https://example.test/products",
            readUrl = { url ->
                requestedUrls += url
                stablePayload
            }
        )

        val stable = lookup.latestIdeaRelease(IdeaReleaseChannel.STABLE)

        assertEquals("https://example.test/products?code=IIU&release.type=release", requestedUrls.single())
        assertEquals("2025.3.2", stable.version)
        assertEquals("2025.3", stable.majorVersion)
        assertEquals("253.30387.90", stable.build)
        assertEquals("https://download.jetbrains.com/idea/idea-2025.3.2.tar.gz", stable.linuxArchiveUrl)
        assertEquals("https://download.jetbrains.com/idea/idea-2025.3.2-aarch64.tar.gz", stable.linuxArmArchiveUrl)
    }

    @Test
    fun resolvesLatestEapReleaseFromProductsServicePayload() {
        val requestedUrls = mutableListOf<String>()
        val lookup = JetBrainsProductsIdeaReleaseLookup(
            serviceUrl = "https://example.test/products",
            readUrl = { url ->
                requestedUrls += url
                eapPayload
            }
        )

        val eap = lookup.latestIdeaRelease(IdeaReleaseChannel.EAP)

        assertEquals("https://example.test/products?code=IIU&release.type=eap", requestedUrls.single())
        assertEquals("2026.1", eap.version)
        assertEquals("2026.1", eap.majorVersion)
        assertEquals("261.20362.25", eap.build)
        assertEquals("https://download.jetbrains.com/idea/idea-261.20362.25.tar.gz", eap.linuxArchiveUrl)
        assertEquals("https://download.jetbrains.com/idea/idea-261.20362.25-aarch64.tar.gz", eap.linuxArmArchiveUrl)
    }

    @Test
    fun cachesResultsPerReleaseChannel() {
        var requestCount = 0
        val lookup = JetBrainsProductsIdeaReleaseLookup(
            serviceUrl = "https://example.test/products",
            readUrl = { url ->
                requestCount += 1
                if (url.contains("release.type=eap")) eapPayload else stablePayload
            }
        )

        val firstStable = lookup.latestIdeaRelease(IdeaReleaseChannel.STABLE)
        val secondStable = lookup.latestIdeaRelease(IdeaReleaseChannel.STABLE)
        val firstEap = lookup.latestIdeaRelease(IdeaReleaseChannel.EAP)
        val secondEap = lookup.latestIdeaRelease(IdeaReleaseChannel.EAP)

        assertEquals(firstStable, secondStable)
        assertEquals(firstEap, secondEap)
        assertEquals(2, requestCount)
    }

    @Test
    fun failsWhenIdeaProductIsMissing() {
        val lookup = JetBrainsProductsIdeaReleaseLookup(
            serviceUrl = "https://example.test/products",
            readUrl = { """[{"code":"OTHER","releases":[]}]""" }
        )

        assertFailsWith<IllegalStateException> {
            lookup.latestIdeaRelease(IdeaReleaseChannel.STABLE)
        }
    }

    companion object {
        private const val stablePayload = """
            [
              {
                "code": "IIU",
                "releases": [
                  {
                    "type": "release",
                    "version": "2025.3.2",
                    "majorVersion": "2025.3",
                    "build": "253.30387.90",
                    "downloads": {
                      "linux": {"link": "https://download.jetbrains.com/idea/idea-2025.3.2.tar.gz"},
                      "linuxARM64": {"link": "https://download.jetbrains.com/idea/idea-2025.3.2-aarch64.tar.gz"}
                    }
                  }
                ]
              }
            ]
        """

        private const val eapPayload = """
            [
              {
                "code": "IIU",
                "releases": [
                  {
                    "type": "eap",
                    "version": "2026.1",
                    "majorVersion": "2026.1",
                    "build": "261.20362.25",
                    "downloads": {
                      "linux": {"link": "https://download.jetbrains.com/idea/idea-261.20362.25.tar.gz"},
                      "linuxARM64": {"link": "https://download.jetbrains.com/idea/idea-261.20362.25-aarch64.tar.gz"}
                    }
                  }
                ]
              }
            ]
        """
    }
}

/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IdeaEapArchiveResolverTest {
    @Test
    fun resolveUsesServiceEapForX86WhenNoOverrides() {
        val lookup = fakeLookup()
        assertEquals(
            ResolvedIdeaArchive(
                url = "https://download.jetbrains.com/idea/idea-261.12345.67.tar.gz",
                fileName = "idea-eap-x86.tar.gz",
            ),
            IdeaEapArchiveResolver.resolve(
                isArmArch = false,
                overrideUrl = null,
                overrideBuild = null,
                releaseLookup = lookup,
            )
        )
        assertEquals(listOf(IdeaReleaseChannel.EAP), lookup.requestedChannels)
    }

    @Test
    fun resolveUsesServiceEapForArmWhenNoOverrides() {
        val lookup = fakeLookup()
        assertEquals(
            ResolvedIdeaArchive(
                url = "https://download.jetbrains.com/idea/idea-261.12345.67-aarch64.tar.gz",
                fileName = "idea-eap-arm.tar.gz",
            ),
            IdeaEapArchiveResolver.resolve(
                isArmArch = true,
                overrideUrl = null,
                overrideBuild = null,
                releaseLookup = lookup,
            )
        )
        assertEquals(listOf(IdeaReleaseChannel.EAP), lookup.requestedChannels)
    }

    @Test
    fun resolveUsesBuildOverrideWhenUrlOverrideIsMissing() {
        val lookup = fakeLookup()
        assertEquals(
            ResolvedIdeaArchive(
                url = "https://download.jetbrains.com/idea/idea-253.12345.7.tar.gz",
                fileName = "idea-eap-x86.tar.gz",
            ),
            IdeaEapArchiveResolver.resolve(
                isArmArch = false,
                overrideUrl = null,
                overrideBuild = " 253.12345.7 ",
                releaseLookup = lookup,
            )
        )
        assertEquals(emptyList(), lookup.requestedChannels)
    }

    @Test
    fun resolvePrefersUrlOverrideOverBuildOverride() {
        val lookup = fakeLookup()
        assertEquals(
            ResolvedIdeaArchive(
                url = "https://example.test/custom-eap.tar.gz",
                fileName = "idea-eap-arm.tar.gz",
            ),
            IdeaEapArchiveResolver.resolve(
                isArmArch = true,
                overrideUrl = " https://example.test/custom-eap.tar.gz ",
                overrideBuild = "253.12345.7",
                releaseLookup = lookup,
            )
        )
        assertEquals(emptyList(), lookup.requestedChannels)
    }

    @Test
    fun archiveUrlForBuildRejectsBlankBuild() {
        assertFailsWith<IllegalArgumentException> {
            IdeaEapArchiveResolver.archiveUrlForBuild(
                build = "   ",
                isArmArch = false,
            )
        }
    }

    @Test
    fun resolveStableUsesServiceReleaseForX86WhenNoOverrides() {
        val lookup = fakeLookup()
        assertEquals(
            ResolvedIdeaArchive(
                url = "https://download.jetbrains.com/idea/idea-2025.3.2.tar.gz",
                fileName = "idea-x86.tar.gz",
            ),
            IdeaEapArchiveResolver.resolveStable(
                isArmArch = false,
                overrideUrl = null,
                overrideVersion = null,
                releaseLookup = lookup,
            )
        )
        assertEquals(listOf(IdeaReleaseChannel.STABLE), lookup.requestedChannels)
    }

    @Test
    fun resolveStableUsesServiceReleaseForArmWhenNoOverrides() {
        val lookup = fakeLookup()
        assertEquals(
            ResolvedIdeaArchive(
                url = "https://download.jetbrains.com/idea/idea-2025.3.2-aarch64.tar.gz",
                fileName = "idea-arm.tar.gz",
            ),
            IdeaEapArchiveResolver.resolveStable(
                isArmArch = true,
                overrideUrl = null,
                overrideVersion = null,
                releaseLookup = lookup,
            )
        )
        assertEquals(listOf(IdeaReleaseChannel.STABLE), lookup.requestedChannels)
    }

    @Test
    fun resolveStableUsesVersionOverrideWhenUrlOverrideIsMissing() {
        val lookup = fakeLookup()
        assertEquals(
            ResolvedIdeaArchive(
                url = "https://download.jetbrains.com/idea/idea-2026.1.1.tar.gz",
                fileName = "idea-x86.tar.gz",
            ),
            IdeaEapArchiveResolver.resolveStable(
                isArmArch = false,
                overrideUrl = null,
                overrideVersion = " 2026.1.1 ",
                releaseLookup = lookup,
            )
        )
        assertEquals(emptyList(), lookup.requestedChannels)
    }

    @Test
    fun resolveStablePrefersUrlOverrideOverVersionOverride() {
        val lookup = fakeLookup()
        assertEquals(
            ResolvedIdeaArchive(
                url = "https://example.test/custom-stable.tar.gz",
                fileName = "idea-arm.tar.gz",
            ),
            IdeaEapArchiveResolver.resolveStable(
                isArmArch = true,
                overrideUrl = " https://example.test/custom-stable.tar.gz ",
                overrideVersion = "2026.1.1",
                releaseLookup = lookup,
            )
        )
        assertEquals(emptyList(), lookup.requestedChannels)
    }

    @Test
    fun archiveUrlForVersionRejectsBlankVersion() {
        assertFailsWith<IllegalArgumentException> {
            IdeaEapArchiveResolver.archiveUrlForVersion(
                version = "   ",
                isArmArch = false,
            )
        }
    }

    private fun fakeLookup(): FakeIdeaReleaseLookup = FakeIdeaReleaseLookup(
        stable = IdeaReleaseDescriptor(
            version = "2025.3.2",
            majorVersion = "2025.3",
            build = "253.30387.90",
            linuxArchiveUrl = "https://download.jetbrains.com/idea/idea-2025.3.2.tar.gz",
            linuxArmArchiveUrl = "https://download.jetbrains.com/idea/idea-2025.3.2-aarch64.tar.gz",
        ),
        eap = IdeaReleaseDescriptor(
            version = "2026.1",
            majorVersion = "2026.1",
            build = "261.12345.67",
            linuxArchiveUrl = "https://download.jetbrains.com/idea/idea-261.12345.67.tar.gz",
            linuxArmArchiveUrl = "https://download.jetbrains.com/idea/idea-261.12345.67-aarch64.tar.gz",
        )
    )

    private class FakeIdeaReleaseLookup(
        private val stable: IdeaReleaseDescriptor,
        private val eap: IdeaReleaseDescriptor,
    ) : IdeaReleaseLookup {
        val requestedChannels = mutableListOf<IdeaReleaseChannel>()

        override fun latestRelease(product: JetBrainsIdeProduct, channel: IdeaReleaseChannel): IdeaReleaseDescriptor {
            requestedChannels += channel
            return when (channel) {
                IdeaReleaseChannel.STABLE -> stable
                IdeaReleaseChannel.EAP -> eap
            }
        }
    }
}

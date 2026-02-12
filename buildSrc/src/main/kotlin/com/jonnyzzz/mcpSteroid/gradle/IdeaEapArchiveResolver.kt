/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.gradle

data class ResolvedIdeaArchive(
    val url: String,
    val fileName: String,
)

object IdeaEapArchiveResolver {
    fun resolve(
        isArmArch: Boolean,
        overrideUrl: String?,
        overrideBuild: String?,
        releaseLookup: IdeaReleaseLookup = IdeaReleaseService,
    ): ResolvedIdeaArchive = resolveEap(
        isArmArch = isArmArch,
        overrideUrl = overrideUrl,
        overrideBuild = overrideBuild,
        releaseLookup = releaseLookup,
    )

    fun resolveEap(
        isArmArch: Boolean,
        overrideUrl: String?,
        overrideBuild: String?,
        releaseLookup: IdeaReleaseLookup = IdeaReleaseService,
    ): ResolvedIdeaArchive {
        val fileName = if (isArmArch) ARM_EAP_ARCHIVE_FILE_NAME else X86_EAP_ARCHIVE_FILE_NAME
        val resolvedUrl = normalize(overrideUrl)
            ?: normalize(overrideBuild)?.let { build ->
                archiveUrlForBuild(
                    build = build,
                    isArmArch = isArmArch,
                )
            }
            ?: releaseLookup.latestIdeaRelease(IdeaReleaseChannel.EAP).archiveUrl(isArmArch)

        return ResolvedIdeaArchive(
            url = resolvedUrl,
            fileName = fileName,
        )
    }

    fun resolveStable(
        isArmArch: Boolean,
        overrideUrl: String?,
        overrideVersion: String?,
        releaseLookup: IdeaReleaseLookup = IdeaReleaseService,
    ): ResolvedIdeaArchive {
        val fileName = if (isArmArch) ARM_STABLE_ARCHIVE_FILE_NAME else X86_STABLE_ARCHIVE_FILE_NAME
        val resolvedUrl = normalize(overrideUrl)
            ?: normalize(overrideVersion)?.let { version ->
                archiveUrlForVersion(
                    version = version,
                    isArmArch = isArmArch,
                )
            }
            ?: releaseLookup.latestIdeaRelease(IdeaReleaseChannel.STABLE).archiveUrl(isArmArch)

        return ResolvedIdeaArchive(
            url = resolvedUrl,
            fileName = fileName,
        )
    }

    fun archiveUrlForBuild(build: String, isArmArch: Boolean): String {
        val normalizedBuild = build.trim()
        require(normalizedBuild.isNotEmpty()) { "IDEA EAP build must not be blank" }

        val suffix = if (isArmArch) "-aarch64.tar.gz" else ".tar.gz"
        return "https://download.jetbrains.com/idea/idea-$normalizedBuild$suffix"
    }

    fun archiveUrlForVersion(version: String, isArmArch: Boolean): String {
        val normalizedVersion = version.trim()
        require(normalizedVersion.isNotEmpty()) { "IDEA stable version must not be blank" }

        val suffix = if (isArmArch) "-aarch64.tar.gz" else ".tar.gz"
        return "https://download.jetbrains.com/idea/idea-$normalizedVersion$suffix"
    }

    private fun normalize(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    private const val X86_EAP_ARCHIVE_FILE_NAME = "idea-eap-x86.tar.gz"
    private const val ARM_EAP_ARCHIVE_FILE_NAME = "idea-eap-arm.tar.gz"
    private const val X86_STABLE_ARCHIVE_FILE_NAME = "idea-x86.tar.gz"
    private const val ARM_STABLE_ARCHIVE_FILE_NAME = "idea-arm.tar.gz"
}

/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import org.junit.Assert.assertTrue
import org.junit.Test

class IdeReleaseLookupTest {

    @Test
    fun `resolves IDEA stable archive URL for Linux`() {
        val url = resolveArchiveUrl(IdeProduct.IntelliJIdea, IdeChannel.STABLE, os = HostOs.LINUX)
        assertTrue("Expected .tar.gz URL, got: $url", url.endsWith(".tar.gz"))
        assertTrue("Expected download URL, got: $url", url.contains("download"))
    }

    @Test
    fun `resolves IDEA EAP archive URL for Linux`() {
        val url = resolveArchiveUrl(IdeProduct.IntelliJIdea, IdeChannel.EAP, os = HostOs.LINUX)
        assertTrue("Expected .tar.gz URL, got: $url", url.endsWith(".tar.gz"))
    }

    @Test
    fun `resolves IDEA stable archive URL for Mac`() {
        val url = resolveArchiveUrl(IdeProduct.IntelliJIdea, IdeChannel.STABLE, os = HostOs.MAC)
        assertTrue("Expected .dmg URL, got: $url", url.endsWith(".dmg"))
    }

    @Test
    fun `resolves IDEA stable archive URL for Windows`() {
        val url = resolveArchiveUrl(IdeProduct.IntelliJIdea, IdeChannel.STABLE, os = HostOs.WINDOWS)
        assertTrue("Expected .exe URL, got: $url", url.endsWith(".exe"))
    }

    @Test
    fun `resolves Rider stable archive URL for Linux`() {
        val url = resolveArchiveUrl(IdeProduct.Rider, IdeChannel.STABLE, os = HostOs.LINUX)
        assertTrue("Expected .tar.gz URL, got: $url", url.endsWith(".tar.gz"))
        assertTrue("Expected download URL, got: $url", url.contains("download"))
    }

    @Test
    fun `resolveDownloadKey maps correctly`() {
        assertTrue(resolveDownloadKey(HostOs.LINUX, HostArchitecture.X86_64) == "linux")
        assertTrue(resolveDownloadKey(HostOs.LINUX, HostArchitecture.ARM64) == "linuxARM64")
        assertTrue(resolveDownloadKey(HostOs.MAC, HostArchitecture.X86_64) == "mac")
        assertTrue(resolveDownloadKey(HostOs.MAC, HostArchitecture.ARM64) == "macM1")
        assertTrue(resolveDownloadKey(HostOs.WINDOWS, HostArchitecture.X86_64) == "windows")
        assertTrue(resolveDownloadKey(HostOs.WINDOWS, HostArchitecture.ARM64) == "windowsARM64")
    }

    @Test
    fun `IdeProduct fromString maps correctly`() {
        assertTrue(IdeProduct.fromString("idea") == IdeProduct.IntelliJIdea)
        assertTrue(IdeProduct.fromString("pycharm") == IdeProduct.PyCharm)
        assertTrue(IdeProduct.fromString("goland") == IdeProduct.GoLand)
        assertTrue(IdeProduct.fromString("webstorm") == IdeProduct.WebStorm)
        assertTrue(IdeProduct.fromString("rider") == IdeProduct.Rider)
        assertTrue(IdeProduct.fromString("clion") == IdeProduct.CLion)
    }

    @Test
    fun `HostArchitecture resolves correctly`() {
        val arm = resolveHostArchitecture("aarch64")
        assertTrue(arm == HostArchitecture.ARM64)
        assertTrue(arm.isArmArch)

        val x86 = resolveHostArchitecture("x86_64")
        assertTrue(x86 == HostArchitecture.X86_64)
        assertTrue(!x86.isArmArch)
    }

    @Test
    fun `HostOs resolves correctly`() {
        assertTrue(resolveHostOs("Linux") == HostOs.LINUX)
        assertTrue(resolveHostOs("Mac OS X") == HostOs.MAC)
        assertTrue(resolveHostOs("Darwin") == HostOs.MAC)
        assertTrue(resolveHostOs("Windows 10") == HostOs.WINDOWS)
    }
}

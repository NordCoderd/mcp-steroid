/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import org.junit.Assert.assertTrue
import org.junit.Test

class IdeReleaseLookupTest {

    @Test
    fun `resolves IDEA stable archive URL`() {
        val url = resolveArchiveUrl(IdeProduct.IntelliJIdea, IdeChannel.STABLE)
        assertTrue("Expected .tar.gz URL, got: $url", url.endsWith(".tar.gz"))
        assertTrue("Expected download URL, got: $url", url.contains("download"))
    }

    @Test
    fun `resolves IDEA EAP archive URL`() {
        val url = resolveArchiveUrl(IdeProduct.IntelliJIdea, IdeChannel.EAP)
        assertTrue("Expected .tar.gz URL, got: $url", url.endsWith(".tar.gz"))
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
}

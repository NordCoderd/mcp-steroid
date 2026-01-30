/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp

import com.intellij.testFramework.UsefulTestCase
import com.intellij.testFramework.common.timeoutRunBlocking
import com.jonnyzzz.intellij.mcp.mcp.*
import kotlin.time.Duration.Companion.seconds

/**
 * Test suite for MCP Roots capability (2025-11-25 spec).
 */
class McpRootsServiceTest : UsefulTestCase() {

    fun testSupportsRootsReturnsFalseWhenNotDeclared() {
        val session = McpSession()
        session.markInitialized(
            ClientInfo("test", "1.0"),
            ClientCapabilities() // No roots capability
        )

        assertFalse(session.supportsRoots())
        assertFalse(session.supportsRootsListChanged())
    }

    fun testSupportsRootsReturnsTrueWhenDeclared() {
        val session = McpSession()
        session.markInitialized(
            ClientInfo("test", "1.0"),
            ClientCapabilities(
                roots = RootsCapability(listChanged = false)
            )
        )

        assertTrue(session.supportsRoots())
        assertFalse(session.supportsRootsListChanged())
    }

    fun testSupportsRootsListChangedReturnsTrueWhenDeclared() {
        val session = McpSession()
        session.markInitialized(
            ClientInfo("test", "1.0"),
            ClientCapabilities(
                roots = RootsCapability(listChanged = true)
            )
        )

        assertTrue(session.supportsRoots())
        assertTrue(session.supportsRootsListChanged())
    }

    fun testGetRootsReturnsNullWhenClientDoesNotSupportRoots(): Unit = timeoutRunBlocking(10.seconds) {
        val session = McpSession()
        session.markInitialized(
            ClientInfo("test", "1.0"),
            ClientCapabilities() // No roots
        )

        val service = McpRootsService()
        val roots = service.getRoots(session)

        assertNull(roots)
        assertEquals(0, service.getCacheSize())
    }

    fun testHandleRootsListChangedClearsCache() {
        val service = McpRootsService()
        val session = McpSession()
        session.markInitialized(
            ClientInfo("test", "1.0"),
            ClientCapabilities(
                roots = RootsCapability(listChanged = true)
            )
        )

        // Clear cache (which should be empty anyway)
        service.handleRootsListChanged(session)
        assertFalse(service.hasCachedRoots(session.id))
    }

    fun testClearCacheRemovesSessionRoots() {
        val service = McpRootsService()
        val session = McpSession()

        // Clear cache for non-existent session (should not throw)
        service.clearCache(session.id)
        assertFalse(service.hasCachedRoots(session.id))
    }

    fun testClearAllCachesRemovesAllRoots() {
        val service = McpRootsService()

        service.clearAllCaches()
        assertEquals(0, service.getCacheSize())
    }

    fun testRootDataClassSerialization() {
        val root = Root(
            uri = "file:///home/user/project",
            name = "My Project"
        )

        val json = McpJson.encodeToJsonElement(Root.serializer(), root)
        val deserialized = McpJson.decodeFromJsonElement(Root.serializer(), json)

        assertEquals(root, deserialized)
    }

    fun testRootWithNullName() {
        val root = Root(
            uri = "file:///home/user/project",
            name = null
        )

        val json = McpJson.encodeToJsonElement(Root.serializer(), root)
        val deserialized = McpJson.decodeFromJsonElement(Root.serializer(), json)

        assertEquals(root, deserialized)
    }

    fun testRootsListResultSerialization() {
        val result = RootsListResult(
            roots = listOf(
                Root("file:///project1", "Project 1"),
                Root("file:///project2", "Project 2")
            )
        )

        val json = McpJson.encodeToJsonElement(RootsListResult.serializer(), result)
        val deserialized = McpJson.decodeFromJsonElement(RootsListResult.serializer(), json)

        assertEquals(result, deserialized)
        assertEquals(2, deserialized.roots.size)
    }

    fun testRootsListResultWithEmptyList() {
        val result = RootsListResult(roots = emptyList())

        val json = McpJson.encodeToJsonElement(RootsListResult.serializer(), result)
        val deserialized = McpJson.decodeFromJsonElement(RootsListResult.serializer(), json)

        assertEquals(result, deserialized)
        assertTrue(deserialized.roots.isEmpty())
    }
}


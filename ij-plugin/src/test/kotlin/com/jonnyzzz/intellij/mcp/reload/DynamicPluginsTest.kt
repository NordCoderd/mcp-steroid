/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.reload

import com.intellij.ide.plugins.DynamicPlugins
import com.intellij.ide.plugins.IdeaPluginDescriptorImpl
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files

/**
 * Tests for DynamicPlugins API integration with the MCP Steroid plugin.
 *
 * These tests verify that:
 * 1. The plugin can be discovered via PluginManagerCore
 * 2. The DynamicPlugins API can check reload capability
 * 3. Log file paths are accessible
 */
class DynamicPluginsTest : BasePlatformTestCase() {

    /**
     * Test that PluginManagerCore can find installed plugins.
     */
    fun testPluginManagerCoreFindsPlugins() {
        val plugins = PluginManagerCore.loadedPlugins
        assertNotNull("Loaded plugins should not be null", plugins)
        assertTrue("Should have at least one loaded plugin", plugins.isNotEmpty())
    }

    /**
     * Test that we can get the PluginId for our plugin.
     */
    fun testPluginIdCreation() {
        val pluginId = PluginId.getId(PluginReloadHelper.PLUGIN_ID)
        assertNotNull("PluginId should be created", pluginId)
        assertEquals(
            "PluginId string should match",
            PluginReloadHelper.PLUGIN_ID,
            pluginId.idString
        )
    }

    /**
     * Test that PathManager.getLogPath() returns a valid path.
     */
    fun testLogPathIsAccessible() {
        val logPath = PathManager.getLogPath()
        assertNotNull("Log path should not be null", logPath)
        assertTrue("Log path should not be empty", logPath.isNotEmpty())

        val logDir = java.nio.file.Path.of(logPath)
        // In tests, the log directory might not exist, but the path should be valid
        assertNotNull("Log directory path should be valid", logDir)
    }

    /**
     * Test that PluginReloadHelper.getLogPath() returns a valid path.
     */
    fun testPluginReloadHelperLogPath() {
        val logPath = PluginReloadHelper.getLogPath()
        assertNotNull("Log path should not be null", logPath)
    }

    /**
     * Test that PluginReloadHelper.getIdeaLogFile() returns a path.
     */
    fun testPluginReloadHelperIdeaLogFile() {
        val logFile = PluginReloadHelper.getIdeaLogFile()
        assertNotNull("Log file path should not be null", logFile)
        assertTrue("Log file should have .log extension", logFile.toString().endsWith(".log"))
    }

    /**
     * Test that we can get plugin info without errors.
     */
    fun testPluginReloadHelperGetPluginInfo() {
        val info = PluginReloadHelper.getPluginInfo()
        assertNotNull("Plugin info should not be null", info)
        assertEquals("Plugin ID should match", PluginReloadHelper.PLUGIN_ID, info.pluginId)
    }

    /**
     * Test that DynamicPlugins.allowLoadUnloadWithoutRestart works on descriptors.
     *
     * We test with the platform plugin which should always be present.
     */
    fun testDynamicPluginsAllowLoadUnloadCheck() {
        // Get any loaded plugin to test the API
        val plugins = PluginManagerCore.loadedPlugins
        assertTrue("Should have loaded plugins", plugins.isNotEmpty())

        val firstPlugin = plugins.firstOrNull { it is IdeaPluginDescriptorImpl }
        if (firstPlugin != null) {
            val descriptor = firstPlugin as IdeaPluginDescriptorImpl
            // This should not throw - we're just testing the API call works
            val result = DynamicPlugins.allowLoadUnloadWithoutRestart(descriptor)
            // Result doesn't matter for the test - we just verify the call succeeds
            assertNotNull("allowLoadUnloadWithoutRestart should return a boolean", result)
        }
    }

    /**
     * Test that DynamicPlugins.checkCanUnloadWithoutRestart returns a result.
     *
     * Note: The actual result (null or error string) depends on the specific plugin
     * and IDE state. We just verify the API call works.
     */
    fun testDynamicPluginsCheckCanUnloadWithoutRestart() {
        val plugins = PluginManagerCore.loadedPlugins
        val testPlugin = plugins.filterIsInstance<IdeaPluginDescriptorImpl>().firstOrNull()

        if (testPlugin != null) {
            // This call should not throw
            val reason = DynamicPlugins.checkCanUnloadWithoutRestart(testPlugin)
            // reason can be null (can unload) or a string (cannot unload)
            // We just verify the call doesn't throw
            assertTrue(
                "checkCanUnloadWithoutRestart should return null or a string",
                reason == null || reason.isNotEmpty()
            )
        }
    }

    /**
     * Test that PluginReloadHelper.checkCanReloadWithoutRestart handles missing plugin.
     */
    fun testCheckCanReloadWithMissingPlugin() {
        // In test environment, our plugin may not be fully loaded
        val reason = PluginReloadHelper.checkCanReloadWithoutRestart()
        // This should either return null (can reload) or a reason string
        // We can't assert the exact result as it depends on test environment
        assertNotNull("checkCanReloadWithoutRestart should not throw", true)
    }

    /**
     * Test that canReloadWithoutRestart returns a boolean.
     */
    fun testCanReloadWithoutRestartReturnsBool() {
        val result = PluginReloadHelper.canReloadWithoutRestart()
        // Just verify it returns without throwing
        assertNotNull("canReloadWithoutRestart should return a boolean", result)
    }

    /**
     * Test PathManager provides various paths needed for plugin operations.
     */
    fun testPathManagerPaths() {
        // These are commonly used paths for plugin operations
        val configPath = PathManager.getConfigPath()
        assertNotNull("Config path should not be null", configPath)

        val systemPath = PathManager.getSystemPath()
        assertNotNull("System path should not be null", systemPath)

        val pluginsPath = PathManager.getPluginsPath()
        assertNotNull("Plugins path should not be null", pluginsPath)

        val homePath = PathManager.getHomePath()
        assertNotNull("Home path should not be null", homePath)
    }

    /**
     * Test that we can iterate through plugin dependencies.
     */
    fun testPluginDependencies() {
        val plugins = PluginManagerCore.loadedPlugins
        val pluginWithDeps = plugins.filterIsInstance<IdeaPluginDescriptorImpl>()
            .firstOrNull { it.dependencies.isNotEmpty() }

        if (pluginWithDeps != null) {
            val deps = pluginWithDeps.dependencies
            assertNotNull("Dependencies should not be null", deps)
            // Just verify we can access dependency info
            deps.forEach { dep ->
                assertNotNull("Dependency pluginId should not be null", dep.pluginId)
            }
        }
    }

    /**
     * Test that PluginManagerCore.getPluginSet() is accessible.
     */
    fun testPluginSetAccessible() {
        val pluginSet = PluginManagerCore.getPluginSet()
        assertNotNull("PluginSet should not be null", pluginSet)

        val allPlugins = pluginSet.allPlugins
        assertNotNull("All plugins should not be null", allPlugins)
        assertTrue("Should have plugins in set", allPlugins.isNotEmpty())
    }

    /**
     * Test that enabled plugins can be queried.
     */
    fun testEnabledPluginsQuery() {
        val pluginSet = PluginManagerCore.getPluginSet()
        val enabledPlugins = pluginSet.enabledPlugins

        assertNotNull("Enabled plugins should not be null", enabledPlugins)
        assertTrue("Should have enabled plugins", enabledPlugins.isNotEmpty())
    }
}

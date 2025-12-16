/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.reload

import com.intellij.ide.plugins.DynamicPlugins
import com.intellij.ide.plugins.IdeaPluginDescriptorImpl
import com.intellij.ide.plugins.PluginMainDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import java.nio.file.Path

/**
 * Helper for dynamic plugin reload operations.
 *
 * This class provides utilities for checking if the MCP Steroid plugin
 * can be dynamically reloaded and for performing the reload operation.
 *
 * ## Important Notes
 *
 * Plugin reload is complex because the code that initiates the reload
 * is running in the same classloader that needs to be unloaded. This
 * helper provides safe methods for checking reload capability and
 * getting plugin information.
 *
 * For actual reload, use a separate classloader approach or leverage
 * the DynamicPlugins API from external code (e.g., another plugin or
 * the IDE itself).
 */
object PluginReloadHelper {

    private val LOG = Logger.getInstance(PluginReloadHelper::class.java)

    const val PLUGIN_ID = "com.jonnyzzz.intellij.mcp-steroid"

    /**
     * Returns the PluginId for the MCP Steroid plugin.
     */
    fun getPluginId(): PluginId = PluginId.getId(PLUGIN_ID)

    /**
     * Returns the plugin descriptor for MCP Steroid if installed.
     */
    fun getPluginDescriptor(): IdeaPluginDescriptorImpl? {
        return PluginManagerCore.getPlugin(getPluginId()) as? IdeaPluginDescriptorImpl
    }

    /**
     * Checks if the plugin can be dynamically unloaded and reloaded without restart.
     *
     * @return null if the plugin can be reloaded, or a reason string if it cannot
     */
    fun checkCanReloadWithoutRestart(): String? {
        val descriptor = getPluginDescriptor()
        if (descriptor == null) {
            return "Plugin not found: $PLUGIN_ID"
        }
        return DynamicPlugins.checkCanUnloadWithoutRestart(descriptor)
    }

    /**
     * Returns true if the plugin can be dynamically reloaded without IDE restart.
     */
    fun canReloadWithoutRestart(): Boolean {
        return checkCanReloadWithoutRestart() == null
    }

    /**
     * Returns the plugin installation path if available.
     */
    fun getPluginPath(): Path? {
        return getPluginDescriptor()?.pluginPath
    }

    /**
     * Returns the IDE log directory path.
     * This is useful for monitoring reload operations.
     */
    fun getLogPath(): Path {
        return Path.of(PathManager.getLogPath())
    }

    /**
     * Returns the path to the main idea.log file.
     */
    fun getIdeaLogFile(): Path {
        return getLogPath().resolve("idea.log")
    }

    /**
     * Returns information about the current plugin state.
     */
    fun getPluginInfo(): PluginInfo {
        val descriptor = getPluginDescriptor()
        val pluginId = getPluginId()
        return PluginInfo(
            pluginId = PLUGIN_ID,
            isInstalled = descriptor != null,
            isEnabled = PluginManagerCore.getPluginSet().isPluginEnabled(pluginId),
            version = descriptor?.version,
            pluginPath = descriptor?.pluginPath?.toString(),
            canReloadWithoutRestart = canReloadWithoutRestart(),
            reloadBlockingReason = checkCanReloadWithoutRestart()
        )
    }

    /**
     * Attempts to unload the plugin. Returns true if successful.
     *
     * WARNING: This method should NOT be called from within the plugin itself
     * as it will unload the classloader that's executing this code.
     * Use this method only from external code or tests.
     */
    fun unloadPlugin(): Boolean {
        val descriptor = getPluginDescriptor()
        if (descriptor == null) {
            LOG.warn("Cannot unload: plugin not found")
            return false
        }

        if (descriptor !is PluginMainDescriptor) {
            LOG.warn("Cannot unload: unexpected descriptor type")
            return false
        }

        val canUnload = DynamicPlugins.checkCanUnloadWithoutRestart(descriptor)
        if (canUnload != null) {
            LOG.warn("Cannot unload plugin without restart: $canUnload")
            return false
        }

        LOG.info("Attempting to unload plugin: $PLUGIN_ID")
        return DynamicPlugins.unloadPlugin(descriptor, DynamicPlugins.UnloadPluginOptions(disable = false))
    }

    /**
     * Attempts to load the plugin from its installation path.
     * This is used after updating plugin files on disk.
     *
     * WARNING: Similar to unloadPlugin, this should be called from external code.
     */
    fun loadPlugin(): Boolean {
        val descriptor = getPluginDescriptor()
        if (descriptor == null) {
            LOG.warn("Cannot load: plugin descriptor not found")
            return false
        }

        if (descriptor !is PluginMainDescriptor) {
            LOG.warn("Cannot load: unexpected descriptor type")
            return false
        }

        LOG.info("Attempting to load plugin: $PLUGIN_ID")
        return DynamicPlugins.loadPlugin(descriptor)
    }

    data class PluginInfo(
        val pluginId: String,
        val isInstalled: Boolean,
        val isEnabled: Boolean,
        val version: String?,
        val pluginPath: String?,
        val canReloadWithoutRestart: Boolean,
        val reloadBlockingReason: String?
    )
}

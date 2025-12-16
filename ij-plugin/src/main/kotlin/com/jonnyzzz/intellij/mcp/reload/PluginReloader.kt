/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.reload

import com.intellij.ide.plugins.DynamicPlugins
import com.intellij.ide.plugins.IdeaPluginDescriptorImpl
import com.intellij.ide.plugins.PluginMainDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

/**
 * Plugin reloader that executes reload logic from a separate classloader.
 *
 * The challenge with plugin reload is that the code initiating the reload
 * is loaded by the plugin's classloader, which needs to be unloaded during reload.
 *
 * This implementation uses reflection to call DynamicPlugins API through
 * the Application classloader, avoiding the need to generate bytecode.
 *
 * ## How it works:
 * 1. Get the DynamicPlugins class from the platform classloader
 * 2. Use reflection to call unloadPlugin/loadPlugin methods
 * 3. Since reflection resolves classes at runtime, this avoids classloader issues
 *
 * ## Alternative approach (bytecode):
 * For more complex scenarios, bytecode can be generated and loaded via a custom
 * classloader. See PluginReloadBytecode for that approach.
 */
object PluginReloader {

    private val LOG = Logger.getInstance(PluginReloader::class.java)

    /**
     * Schedules a plugin reload to happen after the current execution completes.
     *
     * This is safer than immediate reload because it allows the current script
     * execution to complete before the plugin is unloaded.
     */
    fun scheduleReload(pluginId: String = PluginReloadHelper.PLUGIN_ID): ReloadResult {
        LOG.info("Scheduling reload for plugin: $pluginId")

        // Check if plugin can be reloaded
        val descriptor = PluginManagerCore.getPlugin(PluginId.getId(pluginId))
        if (descriptor == null) {
            return ReloadResult.failure("Plugin not found: $pluginId")
        }

        if (descriptor !is IdeaPluginDescriptorImpl) {
            return ReloadResult.failure("Unexpected descriptor type: ${descriptor.javaClass}")
        }

        val canUnload = DynamicPlugins.checkCanUnloadWithoutRestart(descriptor)
        if (canUnload != null) {
            return ReloadResult.failure("Cannot reload without restart: $canUnload")
        }

        // Schedule the reload using invokeLater to execute outside current call stack
        ApplicationManager.getApplication().invokeLater {
            performReload(pluginId)
        }

        return ReloadResult.scheduled("Plugin reload scheduled. Check IDE log for results.")
    }

    /**
     * Performs the actual plugin reload.
     *
     * WARNING: This should be called from invokeLater or similar deferred execution
     * to ensure the calling code has completed.
     */
    private fun performReload(pluginId: String) {
        val logFile = Path.of(PathManager.getLogPath()).resolve("idea.log")
        val startMarker = "=== MCP Steroid Plugin Reload Start: ${System.currentTimeMillis()} ==="
        LOG.info(startMarker)

        try {
            val id = PluginId.getId(pluginId)
            val descriptor = PluginManagerCore.getPlugin(id) as? PluginMainDescriptor

            if (descriptor == null) {
                LOG.error("Plugin descriptor not found for reload: $pluginId")
                return
            }

            val pluginPath = descriptor.pluginPath
            LOG.info("Unloading plugin from: $pluginPath")

            // Unload the plugin
            val unloadResult = DynamicPlugins.unloadPlugin(
                descriptor,
                DynamicPlugins.UnloadPluginOptions(
                    disable = false,
                    save = true,
                    waitForClassloaderUnload = true
                )
            )

            if (!unloadResult) {
                LOG.error("Failed to unload plugin: $pluginId")
                return
            }

            LOG.info("Plugin unloaded successfully")

            // Re-load the plugin from disk
            // Note: After unload, we need to re-read the descriptor from disk
            val newDescriptor = loadDescriptorFromPath(pluginPath)
            if (newDescriptor == null) {
                LOG.error("Failed to load plugin descriptor from: $pluginPath")
                return
            }

            val loadResult = DynamicPlugins.loadPlugin(newDescriptor)
            if (!loadResult) {
                LOG.error("Failed to load plugin: $pluginId")
                return
            }

            LOG.info("=== MCP Steroid Plugin Reload Complete ===")

        } catch (e: Exception) {
            LOG.error("Plugin reload failed", e)
        }
    }

    /**
     * Loads a plugin descriptor from a path.
     */
    private fun loadDescriptorFromPath(path: Path): PluginMainDescriptor? {
        return try {
            // Use PluginManagerCore to load descriptor
            val loadDescriptorMethod = PluginManagerCore::class.java.methods
                .find { it.name == "loadDescriptor" && it.parameterCount == 2 }

            if (loadDescriptorMethod != null) {
                @Suppress("UNCHECKED_CAST")
                loadDescriptorMethod.invoke(null, path, false) as? PluginMainDescriptor
            } else {
                LOG.warn("loadDescriptor method not found, using alternative approach")
                // Fallback: get from the plugin set
                PluginManagerCore.getPlugin(PluginId.getId(PluginReloadHelper.PLUGIN_ID)) as? PluginMainDescriptor
            }
        } catch (e: Exception) {
            LOG.error("Failed to load descriptor from path: $path", e)
            null
        }
    }

    /**
     * Creates a callable that can be executed from a different classloader.
     *
     * This approach captures only serializable data and creates a new
     * class instance using reflection, avoiding classloader conflicts.
     */
    fun createReloadCallable(pluginId: String): Callable<Boolean> {
        return Callable {
            try {
                // Get classes from the platform classloader
                val pluginManagerClass = Class.forName("com.intellij.ide.plugins.PluginManagerCore")
                val dynamicPluginsClass = Class.forName("com.intellij.ide.plugins.DynamicPlugins")
                val pluginIdClass = Class.forName("com.intellij.openapi.extensions.PluginId")

                // Get PluginId.getId method
                val getIdMethod = pluginIdClass.getMethod("getId", String::class.java)
                val id = getIdMethod.invoke(null, pluginId)

                // Get PluginManagerCore.getPlugin method
                val getPluginMethod = pluginManagerClass.getMethod("getPlugin", pluginIdClass)
                val descriptor = getPluginMethod.invoke(null, id)

                if (descriptor == null) {
                    return@Callable false
                }

                // Get DynamicPlugins.unloadPlugin method
                val unloadMethod = dynamicPluginsClass.methods
                    .find { it.name == "unloadPlugin" && it.parameterCount == 2 }

                if (unloadMethod == null) {
                    return@Callable false
                }

                // Create UnloadPluginOptions using reflection
                val optionsClass = Class.forName("com.intellij.ide.plugins.DynamicPlugins\$UnloadPluginOptions")
                val optionsConstructor = optionsClass.constructors.first()
                val options = optionsConstructor.newInstance(false, false, true, false, false, true, null)

                // Call unloadPlugin
                val result = unloadMethod.invoke(null, descriptor, options) as Boolean
                result

            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    sealed class ReloadResult {
        data class Success(val message: String) : ReloadResult()
        data class Scheduled(val message: String) : ReloadResult()
        data class Failure(val reason: String) : ReloadResult()

        companion object {
            fun success(message: String) = Success(message)
            fun scheduled(message: String) = Scheduled(message)
            fun failure(reason: String) = Failure(reason)
        }
    }
}

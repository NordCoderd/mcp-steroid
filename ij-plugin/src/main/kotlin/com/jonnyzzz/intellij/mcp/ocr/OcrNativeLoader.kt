/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.ocr

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.util.system.CpuArch
import com.jonnyzzz.intellij.mcp.server.PluginVersionResolver
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isDirectory
import kotlin.io.path.name

object OcrNativeLoader {
    private val log = Logger.getInstance(OcrNativeLoader::class.java)
    private val pluginId = PluginId.getId("com.jonnyzzz.intellij.mcp-steroid")

    @Volatile
    private var cached: OcrClassLoader? = null
    @Volatile
    private var cachedTessdataDir: Path? = null

    fun ensureLoaded(): ClassLoader {
        val existing = cached
        if (existing != null) return existing

        synchronized(this) {
            val again = cached
            if (again != null) return again

            val nativeDir = resolveNativeDir()
            Files.createDirectories(nativeDir)
            configureNativePaths(nativeDir)

            val loader = OcrClassLoader(resolvePluginUrls(), nativeDir)
            loadNativeLibraries(loader)

            cached = loader
            return loader
        }
    }

    fun ensureTessdataDir(loader: ClassLoader): Path {
        val existing = cachedTessdataDir
        if (existing != null) return existing

        synchronized(this) {
            val again = cachedTessdataDir
            if (again != null) return again

            val tessdataDir = resolveTessdataDir()
            Files.createDirectories(tessdataDir)

            copyResource(loader, "tessdata/eng.traineddata", tessdataDir.resolve("eng.traineddata"))
            copyResource(loader, "tessdata/osd.traineddata", tessdataDir.resolve("osd.traineddata"))

            cachedTessdataDir = tessdataDir
            return tessdataDir
        }
    }

    private fun resolveNativeDir(): Path {
        val version = PluginVersionResolver.resolve(OcrNativeLoader::class.java.classLoader)
        val platform = platformId()
        return Path.of(PathManager.getSystemPath(), "mcp-ocr", "native", version, platform)
    }

    private fun resolveTessdataDir(): Path {
        val version = PluginVersionResolver.resolve(OcrNativeLoader::class.java.classLoader)
        return Path.of(PathManager.getSystemPath(), "mcp-ocr", "tessdata", version)
    }

    private fun platformId(): String {
        val os = when {
            SystemInfoRt.isMac -> "macos"
            SystemInfoRt.isLinux -> "linux"
            SystemInfoRt.isWindows -> "windows"
            else -> System.getProperty("os.name").replace(' ', '-').lowercase()
        }
        val arch = when {
            CpuArch.isArm64() -> "arm64"
            CpuArch.isIntel64() -> "x86_64"
            else -> CpuArch.CURRENT.name.lowercase()
        }
        return "$os-$arch"
    }

    private fun configureNativePaths(nativeDir: Path) {
        val path = nativeDir.toString()
        System.setProperty("org.bytedeco.javacpp.cachedir", path)
        prependProperty("jna.library.path", path)
        prependProperty("jna.platform.library.path", path)
    }

    private fun prependProperty(key: String, value: String) {
        val current = System.getProperty(key)
        val updated = if (current.isNullOrBlank()) value else value + File.pathSeparator + current
        System.setProperty(key, updated)
    }

    private fun resolvePluginUrls(): Array<URL> {
        val plugin = PluginManagerCore.getPlugin(pluginId)
        val pluginPath = plugin?.pluginPath
        if (pluginPath != null) {
            val libDir = pluginPath.resolve("lib")
            if (libDir.isDirectory()) {
                Files.list(libDir).use { stream ->
                    return stream
                        .filter { it.fileName.toString().endsWith(".jar") }
                        .sorted()
                        .map { it.toUri().toURL() }
                        .toList()
                        .toTypedArray()
                }
            }
        }
        val fallback = PathManager.getJarForClass(OcrNativeLoader::class.java)
        if (fallback != null) {
            return arrayOf(fallback.toUri().toURL())
        }
        log.warn("Unable to resolve plugin lib directory for OCR classloader")
        return emptyArray()
    }

    private fun copyResource(loader: ClassLoader, resource: String, target: Path) {
        if (Files.exists(target) && Files.size(target) > 0) return

        val stream = loader.getResourceAsStream(resource)
            ?: throw IllegalStateException("OCR resource not found: $resource")
        stream.use { input ->
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun loadNativeLibraries(loader: ClassLoader) {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        thread.contextClassLoader = loader
        try {
            val loaderClass = loader.loadClass("org.bytedeco.javacpp.Loader")
            val loadMethod = loaderClass.getMethod("load", Class::class.java)

            val leptonicaClass = loader.loadClass("org.bytedeco.leptonica.global.leptonica")
            val tesseractClass = loader.loadClass("org.bytedeco.tesseract.global.tesseract")

            loadMethod.invoke(null, leptonicaClass)
            loadMethod.invoke(null, tesseractClass)
        } catch (e: Throwable) {
            log.warn("Failed to load OCR native libraries: ${e.message}", e)
        } finally {
            thread.contextClassLoader = previous
        }
    }
}

private class OcrClassLoader(
    urls: Array<URL>,
    private val nativeDir: Path,
) : URLClassLoader(urls, OcrNativeLoader::class.java.classLoader) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (isOcrClass(name)) {
            val loaded = findLoadedClass(name)
            if (loaded != null) return loaded
            val clazz = findClass(name)
            if (resolve) {
                resolveClass(clazz)
            }
            return clazz
        }
        return super.loadClass(name, resolve)
    }

    override fun findLibrary(libname: String): String? {
        val mapped = System.mapLibraryName(libname)
        val direct = nativeDir.resolve(mapped)
        if (Files.exists(direct)) {
            return direct.toAbsolutePath().toString()
        }

        Files.list(nativeDir).use { stream ->
            val iterator = stream.iterator()
            while (iterator.hasNext()) {
                val path = iterator.next()
                val name = path.name
                if (name.contains(libname, ignoreCase = true) || name.contains(mapped, ignoreCase = true)) {
                    return path.toAbsolutePath().toString()
                }
            }
        }

        return null
    }

    private fun isOcrClass(name: String): Boolean {
        return name.startsWith("net.sourceforge.tess4j.") ||
            name.startsWith("org.bytedeco.")
    }
}

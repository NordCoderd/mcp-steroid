/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.koltinc

import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.time.Duration.Companion.seconds

class ScriptClassLoaderFactoryTest : BasePlatformTestCase() {

    override fun runInDispatchThread(): Boolean = false

    fun testIdeClasspathContainsCurrentClassEntry(): Unit = timeoutRunBlocking(30.seconds) {
        val ideEntries = scriptClassLoaderFactory.ideClasspath()
        assertTrue("Expected ideClasspath to contain entries", ideEntries.isNotEmpty())

        val resourceEntry = classpathEntryFromResource(javaClass)
        assertTrue(
            "Expected ideClasspath to contain $resourceEntry",
            ideEntries.any { it.normalize() == resourceEntry.normalize() },
        )
    }

    fun testExecCodeClassloaderLoadsClassFromJar(): Unit = timeoutRunBlocking(30.seconds) {
        val root = Files.createTempDirectory("script-classloader")
        val resourceEntry = classpathEntryFromResource(javaClass)
        val jar = if (Files.isRegularFile(resourceEntry)) {
            resourceEntry
        } else {
            createClassJar(root, javaClass)
        }

        val loader = scriptClassLoaderFactory.execCodeClassloader(jar)
        val loaded = loader.loadClass(javaClass.name)
        assertEquals(javaClass.name, loaded.name)
    }

    private fun classpathEntryFromResource(klass: Class<*>): Path {
        val resourcePath = klass.name.replace('.', '/') + ".class"
        val resourceUrl = klass.classLoader.getResource(resourcePath)
            ?: error("Resource not found for $resourcePath")
        return when (resourceUrl.protocol) {
            "jar" -> Paths.get(URI.create(jarPathFromUrl(resourceUrl)))
            "file" -> {
                var path = Paths.get(resourceUrl.toURI())
                repeat(resourcePath.split('/').size) { path = path.parent }
                path
            }
            else -> error("Unsupported resource protocol: ${resourceUrl.protocol}")
        }
    }

    private fun jarPathFromUrl(url: java.net.URL): String {
        val text = url.toString()
        val bangIndex = text.indexOf("!/")
        require(text.startsWith("jar:") && bangIndex > 0) { "Unexpected jar URL: $text" }
        return text.substring("jar:".length, bangIndex)
    }

    private fun createClassJar(root: Path, klass: Class<*>): Path {
        val jarPath = root.resolve("class.jar")
        val resourcePath = klass.name.replace('.', '/') + ".class"
        JarOutputStream(Files.newOutputStream(jarPath)).use { jar ->
            jar.putNextEntry(JarEntry(resourcePath))
            jar.write(classBytes(klass))
            jar.closeEntry()
        }
        return jarPath
    }

    private fun classBytes(klass: Class<*>): ByteArray {
        val resourcePath = klass.name.replace('.', '/') + ".class"
        val stream = klass.classLoader.getResourceAsStream(resourcePath)
            ?: error("Resource not found for $resourcePath")
        return stream.use { it.readBytes() }
    }
}

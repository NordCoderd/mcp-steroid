/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.koltinc

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.client.ClientAwareComponentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * A [ScriptClassLoaderFactory] for tests that includes ALL jars from the IDE home
 * (plugins/ and lib/ directories), ensuring that plugin-specific classes like
 * [com.intellij.execution.junit.JUnitConfiguration] are available for kotlinc compilation.
 *
 * Register via [useFullIdeClasspathForCompilation] in test setUp().
 */
class FullIdeClasspathScriptClassLoaderFactory : ScriptClassLoaderFactory {
    private val delegate = DefaultScriptClassLoaderFactory()

    override fun ideClasspath(): List<Path> {
        val standard = delegate.ideClasspath()
        val ideHome = Paths.get(System.getProperty("idea.home.path") ?: return standard)
        val extraJars = mutableListOf<Path>()
        for (subdir in listOf("lib", "plugins")) {
            val dir = ideHome.resolve(subdir)
            if (Files.isDirectory(dir)) {
                Files.walk(dir).use { stream ->
                    stream.filter { it.toString().endsWith(".jar") && Files.isRegularFile(it) }
                        .forEach { extraJars.add(it) }
                }
            }
        }
        return (standard + extraJars).distinctBy { it.normalize().toString() }
    }

    override fun execCodeClassloader(jar: Path) = delegate.execCodeClassloader(jar)
}

/**
 * Replaces [ScriptClassLoaderFactory] for the duration of this test with
 * [FullIdeClasspathScriptClassLoaderFactory], which includes all IDE plugin JARs.
 */
fun BasePlatformTestCase.useFullIdeClasspathForCompilation() {
    (ApplicationManager.getApplication() as ClientAwareComponentManager)
        .replaceServiceInstance(
            ScriptClassLoaderFactory::class.java,
            FullIdeClasspathScriptClassLoaderFactory(),
            testRootDisposable,
        )
}

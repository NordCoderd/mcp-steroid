/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.koltinc

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Base class for generated KtBlocks compilation tests.
 *
 * Replaces [com.jonnyzzz.mcpSteroid.koltinc.ScriptClassLoaderFactory] with
 * [FullIdeClasspathScriptClassLoaderFactory] so that all IDE plugin JARs
 * (including plugin-specific classes like JUnitConfiguration) are available
 * on the kotlinc compilation classpath.
 */
abstract class BaseKtBlocksCompilationTest : BasePlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        useFullIdeClasspathForCompilation()
    }
}

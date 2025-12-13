/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.script

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.intellij.mcp.execution.McpScriptContext
import kotlin.script.experimental.api.*
import kotlin.script.experimental.jvm.JvmDependency
import kotlin.script.experimental.jvm.jvm

/**
 * Tests for MCP Steroid script definition.
 *
 * Verifies that the script definition provides correct classpath
 * so that deep IntelliJ API references resolve without errors.
 */
class ScriptDefinitionTest : BasePlatformTestCase() {

    /**
     * Verify that the script compilation configuration has expected default imports.
     */
    fun testScriptDefinitionHasDefaultImports() {
        val config = McpSteroidScriptCompilationConfiguration

        // Get default imports from the configuration
        val imports = config[ScriptCompilationConfiguration.defaultImports]
        assertNotNull("Script definition should have default imports", imports)

        // Verify key IntelliJ imports are present
        val importStrings = imports!!.map { it.toString() }
        assertTrue(
            "Should have Project import",
            importStrings.any { it.contains("com.intellij.openapi.project") }
        )
        assertTrue(
            "Should have readAction import",
            importStrings.any { it.contains("readAction") }
        )
        assertTrue(
            "Should have writeAction import",
            importStrings.any { it.contains("writeAction") }
        )
        assertTrue(
            "Should have VirtualFile import",
            importStrings.any { it.contains("com.intellij.openapi.vfs") }
        )
        assertTrue(
            "Should have PSI import",
            importStrings.any { it.contains("com.intellij.psi") }
        )
        assertTrue(
            "Should have coroutines import",
            importStrings.any { it.contains("kotlinx.coroutines") }
        )
    }

    /**
     * Verify that the script definition has the execute property binding.
     */
    fun testScriptDefinitionHasExecuteBinding() {
        val config = McpSteroidScriptCompilationConfiguration

        val providedProps = config[ScriptCompilationConfiguration.providedProperties]
        assertNotNull("Script definition should have provided properties", providedProps)

        // providedProperties returns Map<String, KotlinType>
        assertTrue(
            "Script definition should provide 'execute' binding",
            providedProps!!.containsKey("execute")
        )
    }

    /**
     * Verify that the script definition accepts scripts everywhere.
     */
    fun testScriptDefinitionAcceptsScriptsEverywhere() {
        val config = McpSteroidScriptCompilationConfiguration

        val acceptedLocations = config[ScriptCompilationConfiguration.ide.acceptedLocations]
        assertNotNull("Script definition should specify accepted locations", acceptedLocations)
        assertTrue(
            "Script definition should accept scripts everywhere",
            acceptedLocations!!.contains(ScriptAcceptedLocation.Everywhere)
        )
    }

    /**
     * Verify that the script definitions provider returns the correct class.
     */
    fun testScriptDefinitionsProviderReturnsCorrectClass() {
        val provider = McpSteroidScriptDefinitionsProvider()

        val classes = provider.getDefinitionClasses().toList()
        assertEquals(1, classes.size)
        assertEquals(McpSteroidScript::class.qualifiedName, classes[0])
    }

    /**
     * Verify that the script definitions provider returns a non-empty classpath.
     */
    fun testScriptDefinitionsProviderReturnsClasspath() {
        val provider = McpSteroidScriptDefinitionsProvider()

        val classpath = provider.getDefinitionsClassPath().toList()
        // Classpath should not be empty - it should at least contain our plugin classes
        assertTrue(
            "Script definitions classpath should not be empty",
            classpath.isNotEmpty()
        )

        // At least one path should exist
        assertTrue(
            "At least one classpath entry should exist",
            classpath.any { it.exists() }
        )
    }

    /**
     * Verify that JVM dependencies from script configuration include IntelliJ classes.
     *
     * This test ensures that the dependenciesFromClassContext includes the IntelliJ
     * platform classes that scripts need to use.
     */
    fun testScriptConfigurationIncludesIntelliJClasses() {
        val config = McpSteroidScriptCompilationConfiguration

        val jvmDeps = config[ScriptCompilationConfiguration.dependencies]
        assertNotNull("Script configuration should have dependencies", jvmDeps)

        // Get all classpath entries
        val classpathEntries = jvmDeps!!.filterIsInstance<JvmDependency>()
            .flatMap { it.classpath }
            .map { it.absolutePath }

        // The classpath should include IntelliJ platform classes
        // McpScriptContext is our class that depends on IntelliJ APIs
        val hasOurClasses = classpathEntries.any { path ->
            path.contains("mcp") || path.contains("intellij") || path.contains("classes")
        }
        assertTrue(
            "Classpath should include plugin classes: $classpathEntries",
            hasOurClasses
        )
    }

    /**
     * Verify that the script definition can be used to resolve IntelliJ API types.
     *
     * This is a sanity check that the classpath includes the necessary types
     * that scripts will reference.
     */
    fun testDeepIntelliJApiTypesAreResolvable() {
        // These classes should be loadable via the same classloader that will be used for scripts
        val classLoader = McpScriptContext::class.java.classLoader

        // Core IntelliJ types that scripts reference
        val typesToCheck = listOf(
            "com.intellij.openapi.project.Project",
            "com.intellij.openapi.vfs.VirtualFile",
            "com.intellij.openapi.editor.Editor",
            "com.intellij.openapi.fileEditor.FileEditorManager",
            "com.intellij.psi.PsiFile",
            "com.intellij.psi.PsiManager",
            "com.intellij.openapi.command.WriteCommandAction",
            "com.intellij.openapi.application.ApplicationManager",
            // Deep API types that demonstrate full access
            "com.intellij.psi.util.PsiTreeUtil",
            "com.intellij.openapi.module.ModuleManager",
            "com.intellij.openapi.roots.ModuleRootManager",
            "com.intellij.openapi.vfs.LocalFileSystem",
            "com.intellij.openapi.diagnostic.Logger"
        )

        val missingTypes = mutableListOf<String>()
        for (typeName in typesToCheck) {
            try {
                Class.forName(typeName, false, classLoader)
            } catch (_: ClassNotFoundException) {
                missingTypes.add(typeName)
            }
        }

        assertTrue(
            "All IntelliJ API types should be resolvable. Missing: $missingTypes",
            missingTypes.isEmpty()
        )
    }
}

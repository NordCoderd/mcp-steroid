/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Infrastructure test that validates JDK table registration in the Docker container.
 *
 * The container pre-writes `jdk.table.xml` before IntelliJ starts (see [IntelliJDriver.writeJdkTable]).
 * This test verifies that:
 * 1. JDKs are registered in [ProjectJdkTable] and visible to the IDE
 * 2. Each registered JDK has a valid homePath with `bin/java`
 * 3. A JDK can be applied as the project SDK
 * 4. IntelliJ compilation works after SDK is set (no "SDK not specified" error)
 *
 * No AI agents are used — this is a pure infrastructure validation test via MCP Steroid.
 *
 * ```
 * ./gradlew :test-integration:test --tests '*JdkTableIntegrationTest*'
 * ```
 */
class JdkTableIntegrationTest {

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `JDK table has registered SDKs with valid paths`() = runWithCloseableStack { lifetime ->
        val session = IntelliJContainer.create(lifetime, consoleTitle = "jdk-table-test")
        val console = session.console

        // Step 1: Verify JDKs are registered in ProjectJdkTable
        console.writeStep(1, "Checking ProjectJdkTable for registered JDKs")
        session.mcpSteroid.mcpExecuteCode(
            code = """
                import com.intellij.openapi.projectRoots.JavaSdk
                import com.intellij.openapi.projectRoots.ProjectJdkTable

                val allSdks = ProjectJdkTable.getInstance().getSdksOfType(JavaSdk.getInstance())
                println("JDK_COUNT: ${'$'}{allSdks.size}")
                allSdks.forEach { sdk ->
                    val home = sdk.homePath ?: "null"
                    val hasJava = java.io.File(home, "bin/java").exists()
                    println("JDK: name=${'$'}{sdk.name} home=${'$'}home valid=${'$'}hasJava")
                }
                require(allSdks.isNotEmpty()) { "No JDKs registered in ProjectJdkTable — jdk.table.xml is broken" }
                println("JDK_TABLE_OK")
            """.trimIndent(),
            taskId = "jdk-table-test",
            reason = "Validate JDK table registration",
        ).assertExitCode(0, "JDK table query should succeed")
            .assertOutputContains("JDK_TABLE_OK", message = "should have registered JDKs")
        console.writeSuccess("JDK table has registered entries")

        // Step 2: Verify each JDK has a valid home path with bin/java
        console.writeStep(2, "Validating JDK home paths")
        session.mcpSteroid.mcpExecuteCode(
            code = """
                import com.intellij.openapi.projectRoots.JavaSdk
                import com.intellij.openapi.projectRoots.ProjectJdkTable

                val allSdks = ProjectJdkTable.getInstance().getSdksOfType(JavaSdk.getInstance())
                var allValid = true
                for (sdk in allSdks) {
                    val home = sdk.homePath
                    if (home == null) {
                        println("INVALID: ${'$'}{sdk.name} — homePath is null")
                        allValid = false
                        continue
                    }
                    val javaFile = java.io.File(home, "bin/java")
                    if (!javaFile.exists()) {
                        println("INVALID: ${'$'}{sdk.name} — ${'$'}home/bin/java does not exist")
                        allValid = false
                    } else {
                        println("VALID: ${'$'}{sdk.name} — ${'$'}home")
                    }
                }
                require(allValid) { "Some JDKs have invalid home paths" }
                println("ALL_PATHS_VALID")
            """.trimIndent(),
            taskId = "jdk-table-test",
            reason = "Validate JDK home paths have bin/java",
        ).assertExitCode(0, "JDK path validation should succeed")
            .assertOutputContains("ALL_PATHS_VALID", message = "all JDK paths should be valid")
        console.writeSuccess("All JDK paths are valid")

        // Step 3: Apply a JDK as project SDK and verify it's set
        console.writeStep(3, "Setting project SDK from registered JDKs")
        session.mcpSteroid.mcpExecuteCode(
            code = """
                import com.intellij.openapi.projectRoots.JavaSdk
                import com.intellij.openapi.projectRoots.ProjectJdkTable
                import com.intellij.openapi.projectRoots.ex.JavaSdkUtil
                import com.intellij.openapi.roots.ProjectRootManager
                import com.intellij.openapi.application.edtWriteAction

                val allSdks = ProjectJdkTable.getInstance().getSdksOfType(JavaSdk.getInstance())
                // Prefer JDK 21, fall back to any
                val sdk = allSdks.firstOrNull { it.name == "21" }
                    ?: allSdks.firstOrNull { it.name.contains("21") }
                    ?: allSdks.first()

                edtWriteAction { JavaSdkUtil.applyJdkToProject(project, sdk) }

                val appliedSdk = ProjectRootManager.getInstance(project).projectSdk
                println("APPLIED_SDK: name=${'$'}{appliedSdk?.name} home=${'$'}{appliedSdk?.homePath}")
                require(appliedSdk != null) { "Project SDK should be set after applyJdkToProject" }
                println("PROJECT_SDK_OK")
            """.trimIndent(),
            taskId = "jdk-table-test",
            reason = "Apply JDK as project SDK",
        ).assertExitCode(0, "Applying project SDK should succeed")
            .assertOutputContains("PROJECT_SDK_OK", message = "project SDK should be set")
        console.writeSuccess("Project SDK applied successfully")

        // Step 4: Verify compilation works with the SDK set
        console.writeStep(4, "Triggering IntelliJ compilation to verify SDK works")
        session.mcpSteroid.mcpExecuteCode(
            code = """
                val result = com.intellij.task.ProjectTaskManager.getInstance(project)
                    .buildAllModules().blockingGet(60_000)
                val hasErrors = result?.hasErrors() ?: false
                val isAborted = result?.isAborted ?: false
                println("BUILD_RESULT: errors=${'$'}hasErrors aborted=${'$'}isAborted")
                // Aborted is acceptable (no source files in default test project) but errors are not
                require(!hasErrors) { "Compilation should not have errors with a valid SDK" }
                println("COMPILATION_OK")
            """.trimIndent(),
            taskId = "jdk-table-test",
            reason = "Verify IntelliJ compilation works with project SDK",
            timeout = 120,
        ).assertExitCode(0, "Compilation should succeed")
            .assertOutputContains("COMPILATION_OK", message = "compilation should pass without errors")

        console.writeSuccess("All JDK table checks passed")
    }
}

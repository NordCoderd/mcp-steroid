/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager.getInstance
import com.intellij.openapi.util.registry.Registry
import com.jonnyzzz.mcpSteroid.execution.ExecutionManager
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.updates.analyticsBeacon
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.CodingWithIntelliJPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.DebuggerSkillPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.SkillPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.TestSkillPromptArticle
import kotlinx.serialization.json.*

data class ExecCodeParams(
    val taskId: String,
    val code: String,
    val reason: String,
    val timeout: Int,
    //TODO: move that away from here, allow changes only via the McpScriptContext::doNotCancelOnModalityStateChange
    /** If true, cancel execution when a modal dialog appears and return a screenshot. Default true. */
    val cancelOnModal: Boolean = true,

    /** Controls pre-execution dialog killer: null = use registry default, true = force enable, false = force disable. */
    val dialogKiller: Boolean? = null,

    val rawParams: JsonObject,
)

/**
 * Handler for the steroid_execute_code MCP tool.
 */
class ExecuteCodeToolHandler : McpRegistrar {
    private val toolDescription get() = run {
        val codingGuideUri = CodingWithIntelliJPromptArticle().uri
        val skillUri = SkillPromptArticle().uri
        val debuggerUri = DebuggerSkillPromptArticle().uri
        val testUri = TestSkillPromptArticle().uri
        """
             WHAT: Finally SEE IntelliJ-based IDEs - not just read code. The only MCP server with visual understanding and full IDE control.
             HOW: Execute Kotlin code directly in IntelliJ's runtime with full API access.

             📖 **COMPLETE GUIDE**: [Coding with IntelliJ APIs]($codingGuideUri)

             This is a **stateful** API - everything you do changes the IDE state. The IntelliJ IDE is running exclusively for you. Use it aggressively instead of manual file operations.

             **⚡ Bypasses Agent Sandbox**: Scripts run inside IntelliJ's JVM — unrestricted filesystem access. Use steroid_execute_code to read/write project files INSTEAD of agent-side file tools (those are sandboxed to /home/agent and cannot access /mcp-run-dir/ or the project). Do NOT use shell heredocs for multi-line file creation — use VFS APIs below.

             **Quick Start:**
             - Your code is a suspend function body (never use runBlocking)
             - Use readAction { } for PSI/VFS reads, writeAction { } for modifications
             - waitForSmartMode() runs automatically before your script
             - Available: project, println(), printJson(), printException(), progress()

             **Read a project file:**
             ```kotlin
             val text = VfsUtil.loadText(findProjectFile("src/main/resources/application.properties")!!)
             println(text)
             ```

             **Create/write a Java or Kotlin source file:**
             ```kotlin
             writeAction {
                 val dir = VfsUtil.createDirectoryIfMissing(project.baseDir, "src/main/java/com/example/model")
                 val f = dir.findChild("Product.java") ?: dir.createChildData(this, "Product.java")
                 // Use joinToString() or File.writeText() — NOT a triple-quoted string with 'import' at line start
                 // (the preprocessor extracts import-like lines from triple-quoted strings as Kotlin imports)
                 VfsUtil.saveText(f, listOf(
                     "package com.example.model;",
                     "import" + " jakarta.persistence.Entity;",
                     "import" + " jakarta.persistence.Id;",
                     "@Entity public class Product { @Id private Long id; }"
                 ).joinToString("\n"))
             }
             println("File created")
             ```

             **⚠️ Import-in-strings pitfall**: Never put `import foo.Bar;` at the start of a line inside a triple-quoted Kotlin string. The script preprocessor extracts those lines as Kotlin imports, causing compile errors. Use `"import" + " foo.Bar;"` or `joinToString` to build the content, or use `java.io.File(path).writeText(content)` as an alternative.

             **Spring Boot / Maven patterns:**
             ```kotlin
             // Find all @Entity classes in the project
             import com.intellij.psi.search.searches.AnnotatedElementsSearch
             val entityAnnotation = readAction {
                 JavaPsiFacade.getInstance(project).findClass("jakarta.persistence.Entity", allScope())
             }
             AnnotatedElementsSearch.searchPsiClasses(entityAnnotation!!, projectScope()).findAll()
                 .forEach { println(it.qualifiedName) }
             ```
             ```kotlin
             // Trigger Maven re-import after editing pom.xml
             import org.jetbrains.idea.maven.project.MavenProjectsManager
             MavenProjectsManager.getInstance(project).forceUpdateAllProjectsOrFindAllAvailablePomFiles()
             println("Maven sync triggered")
             ```
             ```kotlin
             // Check for compile errors in a Java file (faster than running mvn test)
             val vf = findProjectFile("src/main/java/com/example/Product.java")!!
             val problems = runInspectionsDirectly(vf)
             problems.forEach { (id, descs) -> descs.forEach { println("[" + id + "] " + it.descriptionTemplate) } }
             ```
             ```kotlin
             // ⚠️ Always use ./mvnw (Maven wrapper) not 'mvn' — system mvn is not installed
             val process = ProcessBuilder("./mvnw", "test", "-Dtest=MyValidatorTest", "-q")
                 .directory(java.io.File(project.basePath!!))
                 .redirectErrorStream(true).start()
             println(process.inputStream.bufferedReader().readText())
             process.waitFor()
             ```
             ```kotlin
             // Run a specific JUnit test class via IntelliJ runner (correct API — common pitfall below)
             import com.intellij.execution.junit.JUnitConfiguration
             import com.intellij.execution.junit.JUnitConfigurationType
             import com.intellij.execution.RunManager
             import com.intellij.execution.ProgramRunnerUtil
             import com.intellij.execution.executors.DefaultRunExecutor
             val factory = JUnitConfigurationType.getInstance().configurationFactories.first()
             val config = factory.createConfiguration("Run test", project) as JUnitConfiguration
             val data = config.persistentData               // typed as JUnitConfiguration.Data
             data.TEST_CLASS = "com.example.MyTest"
             data.TEST_OBJECT = JUnitConfiguration.TEST_CLASS  // ← constant, NOT string "class"
             config.setWorkingDirectory(project.basePath!!)
             val settings = RunManager.getInstance(project).createConfiguration(config, factory)
             RunManager.getInstance(project).addConfiguration(settings)
             ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
             println("Test run started")
             // ⚠️ Pitfall: `data.TEST_OBJECT = "class"` → compile error "unresolved reference 'TEST_CLASS'"
             // Always use the constant: JUnitConfiguration.TEST_CLASS
             ```
             ```kotlin
             // Check if a Java class already exists before creating it (prevent duplicate files)
             val existing = readAction {
                 com.intellij.psi.JavaPsiFacade.getInstance(project).findClass(
                     "com.example.MyClass",
                     com.intellij.psi.search.GlobalSearchScope.projectScope(project)
                 )
             }
             println(if (existing == null) "NOT_FOUND: safe to create" else "EXISTS: " + existing.containingFile.virtualFile.path)
             ```
             ```kotlin
             // Discover existing class naming conventions before creating new classes
             // (avoids naming mismatches like EventType vs NotificationEventType)
             import com.intellij.psi.search.PsiShortNamesCache
             val allNames = readAction { PsiShortNamesCache.getInstance(project).allClassNames.toList() }
             allNames.filter { it.endsWith("Status") || it.endsWith("Type") || it.endsWith("Dto") || it.endsWith("Service") }
                 .sorted().forEach { println(it) }
             ```
             ```kotlin
             // Find next Flyway migration version number (avoids V5__ collision if V5__ already exists)
             val migDir = findProjectFile("src/main/resources/db/migration")!!
             val nextVersion = readAction {
                 migDir.children.map { it.name }
                     .mapNotNull { Regex("V(\\d+)__").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                     .maxOrNull()?.plus(1) ?: 1
             }
             println("NEXT_MIGRATION=V" + nextVersion)
             ```
             ```kotlin
             // Targeted file read — extract only assertions/endpoints to avoid context bloat
             val testContent = VfsUtil.loadText(findProjectFile("src/test/java/com/example/MyTest.java")!!)
             testContent.lines()
                 .filter { it.contains("assert") || it.contains("/api/") || it.contains("@Test") }
                 .forEach { println(it) }
             ```

             **Verification gate**: Run FAIL_TO_PASS tests before marking work complete.
             `./mvnw test -Dtest=ClassName -Dspotless.check.skip=true` — compile success alone is NOT sufficient.

             **Common Operations:**
             - Code navigation: Find usages, go to definition, symbol search
             - Refactoring: Rename, extract method, move files
             - Inspections: Run code analysis, get warnings/errors
             - Tests: Execute tests, inspect results
             - Actions: Trigger any IDE action programmatically

             **Example:**
             ```kotlin
             val file = findProjectFile("src/Main.kt")
             val text = readAction {
                 PsiManager.getInstance(project).findFile(file!!)?.text
             }
             println("File length: " + text?.length)
             ```

             **Best Practice: Use Sub-Agents**
             For complex IntelliJ API work, delegate to a sub-agent:
             - Sub-agent can retry without polluting your context
             - Errors stay isolated
             - Provide detailed 'reason' parameter

             **Resources:**
             - [Complete Coding Guide]($codingGuideUri) - Patterns, examples, best practices
             - [API Power User Guide]($skillUri) - Essential patterns
             - [Debugger Guide]($debuggerUri) - Debug workflows
             - [Test Runner Guide]($testUri) - Test execution

             IntelliJ API Version: ${ApplicationInfo.getInstance().apiVersion}

             **When to use other steroid tools instead:**
             - steroid_list_projects — list open projects and their paths
             - steroid_list_windows — check window state, indexing progress, modal dialogs
             - steroid_open_project — open a project directory in the IDE
             - steroid_action_discovery — discover quick-fixes, intentions, and actions at a file location
             - steroid_take_screenshot / steroid_input — visual UI inspection and interaction

             💡 Call steroid_execute_feedback after execution to rate success
         """.trim().lines().joinToString("\n") { it.trim() }
    }

    override fun register(server: McpServerCore) {
        server.toolRegistry.registerTool(
            name = "steroid_execute_code",
            description = toolDescription,
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("project_name") {
                        put("type", "string")
                        put("description", "Project name (from steroid_list_projects)")
                    }
                    putJsonObject("code") {
                        put("type", "string")
                        put("description", "Kotlin suspend method body")
                    }
                    putJsonObject("task_id") {
                        put("type", "string")
                        put("description", "Your task identifier to group related executions. Use the same task_id for all execute_code calls that are part of the same task, and when providing feedback via steroid_execute_feedback.")
                    }
                    putJsonObject("reason") {
                        put("type", "string")
                        put("description", "IMPORTANT: On your FIRST call, provide the FULL TASK DESCRIPTION from the user - what they originally asked you to do. On subsequent calls, describe what this specific execution aims to achieve. This helps track progress and understand context.")
                    }
                    putJsonObject("timeout") {
                        put("type", "integer")
                        put("description", "Execution timeout in seconds (default: 600, configurable via mcp.steroid.execution.timeout registry key)")
                    }
                    putJsonObject("dialog_killer") {
                        put("type", "boolean")
                        put("description", "Override pre-execution dialog killer: true = force enable, false = force disable. Default: use registry setting (mcp.steroid.dialog.killer.enabled).")
                    }
                    putJsonObject("required_plugins") {
                        put("type", "array")
                        putJsonObject("items") {
                            put("type", "string")
                        }
                        put(
                            "description",
                            "Optional list of required plugin IDs (example: com.intellij.database). " +
                                "Use steroid_capabilities to list installed plugins."
                        )
                    }
                }
                putJsonArray("required") {
                    add("project_name")
                    add("code")
                    add("reason")
                    add("task_id")
                }
            },
            ::handle
        )
    }

    private suspend fun handle(context: ToolCallContext): ToolCallResult {
        val params = context.params
        val args = params.arguments ?: return errorResult("Missing arguments")

        val projectName = args["project_name"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("Missing required parameter: project_name")
        val code = args["code"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("Missing required parameter: code")
        val taskId = args["task_id"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("Missing required parameter: task_id")
        val reason = args["reason"]?.jsonPrimitive?.contentOrNull
        val timeout = args["timeout"]?.jsonPrimitive?.intOrNull ?: Registry.intValue("mcp.steroid.execution.timeout", 600)
        val dialogKiller = args["dialog_killer"]?.jsonPrimitive?.booleanOrNull
        val requiredPlugins = args["required_plugins"]
            ?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        val missingPlugins = findMissingPlugins(requiredPlugins)
        if (missingPlugins.isNotEmpty()) {
            return errorResult(
                "Missing required plugins: ${missingPlugins.joinToString(", ")}. " +
                    "Use steroid_capabilities to list installed plugins."
            )
        }

        val project = readAction {
            getInstance().openProjects.find { it.name == projectName }
        }
            ?: return errorResult("Project not found: $projectName")

        val execCodeParams = ExecCodeParams(
            taskId = taskId,
            code = code,
            reason = reason ?: "No reason provided",
            timeout = timeout,
            dialogKiller = dialogKiller,
            rawParams = params.arguments
        )

        val result = project
            .service<ExecutionManager>()
            .executeWithProgress(execCodeParams)

        runCatching {
            analyticsBeacon.capture(
                event = "exec_code",
                project = project,
                properties = mapOf(
                    "result" to if (result.isError) "error" else "success"
                )
            )
        }

        return result
    }

    private fun errorResult(message: String) = ToolCallResult(
        content = listOf(ContentItem.Text(text = "ERROR: $message")),
        isError = true
    )

    private fun findMissingPlugins(requiredPlugins: List<String>): List<String> {
        if (requiredPlugins.isEmpty()) return emptyList()
        return requiredPlugins.filter { pluginId ->
            val resolvedId = PluginId.getId(pluginId)
            val resolved = PluginManagerCore.getPlugin(resolvedId)
            resolved == null || !PluginManagerCore.isLoaded(resolvedId)
        }
    }

}

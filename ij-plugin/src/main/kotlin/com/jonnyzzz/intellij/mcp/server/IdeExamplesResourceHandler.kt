/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.server

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.jonnyzzz.intellij.mcp.mcp.McpServerCore

/**
 * Handler for IDE power operation examples as MCP resources.
 * Each example is a complete Kotlin script demonstrating advanced
 * IntelliJ IDE operations beyond LSP.
 */
@Service(Service.Level.APP)
class IdeExamplesResourceHandler {

    data class IdeExample(
        val id: String,
        val name: String,
        val description: String,
        val resourceFile: String
    )

    val examples = listOf(
        IdeExample(
            id = "extract-method",
            name = "Extract Method",
            description = """
                Extract selected statements into a new method.

                Shows how to:
                - Build an ExtractMethodProcessor
                - Prepare and execute the refactoring

                IntelliJ APIs: ExtractMethodProcessor, ExtractMethodHandler

                WARNING: Can modify code. Use dryRun=true first!
            """.trimIndent(),
            resourceFile = "/ide-examples/extract-method.kts"
        ),
        IdeExample(
            id = "introduce-variable",
            name = "Introduce Variable",
            description = """
                Extract an expression into a new local variable.

                Shows how to:
                - Locate an expression at a position
                - Use VariableExtractor with custom settings

                IntelliJ APIs: VariableExtractor, IntroduceVariableSettings

                WARNING: Can modify code. Use dryRun=true first!
            """.trimIndent(),
            resourceFile = "/ide-examples/introduce-variable.kts"
        ),
        IdeExample(
            id = "inline-method",
            name = "Inline Method",
            description = """
                Inline a method body at its call sites.

                Shows how to:
                - Locate method declaration or call site
                - Run InlineMethodProcessor

                IntelliJ APIs: InlineMethodProcessor

                WARNING: Can modify code. Use dryRun=true first!
            """.trimIndent(),
            resourceFile = "/ide-examples/inline-method.kts"
        ),
        IdeExample(
            id = "change-signature",
            name = "Change Signature",
            description = """
                Add or reorder parameters and update call sites.

                Shows how to:
                - Build ParameterInfoImpl list
                - Execute ChangeSignatureProcessor

                IntelliJ APIs: ChangeSignatureProcessor, ParameterInfoImpl

                WARNING: Can modify code. Use dryRun=true first!
            """.trimIndent(),
            resourceFile = "/ide-examples/change-signature.kts"
        ),
        IdeExample(
            id = "move-file",
            name = "Move File",
            description = """
                Move a file to another directory and update references.

                Shows how to:
                - Resolve source file and target directory
                - Run MoveFilesOrDirectoriesProcessor

                IntelliJ APIs: MoveFilesOrDirectoriesProcessor

                WARNING: Can modify code. Use dryRun=true first!
            """.trimIndent(),
            resourceFile = "/ide-examples/move-file.kts"
        ),
        IdeExample(
            id = "safe-delete",
            name = "Safe Delete",
            description = """
                Safely delete a method or class with usage analysis.

                Shows how to:
                - Resolve a deletable PSI element
                - Run SafeDeleteProcessor

                IntelliJ APIs: SafeDeleteProcessor, ReferencesSearch

                WARNING: Can modify code. Use dryRun=true first!
            """.trimIndent(),
            resourceFile = "/ide-examples/safe-delete.kts"
        ),
        IdeExample(
            id = "optimize-imports",
            name = "Optimize Imports",
            description = """
                Remove unused imports and sort remaining ones.

                Shows how to:
                - Preview optimized imports via PSI copy
                - Apply changes via JavaCodeStyleManager

                IntelliJ APIs: JavaCodeStyleManager

                WARNING: Can modify code. Use dryRun=true first!
            """.trimIndent(),
            resourceFile = "/ide-examples/optimize-imports.kts"
        ),
        IdeExample(
            id = "generate-override",
            name = "Generate Overrides",
            description = """
                Implement interface methods / override base methods.

                Shows how to:
                - Create method prototypes with OverrideImplementUtil
                - Insert members in class

                IntelliJ APIs: OverrideImplementUtil, GenerateMembersUtil

                WARNING: Can modify code. Use dryRun=true first!
            """.trimIndent(),
            resourceFile = "/ide-examples/generate-override.kts"
        ),
        IdeExample(
            id = "inspect-and-fix",
            name = "Inspection + Quick Fix",
            description = """
                Run a local inspection and apply a quick fix.

                Shows how to:
                - Execute a LocalInspectionTool
                - Apply the first available LocalQuickFix

                IntelliJ APIs: LocalInspectionTool, LocalQuickFix

                WARNING: Can modify code. Use dryRun=true first!
            """.trimIndent(),
            resourceFile = "/ide-examples/inspect-and-fix.kts"
        ),
        IdeExample(
            id = "hierarchy-search",
            name = "Hierarchy Search",
            description = """
                Find inheritors and overriding methods.

                Shows how to:
                - Search class inheritors
                - Search method overrides

                IntelliJ APIs: ClassInheritorsSearch, OverridingMethodsSearch
            """.trimIndent(),
            resourceFile = "/ide-examples/hierarchy-search.kts"
        )
    )

    fun register(server: McpServerCore) {
        server.resourceRegistry.registerResource(
            uri = "intellij://ide/overview",
            name = "IDE Examples Overview",
            description = """
                Overview of IntelliJ IDE power operation examples.

                This resource lists runnable scripts for advanced IDE operations
                such as refactorings, inspections, and code generation.
            """.trimIndent(),
            mimeType = "text/markdown",
            contentProvider = ::loadOverview
        )

        examples.forEach { example ->
            server.resourceRegistry.registerResource(
                uri = "intellij://ide/${example.id}",
                name = "IDE: ${example.name}",
                description = example.description,
                mimeType = "text/x-kotlin",
                contentProvider = { loadExample(example.resourceFile) }
            )
        }
    }

    fun loadOverview(): String {
        return javaClass.getResourceAsStream("/ide-examples/IDE_OVERVIEW.md")
            ?.bufferedReader()
            ?.readText()
            ?: error("IDE_OVERVIEW.md resource is not found")
    }

    fun loadExample(resourceFile: String): String {
        return javaClass.getResourceAsStream(resourceFile)
            ?.bufferedReader()
            ?.readText()
            ?: error("IDE example resource is not found: $resourceFile")
    }
}

inline val ideExamplesResourceHandler: IdeExamplesResourceHandler get() = service()

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
class IdeExamplesResourceHandler : McpRegistrar {

    data class IdeExample(
        val id: String,
        val name: String,
        val description: String,
        val resourceFile: String
    )

    //TODO: it must work different way, it must just list resources
    //TODO: which we have in the plugin (use PluginDescriptorProvider alike to resolve path)
    //TODO: each resource entry must have header and the content
    //TODO: we parse the header to include each resource
    //TODO: thus there must be no hardcoded text like it is now.
    //TODO: Alternative is to add file names conveision -- the .kts is the resource, the .md is the information (event easier to implement)
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
        ),
        IdeExample(
            id = "call-hierarchy",
            name = "Call Hierarchy (Find Callers)",
            description = """
                Find callers for a method (call hierarchy view).

                Shows how to:
                - Resolve a method at a position
                - Find references to the method
                - Summarize call sites with location info

                IntelliJ APIs: MethodReferencesSearch, PsiMethod, PsiTreeUtil
            """.trimIndent(),
            resourceFile = "/ide-examples/call-hierarchy.kts"
        ),
        IdeExample(
            id = "run-configuration",
            name = "Run Configuration",
            description = """
                List and optionally execute run configurations.

                Shows how to:
                - Enumerate available run configurations
                - Resolve a configuration by name
                - Execute with a selected executor

                IntelliJ APIs: RunManager, ProgramRunnerUtil, ExecutorRegistry
            """.trimIndent(),
            resourceFile = "/ide-examples/run-configuration.kts"
        ),
        IdeExample(
            id = "pull-up-members",
            name = "Pull Up Members",
            description = """
                Pull members from subclass into a base class.

                Shows how to:
                - Resolve source/target classes
                - Select members via MemberInfo
                - Run PullUpProcessor

                IntelliJ APIs: PullUpProcessor, MemberInfo, DocCommentPolicy

                WARNING: Can modify code. Use dryRun=true first!
            """.trimIndent(),
            resourceFile = "/ide-examples/pull-up-members.kts"
        ),
        IdeExample(
            id = "push-down-members",
            name = "Push Down Members",
            description = """
                Push members from a base class into subclasses.

                Shows how to:
                - Resolve source class
                - Select members via MemberInfo
                - Run PushDownProcessor

                IntelliJ APIs: PushDownProcessor, MemberInfo, DocCommentPolicy

                WARNING: Can modify code. Use dryRun=true first!
            """.trimIndent(),
            resourceFile = "/ide-examples/push-down-members.kts"
        ),
        IdeExample(
            id = "extract-interface",
            name = "Extract Interface",
            description = """
                Extract an interface from a class.

                Shows how to:
                - Resolve a class and target directory
                - Select members for the new interface
                - Run ExtractInterfaceProcessor

                IntelliJ APIs: ExtractInterfaceProcessor, MemberInfo

                WARNING: Can modify code. Use dryRun=true first!
            """.trimIndent(),
            resourceFile = "/ide-examples/extract-interface.kts"
        ),
        IdeExample(
            id = "move-class",
            name = "Move Class / Package",
            description = """
                Move a class to another package and update references.

                Shows how to:
                - Resolve class and destination package
                - Create MoveDestination
                - Run MoveClassesOrPackagesProcessor

                IntelliJ APIs: MoveClassesOrPackagesProcessor, PackageWrapper

                WARNING: Can modify code. Use dryRun=true first!
            """.trimIndent(),
            resourceFile = "/ide-examples/move-class.kts"
        ),
        IdeExample(
            id = "generate-constructor",
            name = "Generate Constructor",
            description = """
                Generate a constructor from selected fields.

                Shows how to:
                - Locate fields in a class
                - Build constructor prototype
                - Insert generated member into class

                IntelliJ APIs: GenerateConstructorHandler, GenerateMembersUtil

                WARNING: Can modify code. Use dryRun=true first!
            """.trimIndent(),
            resourceFile = "/ide-examples/generate-constructor.kts"
        ),
        IdeExample(
            id = "project-dependencies",
            name = "Project Dependencies",
            description = """
                Summarize module dependencies and libraries.

                Shows how to:
                - Enumerate modules
                - List order entries (modules, libraries, SDKs)

                IntelliJ APIs: ModuleManager, ModuleRootManager, OrderEnumerator
            """.trimIndent(),
            resourceFile = "/ide-examples/project-dependencies.kts"
        ),
        IdeExample(
            id = "inspection-summary",
            name = "Inspection Summary",
            description = """
                List enabled inspections and provide a quick summary.

                Shows how to:
                - Access inspection profiles
                - Enumerate enabled inspections

                IntelliJ APIs: InspectionProjectProfileManager, InspectionProfile
            """.trimIndent(),
            resourceFile = "/ide-examples/inspection-summary.kts"
        ),
        IdeExample(
            id = "project-search",
            name = "Project Search (Index)",
            description = """
                Search files by name or file type via indices.

                Shows how to:
                - Use FilenameIndex for file name queries
                - Use FileTypeIndex for extension queries

                IntelliJ APIs: FilenameIndex, FileTypeIndex
            """.trimIndent(),
            resourceFile = "/ide-examples/project-search.kts"
        )
    )

    override fun register(server: McpServerCore) {
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

/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.server

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test

/**
 * Tests that verify LSP example resources are correctly loaded and contain valid content.
 * These tests verify the resource files exist and contain expected elements.
 */
class LspExamplesResourceTest : BasePlatformTestCase() {

    private val handler = LspExamplesResourceHandler()

    fun testOverviewResourceLoads() {
        val overview = handler.loadOverview()
        assertNotNull("Overview should not be null", overview)
        assertTrue("Overview should contain title", overview.contains("LSP-like Operations"))
        assertTrue("Overview should contain examples", overview.contains("textDocument/definition"))
    }

    fun testAllExamplesAreDefined() {
        val examples = handler.examples
        assertEquals("Should have 10 LSP examples", 10, examples.size)

        val expectedIds = listOf(
            "go-to-definition",
            "find-references",
            "hover",
            "completion",
            "document-symbols",
            "rename",
            "formatting",
            "code-action",
            "signature-help",
            "workspace-symbol"
        )

        expectedIds.forEach { id ->
            assertTrue("Should have example: $id", examples.any { it.id == id })
        }
    }

    fun testGoToDefinitionLoads() {
        val content = handler.loadExample("/lsp-examples/go-to-definition.kts")
        assertNotNull("Content should not be null", content)
        assertTrue("Should have LSP method comment", content.contains("textDocument/definition"))
        assertTrue("Should have execute block", content.contains("execute {"))
        assertTrue("Should use PsiManager", content.contains("PsiManager"))
    }

    fun testFindReferencesLoads() {
        val content = handler.loadExample("/lsp-examples/find-references.kts")
        assertNotNull("Content should not be null", content)
        assertTrue("Should have LSP method comment", content.contains("textDocument/references"))
        assertTrue("Should have execute block", content.contains("execute {"))
        assertTrue("Should use ReferencesSearch", content.contains("ReferencesSearch"))
    }

    fun testHoverLoads() {
        val content = handler.loadExample("/lsp-examples/hover.kts")
        assertNotNull("Content should not be null", content)
        assertTrue("Should have LSP method comment", content.contains("textDocument/hover"))
        assertTrue("Should have execute block", content.contains("execute {"))
        assertTrue("Should mention DocumentationProvider", content.contains("DocumentationProvider"))
    }

    fun testCompletionLoads() {
        val content = handler.loadExample("/lsp-examples/completion.kts")
        assertNotNull("Content should not be null", content)
        assertTrue("Should have LSP method comment", content.contains("textDocument/completion"))
        assertTrue("Should have execute block", content.contains("execute {"))
        assertTrue("Should mention CompletionContributor", content.contains("CompletionContributor"))
    }

    fun testDocumentSymbolsLoads() {
        val content = handler.loadExample("/lsp-examples/document-symbols.kts")
        assertNotNull("Content should not be null", content)
        assertTrue("Should have LSP method comment", content.contains("textDocument/documentSymbol"))
        assertTrue("Should have execute block", content.contains("execute {"))
        assertTrue("Should use StructureViewBuilder", content.contains("StructureViewBuilder"))
    }

    fun testRenameLoads() {
        val content = handler.loadExample("/lsp-examples/rename.kts")
        assertNotNull("Content should not be null", content)
        assertTrue("Should have LSP method comment", content.contains("textDocument/rename"))
        assertTrue("Should have execute block", content.contains("execute {"))
        assertTrue("Should use RefactoringFactory", content.contains("RefactoringFactory"))
        assertTrue("Should have dryRun option", content.contains("dryRun"))
    }

    fun testFormattingLoads() {
        val content = handler.loadExample("/lsp-examples/formatting.kts")
        assertNotNull("Content should not be null", content)
        assertTrue("Should have LSP method comment", content.contains("textDocument/formatting"))
        assertTrue("Should have execute block", content.contains("execute {"))
        assertTrue("Should use CodeStyleManager", content.contains("CodeStyleManager"))
        assertTrue("Should have dryRun option", content.contains("dryRun"))
    }

    fun testCodeActionLoads() {
        val content = handler.loadExample("/lsp-examples/code-action.kts")
        assertNotNull("Content should not be null", content)
        assertTrue("Should have LSP method comment", content.contains("textDocument/codeAction"))
        assertTrue("Should have execute block", content.contains("execute {"))
        assertTrue("Should use IntentionManager", content.contains("IntentionManager"))
    }

    fun testSignatureHelpLoads() {
        val content = handler.loadExample("/lsp-examples/signature-help.kts")
        assertNotNull("Content should not be null", content)
        assertTrue("Should have LSP method comment", content.contains("textDocument/signatureHelp"))
        assertTrue("Should have execute block", content.contains("execute {"))
        assertTrue("Should mention ParameterInfoHandler", content.contains("ParameterInfoHandler"))
    }

    fun testWorkspaceSymbolLoads() {
        val content = handler.loadExample("/lsp-examples/workspace-symbol.kts")
        assertNotNull("Content should not be null", content)
        assertTrue("Should have LSP method comment", content.contains("workspace/symbol"))
        assertTrue("Should have execute block", content.contains("execute {"))
        assertTrue("Should use PsiShortNamesCache", content.contains("PsiShortNamesCache"))
    }

    fun testAllExamplesHaveRequiredStructure() {
        handler.examples.forEach { example ->
            val content = handler.loadExample(example.resourceFile)
            assertNotNull("${example.id} should load", content)

            // All examples should have the doc comment header
            assertTrue("${example.id} should have LSP method in header",
                content.contains(example.lspMethod))

            // All examples should have execute block
            assertTrue("${example.id} should have execute block",
                content.contains("execute {"))

            // All examples should have waitForSmartMode
            assertTrue("${example.id} should call waitForSmartMode",
                content.contains("waitForSmartMode()"))

            // All examples should use readAction or writeAction
            assertTrue("${example.id} should have read/write action",
                content.contains("readAction") || content.contains("writeAction"))

            // All examples should have a configuration section (filePath or query)
            assertTrue("${example.id} should have config (filePath or query)",
                content.contains("filePath") || content.contains("query"))
        }
    }

    fun testExampleDescriptionsAreUseful() {
        handler.examples.forEach { example ->
            assertTrue("${example.id} description should not be empty",
                example.description.isNotBlank())

            assertTrue("${example.id} description should have meaningful content (>50 chars)",
                example.description.length > 50)

            // Descriptions should mention IntelliJ APIs
            assertTrue("${example.id} description should mention IntelliJ API",
                example.description.contains("IntelliJ") ||
                example.description.contains("Psi") ||
                example.description.contains("API"))
        }
    }
}

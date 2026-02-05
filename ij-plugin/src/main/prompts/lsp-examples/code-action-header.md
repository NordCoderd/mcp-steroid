LSP: textDocument/codeAction - Code Actions / Quick Fixes

This example demonstrates how to get available code actions (quick fixes,
refactorings) at a given position, similar to Alt+Enter in IDEs.

IntelliJ API used:
- ShowIntentionActionsHandler - Get available intentions
- IntentionManager - List registered intentions
- LocalInspectionTool - Run inspections to find problems
- QuickFix - Fixes for inspection problems

Parameters to customize:
- filePath: Absolute path to the file
- line/column: Position to get code actions for

Output: List of available code actions

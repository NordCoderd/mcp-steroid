LSP: textDocument/definition - Go to Definition

This example demonstrates how to find the definition of a symbol
at a specific position in a file, similar to Ctrl+Click or F12 in IDEs.

IntelliJ API used:
- PsiManager.findFile() - Get PSI tree for a file
- PsiFile.findElementAt() - Find element at offset
- PsiElement.references / PsiReference.resolve() - Follow reference to definition
- GotoDeclarationHandler - IDE's "Go to Declaration" infrastructure

Parameters to customize:
- filePath: Absolute path to the file
- offset: Character offset in the file (0-based)

Output: Definition location (file path and line number)

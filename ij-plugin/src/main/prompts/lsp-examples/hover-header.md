LSP: textDocument/hover - Hover Information

This example demonstrates how to get documentation and type information
for a symbol at a given position, similar to hovering over code in the IDE.

IntelliJ API used:
- DocumentationManager - Get documentation for elements
- PsiElement.getReference().resolve() - Find the target element
- TypeProvider / PsiType - Get type information

Parameters to customize:
- filePath: Absolute path to the file
- line/column: Position to get hover info for

Output: Documentation and/or type information

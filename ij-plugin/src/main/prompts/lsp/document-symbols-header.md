LSP: textDocument/documentSymbol - Document Symbols

This example demonstrates how to list all symbols (classes, functions,
variables, etc.) in a document, similar to the Structure view in IDEs.

IntelliJ API used:
- StructureViewBuilder - Build structure view for a file
- PsiTreeUtil - Navigate PSI tree
- PsiNamedElement - Elements with names (classes, methods, etc.)

Parameters to customize:
- filePath: Absolute path to the file

Output: Hierarchical list of symbols in the document

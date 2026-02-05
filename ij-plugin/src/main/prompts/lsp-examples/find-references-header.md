LSP: textDocument/references - Find All References

This example demonstrates how to find all references to a symbol,
similar to "Find Usages" (Alt+F7) in IDEs.

IntelliJ API used:
- ReferencesSearch.search() - Find all references to a PSI element
- PsiReference.getElement() - Get the referencing element

Parameters to customize:
- filePath: Path to file containing the symbol definition
- line/column: Position of the symbol to find references for

Output: List of all reference locations

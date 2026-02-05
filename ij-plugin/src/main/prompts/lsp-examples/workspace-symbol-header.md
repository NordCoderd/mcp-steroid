LSP: workspace/symbol - Workspace Symbol Search

This example demonstrates how to search for symbols across the entire
workspace/project, similar to Ctrl+N (Go to Class) or Ctrl+Shift+N
(Go to File) in IDEs.

IntelliJ API used:
- GotoClassModel2 - Search for classes
- GotoSymbolModel2 - Search for all symbols
- PsiShortNamesCache - Fast lookup by short name
- AllClassesSearch - Search all classes

Parameters to customize:
- query: Search pattern (supports camelCase matching)
- searchType: "class", "symbol", or "file"
- maxResults: Maximum number of results

Output: List of matching symbols with their locations

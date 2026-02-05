LSP: textDocument/completion - Code Completion

This example demonstrates how to get code completion suggestions
at a given position, similar to pressing Ctrl+Space in the IDE.

IntelliJ API used:
- CompletionService - Core completion infrastructure
- CompletionParameters - Parameters for completion request
- LookupElement - Individual completion item

Parameters to customize:
- filePath: Absolute path to the file
- line/column: Position to get completions for

Output: List of completion suggestions

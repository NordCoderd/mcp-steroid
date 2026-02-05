LSP: textDocument/rename - Rename Symbol

This example demonstrates how to rename a symbol across the project,
similar to Shift+F6 in IDEs.

IntelliJ API used:
- RefactoringFactory.createRename() - Create rename refactoring
- RenamePsiElementProcessor - Handle language-specific rename logic
- RenameHandler - IDE's rename infrastructure

Parameters to customize:
- filePath: Path to file containing the symbol
- line/column: Position of the symbol to rename
- newName: The new name for the symbol

Output: Preview of rename changes (or performs rename if dryRun=false)

WARNING: This modifies code. Use dryRun=true to preview changes first.

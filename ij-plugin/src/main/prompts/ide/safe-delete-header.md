IDE: Safe Delete

This example safely deletes a method or class, similar to "Refactor | Safe Delete".

IntelliJ API used:
- SafeDeleteProcessor
- ReferencesSearch (for preview)

Parameters to customize:
- filePath: Absolute path to the file
- line/column: Position inside method or class to delete
- dryRun: Preview only (no changes)

Output: Summary of delete operation or error message

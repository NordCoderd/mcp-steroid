IDE: Extract Method

This example extracts a range of statements into a new method,
similar to "Refactor | Extract Method" in the IDE.

IntelliJ API used:
- ExtractMethodProcessor / ExtractMethodHandler
- EditorFactory for a temporary editor

Parameters to customize:
- filePath: Absolute path to the file
- startLine/endLine: 1-based line range to extract
- newMethodName: Name for the extracted method
- dryRun: Preview only (no changes)

Output: Summary of extraction or error message

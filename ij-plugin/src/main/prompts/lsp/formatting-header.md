LSP: textDocument/formatting - Format Document

This example demonstrates how to format an entire document
according to the project's code style settings.

IntelliJ API used:
- CodeStyleManager.reformat() - Format PSI elements
- CodeStyleManager.reformatText() - Format text range

Parameters to customize:
- filePath: Path to the file to format
- dryRun: Preview changes without modifying the file

Output: Formatted code or diff showing changes

WARNING: This modifies code. Use dryRun=true to preview changes first.

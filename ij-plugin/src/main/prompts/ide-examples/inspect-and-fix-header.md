IDE: Inspection + Quick Fix

This example runs a local inspection and applies a quick fix,
similar to "Code | Inspect Code" + Alt+Enter.

IntelliJ API used:
- LocalInspectionTool.checkFile()
- LocalQuickFix.applyFix()

Parameters to customize:
- filePath: Absolute path to the file
- dryRun: Preview only (no changes)

Output: Inspection results and fix status

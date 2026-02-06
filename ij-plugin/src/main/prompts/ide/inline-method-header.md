IDE: Inline Method

This example inlines a method body at call sites,
similar to "Refactor | Inline".

IntelliJ API used:
- InlineMethodProcessor
- EditorFactory for a temporary editor

Parameters to customize:
- filePath: Absolute path to the file
- line/column: Position inside method call or declaration
- inlineThisOnly: Inline only the selected call site
- deleteDeclaration: Remove the original method after inlining
- dryRun: Preview only (no changes)

Output: Summary of inline operation or error message

IDE: Introduce Variable

This example extracts an expression into a new local variable,
similar to "Refactor | Introduce Variable".

IntelliJ API used:
- IntroduceVariableHandler
- IntroduceVariableSettings

Parameters to customize:
- filePath: Absolute path to the file
- line/column: Position inside the expression
- newVariableName: Name for the extracted variable
- dryRun: Preview only (no changes)

Output: Summary of extraction or error message

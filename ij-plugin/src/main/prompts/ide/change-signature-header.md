IDE: Change Signature

This example updates a method signature (adds a parameter)
and updates call sites, similar to "Refactor | Change Signature".

IntelliJ API used:
- ChangeSignatureProcessor
- ParameterInfoImpl

Parameters to customize:
- filePath: Absolute path to the file
- line/column: Position inside method declaration or call
- newParameterName/type/defaultValue: Parameter to add
- dryRun: Preview only (no changes)

Output: Summary of change or error message

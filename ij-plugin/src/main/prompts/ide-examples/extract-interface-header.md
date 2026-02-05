IDE: Extract Interface

This example extracts an interface from a class,
similar to "Refactor | Extract Interface".

IntelliJ API used:
- ExtractInterfaceProcessor
- MemberInfo
- DocCommentPolicy

Parameters to customize:
- sourceClassFqn: Fully-qualified name of the class
- interfaceName: Name of the new interface
- targetDirPath: Directory where the interface should be created
- memberName: Member (method) to extract
- dryRun: Preview only (no changes)

Output: Summary of extraction

WARNING: This modifies code. Use dryRun=true to preview changes first.

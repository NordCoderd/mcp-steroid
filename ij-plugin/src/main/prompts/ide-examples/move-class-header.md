IDE: Move Class / Package

This example moves a class to another package and updates references,
similar to "Refactor | Move".

IntelliJ API used:
- MoveClassesOrPackagesProcessor
- PackageWrapper
- SingleSourceRootMoveDestination

Parameters to customize:
- classFqn: Fully-qualified name of the class to move
- targetPackage: New package name
- targetDirPath: Directory for the target package
- dryRun: Preview only (no changes)

Output: Summary of move operation

WARNING: This modifies code. Use dryRun=true to preview changes first.

IDE: Pull Up Members

This example pulls a member from a subclass into a base class,
similar to "Refactor | Pull Members Up".

IntelliJ API used:
- PullUpProcessor
- MemberInfo
- DocCommentPolicy

Parameters to customize:
- sourceClassFqn: Fully-qualified name of the subclass
- targetClassFqn: Fully-qualified name of the base class
- memberName: Member (method/field) to pull up
- dryRun: Preview only (no changes)

Output: Summary of pull up operation

WARNING: This modifies code. Use dryRun=true to preview changes first.

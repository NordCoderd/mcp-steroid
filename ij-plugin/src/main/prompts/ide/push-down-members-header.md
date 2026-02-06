IDE: Push Down Members

This example pushes a member from a base class into its subclasses,
similar to "Refactor | Push Members Down".

IntelliJ API used:
- PushDownProcessor
- MemberInfo
- DocCommentPolicy

Parameters to customize:
- sourceClassFqn: Fully-qualified name of the base class
- memberName: Member (method/field) to push down
- dryRun: Preview only (no changes)

Output: Summary of push down operation

WARNING: This modifies code. Use dryRun=true to preview changes first.

IDE: Call Hierarchy (Find Callers)

This example finds call sites for a method, similar to the
"Call Hierarchy" tool window.

IntelliJ API used:
- MethodReferencesSearch - Find method call references
- PsiMethod - Target method resolution
- PsiTreeUtil - Walk PSI tree to locate methods

Parameters to customize:
- filePath: Absolute path to the file
- line/column: Position inside the method declaration or call
- maxResults: Limit the number of call sites shown

Output: List of callers and their locations

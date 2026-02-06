LSP: textDocument/signatureHelp - Signature Help

This example demonstrates how to get function signature information
when the cursor is inside function call parentheses.

IntelliJ API used:
- ParameterInfoHandler - Get parameter info for function calls
- PsiElement navigation to find enclosing call expression

Parameters to customize:
- filePath: Absolute path to the file
- line/column: Position inside function call parentheses

Output: Function signature with parameter information

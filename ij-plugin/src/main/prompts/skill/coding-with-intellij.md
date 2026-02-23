# Coding with IntelliJ APIs - Comprehensive Guide for AI Agents

This guide teaches you how to write effective Kotlin code that executes inside IntelliJ IDEA's runtime environment via `steroid_execute_code`. You'll learn the execution model, available APIs, and best practices for working with PSI (Program Structure Interface), VFS (Virtual File System), and other IntelliJ platform APIs.

## Sections

- [Introduction & Execution Model](mcp-steroid://skill/coding-with-intellij-intro) — Script structure, coroutine context, helper function rules
- [McpScriptContext API Reference](mcp-steroid://skill/coding-with-intellij-context-api) — project, output methods, readAction/writeAction, file helpers
- [Threading and Read/Write Actions](mcp-steroid://skill/coding-with-intellij-threading) — Threading model, smart mode, modal dialogs
- [Common Patterns](mcp-steroid://skill/coding-with-intellij-patterns) — Project info, plugin discovery, file navigation
- [PSI Operations & Code Analysis](mcp-steroid://skill/coding-with-intellij-psi) — PSI tree navigation, find usages, inspections
- [Document, Editor & VFS Operations](mcp-steroid://skill/coding-with-intellij-vfs) — Document/editor manipulation, VFS read/write
- [Java & Spring Boot Patterns](mcp-steroid://skill/coding-with-intellij-spring) — Maven/Gradle, Spring annotations, test execution
- [Refactoring, Services & Best Practices](mcp-steroid://skill/coding-with-intellij-refactoring) — Refactoring, services, error handling, quick reference

## Quick Reference

**The IDE knows the code better than any file search tool.**

| Instead of... | Use IntelliJ API | Why? |
|--------------|------------------|------|
| `grep`, `find` | PSI search, Find Usages | Understands code structure |
| Reading files with `cat` | VFS and PSI APIs | Respects IDE's caching |
| Manual text replacement | Refactoring APIs | Maintains code correctness |
| Guessing code structure | Query project model | IDE has already indexed everything |

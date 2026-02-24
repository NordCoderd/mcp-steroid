## Introduction

### What is steroid_execute_code?

`steroid_execute_code` is an MCP tool that executes Kotlin code directly inside IntelliJ IDEA's JVM. Your code runs with full access to:

- **Project model** - modules, dependencies, source roots
- **PSI (Program Structure Interface)** - parsed code representation
- **VFS (Virtual File System)** - file access layer
- **IntelliJ indices** - fast code search and navigation
- **Editor APIs** - document manipulation, caret position
- **Refactoring APIs** - automated code transformations
- **Inspection APIs** - code quality analysis

### Why Use IntelliJ APIs Over File Operations?

| Instead of... | Use IntelliJ API | Why? |
|--------------|------------------|------|
| `grep`, `find` | PSI search, Find Usages | Understands code structure, not just text |
| Reading files with `cat` | VFS and PSI APIs | Respects IDE's caching and encoding |
| Manual text replacement | Refactoring APIs | Maintains code correctness and formatting |
| Guessing code structure | Query project model | IDE has already indexed everything |

**The IDE knows the code better than any file search tool.**

### Learning Curve

**Important**: Writing IntelliJ API code may require several attempts. This is normal! The API surface is vast and powerful. Keep trying - each attempt teaches you more about the available APIs.

- Use `printException(msg, throwable)` to see full stack traces
- Check return types and nullability
- Use reflection to discover available methods
- Consult the [IntelliJ Platform SDK docs](https://plugins.jetbrains.com/docs/intellij/)

---

## Execution Model

### Script Structure

Your code is the **suspend function body**. You do NOT need an `execute { }` wrapper.

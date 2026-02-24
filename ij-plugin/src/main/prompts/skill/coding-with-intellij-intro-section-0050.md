
> **Bulk file creation triggers re-indexing**: Writing new files via `writeAction { VfsUtil.saveText(...) }` causes IntelliJ to re-index those files.
> - **In a subsequent exec_code call**: Safe — `waitForSmartMode()` runs automatically at script start, so PSI is up-to-date by the time your code runs.
> - **In the same exec_code call** (create files then immediately inspect them): call `waitForSmartMode()` explicitly after the `writeAction` block and before any `runInspectionsDirectly` / `ReferencesSearch` / `JavaPsiFacade.findClass()` calls on the new files.
>
> ```kotlin
> // Pattern: create files AND inspect in the SAME exec_code call
> writeAction {
>     val root = LocalFileSystem.getInstance().findFileByPath(project.basePath!!)!!
>     val dir = VfsUtil.createDirectoryIfMissing(root, "src/main/java/com/example")
>     val f = dir.findChild("MyService.java") ?: dir.createChildData(this, "MyService.java")
>     VfsUtil.saveText(f, "package com.example;\npublic class MyService {}")
> }
> waitForSmartMode()  // ← flush PSI index before inspecting the newly created file
> val vf = findProjectFile("src/main/java/com/example/MyService.java")!!
> val problems = runInspectionsDirectly(vf)
> println(if (problems.isEmpty()) "OK" else problems.toString())
> ```
>
> **Best practice**: Create files in one exec_code call, then inspect in a separate exec_code call — `waitForSmartMode()` runs automatically between calls.
>
> **⚠️ Create one file per exec_code call** when possible. Bundling multiple file creations in a single call makes error attribution hard: if the call throws an exception midway, it's unclear which files were created and which failed. Create files one at a time, verify existence (`findProjectFile(path) != null`), then proceed to the next.

### Execution Flow

1. **Submit code** via `steroid_execute_code`
2. **Review phase** (if enabled) - human approval
3. **Compilation** - Kotlin script engine compiles your code
   - Fast failure if compilation errors occur
4. **Execution** - Your script body runs with timeout
   - Progress messages throttled to 1/second
   - Context disposed when complete
5. **Response** - Output returned to MCP client

### Fast Failure

Errors are reported immediately (no waiting for timeout):

- **Script engine not available** → ERROR immediately
- **Compilation errors** → ERROR with details immediately
- **Runtime errors** → ERROR with stack trace
- **Timeout** → Execution cancelled, resources cleaned up

---

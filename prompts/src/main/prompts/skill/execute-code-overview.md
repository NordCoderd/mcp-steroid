Execute Code: Overview & Key Rules

Intro, when to use steroid_execute_code vs native tools, file creation rule, retry rule, and Read/Edit constraint for steroid_execute_code.

# Execute Code: Overview & Key Rules

WHAT: Finally SEE IntelliJ-based IDEs — not just read code. The only MCP server with visual understanding and full IDE control.
HOW: Execute Kotlin code directly in IntelliJ's runtime with full API access.

This is a **stateful** API — everything you do changes the IDE state. The IntelliJ IDE is running exclusively for you. Use it aggressively instead of manual file operations.

---

## ⚡ Bypasses Agent Sandbox

Scripts run inside IntelliJ's JVM — unrestricted filesystem access to `/mcp-run-dir/` and the project. Use `steroid_execute_code` for IntelliJ-specific operations (PSI, indexing, VCS, compile checks, find usages). For plain file reads/writes, prefer the native Read/Write tools — they have zero JVM compilation overhead (~12s saved per call). **To run Maven/Gradle tests, use the Bash tool** (`./mvnw test -Dtest=...`) — do NOT run `ProcessBuilder("./mvnw")` inside steroid_execute_code.

---

## EXCEPTION — Use Native Read Tool for Simple File Reads

The native Read tool can access `/mcp-run-dir/` paths directly. For reading a single file's content, prefer the Read tool over `String(vf.contentsToByteArray(), vf.charset)` — it's faster (no compilation overhead). Reserve `steroid_execute_code` for operations that **REQUIRE** IntelliJ APIs: PSI analysis, compilation checks, test execution, find usages, refactoring, VCS inspection. If you just need to read file text, use the Read tool.

**BANNED: Using steroid_execute_code to read files you know the path of.**

```kotlin
// ❌ BANNED — costs 8-15s for zero benefit
val content = String(findProjectFile("src/main/java/Foo.java")!!.contentsToByteArray())
// ✅ CORRECT — use Read tool directly (0s overhead)
// Read: src/main/java/Foo.java
```

This mistake caused 15-80s overhead in case analyses. The Read tool reads files in ~0s.
steroid_execute_code compiles and runs Kotlin, adding 8-15s per call.

---

## ⚡ File Creation: Use the Write Tool (NOT steroid_execute_code)

**Creating files via steroid_execute_code (`writeAction { VfsUtil.saveText(...) }`) is measured +47% slower**
than using the native Write tool — IntelliJ VFS overhead dominates file creation time.

**Use the Write tool for all file creation.** After writing files, trigger a VFS refresh
if you need IntelliJ to index them for PSI/compilation:

```kotlin
// ONE steroid_execute_code call to refresh after bulk file creation via Write tool:
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.LocalFileSystem
VfsUtil.markDirtyAndRefresh(false, true, true,
    LocalFileSystem.getInstance().findFileByPath(project.basePath!!)
)
println("VFS refreshed — IntelliJ now sees new files")
```

**Exception**: Use steroid_execute_code VFS file creation ONLY when you must create a file AND
immediately use PSI on it in the same steroid_execute_code call (e.g., create + run inspections atomically).

---

## ⚡ After a steroid_execute_code Error: Diagnose and RETRY — Do NOT Fall Back to Native Tools

A single steroid_execute_code failure does NOT mean steroid_execute_code is unreliable. Read the error message — it is almost always one of:
- **(a)** Missing import (`unresolved reference 'GlobalSearchScope'` → add `import com.intellij.psi.search.GlobalSearchScope`)
- **(b)** Threading violation (`Write access is allowed inside write-action only` → wrap in `writeAction { }`)
- **(c)** Kotlin string syntax issue (`.class` reference or `$` in a double-quoted string → switch to triple-quoted strings)

Fix the issue and resubmit. Switching to native Write/Bash after one steroid_execute_code failure bypasses IDE indexing and eliminates compile verification — **always retry steroid_execute_code first**.

---

## ⚠️ steroid_execute_code VFS Reads Do NOT Satisfy the Native Edit Tool's Read-Before-Write Constraint

If you read a file via steroid_execute_code (`String(vf.contentsToByteArray(), vf.charset)`) and then try to use the native `Edit` tool on the same file, you will get `"File has not been read yet"`. These are tracked separately.

Options:
- **(a)** Also issue a native `Read` tool call for that file before using `Edit`
- **(b)** Use a `writeAction { }` block in steroid_execute_code to both read and write the file atomically — **PREFERRED** (saves a round-trip)

If you plan to modify a file via steroid_execute_code `writeAction { }`, do NOT also issue a native `Read` for that file — it wastes a turn and provides zero benefit. steroid_execute_code can read and write in a single call:
```kotlin
import com.intellij.openapi.vfs.VfsUtil

val vf = findProjectFile("src/main/java/com/example/MyClass.java")!!
val content = String(vf.contentsToByteArray(), vf.charset)  // read OUTSIDE writeAction
val updated = content.replace("oldMethod", "newMethod")
check(updated != content) { "replace matched nothing — check whitespace" }
writeAction { VfsUtil.saveText(vf, updated) }  // write INSIDE writeAction
// ↑ This replaces both Read + Edit tools in a single steroid_execute_code call
```

---

## ❌ BANNED: ProcessBuilder for Builds and Tests

**`ProcessBuilder("./mvnw", ...)` and `ProcessBuilder("./gradlew", ...)` inside `steroid_execute_code` are BANNED** for build and test execution.

**Why it's harmful:**
- Spawns an external child process from within IntelliJ's JVM — bypasses all IDE process management
- Causes classpath conflicts (child Maven/Gradle inherits IDE's classpath)
- Produces 200k+ character output → MCP token limit overflow → lost agent turns
- Prevents IntelliJ from tracking the build lifecycle

**What's allowed instead:**

| Task | Use instead of ProcessBuilder |
|------|-------------------------------|
| Run Maven tests | `MavenRunConfigurationType.runConfiguration()` |
| Run Gradle tests | `ExternalSystemUtil.runTask()` with `GradleConstants.SYSTEM_ID` |
| Maven re-import after pom.xml edit | `MavenProjectsManager.scheduleUpdateAllMavenProjects()` + `Observation.awaitConfiguration()` |
| Check Docker availability | `java.io.File("/var/run/docker.sock").exists()` — no process spawn needed |
| Docker inspect/exec operations | **Bash tool** (outside steroid_execute_code) — e.g. `docker inspect`, `docker exec` |
| `dependency:resolve` workaround | `MavenProjectsManager.getInstance(project).forceUpdateAllProjectsOrFindAllAvailablePomFiles()` |

**`GeneralCommandLine("docker", ...)` and `ProcessBuilder("docker", ...)` inside steroid_execute_code are BANNED** — same reason as `./mvnw`: child process inside IDE JVM causes classpath conflicts. Use the Bash tool outside steroid_execute_code instead.

**ProcessBuilder("./mvnw") is permitted ONLY when:**
1. pom.xml was just modified in this session, AND
2. Maven sync was already triggered (`scheduleUpdateAllMavenProjects` + `awaitConfiguration`), AND
3. `MavenRunConfigurationType.runConfiguration()` with `dialog_killer: true` has already timed out (>2 min)

---

## ⚠️ Do NOT Configure JDKs/SDKs or Install Plugins

This environment is pre-configured with the correct JDK and all required plugins. Do NOT call `ProjectJdkTable`, search hardcoded JVM paths, or attempt any JDK/SDK configuration — these calls either throw at runtime or silently do nothing, wasting a turn. Do NOT attempt `PluginsAdvertiser`, `PluginInstaller`, `PluginManagerCore` write APIs, or reflection-based plugin installation.

To check if a plugin is already installed (without installing it):
```kotlin
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
val pluginId = PluginId.getId("org.jetbrains.kotlin")
val installed = PluginManagerCore.isPluginInstalled(pluginId)
println("Plugin installed: $installed")
// If not installed: report the plugin ID and stop — do NOT attempt programmatic installation
```

---

## ⚠️ NO AUTO-IMPORTS — Every IntelliJ Class Must Be Imported Explicitly

A missing import produces `unresolved reference` (sometimes misleadingly as a type-inference error) and wastes a full retry turn. Common imports not auto-added by the preprocessor:
```kotlin[IU]
import com.intellij.psi.search.FilenameIndex        // getVirtualFilesByName, getAllFilesByExt
import com.intellij.psi.search.GlobalSearchScope    // projectScope(), allScope()
import com.intellij.openapi.roots.ProjectRootManager // contentSourceRoots
import com.intellij.openapi.vfs.VfsUtil              // saveText(), createDirectoryIfMissing()
import com.intellij.psi.search.PsiShortNamesCache   // allClassNames
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.search.searches.ReferencesSearch
```

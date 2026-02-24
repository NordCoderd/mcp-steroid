## Java / Spring Boot Patterns

> **Step -1 — Combined startup call: readiness + Docker + VCS in ONE exec_code call**
>
> For any Spring Boot / Maven task, combine your first three checks into a single call.
> This saves ~60s (3 round-trips × ~20s each) and gives you everything you need to plan exploration:
>
> ```kotlin
> // Recommended FIRST exec_code call — do NOT split into 3 separate calls:
> println("Project: ${project.name}, base: ${project.basePath}")
> println("Smart: ${!com.intellij.openapi.project.DumbService.isDumb(project)}")
> val dp = ProcessBuilder("docker", "info").redirectErrorStream(true).start()
> val dockerOk = dp.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && dp.exitValue() == 0
> println("Docker: $dockerOk")
> val changes = readAction {
>     com.intellij.openapi.vcs.changes.ChangeListManager.getInstance(project)
>         .allChanges.mapNotNull { it.virtualFile?.path }
> }
> println(if (changes.isEmpty()) "VCS: clean slate" else "VCS-modified files:\n" + changes.joinToString("\n"))
> // If files are listed: read them BEFORE writing to avoid overwriting parallel-agent work.
> // If dockerOk=false: still attempt to run FAIL_TO_PASS tests — many use H2 in-memory DB,
> // no Docker needed. Only fall back to runInspectionsDirectly if the test fails with an
> // explicit Docker connection error ("Cannot connect to Docker daemon").
> // Then add file reads for VCS-modified files + FAIL_TO_PASS test files IN THIS SAME CALL
> // to compress exploration from 7+ calls to 2-3.
> ```

> **Step 0 — Explore with PSI BEFORE reading files**
>
> When you need to understand a class's methods, fields, or call-sites, use PSI structural
> queries instead of reading file contents. **1 PSI call replaces 5-10 VfsUtil.loadText calls.**
>
> ```kotlin
> // Inspect class structure — no file read needed:
> val cls = readAction {
>     JavaPsiFacade.getInstance(project).findClass(
>         "com.example.domain.FeatureService",
>         GlobalSearchScope.projectScope(project)
>     )
> }
> cls?.methods?.forEach { m ->
>     val params = m.parameterList.parameters.joinToString { "${it.name}: ${it.type.presentableText}" }
>     println("${m.name}($params): ${m.returnType?.presentableText}")
> }
> // Find all callers (replaces grepping source files):
> import com.intellij.psi.search.searches.ReferencesSearch
> ReferencesSearch.search(cls!!, projectScope()).findAll().forEach { ref ->
>     println("${ref.element.containingFile.name} → ${ref.element.parent.text.take(80)}")
> }
> ```
>
> **Rule**: Before reading a 3rd file just to trace code flow, try `ReferencesSearch.search()`
> or `JavaPsiFacade.findClass()`. These answer in 1 round-trip what file reading takes 5-10 calls.

> **Step 2 — Do This FIRST Before Creating Any Migration File**
>
> Always determine the next available Flyway migration version number before writing `V{N}__*.sql`.
> Creating `V5__` when `V5__` already exists breaks Flyway on startup (checksum conflict).
>
> ```kotlin
> val migDir = findProjectFile("src/main/resources/db/migration")!!
> val nextVersion = readAction {
>     migDir.children.map { it.name }
>         .mapNotNull { Regex("""V(\d+)__""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
>         .maxOrNull()?.plus(1) ?: 1
> }
> println("Existing migrations:")
> readAction { migDir.children.map { it.name }.sorted() }.forEach { println("  $it") }
> println("NEXT_MIGRATION_VERSION=V$nextVersion")
> // Use this output as the prefix for your new migration file name
> ```

### Spring Boot Feature Implementation Workflow (New Feature / JWT / Security)

When implementing a new Spring Boot feature from scratch (e.g., JWT authentication, a new service + controller), follow this workflow to minimize wasted turns:

**Phase 1: Explore (1-2 exec_code calls)**

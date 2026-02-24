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
```kotlin
// Call 1: readiness + Docker + VCS + test file content in ONE call
println("Project: ${project.name}, base: ${project.basePath}")
println("Smart: ${!com.intellij.openapi.project.DumbService.isDumb(project)}")
val dp = ProcessBuilder("docker", "info").redirectErrorStream(true).start()
println("Docker: ${dp.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && dp.exitValue() == 0}")
// VCS check
val changes = readAction {
    com.intellij.openapi.vcs.changes.ChangeListManager.getInstance(project)
        .allChanges.mapNotNull { it.virtualFile?.path }
}
println(if (changes.isEmpty()) "VCS: clean" else "VCS-modified:\n" + changes.joinToString("\n"))
// Read test file and pom.xml in SAME call to understand what's needed
val testVf = findProjectFile("src/test/java/eval/sample/AuthControllerTest.java")  // ← use actual test path
if (testVf != null) println("\n=== TEST ===\n" + VfsUtil.loadText(testVf))
val pomVf = findProjectFile("pom.xml")
if (pomVf != null) println("\n=== pom.xml ===\n" + VfsUtil.loadText(pomVf))
```

**Phase 2: Add dependencies to pom.xml (1 exec_code call)**
```kotlin
// Read → inject new dependencies → write via VFS (DO NOT use native Write tool)
val pomFile = findProjectFile("pom.xml")!!
val content = VfsUtil.loadText(pomFile)
val jjwtDeps = """
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-api</artifactId>
            <version>0.12.6</version>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-impl</artifactId>
            <version>0.12.6</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-jackson</artifactId>
            <version>0.12.6</version>
            <scope>runtime</scope>
        </dependency>"""
val updated = content.replace("</dependencies>", "$jjwtDeps\n  </dependencies>")
check(updated != content) { "replace matched nothing — check pom.xml </dependencies> tag" }
writeAction { VfsUtil.saveText(pomFile, updated) }
println("pom.xml updated")
// Then trigger Maven sync (next step) before inspecting or compiling
```

**Phase 3: Create source files via exec_code VFS APIs (1-2 calls)**
```kotlin
// ALWAYS use writeAction + VFS to create new files — NOT native Write tool.
// VFS creates index immediately; native Write bypasses it.
// Use triple-quoted strings for Java code with .class refs and $ signs:
writeAction {
    val root = LocalFileSystem.getInstance().findFileByPath(project.basePath!!)!!
    val secDir = VfsUtil.createDirectoryIfMissing(root, "src/main/java/eval/sample/security")!!
    // Create JwtService.java
    val jwtService = secDir.findChild("JwtService.java") ?: secDir.createChildData(this, "JwtService.java")
    VfsUtil.saveText(jwtService, """
        package eval.sample.security;

        import io.jsonwebtoken.Jwts;
        import io.jsonwebtoken.security.Keys;
        import org.springframework.stereotype.Service;
        import java.util.Date;

        @Service
        public class JwtService {
            private static final String SECRET = "your-secret-key-here-must-be-at-least-256-bits";

            public String generateToken(String username) {
                return Jwts.builder()
                    .subject(username)
                    .issuedAt(new Date())
                    .expiration(new Date(System.currentTimeMillis() + 86400000))
                    .signWith(Keys.hmacShaKeyFor(SECRET.getBytes()))
                    .compact();
            }
        }
    """.trimIndent())
    println("Created JwtService.java")
    // Create more files similarly...
}
// After creating files, trigger re-indexing for compile checks:
waitForSmartMode()
```

**Phase 4: Verify compilation before running tests (~5s vs 90s for Maven)**
```kotlin
// Run IDE inspection on all newly created files — much faster than ./mvnw test-compile
for (path in listOf(
    "src/main/java/eval/sample/security/JwtService.java",
    "src/main/java/eval/sample/security/SecurityConfig.java",
    "src/main/java/eval/sample/security/JwtAuthenticationFilter.java"
)) {
    val vf = findProjectFile(path) ?: run { println("NOT FOUND: $path"); continue }
    val problems = runInspectionsDirectly(vf)
    if (problems.isEmpty()) println("OK: $path")
    else problems.forEach { (id, d) -> d.forEach { println("[$id] $path: ${it.descriptionTemplate}") } }
}
// Only if all OK: proceed to Maven test run
```

**Phase 5: Run the failing test class**
```kotlin
val process = ProcessBuilder("./mvnw", "test", "-Dtest=AuthControllerTest", "-Dspotless.check.skip=true")
    .directory(java.io.File(project.basePath!!))
    .redirectErrorStream(true).start()
val lines = process.inputStream.bufferedReader().readLines()
val exitCode = process.waitFor()
println("Exit: $exitCode | lines: ${lines.size}")
println(lines.take(30).joinToString("\n"))   // Spring context / Testcontainers errors at top
println(lines.takeLast(30).joinToString("\n")) // Maven BUILD summary at bottom
```

> **Key rules**:
> - If `steroid_execute_code` returns an error: read the error message and **retry with fixed code** — do NOT fall back to native Write/Bash
> - If an exec_code error is about missing import → add the import and retry
> - If an exec_code error is about `Write access allowed inside write-action only` → wrap VFS calls in `writeAction { }`
> - If exec_code compilation fails with `.class` or `$` → use triple-quoted Kotlin strings for Java source content

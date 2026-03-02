Coding with IntelliJ: Java & Spring Boot Patterns
[IU]
Comprehensive Java and Spring Boot patterns: Maven/Gradle, PSI class creation, Spring annotations, repositories, and test execution.

## Java / Spring Boot Patterns

> **Step -1 — Combined startup call: readiness + Docker + VCS in ONE exec_code call**
>
> For any Spring Boot / Maven task, combine your first three checks into a single call.
> This saves ~60s (3 round-trips × ~20s each) and gives you everything you need to plan exploration:
>
> ```text
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
> ```text
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
> ```text
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
```text
import com.intellij.openapi.vfs.VfsUtil

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
if (testVf != null) println("\n=== TEST ===\n" + VfsUtilCore.loadText(testVf))
val pomVf = findProjectFile("pom.xml")
if (pomVf != null) println("\n=== pom.xml ===\n" + VfsUtilCore.loadText(pomVf))
```
**Phase 2: Add dependencies to pom.xml (1 exec_code call)**
```text
import com.intellij.openapi.vfs.VfsUtil

// Read → inject new dependencies → write via VFS (DO NOT use native Write tool)
val pomFile = findProjectFile("pom.xml")!!
val content = VfsUtilCore.loadText(pomFile)
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
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.LocalFileSystem

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
### Find All @Entity / @Service / @RestController Classes

```kotlin
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.searches.AnnotatedElementsSearch

// Find all JPA @Entity classes
val entityClass = readAction {
    JavaPsiFacade.getInstance(project).findClass("jakarta.persistence.Entity", allScope())
        ?: JavaPsiFacade.getInstance(project).findClass("javax.persistence.Entity", allScope())
}
if (entityClass != null) {
    val entities = AnnotatedElementsSearch.searchPsiClasses(entityClass, projectScope()).findAll()
    println("@Entity classes (${entities.size}):")
    entities.forEach { println("  ${it.qualifiedName} in ${it.containingFile.virtualFile.path}") }
}

// Find all Spring @Service classes
val serviceClass = readAction {
    JavaPsiFacade.getInstance(project).findClass("org.springframework.stereotype.Service", allScope())
}
if (serviceClass != null) {
    AnnotatedElementsSearch.searchPsiClasses(serviceClass, projectScope()).findAll()
        .forEach { println("@Service: ${it.qualifiedName}") }
}

// Find all @RestController classes
val rcClass = readAction {
    JavaPsiFacade.getInstance(project).findClass("org.springframework.web.bind.annotation.RestController", allScope())
}
if (rcClass != null) {
    AnnotatedElementsSearch.searchPsiClasses(rcClass, projectScope()).findAll()
        .forEach { println("@RestController: ${it.qualifiedName}") }
}
```

### Check if a Class Already Exists (prevent duplicate file creation)

```kotlin
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope

val existing = readAction {
    JavaPsiFacade.getInstance(project).findClass(
        "com.example.MyClass",
        GlobalSearchScope.projectScope(project)
    )
}
if (existing != null) {
    println("EXISTS: " + existing.containingFile.virtualFile.path)
} else {
    println("NOT_FOUND: safe to create")
    // ... create the file
}
```

### Check Jakarta vs javax Import Conflicts

```kotlin
import com.intellij.psi.JavaPsiFacade

// Check which persistence API is available (Jakarta EE 3 vs older javax)
val hasJakarta = readAction {
    JavaPsiFacade.getInstance(project).findClass("jakarta.persistence.Entity", allScope()) != null
}
val hasJavax = readAction {
    JavaPsiFacade.getInstance(project).findClass("javax.persistence.Entity", allScope()) != null
}
println("Has jakarta.persistence: $hasJakarta")
println("Has javax.persistence: $hasJavax")
// Use the correct import prefix in your generated files
val persistencePrefix = if (hasJakarta) "jakarta" else "javax"
println("Use: ${persistencePrefix}.persistence.Entity")
```

### Find All Usages of a Class (Call Sites / Constructor Invocations)

**CRITICAL**: When adding a new field to a command/DTO/entity class, always find all call sites
*before* writing any code. Missing even one call site causes a compile error.

> **⚠️ Safe Constructor/Signature Change Recipe**: `runInspectionsDirectly` is file-scoped and
> does NOT catch cross-file compile errors from constructor changes. Before adding a parameter to
> any constructor, record, or method signature: (1) run `ReferencesSearch` to find ALL call sites,
> (2) update every call site in the same exec_code session, (3) then run `./mvnw compile -q` to
> verify project-wide correctness. Skipping step 1 causes "cannot find symbol" errors that only
> surface during test execution, not during file-level inspection.

```kotlin
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.search.GlobalSearchScope

// Find every place that constructs or references CreateReleaseCommand
val cmdClass = readAction {
    JavaPsiFacade.getInstance(project).findClass(
        "com.example.CreateReleaseCommand",
        GlobalSearchScope.projectScope(project)
    )
}
if (cmdClass != null) {
    val refs = ReferencesSearch.search(cmdClass, projectScope()).findAll()
    println("Found ${refs.size} usages:")
    refs.forEach { ref ->
        val file = ref.element.containingFile.virtualFile.path.substringAfterLast('/')
        val snippet = ref.element.parent.text.take(100)
        println("  $file → $snippet")
    }
} else println("Class not found")
```

### Add a Component to a Java Record via PSI (Whitespace-Safe)

**Use this instead of `content.replace()`** when adding fields to Java `record` classes.
PSI insertion is atomic and whitespace-independent — no excerpt-first ritual, no failed-replace retries.

**WORKFLOW**: When adding a parameter to a command or DTO record:
1. First, use `ReferencesSearch.search()` (below) to find ALL constructor call sites.
2. Then add the record component via PSI.
3. Then update each call site.

```kotlin
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch

// Step 1: Find all call sites BEFORE modifying the record
val cmdClass = readAction {
    JavaPsiFacade.getInstance(project).findClass(
        "com.example.CreateReleaseCommand",   // ← actual FQN
        GlobalSearchScope.projectScope(project)
    )
}
if (cmdClass != null) {
    val refs = readAction { ReferencesSearch.search(cmdClass, projectScope()).findAll() }
    println("Call sites to update (${refs.size}):")
    refs.forEach { ref ->
        println("  ${ref.element.containingFile.virtualFile.path.substringAfterLast('/')} → " +
            ref.element.parent.text.take(120))
    }
}
// ↑ Read this output, then update each call site — then proceed to add the record component below

// Step 2: Add the record component via PSI (whitespace-safe)
val vf = readAction {
    FilenameIndex.getVirtualFilesByName("Commands.java", GlobalSearchScope.projectScope(project)).first()
}
val psiFile = readAction { PsiManager.getInstance(project).findFile(vf) as? PsiJavaFile }
val record = readAction { psiFile?.classes?.firstOrNull { it.name == "CreateReleaseCommand" } }
if (record != null) {
    val factory = JavaPsiFacade.getElementFactory(project)
    // Create as a field — IntelliJ represents record components as fields
    val newComponent = readAction { factory.createFieldFromText("String parentCode;", record) }
    WriteCommandAction.runWriteCommandAction(project) {
        // Insert AFTER the last existing component (or use addBefore to prepend)
        val lastComponent = record.recordComponents.lastOrNull()
        if (lastComponent != null) {
            record.addAfter(newComponent, lastComponent)
        } else {
            record.add(newComponent)
        }
    }
    println("Record component added successfully")
    // Verify: list all components now
    println("Components now: " + readAction { record.recordComponents.map { it.name } })
} else println("Record class not found")
```

> **Rule**: If adding a parameter to a command/DTO record, ALWAYS use `ReferencesSearch.search()` FIRST
> to enumerate ALL constructor call sites — then update each. Never manually guess which files use a
> shared command/DTO class. This is the single most time-saving PSI operation for Spring Boot refactoring.

### Find @Repository Methods with @Query Annotations

Inspect existing DB query patterns before adding new queries:

```kotlin
import com.intellij.psi.search.GlobalSearchScope

val repo = readAction {
    JavaPsiFacade.getInstance(project).findClass(
        "com.example.ReleaseRepository",
        GlobalSearchScope.projectScope(project)
    )
}
repo?.methods?.forEach { method ->
    val queryAnnotation = method.annotations.firstOrNull {
        it.qualifiedName == "org.springframework.data.jpa.repository.Query" ||
        it.qualifiedName?.endsWith(".Query") == true
    }
    if (queryAnnotation != null) {
        val value = queryAnnotation.findAttributeValue("value")?.text ?: "<no value>"
        val nativeQ = queryAnnotation.findAttributeValue("nativeQuery")?.text ?: "false"
        println("@Query(nativeQuery=$nativeQ) ${method.name}: $value")
    } else {
        println("derived-query: ${method.name}")
    }
}
```

### Validate Spring Data JPA Repository After Adding Derived Query Methods

**Always run `runInspectionsDirectly` on the repository file immediately after adding derived query methods.** Spring Data JPA method names like `findByFeature_Code` and `findByParentComment_Id` follow strict naming conventions derived from entity field paths. They compile fine in Java but throw `QueryCreationException` at Spring context startup — which only surfaces during `./mvnw test`, not during `./mvnw test-compile`.

> **Rule**: Inspect every file you **modify** — not just files you **create**. The most common undetected error pattern is: inspections pass on all newly created files, but the modified repository has a subtly invalid method name that causes a 90+ second Maven test failure. Catching it with `runInspectionsDirectly` (~5s) prevents that wasted turn.

```kotlin
// After modifying a Spring Data JPA repository (adding new findBy... methods):
val repoVf = findProjectFile("src/main/java/com/example/CommentRepository.java")!!
val problems = runInspectionsDirectly(repoVf)
if (problems.isEmpty()) println("OK: repository methods are valid")
else problems.forEach { (id, d) -> d.forEach { println("[$id] ${it.descriptionTemplate}") } }
// Spring Data Plugin reports: SpringDataMethodInconsistency, invalid derived query names, etc.
// Example valid derived queries for a Comment entity with Feature and ParentComment fields:
//   findByFeature_Code(String code)       → traverses Comment.feature.code
//   findByParentComment_Id(Long id)       → traverses Comment.parentComment.id
// Example invalid: findByFeatureCode(String code) if field is 'feature.code' not 'featureCode'
```

**Batch: inspect multiple modified files at once**
```kotlin
// Inspect both modified file AND newly created files in a single call
for (path in listOf(
    "src/main/java/com/example/CommentRepository.java",   // ← MODIFIED (added findBy methods)
    "src/main/java/com/example/CommentService.java",      // ← CREATED
    "src/main/java/com/example/api/CommentController.java" // ← CREATED
)) {
    val vf = findProjectFile(path) ?: run { println("NOT FOUND: $path"); continue }
    val problems = runInspectionsDirectly(vf)
    if (problems.isEmpty()) println("OK: $path")
    else problems.forEach { (id, d) -> d.forEach { println("[$id] $path: ${it.descriptionTemplate}") } }
}
```

### Verify @ControllerAdvice / @ExceptionHandler Before Writing Controllers

**CRITICAL for controllers that throw custom exceptions** (e.g. `ResourceNotFoundException`): if no `@ControllerAdvice` handles the exception, the API returns HTTP 500 instead of 404, failing tests like `shouldReturnNotFoundForNonExistentNotification`. Always verify this BEFORE finalising a new controller.

```kotlin
// Find all @ControllerAdvice/@RestControllerAdvice classes and the exceptions they handle
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch

val scope = GlobalSearchScope.projectScope(project)

// Step 1: Locate the advice annotation class (handles both @ControllerAdvice and @RestControllerAdvice)
val adviceAnnotation = readAction {
    JavaPsiFacade.getInstance(project).findClass(
        "org.springframework.web.bind.annotation.RestControllerAdvice", allScope()
    ) ?: JavaPsiFacade.getInstance(project).findClass(
        "org.springframework.web.bind.annotation.ControllerAdvice", allScope()
    )
}

if (adviceAnnotation == null) {
    println("ERROR: Spring Web not found on classpath — check pom.xml")
} else {
    val adviceClasses = AnnotatedElementsSearch.searchPsiClasses(adviceAnnotation, scope).findAll().toList()
    if (adviceClasses.isEmpty()) {
        println("WARNING: No @ControllerAdvice/@RestControllerAdvice found in project.")
        println("Controllers throwing custom exceptions will return HTTP 500. Create a @RestControllerAdvice.")
    } else {
        adviceClasses.forEach { cls ->
            println("Found advice: ${cls.qualifiedName}")
            readAction {
                cls.methods.forEach { m ->
                    val handler = m.annotations.firstOrNull { it.qualifiedName?.endsWith("ExceptionHandler") == true }
                    if (handler != null) {
                        val exTypes = handler.findAttributeValue("value")?.text ?: "(all)"
                        val status = m.annotations.firstOrNull { it.qualifiedName?.endsWith("ResponseStatus") == true }
                            ?.findAttributeValue("code")?.text ?: "?"
                        println("  ${m.name} handles: $exTypes → HTTP $status")
                    }
                }
            }
        }
    }
}
// If the output shows no handler for your exception type → add @ExceptionHandler to existing advice,
// or create a new @RestControllerAdvice class before writing the controller.
```

### Inspect JPA Entity Fields (Parent-Child Relationships)

Understand existing JPA mappings before adding `@ManyToOne` / `@OneToMany` relationships:

```kotlin
import com.intellij.psi.search.GlobalSearchScope

val entityClass = readAction {
    JavaPsiFacade.getInstance(project).findClass(
        "com.example.Release",
        GlobalSearchScope.projectScope(project)
    )
}
entityClass?.fields?.forEach { field ->
    val jpaAnnotations = field.annotations.filter { ann ->
        listOf("Id", "Column", "ManyToOne", "OneToMany", "ManyToMany", "OneToOne", "JoinColumn")
            .any { ann.qualifiedName?.endsWith(it) == true }
    }
    if (jpaAnnotations.isNotEmpty()) {
        println("${field.name}: ${field.type.presentableText} → ${jpaAnnotations.map { it.qualifiedName?.substringAfterLast('.') }}")
    }
}
```

### Read pom.xml / Test Files via VFS

```text
// Read pom.xml
val pomContent = VfsUtilCore.loadText(findProjectFile("pom.xml")!!)
println(pomContent)

// Read a specific test file to understand its assertions before implementing
val testContent = VfsUtilCore.loadText(findProjectFile("src/test/java/com/example/ProductTest.java")!!)
println(testContent)
```

### Targeted File Read (Minimal Context — Avoid Context Bloat)

Instead of printing the full file, filter for the lines you need:

```text
// Extract only test assertions and endpoint URLs from a large test file
val testContent = VfsUtilCore.loadText(findProjectFile("src/test/java/com/example/MyIntegrationTest.java")!!)
testContent.lines()
    .filter { it.contains("assert") || it.contains("/api/") || it.contains("@Test") || it.trim().startsWith("//") }
    .forEach { println(it) }
```

This is much cheaper than reading the full file when you only need to know what a test asserts.

### Discover Existing Class Naming Conventions

Before creating a new class, check what naming patterns already exist in the project to avoid mismatches (e.g., `EventType` vs `NotificationEventType`):

```kotlin
import com.intellij.psi.search.PsiShortNamesCache

val allNames = readAction { PsiShortNamesCache.getInstance(project).allClassNames.toList() }
// Print domain model names to understand naming conventions
allNames.filter { name ->
    name.endsWith("Status") || name.endsWith("Type") || name.endsWith("Dto") ||
    name.endsWith("Entity") || name.endsWith("Service") || name.endsWith("Repository")
}.sorted().forEach { println(it) }
```

### Find Next Flyway Migration Version Number

Avoid creating a migration with a version number that already exists:

```kotlin
val migDir = findProjectFile("src/main/resources/db/migration")!!
val nextVersion = readAction {
    migDir.children.map { it.name }
        .mapNotNull { Regex("""V(\d+)__""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        .maxOrNull()?.plus(1) ?: 1
}
println("Existing migrations:")
readAction { migDir.children.map { it.name }.sorted() }.forEach { println("  $it") }
println("Next version: V$nextVersion")
```

### Find Java/Kotlin Files via IDE Index (PREFERRED over shell find)

**Always prefer the IDE index over `ProcessBuilder("find", ...)`.** The IDE index respects source roots, handles not-yet-flushed writes, and stays consistent with PSI. Shell `find` bypasses indexing and may return stale or out-of-scope results.

```kotlin
// PREFERRED: IDE index — respects source roots, project scope
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

val javaFiles = readAction {
    FilenameIndex.getAllFilesByExt(project, "java", GlobalSearchScope.projectScope(project))
}
println("Java files: ${javaFiles.size}")
javaFiles.forEach { vf -> println(vf.path) }

// Same for Kotlin:
val ktFiles = readAction {
    FilenameIndex.getAllFilesByExt(project, "kt", GlobalSearchScope.projectScope(project))
}
ktFiles.forEach { println(it.path) }

// Find a file by EXACT filename (fastest path — O(1) index lookup by name, no iteration)
val byName = readAction {
    FilenameIndex.getVirtualFilesByName("UserServiceImpl.java", GlobalSearchScope.projectScope(project))
}
byName.forEach { println(it.path) }

// Or by name + path substring filter (when multiple files have the same name):
val filtered = readAction {
    FilenameIndex.getAllFilesByExt(project, "java", GlobalSearchScope.projectScope(project))
        .filter { it.path.contains("user", ignoreCase = true) }
}
filtered.forEach { println(it.path) }

// AVOID: ProcessBuilder("find", "/mcp-run-dir/src", "-name", "*.java", "-type", "f")
// ↑ Bypasses IDE indexing — may miss newly-created files or include out-of-scope files
```

### Search for Text Across Project Files (PREFERRED Over shell grep/rg)

**Always prefer the IDE search API over `ProcessBuilder("grep", ...)` or `ProcessBuilder("rg", ...)`.**
Shell grep bypasses the IDE's PSI and may silently fail on regex escaping (`\/` is invalid in ripgrep).
`PsiSearchHelper` uses the same index as "Find in Files" — it's fast and always correct.

```text
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.search.FilenameIndex

// Option A: Find all files containing a specific word (word-boundary search)
// Use this for plain identifiers, class names, annotation names, etc.
val scope = GlobalSearchScope.projectScope(project)
val matchingFiles = mutableListOf<String>()
readAction {
    PsiSearchHelper.getInstance(project).processAllFilesWithWord("api", scope, { psiFile ->
        // Further filter if needed (e.g., only files that contain "/api/")
        if (psiFile.text.contains("/api/")) matchingFiles.add(psiFile.virtualFile.path)
        true  // return true to continue searching; false to stop early
    }, true)
}
matchingFiles.forEach { println(it) }

// Option B: Content filter over IDE-indexed files (for arbitrary substrings / URLs / paths)
// Faster than shell grep because it operates on the IDE's already-indexed file list
val filesWithPath = readAction {
    FilenameIndex.getAllFilesByExt(project, "java", GlobalSearchScope.projectScope(project))
        .filter { vf -> VfsUtilCore.loadText(vf).contains("/api/") }
}
filesWithPath.forEach { println(it.path) }

// Option C: Search in YAML/XML/properties files (no word boundary needed)
val yamlFiles = readAction {
    FilenameIndex.getAllFilesByExt(project, "yml", GlobalSearchScope.projectScope(project))
        .filter { vf -> VfsUtilCore.loadText(vf).contains("/api/") }
}
yamlFiles.forEach { println(it.path + ": " + VfsUtilCore.loadText(it).lines().filter { l -> l.contains("/api/") }.joinToString("; ")) }
```

### Combine Discovery + Batch Update in ONE Call (Eliminates Two-Step Pattern)

**Anti-pattern to avoid**: listing files first (call 1), then reading + updating each file (call 2 or more).
**Preferred pattern**: find files that match, read content, apply updates — all in a single exec_code call.

This approach eliminates the most common wasteful two-step pattern seen in Spring Boot refactoring tasks
(e.g., "update URL prefix in all controllers"). Instead of `FilenameIndex` → read each → decide → update,
do everything in one shot:

```text
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.openapi.vfs.VfsUtil

// Single call: find all Java files containing "/api/v1" and replace with "/api/v2"
val scope = GlobalSearchScope.projectScope(project)
val toUpdate = readAction {
    FilenameIndex.getAllFilesByExt(project, "java", scope)
        .filter { vf -> VfsUtilCore.loadText(vf).contains("/api/v1") }
}
println("Files to update: ${toUpdate.size}")
toUpdate.forEach { vf ->
    val content = VfsUtilCore.loadText(vf)          // read OUTSIDE writeAction
    val updated = content.replace("/api/v1", "/api/v2")
    check(updated != content) { "Replace matched nothing in ${vf.name}" }
    writeAction { VfsUtil.saveText(vf, updated) } // write INSIDE writeAction
    println("Updated: ${vf.path}")
}
// Flush changes so git/shell tools see them immediately:
LocalFileSystem.getInstance().refresh(false)
println("Done — updated ${toUpdate.size} files")
```

> **Rule**: If you can describe your task as "find files with X, then update them" — do it in **one**
> exec_code call. Discovery + read + update in separate calls wastes ~20s per round-trip and provides
> no benefit since you're working with the same VFS state.

### Diagnosing String Replace Failures

If `check(updated != content)` fires with `"Replace matched nothing"`, the target string has slightly
different whitespace, indentation, or line endings than you expected — or a prior agent already modified
the file. **Always print the exact target region BEFORE attempting the replace:**

```text
val vf = findProjectFile("src/main/java/com/example/ReleaseService.java")!!
val content = VfsUtilCore.loadText(vf)

// Step 1: Locate the target region and PRINT it before replacing (costs nothing; saves a retry turn):
val keyword = "updateRelease"   // keyword near your target
val idx = content.indexOf(keyword)
if (idx < 0) {
    println("KEYWORD_NOT_FOUND: '$keyword' — file may already be modified, or check the exact method name")
} else {
    println("EXCERPT (chars $idx..${idx + 300}):\n" + content.substring(idx, (idx + 300).coerceAtMost(content.length)))
}

// Step 2: Only AFTER confirming the exact text from the excerpt, perform the replace:
val updated = content.replace("exact old string from excerpt", "new string")
check(updated != content) { "No change — re-read the excerpt above and fix old_string" }
writeAction { VfsUtil.saveText(vf, updated) }
println("Updated: ${vf.name}")
```

**When a prior agent already modified the file**: The expected string may be gone or transformed.
Use `ChangeListManager.allChanges` to detect modified files, then re-read before replacing — do not
rely on a prior turn's view of the file content.

**Alternative when string replace fails repeatedly**: Use PSI surgery to add/modify fields and methods
directly (see "Add a Method to an Existing Java Class via PSI" below). PSI operations are whitespace-
insensitive and survive partial edits made by other agents.

### Batch Project Exploration (One Script Instead of Many Calls)

Explore the full project structure and read multiple relevant files in a single execution — avoid making 5+ separate calls to understand the codebase:

```kotlin
import com.intellij.openapi.roots.ProjectRootManager

// Step 1: Print the full file tree for src/main and src/test
// ⚠️ contentRoots accesses the project model — must be inside readAction { }
val contentRoots = readAction { ProjectRootManager.getInstance(project).contentRoots.toList() }
contentRoots.forEach { root ->
    VfsUtil.iterateChildrenRecursively(root, null) { file ->
        if (!file.isDirectory && (file.extension == "java" || file.extension == "kt" || file.name == "pom.xml")) {
            println(file.path.removePrefix(project.basePath!!))
        }
        true
    }
}
```

```text
// Step 2: Read multiple files in a single script (batch instead of per-file calls)
val filesToRead = listOf(
    "src/main/java/com/example/domain/FeatureService.java",
    "src/main/java/com/example/api/controllers/FeatureController.java",
    "src/main/java/com/example/domain/models/Feature.java",
    "pom.xml"
)
for (path in filesToRead) {
    val vf = findProjectFile(path) ?: run { println("NOT FOUND: $path"); continue }
    println("\n=== $path ===")
    println(VfsUtilCore.loadText(vf))
}
```

### Semantic Code Navigation — Extract Structural Info Without Reading Full Files

**Prefer PSI-based structural queries over reading entire file contents.** When you need to know
"what methods does FeatureService have?" or "what fields does CommentDto have?", a single PSI call
answers that question in one round-trip — no need to read 5-6 full files one by one.

```kotlin
import com.intellij.psi.search.GlobalSearchScope

// Get all methods and fields of a Java class WITHOUT reading the full file text
val cls = readAction {
    JavaPsiFacade.getInstance(project).findClass(
        "com.sivalabs.ft.features.domain.FeatureService",
        GlobalSearchScope.projectScope(project)
    )
}
if (cls != null) {
    println("=== ${cls.qualifiedName} ===")
    println("Methods:")
    cls.methods.forEach { m ->
        val params = m.parameterList.parameters.joinToString { "${it.name}: ${it.type.presentableText}" }
        println("  ${m.name}($params): ${m.returnType?.presentableText}")
    }
    println("Fields:")
    cls.fields.forEach { f -> println("  ${f.name}: ${f.type.presentableText}") }
} else println("Class not found")
```

```kotlin
// Inspect multiple related classes in ONE script to understand codebase structure
val classesToInspect = listOf(
    "com.example.features.domain.FeatureRepository",
    "com.example.features.domain.CommentDto",
    "com.example.features.api.FeatureController"
)
for (fqn in classesToInspect) {
    val c = readAction { JavaPsiFacade.getInstance(project).findClass(fqn, projectScope()) }
    if (c == null) { println("NOT FOUND: $fqn"); continue }
    println("\n=== $fqn ===")
    c.methods.forEach { m -> println("  ${m.name}(${m.parameterList.parameters.size} params)") }
}
```

This replaces 6 separate `VfsUtilCore.loadText()` calls with 1 PSI-based structural query.

### Verify Project Package Structure Before Creating Files

**CRITICAL**: Always verify the actual package hierarchy via the IDE project model before creating new source files.
Directory names alone are NOT reliable — module source roots may start at a different depth than you expect.
Getting this wrong means your files are in the wrong package (tests pass locally but fail arena validation).

```kotlin
import com.intellij.openapi.roots.ProjectRootManager

// Step 1: List all content source roots (shows exactly where packages start)
// ⚠️ contentSourceRoots accesses the project model — must be inside readAction { }
readAction { ProjectRootManager.getInstance(project).contentSourceRoots.toList() }.forEach { root ->
    println("Source root: ${root.path}")
}

// Step 2: Check if a target package exists in the project model
val pkg = readAction { JavaPsiFacade.getInstance(project).findPackage("com.example.api") }
println("com.example.api exists: ${pkg != null}")

// Step 3: If the package is null, list top-level packages to find the correct root
val rootPkg = readAction { JavaPsiFacade.getInstance(project).findPackage("") }
rootPkg?.subPackages?.forEach { println("top-level package: ${it.qualifiedName}") }

// Step 4: Navigate the package hierarchy to find correct sub-packages
val apiPkg = readAction { JavaPsiFacade.getInstance(project).findPackage("com.example") }
apiPkg?.subPackages?.forEach { println("  sub-package: ${it.qualifiedName}") }
```

This prevents the common error of creating `com.example.microservices.api.Foo` when the project
actually uses `com.example.api.Foo` — a package mismatch that passes internal tests (JSON field matching)
but fails integration validation (class path matching).

**For empty modules with no existing source files** — also infer package convention from sibling modules:

```kotlin
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

// When target module has no existing Java files (package can't be inferred locally),
// find existing packages in sibling modules to discover naming convention:
val allJavaFiles = readAction {
    FilenameIndex.getAllFilesByExt(project, "java", GlobalSearchScope.projectScope(project))
        .filter { it.path.contains("/main/java/") }
}
// Extract package names from existing files
val existingPackages = readAction {
    allJavaFiles.mapNotNull { vf ->
        val rel = vf.path.substringAfter("/main/java/")
        rel.substringBeforeLast("/").replace("/", ".")
    }.distinct().sorted()
}
println("Existing packages in sibling modules:")
existingPackages.take(10).forEach { println("  $it") }
// ↑ Use this to derive the correct base package (e.g. "shop.api" not "shop.microservices.api")
```

**⚠️ Pitfall: Gradle `group` ≠ Java package prefix**

The `group` field in `build.gradle` (`group = 'shop.microservices.api'`) is the **Maven artifact group coordinate** — it controls how the JAR is published to a repository. It does NOT determine the Java package hierarchy inside the source files. Projects commonly have:
- Gradle `group = 'shop.microservices.api'` (artifact coordinate)
- Actual Java package = `shop.api` (source code package)

**Always derive the required Java package from test import statements or existing source files — never from the Gradle `group` field.**

```kotlin
// Extract required packages from test imports (ground truth for new files):
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

val testFile = readAction {
    FilenameIndex.getVirtualFilesByName("ProductServiceApiTests.java",
        GlobalSearchScope.projectScope(project)).firstOrNull()
}
val testImports = testFile?.let { vf -> readAction {
    (PsiManager.getInstance(project).findFile(vf) as? PsiJavaFile)
        ?.importList?.importStatements?.map { it.qualifiedName ?: "" }
} }
println("Packages to use for new files:\n" + testImports?.joinToString("\n"))
// ↑ e.g. "shop.api.core.product.Product" → create file in `shop/api/core/product/`
//   even if build.gradle says `group = 'shop.microservices.api'`
```
### Check Pending VCS Changes (Prefer Over `git diff` Shell Calls)

**PREFERRED over `ProcessBuilder("git", "diff", "HEAD", "--name-only")`** — avoids blocking the script executor thread and works correctly even inside IDE-managed VFS.

```kotlin
// Check which files have pending (uncommitted) changes
val changes = readAction {
    com.intellij.openapi.vcs.changes.ChangeListManager.getInstance(project)
        .allChanges.mapNotNull { it.virtualFile?.path }
}
println(if (changes.isEmpty()) "Clean slate — no pending changes" else "Modified files:\n" + changes.joinToString("\n"))
```

Use this at the start of arena tasks to detect whether a previous agent slot already modified files — avoids overwriting work done by a parallel agent.

**Multi-agent step 2: after VCS check, verify required classes exist with correct FQN** (changed files ≠ correct fix — a prior agent may have created files in the wrong package):

```kotlin
// After detecting modified files, check that required classes actually resolve
import com.intellij.psi.search.GlobalSearchScope

val scope = GlobalSearchScope.projectScope(project)
val required = listOf(
    "shop.api.core.product.Product",
    "shop.api.composite.product.ProductAggregate"
)  // ← replace with your task's required FQNs
val missing = required.filter {
    readAction { JavaPsiFacade.getInstance(project).findClass(it, scope) } == null
}
println(if (missing.isEmpty()) "All required classes present — run tests"
        else "STILL MISSING (must create): " + missing.joinToString(", "))
```

### Read JUnit XML Test Results After ExternalSystemUtil `success=false`

When `ExternalSystemUtil.runTask()` returns `success=false` **do NOT immediately fall back to `ProcessBuilder("./gradlew")`**. Read the JUnit XML results directly from `build/test-results/test/` instead — this gives you structured failure details without spawning a nested Gradle daemon:

```text
import com.intellij.openapi.vfs.VfsUtil

// Adjust path for your module (e.g. "microservices/product-service/build/test-results/test")
val testResultsDir = findProjectFile("build/test-results/test")
if (testResultsDir == null) {
    println("No test-results dir — tests may not have run (compilation error stopped before test phase)")
} else {
    testResultsDir.children.filter { it.name.endsWith(".xml") }.forEach { xmlFile ->
        val content = VfsUtilCore.loadText(xmlFile)
        val failures = Regex("""<failure[^>]*>(.+?)</failure>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(content).map { it.groupValues[1].take(300) }.toList()
        if (failures.isNotEmpty()) println("FAIL ${xmlFile.name}:\n" + failures.first())
        else println("PASS ${xmlFile.name}")
    }
}
```

> **⚠️ CRITICAL: `BUILD SUCCESSFUL` with ProcessBuilder exit=0 does NOT mean tests ran and passed.**
> Gradle exits 0 when it completes all *requested tasks* without error — but if the test task was
> UP-TO-DATE, or a compilation error stopped execution before the test phase, no tests ran at all.
> The **only** confirmation that tests executed and passed is `Tests run: X, Failures: 0, Errors: 0`
> appearing in the output. Absence of this line means tests did not run — do NOT declare success.

**⚠️ VFS → Git sync lag**: After bulk `writeAction { VfsUtil.saveText(...) }` edits, git-based tools (subprocess `git diff`, `ProcessBuilder("git", ...)`) may see stale content because VFS changes haven't been flushed to disk yet. Always call `LocalFileSystem.getInstance().refresh(false)` (synchronous) after bulk VFS edits, BEFORE running any git subprocess or checking git diff:

```text
// Apply bulk changes
writeAction {
    files.forEach { (vf, content) -> VfsUtil.saveText(vf, content) }
}
// Flush VFS to disk — ensures git diff / shell tools see the updates
LocalFileSystem.getInstance().refresh(false)

// Now git-based checks are accurate:
val result = ProcessBuilder("git", "diff", "--name-only")
    .directory(java.io.File(project.basePath!!)).start()
println(result.inputStream.bufferedReader().readText())
```

### Add a Method to an Existing Java Class via PSI (Safer Than VfsUtil.saveText for Partial Updates)

**`VfsUtil.saveText()` replaces the ENTIRE file** — if you only need to add one method, use PSI surgery instead. This avoids overwriting code you haven't read and reduces the risk of accidentally losing other methods.

```kotlin
import com.intellij.psi.JavaPsiFacade
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.search.GlobalSearchScope

// Find the class to modify
val psiClass = readAction {
    JavaPsiFacade.getInstance(project).findClass(
        "org.springframework.samples.petclinic.service.UserServiceImpl",
        GlobalSearchScope.projectScope(project)
    )
}
if (psiClass != null) {
    val factory = JavaPsiFacade.getElementFactory(project)
    // Build method text using concatenation — avoid 'import ...' at line-start in triple-quoted strings
    val methodText = "private void validatePassword(String password) {\n" +
        "    if (password == null || password.isEmpty()) {\n" +
        "        throw new IllegalArgumentException(\"Password must not be empty\");\n" +
        "    }\n" +
        "}"
    val newMethod = readAction { factory.createMethodFromText(methodText, psiClass) }
    WriteCommandAction.runWriteCommandAction(project) {
        psiClass.add(newMethod)
    }
    println("Method added to ${psiClass.qualifiedName}")
    // Run inspection to verify syntax
    val vf = psiClass.containingFile.virtualFile
    val problems = runInspectionsDirectly(vf)
    if (problems.isEmpty()) println("No compile errors") else problems.forEach { (id, ds) -> ds.forEach { println("[$id] ${it.descriptionTemplate}") } }
} else println("Class not found — check the FQN")
```

### Add Maven Dependencies to pom.xml via VFS (Reliable Pattern)

**PREFER exec_code VFS write over native Edit tool** for pom.xml changes. VFS write triggers IDE file-change notification immediately, making Maven auto-import more reliable.

```text
// Step 1: Add dependency via VFS text replace
val pomFile = findProjectFile("pom.xml")!!
val content = VfsUtilCore.loadText(pomFile)

// Print excerpt before replacing — catch whitespace issues before they waste a turn:
val idx = content.indexOf("</dependencies>")
println("EXCERPT (around </dependencies>):\n" + content.substring((idx - 50).coerceAtLeast(0), idx + 20))

val newDep = """
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-api</artifactId>
        <version>0.12.6</version>
    </dependency>"""  // ← adjust groupId/artifactId/version as needed
val updated = content.replace("</dependencies>", "$newDep\n  </dependencies>")
check(updated != content) { "replace matched nothing — pom.xml may not have a </dependencies> tag" }
writeAction { VfsUtil.saveText(pomFile, updated) }
println("pom.xml updated — trigger Maven sync next")
// Step 2: After writing, trigger Maven sync (next section)
```

### Trigger Maven Re-import After pom.xml Changes

```kotlin
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.buildtool.MavenSyncSpec  // ← correct package for IU-253+; NOT .project.MavenSyncSpec
import com.intellij.platform.backend.observation.Observation

// After editing pom.xml: schedule sync AND AWAIT it with Observation.awaitConfiguration()
// This is the production-grade API used in Android Studio — avoids 600s modal timeouts.
val manager = MavenProjectsManager.getInstance(project)
manager.scheduleUpdateAllMavenProjects(MavenSyncSpec.full("post-pom-edit", false))
Observation.awaitConfiguration(project)   // suspends until Maven sync + indexing fully complete
println("Maven sync and indexing complete — safe to run tests now")
// ⚠️ If MavenSyncSpec cannot be resolved, fall back to:
//   ProcessBuilder("./mvnw", "dependency:resolve").directory(java.io.File(project.basePath!!)).start().waitFor()
```

> **`Observation.awaitConfiguration()`** is the canonical way to await any background IDE activity
> (Maven sync, Gradle import, indexing). It is suspend-compatible and handles cancellation.
> This replaces ad-hoc polling loops or `waitForSmartMode()` after build-file changes.

### Editing Existing Files via VFS (PREFERRED over native Edit tool)

**Use `VfsUtil.loadText` + `VfsUtil.saveText` for editing existing files** when IDE change notification matters (e.g., pom.xml edits that trigger Maven auto-import, or any file that the IDE needs to re-parse). The native `Edit` tool writes directly to disk, bypassing IntelliJ's VFS layer — Maven auto-import may not trigger.

```text
// ✓ PREFERRED: Edit existing file via VFS — triggers IDE change notification
val vf = findProjectFile("pom.xml")!!
val content = VfsUtilCore.loadText(vf)             // read OUTSIDE writeAction
// Print the target slice first to verify exact whitespace/content before replacing:
val idx = content.indexOf("</dependencies>")
println("Target slice:\n" + content.substring(maxOf(0, idx - 100), minOf(content.length, idx + 50)))
val newDeps = """
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
        <groupId>com.h2database</groupId>
        <artifactId>h2</artifactId>
        <scope>test</scope>
    </dependency>
""".trimIndent()
val updated = content.replace("</dependencies>", "$newDeps\n</dependencies>")
check(updated != content) { "pom.xml replace matched nothing — check exact whitespace" }
writeAction { VfsUtil.saveText(vf, updated) }  // write INSIDE writeAction — triggers VFS notification
println("pom.xml updated — IDE change notification fired")
// Then trigger Maven sync (see above)
```

> **Why VFS over native Edit?**
> - VFS write → IntelliJ detects the change → Maven auto-import triggers automatically
> - Native `Edit` tool → writes to disk directly → IntelliJ may miss the change → Maven sync needed manually
> - After VFS write of pom.xml, still run `MavenSyncSpec.full(...)` + `Observation.awaitConfiguration()` to be safe

### Read Maven Project Model (Dependencies, Effective POM)

**Prefer over `File(basePath, "pom.xml").readText()`** — respects parent POM inheritance and property interpolation. Useful for checking which version of a library is in use, or whether a dependency is present.

```kotlin
import org.jetbrains.idea.maven.project.MavenProjectsManager

// Query Maven project model (effective POM — includes parent POM inheritance and property resolution)
val mavenManager = MavenProjectsManager.getInstance(project)
val rootProject = mavenManager.rootProjects.firstOrNull() ?: error("No Maven project found")
println("Project: ${rootProject.mavenId.groupId}:${rootProject.mavenId.artifactId}:${rootProject.mavenId.version}")
// List all resolved dependencies (includes dependencies inherited from parent POM):
rootProject.dependencies.forEach { dep ->
    println("  dep: ${dep.groupId}:${dep.artifactId}:${dep.version} scope=${dep.scope}")
}
// Check if a specific dependency exists (e.g. to detect Jakarta vs javax):
val hasLiquibase = rootProject.dependencies.any { it.groupId == "org.liquibase" }
println("Has Liquibase: $hasLiquibase")
```

### IDE-Native Project Build Verification (ProjectTaskManager)

**Preferred over `./mvnw test-compile`** — compiles through IntelliJ's build system, gives structured results, and avoids spawning a child Maven process. Use when you want to verify project-wide compilation without running any tests.

```text
import com.intellij.task.ProjectTaskManager
import com.intellij.openapi.module.ModuleManager
import kotlinx.coroutines.future.await

val modules = ModuleManager.getInstance(project).modules
val result = ProjectTaskManager.getInstance(project).build(*modules).await()
println("Build errors: ${result.hasErrors()}")
println("Build aborted: ${result.isAborted}")
// result.hasErrors() == false means project-wide compile passed
```

> **Note**: `ProjectTaskManager.build()` compiles *all* modules. For a quick single-file check, use
> `runInspectionsDirectly(vf)` first (seconds), then fall back to this for cross-file verification.

### Run Tests via IntelliJ IDE Runner ★ PREFERRED ★

> **⚠️ Arena / Docker environment**: Tests that use `@Testcontainers` require Docker-in-Docker to be
> available. Most arena environments support this — **do NOT skip running tests just because you see
> `@Testcontainers`**. Only treat Docker as unavailable if you run a baseline test (a test that existed
> BEFORE your changes) and it fails with `Could not find a valid Docker environment` — then it's an
> infrastructure constraint, not a code defect. Use `runInspectionsDirectly()` as your final check
> in that case and declare your fix complete.

**Always prefer this over `./mvnw test` or `./gradlew test`.** Running tests through the IDE
runner is equivalent to clicking the green ▶ button next to a test class or method. Benefits:

- **No 200k-char truncation problem** — pass/fail from `isPassed()` on the SMTestProxy root
- **Structured results** in the IDE Test Results window — individual failures navigable
- **Works for any build system** — Maven, Gradle, or plain JUnit

> ⚠️ **CRITICAL**: `JUnitConfiguration` (from `com.intellij.execution.junit`) is for **standalone
> JUnit tests** that do NOT need Maven/Gradle. For Maven or Gradle projects use the APIs below —
> otherwise dependencies won't be resolved and the test will fail to compile.
### Completion Rule: Separate Code Correctness from Environment Availability

Do not collapse everything into one "test failed" bucket. Track two independent axes:

1. **Code correctness** - inspections/build checks on changed files
2. **Environment availability** - whether runtime infra (Docker/Testcontainers, etc.) is available

A fix is complete when code is correct and either:
- target tests pass, or
- target and baseline tests fail with the same infrastructure signature

```kotlin
import java.io.File
import java.util.concurrent.TimeUnit

data class TestOutcome(
    val name: String,
    val exitCode: Int?,
    val timedOut: Boolean,
    val lines: List<String>,
) {
    val passed: Boolean get() = !timedOut && exitCode == 0
    val dockerUnavailable: Boolean
        get() = lines.any { "Could not find a valid Docker environment" in it }
}

fun runMavenTest(testClass: String, timeoutSec: Long = 180): TestOutcome {
    val process = ProcessBuilder("./mvnw", "test", "-Dtest=$testClass", "-q")
        .directory(File(project.basePath!!))
        .redirectErrorStream(true)
        .start()
    val lines = process.inputStream.bufferedReader().readLines()
    val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
    return if (finished) {
        TestOutcome(testClass, process.exitValue(), timedOut = false, lines = lines)
    } else {
        process.destroyForcibly()
        TestOutcome(testClass, exitCode = null, timedOut = true, lines = lines)
    }
}

val changedPaths = listOf(
    "src/main/java/com/example/api/MyController.java",
    "src/main/java/com/example/service/MyService.java",
)
val inspectionFailures = mutableListOf<String>()
for (path in changedPaths) {
    val vf = findProjectFile(path) ?: continue
    val problems = runInspectionsDirectly(vf)
    if (problems.isNotEmpty()) inspectionFailures += path
}
val codeReady = inspectionFailures.isEmpty()

val target = runMavenTest("com.example.api.MyFeatureIntegrationTest")
val baseline = runMavenTest("com.example.api.ExistingIntegrationBaselineTest")
val infraBlocked = target.dockerUnavailable && baseline.dockerUnavailable

val complete = codeReady && (target.passed || infraBlocked)
println("CODE_READY=$codeReady TARGET_PASSED=${target.passed} INFRA_BLOCKED=$infraBlocked")
println("VERIFICATION_DECISION=${if (complete) "COMPLETE" else "INCOMPLETE"}")
```

#### Maven projects — `MavenRunConfigurationType.runConfiguration()`

> **⚠️ CRITICAL — After editing pom.xml: do NOT use this IDE runner immediately.**
> When `pom.xml` is modified, IntelliJ triggers a Maven project re-import that shows a
> modal dialog. This dialog **blocks the latch for up to 600 seconds** — wasting an entire
> agent turn. Multiply by 3 agents hitting the same issue = 1800 seconds lost.
>
> **Rule**: After editing `pom.xml`, use `ProcessBuilder("./mvnw", ...)` (see the
> "Run Unit Tests via Maven Wrapper" section below) as your PRIMARY test runner.
> The Maven IDE runner is reliable only when no build file was modified in the current session.
>
> If you do use the Maven IDE runner (e.g. for an unmodified project), always pass
> `dialog_killer: true` on the `steroid_execute_code` call to auto-dismiss any dialogs.
> If the latch still times out after 2 minutes, fall back to `ProcessBuilder("./mvnw", ...)`
> immediately — do not wait the full 10 minutes.

```kotlin
import org.jetbrains.idea.maven.execution.MavenRunConfigurationType
import org.jetbrains.idea.maven.execution.MavenRunnerParameters
import org.jetbrains.idea.maven.execution.MavenRunnerSettings
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.minutes

val result = CompletableDeferred<Boolean>()

// Subscribe BEFORE launching so we don't miss the event
val connection = project.messageBus.connect()
connection.subscribe(SMTRunnerEventsListener.TEST_STATUS, object : SMTRunnerEventsListener {
    override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) {
        val passed = testsRoot.isPassed()
        val failed = testsRoot.getAllTests().count { it.isDefect }
        println("Tests finished — passed=$passed failures=$failed")
        connection.disconnect()
        result.complete(passed)
    }
    override fun onTestingStarted(testsRoot: SMTestProxy.SMRootTestProxy) {}
    override fun onTestFailed(test: SMTestProxy) { println("FAILED: ${test.name}") }
    // ⚠️ ALL abstract methods must be implemented — SMTRunnerEventsListener is NOT an adapter class
    // (SMTRunnerEventsAdapter was removed in IntelliJ 2025.x; missing stubs → compilation failure)
    override fun onTestsCountInSuite(count: Int) {}
    override fun onTestStarted(test: SMTestProxy) {}
    override fun onTestFinished(test: SMTestProxy) {}
    override fun onTestIgnored(test: SMTestProxy) {}
    override fun onSuiteFinished(suite: SMTestProxy) {}
    override fun onSuiteStarted(suite: SMTestProxy) {}
    override fun onCustomProgressTestsCategory(categoryName: String?, count: Int) {}
    override fun onCustomProgressTestStarted() {}
    override fun onCustomProgressTestFailed() {}
    override fun onCustomProgressTestFinished() {}
    override fun onSuiteTreeNodeAdded(testProxy: SMTestProxy) {}
    override fun onSuiteTreeStarted(suite: SMTestProxy) {}
})

// Launch via Maven IDE runner (runs through Maven lifecycle, resolves deps)
MavenRunConfigurationType.runConfiguration(
    project,
    MavenRunnerParameters(
        /* isPomExecution= */ true,
        /* workingDirPath= */ project.basePath!!,
        /* pomFileName= */ "pom.xml",
        /* goals= */ listOf("test", "-Dtest=com.example.MyTest", "-Dspotless.check.skip=true"),
        /* profiles= */ emptyList()
    ),
    /* settings (MavenGeneralSettings) = */ null,
    /* runnerSettings (MavenRunnerSettings) = */ null,
) { /* ProgramRunner.Callback — completion handled by SMTRunnerEventsListener above */ }

val passed = withTimeout(5.minutes) { result.await() }
println("Result: passed=$passed")
```

> **⚠️ Docker / CI environments — use `dialog_killer: true`**: When running `MavenRunConfigurationType.runConfiguration()` in a Docker or CI container, Maven project-reimport dialogs can block the run silently for the full latch timeout (5 minutes wasted). Pass `dialog_killer: true` as the `steroid_execute_code` parameter to auto-dismiss these modals. If the latch still times out after 2-3 minutes despite `dialog_killer: true`, **stop waiting and fall back to `ProcessBuilder("./mvnw", ...)` immediately** — do not wait the full 5 minutes.

#### Gradle projects — `GradleRunConfiguration.setRunAsTest(true)`

```kotlin
import org.jetbrains.plugins.gradle.service.execution.GradleExternalTaskConfigurationType
import org.jetbrains.plugins.gradle.service.execution.GradleRunConfiguration
import com.intellij.execution.RunManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.minutes

val result = CompletableDeferred<Boolean>()

val connection = project.messageBus.connect()
connection.subscribe(SMTRunnerEventsListener.TEST_STATUS, object : SMTRunnerEventsListener {
    override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) {
        val passed = testsRoot.isPassed()
        println("Tests finished — passed=$passed")
        connection.disconnect()
        result.complete(passed)
    }
    override fun onTestingStarted(testsRoot: SMTestProxy.SMRootTestProxy) {}
    override fun onTestFailed(test: SMTestProxy) { println("FAILED: ${test.name}") }
    // ⚠️ ALL abstract methods must be implemented — SMTRunnerEventsListener is NOT an adapter class
    override fun onTestsCountInSuite(count: Int) {}
    override fun onTestStarted(test: SMTestProxy) {}
    override fun onTestFinished(test: SMTestProxy) {}
    override fun onTestIgnored(test: SMTestProxy) {}
    override fun onSuiteFinished(suite: SMTestProxy) {}
    override fun onSuiteStarted(suite: SMTestProxy) {}
    override fun onCustomProgressTestsCategory(categoryName: String?, count: Int) {}
    override fun onCustomProgressTestStarted() {}
    override fun onCustomProgressTestFailed() {}
    override fun onCustomProgressTestFinished() {}
    override fun onSuiteTreeNodeAdded(testProxy: SMTestProxy) {}
    override fun onSuiteTreeStarted(suite: SMTestProxy) {}
})

val configurationType = GradleExternalTaskConfigurationType.getInstance()
val factory = configurationType.configurationFactories[0]
val config = GradleRunConfiguration(project, factory, "Run MyTest")
config.settings.externalProjectPath = project.basePath!!
config.settings.taskNames = listOf(":test")
config.settings.scriptParameters = "--tests \"com.example.MyTest\""
config.setRunAsTest(true)  // CRITICAL: enables test console / SMTestProxy wiring

val runManager = RunManager.getInstance(project)
val settings = runManager.createConfiguration(config, factory)
runManager.addConfiguration(settings)
ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())

val passed = withTimeout(5.minutes) { result.await() }
println("Result: passed=$passed")
```

#### Auto-detect runner via ConfigurationContext (simplest, works for any build system)

This is exactly what the green ▶ gutter button does:

```text
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.actionSystem.CommonDataKeys

val psiClass = readAction {
    JavaPsiFacade.getInstance(project).findClass("com.example.MyTest", projectScope())
} ?: error("Class not found")

val settings = readAction {
    ConfigurationContext.getFromContext(
        SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.PSI_ELEMENT, psiClass)
            .build()
    ).configuration
} ?: error("No run configuration produced for this class")

// Auto-selects Maven/Gradle/JUnit runner based on project structure
ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
println("Test started — check IDE Test Results window")
```

---
### Run Unit Tests via Maven Wrapper (PRIMARY after pom.xml edits; fallback otherwise)

**Use `./mvnw` as your PRIMARY test runner whenever you have edited `pom.xml`** in the current session — the Maven IDE runner triggers a re-import modal that blocks for up to 600 seconds after build file changes. For sessions where `pom.xml` was NOT modified, prefer the IDE runner above (avoids 200k-char output truncation).

> **⚠️ CRITICAL — Output Truncation Required**: Spring Boot integration test output routinely exceeds
> **200k characters** (Spring context startup ~100 lines, Flyway migration logs, Testcontainers Docker
> pull logs, full stack traces). Printing the full output causes MCP token limit errors.
> **Always use `takeLast()` to read only the relevant tail**:

```kotlin
// ⚠️ FALLBACK ONLY — use the JUnit or Maven IDE runner below when possible
// ⚠️ Always use ./mvnw (Maven wrapper) not 'mvn' — system mvn is not installed in arena environments
// ⚠️ NEVER print process.inputStream.bufferedReader().readText() — Spring Boot output can be 200k+ chars
val process = ProcessBuilder("./mvnw", "test", "-Dtest=MyValidatorTest", "-Dspotless.check.skip=true", "-q")
    .directory(java.io.File(project.basePath!!))
    .redirectErrorStream(true).start()
val lines = process.inputStream.bufferedReader().readLines()
val exitCode = process.waitFor()
println("Exit: $exitCode | total output lines: ${lines.size}")
// ⚠️ Capture BOTH ends: Spring context / Testcontainers errors appear at the START of output;
// Maven BUILD FAILURE summary appears at the END. Using takeLast alone loses early failures.
println("--- First 30 lines (Spring context / Testcontainers errors appear here) ---")
println(lines.take(30).joinToString("\n"))
println("--- Last 30 lines (Maven BUILD FAILURE summary appears here) ---")
println(lines.takeLast(30).joinToString("\n"))
```

> **⚠️ Run FAIL_TO_PASS tests one at a time** — not all at once. Running multiple Spring Boot tests in
> one Maven call multiplies startup log output (4 tests × 25k chars each = 100k+ chars), causing MCP
> token overflow errors that require multi-step Bash parsing to recover from. Always run individually:
> `-Dtest=SingleTestClass` not `-Dtest=Test1,Test2,Test3,Test4`.

> **⚠️ After FAIL_TO_PASS tests pass: run the FULL test class for regression check**.
> Using method-level filtering (`-Dtest=ClassName#method1+method2`) runs only the new tests —
> pre-existing tests updated by the test patch are not exercised and can silently regress.
> Always run class-level: `-Dtest=UserRestControllerTests` (no `#method` suffix) to exercise
> all methods and confirm no regressions before outputting `ARENA_FIX_APPLIED: yes`.

> **⚠️ After FAIL_TO_PASS class passes: run the FULL project test suite** (`./mvnw test`, no `-Dtest=` filter)
> to catch regressions in OTHER test classes that share service or data layer code with FAIL_TO_PASS.
> Example: adding password validation to `UserServiceImpl` may break `AbstractUserServiceTests`,
> `UserServiceJdbcTests`, `UserServiceJpaTests`, `UserServiceSpringDataJpaTests` if they use `"password"`
> as test data. These tests are not in FAIL_TO_PASS but will fail after your change.
> **Before outputting `ARENA_FIX_APPLIED: yes`**: (1) Run class-level FAIL_TO_PASS tests → pass,
> (2) Search for `Abstract*Tests` and other test classes sharing the same data, update them if needed,
> (3) Run `./mvnw test` (full suite, no filter) → exit 0. Only then output the success marker.

> **⚠️ When take/takeLast is not enough** (output still exceeds limit after first+last 30 lines):
> Use keyword filtering to extract only signal lines from verbose Spring Boot / Testcontainers output:

```kotlin
// Keyword-filtered Maven output — use when verbose Spring Boot output exceeds MCP token limit
// even after take(30)+takeLast(30). Prevents multi-step Bash parsing recovery (saves 3-5 turns).
val process = ProcessBuilder("./mvnw", "test", "-Dtest=OnlyOneTestClass", "-Dspotless.check.skip=true", "-q")
    .directory(java.io.File(project.basePath!!)).redirectErrorStream(true).start()
val lines = process.inputStream.bufferedReader().readLines()
val completed = process.waitFor(180, java.util.concurrent.TimeUnit.SECONDS)
val keywords = listOf("Tests run:", "FAILED", "ERROR", "Caused by:", "BUILD", "Could not", "Exception in")
println("Exit: ${if (completed) process.exitValue() else "TIMEOUT"} | total lines: ${lines.size}")
println("--- First 20 lines (Spring startup errors) ---")
lines.take(20).forEach(::println)
println("--- Signal lines only ---")
lines.filter { l -> keywords.any { k -> k in l } }.take(50).forEach(::println)
println("--- Last 15 lines (Maven BUILD FAILURE) ---")
lines.takeLast(15).forEach(::println)
```

Similarly for `test-compile` (project-wide dependency check, faster than full test run):
```kotlin
val process = ProcessBuilder("./mvnw", "test-compile", "-Dspotless.check.skip=true", "-q")
    .directory(java.io.File(project.basePath!!))
    .redirectErrorStream(true).start()
val lines = process.inputStream.bufferedReader().readLines()
val exitCode = process.waitFor()
println("Compile exit: $exitCode | lines: ${lines.size}")
// Compile errors may appear anywhere — capture both ends for full context
println(lines.take(20).joinToString("\n"))
println("---")
println(lines.takeLast(20).joinToString("\n"))
```

> **⚠️ Deprecation warnings are non-fatal**: Output like `warning: 'getVirtualFilesByName(String, GlobalSearchScope)' is deprecated` does not indicate failure — the script ran successfully. Do NOT retry just because of deprecation warnings; only retry on actual `ERROR` responses.

### Run Gradle Tests via ExternalSystemUtil ★ PREFERRED for Gradle ★

> **⚠️ Anti-pattern**: Never use `ProcessBuilder("./gradlew", ...)` **inside** `steroid_execute_code`.
> This spawns a nested Gradle daemon from within the IDE JVM, causing classpath conflicts and
> resource exhaustion. Use the IntelliJ ExternalSystem API below instead. If the IDE runner is
> unavailable, fall back to the Bash tool (outside exec_code) — NOT ProcessBuilder inside exec_code.

```kotlin
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import org.jetbrains.plugins.gradle.util.GradleConstants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.minutes

val result = CompletableDeferred<Boolean>()

val settings = ExternalSystemTaskExecutionSettings().apply {
    externalProjectPath = project.basePath!!
    taskNames = listOf(":api:test", "--tests", "com.example.api.ProductControllerTest")
    externalSystemIdString = GradleConstants.SYSTEM_ID.toString()
    vmOptions = "-Xmx512m"
}

ExternalSystemUtil.runTask(
    settings,
    com.intellij.execution.executors.DefaultRunExecutor.EXECUTOR_ID,
    project,
    GradleConstants.SYSTEM_ID,
    object : TaskCallback {
        override fun onSuccess() { result.complete(true) }
        override fun onFailure() { result.complete(false) }
    },
    ProgressExecutionMode.IN_BACKGROUND_ASYNC,
    false
)
val gradleSuccess = withTimeout(5.minutes) { result.await() }
println("Gradle result: success=$gradleSuccess")
```

> If the IDE runner is not available or times out, use the Bash tool **outside exec_code**:
> `./gradlew :api:test --tests "com.example.api.ProductControllerTest" --no-daemon -q`
> Do NOT use ProcessBuilder inside exec_code for this.

### Run Gradle Tests via ProcessBuilder (fallback — use Bash tool instead when possible)

> **⚠️ FALLBACK ONLY**: Prefer the ExternalSystemUtil approach above. If you must use ProcessBuilder
> (e.g. when IDE runner hangs), note that this spawns a child Gradle process inside the IDE JVM.
> When possible, use the Bash tool outside exec_code instead (avoids classpath conflicts).

For **Gradle** projects, use `./gradlew` with `--tests` for targeted test class execution.

> **⚠️ CRITICAL — Output Truncation Required**: Same as Maven — Gradle integration test output can be 200k+ chars. **Always use `takeLast()` and `take()` to capture both ends.**

> **⚠️ UP-TO-DATE false-positive after writing new files**: After creating new source files via `writeAction { VfsUtil.saveText(...) }`, Gradle may report the test task as `UP-TO-DATE` and skip executing tests entirely — yet still exit with code 0 and print `BUILD SUCCESSFUL`. The task inputs appear unchanged from Gradle's perspective because the compilation cache was not invalidated. **Always add `--rerun-tasks` to the FIRST Gradle test invocation after writing new source files.** If you see `BUILD SUCCESSFUL` with no `Tests run:` line in the output, add `--rerun-tasks` and rerun.

```kotlin
// Run a specific test class in a specific Gradle submodule
// ⚠️ After writing new source files: ALWAYS add --rerun-tasks to the first test run
// to avoid the UP-TO-DATE false-positive (Gradle skips tests silently, exits 0)
val proc = ProcessBuilder("./gradlew", ":product-service:test",
    "--tests", "com.example.product.ProductServiceTest",
    "--rerun-tasks", "--no-daemon")
    .directory(java.io.File(project.basePath!!))
    .redirectErrorStream(true).start()
val lines = proc.inputStream.bufferedReader().readLines()
val exit = proc.waitFor()
println("Exit: $exit | total lines: ${lines.size}")
// ⚠️ Capture BOTH ends: Spring context / startup errors appear at the START;
// Gradle BUILD FAILED summary with test counts appears at the END.
println("--- First 30 lines (Spring context / Testcontainers errors appear here) ---")
println(lines.take(30).joinToString("\n"))
println("--- Last 30 lines (Gradle BUILD FAILED summary appears here) ---")
println(lines.takeLast(30).joinToString("\n"))
```

```kotlin
// Run ALL tests in a module (when no specific class is needed):
val proc = ProcessBuilder("./gradlew", ":product-service:test", "--no-daemon", "-q")
    .directory(java.io.File(project.basePath!!))
    .redirectErrorStream(true).start()
val lines = proc.inputStream.bufferedReader().readLines()
val exit = proc.waitFor()
println("Exit: $exit | lines: ${lines.size}")
println(lines.take(30).joinToString("\n"))
println("---")
println(lines.takeLast(30).joinToString("\n"))
```

**Gradle vs Maven cheat sheet:**

| Action | Maven | Gradle |
|--------|-------|--------|
| Run one test class | `-Dtest=SimpleClassName` | `--tests "com.example.FullyQualifiedClassName"` |
| Run one test method | `-Dtest=ClassName#method` | `--tests "com.example.ClassName.method"` |
| Target a module | `-pl product-service` | `:product-service:test` |
| Skip spotless | `-Dspotless.check.skip=true` | (not needed usually) |
| No daemon | n/a | `--no-daemon` |
| Quiet output | `-q` | `-q` |
| Force re-run (post-write) | (Maven always reruns) | `--rerun-tasks` |
### Environment Diagnostics (Docker / System — Consolidated)

**Consolidate all Docker and system environment checks into ONE `steroid_execute_code` call** instead of multiple Bash tool calls (each Bash call costs ~20s overhead). This single call replaces 8+ separate Bash commands (`docker info`, `ls /var/run/docker.sock`, `find / -name docker*`, `env | grep DOCKER`, `env | grep TESTCONTAINER`, `ps aux | grep docker`, etc.):

```kotlin
// ONE call replaces 8 separate Bash diagnostics — saves ~160s round-trip overhead
val dockerEnv = System.getenv().filter { (k, _) ->
    k.contains("DOCKER", ignoreCase = true) || k.contains("TESTCONTAINERS", ignoreCase = true)
}
println("Docker/TC env vars: $dockerEnv")
println("docker.sock exists: ${java.io.File("/var/run/docker.sock").exists()}")
val dockerBin = try {
    ProcessBuilder("which", "docker").start().inputStream.bufferedReader().readText().trim()
} catch (e: Exception) { "not found: $e" }
println("docker binary: $dockerBin")
println("dockerd exists: ${java.io.File("/usr/bin/dockerd").exists() || java.io.File("/usr/local/bin/dockerd").exists()}")
println("podman exists: ${java.io.File("/usr/bin/podman").exists()}")
println("PATH: ${System.getenv("PATH")}")
```

> **Key principle**: If you need 3+ diagnostic shell commands, collapse them into ONE `steroid_execute_code` call. The JVM inside IntelliJ has unrestricted filesystem and process access — identical to what Bash can do, but without the per-call overhead.

> **⚡ Proactive Docker pre-check — TRIGGER: run on your VERY FIRST `steroid_execute_code` call when**
> **FAIL_TO_PASS tests use `@Testcontainers` or extend `AbstractIT` / `IntegrationTest` / `AbstractITBase`.**
> Do NOT wait for test failures to discover Docker unavailability — that wastes 8+ turns (~3 min).
> Combine with your IDE readiness probe so it costs zero extra turns:

```kotlin
// STEP ZERO: combine IDE probe + Docker check in one call (before any implementation)
println("Project: ${project.name} @ ${project.basePath}")
println("Smart mode: ${!com.intellij.openapi.project.DumbService.isDumb(project)}")
val dp = ProcessBuilder("docker", "info").redirectErrorStream(true).start()
val dockerOk = dp.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && dp.exitValue() == 0
println("Docker available: $dockerOk")
// Decision based on result:
// dockerOk=true  → proceed normally; run @Testcontainers tests as final verification
// dockerOk=false → use runInspectionsDirectly for compile verification only; DO NOT run integration tests
//                  report ARENA_FIX_APPLIED: yes once inspections pass; do NOT investigate Docker further
```

> **When to stop investigating Docker failures**: If `./mvnw test` fails with `Could not find a valid Docker environment` AND an existing test (pre-patch) fails with the same error, the environment lacks Docker. This is an **infrastructure constraint, not a code defect**. Do NOT investigate further — use `runInspectionsDirectly` as your final verification and declare your fix complete.

### Baseline-vs-Target Infrastructure Probe (Deterministic)

Before declaring "environment blocked", run one **baseline** integration test and one **target**
integration test, then compare signatures. Same infra signature in both means the blocker is
environmental, not patch-specific.

```kotlin
import java.io.File

fun runSingleMavenTest(testClass: String): List<String> {
    val process = ProcessBuilder("./mvnw", "test", "-Dtest=$testClass", "-q")
        .directory(File(project.basePath!!))
        .redirectErrorStream(true)
        .start()
    val lines = process.inputStream.bufferedReader().readLines()
    process.waitFor()
    return lines
}

fun hasDockerInfraSignature(lines: List<String>): Boolean =
    lines.any { "Could not find a valid Docker environment" in it }

val baselineLines = runSingleMavenTest("com.example.ExistingIntegrationTest")
val targetLines = runSingleMavenTest("com.example.NewBehaviorIntegrationTest")

val baselineInfraBlocked = hasDockerInfraSignature(baselineLines)
val targetInfraBlocked = hasDockerInfraSignature(targetLines)

println("BASELINE_INFRA_BLOCKED=$baselineInfraBlocked")
println("TARGET_INFRA_BLOCKED=$targetInfraBlocked")

if (baselineInfraBlocked && targetInfraBlocked) {
    println("ENVIRONMENT_STATUS=BLOCKED")
    println("NEXT_STEP=Use inspections/build checks and finalize based on code correctness")
} else {
    println("ENVIRONMENT_STATUS=AVAILABLE_OR_DIFFERENT_FAILURE")
    println("NEXT_STEP=Treat target failure as code/test issue and continue debugging")
}
```

### Run a Specific JUnit Test Class via IntelliJ Runner (non-Maven/Gradle only)

> ⚠️ **Only use `JUnitConfiguration` for projects that do NOT use Maven or Gradle** (e.g. pure
> IntelliJ module projects). For Maven/Gradle projects use `MavenRunConfigurationType` or
> `GradleRunConfiguration` from the ★ PREFERRED ★ section above.

```kotlin
import com.intellij.execution.junit.JUnitConfiguration
import com.intellij.execution.junit.JUnitConfigurationType
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor

val factory = JUnitConfigurationType.getInstance().configurationFactories.first()
val settings = RunManager.getInstance(project).createConfiguration("Run test", factory)
val config = settings.configuration as JUnitConfiguration
val data = config.persistentData               // typed as JUnitConfiguration.Data
data.TEST_OBJECT = JUnitConfiguration.TEST_CLASS  // ← must use constant, NOT string "class"
data.MAIN_CLASS_NAME = "com.example.MyValidatorTest"
config.setWorkingDirectory(project.basePath!!)
RunManager.getInstance(project).addConfiguration(settings)
ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
println("Test run started")
// ⚠️ Pitfall: writing data.TEST_OBJECT = "class" → compile error "unresolved reference 'TEST_CLASS'"
// Always use the constant: JUnitConfiguration.TEST_CLASS
```

### Get Per-Test Breakdown via SMTestProxy

`SMTRunnerEventsListener.TEST_STATUS` works for all runners (Maven, Gradle, JUnit). Subscribe
before launching the test. Use `testsRoot.isPassed()` for overall pass/fail:

```kotlin
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.minutes

val result = CompletableDeferred<Unit>()

// Subscribe BEFORE launching (don't miss the event)
val connection = project.messageBus.connect()
connection.subscribe(SMTRunnerEventsListener.TEST_STATUS, object : SMTRunnerEventsListener {
    override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) {
        val passed = testsRoot.isPassed()
        val hasErrors = testsRoot.hasErrors()
        val allTests = testsRoot.getAllTests()
        val failCount = allTests.count { it.isDefect }
        println("Done — passed=$passed errors=$hasErrors failures=$failCount")
        allTests.filter { it.isDefect }.forEach { println("  FAILED: ${it.name}") }
        connection.disconnect()
        result.complete(Unit)
    }
    override fun onTestingStarted(testsRoot: SMTestProxy.SMRootTestProxy) {}
    override fun onTestFailed(test: SMTestProxy) {}
    // ⚠️ ALL abstract methods must be implemented — SMTRunnerEventsListener is NOT an adapter class
    override fun onTestsCountInSuite(count: Int) {}
    override fun onTestStarted(test: SMTestProxy) {}
    override fun onTestFinished(test: SMTestProxy) {}
    override fun onTestIgnored(test: SMTestProxy) {}
    override fun onSuiteFinished(suite: SMTestProxy) {}
    override fun onSuiteStarted(suite: SMTestProxy) {}
    override fun onCustomProgressTestsCategory(categoryName: String?, count: Int) {}
    override fun onCustomProgressTestStarted() {}
    override fun onCustomProgressTestFailed() {}
    override fun onCustomProgressTestFinished() {}
    override fun onSuiteTreeNodeAdded(testProxy: SMTestProxy) {}
    override fun onSuiteTreeStarted(suite: SMTestProxy) {}
})

// Then launch via MavenRunConfigurationType or GradleRunConfiguration (see ★ PREFERRED ★ above)
// ... launch code here ...

withTimeout(5.minutes) { result.await() }
```

### Check Compile Errors Without Running Full Build

**Always run this BEFORE `./mvnw test`** — it catches errors in seconds, not minutes. If this reports errors, fix them before running the Maven test command.

> **⚠️ Scope limitation**: `runInspectionsDirectly` is **file-scoped** — it only analyzes the single
> file you pass. It does NOT catch compile errors in OTHER files that reference your changed signatures.
> After modifying a widely-used class (DTO, command, entity, record), also check the key dependent files
> (service, controller, mapper, test), or run `./mvnw compile -q` (with takeLast() truncation) for
> project-wide verification.
>
> **Staged verification recipe (Maven projects)**:
> 1. `runInspectionsDirectly(vf)` for each changed file — catches single-file syntax/import errors (~5s each)
> 2. `./mvnw compile -q` — catches cross-file type errors, missing methods, broken call sites (~30-60s)
> 3. `./mvnw test -Dtest=TargetTest` — only after steps 1+2 pass (runs Docker-dependent tests)
>
> Do NOT skip step 2 and jump directly to step 3 — a compile error in a dependent file will fail the test
> with a confusing runtime stacktrace rather than a clean compile error message.

> **⚠️ Spring bonus — also catches bean conflicts**: `runInspectionsDirectly` detects Spring Framework
> issues beyond compile errors: duplicate `@Bean` method definitions in `@Configuration` classes (causes
> `NoUniqueBeanDefinitionException` at startup), missing `@Component` / `@Service` annotations, and
> unresolved `@Autowired` dependencies. Run it on `@Configuration` classes **BEFORE** `./mvnw test`
> to catch Spring startup failures in ~5s instead of waiting for a 90s Maven cold-start.

```kotlin
// Faster than 'mvn test' — returns IDE inspection results in seconds
// Run this after creating/modifying files, BEFORE running ./mvnw test
val vf = findProjectFile("src/main/java/com/example/Product.java")!!
val problems = runInspectionsDirectly(vf)
if (problems.isEmpty()) {
    println("No problems found — safe to run tests")
} else {
    problems.forEach { (id, descs) ->
        descs.forEach { println("[$id] ${it.descriptionTemplate}") }
    }
    println("Fix the above errors before running tests")
}
// Also check key dependent files to catch cross-file breakage:
for (depPath in listOf(
    "src/main/java/com/example/service/ProductService.java",
    "src/main/java/com/example/api/ProductController.java"
)) {
    val depVf = findProjectFile(depPath) ?: continue
    val depProblems = runInspectionsDirectly(depVf)
    if (depProblems.isNotEmpty()) {
        println("Problems in $depPath:")
        depProblems.forEach { (id, descs) -> descs.forEach { println("  [$id] ${it.descriptionTemplate}") } }
    }
}
```

### Inspection Result: ClassCanBeRecord → Always Convert for New DTO Classes

> **When creating new DTO, data, or value classes** and `runInspectionsDirectly` reports
> `[ClassCanBeRecord]`, **always convert the class to a Java `record`**. This is not an optional
> style suggestion for new code — the inspection is telling you the class *should be* a record.
> The reference solution typically uses Java records for DTOs; failing to convert causes a structural
> mismatch with expected behavior.
>
> **Do NOT ignore `ClassCanBeRecord` on newly-created DTO/data classes.** Treat it as a required
> action, not informational noise.

```kotlin
// WRONG: create as traditional class and ignore ClassCanBeRecord warning
// public class ProductAggregate { private String name; ... }

// CORRECT: create as Java record from the start
writeAction {
    val root = LocalFileSystem.getInstance().findFileByPath(project.basePath!!)!!
    val dir = VfsUtil.createDirectoryIfMissing(root, "src/main/java/com/example/api")
    val f = dir.findChild("ProductAggregate.java") ?: dir.createChildData(this, "ProductAggregate.java")
    VfsUtil.saveText(f, listOf(
        "package com.example.api;",
        "",
        "public record ProductAggregate(String name, int weight) {}"
    ).joinToString("\n"))
}
// After writing, run runInspectionsDirectly to confirm ClassCanBeRecord is gone
```

### Inspection Result: ClassEscapesItsScope for Spring Beans → Expected, Non-Blocking

> **`[ClassEscapesItsScope]`** appears on Spring `@Service`, `@Repository`, and `@Component` beans that expose package-private types through public methods (e.g. a `public` method returning a package-private domain object). This is **expected in Spring Boot projects** and non-blocking — it does not prevent compilation or deployment.
>
> **Before spending a turn trying to fix it**: Check whether the same warning appears on existing (pre-patch) services or repositories in the project. If `FeatureService`, `FavoriteFeatureService`, or other existing beans have the same warning, your new `@Service` will too — it is a deliberate design pattern in the codebase. Do NOT refactor to fix it; simply note it and move on.

### Inspection Result: ConstantValue "Value is always null" on DTO Accessor → CRITICAL Bug

> **`[ConstantValue] Value ... is always 'null'`** on a DTO method call (e.g. `dto.releasedAt()`, `dto.status()`, `dto.version()`) in a test file is a **critical data-flow finding, NOT a style warning**. IntelliJ's type system has proven the accessor always returns `null` — which happens when the **DTO record is missing that component field** (the accessor method does not exist or returns null unconditionally).
>
> **Do NOT dismiss `ConstantValue` on DTO/record accessor calls as "pre-existing static analysis notes" or "noise".** It is a guaranteed runtime `NullPointerException` or assertion failure at test execution time.

**Severity classification — prevents misclassification:**

| Inspection ID | Severity | Action |
|---------------|----------|--------|
| `ConstantValue` ("always null/true/false") | **CRITICAL** — runtime failure guaranteed | Investigate immediately |
| `AssertBetweenInconvertibleTypes` | **CRITICAL** — assertion always passes/fails | Investigate |
| `ClassCanBeRecord` | **REQUIRED** — structural mismatch | Convert to record |
| `ClassEscapesItsScope` on Spring beans | **EXPECTED** — ignore | Skip |
| `DeprecatedIsStillUsed` | **LOW** — cosmetic | Fix if time allows |
| `GrazieInspectionRunner` | **COSMETIC** — grammar | Ignore |

**Diagnosis recipe** — run when you see `[ConstantValue] Value ... is always 'null'` on a DTO accessor:

```text
// Step 1: Read the DTO record source to see its actual component list
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
val dtoFile = readAction {
    FilenameIndex.getVirtualFilesByName("ReleaseDto.java", GlobalSearchScope.projectScope(project)).firstOrNull()
}
if (dtoFile != null) {
    println("=== DTO source ===")
    println(VfsUtilCore.loadText(dtoFile))
}

// Step 2: Cross-reference with what the test calls on the DTO
val testFile = readAction {
    FilenameIndex.getVirtualFilesByName("ReleaseControllerTests.java", GlobalSearchScope.projectScope(project)).firstOrNull()
}
if (testFile != null) {
    val text = VfsUtilCore.loadText(testFile)
    // Extract .methodName() calls that look like DTO accessors (lower-camel, not assertion calls):
    val dtoCalls = Regex("\\.([a-z][a-zA-Z0-9]+)\\(\\)")
        .findAll(text)
        .map { it.groupValues[1] }
        .filter { it !in setOf("body", "isEqualTo", "isNotNull", "statusCode", "then", "when", "get", "size", "isEmpty") }
        .toSet()
    println("DTO methods called in tests: $dtoCalls")
}
// Compare output: methods in dtoCalls absent from DTO record components = missing fields to add
```

> **Fix**: Add the missing components to the DTO `record` definition. For example, if `ReleaseDto` is
> `public record ReleaseDto(String name, String version)` and the test calls `dto.releasedAt()`, add
> `Instant releasedAt` to the record — and update the mapper/service/query that constructs the DTO.

---

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
import com.intellij.openapi.command.writeCommandAction

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
    writeCommandAction(project) {
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

```kotlin
// Read pom.xml
val pomContent = VfsUtil.loadText(findProjectFile("pom.xml")!!)
println(pomContent)

// Read a specific test file to understand its assertions before implementing
val testContent = VfsUtil.loadText(findProjectFile("src/test/java/com/example/ProductTest.java")!!)
println(testContent)
```

### Targeted File Read (Minimal Context — Avoid Context Bloat)

Instead of printing the full file, filter for the lines you need:

```kotlin
// Extract only test assertions and endpoint URLs from a large test file
val testContent = VfsUtil.loadText(findProjectFile("src/test/java/com/example/MyIntegrationTest.java")!!)
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

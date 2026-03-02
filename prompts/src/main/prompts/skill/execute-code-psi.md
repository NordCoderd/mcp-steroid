Execute Code: PSI Operations

PSI structural queries, ReferencesSearch, JavaPsiFacade patterns, and PSI vs file-read decision rules.

# Execute Code: PSI Operations

## ⚡ PSI Structural Query — Explore Class Structure WITHOUT Reading Files

Replaces 5-10 `VfsUtil.loadText` calls with a single PSI query:
```kotlin[IU]
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
// When you need to know a class's methods, fields, or call-sites — use PSI.
// This 1 call replaces reading 5-10 separate files just to trace code flow.
val cls = readAction {
    JavaPsiFacade.getInstance(project).findClass(
        "com.example.domain.FeatureService",   // ← actual FQN from pom.xml group + class name
        GlobalSearchScope.projectScope(project)
    )
}
// Print all methods (no file read needed):
cls?.methods?.forEach { m ->
    val params = m.parameterList.parameters.joinToString { "${it.name}: ${it.type.presentableText}" }
    println("${m.name}($params): ${m.returnType?.presentableText}")
}
// Also print fields and implemented interfaces in the same call:
cls?.fields?.forEach { f -> println("field ${f.name}: ${f.type.presentableText}") }
cls?.implementsListTypes?.forEach { t -> println("implements: ${t.presentableText}") }
```

> **PSI vs file read comparison:**
> - **VfsUtil.loadText()** on a 200-line service file → you receive 200 lines, parse mentally, extract ~10 method signatures
> - **JavaPsiFacade.findClass() + .methods** → you receive ~10 lines of compact signatures directly, ready to use
>
> **Rule**: If you're about to read a 3rd file just to trace code flow, use `ReferencesSearch.search()` or `JavaPsiFacade.findClass()` instead. PSI answers in 1 call what file reading needs 5-10 calls to reconstruct.
>
> **When to use PSI vs file read:**
> - PSI: when you need structure (method signatures, field types, implemented interfaces, call sites)
> - File read: when you need full implementation details (method bodies, SQL queries, config file content)

---

## Find ALL Callers/Usages — PREFERRED Over Grepping Source Files

```text
// Find ALL callers/usages (replaces grepping through source files):
import com.intellij.psi.search.searches.ReferencesSearch
ReferencesSearch.search(cls!!, projectScope()).findAll().forEach { ref ->
    val snippet = ref.element.parent.text.take(80)
    println("${ref.element.containingFile.name} → $snippet")
}
```

---

## Find All Usages of a Class (Call Sites, Constructor Invocations)

**CRITICAL when adding new fields to command/DTO objects** — find every call site first so you can update all constructors before running the compiler:
```kotlin[IU]
import com.intellij.psi.search.searches.ReferencesSearch
val cmdClass = readAction {
    JavaPsiFacade.getInstance(project).findClass("com.example.CreateReleaseCommand", projectScope())
}
if (cmdClass != null) {
    ReferencesSearch.search(cmdClass, projectScope()).findAll().forEach { ref ->
        val file = ref.element.containingFile.virtualFile.path.substringAfterLast('/')
        println("$file → " + ref.element.parent.text.take(100))
    }
} else println("class not found")
```

---

## Find @Repository Methods with @Query Annotations
```kotlin[IU]
val repo = readAction {
    JavaPsiFacade.getInstance(project).findClass("com.example.ReleaseRepository", projectScope())
}
repo?.methods?.forEach { m ->
    val q = m.annotations.firstOrNull { it.qualifiedName?.endsWith("Query") == true }
    if (q != null) println("@Query ${m.name}: " + (q.findAttributeValue("value")?.text ?: ""))
}
```

---

## Find All @Entity Classes in the Project
```kotlin[IU]
import com.intellij.psi.search.searches.AnnotatedElementsSearch
val entityAnnotation = readAction {
    JavaPsiFacade.getInstance(project).findClass("jakarta.persistence.Entity", allScope())
}
AnnotatedElementsSearch.searchPsiClasses(entityAnnotation!!, projectScope()).findAll()
    .forEach { println(it.qualifiedName) }
```

---

## Discover Existing Class Naming Conventions Before Creating New Classes

Avoids naming mismatches like `CreateCommentPayload` vs `AddReplyPayload` vs `CreateReplyPayload`. Always do this FIRST when the test doesn't import the payload class directly:
```kotlin[IU]
import com.intellij.psi.search.PsiShortNamesCache
val allNames = readAction { PsiShortNamesCache.getInstance(project).allClassNames.toList() }
allNames.filter { it.endsWith("Payload") || it.endsWith("Request") || it.endsWith("Dto") ||
    it.endsWith("Status") || it.endsWith("Type") || it.endsWith("Service") }
    .sorted().forEach { println(it) }
```

---

## Check if a Java Class Already Exists
```kotlin[IU]
val existing = readAction {
    com.intellij.psi.JavaPsiFacade.getInstance(project).findClass(
        "com.example.MyClass",
        com.intellij.psi.search.GlobalSearchScope.projectScope(project)
    )
}
println(if (existing == null) "NOT_FOUND: safe to create" else "EXISTS: " + existing.containingFile.virtualFile.path)
```

---

## Discover REST Endpoint Mappings via PSI

PREFERRED over string-searching source — correctly handles class-level `@RequestMapping` + method-level `@GetMapping` combinations:
```kotlin[IU]
import com.intellij.psi.search.GlobalSearchScope
val controllerClass = readAction {
    JavaPsiFacade.getInstance(project).findClass(
        "com.example.api.controllers.FeatureReactionController",
        GlobalSearchScope.projectScope(project)
    )
}
readAction {
    controllerClass?.methods?.forEach { method ->
        val ann = method.annotations.firstOrNull { a ->
            listOf("GetMapping","PostMapping","DeleteMapping","PutMapping","PatchMapping","RequestMapping")
                .any { a.qualifiedName?.endsWith(it) == true }
        }
        if (ann != null) {
            val path = ann.findAttributeValue("value")?.text ?: ann.findAttributeValue("path")?.text ?: "\"\""
            println("${method.name}: ${ann.qualifiedName?.substringAfterLast('.')} $path")
        }
    }
}
```

---

## Targeted File Read — Extract Only Relevant Lines to Avoid Context Bloat
```text
val testContent = VfsUtil.loadText(findProjectFile("src/test/java/com/example/MyTest.java")!!)
testContent.lines()
    .filter { it.contains("assert") || it.contains("/api/") || it.contains("@Test") }
    .forEach { println(it) }
```

---

## Find Next Flyway Migration Version Number

Avoids `V5__` collision if `V5__` already exists:
```kotlin[IU]
val migDir = findProjectFile("src/main/resources/db/migration")!!
val nextVersion = readAction {
    migDir.children.map { it.name }
        .mapNotNull { Regex("V(\\d+)__").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        .maxOrNull()?.plus(1) ?: 1
}
println("NEXT_MIGRATION=V" + nextVersion)
```

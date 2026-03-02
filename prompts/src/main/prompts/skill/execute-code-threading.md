Execute Code: Threading Rules

Threading model, readAction/writeAction requirements, VFS mutation rules, and edtWriteAction usage for steroid_execute_code.

# Execute Code: Threading Rules

## Quick Start

Your code is a **suspend function body** (never use `runBlocking`):
- Use `readAction { }` for PSI/VFS reads, `writeAction { }` for modifications
- `waitForSmartMode()` runs automatically before your script
- Available: `project`, `println()`, `printJson()`, `printException()`, `progress()`

**⚠️ Helper functions that call `readAction`/`writeAction` MUST be `suspend fun`** — a regular `fun` that calls these gets a compile error: `"suspension functions can only be called within coroutine body"`. This applies to ALL suspend-context APIs: `readAction`, `writeAction`, `smartReadAction`, `waitForSmartMode`, `runInspectionsDirectly`.

---

## ⚠️ THREADING RULE — NEVER SKIP

Any PSI access (`JavaPsiFacade`, `PsiShortNamesCache`, `PsiManager.findFile`, `ProjectRootManager.contentSourceRoots`, module roots, annotations, etc.) **MUST** be wrapped in `readAction { }`. Modifications require `writeAction { }`. Threading violations throw immediately at runtime — they are not silently ignored. **This applies to ALL PSI calls including your very first exploration call** (e.g. listing source roots). This is the most common first-attempt error.

---

## ⚠️ writeAction { } Is NOT a Coroutine Scope

Calling `readAction { }` or ANY suspend function inside `writeAction { }` throws `suspension functions can only be called within coroutine body`. **ALWAYS read first (outside), then write (inside)**:
```text
val vf = findProjectFile("src/main/java/com/example/Foo.java")!!
val content = VfsUtil.loadText(vf)               // read OUTSIDE writeAction

// ⚠️ BEFORE content.replace() — ALWAYS print the excerpt BEFORE THE FIRST ATTEMPT:
// Do NOT print it only after a failure — that costs an extra turn.
val idx = content.indexOf("methodName")
println("EXCERPT:\n" + content.substring(idx, (idx + 250).coerceAtMost(content.length)))

// Only then do the replace, verifying the result is different:
val updated = content.replace("oldString", "newString")
check(updated != content) { "content.replace matched nothing — whitespace mismatch!" }
writeAction { VfsUtil.saveText(vf, updated) }    // write INSIDE — no suspend calls allowed

// After bulk VFS edits, flush to disk before running git/shell subprocesses:
LocalFileSystem.getInstance().refresh(false)     // ensures git diff sees the changes
```

Use `edtWriteAction { }` (a suspend wrapper) if you need suspend calls inside the write block.

---

## ⚠️ ALL VFS Mutation Ops Need writeAction — Not Just saveText

`createDirectoryIfMissing()`, `createChildData()`, `createChildFile()`, `createChildDirectory()`, `delete()`, `rename()`, `move()`, and `saveText()` ALL require `writeAction`. Calling any of these OUTSIDE a `writeAction` throws `Write access is allowed inside write-action only` at runtime. Always put the ENTIRE create-directory-and-write sequence inside a SINGLE `writeAction` block:

```text
writeAction {
    val root = LocalFileSystem.getInstance().findFileByPath(project.basePath!!)!!
    val dir = VfsUtil.createDirectoryIfMissing(root, "src/main/java/com/example/model")  // ← needs writeAction
    val f = dir.findChild("Product.java") ?: dir.createChildData(this, "Product.java")   // ← needs writeAction
    VfsUtil.saveText(f, content)                                                          // ← needs writeAction
}
// ✗ WRONG: val dir = VfsUtil.createDirectoryIfMissing(...) OUTSIDE writeAction, then writeAction { saveText }
// ↑ This throws "Write access is allowed inside write-action only" on the createDirectoryIfMissing call
```

---

## Create/Write a Java or Kotlin Source File

One file per exec_code call when possible (makes error attribution trivial):
```kotlin
writeAction {
    // DEPRECATED: project.baseDir — use LocalFileSystem instead:
    val root = LocalFileSystem.getInstance().findFileByPath(project.basePath!!)!!
    val dir = VfsUtil.createDirectoryIfMissing(root, "src/main/java/com/example/model")
    val f = dir.findChild("Product.java") ?: dir.createChildData(this, "Product.java")
    // Use joinToString() or File.writeText() — NOT a triple-quoted string with 'import' at line start
    // (the preprocessor extracts import-like lines from triple-quoted strings as Kotlin imports)
    // ⚠️ VfsUtil.saveText() REPLACES THE ENTIRE FILE — for adding a single method to an existing
    // class, use PSI writeCommandAction + factory.createMethodFromText() instead (see guide).
    VfsUtil.saveText(f, listOf(
        "package com.example.model;",
        "import" + " jakarta.persistence.Entity;",
        "import" + " jakarta.persistence.Id;",
        "@Entity public class Product { @Id private Long id; }"
    ).joinToString("\n"))
}
println("File created")
// ⚠️ After bulk file creation: if you plan to run runInspectionsDirectly or
// ReferencesSearch on the new files in THIS SAME exec_code call, call waitForSmartMode()
// between writeAction and the inspection call.
```

---

## ⚠️ Import-in-Strings Pitfall

Never put `import foo.Bar;` at the start of a line inside a triple-quoted Kotlin string. The script preprocessor extracts those lines as Kotlin imports, causing compile errors. Use `"import" + " foo.Bar;"` or `joinToString` to build the content, or use `java.io.File(path).writeText(content)` as an alternative.

---

## ⚠️ Generating Java Code Inline — `.class` and Dollar-Sign Pitfalls

Java code often contains `.class` references and dollar-sign characters. In double-quoted Kotlin strings, `.class)` can be mis-parsed and a bare dollar sign triggers string interpolation. Use `java.io.File(path).writeText()` with string concatenation to avoid both pitfalls:
```kotlin
// Safe pattern for Java source with .class refs and dollar signs:
java.io.File("${project.basePath}/src/main/java/com/example/SecurityConfig.java").writeText(
    "package com.example;\n" +
    "import" + " org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;\n" +
    "public class SecurityConfig {\n" +
    "    public void configure(HttpSecurity http) throws Exception {\n" +
    "        http.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);\n" +
    "    }\n" +
    "}"
)
// For dollar signs in Java string literals: use "${'\$'}Bearer" (produces literal dollar-Bearer in the output)
```

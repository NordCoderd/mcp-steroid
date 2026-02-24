/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
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

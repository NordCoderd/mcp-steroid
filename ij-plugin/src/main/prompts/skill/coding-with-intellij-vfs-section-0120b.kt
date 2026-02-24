/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
writeAction {
    // Create package directories
    // DEPRECATED: project.baseDir — use LocalFileSystem instead:
    val root = LocalFileSystem.getInstance().findFileByPath(project.basePath!!)!!
    val srcDir = VfsUtil.createDirectoryIfMissing(root, "src/main/java/com/example/model")

    // Build Java source using joinToString — avoids import-extraction bug
    val content = listOf(
        "package com.example.model;",
        "import" + " jakarta.persistence.Entity;",
        "import" + " jakarta.persistence.GeneratedValue;",
        "import" + " jakarta.persistence.GenerationType;",
        "import" + " jakarta.persistence.Id;",
        "",
        "@Entity",
        "public class Product {",
        "    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)",
        "    private Long id;",
        "    private String name;",
        "    // getters/setters...",
        "}"
    ).joinToString("\n")

    val f = srcDir.findChild("Product.java") ?: srcDir.createChildData(this, "Product.java")
    VfsUtil.saveText(f, content)
    println("Created: ${f.path}")
}

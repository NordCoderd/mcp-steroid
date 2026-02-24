/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
// ✓ SAFE: Use java.io.File for Java source with char literals — not affected by import extraction
java.io.File("${project.basePath}/src/main/java/com/example/model/Product.java")
    .also { it.parentFile.mkdirs() }
    .writeText("""
        package com.example.model;
        import jakarta.persistence.Entity;
        import jakarta.persistence.Id;
        @Entity
        public class Product {
            @Id private Long id;
            private String name;
            @Override public String toString() {
                return "Product{id=" + id + ", name='" + name + '\'' + "}";
            }
        }
    """.trimIndent())
LocalFileSystem.getInstance().refreshAndFindFileByPath("${project.basePath}/src/main/java/com/example/model/Product.java")
println("Created Product.java")
// Verify the write succeeded:
val vf = findProjectFile("src/main/java/com/example/model/Product.java")!!
check(VfsUtil.loadText(vf).contains("class Product")) { "Write failed or file is empty" }
println("Verified: Product.java written correctly")

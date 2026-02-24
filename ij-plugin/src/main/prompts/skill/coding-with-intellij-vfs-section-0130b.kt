/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
java.io.File("/path/to/project/src/main/java/com/example/Product.java").also { it.parentFile.mkdirs() }.writeText("""
    package com.example;
    import jakarta.persistence.Entity;
    @Entity public class Product { }
""".trimIndent())
// Then refresh the VFS so IntelliJ picks up the new file
LocalFileSystem.getInstance().refreshAndFindFileByPath("/path/to/project/src/main/java/com/example/Product.java")
println("File created and VFS refreshed")

/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
// WRONG: class ProductAggregate { String name; int weight; }  ← creates ClassCanBeRecord warning
// CORRECT (record from the start):
writeAction {
    val root = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(project.basePath!!)!!
    val dir = com.intellij.openapi.vfs.VfsUtil.createDirectoryIfMissing(root, "src/main/java/shop/api/core/product")
    val f = dir.findChild("Product.java") ?: dir.createChildData(this, "Product.java")
    com.intellij.openapi.vfs.VfsUtil.saveText(f, listOf(
        "package shop.api.core.product;",
        "",
        "public record Product(int productId, String name, int weight) {}"
    ).joinToString("\n"))
}
// Verify: runInspectionsDirectly should return NO ClassCanBeRecord after using record syntax

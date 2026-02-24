/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
// Check whether required classes exist with correct FQN (not just any file)
val scope = com.intellij.psi.search.GlobalSearchScope.projectScope(project)
val required = listOf(
    "shop.api.core.product.Product",
    "shop.api.composite.product.ProductAggregate"
)
val missing = required.filter {
    readAction { com.intellij.psi.JavaPsiFacade.getInstance(project).findClass(it, scope) } == null
}
println(if (missing.isEmpty()) "All required classes present — run tests to verify"
        else "STILL MISSING (must create): " + missing.joinToString(", "))

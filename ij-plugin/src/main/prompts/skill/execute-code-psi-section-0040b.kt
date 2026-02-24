/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
val repo = readAction {
    JavaPsiFacade.getInstance(project).findClass("com.example.ReleaseRepository", projectScope())
}
repo?.methods?.forEach { m ->
    val q = m.annotations.firstOrNull { it.qualifiedName?.endsWith("Query") == true }
    if (q != null) println("@Query ${m.name}: " + (q.findAttributeValue("value")?.text ?: ""))
}

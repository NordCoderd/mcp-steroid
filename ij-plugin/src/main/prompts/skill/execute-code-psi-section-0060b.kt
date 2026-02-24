/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.psi.search.PsiShortNamesCache
val allNames = readAction { PsiShortNamesCache.getInstance(project).allClassNames.toList() }
allNames.filter { it.endsWith("Payload") || it.endsWith("Request") || it.endsWith("Dto") ||
    it.endsWith("Status") || it.endsWith("Type") || it.endsWith("Service") }
    .sorted().forEach { println(it) }

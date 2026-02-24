/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.openapi.application.readAction
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.impl.java.stubs.index.JavaMethodNameIndex
import com.intellij.psi.PsiMethod


readAction {
    val methods = StubIndex.getElements(
        JavaMethodNameIndex.getInstance().key,
        "toString",
        project,
        GlobalSearchScope.projectScope(project),
        PsiMethod::class.java
    )

    println("Found ${methods.size} methods named 'toString'")
    methods.take(10).forEach { method ->
        println("  ${method.containingClass?.qualifiedName}.${method.name}")
    }
}

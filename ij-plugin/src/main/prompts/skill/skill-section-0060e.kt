/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.scope.PsiScopeProcessor
import com.intellij.psi.ResolveState


val filePath = "/path/to/YourFile.kt"
val offset = 150

val vf = LocalFileSystem.getInstance().findFileByPath(filePath)
if (vf == null) {
    println("File not found")
    return
}

val symbols = readAction {
    val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return@readAction emptyList<Pair<String, String>>()
    val context = psiFile.findElementAt(offset) ?: return@readAction emptyList<Pair<String, String>>()

    val declarations = mutableListOf<Pair<String, String>>()

    val processor = object : PsiScopeProcessor {
        override fun execute(element: PsiElement, state: ResolveState): Boolean {
            if (element is PsiNamedElement) {
                val name = element.name ?: return true
                val kind = element.javaClass.simpleName
                declarations.add(name to kind)
            }
            return true  // Continue processing
        }
    }

    context.processDeclarations(processor, ResolveState.initial(), null, context)
    declarations
}

println("Visible symbols at offset $offset:")
symbols.groupBy { it.second }.forEach { (kind, items) ->
    println("\n$kind:")
    items.take(10).forEach { (name, _) ->
        println("  - $name")
    }
    if (items.size > 10) {
        println("  ... and ${items.size - 10} more")
    }
}

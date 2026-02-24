/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiManager
import com.intellij.codeInsight.lookup.LookupElement


val editor = FileEditorManager.getInstance(project).selectedTextEditor
if (editor == null) {
    println("No editor open")
    return
}

val offset = editor.caretModel.offset
val virtualFile = editor.virtualFile ?: run {
    println("No virtual file for editor")
    return
}

val completions = readAction {
    val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
    if (psiFile == null) return@readAction emptyArray<Any>()

    val element = psiFile.findElementAt(offset)
    val reference = element?.reference

    reference?.getVariants() ?: emptyArray()
}

println("Completions at offset $offset:")
completions.take(20).forEach { variant ->
    when (variant) {
        is LookupElement -> {
            val psi = variant.psiElement
            val type = psi?.javaClass?.simpleName ?: "unknown"
            println("  - ${variant.lookupString} ($type)")
        }
        else -> println("  - $variant")
    }
}
if (completions.size > 20) {
    println("  ... and ${completions.size - 20} more")
}

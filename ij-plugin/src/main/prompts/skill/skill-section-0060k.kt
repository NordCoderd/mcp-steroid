/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiClass


val filePath = "/path/to/YourFile.java"
val offset = 200

val vf = LocalFileSystem.getInstance().findFileByPath(filePath)
if (vf == null) {
    println("File not found")
    return
}

readAction {
    val psiFile = PsiManager.getInstance(project).findFile(vf)
    if (psiFile == null) {
        println("Cannot parse file")
        return@readAction
    }

    val element = psiFile.findElementAt(offset)
    println("Element at offset $offset: ${element?.text}")
    println("Element type: ${element?.javaClass?.simpleName}")

    // Find containing expression
    val expr = PsiTreeUtil.getParentOfType(element, PsiExpression::class.java)
    if (expr != null) {
        println("Expression: ${expr.text}")
        println("Expression type: ${expr.type?.presentableText}")
    }

    // Find containing variable declaration
    val variable = PsiTreeUtil.getParentOfType(element, PsiVariable::class.java)
    if (variable != null) {
        println("Variable: ${variable.name}")
        println("Variable type: ${variable.type.presentableText}")
    }

    // Find containing method
    val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
    if (method != null) {
        println("In method: ${method.name}")
        println("Return type: ${method.returnType?.presentableText}")
    }

    // Find containing class
    val psiClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java)
    if (psiClass != null) {
        println("In class: ${psiClass.qualifiedName}")
    }
}

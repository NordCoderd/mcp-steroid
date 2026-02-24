IDE: Generate Overrides / Implementations

This example generates an override/implementation method in a class,

```kotlin
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.TypeConversionUtil
import com.intellij.codeInsight.generation.GenerateMembersUtil
import com.intellij.codeInsight.generation.OverrideImplementUtil

// Configuration - modify these for your use case
val filePath = "/path/to/your/File.java" // TODO: Set your file path
val baseMethodName = "toString" // TODO: Set method name to override/implement
val dryRun = true


val psiClass = readAction {
    val virtualFile = findFile(filePath) ?: return@readAction null
    val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return@readAction null
    PsiTreeUtil.findChildOfType(psiFile, PsiClass::class.java)
}

if (psiClass == null) {
    println("No class found in file: $filePath")
    return
}

val className = readAction { psiClass.name ?: "<unnamed>" }

val baseMethod = readAction {
    val candidates = psiClass.interfaces.flatMap { it.methods.toList() }
    candidates.firstOrNull { it.name == baseMethodName } ?: candidates.firstOrNull()
}

if (baseMethod == null) {
    println("No base method found to implement/override.")
    return
}

val prototypes = readAction { OverrideImplementUtil.overrideOrImplementMethod(psiClass, baseMethod, false) }
if (prototypes.isEmpty()) {
    println("No methods generated (already implemented?)")
    return
}
val prototypeNames = readAction { prototypes.map(PsiMethod::getName) }

if (dryRun) {
    println("Generate override prepared for class: $className")
    println("Methods to add: ${prototypeNames.joinToString()}")
    println("Set dryRun=false to apply changes.")
    return
}

val baseClass = readAction { baseMethod.containingClass }
if (baseClass == null) {
    println("Base method has no containing class.")
    return
}

WriteCommandAction.runWriteCommandAction(project) {
    val substitutor = TypeConversionUtil.getSuperClassSubstitutor(
        baseClass,
        psiClass,
        PsiSubstitutor.EMPTY
    )
    val anchor = OverrideImplementUtil.getDefaultAnchorToOverrideOrImplement(psiClass, baseMethod, substitutor)
    val infos = OverrideImplementUtil.convert2GenerationInfos(prototypes)
    GenerateMembersUtil.insertMembersBeforeAnchor(psiClass, anchor, infos)
    FileDocumentManager.getInstance().saveAllDocuments()
}

println("Generated override(s) in class: $className")
```

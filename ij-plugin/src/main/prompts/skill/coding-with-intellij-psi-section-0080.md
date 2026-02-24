
**Parameters:**
- `file`: VirtualFile to inspect
- `includeInfoSeverity`: Include INFO-level problems (default: false, only WEAK_WARNING and above)

**Returns:** `Map<String, List<ProblemDescriptor>>` - inspection tool ID to problems found

### Get Errors and Warnings (Daemon-based, requires window focus)

**Note**: This approach may return stale results if the IDE window is not focused. Prefer `runInspectionsDirectly()` for MCP use cases.

```kotlin
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiManager
import com.intellij.openapi.vfs.LocalFileSystem

readAction {
    val vf = LocalFileSystem.getInstance().findFileByPath("/path/to/file.kt")
    val psiFile = PsiManager.getInstance(project).findFile(vf!!)
    val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)

    if (psiFile != null && document != null) {
        val highlights = DaemonCodeAnalyzerEx.getInstanceEx(project)
            .getFileLevelHighlights(project, psiFile)

        highlights.forEach { info ->
            val severity = info.severity
            println("[$severity] ${info.description} at ${info.startOffset}")
        }
    }
}
```

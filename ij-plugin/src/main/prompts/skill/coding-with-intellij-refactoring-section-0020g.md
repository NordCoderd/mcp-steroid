### 7. Use printException for Errors

Includes full stack trace in output.

### 8. Keep Trying

The IntelliJ API has a learning curve - persistence pays off!

### 8a. Single Exploration Pass — Never Re-read Files Already in Context

Every file you read via `steroid_execute_code` is in your conversation history for the rest
of the session. Do NOT re-read a file you already read earlier:

- Files you read remain available in your conversation history.
- Only re-read a file if you **explicitly modified it** and need to verify the write succeeded.
- Do NOT restart exploration under a new `task_id` — you will re-read the same files and waste turns.
- Do NOT re-create a file because you forgot whether you created it — use `findProjectFile()` to check existence.

This rule is critical: each `steroid_execute_code` call takes ~20 seconds. Re-reading files already in
context wastes turns and time without adding information.

### 8b. Recovering from steroid_execute_code Compile Errors

When `steroid_execute_code` fails with a Kotlin compilation error (e.g. `unresolved reference 'GlobalSearchScope'`):

1. **Read the error message** — it names the exact unresolved symbol.
2. **Add the missing import** at the top of your script and resubmit.
3. **Do NOT switch to Bash/grep** after a compile error — one corrected steroid call is faster than 10 grep commands.

Common imports that are frequently missing:

```kotlin
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope   // ← most often missing
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiManager
import com.intellij.openapi.vfs.VfsUtil
```

If the error is `suspension functions can only be called within coroutine body`, your helper
function is missing the `suspend` keyword — add `suspend fun myHelper()`.

### 9. Use runInspectionsDirectly for Code Analysis

More reliable than daemon-based analysis (works even when IDE window is not focused).

### 10. Never Use runBlocking

You're already in a coroutine context!

### 11. Verified Existing Implementation Is a Successful Outcome

If required behavior is already present, you still need explicit verification and explicit final
status output. "No code changes" is valid only after verification, not by assumption.

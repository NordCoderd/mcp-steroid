
> **PSI vs file read comparison:**
> - **VfsUtil.loadText()** on a 200-line service file → you receive 200 lines, parse mentally, extract ~10 method signatures
> - **JavaPsiFacade.findClass() + .methods** → you receive ~10 lines of compact signatures directly, ready to use
>
> **Rule**: If you're about to read a 3rd file just to trace code flow, use `ReferencesSearch.search()` or `JavaPsiFacade.findClass()` instead. PSI answers in 1 call what file reading needs 5-10 calls to reconstruct.
>
> **When to use PSI vs file read:**
> - PSI: when you need structure (method signatures, field types, implemented interfaces, call sites)
> - File read: when you need full implementation details (method bodies, SQL queries, config file content)

---

## Find ALL Callers/Usages — PREFERRED Over Grepping Source Files

```kotlin
// Find ALL callers/usages (replaces grepping through source files):
import com.intellij.psi.search.searches.ReferencesSearch
ReferencesSearch.search(cls!!, projectScope()).findAll().forEach { ref ->
    val snippet = ref.element.parent.text.take(80)
    println("${ref.element.containingFile.name} → $snippet")
}
```


> **⚠️ Compile-error recovery**: If you get `unresolved reference 'GlobalSearchScope'`, add `import com.intellij.psi.search.GlobalSearchScope` and retry immediately. Do NOT abandon steroid_execute_code and fall back to Bash/grep after a compile error.

---

## Combined Discovery + Read in One Call

When you know target filenames from test imports — skip separate discovery step:

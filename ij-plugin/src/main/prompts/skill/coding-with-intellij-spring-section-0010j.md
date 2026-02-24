> **Key rules**:
> - If `steroid_execute_code` returns an error: read the error message and **retry with fixed code** — do NOT fall back to native Write/Bash
> - If an exec_code error is about missing import → add the import and retry
> - If an exec_code error is about `Write access allowed inside write-action only` → wrap VFS calls in `writeAction { }`
> - If exec_code compilation fails with `.class` or `$` → use triple-quoted Kotlin strings for Java source content

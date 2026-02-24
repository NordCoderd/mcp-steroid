
### ⚠️ Helper Functions Must Be `suspend` When Calling Suspend APIs

If you define a local helper function inside your script that calls any suspend API (`runInspectionsDirectly`, `readAction`, `writeAction`, `smartReadAction`, etc.), the helper **must be declared `suspend fun`**. Omitting `suspend` causes a compile error: `"suspension functions can only be called within coroutine body"`.

```kotlin
// ✗ WRONG — non-suspend helper calling a suspend API
fun checkFile(vf: VirtualFile) {
    val problems = runInspectionsDirectly(vf)  // ERROR: suspend call in non-suspend fun
    println(if (problems.isEmpty()) "OK" else "ERRORS: $problems")
}

// ✓ CORRECT — declare the helper as suspend
suspend fun checkFile(vf: VirtualFile) {
    val problems = runInspectionsDirectly(vf)  // OK: suspend call in suspend fun
    println(if (problems.isEmpty()) "OK" else "ERRORS: $problems")
}

// ✓ ALTERNATIVE — inline the call directly in the script body (no helper needed):
val problems = runInspectionsDirectly(vf)
println(if (problems.isEmpty()) "OK" else "ERRORS: $problems")
```

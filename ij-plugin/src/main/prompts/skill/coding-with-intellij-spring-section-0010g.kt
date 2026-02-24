/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
// Run IDE inspection on all newly created files — much faster than ./mvnw test-compile
for (path in listOf(
    "src/main/java/eval/sample/security/JwtService.java",
    "src/main/java/eval/sample/security/SecurityConfig.java",
    "src/main/java/eval/sample/security/JwtAuthenticationFilter.java"
)) {
    val vf = findProjectFile(path) ?: run { println("NOT FOUND: $path"); continue }
    val problems = runInspectionsDirectly(vf)
    if (problems.isEmpty()) println("OK: $path")
    else problems.forEach { (id, d) -> d.forEach { println("[$id] $path: ${it.descriptionTemplate}") } }
}
// Only if all OK: proceed to Maven test run

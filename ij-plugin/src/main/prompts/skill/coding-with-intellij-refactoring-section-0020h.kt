/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
val requiredPaths = listOf(
    "src/main/java/com/example/api/MyController.java",
    "src/main/java/com/example/service/MyService.java",
    "src/test/java/com/example/api/MyControllerTest.java",
)

val missing = requiredPaths.filter { findProjectFile(it) == null }
if (missing.isNotEmpty()) {
    println("FINAL_STATUS=INCOMPLETE")
    println("MISSING_FILES=${missing.joinToString()}")
} else {
    val filesWithProblems = mutableListOf<String>()
    for (path in requiredPaths) {
        val vf = findProjectFile(path) ?: continue
        val problems = runInspectionsDirectly(vf)
        if (problems.isNotEmpty()) filesWithProblems += path
    }

    if (filesWithProblems.isEmpty()) {
        println("FINAL_STATUS=COMPLETE")
        println("FINAL_REASON=Verified existing implementation; no edits required")
    } else {
        println("FINAL_STATUS=INCOMPLETE")
        println("FILES_WITH_PROBLEMS=${filesWithProblems.joinToString()}")
    }
}

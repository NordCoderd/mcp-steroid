/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
// Batch exploration: replace 5-8 sequential steroid_execute_code calls with 1
for (path in listOf(
    "pom.xml",
    "src/main/java/com/example/domain/CommentService.java",
    "src/main/java/com/example/domain/CommentRepository.java",
    "src/test/java/com/example/api/CommentControllerTest.java"
)) {
    val vf = findProjectFile(path) ?: run { println("NOT FOUND: $path"); continue }
    val content = VfsUtil.loadText(vf)
    // IMPORTANT: distinguish three states:
    //   NOT FOUND  → file doesn't exist at all (test_patch may add it later)
    //   EMPTY      → file exists but has no content (patch not yet applied, or placeholder)
    //   HAS_CONTENT → readable; process normally
    if (content.isEmpty()) { println("EMPTY (file exists but no content — may be a new file from test_patch not yet applied): $path"); continue }
    println("\n=== $path ===")
    println(content)
}

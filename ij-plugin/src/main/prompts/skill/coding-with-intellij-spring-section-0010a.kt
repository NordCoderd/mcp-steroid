/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.openapi.vfs.VfsUtil

// Call 1: readiness + Docker + VCS + test file content in ONE call
println("Project: ${project.name}, base: ${project.basePath}")
println("Smart: ${!com.intellij.openapi.project.DumbService.isDumb(project)}")
val dp = ProcessBuilder("docker", "info").redirectErrorStream(true).start()
println("Docker: ${dp.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && dp.exitValue() == 0}")
// VCS check
val changes = readAction {
    com.intellij.openapi.vcs.changes.ChangeListManager.getInstance(project)
        .allChanges.mapNotNull { it.virtualFile?.path }
}
println(if (changes.isEmpty()) "VCS: clean" else "VCS-modified:\n" + changes.joinToString("\n"))
// Read test file and pom.xml in SAME call to understand what's needed
val testVf = findProjectFile("src/test/java/eval/sample/AuthControllerTest.java")  // ← use actual test path
if (testVf != null) println("\n=== TEST ===\n" + VfsUtil.loadText(testVf))
val pomVf = findProjectFile("pom.xml")
if (pomVf != null) println("\n=== pom.xml ===\n" + VfsUtil.loadText(pomVf))

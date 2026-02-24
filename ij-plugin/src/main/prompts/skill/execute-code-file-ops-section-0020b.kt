/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
// Recommended FIRST exec_code call for any Spring Boot / Maven task:
println("Project: ${project.name}")
println("Smart: ${!com.intellij.openapi.project.DumbService.isDumb(project)}")
val dp = ProcessBuilder("docker", "info").redirectErrorStream(true).start()
val dockerOk = dp.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && dp.exitValue() == 0
println("Docker: $dockerOk")
val changes = readAction {
    com.intellij.openapi.vcs.changes.ChangeListManager.getInstance(project)
        .allChanges.mapNotNull { it.virtualFile?.path }
}
println("VCS-modified files:\n" + changes.joinToString("\n"))
// Then read VCS-modified files + FAIL_TO_PASS test files in this SAME call or the next call.
// If dockerOk=false: still attempt to run FAIL_TO_PASS tests — many use H2 in-memory DB,
// no Docker needed. Only fall back to runInspectionsDirectly if the test fails with an
// explicit Docker connection error.

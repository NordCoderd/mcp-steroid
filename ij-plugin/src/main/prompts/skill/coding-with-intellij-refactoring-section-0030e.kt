/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
// Run this after a test failure to determine whether Docker is the sole blocker:
val proc = ProcessBuilder("./mvnw", "test", "-Dtest=MyIntegrationTest", "-Dspotless.check.skip=true")
    .directory(java.io.File(project.basePath!!)).redirectErrorStream(true).start()
val lines = proc.inputStream.bufferedReader().readLines()
proc.waitFor()
val dockerError = lines.any { "Could not find a valid Docker" in it || "DockerException" in it }
println("Docker-only failure: $dockerError")
println(lines.takeLast(20).joinToString("\n"))

/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
// ⚠️ Always use ./mvnw (Maven wrapper) not 'mvn' — system mvn is not installed in arena
val process = ProcessBuilder("./mvnw", "test", "-Dtest=MyValidatorTest", "-q")
    .directory(java.io.File(project.basePath!!))
    .redirectErrorStream(true).start()
println(process.inputStream.bufferedReader().readText())
process.waitFor()

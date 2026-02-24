/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
val dp = ProcessBuilder("docker", "info").redirectErrorStream(true).start()
val dockerOk = dp.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && dp.exitValue() == 0
println("Docker available: $dockerOk")
// If ALL FAIL_TO_PASS tests contain @Import(TestcontainersConfiguration.class)
//   OR extend AbstractIT/AbstractITBase/IntegrationTest, AND dockerOk=false →
//   SKIP the test run — go directly to ./mvnw test-compile verification.
//   These tests have NO H2 fallback: a DockerException is guaranteed.
// If dockerOk=false AND the test does NOT use Testcontainers: attempt it anyway.
//   Many "integration" tests use H2 in-memory DB and do NOT require Docker at all.

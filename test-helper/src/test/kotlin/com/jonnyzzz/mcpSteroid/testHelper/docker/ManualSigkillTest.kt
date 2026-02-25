/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.createTempDirectory
import kotlin.system.exitProcess

/**
 * Manual test for SIGKILL handling.
 *
 * This is NOT an automated test - run it manually to test SIGKILL:
 *
 * 1. Run this test
 * 2. It will print the PID and container ID
 * 3. Open another terminal and run: kill -9 <PID>
 * 4. Wait 4+ seconds (3s ping timeout + cleanup time)
 * 5. Check that the container is gone: docker ps -a | grep <container-id>
 */
fun main() {
    println("=".repeat(80))
    println("SIGKILL Test - Manual Verification")
    println("=".repeat(80))

    val lifetime = CloseableStackHost()

    // Start the reaper
    println("\n[1] Starting reaper...")
    DockerReaper.start()

    // Start a container
    println("\n[2] Starting test container...")
    val container = startDockerContainerAndDispose(
        lifetime = lifetime,
        StartContainerRequest()
            .image("alpine:latest")
            .entryPoint("sleep", "infinity")
    )

    // Register with reaper
    DockerReaper.registerContainer(container)

    // Print info
    val pid = ProcessHandle.current().pid()
    println("\n[3] Container started successfully!")
    println("    Container ID: $container")
    println("    Process PID:  $pid")

    // Verify container is running
    Thread.sleep(2000)
    val checkResult = ProcessBuilder("docker", "ps", "-q", "--filter", "id=$container")
        .redirectErrorStream(true)
        .start()
        .inputStream
        .bufferedReader()
        .readText()
        .trim()

    if (checkResult.isEmpty()) {
        println("\n[ERROR] Container is not running!")
        exitProcess(1)
    }

    println("\n[4] Container verified running: $checkResult")

    // Instructions
    println("\n" + "=".repeat(80))
    println("TO TEST SIGKILL CLEANUP:")
    println("=".repeat(80))
    println("1. Open a new terminal")
    println("2. Run this command to kill the process:")
    println("   kill -9 $pid")
    println("3. Wait 4+ seconds for reaper to detect and cleanup")
    println("4. Verify container is gone with:")
    println("   docker ps -a | grep $container")
    println("   (Should return nothing)")
    println("5. Verify reaper container also exited:")
    println("   docker ps | grep mcp-steroid-reaper")
    println("   (Should return nothing)")
    println("=".repeat(80))
    println("\nWaiting for kill -9 signal... (this process will not exit normally)")
    println("Current time: ${java.time.LocalDateTime.now()}")

    // Wait forever - process should be killed with kill -9
    while (true) {
        Thread.sleep(10000)
        println("[Still alive at ${java.time.LocalDateTime.now()}]")
    }
}

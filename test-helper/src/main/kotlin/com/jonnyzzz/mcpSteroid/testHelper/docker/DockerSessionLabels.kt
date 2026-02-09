/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import java.util.*

/**
 * Docker labels for tracking containers created by this test session.
 * Similar to testcontainers' approach with org.testcontainers.sessionId.
 */
object DockerSessionLabels {
    /**
     * Base label namespace for all MCP Steroid test containers.
     */
    const val BASE_LABEL = "com.jonnyzzz.mcpSteroid.test"

    /**
     * Label key for session ID - used to identify containers from this test run.
     */
    const val SESSION_ID_LABEL = "$BASE_LABEL.sessionId"

    /**
     * Label key for creation timestamp - used to identify container age.
     */
    const val CREATED_AT_LABEL = "$BASE_LABEL.createdAt"

    /**
     * Label key for process ID - used to identify the test process that created the container.
     */
    const val PROCESS_ID_LABEL = "$BASE_LABEL.pid"

    /**
     * Unique session ID for this test run. Generated once per JVM process.
     * All containers created in this session will have this label.
     */
    val SESSION_ID: String = UUID.randomUUID().toString()

    /**
     * Current process ID.
     */
    val PROCESS_ID: String = ProcessHandle.current().pid().toString()

    /**
     * Default labels applied to all containers created by this test session.
     */
    fun createLabels(): Map<String, String> = mapOf(
        BASE_LABEL to "true",
        SESSION_ID_LABEL to SESSION_ID,
        PROCESS_ID_LABEL to PROCESS_ID,
        CREATED_AT_LABEL to System.currentTimeMillis().toString()
    )

    /**
     * Create a Docker label filter string for finding containers from this session.
     * Format: "label=key1=value1,label=key2=value2"
     */
    fun createSessionFilter(): String {
        return "label=$SESSION_ID_LABEL=$SESSION_ID"
    }

    /**
     * Create a Docker label filter string for finding all test containers.
     */
    fun createAllTestContainersFilter(): String {
        return "label=$BASE_LABEL=true"
    }
}

/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.storage

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64

@Serializable
enum class OutputType {
    OUT,   // stdout from ctx.println()
    JSON,  // structured data from ctx.printJson()
    LOG,   // log messages (info/warn/error)
    ERR    // exceptions/errors
}

@Serializable
data class OutputMessage(
    val ts: Long,
    val type: OutputType,
    val msg: String,
    val level: String? = null  // For LOG type: info, warn, error
)

@Serializable
data class ExecutionParams(
    val timeout: Int = 60,
    val showReviewOnError: Boolean = false
)

@Serializable
enum class ExecutionStatus {
    COMPILING,
    PENDING_REVIEW,
    RUNNING,
    SUCCESS,
    ERROR,
    TIMEOUT,
    CANCELLED,
    NOT_FOUND
}

@Serializable
data class ExecutionResult(
    val status: ExecutionStatus,
    val errorMessage: String? = null,
    val exceptionInfo: String? = null
)

/**
 * File-based storage for execution history.
 * Stores scripts, parameters, output, and results.
 */
@Service(Service.Level.PROJECT)
class ExecutionStorage(private val project: Project) {

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }

    private val baseDir: Path
        get() = Path.of(project.basePath ?: throw IllegalStateException("Project has no base path"), ".idea", "mcp-run")

    /**
     * Generate execution ID in format: {project-hash-3}-{YYYY-MM-DD}T{HH-MM-SS}-{payload-hash-10}
     */
    fun generateExecutionId(code: String, params: ExecutionParams): String {
        val projectHash = computeHash(project.name).take(3)
        val timestamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss"))
        val payloadHash = computeHash(code + json.encodeToString(params)).take(10)

        return "$projectHash-$timestamp-$payloadHash"
    }

    private fun computeHash(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hashBytes)
    }

    /**
     * Create execution directory and save initial files.
     */
    fun createExecution(executionId: String, code: String, params: ExecutionParams) {
        val dir = baseDir.resolve(executionId)
        Files.createDirectories(dir)

        Files.writeString(dir.resolve("script.kt"), code)
        Files.writeString(dir.resolve("parameters.json"), json.encodeToString(params))
    }

    /**
     * Append an output message to the execution log.
     */
    fun appendOutput(executionId: String, message: OutputMessage) {
        val file = baseDir.resolve(executionId).resolve("output.jsonl")
        val line = json.encodeToString(message) + "\n"
        Files.writeString(
            file,
            line,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        )
    }

    /**
     * Read output messages, optionally skipping first N.
     */
    fun readOutput(executionId: String, offset: Int = 0): List<OutputMessage> {
        val file = baseDir.resolve(executionId).resolve("output.jsonl")
        if (!Files.exists(file)) return emptyList()

        return Files.readAllLines(file)
            .drop(offset)
            .filter { it.isNotBlank() }
            .map { json.decodeFromString<OutputMessage>(it) }
    }

    /**
     * Write final execution result.
     */
    fun writeResult(executionId: String, result: ExecutionResult) {
        val file = baseDir.resolve(executionId).resolve("result.json")
        Files.writeString(file, json.encodeToString(result))
    }

    /**
     * Read execution result if available.
     */
    fun readResult(executionId: String): ExecutionResult? {
        val file = baseDir.resolve(executionId).resolve("result.json")
        if (!Files.exists(file)) return null
        return json.decodeFromString<ExecutionResult>(Files.readString(file))
    }

    /**
     * Read the script code for an execution.
     */
    fun readScript(executionId: String): String? {
        val file = baseDir.resolve(executionId).resolve("script.kt")
        if (!Files.exists(file)) return null
        return Files.readString(file)
    }

    /**
     * Read execution parameters.
     */
    fun readParams(executionId: String): ExecutionParams? {
        val file = baseDir.resolve(executionId).resolve("parameters.json")
        if (!Files.exists(file)) return null
        return json.decodeFromString<ExecutionParams>(Files.readString(file))
    }

    /**
     * Check if execution exists.
     */
    fun exists(executionId: String): Boolean {
        return Files.exists(baseDir.resolve(executionId))
    }

    /**
     * Get execution directory path.
     */
    fun getExecutionDir(executionId: String): Path {
        return baseDir.resolve(executionId)
    }

    /**
     * Save code for pending review.
     */
    fun savePendingReview(executionId: String, code: String): Path {
        val pendingDir = baseDir.resolve("pending")
        Files.createDirectories(pendingDir)
        val file = pendingDir.resolve("$executionId.kt")
        Files.writeString(file, code)
        return file
    }

    /**
     * Remove pending review file.
     */
    fun removePendingReview(executionId: String) {
        val file = baseDir.resolve("pending").resolve("$executionId.kt")
        Files.deleteIfExists(file)
    }
}

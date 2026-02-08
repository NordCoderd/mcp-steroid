/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.jonnyzzz.mcpSteroid.koltinc.KotlincCommandLine
import com.jonnyzzz.mcpSteroid.koltinc.builder
import com.jonnyzzz.mcpSteroid.koltinc.kotlincProcessClient
import com.jonnyzzz.mcpSteroid.koltinc.scriptClassLoaderFactory
import com.jonnyzzz.mcpSteroid.koltinc.toArgFile
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import com.jonnyzzz.mcpSteroid.storage.executionStorage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.path.div
import kotlin.io.path.writeLines
import kotlin.io.path.writeText

data class EvalResult(val result: List<suspend McpScriptContext.() -> Unit>)

inline val Project.codeEvalManager: CodeEvalManager get() = service()

@Service(Service.Level.PROJECT)
class CodeEvalManager(
    private val project: Project,
) : Disposable {
    override fun dispose() = Unit

    private val log = thisLogger()
    private val compilationMutex = Mutex()

    suspend fun evalCode(executionId: ExecutionId, code: String, resultBuilder: ExecutionResultBuilder): EvalResult? {
        if (compilationMutex.isLocked) {
            log.info("Compilation $executionId waiting for previous compilation to complete")
            resultBuilder.logProgress("Waiting for previous compilation to complete...")
        }
        return compilationMutex.withLock {
            evalCodeInternal(executionId, code, resultBuilder)
        }
    }

    private suspend fun evalCodeInternal(
        executionId: ExecutionId,
        code: String,
        resultBuilder: ExecutionResultBuilder
    ): EvalResult? {
        try {
            log.info("Compiling script $executionId")

            val compileClasspath = scriptClassLoaderFactory.ideClasspath()
            val compilerDir = project.executionStorage.createCompilerOutputDir(executionId)

            val outputJar = compilerDir / "script.jar"
            val wrappedCode = codeButcher.wrapToKotlinClass("Script_@jonnyzzz_${executionId.executionId}", code)
            project.executionStorage.writeWrappedScript(executionId, wrappedCode.code)

            val inputKt = compilerDir / "input.kt"
            inputKt.writeText(wrappedCode.code)

            (compilerDir / "classpath.txt").writeLines(compileClasspath.map { it.toString() })
            val classpathArgsFile = compilerDir / "kotlinc.args"

            val cmd = KotlincCommandLine
                .builder(outputJar)
                .withNoStdLib(true)
                .addClasspathEntries(compileClasspath)
                .addSource(inputKt)
                .build()
                .toArgFile(classpathArgsFile)

            val kotlincResult = kotlincProcessClient.kotlinc(
                cmd.args,
                compilerDir,
            )

            run {
                val compilerOutput = kotlincResult.stdout.trim()
                val compilerError = kotlincResult.stderr.trim()

                if (compilerOutput.isNotEmpty()) {
                    resultBuilder.logProgress("Compiler Output:\n$compilerOutput")
                }

                if (compilerError.isNotEmpty()) {
                    // Log stderr output (warnings/errors) for transparency.
                    // Actual failure is determined by the exit code check below,
                    // since stderr may contain mere warnings that don't block compilation.
                    resultBuilder.logMessage("Compiler Errors/Warnings:\n$compilerError")
                }

                if (compilerOutput.isNotEmpty() || compilerError.isNotEmpty()) {
                    project.executionStorage.writeCodeExecutionData(
                        executionId,
                        "kotlinc.txt",
                        "${kotlincResult.exitCode}\n--- STDOUT ---\n$compilerOutput\n\n--- STDERR ---\n$compilerError"
                    )
                }

                if (kotlincResult.isTimeout) {
                    resultBuilder.reportFailed("kotlinc stopped on timeout")
                }

                if (kotlincResult.exitCode != 0) {
                    resultBuilder.reportFailed("kotlinc exited with code: ${kotlincResult.exitCode}\n${kotlincResult.stderr}")
                    return null
                }
            }

            val capturedBlocks = try {
                val builder = McpScriptBuilder()
                val scriptClassloader = scriptClassLoaderFactory.execCodeClassloader(outputJar)
                val scriptClazz = scriptClassloader.loadClass(wrappedCode.classFqn)
                val scriptObject = scriptClazz.constructors.single().newInstance()
                val loadMethod = scriptClazz.getMethod(wrappedCode.methodName, McpScriptBuilder::class.java)
                loadMethod.invoke(scriptObject, builder)
                builder.executeBlocks.toList()
            } catch (t: Throwable) {
                resultBuilder.reportFailed("Failed to load generated code. ${t}. ${t.stackTraceToString()}")
                return null
            }

            log.info("Script evaluation complete for $executionId. Captured ${capturedBlocks.size} script block(s)")

            project.executionStorage.writeCodeExecutionData(executionId, "compilation-success.txt", "Compiled")
            return EvalResult(capturedBlocks.toList())
        } catch (e: Throwable) {
            val message = "Error executing script $executionId: ${e.message}"

            if (e.toString().contains("Service is dying", ignoreCase = true)) {
                log.warn("Kotlin daemon is dying detected: ${e.message}", e)
                kotlinDaemonManager.forceKillKotlinDaemon()
                resultBuilder.logMessage("WARN: Script compilation/evaluation failed: Kotlin Daemon is dying. TRY AGAIN otherwise let user know")
                project.executionStorage.writeCodeExecutionData(
                    executionId,
                    "dying-kotlin-debug.txt",
                    buildString {
                        appendLine("Error: ${e.message}")
                        appendLine(e)
                        appendLine(e.stackTraceToString())
                    }
                )
            }

            if (e.toString().contains("Incomplete code", ignoreCase = true)
                || e.toString().contains("Code is incomplete", ignoreCase = true)
            ) {

                log.warn("Kotlin incomplete code error detected: ${e.message}", e)
                resultBuilder.logMessage("WARN: Script compilation/evaluation failed: Incomplete code error. It usually means the Kotlin syntax is invalid or incomplete")

                project.executionStorage.writeCodeExecutionData(
                    executionId,
                    "incomplete-code-debug.txt",
                    buildString {
                        appendLine("Error: ${e.message}")
                        appendLine(e)
                        appendLine(e.stackTraceToString())
                    }
                )
            }

            log.warn(message, e)
            resultBuilder.logException(message, e)
            resultBuilder.reportFailed(message)
            return null
        }
    }
}

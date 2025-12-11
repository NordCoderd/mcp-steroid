/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.server

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.jonnyzzz.intellij.mcp.storage.ExecutionStatus
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/**
 * MCP Server application service.
 * Manages the Ktor-based MCP server lifecycle using Kotlin MCP SDK.
 */
@Service(Service.Level.APP)
class SteroidsMcpServer(
    parentScope: CoroutineScope,
) : Disposable {
    private val log = thisLogger()

    private val serverRef = AtomicReference<EmbeddedServer<*, *>?>(null)
    private val portRef = AtomicReference<Int>(0)
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob() + Dispatchers.IO)

    val port: Int get() = portRef.get()
    val mcpUrl: String get() = "http://localhost:$port$MCP_PATH"

    fun startServerIfNeeded() {
        if (port > 0) return

        val configuredPort = Registry.intValue("mcp.steroids.server.port", 63150)
        val actualPort = if (configuredPort == 0) findFreePort() else configuredPort

        try {
            val server = scope.embeddedServer(CIO, host = "0.0.0.0", port = actualPort) {
                install(SSE)
                routing {
                    mcp(MCP_PATH) {
                        createMcpServer()
                    }
                }
            }

            serverRef.set(server)
            portRef.set(actualPort)

            val mutex = ReentrantLock()
            mutex.lock()
            server.application.monitor.subscribe(ApplicationStarted) {
                mutex.unlock()
            }

            log.info("Starting MCP Steroid server on port $actualPort")
            server.start(wait = false)

            //wait for ktor is ready
            mutex.lock()

            log.info("MCP Steroid server started on $mcpUrl")
        } catch (e: Exception) {
            log.error("Failed to start MCP server. ${e.message}", e)
        }
    }

    /**
     * Write the MCP server URL to a specific project's .idea folder.
     */
    fun writeServerUrlToProject(project: Project) {
        val serverUrl = mcpUrl
        //TODO: wait for server to start!
        assert(port > 0)
        writeServerUrlToProjectInternal(project, serverUrl)
    }

    private fun writeServerUrlToProjectInternal(project: Project, serverUrl: String) {
        try {
            val basePath = project.basePath ?: return
            val ideaDir = Path.of(basePath, ".idea")
            if (Files.exists(ideaDir)) {
                val mcpFile = ideaDir.resolve("mcp-steroids.txt")
                Files.writeString(mcpFile, serverUrl)
                log.warn("MCP Steroid server URL written to: $mcpFile")
            }
        } catch (e: Exception) {
            log.error("Failed to write server URL to project folder: ${project.name}", e)
        }
    }

    private fun findFreePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    private fun createMcpServer(): Server {
        val server = Server(
            Implementation(
                name = "intellij-mcp-steroid",
                version = "1.0.0"
            ),
            ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false)
                )
            )
        )

        // Register tools via dedicated handlers
        service<ListProjectsToolHandler>().register(server)
        service<ExecuteCodeToolHandler>().register(server)

        return server
    }

    override fun dispose() {
        scope.cancel()
        serverRef.get()?.stop(1000, 2000)
        log.info("MCP Steroid server stopped")
    }

    companion object {
        private const val MCP_PATH = "/mcp"

        fun getInstance(): SteroidsMcpServer = ApplicationManager.getApplication().service()
    }
}

data class ExecutionResultWithOutput(
    val status: ExecutionStatus,
    val output: List<String>,
    val errorMessage: String? = null
)

/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.server

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.jonnyzzz.intellij.mcp.mcp.*
import com.jonnyzzz.intellij.mcp.storage.ExecutionStatus
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.sse.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/**
 * MCP Server application service.
 * Manages the Ktor-based MCP server lifecycle with custom MCP implementation.
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
    val mcpUrl: String get() = "http://localhost:$port/mcp"

    private val mcpServer = McpServerCore(
        serverInfo = ServerInfo(
            name = "intellij-mcp-steroid",
            version = "1.0.0"
        ),
        capabilities = ServerCapabilities(
            tools = ToolsCapability(listChanged = false)
        )
    )

    fun startServerIfNeeded() {
        if (port > 0) return

        // Register tools
        service<ListProjectsToolHandler>().register(mcpServer)
        service<ExecuteCodeToolHandler>().register(mcpServer)

        val configuredPort = Registry.intValue("mcp.steroids.server.port", 63150)
        val actualPort = if (configuredPort == 0) findFreePort() else configuredPort

        // By default, bind to localhost only per MCP security requirements.
        // For Docker testing, set mcp.steroids.server.host to "0.0.0.0"
        val bindHost = Registry.stringValue("mcp.steroids.server.host").takeIf { it.isNotBlank() } ?: "127.0.0.1"

        try {
            val server = scope.embeddedServer(CIO, host = bindHost, port = actualPort) {
                install(requestLoggingPlugin)
                install(SSE)
                routing {
                    with(McpHttpTransport) {
                        installMcp("/mcp", mcpServer)
                    }
                    get("/.well-known/mcp.json") {
                        call.respondText(
                            contentType = ContentType.Application.Json,
                            text = buildWellKnownMcpJson(actualPort)
                        )
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

            log.info("Starting MCP Steroid server on $bindHost:$actualPort")
            server.start(wait = false)

            //wait for ktor is ready
            mutex.lock()

            log.info("MCP Steroid server started on $mcpUrl")
            log.info("Note: If you restart IntelliJ, connected MCP clients (Claude CLI, etc.) will need to reconnect.")
            log.info("      Client should re-run: claude mcp add --transport http intellij-steroid $mcpUrl")
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
                val content = buildMcpSteroidsFileContent(serverUrl)
                Files.writeString(mcpFile, content)
                log.warn("MCP Steroid server URL written to: $mcpFile")
            }
        } catch (e: Exception) {
            log.error("Failed to write server URL to project folder: ${project.name}", e)
        }
    }

    private fun buildMcpSteroidsFileContent(serverUrl: String): String {
        return """
            |# IntelliJ MCP Steroid Server
            |# URL: $serverUrl
            |#
            |# === Claude Code CLI ===
            |# Add server:
            |#   claude mcp add --transport http intellij-steroid $serverUrl
            |# Verify:
            |#   claude mcp list
            |# Use:
            |#   claude -p "List all open projects using steroid_list_projects"
            |# Remove:
            |#   claude mcp remove intellij-steroid
            |#
            |# === Codex CLI (TOML config) ===
            |# Create ~/.codex/config.toml with:
            |#   [features]
            |#   rmcp_client = true
            |#
            |#   [mcp_servers.intellij-steroid]
            |#   url = "$serverUrl"
            |#
            |# Or run:
            |#   mkdir -p ~/.codex && cat > ~/.codex/config.toml << 'EOF'
            |#   [features]
            |#   rmcp_client = true
            |#
            |#   [mcp_servers.intellij-steroid]
            |#   url = "$serverUrl"
            |#   EOF
            |#
            |# Use:
            |#   codex exec "List all open projects using steroid_list_projects"
            |
            |$serverUrl
        """.trimMargin()
    }

    private fun findFreePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    private fun buildWellKnownMcpJson(port: Int): String {
        return """
            {
                "mcpServers": {
                    "intellij-mcp-steroid": {
                        "url": "http://localhost:$port/mcp"
                    }
                }
            }
        """.trimIndent()
    }

    override fun dispose() {
        scope.cancel()
        serverRef.get()?.stop(1000, 2000)
        log.info("MCP Steroid server stopped")
    }

    companion object {
        private val requestLoggingPlugin = createApplicationPlugin("SteroidsMcpRequestLogger") {
            val logger = Logger.getInstance(SteroidsMcpServer::class.java)
            onCall { call ->
                val startedAt = System.nanoTime()
                val method = call.request.httpMethod.value
                val uri = call.request.uri
                val remoteHost = call.request.local.remoteHost
                logger.info("[MCP-HTTP] <- $method $uri from $remoteHost")

                call.response.pipeline.intercept(ApplicationSendPipeline.After) {
                    val status = call.response.status() ?: HttpStatusCode.OK
                    val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                    logger.info("[MCP-HTTP] -> ${status.value} ${status.description} for $method $uri in ${elapsedMs}ms")
                }
            }
        }

        fun getInstance(): SteroidsMcpServer = ApplicationManager.getApplication().service()
    }
}

data class ExecutionResultWithOutput(
    val status: ExecutionStatus,
    val output: List<String>,
    val errorMessage: String? = null
)

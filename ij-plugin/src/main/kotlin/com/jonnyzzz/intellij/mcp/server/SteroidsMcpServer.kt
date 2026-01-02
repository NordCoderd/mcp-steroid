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
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import kotlinx.coroutines.*
import java.net.BindException
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
    private val startupLock = ReentrantLock()
    private val pluginVersion = PluginVersionResolver.resolve(javaClass.classLoader)

    val port: Int get() = portRef.get()
    val mcpUrl: String get() = "http://localhost:$port/mcp"
    val skillUrl: String get() = "http://localhost:$port/skill.md"

    /**
     * Get the underlying MCP server core for testing or tool registration.
     */
    fun getServer(): McpServerCore = mcpServer

    private val mcpServer = McpServerCore(
        serverInfo = ServerInfo(
            name = "intellij-mcp-steroid",
            version = pluginVersion
        ),
        capabilities = ServerCapabilities(
            tools = ToolsCapability(listChanged = false),
            resources = ResourcesCapability(subscribe = false, listChanged = false)
        )
    )

    fun startServerIfNeeded() {
        // Fast check without lock
        if (port > 0) return

        // Synchronize startup to handle concurrent calls from multiple projects
        startupLock.lock()
        try {
            // Double-check after acquiring lock
            if (port > 0) return

            // Register tools
            service<ListProjectsToolHandler>().register(mcpServer)
            service<ExecuteCodeToolHandler>().register(mcpServer)
            service<ExecuteFeedbackToolHandler>().register(mcpServer)
            service<CapabilitiesToolHandler>().register(mcpServer)
            service<ActionDiscoveryToolHandler>().register(mcpServer)
            service<VisionScreenshotToolHandler>().register(mcpServer)
            service<VisionInputToolHandler>().register(mcpServer)

            // Register resources
            service<SkillResourceHandler>().register(mcpServer)
            service<LspExamplesResourceHandler>().register(mcpServer)
            service<IdeExamplesResourceHandler>().register(mcpServer)

            val configuredPort = Registry.intValue("mcp.steroids.server.port")

            // By default, bind to localhost only per MCP security requirements.
            // For Docker testing, set mcp.steroids.server.host to "0.0.0.0"
            val bindHost = Registry.stringValue("mcp.steroids.server.host").takeIf { it.isNotBlank() } ?: "127.0.0.1"

            // Try to start on configured port, fall back to next ports if busy
            val actualPort = startServerOnAvailablePort(bindHost, configuredPort)
            if (actualPort > 0) {
                log.info("MCP Steroid server started on $mcpUrl")
                log.info("Note: If you restart IntelliJ, connected MCP clients (Claude CLI, etc.) will need to reconnect.")
                log.info("      Client should re-run: claude mcp add --transport http intellij-steroid $mcpUrl")
            }
        } finally {
            startupLock.unlock()
        }
    }

    /**
     * Tries to start the server on the given port. If the port is busy,
     * tries subsequent ports up to MAX_PORT_RETRIES times.
     * If configuredPort is 0, finds a free port automatically.
     *
     * @return the actual port the server started on, or 0 if failed
     */
    private fun startServerOnAvailablePort(bindHost: String, configuredPort: Int): Int {
        if (configuredPort == 0) {
            // Dynamic port allocation requested
            val freePort = findFreePort()
            return tryStartServer(bindHost, freePort)
        }

        // Try configured port and subsequent ports
        for (attempt in 0 until MAX_PORT_RETRIES) {
            val portToTry = configuredPort + attempt
            val result = tryStartServer(bindHost, portToTry)
            if (result > 0) {
                if (attempt > 0) {
                    log.info("Port $configuredPort was busy, started on port $portToTry instead")
                }
                return result
            }
        }

        log.error("Failed to start MCP server: all ports from $configuredPort to ${configuredPort + MAX_PORT_RETRIES - 1} are busy")
        return 0
    }

    /**
     * Attempts to start the server on the specified port.
     *
     * @return the port if successful, 0 if the port is busy, throws on other errors
     */
    private fun tryStartServer(bindHost: String, portToTry: Int): Int {
        // Pre-check if port is available to avoid async BindException from Ktor CIO
        if (!isPortAvailable(bindHost, portToTry)) {
            log.info("Port $portToTry is busy, will try next port")
            return 0
        }

        try {
            val server = scope.embeddedServer(CIO, host = bindHost, port = portToTry) {
                install(requestLoggingPlugin)
                install(SSE)
                routing {
                    with(McpHttpTransport) {
                        installMcp("/mcp", mcpServer)
                    }
                    get("/.well-known/mcp.json") {
                        call.respondText(
                            contentType = ContentType.Application.Json,
                            text = buildWellKnownMcpJson(portToTry)
                        )
                    }
                    // Agent Skills endpoints - serve SKILL.md at root and /skill.md
                    get("/") {
                        call.respondText(
                            contentType = ContentType.Text.Plain.withCharset(Charsets.UTF_8),
                            text = skillResourceHandler.loadSkillMd()
                        )
                    }
                    get("/skill.md") {
                        call.respondText(
                            contentType = ContentType.Text.Plain.withCharset(Charsets.UTF_8),
                            text = skillResourceHandler.loadSkillMd()
                        )
                    }
                    get("/SKILL.md") {
                        call.respondText(
                            contentType = ContentType.Text.Plain.withCharset(Charsets.UTF_8),
                            text = skillResourceHandler.loadSkillMd()
                        )
                    }
                }
            }

            val startupMutex = ReentrantLock()
            startupMutex.lock()
            server.application.monitor.subscribe(ApplicationStarted) {
                startupMutex.unlock()
            }

            log.info("Starting MCP Steroid server on $bindHost:$portToTry")
            server.start(wait = false)

            // Wait for Ktor to be ready
            startupMutex.lock()

            // Server started successfully
            serverRef.set(server)
            portRef.set(portToTry)
            return portToTry
        } catch (e: CancellationException) {
            // Control-flow exception - rethrow, do not log
            throw e
        } catch (e: Exception) {
            // Check if the root cause is BindException (port busy)
            if (isPortBusyException(e)) {
                log.info("Port $portToTry is busy, will try next port")
                return 0
            }
            // Other exception - log and rethrow
            log.error("Failed to start MCP server on port $portToTry: ${e.message}", e)
            throw e
        }
    }

    /**
     * Checks if a port is available for binding.
     */
    private fun isPortAvailable(host: String, port: Int): Boolean {
        return try {
            val address = if (host == "0.0.0.0") null else java.net.InetAddress.getByName(host)
            ServerSocket(port, 1, address).use { true }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Checks if the exception indicates the port is already in use.
     */
    private fun isPortBusyException(e: Throwable): Boolean {
        var current: Throwable? = e
        while (current != null) {
            if (current is BindException) {
                return true
            }
            // Also check for common messages
            val msg = current.message?.lowercase() ?: ""
            if (msg.contains("address already in use") || msg.contains("address in use")) {
                return true
            }
            current = current.cause
        }
        return false
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
             IntelliJ MCP Steroid Server
             URL: $serverUrl
            
             === Claude Code CLI ===
             
             Add server:
               claude mcp add --transport http intellij-steroid $serverUrl
               claude mcp list
               
             Recommended:
               claude mcp add playwright npx @playwright/mcp@latest
               
             Test:
               claude -p "List all open projects using steroid_list_projects"
            
             === Codex CLI (TOML config) ===
               codex mcp add intellij --url http://localhost:6315/mcp
               codex mcp list

             Recommended:
               codex mcp add playwright npx "@playwright/mcp@latest"

             Test:
               codex exec "List all open projects using steroid_list_projects"

             === Gemini CLI ===
               gemini mcp add intellij-steroid $serverUrl --transport http --scope user
               gemini mcp list

             Test:
               gemini "List all open projects using steroid_list_projects"

             $serverUrl
        """.trimMargin()
    }

    private fun findFreePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    private fun buildWellKnownMcpJson(port: Int): String {
        //TODO: for some scenarios it may not be localhost!
        //TODO: use the request URL from the HTTP instead,
        // fallback to localhost:port only as last resort
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
        private const val MAX_PORT_RETRIES = 10

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

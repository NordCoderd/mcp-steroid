/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.GitDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerPort
import com.jonnyzzz.mcpSteroid.testHelper.docker.RunningContainerProcess
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions
import java.io.File
import kotlin.concurrent.thread

class IntelliJDriver(
    private val lifetime: CloseableStack,
    private val driver: ContainerDriver,
    private val guestDir: String,
) {
    private val intelliJGuestHomeDir = "/opt/idea"
    private val projectGuestDir = "$guestDir/project-home"
    private val configGuestDir = "$guestDir/ide-config"
    private val systemGuestDir = "/home/agent/ide-system"
    private val logsGuestDir = "$guestDir/ide-log"
    private val pluginsGuestDir = "$guestDir/ide-plugins"
    private val steroidGuestDir = "$guestDir/mcp-steroid"

    fun getGuestProjectDir() = projectGuestDir

    fun readLogs(): List<String> {
        val file = ideaLogsFile()
        if (!file.exists()) return emptyList()
        return file.readLines()
    }

    private fun ideaLogsFile(): File = driver.mapGuestPathToHostPath(logsGuestDir).resolve("idea.log")

    fun startIde(): RunningContainerProcess {
        driver.mkdirs(intelliJGuestHomeDir)
        driver.mkdirs(projectGuestDir)
        driver.mkdirs(configGuestDir)
        driver.mkdirs(systemGuestDir)
        driver.mkdirs(logsGuestDir)
        driver.mkdirs(pluginsGuestDir)

        driver.runInContainer(listOf("ls", "-la", intelliJGuestHomeDir))

        writeEulaAcceptance()
        writeConsentOptions()
        writeTrustedPaths()
        generateVmOptions()

        println("[IDE-AGENT] Starting IntelliJ IDEA...")
        val idea = driver.runInContainerDetached(
            listOf("/opt/idea/bin/idea.sh", projectGuestDir),
        )

        try {
            waitFor(10_000L) {
                readLogs().size > 20
            }
        } catch (t: Throwable) {
            idea.printProcessInfo()

            if (!idea.isRunning()) {
                throw RuntimeException("IntelliJ IDEA Exited Unexpectedly with code ${idea.exitCode}. See logs above for details.")
            } else {
                throw RuntimeException("Problem reading IntelliJ IDEA", t)
            }
        }

        val ijLogsStream = thread(isDaemon = true, name = "ijLogsStream") {
            runCatching {
                ideaLogsFile().bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine()
                        if (line == null) {
                            Thread.sleep(100)
                            continue
                        }
                        println("[IntelliJ LOG] $line")
                    }
                }
            }
        }

        lifetime.registerCleanupAction {
            ijLogsStream.interrupt()
        }

        return idea
    }

    fun mountProjectFiles(projectName: String) {
        IdeTestFolders.copyProjectFiles(projectName, driver.mapGuestPathToHostPath(projectGuestDir))
    }

    /**
     * Clone a git repository into the project directory inside the container.
     * Use this instead of [mountProjectFiles] for external projects.
     */
    fun cloneGitRepo(repoUrl: String, shallow: Boolean = true, timeoutSeconds: Long = 300) {
        GitDriver(driver).clone(repoUrl, projectGuestDir, shallow, timeoutSeconds)
    }

    private fun generateVmOptions() {
        val opts = buildString {
            appendLine("-Xmx2g")
            appendLine("-Xms512m")

            appendLine("# Redirect IDE directories to explicit paths")
            appendLine("-Didea.config.path=$configGuestDir")
            appendLine("-Didea.system.path=$systemGuestDir")
            appendLine("-Didea.log.path=$logsGuestDir")
            appendLine("-Didea.plugins.path=$pluginsGuestDir")

            appendLine("# MCP Steroid plugin configuration")
            appendLine("-Dmcp.steroid.server.host=0.0.0.0")
            appendLine("-Dmcp.steroid.server.port=${MCP_STEROID_PORT.containerPort}")
            appendLine("-Dmcp.steroid.review.mode=NEVER")
            appendLine("-Dmcp.steroid.updates.enabled=false")
            appendLine("-Dmcp.steroid.analytics.enabled=false")
            appendLine("-Dmcp.steroid.idea.description.enabled=false")
            appendLine("-Dmcp.steroid.storage.path=$steroidGuestDir")

            appendLine("# Skip EULA, consent dialogs, and onboarding")
            appendLine("-Djb.consents.confirmation.enabled=false")
            appendLine("-Djb.privacy.policy.text=<!--999.999-->")
            appendLine("-Djb.privacy.policy.ai.assistant.text=<!--999.999-->")
            appendLine("-Dmarketplace.eula.reviewed.and.accepted=true")
            appendLine("-Dwriterside.eula.reviewed.and.accepted=true")
            appendLine("-Didea.initially.ask.config=never")
            appendLine("-Dide.newUsersOnboarding=false")
            appendLine()
            appendLine("# Suppress telemetry and update checks")
            appendLine("-Didea.suppress.statistics.report=true")
            appendLine("-Didea.local.statistics.without.report=true")
            appendLine("-Dfeature.usage.event.log.send.on.ide.close=false")
            appendLine("-Dide.enable.notification.trace.data.sharing=false")
            appendLine("-Didea.updates.url=http://127.0.0.1")
            appendLine()
        }

        driver.writeFileInContainer("$intelliJGuestHomeDir.vmoptions", opts)
    }

    private fun writeEulaAcceptance() {
        val prefsXml = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8" standalone="no"?>""")
            appendLine("""<!DOCTYPE map SYSTEM "http://java.sun.com/dtd/preferences.dtd">""")
            appendLine("""<map MAP_XML_VERSION="1.0">""")
            appendLine("""  <entry key="accepted_version" value="999.999"/>""")
            appendLine("""  <entry key="privacy_policy_accepted_version" value="999.999"/>""")
            appendLine("""  <entry key="eua_accepted_version" value="999.999"/>""")
            appendLine("""  <entry key="euaCommunity_accepted_version" value="999.999"/>""")
            appendLine("""  <entry key="ij_euaEap_accepted_version" value="999.999"/>""")
            appendLine("""</map>""")
        }

        driver.writeFileInContainer(
            "/home/agent/.java/.userPrefs/jetbrains/privacy_policy/prefs.xml",
            prefsXml,
        )
    }

    private fun writeConsentOptions() {
        val timestamp = System.currentTimeMillis() - 1000
        driver.writeFileInContainer(
            "/home/agent/.config/JetBrains/consentOptions/accepted",
            "rsch.send.usage.stat:1.1:0:${timestamp}",
        )
    }

    private fun writeTrustedPaths() {
        val trustedPathsXml = buildString {
            appendLine("""<application>""")
            appendLine("""  <component name="Trusted.Paths">""")
            appendLine("""    <option name="TRUSTED_PROJECT_PATHS">""")
            appendLine("""      <map>""")
            appendLine("""        <entry key="$projectGuestDir" value="true" />""")
            appendLine("""      </map>""")
            appendLine("""    </option>""")
            appendLine("""  </component>""")
            appendLine("""  <component name="Trusted.Paths.Settings">""")
            appendLine("""    <option name="TRUSTED_PATHS">""")
            appendLine("""      <list>""")
            appendLine("""        <option value="/" />""")
            appendLine("""      </list>""")
            appendLine("""    </option>""")
            appendLine("""  </component>""")
            appendLine("""</application>""")
        }

        driver.writeFileInContainer(
            "$configGuestDir/options/trusted-paths.xml",
            trustedPathsXml,
        )
    }

    fun deployPluginToContainer(pluginZipPath: File) {
        val containerTempDir = "$guestDir/temp"
        val containerTempZip = "$containerTempDir/plugin.zip"
        println("[IDE-AGENT] Deploying plugin to container: $pluginZipPath")

        require(pluginZipPath.isFile()) { "Plugin zip does not exist: $pluginZipPath" }

        driver.mkdirs(pluginsGuestDir)
        driver.mkdirs(containerTempDir)
        driver.copyToContainer(pluginZipPath, containerTempZip)
        driver.runInContainer(
            listOf("unzip", containerTempZip),
            workingDir = pluginsGuestDir,
            timeoutSeconds = 30,
        ).assertExitCode(0)

        // Ensure kotlinc and other bundled binaries are executable (best-effort, may find nothing)
        driver.runInContainer(
            listOf("bash", "-c", "find $pluginsGuestDir -name 'kotlinc' -type f -exec chmod +x {} + 2>/dev/null || true"),
            timeoutSeconds = 10,
        )
    }

    /**
     * Execute Kotlin code via steroid_execute_code tool.
     *
     * This makes a direct HTTP call to the MCP server, bypassing AI agents.
     * Useful for integration tests that need reliable, deterministic behavior.
     *
     * @param code Kotlin code to execute (suspend function body)
     * @param taskId Task identifier (default: "integration-test")
     * @param reason Human-readable reason for execution
     * @param timeout Timeout in seconds (default: 600)
     * @param projectName Project name (default: "test-project")
     * @return MCP tool result as JSON string
     */
    fun mcpExecuteCode(
        code: String,
        taskId: String = "integration-test",
        reason: String = "Integration test execution",
        timeout: Int = 600,
        projectName: String = "test-project",
    ): String {
        val mcpUrl = "http://localhost:${MCP_STEROID_PORT.containerPort}/mcp"

        // First, initialize MCP session
        val sessionId = mcpInitialize(mcpUrl)

        // Build the tool call request using kotlinx.serialization
        val toolCallRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 2)
            putJsonObject("params") {
                put("name", "steroid_execute_code")
                putJsonObject("arguments") {
                    put("project_name", projectName)
                    put("code", code)
                    put("task_id", taskId)
                    put("reason", reason)
                    put("timeout", timeout)
                }
            }
            put("method", "tools/call")
        }.toString()

        // Execute the tool call
        val run = executeMcpRequest(mcpUrl, sessionId, toolCallRequest)
        val data = Json { }.parseToJsonElement(run)

        data.jsonObject["result"]?.jsonObject["content"]?.jsonArray?.forEach {
            it.jsonObject["text"]?.jsonPrimitive?.contentOrNull?.let { println("[MCP LOG]: $it ") }
        }

        Assertions.assertEquals(
            data.jsonObject["result"]?.jsonObject["isError"]?.jsonPrimitive?.booleanOrNull,
            false
        )


        return run
    }

    /**
     * Initialize MCP session and return session ID.
     */
    private fun mcpInitialize(mcpUrl: String): String {
        val initRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
            putJsonObject("params") {
                put("protocolVersion", "2025-11-25")
                putJsonObject("capabilities") { }
                putJsonObject("clientInfo") {
                    put("name", "integration-test")
                    put("version", "1.0")
                }
            }
        }.toString()

        executeMcpRequest(mcpUrl, null, initRequest)

        // Return a session ID (the server will manage the actual session)
        return "test-session-${System.currentTimeMillis()}"
    }

    /**
     * Execute an MCP request via curl in the container.
     */
    private fun executeMcpRequest(
        mcpUrl: String,
        sessionId: String?,
        requestBody: String
    ): String {
        // Create curl command
        val curlCommand = buildList {
            add("curl")
            add("-s")  // Silent
            add("-X")
            add("POST")
            add(mcpUrl)
            add("-H")
            add("Content-Type: application/json")
            add("-H")
            add("Accept: application/json")

            // Add session cookie if present
            if (sessionId != null) {
                add("-H")
                add("Cookie: mcp_session=$sessionId")
            }

            add("-d")
            add(requestBody)
        }

        val result = driver.runInContainer(
            curlCommand,
            timeoutSeconds = 30,
        )

        if (result.exitCode != 0) {
            error("MCP request failed (exit ${result.exitCode}): ${result.output}")
        }

        val j = result.output.trim()
        return Json.encodeToString(Json.parseToJsonElement(j))
    }

    companion object {
        val MCP_STEROID_PORT = ContainerPort(6754)

        private val Json = Json { prettyPrint = true }
    }
}

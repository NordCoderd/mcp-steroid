/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerPort
import com.jonnyzzz.mcpSteroid.testHelper.docker.RunningContainerProcess
import java.io.File
import kotlin.concurrent.thread

class IntelliJDriver(
    private val lifetime: CloseableStack,
    private val driver: ContainerDriver,
    private val guestDir: String,
) {
    private val intelliJGuestHomeDir = "/opt/idea"
    private val projectDir = "$guestDir/project-home"
    private val configGuestDir = "$guestDir/ide-config"
    private val systemGuestDir = "/home/agent/ide-system"
    private val logsGuestDir = "$guestDir/ide-log"
    private val pluginsGuestDir = "$guestDir/ide-plugins"

    val steroidPort get() = MCP_STEROID_PORT.containerPort

    fun readLogs(): List<String> {
        val file = ideaLogsFile()
        if (!file.exists()) return emptyList()
        return file.readLines()
    }

    private fun ideaLogsFile(): File = driver.mapGuestPathToHostPath(logsGuestDir).resolve("idea.log")

    fun startIde(): RunningContainerProcess {
        driver.mkdirs(intelliJGuestHomeDir)
        driver.mkdirs(projectDir)
        driver.mkdirs(configGuestDir)
        driver.mkdirs(systemGuestDir)
        driver.mkdirs(logsGuestDir)
        driver.mkdirs(pluginsGuestDir)

        driver.runInContainer(listOf("find", "-r", intelliJGuestHomeDir))

        writeEulaAcceptance()
        writeConsentOptions()
        writeTrustedPaths()
        generateVmOptions()

        println("[IDE-AGENT] Starting IntelliJ IDEA...")
        val idea = driver.runInContainerDetached(
            listOf("/opt/idea/bin/idea.sh", projectDir),
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
        IdeTestFolders.copyProjectFiles(projectName, driver.mapGuestPathToHostPath(projectDir))
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
            appendLine("-Dmcp.steroid.server.port=$steroidPort")
            appendLine("-Dmcp.steroid.review.mode=NEVER")
            appendLine("-Dmcp.steroid.updates.enabled=false")
            appendLine("-Dmcp.steroid.analytics.enabled=false")
            appendLine("-Dmcp.steroid.idea.description.enabled=false")

            appendLine("# Activate as Community Edition (no license dialog)")
            appendLine("-Didea.platform.prefix=Idea")
            appendLine()
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
            appendLine("-Dide.do.not.disable.paid.plugins.on.startup=true")
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
            appendLine("""        <entry key="$projectDir" value="true" />""")
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
    }

    companion object {
        val MCP_STEROID_PORT = ContainerPort(6754)
    }
}

/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.integration.infra.McpSteroidDriver.Companion.MCP_STEROID_PORT
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.RunningContainerProcess
import com.jonnyzzz.mcpSteroid.testHelper.docker.copyToContainer
import com.jonnyzzz.mcpSteroid.testHelper.docker.mapGuestPathToHostPath
import com.jonnyzzz.mcpSteroid.testHelper.docker.mkdirs
import java.io.File
import kotlin.concurrent.thread

class IntelliJDriver(
    private val lifetime: CloseableStack,
    private val driver: ContainerDriver,
    private val guestDir: String,
    private val ideProduct: IdeProduct,
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
        writeStartupProperties()
        writeEarlyAccessRegistry()
        writeAiPromoState()
        generateVmOptions()

        println("[IDE-AGENT] Starting ${ideProduct.displayName}...")
        val launcherPath = "$intelliJGuestHomeDir/bin/${ideProduct.launcherExecutable}"
        driver.runInContainer(listOf("ls", "-la", "$intelliJGuestHomeDir/bin"))
        driver.runInContainer(
            listOf("test", "-x", launcherPath),
            timeoutSeconds = 5,
            quietly = true,
        ).assertExitCode(0)

        val idea = driver.runInContainerDetached(
            listOf(launcherPath, projectGuestDir),
        )

        val logFile = ideaLogsFile()
        val logsReady = try {
            waitFor(60_000L, "for ${ideProduct.displayName} log file") {
                !idea.isRunning() || (logFile.exists() && logFile.length() > 0L)
            }
            logFile.exists() && logFile.length() > 0L
        } catch (_: Throwable) {
            false
        }

        if (!idea.isRunning()) {
            idea.printProcessInfo()
            throw RuntimeException("${ideProduct.displayName} exited unexpectedly with code ${idea.exitCode}. See logs above for details.")
        }

        if (!logsReady) {
            println(
                "[IDE-AGENT] ${ideProduct.displayName} log file is not available yet at ${logFile.absolutePath}. " +
                        "Continuing startup and relying on window/MCP readiness checks."
            )
        } else {
            val ijLogsStream = thread(isDaemon = true, name = "ijLogsStream") {
                runCatching {
                    logFile.bufferedReader().use { reader ->
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
        }

        return idea
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
            appendLine("-Dmcp.steroid.dialog.killer.enabled=true")
            appendLine("-Dmcp.steroid.updates.enabled=false")
            appendLine("-Dmcp.steroid.analytics.enabled=false")
            appendLine("-Dmcp.steroid.idea.description.enabled=false")
            appendLine("-Dmcp.steroid.storage.path=$steroidGuestDir")

            appendLine("# Suppress AI promo window (prevents 8-minute startup deadlock in Docker)")
            appendLine("# AIPromoWindowAdvisor fetches a remote URL on first run; in Docker this")
            appendLine("# times out after 480s and blocks VfsData via fleet.kernel.Transactor.")
            appendLine("-Dllm.show.ai.promotion.window.on.start=false")
            appendLine("# Reduce network connection timeout (safety net: cuts any remaining")
            appendLine("# timeout-based delay from ~480s down to ~15s: 5 retries x 3s).")
            appendLine("-Didea.connection.timeout=3000")
            appendLine()
            appendLine("# Skip EULA, consent dialogs, and onboarding")
            appendLine("-Djb.consents.confirmation.enabled=false")
            appendLine("-Djb.privacy.policy.text=<!--999.999-->")
            appendLine("-Djb.privacy.policy.ai.assistant.text=<!--999.999-->")
            appendLine("-Dmarketplace.eula.reviewed.and.accepted=true")
            appendLine("-Dwriterside.eula.reviewed.and.accepted=true")
            appendLine("-Didea.initially.ask.config=never")
            appendLine("-Dide.newUsersOnboarding=false")
            appendLine("-Dnosplash=true")
            appendLine()
            appendLine("# Suppress telemetry, update checks, and async network startup activities")
            appendLine("-Didea.suppress.statistics.report=true")
            appendLine("-Didea.local.statistics.without.report=true")
            appendLine("-Dfeature.usage.event.log.send.on.ide.close=false")
            appendLine("-Dide.enable.notification.trace.data.sharing=false")
            appendLine("-Didea.updates.url=http://127.0.0.1")
            appendLine("-Dide.no.platform.update=true")
            appendLine("-Dide.browser.disabled=true")
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

    /**
     * Pre-write IDE startup properties to suppress first-startup onboarding dialogs.
     *
     * IntelliJ 2025.3.3+ (build 253.31033+) shows a "Meet the Islands Theme" modal dialog
     * on first startup via NewUiOnboardingStartupActivity, which blocks the EDT and prevents
     * background Gradle import from executing. We suppress the dialog via the proposeOnboarding
     * code path by marking onboarding as already proposed.
     */
    private fun writeStartupProperties() {
        // "RunOnceActivity.llm.onboarding.window.launcher.v7" = "true" makes
        // AIPromoWindowLauncher skip the promo window on first project open
        // (checked via PropertiesComponent.getBoolean before any verdict calculation).
        val otherXml = """<application>
  <component name="PropertyService"><![CDATA[{"keyToString":{"experimental.ui.on.first.startup":"true","experimental.ui.onboarding.proposed.version":"suppressed","RunOnceActivity.llm.onboarding.window.launcher.v7":"true"}}]]></component>
</application>
"""
        driver.writeFileInContainer(
            "$configGuestDir/options/other.xml",
            otherXml,
        )
    }

    /**
     * Pre-write the early-access registry to suppress the ClassicUiToIslandsMigration.
     *
     * IntelliJ 2025.3.3+ runs ClassicUiToIslandsMigration.enableNewUiWithIslands() at bootstrap.
     * If the IDE detects a Classic→Islands theme migration, it sets SHOW_NEW_UI_ONBOARDING_ON_START=true,
     * which then causes the "Meet the Islands Theme" blocking modal dialog to appear.
     *
     * By pre-setting "switched.from.classic.to.islands=false" in the early-access registry file,
     * the migration code sees a non-null value and returns early (processed-once guard),
     * preventing SHOW_NEW_UI_ONBOARDING_ON_START from ever being set to true.
     */
    private fun writeEarlyAccessRegistry() {
        // Format: alternating lines of key and value (one entry per two lines)
        val content = "switched.from.classic.to.islands\nfalse\n"
        driver.writeFileInContainer(
            "$configGuestDir/early-access-registry.txt",
            content,
        )
    }

    /**
     * Pre-write the AI promo window state to prevent AIPromoWindowAdvisor from
     * performing a remote network call that can block VfsData initialization for up
     * to 8 minutes in Docker containers (socket connection timeout = 480s).
     *
     * Root cause: AIPromoWindowAdvisorPreheat starts verdict calculation immediately
     * after app init. In a fresh container, the verdict is UNSURE → it fetches
     * https://frameworks.jetbrains.com/llm-config/v2/products-promo.txt which may
     * time out. During this time, fleet.kernel.Transactor is blocked, which in turn
     * prevents VfsData from initializing, so the main IntelliJ window never appears.
     *
     * Fix: Pre-write "wasShown=true" so getVerdictFromStored() returns false immediately,
     * and "shouldShowNextTime=NO" as an additional guard.
     */
    private fun writeAiPromoState() {
        val xml = """<application>
  <component name="AIOnboardingPromoWindowAdvisor">
    <option name="shouldShowNextTime" value="NO" />
    <option name="wasShown" value="true" />
    <option name="attempts" value="1" />
  </component>
</application>
"""
        driver.writeFileInContainer(
            "$configGuestDir/options/AIOnboardingPromoWindowAdvisor.xml",
            xml,
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
            listOf("unzip", "-o", containerTempZip),
            workingDir = pluginsGuestDir,
            timeoutSeconds = 60,
        ).assertExitCode(0)
    }

}

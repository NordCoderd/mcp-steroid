/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration

import com.jonnyzzz.mcpSteroid.testHelper.docker.DockerDriver

/**
 * Utilities for configuring IntelliJ IDEA inside a Docker container.
 * Writes all necessary config files via [DockerDriver.writeFileInContainer]
 * to skip EULA dialogs, accept consent, trust project paths, etc.
 */
object IntelliJUtils {
    private const val HOME = "/home/agent"

    /**
     * Accept all IntelliJ EULA variants and data sharing consent via Java Preferences.
     * Keys from JetBrains test infrastructure (IDERunContext.kt).
     */
    fun writeEulaAcceptance(driver: DockerDriver, containerId: String) {
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
            containerId,
            "$HOME/.java/.userPrefs/jetbrains/privacy_policy/prefs.xml",
            prefsXml,
        )
    }

    /**
     * Accept data sharing / statistics consent.
     */
    fun writeConsentOptions(driver: DockerDriver, containerId: String) {
        val timestamp = System.currentTimeMillis()
        driver.writeFileInContainer(
            containerId,
            "$HOME/.config/JetBrains/consentOptions/accepted",
            "rsch.send.usage.stat:1.1:0:${timestamp}",
        )
    }

    /**
     * Pre-trust a project directory so the "Trust and Open Project?" dialog is skipped.
     * From JetBrains test infrastructure (IDETestContext.addProjectToTrustedLocations).
     */
    fun writeTrustedPaths(
        driver: DockerDriver,
        containerId: String,
        configDir: String,
        projectDir: String,
        trustedRoot: String = HOME,
    ) {
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
            appendLine("""        <option value="$trustedRoot" />""")
            appendLine("""      </list>""")
            appendLine("""    </option>""")
            appendLine("""  </component>""")
            appendLine("""</application>""")
        }

        driver.writeFileInContainer(
            containerId,
            "$configDir/options/trusted-paths.xml",
            trustedPathsXml,
        )
    }

    /**
     * Write all IntelliJ configuration needed for headless/unattended startup:
     * EULA acceptance, consent options, and trusted project paths.
     */
    fun configureForHeadlessStartup(
        driver: DockerDriver,
        containerId: String,
        configDir: String,
        projectDir: String,
    ) {
        writeEulaAcceptance(driver, containerId)
        writeConsentOptions(driver, containerId)
        writeTrustedPaths(driver, containerId, configDir, projectDir)
    }
}

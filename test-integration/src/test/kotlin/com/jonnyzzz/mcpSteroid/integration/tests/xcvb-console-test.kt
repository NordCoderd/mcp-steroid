/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.*
import com.jonnyzzz.mcpSteroid.testHelper.docker.startContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import org.junit.jupiter.api.Test

class XcvbConsoleTest {

    @Test
    fun testConsoleLayout() = runWithCloseableStack { lifetime ->
        val dockerFileBase = "ide-agent"
        val imageName = "$dockerFileBase-test"
        val scope = buildIdeImage(dockerFileBase, imageName)

        var container = startContainerDriver(
            lifetime, scope, imageName,
            extraEnvVars = emptyMap(),
            ports = listOf(
                XcvbVideoDriver.VIDEO_STREAMING_PORT
            )
        )

        val layoutManager = HorizontalLayoutManager()

        val xcvb = XcvbDriver(
            lifetime,
            container,
            layoutManager
        )

        xcvb.startDisplayServer()
        container = xcvb.withDisplay(container)

        val windowsDriver = XcvbWindowDriver(lifetime, container, xcvb.wholeScreenAreal())
        windowsDriver.startWindowManager()

        val videoDriver = XcvbVideoDriver(lifetime, container, windowsDriver, xcvb, "/tmp/ignored/video", "console test")
        videoDriver.startVideoService()

        val windowsLayout = WindowLayoutManager(windowsDriver, layoutManager)

        // Debug: list all windows and their PIDs before creating the console
        println("[DEBUG] Windows before console creation:")
        windowsDriver.listWindows(quietly = false).forEach { w ->
            println("[DEBUG]   id=${w.id} pid=${w.pid} title='${w.title}' rect=${w.rect}")
        }

        val consoleDriver = XcvbConsoleDriver(lifetime, container, windowsDriver)
        val console = consoleDriver.createConsoleDriver(container, "Title", windowsLayout.layoutStatusConsoleWindow())
        console.writeInfo("Preparing IntelliJ IDEA...")

        Thread.sleep(500)

        // Debug: list all windows and their PIDs after creating the console
        println("[DEBUG] Windows after console creation:")
        windowsDriver.listWindows(quietly = false).forEach { w ->
            println("[DEBUG]   id=${w.id} pid=${w.pid} title='${w.title}' rect=${w.rect}")
        }

        Thread.sleep(1000)
    }

}
/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.GitDriver


sealed class IntelliJProject{
    abstract fun IntelliJProjectDriver.deploy()

    object TestProject : ProjectFromRepository("test-project")
    object PyCharmTestProject : ProjectFromRepository("test-project-pycharm")
    object GoLandTestProject : ProjectFromRepository("test-project-goland")
    object WebStormTestProject : ProjectFromRepository("test-project-webstorm")

    object KeycloakProject : ProjectFromRemoteGit("https://github.com/keycloak/keycloak.git")

    open class ProjectFromRepository protected constructor(val projectName: String) : IntelliJProject() {
        override fun IntelliJProjectDriver.deploy() {
            console.writeInfo("Moving project $projectName files...")

            val hostProjectDir = container.mapGuestPathToHostPath(ijDriver.getGuestProjectDir())
            IdeTestFolders.copyProjectFiles(projectName, hostProjectDir)
        }
    }

    open class ProjectFromRemoteGit protected constructor(val repoUrl: String) : IntelliJProject() {
        override fun IntelliJProjectDriver.deploy() {
            GitDriver(container)
                .clone(repoUrl, ijDriver.getGuestProjectDir())
        }
    }
}

class IntelliJProjectDriver(
    val lifetime: CloseableStack,
    val container: ContainerDriver,
    val ijDriver: IntelliJDriver,
    val console: ConsoleDriver,
) {
    fun deployProject(project: IntelliJProject) {
        project.apply { deploy() }
    }
}

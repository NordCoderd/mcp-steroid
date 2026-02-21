/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.GitDriver


sealed class IntelliJProject{
    abstract fun IntelliJProjectDriver.deploy()

    /**
     * Returns the HTTPS clone URL for the repository that this project deploys,
     * or null if this project is not backed by a remote git repository.
     *
     * Used by [IntelliJContainer.create] to warm the bare repo cache on the host
     * before the container starts, so [GitDriver.cloneFromCachedBare] can use the
     * fast local clone path instead of hitting the remote.
     */
    open fun getRepoUrlForCache(): String? = null

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
        override fun getRepoUrlForCache(): String = repoUrl

        override fun IntelliJProjectDriver.deploy() {
            val git = GitDriver(container)
            val guestProjectDir = ijDriver.getGuestProjectDir()

            // Derive owner/repo from URL (e.g. "keycloak/keycloak") for the cache path.
            val ownerAndRepo = repoUrl
                .removePrefix("https://github.com/")
                .trimEnd('/')
                .removeSuffix(".git")

            // Use the bare repo cache when it is mounted at /repo-cache inside the container.
            val clonedFromCache = git.cloneFromCachedBare(ownerAndRepo, guestProjectDir)
            if (!clonedFromCache) {
                console.writeInfo("Cache miss for $ownerAndRepo — cloning from $repoUrl ...")
                git.clone(repoUrl, guestProjectDir)
            }
        }
    }

    /**
     * Deploy a project by cloning a git repository at a specific commit and optionally
     * applying a patch. The project is deployed at the IDE's project-home path so
     * IntelliJ opens it directly on startup — no [steroid_open_project] call needed.
     *
     * Used by arena test runners (e.g. DpaiaArenaTest) to pre-deploy the test scenario
     * before IntelliJ starts, so that [waitForProjectReady] handles indexing as usual.
     *
     * @param cloneUrl         Full HTTPS clone URL (e.g. "https://github.com/dpaia/empty-maven-springboot3")
     * @param repoOwnerAndName Owner/repo without .git suffix (e.g. "dpaia/empty-maven-springboot3")
     * @param baseCommit       Git commit SHA to check out
     * @param testPatch        Unified diff to apply after checkout; empty string means no patch
     * @param displayName      Human-readable name used in console messages
     */
    class ProjectFromGitCommitAndPatch(
        val cloneUrl: String,
        val repoOwnerAndName: String,
        val baseCommit: String,
        val testPatch: String,
        val displayName: String,
    ) : IntelliJProject() {
        override fun getRepoUrlForCache(): String = cloneUrl

        override fun IntelliJProjectDriver.deploy() {
            console.writeInfo("Cloning $displayName ...")
            val git = GitDriver(container)
            val guestProjectDir = ijDriver.getGuestProjectDir()

            val clonedFromCache = git.cloneFromCachedBare(repoOwnerAndName, guestProjectDir)
            if (!clonedFromCache) {
                console.writeInfo("Cache miss — cloning from $cloneUrl ...")
                git.clone(cloneUrl, guestProjectDir, shallow = false, timeoutSeconds = 120)
            }

            git.checkout(guestProjectDir, baseCommit)

            if (testPatch.isNotBlank()) {
                console.writeInfo("Applying test patch for $displayName ...")
                git.applyPatch(guestProjectDir, testPatch)
            }

            console.writeSuccess("$displayName ready")
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

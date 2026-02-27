/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.copyToContainer
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.git.BareRepoCache
import com.jonnyzzz.mcpSteroid.testHelper.git.GitDriver
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import java.io.File


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

    /**
     * Warm host-side cache artifacts before container startup.
     *
     * Default behavior:
     * - if [getRepoUrlForCache] is non-null: warm bare git cache
     * - otherwise: no-op
     */
    open fun warmRepoCache(cacheDir: File) {
        val repoUrl = getRepoUrlForCache() ?: return
        BareRepoCache.ensureRepo(repoUrl, cacheDir)
    }

    /**
     * Relative path (from project root) of the file to open when the IDE starts.
     * When null, the default README.md / first source file fallback is used.
     */
    open val openFileOnStart: String? = null

    object TestProject : ProjectFromRepository(
        "test-project",
        openFile = "src/test/kotlin/com/jonnyzzz/mcpSteroid/demo/DemoByJonnyzzzTest.kt",
    )
    object PyCharmTestProject : ProjectFromRepository("test-project-pycharm", openFile = "main.py")
    object GoLandTestProject : ProjectFromRepository("test-project-goland", openFile = "main.go")
    object WebStormTestProject : ProjectFromRepository("test-project-webstorm", openFile = "index.js")
    object RiderTestProject : ProjectFromRepository(
        "test-project-rider",
        openFile = "DemoRider.Tests/LeaderboardTests.cs",
    )

    object KeycloakProject : ProjectFromRemoteGit("https://github.com/keycloak/keycloak.git")
    object IntelliJMasterProject : ProjectFromIntelliJMasterZip(
        openFile = "platform/platform-tests/testSrc/com/intellij/openapi/vfs/newvfs/persistent/PersistentFsTest.java",
    )

    open class ProjectFromRepository protected constructor(
        val projectName: String,
        private val openFile: String? = null,
    ) : IntelliJProject() {
        override val openFileOnStart: String? get() = openFile
        override fun IntelliJProjectDriver.deploy() {
            console.writeInfo("Copying project $projectName files into container-local project-home...")
            val guestProjectDir = ijDriver.getGuestProjectDir()
            val hostProjectSourceDir = IdeTestFolders.dockerDir.resolve(projectName)
            require(hostProjectSourceDir.isDirectory) {
                "Project source directory does not exist: ${hostProjectSourceDir.absolutePath}"
            }

            container.startProcessInContainer {
                this
                    .args("rm", "-rf", guestProjectDir)
                    .timeoutSeconds(30)
                    .description("Remove stale project directory $guestProjectDir")
                    .quietly()
            }.awaitForProcessFinish().assertExitCode(0, "Failed to clean project directory $guestProjectDir")

            container.copyToContainer(hostProjectSourceDir, guestProjectDir)

            // docker cp on macOS Docker Desktop creates directories owned by root inside the container.
            // Fix ownership so the agent user can write to the project directory (e.g. create .idea/).
            // Must run as root (user 0:0) since the files are root-owned and only root can chown them.
            container.startProcessInContainer {
                this
                    .user("0:0")
                    .args("chown", "-R", "agent:agent", guestProjectDir)
                    .timeoutSeconds(30)
                    .description("Fix project directory ownership for agent user")
                    .quietly()
            }.awaitForProcessFinish().assertExitCode(0, "Failed to chown project directory $guestProjectDir")
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

    open class ProjectFromIntelliJMasterZip protected constructor(
        private val openFile: String? = null,
        private val zipUrl: String = INTELLIJ_MASTER_GIT_CLONE_LINUX_ZIP_URL,
        private val repoUrlOverride: String? = null,
        private val branch: String = INTELLIJ_MASTER_BRANCH,
    ) : IntelliJProject() {
        override val openFileOnStart: String? get() = openFile

        override fun warmRepoCache(cacheDir: File) {
            ensureIntelliJGitCloneZipInCache(cacheDir, zipUrl)
        }

        override fun IntelliJProjectDriver.deploy() {
            val guestProjectDir = ijDriver.getGuestProjectDir()
            val guestZipInCache = "/repo-cache/intellij-master-git-clone/ultimate-git-clone-linux.zip"
            container.startProcessInContainer {
                this
                    .args("test", "-f", guestZipInCache)
                    .timeoutSeconds(10)
                    .description("Verify IntelliJ ZIP exists at $guestZipInCache")
                    .quietly()
            }.assertExitCode(0) {
                "Missing IntelliJ git clone ZIP in repo cache at $guestZipInCache. " +
                        "Warm cache first via ensureIntelliJGitCloneZipInCache()."
            }

            console.writeInfo("Unpacking IntelliJ repository and syncing $branch...")
            val setupScript = """
                set -euo pipefail
                zipPath="$guestZipInCache"
                targetDir="$guestProjectDir"
                repoUrlOverride="${repoUrlOverride ?: ""}"
                branch="$branch"
                unpackDir="/tmp/intellij-master-unpack"

                rm -rf "${'$'}unpackDir" "${'$'}targetDir"
                mkdir -p "${'$'}unpackDir"
                unzip -q "${'$'}zipPath" -d "${'$'}unpackDir"

                if [ -d "${'$'}unpackDir/.git" ]; then
                  repoDir="${'$'}unpackDir"
                else
                  gitDir="$(find "${'$'}unpackDir" -mindepth 1 -maxdepth 4 -type d -name .git | head -n 1)"
                  if [ -z "${'$'}gitDir" ]; then
                    echo "No .git directory found in unpacked ZIP: ${'$'}zipPath" >&2
                    exit 1
                  fi
                  repoDir="$(dirname "${'$'}gitDir")"
                fi

                mkdir -p "$(dirname "${'$'}targetDir")"
                mv "${'$'}repoDir" "${'$'}targetDir"
                rm -rf "${'$'}unpackDir"

                if ! git -C "${'$'}targetDir" remote | grep -qx origin; then
                  echo "Expected origin remote in IntelliJ ZIP checkout at ${'$'}targetDir" >&2
                  exit 1
                fi

                if [ -n "${'$'}repoUrlOverride" ]; then
                  git -C "${'$'}targetDir" remote set-url origin "${'$'}repoUrlOverride"
                fi

                if git -C "${'$'}targetDir" config --get-all remote.origin.fetch >/dev/null 2>&1; then
                  git -C "${'$'}targetDir" config --unset-all remote.origin.fetch
                fi
                git -C "${'$'}targetDir" config --add remote.origin.fetch "+refs/heads/${'$'}branch:refs/remotes/origin/${'$'}branch"
                GIT_SSH_COMMAND='ssh -o StrictHostKeyChecking=accept-new' \
                  git -C "${'$'}targetDir" fetch --prune --depth 1 origin "${'$'}branch"
                git -C "${'$'}targetDir" reset --hard
                git -C "${'$'}targetDir" clean -fdx
                git -C "${'$'}targetDir" checkout -f -B "${'$'}branch" --track "origin/${'$'}branch"
                chown -R agent:agent "${'$'}targetDir"
            """.trimIndent()

            container.startProcessInContainer {
                this
                    .user("0:0")
                    .args("bash", "-lc", setupScript)
                    .timeoutSeconds(900)
                    .description("Prepare IntelliJ repository from ZIP and checkout $branch")
            }.assertExitCode(0) { "Failed to prepare IntelliJ repository from ZIP" }
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

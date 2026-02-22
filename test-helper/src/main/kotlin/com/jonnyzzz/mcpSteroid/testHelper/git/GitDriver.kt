/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.git

import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.mkdirs
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode

/**
 * Reusable Git operations for Docker containers.
 * Works with any [com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver] instance.
 */
//TODO: We need to cache repositories on the host machine as bare checkout to make deployment faster
class GitDriver(
    private val driver: ContainerDriver,
) {
    /**
     * Clone a git repository into [targetDir] inside the container.
     * Creates the parent directory if needed.
     *
     * @param repoUrl repository URL (https, ssh, or file)
     * @param targetDir guest path for the cloned repository
     * @param shallow use `--depth 1` for a shallow clone (default true)
     * @param timeoutSeconds timeout for the clone operation
     */
    fun clone(
        repoUrl: String,
        targetDir: String,
        shallow: Boolean = true,
        timeoutSeconds: Long = 300,
    ) {
        // Ensure parent directory exists
        val parent = targetDir.substringBeforeLast("/")
        driver.mkdirs(parent)

        val args = mutableListOf("git", "clone")
        if (shallow) {
            args += listOf("--depth", "1")
        }
        args += listOf(repoUrl, targetDir)

        println("[GIT] Cloning $repoUrl into $targetDir (shallow=$shallow)...")
        driver.runInContainer(args, timeoutSeconds = timeoutSeconds)
            .assertExitCode(0, message = "git clone $repoUrl")
    }

    /**
     * Clone a repository and checkout a specific commit.
     * Uses full clone (not shallow) since the commit may not be at HEAD.
     *
     * @param repoUrl repository URL
     * @param targetDir guest path for the cloned repository
     * @param commit commit SHA or ref to checkout
     * @param timeoutSeconds timeout for the clone operation
     */
    fun cloneAndCheckout(
        repoUrl: String,
        targetDir: String,
        commit: String,
        timeoutSeconds: Long = 300,
    ) {
        clone(repoUrl, targetDir, shallow = false, timeoutSeconds = timeoutSeconds)
        checkout(targetDir, commit)
    }

    /**
     * Checkout a specific commit or ref in a repository.
     */
    fun checkout(repoDir: String, ref: String) {
        println("[GIT] Checking out $ref in $repoDir...")
        driver.runInContainer(
            listOf("git", "-C", repoDir, "checkout", ref),
            timeoutSeconds = 30,
        ).assertExitCode(0, message = "git checkout $ref")
    }

    /**
     * Try to clone a repository from a host-side bare cache mounted inside the container.
     *
     * Checks whether `{cacheGuestPath}/{ownerAndRepo}.git` exists in the container.
     * If it does, clones from it using a fast `file://` local clone.
     * If it does not, returns false so the caller can fall back to a remote clone.
     *
     * @param ownerAndRepo repo identifier without `.git`, e.g. `"dpaia/feature-service"`
     * @param targetDir guest path for the cloned repository
     * @param cacheGuestPath guest path where the host repo cache is mounted (default `/repo-cache`)
     * @return true if cloned from cache; false if the bare repo was not found in the cache
     */
    fun cloneFromCachedBare(
        ownerAndRepo: String,
        targetDir: String,
        cacheGuestPath: String = "/repo-cache",
    ): Boolean {
        val bareGuestPath = "$cacheGuestPath/$ownerAndRepo.git"

        val check = driver.runInContainer(
            listOf("test", "-d", bareGuestPath),
            timeoutSeconds = 5,
            quietly = true,
        )
        if (check.exitCode != 0) {
            println("[GIT] No cached bare repo at $bareGuestPath, will clone from remote")
            return false
        }

        val parent = targetDir.substringBeforeLast("/")
        driver.mkdirs(parent)

        println("[GIT] Cloning from bare cache: $bareGuestPath -> $targetDir ...")
        driver.runInContainer(
            listOf("git", "clone", "file://$bareGuestPath", targetDir),
            timeoutSeconds = 120,
        ).assertExitCode(0, "git clone from bare cache $bareGuestPath")

        return true
    }

    /**
     * Apply a patch to a repository using `git apply`.
     *
     * @param repoDir guest path of the repository
     * @param patchContent the patch text (unified diff format)
     */
    fun applyPatch(repoDir: String, patchContent: String) {
        if (patchContent.isBlank()) {
            println("[GIT] No patch to apply")
            return
        }

        val patchPath = "$repoDir/_tmp_patch.diff"
        println("[GIT] Applying patch to $repoDir...")
        driver.writeFileInContainer(patchPath, patchContent)

        try {
            driver.runInContainer(
                listOf("git", "-C", repoDir, "apply", "--allow-empty", patchPath),
                timeoutSeconds = 30,
            ).assertExitCode(0, message = "git apply patch")
        } finally {
            driver.runInContainer(listOf("rm", "-f", patchPath), timeoutSeconds = 5)
        }
    }
}
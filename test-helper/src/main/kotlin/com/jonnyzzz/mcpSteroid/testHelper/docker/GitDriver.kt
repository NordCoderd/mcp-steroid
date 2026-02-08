/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.assertExitCode

/**
 * Reusable Git operations for Docker containers.
 * Works with any [ContainerDriver] instance.
 */
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

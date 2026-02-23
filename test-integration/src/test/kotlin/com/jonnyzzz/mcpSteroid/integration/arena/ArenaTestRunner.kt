/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.git.GitDriver

/**
 * Manages the execution of a dpaia arena test case inside a Docker container
 * with IntelliJ IDEA + MCP Steroid.
 *
 * Responsibilities:
 * 1. Clone the test case repository inside the container
 * 2. Check out the base commit
 * 3. Apply test patches (so the agent has failing tests to fix)
 * 4. Build a prompt from the problem statement
 * 5. Run the prompt via an AI agent
 * 6. Evaluate the result
 */
class ArenaTestRunner(
    private val container: ContainerDriver,
    private val projectGuestDir: String,
) {

    private val git = GitDriver(container)

    /**
     * Clone a repository and check out a specific commit inside the container.
     *
     * @param testCase The test case with repo URL and base commit
     * @return The guest directory path where the project was cloned
     */
    fun cloneAndCheckout(testCase: DpaiaTestCase): String {
        // Use a unique suffix so parallel runs for different agents don't collide
        val suffix = System.nanoTime().toString(36)
        val projectDir = "$projectGuestDir/${testCase.repoName}-$suffix"

        println("[ARENA] Cloning ${testCase.cloneUrl} into $projectDir ...")

        // Try fast local clone from the bare repo cache mounted at /repo-cache
        val ownerAndRepo = testCase.repo.removeSuffix(".git")
        val clonedFromCache = git.cloneFromCachedBare(ownerAndRepo, projectDir)
        if (!clonedFromCache) {
            // Cache miss: fall back to a full remote clone (needed to checkout any commit)
            git.clone(testCase.cloneUrl, projectDir, shallow = false, timeoutSeconds = 120)
        }

        git.checkout(projectDir, testCase.baseCommit)
        return projectDir
    }

    /**
     * Apply the test patch to the cloned repository so the agent has
     * failing tests that define expected behavior.
     *
     * @param testCase The test case containing the test patch
     * @param projectDir The guest directory where the repo was cloned
     */
    fun applyTestPatch(testCase: DpaiaTestCase, projectDir: String) {
        if (testCase.testPatch.isBlank()) {
            println("[ARENA] No test patch to apply for ${testCase.instanceId}")
            return
        }
        println("[ARENA] Applying test patch for ${testCase.instanceId} ...")
        git.applyPatch(projectDir, testCase.testPatch)
    }

    /**
     * Build the prompt that will be sent to the AI agent.
     *
     * Kept minimal on purpose: the MCP tool descriptions and mcp-steroid://skill/
     * resources already contain all API usage guidance (threading rules, output
     * truncation, VFS patterns, etc.). This prompt is purely a task brief.
     *
     * @param withMcp when true, instructs the agent to use [steroid_execute_code] and IntelliJ IDEA;
     *                when false, instructs the agent to use shell commands (mvn/gradle/bash) only —
     *                used for the A/B comparison baseline (no IDE tooling).
     */
    fun buildPrompt(testCase: DpaiaTestCase, projectDir: String, withMcp: Boolean = true): String = buildString {
        appendLine("You are working on a Java Spring project located at: `$projectDir`")
        appendLine()
        appendLine("## Problem Statement")
        appendLine()
        appendLine(testCase.problemStatement)
        appendLine()

        if (testCase.failToPass.isNotEmpty()) {
            appendLine("## Tests to Fix (FAIL_TO_PASS)")
            appendLine()
            appendLine("These tests are currently failing and must pass after your fix:")
            for (test in testCase.failToPass) {
                appendLine("- `$test`")
            }
            appendLine()
        }

        if (testCase.passToPass.isNotEmpty()) {
            appendLine("## Regression Tests (PASS_TO_PASS)")
            appendLine()
            appendLine("These tests must continue to pass:")
            for (test in testCase.passToPass) {
                appendLine("- `$test`")
            }
            appendLine()
        }

        appendLine("## Environment")
        appendLine()
        val buildWrapper = if (testCase.buildSystem == "maven") "`./mvnw`" else "`./gradlew`"
        appendLine("- Build system: **${testCase.buildSystem}** — use $buildWrapper (the system binary is not installed)")
        appendLine("- **Docker IS available.** Tests that use `@Testcontainers` or require a running database WILL")
        appendLine("  work in this environment. Do NOT skip test execution because you see `@Testcontainers`.")
        appendLine("  **Always run the FAIL_TO_PASS tests** as the final verification step — `test-compile` alone is NOT sufficient.")
        appendLine("  **Exception — environment-wide Docker failure**: If `./mvnw test` fails with")
        appendLine("  `Could not find a valid Docker environment`, run ONE existing (pre-patch) test")
        appendLine("  (e.g. `ProductRepositoryTest`) to baseline. If it also fails with the same Docker error,")
        appendLine("  the environment lacks Docker-in-Docker — this is NOT a code defect. Use `runInspectionsDirectly`")
        appendLine("  as your verification and report ARENA_FIX_APPLIED: yes. Do NOT investigate Docker further.")
        appendLine("- **Multi-agent environment**: another agent slot may have already modified files.")
        appendLine("  Check VCS changes FIRST (before writing any code):")
        appendLine("  ```kotlin")
        appendLine("  val changes = readAction { com.intellij.openapi.vcs.changes.ChangeListManager.getInstance(project)")
        appendLine("      .allChanges.mapNotNull { it.virtualFile?.path } }")
        appendLine("  println(if (changes.isEmpty()) \"Clean slate\" else \"ALREADY MODIFIED:\\n\" + changes.joinToString(\"\\n\"))")
        appendLine("  ```")
        appendLine("  If files are already modified: do NOT re-read all files from scratch. Instead:")
        appendLine("  (1) run `runInspectionsDirectly` on the modified files, (2) run the FAIL_TO_PASS tests.")
        appendLine("  If tests pass: output ARENA_FIX_APPLIED: yes. Do NOT re-implement what a prior agent wrote.")

        if (withMcp) {
            appendLine()
            appendLine("The project is already open and fully indexed in IntelliJ IDEA.")
            appendLine("Use `steroid_execute_code` for all IDE interactions.")
            appendLine("Read `mcp-steroid://skill/coding-with-intellij` for patterns and API guidance.")
            appendLine()
            appendLine("**VFS write rule**: ALL mutation ops (createDirectoryIfMissing, createChildData, saveText,")
            appendLine("delete, rename) MUST be inside a SINGLE `writeAction { }` block. Calling them outside")
            appendLine("writeAction throws 'Write access is allowed inside write-action only' at runtime.")
            appendLine()
            appendLine("**After creating or modifying Java/Kotlin files**: run `runInspectionsDirectly(vf)` immediately.")
            appendLine("A clean result (no errors) is your checkpoint — do NOT re-read files you just wrote,")
            appendLine("do NOT re-create files you already created. Trust successful write + clean inspection.")
            appendLine("**Spring-specific inspection**: `runInspectionsDirectly` also catches Spring bean conflicts:")
            appendLine("duplicate `@Bean` definitions, unresolved `@Autowired`. Run it on `@Configuration` classes")
            appendLine("BEFORE `./mvnw test` — catches `NoUniqueBeanDefinitionException`-class bugs in ~5s vs ~90s.")
            appendLine()
            appendLine("**ClassCanBeRecord inspection = REQUIRED action for new DTOs**: If `runInspectionsDirectly`")
            appendLine("reports `[ClassCanBeRecord]` on a newly created class, convert it to a Java `record`.")
            appendLine("This is NOT optional — reference solutions use records. Ignoring it causes structural mismatch.")
            appendLine("```kotlin")
            appendLine("// WRONG: public class Product { private String name; ... }")
            appendLine("// CORRECT: public record Product(String name, int weight) {}")
            appendLine("```")
            appendLine()
            appendLine("**After bulk file creation, verify what was created** before making more calls:")
            appendLine("```kotlin")
            appendLine("import com.intellij.psi.search.FilenameIndex")
            appendLine("val created = readAction { FilenameIndex.getAllFilesByExt(project, \"java\", GlobalSearchScope.projectScope(project))")
            appendLine("    .filter { it.path.contains(\"/src/main/java/\") }.map { it.name } }")
            appendLine("println(\"Created: \$created\")")
            appendLine("```")
            appendLine("If a file you expected is missing, create ONLY that one. Do NOT recreate files already created.")
            appendLine()
            appendLine("**Batch file reads — use 1 call instead of 5-8**: Each steroid_execute_code call takes ~20s.")
            appendLine("Read pom.xml + key source files + test file in ONE call:")
            appendLine("```kotlin")
            appendLine("for (path in listOf(\"pom.xml\", \"src/main/java/.../TargetService.java\", \"src/test/.../FailingTest.java\")) {")
            appendLine("    val vf = findProjectFile(path) ?: run { println(\"NOT FOUND: \$path\"); continue }")
            appendLine("    println(\"\\n=== \$path ===\"); println(VfsUtil.loadText(vf))")
            appendLine("}")
            appendLine("```")
            appendLine()
            appendLine("**Single exploration pass — do NOT re-read files**: Files you read this session remain in your")
            appendLine("conversation history. Do NOT restart exploration under a new task_id. Only re-read a file if")
            appendLine("you explicitly modified it and need to verify the write succeeded.")
            appendLine()
            appendLine("**Staged verification (Maven projects)**:")
            appendLine("1. `runInspectionsDirectly(vf)` for each changed file — catches single-file syntax/import errors (~5s)")
            appendLine("2. `./mvnw compile -q` — catches cross-file type errors, missing methods, broken call sites (~30-60s)")
            appendLine("3. `./mvnw test -Dtest=TargetTest` — only after steps 1+2 pass, for final FAIL_TO_PASS verification")
            appendLine("⚠️ runInspectionsDirectly is file-scoped: it does NOT catch errors in other files calling your changed signatures.")
            appendLine("Always run `./mvnw compile -q` before running tests when you modified a widely-used class (entity, DTO, service).")
            appendLine()
            appendLine("**Naming conventions**: Before creating a new class, discover what naming patterns already exist.")
            appendLine("Tests often send JSON directly (no import) so the class name matters. Do this FIRST:")
            appendLine("```kotlin")
            appendLine("val allNames = readAction { PsiShortNamesCache.getInstance(project).allClassNames.toList() }")
            appendLine("allNames.filter { it.endsWith(\"Payload\") || it.endsWith(\"Request\") || it.endsWith(\"Dto\") }.sorted().forEach { println(it) }")
            appendLine("```")
            appendLine()
            appendLine("**Package names: derive from TEST IMPORTS — NOT from Gradle `group` or directory names**:")
            appendLine("The Gradle `group = 'shop.microservices.api'` is a Maven artifact coordinate, NOT the Java package prefix.")
            appendLine("Always extract required class packages from test import statements before creating new files:")
            appendLine("```kotlin")
            appendLine("import com.intellij.psi.PsiJavaFile")
            appendLine("val testFile = readAction { FilenameIndex.getVirtualFilesByName(\"ProductServiceApiTests.java\",")
            appendLine("    GlobalSearchScope.projectScope(project)).firstOrNull() }")
            appendLine("val testImports = testFile?.let { vf -> readAction {")
            appendLine("    (PsiManager.getInstance(project).findFile(vf) as? PsiJavaFile)")
            appendLine("        ?.importList?.importStatements?.map { it.qualifiedName ?: \"\" }")
            appendLine("} }")
            appendLine("println(\"Test imports: \$testImports\")")
            appendLine("// ↑ Use these packages for new files — NOT the Gradle `group` field")
            appendLine("```")
            appendLine()
            appendLine("**PSI over file-by-file reading — use for class structure and call graphs**:")
            appendLine("If you find yourself reading 3+ files to trace how code flows, stop and use PSI instead.")
            appendLine("One PSI call replaces 5-10 VfsUtil.loadText reads for understanding class structure:")
            appendLine("```kotlin")
            appendLine("// Get all methods of a service class (no file read needed):")
            appendLine("val cls = readAction { JavaPsiFacade.getInstance(project).findClass(\"com.example.FeatureService\", projectScope()) }")
            appendLine("cls?.methods?.forEach { m -> println(\"\${m.name}(\${m.parameterList.parameters.joinToString { it.type.presentableText }})\") }")
            appendLine("// Find all callers (replaces grep/file search for usages):")
            appendLine("import com.intellij.psi.search.searches.ReferencesSearch")
            appendLine("ReferencesSearch.search(cls!!, projectScope()).findAll().forEach { println(\"Caller: \${it.element.containingFile.name}\") }")
            appendLine("```")
            appendLine("Prefer PSI structural queries for: finding method signatures, tracing call graphs,")
            appendLine("checking what implements an interface, finding all @Service or @Entity classes.")
            appendLine()
            appendLine("**Testcontainers tests work in this environment** — Docker IS fully available.")
            appendLine("Do NOT skip test execution because you see `@Testcontainers`, `AbstractIT`, or similar markers.")
            appendLine("**Always run the FAIL_TO_PASS test classes as the final verification step**:")
            appendLine("Run them **one at a time** — not all at once. Multiple Spring Boot tests × verbose startup logs = 100k+ chars → MCP token overflow:")
            appendLine("`./mvnw test -Dtest=SingleClassName -Dspotless.check.skip=true -Dcheckstyle.skip=true`")
            appendLine("⚠️ **Do NOT use `-q`** — Maven quiet mode suppresses all `[INFO]` lines including `\"Tests run:\"` summary.")
            appendLine("Exit code 0 alone is NOT sufficient to confirm tests passed. Capture output to verify `Tests run:` line.")
            appendLine("Compile success (`test-compile` exit 0) alone is NOT sufficient — run the actual tests.")
            appendLine("If ProcessBuilder output overflows (total lines > 800), filter by keywords using single println() calls:")
            appendLine("```kotlin")
            appendLine("val keywords = listOf(\"Tests run:\", \"FAILED\", \"ERROR\", \"Caused by:\", \"BUILD\", \"Could not\")")
            appendLine("println(lines.take(20).joinToString(\"\\n\"))  // Spring startup errors at top")
            appendLine("println(lines.filter { l -> keywords.any { k -> k in l } }.take(50).joinToString(\"\\n\"))  // signal lines")
            appendLine("println(lines.takeLast(15).joinToString(\"\\n\"))  // BUILD FAILURE summary at bottom")
            appendLine("// ⚠️ Use println(joinToString) NOT forEach(::println) — forEach may not flush all lines in MCP output")
            appendLine("```")
            appendLine()
            appendLine("**If steroid_execute_code fails with a compile error** (e.g., `unresolved reference 'GlobalSearchScope'`):")
            appendLine("Read the error message, add the missing import, and resubmit. Do NOT switch to Bash/grep after a compile error.")
            appendLine("One corrected steroid call is faster than 10 grep commands. Common missing imports:")
            appendLine("```kotlin")
            appendLine("import com.intellij.psi.search.FilenameIndex")
            appendLine("import com.intellij.psi.search.GlobalSearchScope  // ← most often missing")
            appendLine("import com.intellij.openapi.roots.ProjectRootManager")
            appendLine("import com.intellij.psi.search.PsiShortNamesCache")
            appendLine("```")
            appendLine()
            appendLine("**Test execution strategy for Maven projects**:")
            appendLine("⚠️ **CRITICAL — After editing pom.xml**: Use `ProcessBuilder(\"./mvnw\", ...)` as your PRIMARY test runner.")
            appendLine("The Maven IDE runner (`MavenRunConfigurationType`) triggers a project re-import modal after pom.xml changes,")
            appendLine("blocking the latch for up to **300 seconds**. Multiple agents hitting this in the same run = 900s wasted.")
            appendLine("Rule: **pom.xml modified → ProcessBuilder('./mvnw') first. pom.xml untouched → Maven IDE runner OK.**")
            appendLine()
            appendLine("**Maven IDE test runner (when pom.xml was NOT modified in this session)**:")
            appendLine("Use `MavenRunConfigurationType.runConfiguration()` — avoids 200k-char output truncation.")
            appendLine("⚠️ **CRITICAL**: Pass `dialog_killer: true` on the `steroid_execute_code` call that runs the Maven IDE runner.")
            appendLine("Without it, a Maven project-reimport dialog can silently block for the full 5-minute latch timeout (300s wasted).")
            appendLine("If the latch times out despite `dialog_killer: true`, fall back to `ProcessBuilder(\"./mvnw\", ...)` immediately.")
            appendLine("```kotlin")
            appendLine("import org.jetbrains.idea.maven.execution.MavenRunConfigurationType")
            appendLine("import org.jetbrains.idea.maven.execution.MavenRunnerParameters")
            appendLine("import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener")
            appendLine("import com.intellij.execution.testframework.sm.runner.SMTestProxy")
            appendLine("import java.util.concurrent.CountDownLatch")
            appendLine("import java.util.concurrent.TimeUnit")
            appendLine("val latch = CountDownLatch(1)")
            appendLine("var passed = false")
            appendLine("val conn = project.messageBus.connect()")
            appendLine("conn.subscribe(SMTRunnerEventsListener.TEST_STATUS, object : SMTRunnerEventsListener {")
            appendLine("    override fun onTestingFinished(r: SMTestProxy.SMRootTestProxy) {")
            appendLine("        passed = r.isPassed(); val failed = r.getAllTests().count { it.isDefect }")
            appendLine("        println(\"Tests done — passed=\$passed failures=\$failed\")")
            appendLine("        r.getAllTests().filter { it.isDefect }.forEach { println(\"FAILED: \${it.name}\\n\${it.errorMessage}\") }")
            appendLine("        conn.disconnect(); latch.countDown()")
            appendLine("    }")
            appendLine("    override fun onTestingStarted(r: SMTestProxy.SMRootTestProxy) {}")
            appendLine("    override fun onTestFailed(t: SMTestProxy) { println(\"FAILED: \${t.name}\") }")
            appendLine("    // ⚠️ ALL abstract methods must be implemented — SMTRunnerEventsListener is NOT an adapter")
            appendLine("    // (SMTRunnerEventsAdapter was removed in IntelliJ 2025.x — missing stubs cause compilation failure)")
            appendLine("    override fun onTestsCountInSuite(count: Int) {}")
            appendLine("    override fun onTestStarted(test: SMTestProxy) {}")
            appendLine("    override fun onTestFinished(test: SMTestProxy) {}")
            appendLine("    override fun onTestIgnored(test: SMTestProxy) {}")
            appendLine("    override fun onSuiteFinished(suite: SMTestProxy) {}")
            appendLine("    override fun onSuiteStarted(suite: SMTestProxy) {}")
            appendLine("    override fun onCustomProgressTestsCategory(categoryName: String?, count: Int) {}")
            appendLine("    override fun onCustomProgressTestStarted() {}")
            appendLine("    override fun onCustomProgressTestFailed() {}")
            appendLine("    override fun onCustomProgressTestFinished() {}")
            appendLine("    override fun onSuiteTreeNodeAdded(testProxy: SMTestProxy) {}")
            appendLine("    override fun onSuiteTreeStarted(suite: SMTestProxy) {}")
            appendLine("})")
            appendLine("MavenRunConfigurationType.runConfiguration(project,")
            appendLine("    MavenRunnerParameters(true, project.basePath!!, \"pom.xml\",")
            appendLine("        listOf(\"test\", \"-Dtest=UserRestControllerTests#testA+testB\", \"-Dspotless.check.skip=true\"),")
            appendLine("        emptyList()), null, null) {}")
            appendLine("latch.await(5, TimeUnit.MINUTES)")
            appendLine("println(\"Result: passed=\$passed\")")
            appendLine("```")
            appendLine("If you must use ProcessBuilder fallback, ALWAYS capture both ends:")
            appendLine("`lines.take(30)` (Spring startup/Testcontainers errors) + `lines.takeLast(30)` (Maven BUILD FAILURE).")
            appendLine("For very verbose output, also add keyword filtering: filter lines containing \"Tests run:\", \"FAILED\", \"Caused by:\", \"BUILD\", \"Could not\".")
            appendLine()
            appendLine("**Gradle projects — use ExternalSystemUtil.runTask (PREFERRED over ProcessBuilder('./gradlew'))**:")
            appendLine("⚠️ NEVER use ProcessBuilder('./gradlew') inside exec_code — spawns nested Gradle daemon inside IDE JVM.")
            appendLine("```kotlin")
            appendLine("import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings")
            appendLine("import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode")
            appendLine("import com.intellij.openapi.externalSystem.task.TaskCallback")
            appendLine("import com.intellij.openapi.externalSystem.util.ExternalSystemUtil")
            appendLine("import org.jetbrains.plugins.gradle.util.GradleConstants")
            appendLine("import java.util.concurrent.CountDownLatch; import java.util.concurrent.TimeUnit")
            appendLine("val latch = CountDownLatch(1); var ok = false")
            appendLine("val s = ExternalSystemTaskExecutionSettings()")
            appendLine("s.externalProjectPath = project.basePath!!; s.taskNames = listOf(\":api:test\")")
            appendLine("s.externalSystemIdString = GradleConstants.SYSTEM_ID.toString()")
            appendLine("ExternalSystemUtil.runTask(s, com.intellij.execution.executors.DefaultRunExecutor.EXECUTOR_ID,")
            appendLine("    project, GradleConstants.SYSTEM_ID,")
            appendLine("    object : TaskCallback { override fun onSuccess() { ok=true; latch.countDown() }; override fun onFailure() { latch.countDown() } },")
            appendLine("    ProgressExecutionMode.IN_BACKGROUND_ASYNC, false)")
            appendLine("latch.await(5, TimeUnit.MINUTES); println(\"Gradle result: success=\$ok\")")
            appendLine("```")
            appendLine("⚠️ **If ExternalSystemUtil returns success=false**: do NOT immediately fall back to ProcessBuilder('./gradlew').")
            appendLine("⚠️ **CRITICAL: `BUILD SUCCESSFUL` with ProcessBuilder exit=0 does NOT mean tests ran and passed.**")
            appendLine("  Gradle may have only compiled the code (UP-TO-DATE tasks or stopped before the test phase).")
            appendLine("  `Tests run: X, Failures: 0, Errors: 0` in the output is the ONLY confirmation tests actually ran.")
            appendLine("  Instead, read JUnit XML results directly to diagnose the failure:")
            appendLine("```kotlin")
            appendLine("val testResultsDir = findProjectFile(\"build/test-results/test\")")
            appendLine("testResultsDir?.children?.filter { it.name.endsWith(\".xml\") }?.forEach { xmlFile ->")
            appendLine("    val content = com.intellij.openapi.vfs.VfsUtil.loadText(xmlFile)")
            appendLine("    val failures = Regex(\"\"\"<failure[^>]*>(.+?)</failure>\"\"\", RegexOption.DOT_MATCHES_ALL)")
            appendLine("        .findAll(content).map { it.groupValues[1].take(300) }.toList()")
            appendLine("    if (failures.isNotEmpty()) println(\"FAIL \${xmlFile.name}: \" + failures.first())")
            appendLine("    else println(\"PASS \${xmlFile.name}\")")
            appendLine("}")
            appendLine("```")
        } else {
            appendLine()
            appendLine("Use shell commands (bash, find, cat, grep) and the build tool to navigate and verify.")
            appendLine("No IntelliJ IDE tools are available.")
        }

        appendLine()
        appendLine("## Completion")
        appendLine()
        appendLine("When done, output these markers on separate lines:")
        appendLine("ARENA_FIX_APPLIED: yes")
        appendLine("ARENA_SUMMARY: <one line summary of what you changed>")
        appendLine()
        appendLine("If tests fail, diagnose the failure — check BOTH the first 30 lines (Spring/Testcontainers startup errors)")
        appendLine("AND the last 30 lines (Maven BUILD FAILURE summary) of the `./mvnw test` output before retrying.")
    }

    /**
     * Run a complete arena test: clone (unless pre-deployed), patch, prompt agent, collect result.
     *
     * @param testCase The test case to run
     * @param agent The AI agent session to use
     * @param timeoutSeconds Maximum time for the agent to work
     * @param prewarm Optional lambda to run before the agent timer (excluded from agent budget).
     *                Use when pre-warming is needed AFTER container creation (legacy approach).
     *                When [predeployedProjectDir] is set, the project is already indexed by
     *                [waitForProjectReady] so no prewarm is needed.
     * @param predeployedProjectDir Guest path of a project already deployed and indexed in the IDE.
     *                              When set, skips clone + patch (done by IntelliJProject.deploy()).
     *                              The project was deployed via [IntelliJProject.ProjectFromGitCommitAndPatch]
     *                              and indexed by [IntelliJContainer.waitForProjectReady].
     */
    fun runTest(
        testCase: DpaiaTestCase,
        agent: AiAgentSession,
        withMcp: Boolean = true,
        timeoutSeconds: Long = 1800,
        prewarm: ((projectDir: String) -> Unit)? = null,
        predeployedProjectDir: String? = null,
    ): ArenaTestResult {
        println("[ARENA] ========================================")
        println("[ARENA] Running: ${testCase.instanceId}")
        println("[ARENA] Repo: ${testCase.repo}")
        println("[ARENA] Tags: ${testCase.tags}")
        println("[ARENA] Build: ${testCase.buildSystem}")
        println("[ARENA] ========================================")

        // Step 1+2: Clone and patch, unless the project was pre-deployed before IntelliJ started.
        val projectDir: String
        if (predeployedProjectDir != null) {
            println("[ARENA] Using pre-deployed project at $predeployedProjectDir (skipping clone+patch)")
            projectDir = predeployedProjectDir
        } else {
            projectDir = cloneAndCheckout(testCase)
            applyTestPatch(testCase, projectDir)
        }

        // Step 3: Build prompt
        val prompt = buildPrompt(testCase, projectDir, withMcp = withMcp)
        println("[ARENA] Prompt length: ${prompt.length} chars")

        // Pre-warm (NOT measured — IDE setup before the agent's timer starts)
        val prewarmStartMs = System.currentTimeMillis()
        if (prewarm != null) {
            println("[ARENA] Pre-warming IDE for ${testCase.instanceId} ...")
            prewarm(projectDir)
            println("[ARENA] Pre-warm complete")
        }
        val prewarmDurationMs = System.currentTimeMillis() - prewarmStartMs

        // Step 4: Run agent (START MEASURING)
        println("[ARENA] Running agent (timeout: ${timeoutSeconds}s) ...")
        val agentStartMs = System.currentTimeMillis()
        val agentResult = agent.runPrompt(prompt, timeoutSeconds = timeoutSeconds)
        val agentDurationMs = System.currentTimeMillis() - agentStartMs

        // Step 5: Evaluate
        val evaluation = evaluate(testCase, agentResult)

        println("[ARENA] ========================================")
        println("[ARENA] Result for ${testCase.instanceId}:")
        println("[ARENA]   Agent exit code: ${agentResult.exitCode}")
        println("[ARENA]   Agent claimed fix: ${evaluation.agentClaimedFix}")
        println("[ARENA]   Used MCP: ${evaluation.usedMcpSteroid}")
        println("[ARENA] ========================================")

        return ArenaTestResult(
            testCase = testCase,
            agentResult = agentResult,
            evaluation = evaluation,
            agentDurationMs = agentDurationMs,
            prewarmDurationMs = prewarmDurationMs,
        )
    }

    /**
     * Evaluate the agent's response against the test case expectations.
     */
    private fun evaluate(testCase: DpaiaTestCase, result: ProcessResult): ArenaEvaluation {
        val combined = result.stdout + "\n" + result.stderr

        return ArenaEvaluation(
            agentExitedSuccessfully = result.exitCode == 0,
            usedMcpSteroid = combined.contains("steroid_execute_code", ignoreCase = true),
            agentClaimedFix = combined.contains("ARENA_FIX_APPLIED: yes", ignoreCase = true),
            agentSummary = extractMarker(combined, "ARENA_SUMMARY:"),
        )
    }

    private fun extractMarker(text: String, marker: String): String? {
        val line = text.lines().find { it.trimStart().startsWith(marker, ignoreCase = true) }
        return line?.substringAfter(marker)?.trim()?.takeIf { it.isNotBlank() }
    }
}

/**
 * Result of running an arena test case.
 */
data class ArenaTestResult(
    val testCase: DpaiaTestCase,
    val agentResult: ProcessResult,
    val evaluation: ArenaEvaluation,
    /** Wall-clock milliseconds spent inside [agent.runPrompt] (excludes git clone and patch apply). */
    val agentDurationMs: Long = 0L,
    /** Wall-clock milliseconds spent in IDE pre-warm (open + index); excluded from agent budget. */
    val prewarmDurationMs: Long = 0L,
)

/**
 * Evaluation metrics for an arena test run.
 */
data class ArenaEvaluation(
    /** Whether the agent process exited with code 0 */
    val agentExitedSuccessfully: Boolean,

    /** Whether the agent used steroid_execute_code */
    val usedMcpSteroid: Boolean,

    /** Whether the agent reported it applied a fix */
    val agentClaimedFix: Boolean,

    /** The agent's one-line summary of changes, if provided */
    val agentSummary: String?,
)

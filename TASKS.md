# TASKS

Current focus: make MCP Steroid measurably better than vanilla agent runs on DPAIA Maven and Gradle projects by reducing tokens, tool errors, and wall-clock time.

## Guardrails

- Do not add methods to `McpSteroid*` interfaces.
- Do not add MCP tools.
- Prefer MCP server behavior, prompt resources, and DPAIA prompt/case improvements.
- Keep changes measurable with DPAIA metrics: tokens, tool calls, tool errors, native edit calls, apply-patch usage, and wall time.
- Run Docker-backed `:test-integration` / `:test-experiments` tests one at a time.

## Methodology

- Use Karpathy-style autoresearch: one narrow hypothesis, one change, one measured run, one evidence note.
- Use RLM-style context control: grep/partition first, then read only the relevant files.
- Use `run-agent.sh` reviews for non-trivial direction changes. Require three reviews and consensus before selecting the next low-hanging fruit.
- Keep run-agent artifacts outside this repository unless explicitly asked to preserve them.

## Completed This Iteration

- [x] DPAIA arena prompt cleanup: remove stale `applyPatch {}` DSL guidance and duplicate MCP-mode prompt rules from `ArenaTestRunner.buildPrompt()`.
  - Files: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/ArenaTestRunner.kt`
  - Consensus: Claude and Codex reviewers selected this as the next low-hanging fruit; Gemini selected Gradle prompt resources as a follow-up.
  - Expected effect: lower input tokens, fewer contradictory edit-path choices, fewer slow `steroid_execute_code` patch attempts.
  - Validation: `./gradlew :test-experiments:compileTestKotlin --warning-mode all` passed.

- [x] Measured one Claude+MCP DPAIA scenario after the prompt cleanup.
  - Command: `./gradlew :test-experiments:test --tests '*DpaiaPetclinicRest37Test.claude with mcp' --rerun-tasks --warning-mode all`
  - Result: fix claimed, MCP used, 184/184 tests passed, agent time 111s.
  - Tool mix: 2 agent `steroid_execute_code` calls in raw metrics, 3 Read, 1 Glob, 1 Grep, 2 native Edit, 2 Bash, `steroid_apply_patch` not used.
  - Lesson: correctness is good, but the edit prompt still needs a stronger dedicated `steroid_apply_patch` cue for import+method edits.

- [x] Tightened the next low-hanging arena edit guidance from the measured run.
  - Kept the `steroid_apply_patch` JSON example on `file_path`, matching `ApplyPatchToolHandler` and the generated tool description.
  - Explicitly classified "imports plus method" as a multi-hunk patch.
  - Review: first 3-agent pass blocked on an accidental `path`/`file_path` mismatch; after correction, Claude/Codex/Gemini approved the diff and agreed the next low-hanging fruit is to re-run the same scenario and measure native Edit reduction.

- [x] Measured the corrected `steroid_apply_patch` prompt on the same Claude+MCP DPAIA scenario.
  - Command: `./gradlew :test-experiments:test --tests '*DpaiaPetclinicRest37Test.claude with mcp' --rerun-tasks --warning-mode all`
  - Run dir: `test-experiments/build/test-logs/test/run-20260427-001705-dpaia__spring__petclinic__rest-37-mcp`
  - Result: fix claimed, MCP used, 184/184 tests passed, agent time 123s.
  - Tool mix from `docs/autoresearch/dpaia/metrics.py`: 12 total calls, 3 MCP calls, 9 native calls, 2 `steroid_execute_code`, 1 `steroid_apply_patch`, 2 Read, 2 Grep, 0 native Edit, 3 Bash, 0 errors.
  - Delta versus the prior run: native Edit 2 -> 0 and apply-patch false -> true; runtime 111s -> 123s because verification used 3 Bash Maven calls instead of 2.
  - Lesson: edit-path guidance worked. The next low-hanging prompt issue is verification: avoid duplicate Maven/Bash checks after a successful IDE build plus targeted test.

- [x] Route prompt resources to the dedicated `steroid_apply_patch` tool for multi-site literal edits.
  - Review artifacts: `/tmp/mcp-steroid-review/runs-next-20260427/`.
  - Consensus: Claude, Codex, and Gemini selected `update-apply-patch-tool-description-routing` before Gradle-resource work.
  - Files: `prompts/src/main/prompts/skill/execute-code-tool-description.md`, `prompts/src/main/prompts/skill/execute-code-overview.md`, `prompts/src/main/prompts/skill/coding-with-intellij.md`, `prompts/src/main/prompts/ide/apply-patch.md`, `prompts/src/test/kotlin/com/jonnyzzz/mcpSteroid/prompts/PromptRoutingContractTest.kt`.
  - Expected effect: stop teaching the slower `steroid_execute_code` + script-context `applyPatch` route as the default after DPAIA showed the dedicated MCP tool applies multi-file patches in tens of ms.
  - Validation: `:prompts:test --tests '*PromptRoutingContractTest*' --tests '*MarkdownArticleContractTest*' --tests '*ExecuteCodeToolDescriptionKtBlocksCompilationTest*' --warning-mode all` passed via IntelliJ Gradle runner.

- [x] Make `steroid_apply_patch` save touched documents before returning and cover disk persistence with integration tests.
  - Reference checked: after updating `~/Work/intellij`, IntelliJ's `ApplyTextFilePatch.updateDocumentContent()` uses `Document.setText(...)` followed by `FileDocumentManager.saveDocument(document)`.
  - Files: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/execution/ApplyPatch.kt`, `ApplyPatchTest.kt`, `ApplyPatchToolIntegrationTest.kt`.
  - Coverage: single hunk, same-file multi-hunk descending offsets, multiple files, empty hunks, missing file, missing old string, non-unique old string, read-only/save failure, and direct disk reads after the MCP HTTP tool returns.
  - Validation: `:ij-plugin:test --tests 'com.jonnyzzz.mcpSteroid.execution.ApplyPatchTest' --tests 'com.jonnyzzz.mcpSteroid.server.ApplyPatchToolIntegrationTest' --rerun-tasks --warning-mode all` passed.
  - Review: three-agent pass approved the persistence fix direction and requested explicit save-failure/read-only coverage; the hardening was implemented.

- [x] Configure DPAIA Microshop cases to use JDK 25 during IDE setup and compile warmup.
  - Files: `DpaiaCuratedCases.kt`, DPAIA runner/comparison setup call sites, `intelliJ-container.kt`, `mcp-steroid.kt`, `DpaiaConfigTest.kt`, `IdeTestHelpersTest.kt`.
  - Fixes: project SDK replacement now updates mismatched SDKs, compile warmup receives the case's configured JDK, and JAVA_HOME lookup fails fast unless a JDK path with `bin/javac` is emitted.
  - Validation: `:test-integration:test --tests 'com.jonnyzzz.mcpSteroid.integration.tests.IdeTestHelpersTest' --rerun-tasks --warning-mode all` passed.

- [x] Add a real IntelliJ Ultimate monorepo `thisLogger` lookup regression test.
  - Files: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/tests/IntelliJThisLoggerLookupTest.kt`.
  - Research: Marinade's MCP Steroid guide waits for `projectInitialized`, `indexingInProgress == false`, and no modal/background indexing, but IntelliJ's own API docs warn that `waitForSmartMode()` has no guarantee another dumb mode will not start before the next statement.
  - Fix direction from `~/Work/intellij`: use `Observation.awaitConfiguration(project)` for initial import/configuration readiness, then run the whole indexed lookup inside `smartReadAction(project)`.
  - Validation: `MCP_STEROID_INTELLIJ_CHECKOUT_DIR=/Users/jonnyzzz/Work/intellij ./gradlew :test-experiments:test --tests 'com.jonnyzzz.mcpSteroid.integration.tests.IntelliJThisLoggerLookupTest' --rerun-tasks --warning-mode all` passed in 8m41s with 4,191 references across 1,526 files.

- [x] Route MCP indexing guidance away from `waitForSmartMode()` as an indexed-read handoff.
  - Files: `ExecutionSuggestionService.kt`, `McpScriptContext.kt`, `prompt/skill.md`, `coding-with-intellij-{intro,patterns,psi,threading}.md`, `SkillReferenceHintTest.kt`, `IndexingGuidanceContractTest.kt`.
  - Fix: `IndexNotReadyException` and smart-mode hints now recommend `Observation.awaitConfiguration(project)` after import/sync/configuration and `smartReadAction { }` around the whole indexed PSI query.
  - Validation: `:ij-plugin:test --tests 'com.jonnyzzz.mcpSteroid.server.SkillReferenceHintTest' --rerun-tasks --warning-mode all` passed; scoped `:prompts:test` for `IndexingGuidanceContractTest`, `MarkdownArticleContractTest`, and changed Kt blocks passed after forced prompt regeneration.

## Next Candidates

- [x] Reduce redundant Maven verification in the DPAIA arena prompt.
  - Evidence: the latest successful run compiled with `steroid_execute_code`, then ran targeted Maven tests, then ran the full Maven suite. That kept correctness but increased Bash calls from 2 to 3 and runtime from 111s to 123s.
  - Implemented wording: do not rerun a completed Maven/Gradle target solely to recover `BUILD SUCCESS` hidden by `tail`/`grep`; explicitly allow reruns after code changes, real failures, incomplete runs, or Gradle skipped-test behavior.
  - Review: first 3-agent pass requested narrowing the wording; follow-up pass under `/tmp/mcp-steroid-review/runs-current-4/` approved with Claude/Codex/Gemini consensus.
  - Measurement: `DpaiaPetclinicRest37Test.claude with mcp` run `test-experiments/build/test-logs/test/run-20260427-003310-dpaia__spring__petclinic__rest-37-mcp` passed 184/184 tests in 101s with 0 native Edit, 1 `steroid_apply_patch`, 2 Bash, and 0 tool errors.
  - Delta versus the prior 123s run: Bash 3 -> 2, total tool calls 12 -> 11, runtime 123s -> 101s, pass rate unchanged.

- [x] Measure the dedicated apply-patch routing resource change.
  - Scenario: repeat `DpaiaPetclinicRest37Test.claude with mcp` first because it is the stable measured case.
  - Target: keep 184/184 tests, 0 native Edit, `steroid_apply_patch` used, 0 tool errors, and no regression versus the 101s run.
  - Run dir: `test-experiments/build/test-logs/test/run-20260427-073953-dpaia__spring__petclinic__rest-37-mcp`
  - Result: fix claimed, MCP used, 184/184 tests passed, agent time 116s.
  - Tool mix from `docs/autoresearch/dpaia/metrics.py`: 10 total calls, 3 MCP calls, 7 native calls, 2 `steroid_execute_code`, 1 `steroid_apply_patch`, 2 Read, 1 Grep, 0 native Edit, 2 Bash, 0 errors.
  - Delta versus the 101s run: `steroid_apply_patch` stayed true, native Edit stayed 0, Bash stayed 2, total calls 11 -> 10, runtime 101s -> 116s. This is within the observed PetclinicRest37 variance and confirms the global prompt-resource routing is not regressing the stable Maven case.

- [x] Add a prompt-size or prompt-shape regression check for the DPAIA arena MCP block.
  - Files likely under `test-experiments/src/test/kotlin/.../arena/`.
  - Expected effect: prevent reintroducing large contradictory prompt blocks.
  - Consensus note: Codex specifically recommended asserting that the prompt does not reintroduce broad "run at most once" wording and still contains explicit rerun-required cases.
  - Measured prerequisite: the verification-guidance tweak succeeded on the 101s run, so this is now the next low-hanging fruit.
  - Implemented in `ArenaPromptContractTest`; validation: `./gradlew :test-experiments:test --tests '*ArenaPromptContractTest*' --warning-mode all` passed.

- [x] Pick and measure one Gradle DPAIA scenario before changing Gradle guidance.
  - Goal: establish a concrete baseline for Gradle cold-starts, skipped-test behavior, and IntelliJ Gradle runner errors before editing prompt resources.
  - Scenario: `DpaiaMicroshop2Test.claude with mcp`.
  - Run dir: `test-experiments/build/test-logs/test/run-20260427-090258-dpaia__spring__boot__microshop-2-mcp`.
  - Result: fix claimed, MCP used, full Gradle suite passed, agent time 171s.
  - Raw metrics: 12 total calls, 4 MCP calls, 3 `steroid_execute_code`, 1 `steroid_apply_patch`, 8 patch hunks, 0 native Edit, 0 Read, 3 Write for new files, 4 Bash, 0 tool errors, 1.05M tokens.
  - Delta versus the stale-disk failure run: 248s -> 171s, tool errors 7 -> 0, native Edit 14 -> 0, Read 11 -> 0. The "apply_patch did not persist" fallback disappeared.

- [ ] Add a Gradle-focused MCP prompt resource modeled after the Maven patterns.
  - Files likely under `prompts/src/main/prompts/skill/`.
  - Consensus status: Gemini recommended this; Microshop-2 measurement now gives a concrete baseline.
  - Expected effect: fewer Bash Gradle cold starts and fewer hand-rolled IntelliJ Gradle snippets.

- [ ] Next low-hanging Gradle improvement: make JDK choice harder to miss in DPAIA prompts.
  - Evidence: Microshop-2 eventually chose JDK 25 and passed, but first tried `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-arm64` and hit `invalid source release: 24`.
  - Candidate fix: have the arena prompt emit a concrete `JAVA_HOME=/usr/lib/jvm/temurin-25-...` line from the prewarm/case config, not just a generic "pick the lowest JDK >= N" rule.
  - Target: remove the wasted Java 21 Bash call while preserving 0 native Edit and 0 tool errors.

- [ ] Investigate severe Kotlin resolve logs from the IntelliJ monorepo `thisLogger` lookup.
  - Evidence: the green lookup run logged `KaFirReferenceResolver` / `Expected FirResolvedContractDescription but FirLazyContractDescriptionImpl` errors, followed by an `ExceptionCaptureService` null-pointer while capturing the IDE error.
  - Target: keep the test real but remove or isolate IDE-side severe errors instead of treating them as harmless noise.

- [ ] Make IntelliJ monorepo test setup prefer an explicitly configured local checkout when appropriate.
  - Evidence: the `MCP_STEROID_INTELLIJ_CHECKOUT_DIR=/Users/jonnyzzz/Work/intellij` run still reused an existing cached TeamCity ZIP before updating the checkout in-container.
  - Candidate fix: review `ensureIntelliJGitCloneZipInCache()` precedence and add unit coverage before changing cache behavior.

## Recent PR Follow-up

- [ ] PR #26 needs fixes before merge.
  - `npx/build.gradle.kts`: include `package-lock.json` and likely `tsconfig.json` as `npmBuild` inputs.
  - `ocr-tesseract/build.gradle.kts`: make tessdata download skipping version-aware.

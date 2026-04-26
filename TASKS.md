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

## Next Candidates

- [ ] Reduce redundant Maven verification in the DPAIA arena prompt.
  - Evidence: the latest successful run compiled with `steroid_execute_code`, then ran targeted Maven tests, then ran the full Maven suite. That kept correctness but increased Bash calls from 2 to 3 and runtime from 111s to 123s.
  - Implemented wording: do not rerun a completed Maven/Gradle target solely to recover `BUILD SUCCESS` hidden by `tail`/`grep`; explicitly allow reruns after code changes, real failures, incomplete runs, or Gradle skipped-test behavior.
  - Review: first 3-agent pass requested narrowing the wording; follow-up pass under `/tmp/mcp-steroid-review/runs-current-4/` approved with Claude/Codex/Gemini consensus.
  - Measurement: `DpaiaPetclinicRest37Test.claude with mcp` run `test-experiments/build/test-logs/test/run-20260427-003310-dpaia__spring__petclinic__rest-37-mcp` passed 184/184 tests in 101s with 0 native Edit, 1 `steroid_apply_patch`, 2 Bash, and 0 tool errors.
  - Delta versus the prior 123s run: Bash 3 -> 2, total tool calls 12 -> 11, runtime 123s -> 101s, pass rate unchanged.

- [x] Add a prompt-size or prompt-shape regression check for the DPAIA arena MCP block.
  - Files likely under `test-experiments/src/test/kotlin/.../arena/`.
  - Expected effect: prevent reintroducing large contradictory prompt blocks.
  - Consensus note: Codex specifically recommended asserting that the prompt does not reintroduce broad "run at most once" wording and still contains explicit rerun-required cases.
  - Measured prerequisite: the verification-guidance tweak succeeded on the 101s run, so this is now the next low-hanging fruit.
  - Implemented in `ArenaPromptContractTest`; validation: `./gradlew :test-experiments:test --tests '*ArenaPromptContractTest*' --warning-mode all` passed.

- [ ] Add a Gradle-focused MCP prompt resource modeled after the Maven patterns.
  - Files likely under `prompts/src/main/prompts/skill/`.
  - Consensus status: Gemini recommended this; it is now the next candidate after the measured arena prompt wins and regression coverage.
  - Expected effect: fewer Bash Gradle cold starts and fewer hand-rolled IntelliJ Gradle snippets.

## Recent PR Follow-up

- [ ] PR #26 needs fixes before merge.
  - `npx/build.gradle.kts`: include `package-lock.json` and likely `tsconfig.json` as `npmBuild` inputs.
  - `ocr-tesseract/build.gradle.kts`: make tessdata download skipping version-aware.

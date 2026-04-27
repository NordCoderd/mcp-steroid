# MEMORY

## 2026-04-26 - Current DPAIA Direction

- Long-term goal: MCP Steroid should help agents complete DPAIA Maven and Gradle tasks with fewer tokens, fewer tool errors, and lower runtime than vanilla runs.
- Hard constraints from the user: do not add methods to `McpSteroid*` interfaces and do not add MCP tools. Improve server behavior, prompt resources, and DPAIA case quality instead.
- External methodology anchors:
  - `https://github.com/karpathy/autoresearch`: narrow autonomous experiment loop; modify the "research org code" / prompt, run a fixed benchmark, keep or revert based on measured improvement.
  - `https://jonnyzzz.com/RLM.md`: keep context outside the model where possible; assess, grep, partition, execute, synthesize, verify.
  - `https://run-agent.sh`: use traceable role-specific agent runs with persisted prompts/stdout/stderr and consensus for non-trivial direction changes.
- Current run-agent review artifacts are under `/tmp/mcp-steroid-review/runs/`.

## 2026-04-26 - Three-Agent Review Consensus

- Valid completed reviewers:
  - Claude: `/tmp/mcp-steroid-review/runs/run_20260426-201025-47047`
  - Codex: `/tmp/mcp-steroid-review/runs/run_20260426-201025-47048`
  - Gemini with provided keys: `/tmp/mcp-steroid-review/runs/run_20260426-201242-49168`
- Failed/redundant runs:
  - First Gemini start failed because `GEMINI_API_KEY` was absent.
  - Extra fallback Claude was stopped after consensus because it produced no output and was no longer needed.
- Consensus:
  - Claude and Codex both selected `ArenaTestRunner.buildPrompt()` MCP prompt cleanup as the lowest-risk next step.
  - Gemini selected a Gradle-focused prompt resource as useful follow-up.
  - Decision: do the arena prompt cleanup first, then consider Gradle resource work.

## 2026-04-26 - Recent GitHub Comment Context

- PR #26, "Improving speed for iterative/sequential tests running (~x10 performance)", is open with `CHANGES_REQUESTED`.
- Review concerns:
  - `npmBuild` declares too few inputs and can leave stale `dist/index.js`.
  - tessdata skip logic ignores `tessdataVersion` and can reuse old files after a version bump.
- This PR is related to iterative test speed, but it is not the next DPAIA prompt improvement.

## DPAIA Lessons To Preserve

- Arena prompt recipes are high-impact because agents follow them directly.
- MCP resources are low-impact unless explicitly requested; prior runs showed agents rarely read them spontaneously.
- Contradictory edit guidance is dangerous. The dedicated `steroid_apply_patch` path is the fast path for multi-site edits; the old `applyPatch {}` DSL inside `steroid_execute_code` still works but has Kotlin compilation overhead and should not be the recommended arena path.
- Do not optimize by adding DSL/interface surface. Prefer clearer routing, shorter prompts, and measured scenario runs.

## 2026-04-26 - Measured Prompt Cleanup Run

- Scenario: `DpaiaPetclinicRest37Test.claude with mcp`.
- Command: `./gradlew :test-experiments:test --tests '*DpaiaPetclinicRest37Test.claude with mcp' --rerun-tasks --warning-mode all`.
- Result: agent fixed the task in 111s, used MCP, and passed 184/184 Maven tests.
- Raw NDJSON metrics from `docs/autoresearch/dpaia/metrics.py`: 13 total tool calls, 2 agent `steroid_execute_code` calls, 11 native calls, 2 native Edit calls, 2 Bash calls, no tool errors, no `steroid_apply_patch`.
- Harness decoded summary reported 4 `exec_code` calls because it includes extra decoded lines around the run; prefer the raw metrics script for agent-only tool mix.
- Follow-up chosen from evidence: strengthen `steroid_apply_patch` prompt schema/wording because the agent used native Edit for an import+method change.

## 2026-04-26 - Current Diff Review Consensus

- Review artifacts:
  - Initial block review: `/tmp/mcp-steroid-review/runs-current/`.
  - Final approve review: `/tmp/mcp-steroid-review/runs-current-2/`.
- Claude, Codex, and Gemini all caught the same blocker in the first pass: the arena prompt used `path` while the current repo's `ApplyPatchToolHandler` schema requires `file_path`.
- After correcting the prompt and `TASKS.md`, all three approved.
- Consensus next step: re-run `DpaiaPetclinicRest37Test.claude with mcp` and compare native Edit count against the 2026-04-26 baseline of 2.

## 2026-04-26 - Corrected Apply-Patch Prompt Measurement

- Scenario: `DpaiaPetclinicRest37Test.claude with mcp`.
- Run dir: `test-experiments/build/test-logs/test/run-20260427-001705-dpaia__spring__petclinic__rest-37-mcp`.
- Result: agent fixed the task in 123s, used MCP, and passed 184/184 Maven tests.
- Arena summary JSON: `agent_duration_ms=123813`, `exec_code_calls=2`, `read_calls=2`, `edit_calls=0`, `write_calls=0`, `bash_calls=3`, `glob_calls=0`, `grep_calls=2`.
- Raw NDJSON metrics: 12 total calls, 3 MCP Steroid calls, 9 native calls, 1 `mcp__mcp-steroid__steroid_apply_patch`, 2 `mcp__mcp-steroid__steroid_execute_code`, 0 tool errors.
- Delta from the prior run: native Edit 2 -> 0, apply-patch false -> true, total tool calls 13 -> 12, errors stayed 0. Runtime regressed 111s -> 123s because Claude made 3 Bash Maven verification calls after the IDE compile check.
- Follow-up chosen from evidence: tighten verification guidance so a successful IDE build plus targeted Maven test does not routinely trigger duplicate Maven runs. Keep Gradle prompt resources as the larger follow-up.
- Implemented next low-hanging prompt tweak in `ArenaTestRunner.buildPrompt()`: do not rerun a completed Maven/Gradle target solely because `tail`/`grep` hid the `BUILD SUCCESS` summary; reruns after code changes, real failures, incomplete runs, or Gradle skipped tests remain required. Next measurement should check Bash count <=2 while preserving 184/184 tests.

## 2026-04-26 - Verification Prompt Review Consensus

- Review artifacts:
  - Initial verification wording review: `/tmp/mcp-steroid-review/runs-current-3/`.
  - Follow-up approve review: `/tmp/mcp-steroid-review/runs-current-4/`.
- First pass: Codex requested changes and Claude flagged the same issue as a nit. The phrase "Run each Maven/Gradle verification target at most once" was too broad and could discourage legitimate reruns after fixes, incomplete runs, or Gradle skipped tests.
- Fix applied: narrow the instruction to completed runs where `tail`/`grep` merely hid `BUILD SUCCESS`; explicitly preserve reruns after code changes, real failures, incomplete runs, or Gradle skipped-test behavior.
- Follow-up pass: Claude, Codex, and Gemini all approved.
- Next low-hanging fruit from consensus: measure this prompt on `DpaiaPetclinicRest37Test.claude with mcp`; if it behaves well, add a prompt regression test so the broad wording and contradictory edit guidance do not reappear.

## 2026-04-26 - Verification Prompt Measurement

- Scenario: `DpaiaPetclinicRest37Test.claude with mcp`.
- Run dir: `test-experiments/build/test-logs/test/run-20260427-003310-dpaia__spring__petclinic__rest-37-mcp`.
- Result: agent fixed the task in 101s, used MCP, and passed 184/184 Maven tests.
- Arena summary JSON: `agent_duration_ms=101319`, `exec_code_calls=3`, `read_calls=2`, `edit_calls=0`, `write_calls=0`, `bash_calls=2`, `glob_calls=0`, `grep_calls=2`.
- Raw NDJSON metrics: 11 total calls, 3 MCP Steroid calls, 8 native calls, 1 `mcp__mcp-steroid__steroid_apply_patch`, 2 `mcp__mcp-steroid__steroid_execute_code`, 2 Bash, 0 tool errors.
- Delta from the prior 123s run: Bash 3 -> 2, total tool calls 12 -> 11, runtime 123s -> 101s, native Edit stayed 0, apply-patch stayed true, pass rate stayed 184/184.
- Next low-hanging fruit: add a prompt regression test for the DPAIA arena MCP block, especially preventing broad "run at most once" wording and ensuring `steroid_apply_patch` uses `file_path`.

## 2026-04-26 - Arena Prompt Regression Test

- Added `ArenaPromptContractTest` in `test-experiments`.
- It builds the MCP arena prompt without starting Docker and asserts:
  - `steroid_apply_patch` is present.
  - Apply-patch examples use `"file_path"`, not `"path"`.
  - The older `applyPatch {}` DSL is mentioned only as the path to avoid.
  - Broad "Run each Maven/Gradle verification target at most once" wording is absent.
  - Legitimate rerun cases remain explicit.
  - Full-suite success remains required before `ARENA_FIX_APPLIED: yes`.
- Validation: `./gradlew :test-experiments:test --tests '*ArenaPromptContractTest*' --warning-mode all` passed.
- Next candidate: Gradle-focused MCP prompt/resource work, but measure one Gradle scenario first or add a similarly narrow prompt contract before broad resource changes.

## 2026-04-27 - Next-Step Review: Apply-Patch Routing First

- Review artifacts: `/tmp/mcp-steroid-review/runs-next-20260427/`.
- Claude, Codex, and Gemini all selected `update-apply-patch-tool-description-routing` as the next low-hanging fruit.
- Rationale: `ArenaTestRunner.buildPrompt()` already routes multi-site edits to the dedicated `steroid_apply_patch` tool, but the global `execute-code-tool-description.md` still taught the slower `steroid_execute_code` + script-context `applyPatch` DSL as the default. This contradiction affects every MCP session before a Gradle-specific resource is read.
- Implemented resource changes:
  - `prompts/src/main/prompts/skill/execute-code-tool-description.md` now recommends `steroid_apply_patch` for 2+ literal edit sites and keeps the script-context DSL as a fallback only when the patch must run inside the same `steroid_execute_code` script.
  - `prompts/src/main/prompts/skill/execute-code-overview.md` and `prompts/src/main/prompts/skill/coding-with-intellij.md` now point multi-site literal edits at `steroid_apply_patch`.
  - `prompts/src/main/prompts/ide/apply-patch.md` now frames the DSL as the lower-level fallback and links the dedicated tool description.
  - `PromptRoutingContractTest` guards the global execute-code tool description against routing ordinary multi-site edits back through `steroid_execute_code`.
- Validation: scoped `:prompts:test` selection passed via IntelliJ Gradle runner:
  `*PromptRoutingContractTest*`, `*MarkdownArticleContractTest*`, and `*ExecuteCodeToolDescriptionKtBlocksCompilationTest*` with `--warning-mode all`.
- Next measurement target: repeat `DpaiaPetclinicRest37Test.claude with mcp`; target 184/184 tests, 0 native Edit, `steroid_apply_patch` used, 0 tool errors, and no regression versus the 101s run.

## 2026-04-27 - Apply-Patch Routing Measurement

- Scenario: `DpaiaPetclinicRest37Test.claude with mcp`.
- Run dir: `test-experiments/build/test-logs/test/run-20260427-073953-dpaia__spring__petclinic__rest-37-mcp`.
- Command: `ANTHROPIC_API_KEY=$(cat ~/.anthropic) GEMINI_API_KEY=$(cat ~/.vertex) OPENAI_API_KEY=$(cat ~/.openai) ./gradlew :test-experiments:test --tests '*DpaiaPetclinicRest37Test.claude with mcp' --rerun-tasks --warning-mode all`.
- Result: agent fixed the task in 116s, used MCP, and passed 184/184 Maven tests.
- Arena summary JSON: `agent_duration_ms=116000`, `exec_code_calls=2`, `read_calls=2`, `edit_calls=0`, `write_calls=0`, `bash_calls=2`, `glob_calls=0`, `grep_calls=1`.
- Raw NDJSON metrics: 10 total calls, 3 MCP Steroid calls, 7 native calls, 1 `mcp__mcp-steroid__steroid_apply_patch`, 2 `mcp__mcp-steroid__steroid_execute_code`, 2 Bash, 0 tool errors.
- Delta from the prior 101s run: native Edit stayed 0, `steroid_apply_patch` stayed true, Bash stayed 2, total tool calls improved 11 -> 10, and runtime moved 101s -> 116s. This is acceptable PetclinicRest37 variance and does not show a prompt-routing regression.
- Next low-hanging fruit: pick and measure one Gradle DPAIA scenario before changing Gradle guidance, then add or tighten a Gradle-focused MCP prompt resource based on observed failures.

# TODO DPAIA

- [ ] Keep `TASKS.md` as the active DPAIA/autoresearch task list.
- [ ] Keep `MEMORY.md` as the short factual handoff for the current DPAIA optimization thread.
- [ ] After each measured DPAIA iteration, record the hypothesis, changed files, validation command, and metric delta.
- [x] Measure the corrected `steroid_apply_patch` prompt on `DpaiaPetclinicRest37Test.claude with mcp` and compare native Edit count against the 2026-04-26 baseline of 2.
- [x] Tighten DPAIA verification guidance to reduce duplicate Maven/Bash runs while preserving 184/184 pass behavior.
- [x] Measure the DPAIA verification-guidance tweak on `DpaiaPetclinicRest37Test.claude with mcp`; target Bash <=2, Edit 0, apply_patch true, 184/184 tests.
- [x] Add a prompt regression test for the DPAIA arena MCP block after the verification-guidance measurement.
- [x] Run 3-agent review for the next low-hanging fruit after arena prompt regression; consensus is to fix global apply-patch prompt-resource routing before Gradle-resource work.
- [x] Measure the dedicated apply-patch routing resource change on `DpaiaPetclinicRest37Test.claude with mcp`.
- [x] Add disk-persistence integration tests for `steroid_apply_patch` success/failure cases.
- [x] Pick the next Gradle DPAIA scenario and measure it before changing Gradle guidance.
- [x] Add a real IntelliJ Ultimate monorepo `thisLogger` lookup regression test using `Observation.awaitConfiguration` plus `smartReadAction`.
- [x] Update MCP server/resource indexing guidance to use `Observation.awaitConfiguration(project)` plus `smartReadAction { }` instead of treating `waitForSmartMode()` as a stable handoff.
- [ ] Tighten Gradle/JDK prompt guidance so DPAIA agents use the configured JDK path before the first Bash Gradle call.
- [x] Fix the `ExceptionCaptureService` null-parameters crash observed during the green IntelliJ monorepo `thisLogger` lookup.
- [x] Review IntelliJ checkout ZIP/cache precedence so `MCP_STEROID_INTELLIJ_CHECKOUT_DIR` does not silently lose to an older cached TeamCity ZIP when a local checkout was explicitly configured.
- [ ] Investigate the remaining severe Kotlin FIR resolve logs observed during the green IntelliJ monorepo `thisLogger` lookup.

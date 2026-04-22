# Autoresearch iteration summary

Running tally of Claude's self-eval report across prompt-tuning iterations.

Metric legend:
- **(a)** tasks where MCP Steroid adds capability (§1 bullet count)
- **(b)** tasks where MCP Steroid applies but offers no clear improvement (§1 bullet count)
- **ratio**: §4 token payload ratio for small 1-3 line edits, built-in vs MCP Steroid
- **verdict**: count of recognizable `**Verdict**` lines
- **wall**: testSerenaSelfEvalPrompt wall time (mm:ss)

| iter | commit   | (a) | (b) | ratio     | verdict | wall   | edit                                                               |
|------|----------|-----|-----|-----------|---------|--------|--------------------------------------------------------------------|
| 00   | 534c008c | 6   | 3   | 4x        | 32      | 20:31  | baseline                                                           |
| 01   | ac8a0182 | 5   | ~2  | —         | 24      | 12:58  | route small edits to built-ins (superseded at 03')                 |
| 02   | ed24e475 | 5   | 2   | —         | 21      | 12:39  | semantic RenameProcessor in lsp/rename.md                          |
| 03   | reverted | —   | —   | —         | —       | —      | mcp-steroid-info.md scope split (reverted 209097e0)                |
| 03'  | f0ff0af1 | 5   | 3   | —         | 28      | 22:32  | compact VfsUtil.saveText in coding-with-intellij.md Quick Ref      |
| —    | b37d173d | —   | —   | —         | —       | —      | fix: rename recipe readAction + writeIntentReadAction              |
| 04   | 17de0af9 | 5   | 3   | 4x        | 31      | 16:30  | correct auto-imports doc in execute-code-overview.md               |
| 05   | beeccdc4 | 4   | 2   | 1.5-2.5x  | 21      | 13:41* | compact in-place edit recipe in tool description                   |
| 06   | 41b66090 | 4   | 2   | —         | 24      | 19:36  | preventive threading-rules card in tool description                |
| 07   | d53c6ca0 | **8** | 3 | ~5x      | 24      | 12:04  | cement always-IDE-for-edits policy in mcp-steroid-info             |
| 08   | e75cb2b0 | 6   | 2   | parity    | 23      | 23:19  | payload-accounting clarification (reads/writes IDE-side only)      |
| 09   | (next)   | —   | —   | —         | —       | —      | quantified batch-multi-edit recipe in tool description             |

*iter-05 build failed in post-test teardown race; report reached sys-out cleanly.

Key trend signals:
- **§1 (a) count**: 6 → 5 → 5 → 5 → 4 → 4 → 8 → 6. Stable around 5-6 with a
  one-iteration spike at iter-07 when agent pulled inspections / quick-fixes /
  signature help / run configs / debugger up from §7 into §1 scope.
- **§1 (b) count**: 3 → 2 → stayed at 2–3 band. Two residual items are (i)
  small 1-3 line edits — structural Kotlin floor that Edit(old,new) skips;
  (ii) free-text search — explicitly outside MCP Steroid's scope.
- **§5 limitations**: converged to "fixture/configuration only" after iter-06
  (no API learning-curve / threading complaints since).
- **Token ratio** for small edits: 4× → 1.5-2.5× → parity (iter-08 after the
  payload-accounting clarification).
- **Autonomous capability discovery**: iter-06 agent surfaced
  `runInspectionsDirectly()` without prompting; iter-07 promoted 4 capabilities
  from §7 into §1 (a).
- **Validation**: :prompts:test + :ij-plugin:test green on every iteration
  (one mid-loop fix at b37d173d for iter-02 rename-recipe threading regression).

iter-09 is the final iteration; apply-patch recipe (task #4) remains parked as
the long-horizon structural solution to residual small-edit parity.

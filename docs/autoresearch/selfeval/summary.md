# Autoresearch iteration summary

Running tally of Claude's self-eval report across prompt-tuning iterations.

Metric legend:
- **(a)** tasks where MCP Steroid adds capability (§1 bullet count)
- **(b)** tasks where MCP Steroid applies but offers no clear improvement (§1 bullet count)
- **ratio** (§4): token payload ratio for small 1-3 line edits, built-in vs MCP Steroid
- **verdict**: count of recognizable `**Verdict**` lines
- **wall**: testSerenaSelfEvalPrompt wall time (mm:ss)

| iter | commit   | (a) | (b) | ratio  | verdict | wall  | edit                                                             |
|------|----------|-----|-----|--------|---------|-------|------------------------------------------------------------------|
| 00   | 534c008c | 6   | 3   | 4x     | 32      | 20:31 | baseline                                                         |
| 01   | ac8a0182 | 5   | ~2  | —      | 24      | 12:58 | route small edits to built-ins (superseded at 03')               |
| 02   | ed24e475 | 5   | 2   | —      | 21      | 12:39 | semantic RenameProcessor in lsp/rename.md                        |
| 03   | reverted | —   | —   | —      | —       | —     | mcp-steroid-info.md scope split (reverted 209097e0)              |
| 03'  | f0ff0af1 | 5   | 3   | —      | 28      | 22:32 | compact VfsUtil.saveText in coding-with-intellij.md Quick Ref    |
| —    | b37d173d | —   | —   | —      | —       | —     | fix: rename recipe readAction + writeIntentReadAction            |
| 04   | 17de0af9 | 5   | 3   | 4x     | 31      | 16:30 | correct auto-imports doc in execute-code-overview.md             |
| 05   | beeccdc4 | 4   | 2   | 1.5-2.5x | 21    | 13:41 | compact in-place edit recipe in tool description                 |
| 06   | (next)   | —   | —   | —      | —       | —     | preventive threading-rules card in tool description              |

Notes on iter-05:
- BUILD FAILED in teardown ("Context has been disposed"); the agent's report
  reached sys-out cleanly — infrastructure race, not an assertion failure.
  Treating iter-05 as signal-valid for planning iter-06.
- §1 (b) shrank to 2 items (only "small edits" + "free-text search" — the latter
  is outside MCP Steroid's intentional scope).
- §4 token-efficiency: small-edit gap narrowed from 4x to 1.5-2.5x.

Still-open (b): small edits and free-text search. apply-patch recipe (task #4)
targets the small-edit one; free-text search is outside MCP Steroid's scope by
design (cf. §8 "Tasks outside MCP Steroid's scope (built-in only)").

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
| 05   | beeccdc4 | 4   | 2   | 1.5-2.5x | 21    | 13:41* | compact in-place edit recipe in tool description                 |
| 06   | 41b66090 | 4   | 2   | —      | 24      | 19:36 | preventive threading-rules card in tool description              |
| 07   | d53c6ca0 | **8** | 3 | ~5x  | 24      | 12:04 | cement always-IDE-for-edits policy in mcp-steroid-info           |
| 08   | (next)   | —   | —   | —      | —       | —     | payload-accounting clarification for recipe reads/writes         |

*iter-05 build failed in post-test teardown race; report reached sys-out cleanly.

Key trend signals:
- **§1 (a) items expanded iter-07**: 4 → 8, agent enumerated inspections,
  quick-fixes, signature help, run configs, debugger as distinct §1 items
  (previously confined to §7 unique capabilities).
- **Stuck small-edit verdict**: iter-07 agent double-counted `saveText(vf, updated)`
  file-content as MCP tool payload (it's IDE-side, never crosses boundary).
  iter-08 targets this misreading directly.
- **§5 limitations stable at fixture-only**: no API-learning-curve complaints
  since iter-06.
- **Validation**: :prompts:test + :ij-plugin:test green through iter-07.

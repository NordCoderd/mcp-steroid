# Autoresearch iteration summary

Running tally of Claude's self-eval report across prompt-tuning iterations.

Metric legend:
- **(a)** tasks where MCP Steroid adds capability (§1 bullet count)
- **(b)** tasks where MCP Steroid applies but offers no clear improvement (§1 bullet count)
- **verdict**: count of recognizable `**Verdict**` lines the agent emitted
- **wall**: wall-clock of the `testSerenaSelfEvalPrompt` run (mm:ss)

| iter | commit   | (a) | (b) | verdict | wall  | edit made this round                                             |
|------|----------|-----|-----|---------|-------|------------------------------------------------------------------|
| 00   | 534c008c | 6   | 3   | 32      | 20:31 | — baseline                                                       |
| 01   | ac8a0182 | 5   | ~2  | 24      | 12:58 | route 1–3 line edits + single-file-private-rename to built-ins (superseded at 03') |
| 02   | ed24e475 | 5   | 2   | 21      | 12:39 | replace lsp/rename.md regex recipe with semantic RenameProcessor |
| 03   | **reverted** 209097e0 | — | — | — | — | mcp-steroid-info.md scope split — policy reversal |
| 03'  | f0ff0af1 | 5   | 3   | 28      | 22:32 | remove iter-01 Edit-routing rows; promote VfsUtil.saveText compact recipe |
| —    | b37d173d | — | — | — | — | fix: lsp/rename — readAction analysis; writeIntentReadAction; test heading |
| 04   | (next)   | —   | —   | —       | —     | correct "NO AUTO-IMPORTS" claim; split into auto-imported vs explicit lists |

Notes:
- iter-03 (ba1dbaf3) reverted; policy: MCP Steroid stays the default for all edits.
  Long-term fix tracked as parked task #4 (apply-patch recipe).
- iter-02 rename recipe had two runtime bugs caught by :ij-plugin:test regression run
  (readAction scope + writeAction vs writeIntentReadAction). Fixed at b37d173d.
- KtBlocks green; LspExamplesExecutionTest.testRenameExampleExecutes green after fix.
- Test verdict-regex loosened once at iter-01 start; no further assertion edits.

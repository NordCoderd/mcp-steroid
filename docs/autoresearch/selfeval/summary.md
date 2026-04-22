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
| 01   | ac8a0182 | 5   | ~2  | 24      | 12:58 | route 1–3 line edits + single-file-private-rename to built-ins  |
| 02   | (next)   | —   | —   | —       | —     | replace lsp/rename.md regex recipe with semantic RenameProcessor |

Notes:
- iter-01 §3.9 flagged a real defect in `lsp/rename.md` (regex-based, not semantic).
  iter-02 fixes it; expect §3.10 cross-file rename verdict to strengthen.
- Test assertion loosened once at iter-01 start (accept `**Verdict (3.1):**` etc.).

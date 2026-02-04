# Website Copy Review

**Date:** 2026-02-04
**Reviewer:** Gemini CLI
**Files Reviewed:**
- `layouts/index.html`
- `hugo.toml`
**Reference:** `prompt-update-website.md` (proxy for `WEBSITE-COPY-FINAL.md`)

## Summary
The homepage copy closely aligns with the provided instructions and messaging guidelines. The value proposition is clear, and the problem/solution structure is effectively implemented. A few minor technical and phrasing adjustments are recommended.

## Alignment Check

| Section | Status | Notes |
| :--- | :--- | :--- |
| **Headline** | ✅ Aligned | "Give AI the whole IDE, not just the files." |
| **Tagline** | ✅ Aligned | Matches the required description exactly. |
| **Proof Strip** | ✅ Aligned | "9 MCP tools - 58 MCP resources - Human review by default" |
| **CTAs** | ✅ Aligned | "Get Early Access" and "Watch Demos" present. |
| **Problem/Solution** | ✅ Aligned | Added as requested. |
| **ASCII Compliance** | ⚠️ Mostly | CSS `content: "\2192"` (→) used for bullets. Inline styles found. |

## Findings & Suggestions

### 1. Claims & Phrasing
- **"Any AI agent" (Tagline):** 
  - *Current:* "...to any AI agent via Kotlin code execution..."
  - *Observation:* This implies *all* AI agents, but strictly speaking, they must be MCP-compliant. 
  - *Suggestion:* Consider changing to "any MCP-compliant AI agent" or "your AI agent".
- **"Supported all IntelliJ-based IDEs" (What's New):**
  - *Current:* "Supported all IntelliJ-based IDEs..."
  - *Observation:* "All" is a strong claim (e.g., AppCode is discontinued, some obscure ones might fail).
  - *Suggestion:* Soften to "major IntelliJ-based IDEs" or just "IntelliJ-based IDEs".

### 2. Technical & Formatting
- **Inline CSS in "Learn More":**
  - *Location:* `layouts/index.html` (Lines 237, 238, 239)
  - *Issue:* `<a ... style="color: #a0a0ff; text-decoration: none;">` uses inline styles.
  - *Suggestion:* Move these styles to a CSS class (e.g., `.learn-more-link`) in the `{{ define "styles" }}` block.
- **Unicode in CSS:**
  - *Location:* `layouts/index.html` (Line 104)
  - *Issue:* `content: "\2192";` (Right Arrow).
  - *Note:* While technically not ASCII *text*, it renders a special character. If strict ASCII compliance is for compatibility, this is likely fine, but worth noting given the "no special bullets" instruction.
- **Link Target Security:**
  - *Location:* All external links (`target="_blank"`)
  - *Status:* Correctly use `rel="noopener"`. (Good job).

### 3. Consistency
- **Hugo Config:**
  - `hugo.toml` `description` matches the homepage tagline exactly.
  - `whatsnew` entries in `hugo.toml` are consistent with the "What's New" section structure.

## Action Plan
1.  **Refine Tagline:** Clarify "any AI agent" to "any MCP client" or similar if precision is desired.
2.  **Refactor CSS:** Move inline styles in the "Learn More" section to the `<style>` block.
3.  **Review "All IDEs" Claim:** Verify if "all" is factually true or if it should be qualified.

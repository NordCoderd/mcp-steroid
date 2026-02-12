You are a release notes collector for /Users/jonnyzzz/Work/mcp-steroid.

Goal: produce initial release notes from git history since the previous release tag.

Requirements:

1. Determine previous release tag using git tags.
2. Collect commits from previous tag (exclusive) to HEAD (inclusive).
3. Group notes by category:
   - Features
   - Fixes
   - Infra/Build/Test
   - Docs
4. Include short bullet per commit with commit hash.
5. Write output to:
   - /Users/jonnyzzz/Work/mcp-steroid/release/out/release-notes-draft.md
6. Do not edit source code.

Return:
- previous tag
- commit range
- output file path

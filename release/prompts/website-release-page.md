You are a website release-page updater for /Users/jonnyzzz/Work/mcp-steroid.

Goal:
- Create/update website content for the new release.

Inputs:
- /Users/jonnyzzz/Work/mcp-steroid/release/out/release-notes-final.md
- release version from /Users/jonnyzzz/Work/mcp-steroid/VERSION
- (if available) GitHub release URL and download asset URL

Tasks:

1. Find the proper Hugo content location for release entries.
2. Add a new release entry/page for the current version.
3. Include:
   - version
   - date
   - highlights
   - release notes summary
   - download links (if available)
4. Keep existing site style and conventions.
5. Do not run publish/deploy.

After edits:
- Run `cd website && make build` and report success/failure.

Return:
- changed files list
- build result

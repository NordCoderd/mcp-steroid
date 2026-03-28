## Release Automation Workspace

This directory contains release orchestration assets for `mcp-steroid`:

- `release-instructions.md` — primary prompt/instructions for a release worker agent.
- `TODO-release.md` — release backlog and completion checklist.
- `docker/Dockerfile` — reproducible builder image used for release builds/tests.
- `scripts/` — helper scripts for dry-run orchestration, version bump, and Dockerized build matrix execution.
- `prompts/` — prompt templates for release notes and website update agent runs.
- `notes/` — committed versioned release notes (`release/notes/<version>.md`) used by build/publish.
- `out/` — deterministic output directory for release artifacts.

### Quick Release (Shorter Path)

For a quick release without the full Docker build matrix, use Claude Code agents directly:

1. **Bump version**: Edit `VERSION` file, commit.
2. **Collect release notes**: Run `./run-agent.sh claude . /tmp/notes-prompt.md` (unset `CLAUDECODE` env var if running from within Claude Code).
3. **Build**: Run `./run-agent.sh claude . /tmp/build-prompt.md` — executes `./gradlew clean build buildPlugin -x :test-integration:test -Pmcp.release.build=true -Pmcp.release.notes.version=<version>`.
4. **Upload to GitHub**: `gh release create <version> release/out/<zip> --repo jonnyzzz/mcp-steroid --title "<version>" --notes-file /tmp/release-body.md`
5. **Update website**: Create release page at `website/website/content/releases/<version>.md`, update `website/website/hugo.toml` whatsnew entry, run `cd website/website && make build`. **Note:** `make build` downloads the release ZIP to extract the plugin version, so step 4 must complete first.
6. **Mark older releases obsolete**: `gh release edit <old-version> --repo jonnyzzz/mcp-steroid --notes-file <updated-body-with-obsolete-banner>`.
7. **Publish website**: Commit and push in `website/` (the public repo clone).

Steps 2+3 can run in parallel. Steps 5–7 require step 4 (GitHub release must exist for website build). The `CLAUDECODE` env var must be unset for nested Claude Code invocations via `run-agent.sh`.

### Full Release (Docker Matrix)

Default execution mode is dry-run (`release/scripts/run-release.sh --dry-run`), which:

- Skips version changes (`VERSION` file remains unchanged)
- Disables publishing (no GitHub release created)
- Enforces clean worktree (override with `--allow-dirty`)
- Still runs build/test matrix and release notes preparation

Release notes workflow:

- Notes are generated/reviewed into `release/notes/<version>.md`
- Notes format should match GitHub release body (user-friendly prose, not raw commit hashes)
- In non-dry-run, orchestration commits this file
- Build injects this file into plugin `change-notes` metadata (plugin.xml patch)

Stable plugin artifact path: `release/out/plugin-idea-2025.3.1.zip`

EAP build selection:

- `RELEASE_EAP_VERSION` defaults to `2026.1` (major request)
- The matrix resolves it to latest matching EAP build number before Stage 2/3 execution

Release build version format:

- `X.Y.Z-<gitHash>`
- No timestamp
- No `SNAPSHOT` marker

Publish stage available in non-dry-run mode with explicit `--publish` flag:

```bash
release/scripts/run-release.sh --no-dry-run --publish
```

Publish safety defaults:

- Uses tag `<VERSION>` (no `v` prefix) and targets the recorded version-bump commit SHA
- Refuses `--publish` together with `--skip-build` or `--skip-notes` unless `--allow-existing-artifacts` is passed
- Refuses to publish if the GitHub release tag already exists

Container builds use an isolated `.intellijPlatform` Docker volume to prevent host-OS IDE cache conflicts.

Builder container API key forwarding:

- Reuses host env vars when present: `OPENAI_API_KEY`, `CODEX_API_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, `GOOGLE_API_KEY`
- If missing, auto-loads from known local files and forwards as env:
  - `~/.openai` -> `OPENAI_API_KEY` (and `CODEX_API_KEY` fallback)
  - `~/.anthropic` -> `ANTHROPIC_API_KEY`
  - `~/.vertes` / `~/.vertex` -> `GEMINI_API_KEY` (and `GOOGLE_API_KEY` fallback)

### Website & Plugin Repository

The website build (`cd website/website && make build`) automatically generates:

- `version.json` — current version for in-IDE update checker
- `updatePlugins.xml` — IntelliJ custom plugin repository XML

The `updatePlugins.xml` generation pipeline:

1. Makefile queries `gh` for the release ZIP download URL (no fallback — build fails if `gh` unavailable or release not found)
2. `scripts/generate-update-plugins-xml.py` (run via `uv`) downloads the ZIP, extracts `ij-plugin-*.jar`, reads `META-INF/plugin.xml` to get the exact plugin version (e.g. `0.89.0-b8388824`) and `since-build`
3. Validates URL format (HTTPS, GitHub release pattern, version present) and generates XML with the artifact's actual version

This ensures `updatePlugins.xml` always matches the published artifact. Always points to the latest release only.

Requirements: `gh` CLI (authenticated), `uv` (Python runner).

Custom plugin repository URL: `https://mcp-steroid.jonnyzzz.com/updatePlugins.xml`

### Post-Release Checklist

- [ ] Older GitHub releases marked obsolete (prepend banner pointing to latest)
- [ ] Website release pages for older versions auto-show obsolete banner (handled by `layouts/releases/single.html`)
- [ ] Releases list page shows latest prominently, older releases in separate section
- [ ] `updatePlugins.xml` points to the new release (verified by `make build`)
- [ ] Website published (`cd website && git add -A && git commit && git push`)

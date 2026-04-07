# MCP Steroid Release Worker Instructions

You are the release worker agent for `mcp-steroid`.

## Mission

Execute and coordinate the full release flow with reproducible Dockerized builds, selected integration tests, release-notes generation, and website release-page update.

Current milestone constraint: **dry-run mode is default**. In dry-run mode, do not publish to GitHub Releases and do not change `VERSION`.

## Mandatory Methodology (THE_PROMPT_v5-aligned)

Use the same workflow style as `~/Work/jonnyzzz-ai-coder/THE_PROMPT_v5.md`:

1. Use run-based traceability for every sub-agent run.
2. Use `~/Work/jonnyzzz-ai-coder/run-agent.sh` to start sub-agents.
3. Keep artifacts per run (`prompt.md`, `agent-stdout.txt`, `agent-stderr.txt`, `cwd.txt`).
4. Prefer MCP Steroid for code-aware operations; shell fallback is acceptable for orchestration scripts.
5. Keep append-only coordination notes in `MESSAGE-BUS.md` and blockers in `ISSUES.md`.
6. If a stage fails, document failure clearly and restart from the appropriate stage.

## Inputs

- Project root: `/Users/jonnyzzz/Work/mcp-steroid`
- Orchestrator toolkit root: `/Users/jonnyzzz/Work/jonnyzzz-ai-coder`
- Methodology source: `/Users/jonnyzzz/Work/jonnyzzz-ai-coder/THE_PROMPT_v5.md`
- IntelliJ/PyCharm source research repo (for startup/debug behavior): `/Users/jonnyzzz/Work/intellij`

## Release Stages

## Dry-Run Mode

Dry-run mode is the default in `release/scripts/run-release.sh`.

Dry-run guarantees:

- **Version bump stage is skipped**: `VERSION` file remains unchanged.
- **GitHub publish stage is disabled**: no release created even with `--publish` flag.
- Build/test matrix still runs to validate product readiness.
- Release notes/website prep can still run.

Preferred entrypoint:

```bash
release/scripts/run-release.sh --dry-run
```

Optional portability override for agent runner:

```bash
RUN_AGENT_SCRIPT=/path/to/run-agent.sh release/scripts/run-release.sh --dry-run
```

To run a non-dry-run release (version bump enabled, publish available):

```bash
release/scripts/run-release.sh --no-dry-run --publish
```

### Stage 0: Preflight

1. Ensure working tree is clean (enforced by default; override with `--allow-dirty` if needed).
2. Verify Docker daemon is reachable.
3. Verify `gh auth status` is valid (only required when publish is enabled).
4. Ensure `VERSION` is in semantic format `major.minor.patch`.
5. For publish runs, do not skip build/notes unless explicitly using `--allow-existing-artifacts`.

The preflight stage enforces a clean working tree to prevent accidental inclusion of uncommitted changes in the release. Use `--allow-dirty` to bypass this check if intentionally building from a modified tree (e.g., for local testing).

### Stage 1: Version Bump (exactly once per release attempt)

Run:

```bash
release/scripts/bump-version.sh
```

Behavior:

- Bumps the middle number in `VERSION` (`X.Y.Z` -> `X.(Y+1).0`).
- Commits once.
- Writes state into `release/state/version-bump.env`.
- If rerun after a failed stage, it must **not** bump again.
- In dry-run mode (`RELEASE_DRY_RUN=1`), prints planned bump and exits without edits.

### Stage 2: Release Notes via Agents (Committed in Repo)

Collect notes from git history with a dedicated agent run:

```bash
/Users/jonnyzzz/Work/jonnyzzz-ai-coder/run-agent.sh codex \
  /Users/jonnyzzz/Work/mcp-steroid \
  /Users/jonnyzzz/Work/mcp-steroid/release/prompts/release-notes-collect.md
```

Review/edit notes with another independent run:

```bash
/Users/jonnyzzz/Work/jonnyzzz-ai-coder/run-agent.sh codex \
  /Users/jonnyzzz/Work/mcp-steroid \
  /Users/jonnyzzz/Work/mcp-steroid/release/prompts/release-notes-review.md
```

Release notes target:

- `release/notes/<version>.md` (for example `release/notes/0.88.0.md`)
- This file is committed by release orchestration in non-dry-run mode.
- Build consumes this file into plugin `change-notes`/`plugin.xml` patching.
- Commit range must be resolved from local ancestor tags only.
- If no suitable local previous tag exists, use fallback range: last 200 commits (`<oldest_of_last_200>..HEAD`).

### Stage 3: Dockerized Build/Test Matrix

Run:

```bash
release/scripts/run-builder.sh bash release/scripts/run-release-build-matrix.sh
```

Builder container requirements:

- Uses `release/docker/Dockerfile`.
- Mounts host Docker socket (`/var/run/docker.sock`) for Docker-in-Docker style test usage.
- Uses a dedicated container volume for `/workspace/.intellijPlatform` to avoid host OS cache contamination.
- Forwards API keys into the builder container:
  - Uses existing env vars when already set (`OPENAI_API_KEY`, `CODEX_API_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, `GOOGLE_API_KEY`).
  - If missing on host, auto-loads from local files (`~/.openai`, `~/.anthropic`, `~/.vertes`, `~/.vertex`) and forwards as env vars.
- Builds plugin and runs tests in containerized environment.
- Uses release build version format `X.Y.Z-<gitHash>` (no `SNAPSHOT`, no timestamp) by passing `-Pmcp.release.build=true`.

Matrix goals:

- Build plugin with stable line (default `2025.3.1`) and run baseline tests.
- Build plugin with EAP line request (default `2026.1`) resolved to the latest matching EAP build number (for example `261.20869.38`) and run baseline tests.
- Stage 1/2 exclude full `:test-integration:test`; selected integration coverage runs only in Stage 3.
- `RELEASE_STABLE_*` and `RELEASE_EAP_*` overrides are forwarded into the Docker builder container.
- Run selected test-integration category across:
  - IDEA stable
  - IDEA EAP
  - PyCharm stable
  - PyCharm EAP

Selected category currently includes:

- `DialogKiller*`
- `IntelliJContainerTest*`
- `InfrastructureTest*`
- `WhatYouSeeTest*`
- dedicated PyCharm smoke tests

Stable plugin artifact handling:

- Plugin ZIP built from stable build is preserved under a deterministic path: `release/out/plugin-${STABLE_PRODUCT}-${STABLE_VERSION}.zip`
- Default artifact path: `release/out/plugin-idea-2025.3.1.zip`
- This ZIP is the candidate for publishing.

### Stage 4: GitHub Release Publish

The publish stage is available in non-dry-run mode with the explicit `--publish` flag:

```bash
release/scripts/run-release.sh --no-dry-run --publish
```

Explicit override for publishing previously prepared artifacts:

```bash
release/scripts/run-release.sh --no-dry-run --publish --skip-build --skip-notes --allow-existing-artifacts
```

Target command when enabled:

```bash
gh release create <tag> <stable-plugin-zip> EULA \
  --repo jonnyzzz/mcp-steroid \
  --target "$(git -C website rev-parse HEAD)" \
  --notes-file <notes-file>
```

**EULA**: The `gh` CLI uses the source filename as the asset name. The root `EULA` file is uploaded directly — no renaming needed.

**Release target**: The release is created on the public repo (`jonnyzzz/mcp-steroid`), so `--target` must be a commit from that repo (use `git -C website rev-parse HEAD`).

**Tagging**: After publish, create matching tags in both repos:
```bash
git tag -a "v<version>" -m "release: <version>" HEAD && git push origin "v<version>"
git -C website tag -a "v<version>" -m "release: <version>" HEAD && git push origin "v<version>"
```

**Immutable releases**: Once published on `jonnyzzz/mcp-steroid`, releases cannot be modified or have assets added. If you need to fix a release, delete it and recreate. Tags locked by immutable releases cannot be reused.

Required inputs for publish stage:

- **Tag**: defaults to `v<VERSION>` derived from `VERSION` file (e.g., `v0.91.0`)
- **Tag target**: the HEAD commit of the `website/` (public repo) clone
- **Notes file**: defaults to `release/notes/<version>.md` (for example `release/notes/0.91.0.md`)
- **Plugin ZIP**: defaults to `release/out/plugin-idea-2025.3.1.zip`
- `gh` CLI must be installed and authenticated (`gh auth status`)
- Notes file and ZIP file must exist before publish stage starts
- Existing release tag on GitHub is treated as a hard stop (no overwrite)
- Publish with `--skip-build`/`--skip-notes` is blocked unless `--allow-existing-artifacts` is explicitly provided

This stage remains disabled in dry-run mode regardless of the `--publish` flag. In dry-run mode, version remains unchanged and no GitHub release is created.

### Stage 4b: Upload to JetBrains Marketplace

After the GitHub release is published, upload the plugin to JetBrains Marketplace:

```bash
release/scripts/publish-marketplace.sh <plugin-zip>
```

Requires `~/.marketplace` file with JetBrains Marketplace permanent token (one line). The script uses the `xmlId` parameter (`com.jonnyzzz.mcp-steroid`) for the upload API. The plugin enters the JetBrains review queue and will be listed once approved.

Plugin page: https://plugins.jetbrains.com/plugin/30019-mcp-steroid

### Stage 5: Website Updates

Two website updates are needed:

**5a. Homepage version and whatsnew** — In `website/website/hugo.toml`:
- Update `params.version` to the new version
- Add a `[[params.whatsnew]]` entry at the top with date and summary

**5b. Release page** — Create `website/website/content/releases/<version>.md` following the pattern of previous releases. Include: version, date, download links (with SHA-256), highlights, and the standard feedback/support sections. The EULA link must point to the LICENSE asset on the GitHub release (`https://github.com/jonnyzzz/mcp-steroid/releases/download/v<version>/LICENSE`), not to the website.

Commit and push in the `website/` repo after both updates.

### Stage 6: Website Build and Publish via Agent

Create/update release page content with another agent run:

```bash
/Users/jonnyzzz/Work/jonnyzzz-ai-coder/run-agent.sh codex \
  /Users/jonnyzzz/Work/mcp-steroid \
  /Users/jonnyzzz/Work/mcp-steroid/release/prompts/website-release-page.md
```

Then build and serve website for review:

```bash
cd website/website
make build
make dev
```

The `make build` target automatically:

- Updates `hugo.toml` with the current version from `VERSION`
- Generates `static/version.json` for the in-IDE update checker
- Generates `static/updatePlugins.xml` — downloads the release ZIP via URL from `gh`, extracts the plugin version from `ij-plugin-*.jar`'s `plugin.xml`, and generates the XML with the exact artifact version. Requires `gh` (authenticated) and `uv`. No fallbacks — build fails on any error.
- Builds the Hugo site to `public/`

Post-build, mark older GitHub releases as obsolete:

```bash
# For each older version
gh release edit <old-version> --repo jonnyzzz/mcp-steroid --notes-file <updated-body-with-obsolete-banner>
```

The website automatically shows obsolete banners on older release pages (handled by `layouts/releases/single.html` template logic — no manual content changes needed).

### Stage 6: Final Report

Report:

- Version bump commit hash and resulting version.
- Stable plugin ZIP path.
- Build/test matrix results.
- Release notes file path.
- Website page path and local dev URL.
- Any blockers.

## Failure Handling

- Never repeat version bump commit automatically after it has been recorded.
- Keep partial outputs under `release/out/`.
- If Docker or integration tests fail, stop publish stages and report diagnostics.

## Notes

- The `CLAUDECODE` env var must be unset before launching nested Claude Code agents via `run-agent.sh`. Both `run-agent.sh` scripts handle this automatically.
- Release notes format should match the GitHub release body style (user-friendly prose with section headers, not raw commit hashes).
- All links in release pages must use full URLs (`https://mcp-steroid.jonnyzzz.com/...`), never relative paths — release content is also shown on GitHub where relative links break.
- Custom plugin repository URL: `https://mcp-steroid.jonnyzzz.com/updatePlugins.xml` — always points to the latest release only.
- The `updatePlugins.xml` is generated by `website/website/scripts/generate-update-plugins-xml.py` (run via `uv`). It downloads the release ZIP, extracts `plugin.xml` from the JAR to get the exact version and `since-build`, and validates URL format. No fallbacks — errors are fatal.

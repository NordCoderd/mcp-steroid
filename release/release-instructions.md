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
4. Prefer IntelliJ MCP Steroid for code-aware operations; shell fallback is acceptable for orchestration scripts.
5. Keep append-only coordination notes in `MESSAGE-BUS.md` and blockers in `ISSUES.md`.
6. If a stage fails, document failure clearly and restart from the appropriate stage.

## Inputs

- Project root: `/Users/jonnyzzz/Work/mcp-steroid`
- Orchestrator toolkit root: `/Users/jonnyzzz/Work/jonnyzzz-ai-coder`
- Methodology source: `/Users/jonnyzzz/Work/jonnyzzz-ai-coder/THE_PROMPT_v5.md`
- IntelliJ/PyCharm source research repo (for startup/debug behavior): `/Users/jonnyzzz/Work/intellij`

## Release Stages

## Dry-Run Mode

Use `RELEASE_DRY_RUN=1` (default in `release/scripts/run-release.sh`).

Dry-run guarantees:

- Version bump stage is skipped.
- GitHub publish stage is skipped.
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

### Stage 0: Preflight

1. Ensure working tree is clean enough for release actions.
2. Verify Docker daemon is reachable.
3. Verify `gh auth status` is valid (only required when publish is enabled).
4. Ensure `VERSION` is in semantic format `major.minor.patch`.

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

### Stage 2: Dockerized Build/Test Matrix

Run:

```bash
release/scripts/run-builder.sh bash release/scripts/run-release-build-matrix.sh
```

Builder container requirements:

- Uses `release/docker/Dockerfile`.
- Mounts host Docker socket (`/var/run/docker.sock`) for Docker-in-Docker style test usage.
- Uses a dedicated container volume for `/workspace/.intellijPlatform` to avoid host OS cache contamination.
- Builds plugin and runs tests in containerized environment.

Matrix goals:

- Build plugin with stable line (default `2025.3`) and run baseline tests.
- Build plugin with EAP line (default `2026.1`) and run baseline tests.
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

- Preserve plugin ZIP built from stable build under `release/out/`.
- This ZIP is the candidate for publishing.

### Stage 3: Release Notes via Agents

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

### Stage 4: GitHub Release Publish

Target command when enabled:

```bash
gh release create <tag> <stable-plugin-zip> --repo jonnyzzz/mcp-steroid --notes-file <notes-file>
```

This stage must stay disabled in dry-run mode and only run in non-dry runs with explicit approval.

### Stage 5: Website Release Page Update via Agent

Create/update release page content with another agent run:

```bash
/Users/jonnyzzz/Work/jonnyzzz-ai-coder/run-agent.sh codex \
  /Users/jonnyzzz/Work/mcp-steroid \
  /Users/jonnyzzz/Work/mcp-steroid/release/prompts/website-release-page.md
```

Then build and serve website for review:

```bash
cd website
make build
make dev
```

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

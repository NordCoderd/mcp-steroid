## Release Automation Workspace

This directory contains release orchestration assets for `mcp-steroid`:

- `release-instructions.md` — primary prompt/instructions for a release worker agent.
- `TODO-release.md` — release backlog and completion checklist.
- `docker/Dockerfile` — reproducible builder image used for release builds/tests.
- `scripts/` — helper scripts for dry-run orchestration, version bump, and Dockerized build matrix execution.
- `prompts/` — prompt templates for release notes and website update agent runs.
- `notes/` — committed versioned release notes (`release/notes/<version>.md`) used by build/publish.
- `out/` — deterministic output directory for release artifacts.

Default execution mode is dry-run (`release/scripts/run-release.sh --dry-run`), which:

- Skips version changes (`VERSION` file remains unchanged)
- Disables publishing (no GitHub release created)
- Enforces clean worktree (override with `--allow-dirty`)
- Still runs build/test matrix and release notes preparation

Release notes workflow:

- Notes are generated/reviewed into `release/notes/<version>.md`
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

- Uses tag `v<VERSION>` and targets the recorded version-bump commit SHA
- Refuses `--publish` together with `--skip-build` or `--skip-notes` unless `--allow-existing-artifacts` is passed
- Refuses to publish if the GitHub release tag already exists

Container builds use an isolated `.intellijPlatform` Docker volume to prevent host-OS IDE cache conflicts.

Builder container API key forwarding:

- Reuses host env vars when present: `OPENAI_API_KEY`, `CODEX_API_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, `GOOGLE_API_KEY`
- If missing, auto-loads from known local files and forwards as env:
  - `~/.openai` -> `OPENAI_API_KEY` (and `CODEX_API_KEY` fallback)
  - `~/.anthropic` -> `ANTHROPIC_API_KEY`
  - `~/.vertes` / `~/.vertex` -> `GEMINI_API_KEY` (and `GOOGLE_API_KEY` fallback)

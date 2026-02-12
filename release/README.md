## Release Automation Workspace

This directory contains release orchestration assets for `mcp-steroid`:

- `release-instructions.md` — primary prompt/instructions for a release worker agent.
- `TODO-release.md` — release backlog and completion checklist.
- `docker/Dockerfile` — reproducible builder image used for release builds/tests.
- `scripts/` — helper scripts for dry-run orchestration, version bump, and Dockerized build matrix execution.
- `prompts/` — prompt templates for release notes and website update agent runs.
- `out/` — deterministic output directory for release artifacts.

Default execution mode is dry-run (`release/scripts/run-release.sh --dry-run`), which:

- Skips version changes (`VERSION` file remains unchanged)
- Disables publishing (no GitHub release created)
- Enforces clean worktree (override with `--allow-dirty`)
- Still runs build/test matrix and release notes preparation

Stable plugin artifact path: `release/out/plugin-idea-2025.3.zip`

Publish stage available in non-dry-run mode with explicit `--publish` flag:

```bash
release/scripts/run-release.sh --no-dry-run --publish
```

Container builds use an isolated `.intellijPlatform` Docker volume to prevent host-OS IDE cache conflicts.

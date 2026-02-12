## Release Automation Workspace

This directory contains release orchestration assets for `mcp-steroid`:

- `release-instructions.md` — primary prompt/instructions for a release worker agent.
- `TODO-release.md` — release backlog and completion checklist.
- `docker/Dockerfile` — reproducible builder image used for release builds/tests.
- `scripts/` — helper scripts for dry-run orchestration, version bump, and Dockerized build matrix execution.
- `prompts/` — prompt templates for release notes and website update agent runs.

Default execution mode is dry-run (`release/scripts/run-release.sh --dry-run`), which skips version changes and publishing.

Container builds use an isolated `.intellijPlatform` Docker volume to prevent host-OS IDE cache conflicts.

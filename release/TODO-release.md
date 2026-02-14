# TODO Release

## In Scope (Prepared Now)

- [x] Create release workspace under `release/`.
- [x] Add release worker instructions in `release/release-instructions.md`.
- [x] Define Docker build container in `release/docker/Dockerfile`.
- [x] Add Dockerized matrix runner script for build + selected integration tests.
- [x] Preserve stable (`2025.3.1`) plugin ZIP artifact under deterministic path `release/out/plugin-idea-2025.3.1.zip`.
- [x] Add one-time version bump script with rerun guard.
- [x] Add dry-run mode that skips version bump and GitHub publish (keeps `VERSION` unchanged).
- [x] Add release orchestration entrypoint script (`release/scripts/run-release.sh`).
- [x] Isolate `.intellijPlatform` cache in container volume and add `.dockerignore` for lean Docker context.
- [x] Add agent prompt templates for release notes collection/review and website release-page update.
- [x] Add `--publish` flag for explicit publish stage enablement in non-dry-run mode.
- [x] Document preflight clean-worktree enforcement with `--allow-dirty` override.
- [x] Document publish stage inputs: tag (default `v<version>`), notes file (default `release/notes/<version>.md`), ZIP (default `release/out/plugin-idea-2025.3.1.zip`).
- [x] Forward stable/EAP matrix override env vars into Docker builder container.
- [x] Forward AI API keys into Docker builder container with env-first and local-file fallback (`~/.openai`, `~/.anthropic`, `~/.vertes`, `~/.vertex`).
- [x] Harden release-critical download tasks with retries/timeouts (`downloadKotlinc_*`, `ocr-tesseract` tessdata, integration IDE downloads).
- [x] Harden publish stage with target commit, existing-release stop check, and stale-artifact guard.
- [x] Validate version-bump state file against current `VERSION` and recorded commit.
- [x] Use committed release notes file at `release/notes/<version>.md` and include it into plugin `change-notes`.
- [x] Release matrix uses non-snapshot version format (`<version>-<gitHash>`) for build artifacts.
- [x] Constrain release notes generation to local-ancestor commit ranges (with 200-commit fallback when no prior local tag exists).

## Verification Tasks

- [ ] Run `release/scripts/bump-version.sh` on an actual release branch.
- [ ] Run `release/scripts/run-release.sh --dry-run` and keep artifacts.
- [ ] Run `release/scripts/run-builder.sh bash release/scripts/run-release-build-matrix.sh`.
- [ ] Confirm selected test category execution on:
- [ ] IDEA stable lane
- [ ] IDEA EAP lane
- [ ] PyCharm stable lane
- [ ] PyCharm EAP lane
- [ ] GoLand stable lane
- [ ] GoLand EAP lane
- [ ] WebStorm stable lane
- [ ] WebStorm EAP lane
- [ ] Confirm stable ZIP copied to `release/out/plugin-idea-2025.3.1.zip`.

## Later (Enhancements)

- [ ] Add post-publish validation (GitHub release asset checksum + website link checks).

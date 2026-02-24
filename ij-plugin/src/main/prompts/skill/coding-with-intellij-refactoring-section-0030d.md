---

## Docker-Unavailable Fallback: Compile Verification When Tests Cannot Run

When `./mvnw test` fails with `Could not find a valid Docker environment` or a `DockerException`,
Testcontainers cannot start the database container. Your code changes may be correct even though
no test passed. Use this two-step verification:

**Step 1 — Confirm it is a Docker-only failure** (not a compile or logic error):

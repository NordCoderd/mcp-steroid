
> **Once smart mode is confirmed, do NOT re-probe before each subsequent operation.** Combine the readiness check with your first real action to save round-trips (~20s each). Only re-probe if you triggered a Maven import or other index-invalidating step.

---

## ⚡ Spring Boot / Maven Combined Startup Call

Combine readiness + Docker + VCS discovery in ONE call instead of 3 separate calls (saves ~60s). **Skip the Docker check if the scenario is pure file-creation (no @Testcontainers, no Docker in FAIL_TO_PASS tests)** — the check adds 10-15s with no benefit for those cases:

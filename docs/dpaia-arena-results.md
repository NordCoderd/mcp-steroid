# DPAIA Arena Results

Automated runs of all PRIMARY_COMPARISON_CASES with `claude+mcp`.
Each scenario is run up to 3 times. Analysis and prompt improvements happen between failed runs.

| Scenario | Run | Fix? | Exit | Duration | exec_code | Summary |
|----------|-----|------|------|----------|-----------|---------|
| empty__maven__springboot3-3 | 1 | True | 0 | 154s | 3 | Created Product JPA entity with Bean Validation and ProductRepository with Spring Data JPA; all 22 tests pass |
| feature__service-125 | 1 | False | -1 | 900s | 0 | (timed out — agent investigated Docker instead of implementing fix) |
| feature__service-125 | 2 | True | 0 | 638s | 0 | Implemented status transition validator, 5 new query endpoints, DB migration — ReleaseStatusTransitionValidatorTest 25/25 PASS, Testcontainers blocked by Docker Desktop API (400), BUILD SUCCESS |
| empty__maven__springboot3-1 | 1 | True | 0 | 219s | 0 | JWT auth: spring-security+jjwt, User entity, BCrypt, JwtUtil, SecurityConfig, AuthController — 9/9 AuthControllerTest + 10/10 total pass, BUILD SUCCESS |

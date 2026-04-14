# DPAIA Arena Results

Automated runs of all PRIMARY_COMPARISON_CASES with `claude+mcp`.
Each scenario is run up to 3 times. Analysis and prompt improvements happen between failed runs.

| Scenario | Run | Fix? | Exit | Duration | exec_code | Summary |
|----------|-----|------|------|----------|-----------|---------|
| empty__maven__springboot3-3 | 1 | True | 0 | 154s | 3 | Created Product JPA entity with Bean Validation and ProductRepository with Spring Data JPA; all 22 tests pass |
| feature__service-125 | 1 | False | -1 | 900s | 0 | (timed out — agent investigated Docker instead of implementing fix) |
| feature__service-125 | 2 | True | 0 | 638s | 0 | Implemented status transition validator, 5 new query endpoints, DB migration — ReleaseStatusTransitionValidatorTest 25/25 PASS, Testcontainers blocked by Docker Desktop API (400), BUILD SUCCESS |
| empty__maven__springboot3-1 | 1 | True | 0 | 219s | 0 | JWT auth: spring-security+jjwt, User entity, BCrypt, JwtUtil, SecurityConfig, AuthController — 9/9 AuthControllerTest + 10/10 total pass, BUILD SUCCESS |
| feature__service-25 | 1 | True | 0 | 380s | 0 | Parent-child Release: V5 migration, parent_id column, ManyToOne/OneToMany, parentCode in DTO/commands, /all-features endpoint — test-compile BUILD SUCCESS, Testcontainers blocked by Docker |
| spring__petclinic__rest-14 | 1 | True | 0 | 130s | 0 | Updated /api → /api/v1 in 7 REST controllers, location headers, openapi.yml — 181/181 tests, BUILD SUCCESS |
| spring__petclinic-36 | 1 | True | 0 | 200s | 0 | Added email field to Owner: @Email validation, 4 DB schemas, seed data, form+details+list templates, 8 locale files — 96/96 tests, BUILD SUCCESS |
| jhipster__sample__app-3 | 1 | True | 0 | 146s | 0 | Renamed `ROLE_ADMIN` to `ROLE_ADMINISTRATOR` in `AuthoritiesConstants.java` (constant value), `AuthorityResource.java` (4 `@PreAuthorize` annotations), `authority.csv` and `user_authority.csv` (Liquibase seed data), `application.yml` (health endpoint roles), and TypeScript files (`authority.constants.ts`, `authority.routes.ts`, `has-any-authority.directive.ts`, `navbar.component.html`); all 4 FAIL_TO_PASS tests and the full 47-test suite pass with BUILD SUCCESS. |
| train__ticket-1 | 1 | True | 0 | 240s | 0 | Added OrderByAccountAndDateRangeInfo entity, findByAccountIdAndTravelDateBetween repository method, queryOrdersByAccountAndTravelDate service method with input validation, and POST /order/byDate controller endpoint; all 3 FAIL_TO_PASS tests (testQueryOrdersByAccountAndTravelDate, testQueryOrdersByAccountAndTravelDate1, testQueryOrdersByAccountAndTravelDate2) confirmed passing with BUILD SUCCESS on ts-order-service using Java 11. |
| train__ticket-31 | 1 | True | 0 | 345s | 0 | Added paymentTime field to Payment entity, findByUserIdAndPaymentTimeBetween query to PaymentRepository (JpaRepository), searchByUserAndDateRange method with full validation to PaymentService/PaymentServiceImpl, and GET /payment/search endpoint to PaymentController; all 30 tests pass (BUILD SUCCESS) including both PaymentControllerIntegrationTest (5 tests) and PaymentControllerTest (5 tests). |
| spring__boot__microshop-18 | 1 | False | -1 | 900s | 0 | (timed out — no summary) |

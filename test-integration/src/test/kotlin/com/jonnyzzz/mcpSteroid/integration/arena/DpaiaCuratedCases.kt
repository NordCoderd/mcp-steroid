/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * Curated set of dpaia.dev arena test cases selected for MCP Steroid comparison testing.
 *
 * Selection criteria:
 * - Requires understanding codebase structure (not just grep/search)
 * - Benefits from IDE navigation, semantic analysis, or compilation feedback
 * - Complex enough that IntelliJ tooling provides a measurable advantage
 * - The project must be able to open in IntelliJ IDEA without manual setup
 *
 * Dataset source:
 * https://raw.githubusercontent.com/dpaia/ee-dataset/main/datasets/java-spring-ee-dataset.json
 *
 * To run a specific case:
 *   -Darena.test.instanceId=dpaia__spring__petclinic__rest-14
 */
object DpaiaCuratedCases {

    /** The default simple case — used as a quick smoke test. */
    const val DEFAULT_SIMPLE = "dpaia__empty__maven__springboot3-3"

    /**
     * Curated shortlist for evaluation runs.
     *
     * Selection criteria: large patch (complex multi-file change), multi-layer
     * (entity + service + controller + migration), and scenarios where IDE tools
     * (Find Usages, compile feedback, run tests) provide measurable advantage.
     *
     * Dataset: https://raw.githubusercontent.com/dpaia/ee-dataset/main/datasets/java-spring-ee-dataset.json
     * (154 cases across 11 repos as of 2026-02-21)
     *
     * | instanceId                              | Repo                  | Patch  | Why MCP helps                                   |
     * |-----------------------------------------|-----------------------|--------|--------------------------------------------------|
     * | dpaia__feature__service-125             | feature-service       | 44 KB  | Cross-layer queries + status machine; PSI nav    |
     * | dpaia__feature__service-122             | feature-service       | 31 KB  | Full notification subsystem; pattern reuse       |
     * | dpaia__empty__maven__springboot3-1      | empty-maven-springboot| 28 KB  | JWT from scratch; Spring Security API resolution |
     * | dpaia__feature__service-25              | feature-service       | 17 KB  | Self-referential JPA; circular dep detection     |
     * | dpaia__feature__service-22              | feature-service       | 15 KB  | FeatureReactionController; IDE Find Usages       |
     * | dpaia__empty__maven__springboot3-3      | empty-maven-springboot| 15 KB  | Product entity; jakarta vs javax validation      |
     * | dpaia__feature__service-21              | feature-service       | 13 KB  | Comments/replies; multi-controller consistency   |
     * | dpaia__spring__petclinic__rest-23       | spring-petclinic-rest | —      | Password policy; IDE per-class test isolation    |
     * | dpaia__spring__petclinic__rest-14       | spring-petclinic-rest | —      | 575 failing tests; IDE compile check             |
     * | dpaia__spring__boot__microshop-1        | spring-boot-microshop | —      | WebClient migration; IDE Find Usages             |
     */
    val COMPARISON_CASES: List<String> = listOf(
        "dpaia__feature__service-125",
        "dpaia__feature__service-122",
        "dpaia__empty__maven__springboot3-1",
        "dpaia__feature__service-25",
        "dpaia__feature__service-22",
        "dpaia__empty__maven__springboot3-3",
        "dpaia__feature__service-21",
        "dpaia__spring__petclinic__rest-23",
        "dpaia__spring__petclinic__rest-14",
        "dpaia__spring__boot__microshop-1",
    )

    /**
     * Primary comparison cases for the A/B comparison: "agent with MCP Steroid" vs "agent without".
     *
     * **Original 4 cases (Batch 1, 2026-03-02):**
     * - feature-service-125: 44 KB patch; JPQL + status machine spanning entities/services/controllers
     * - empty-maven-springboot3-1: JWT auth from scratch; Spring Security API versioning traps
     * - feature-service-25: self-referential JPA hierarchy; circular dependency caught at compile time
     * - spring-petclinic-rest-14: 575 failing tests; IDE highlights every missed edit immediately
     *
     * **8 new cases (Batch 2, Group 4 expansion):**
     * Selected via dataset analysis (group4-analyze-dataset.py) covering 3 new repos and diverse
     * task types. See docs/dpaia-runs/EXEC-SUMMARY.md for selection rationale.
     *
     * | instanceId                              | Repo                    | FTP | Files | Why MCP helps                                   |
     * |-----------------------------------------|-------------------------|-----|-------|--------------------------------------------------|
     * | dpaia__spring__petclinic-36             | spring-petclinic (NEW)  |  4  |   8   | Add email field: entity+schema+form across layers|
     * | dpaia__jhipster__sample__app-3          | jhipster (NEW)          |  4  |   9   | Rename ROLE_ADMIN; IDE Find Usages critical      |
     * | dpaia__train__ticket-1                  | train-ticket (NEW)      |  3  |   4   | Extend OrderRepository with date-range JPQL      |
     * | dpaia__train__ticket-31                 | train-ticket (NEW)      |  2  |   8   | Extend PaymentRepository + service layer         |
     * | dpaia__spring__boot__microshop-18       | spring-boot-microshop   |  8  |  23   | RestTemplate→WebClient migration; 23 files       |
     * | dpaia__spring__boot__microshop-2        | spring-boot-microshop   |  4  |  10   | Add productId validation across all microservices|
     * | dpaia__spring__petclinic-27             | spring-petclinic (NEW)  |  3  |   4   | Build REST endpoints for owners/pets/visits      |
     * | dpaia__spring__petclinic__rest-3        | spring-petclinic-rest   |  3  |   5   | Cache eviction + scheduled invalidation          |
     */
    val PRIMARY_COMPARISON_CASES: List<String> = listOf(
        // Batch 1: original 4 cases
        "dpaia__feature__service-125",
        "dpaia__empty__maven__springboot3-1",
        "dpaia__feature__service-25",
        "dpaia__spring__petclinic__rest-14",

        // Batch 2: 8 new cases from Group 4 expansion
        // NEW repo: spring-petclinic — add email field to Owner across entity/DB schema/form (REST+Validation)
        "dpaia__spring__petclinic-36",
        // NEW repo: jhipster-sample-app — rename ROLE_ADMIN to ROLE_ADMINISTRATOR; Find Usages ideal (Security, 9 files)
        "dpaia__jhipster__sample__app-3",
        // NEW repo: train-ticket — extend OrderRepository with accountId+date-range JPQL queries (Data+JPA)
        "dpaia__train__ticket-1",
        // NEW repo: train-ticket — extend PaymentRepository with date-range + update service layer (Controller+JPA)
        "dpaia__train__ticket-31",
        // spring-boot-microshop — replace RestTemplate with WebClient across ALL microservices (23 files, ftp=8)
        "dpaia__spring__boot__microshop-18",
        // spring-boot-microshop — add productId validation across all microservices (REST+Validation, 10 files)
        "dpaia__spring__boot__microshop-2",
        // NEW repo: spring-petclinic — build REST endpoints for owners/pets/visits (feature addition, 4 files)
        "dpaia__spring__petclinic-27",
        // spring-petclinic-rest — add cache eviction + scheduled invalidation (Cache, 5 files)
        "dpaia__spring__petclinic__rest-3",
    )
}

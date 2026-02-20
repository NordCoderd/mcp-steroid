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
     * Curated shortlist for evaluation runs (complex cases, good for comparison testing).
     *
     * | instanceId                         | Repo                        | Focus                              | Complexity  |
     * |------------------------------------|-----------------------------|------------------------------------|-------------|
     * | dpaia__feature__service-51         | feature-service             | RabbitMQ integration + dedup       | Very High   |
     * | dpaia__spring__boot__microshop-1   | spring-boot-microshop       | RestTemplate→WebClient migration   | Very High   |
     * | dpaia__spring__petclinic__rest-23  | spring-petclinic-rest       | Password policy validation         | Very High   |
     * | dpaia__feature__service-25         | feature-service             | JPA parent-child + circular dep    | High        |
     * | dpaia__feature__service-122        | feature-service             | Notification system entity + API   | High        |
     * | dpaia__spring__petclinic-36        | spring-petclinic            | Add email field across DB schemas  | High        |
     * | dpaia__spring__petclinic__rest-14  | spring-petclinic-rest       | Update API base path /api → /api/v1| Medium      |
     * | dpaia__spring__boot__microshop-24  | spring-boot-microshop       | Add rating field with DTOs         | Medium      |
     */
    val COMPARISON_CASES: List<String> = listOf(
        "dpaia__feature__service-51",
        "dpaia__spring__boot__microshop-1",
        "dpaia__spring__petclinic__rest-23",
        "dpaia__feature__service-25",
        "dpaia__feature__service-122",
        "dpaia__spring__petclinic-36",
        "dpaia__spring__petclinic__rest-14",
        "dpaia__spring__boot__microshop-24",
    )

    /**
     * Primary cases for the A/B comparison: "agent with MCP Steroid" vs "agent without MCP Steroid".
     *
     * Chosen because IDE navigation / compile feedback / test-runner makes a real difference:
     * - spring-petclinic-rest-14: 575 failing tests; IDE compile check prevents missed edits
     * - spring-petclinic-rest-23: Security validation; IDE can run per-class to isolate failures
     * - feature-service-25: JPA entity with circular dependency; IDE detects at compile time
     * - spring-boot-microshop-1: WebClient migration; IDE Find Usages finds all RestTemplate calls
     */
    val PRIMARY_COMPARISON_CASES: List<String> = listOf(
        "dpaia__spring__petclinic__rest-14",
        "dpaia__spring__petclinic__rest-23",
        "dpaia__feature__service-25",
        "dpaia__spring__boot__microshop-1",
    )
}

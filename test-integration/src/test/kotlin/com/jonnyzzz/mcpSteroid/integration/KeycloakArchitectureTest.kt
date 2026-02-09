/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration

import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.assertExitCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.TimeUnit
import java.util.stream.Stream

/**
 * Integration test: architecture investigation of the Keycloak project.
 *
 * Clones the Keycloak repository (shallow) inside a Docker container,
 * opens it in IntelliJ IDEA with MCP Steroid, and asks AI agents to
 * investigate the project architecture using code execution.
 */
class KeycloakArchitectureTest {

    @MethodSource("agents")
    @ParameterizedTest(name = "{0}")
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `agent investigates authentication flow`(agentName: String, agent: AiAgentSession) {
        val prompt = buildString {
            appendLine("Investigate the Keycloak project that is open in IntelliJ IDEA.")
            appendLine()
            appendLine("Using steroid_execute_code, find the main entry point for user authentication.")
            appendLine("Trace the authentication flow through at least 3 classes.")
            appendLine("Show the class hierarchy and method calls involved in processing a login request.")
            appendLine()
            appendLine("You MUST use steroid_execute_code to search and navigate the code.")
            appendLine("Do NOT rely on general knowledge — find actual file paths and class names in the project.")
            appendLine("After your first steroid_execute_code call, include this in your final response:")
            appendLine("TOOL_EVIDENCE: <copy the line starting with Execution ID: ...>")
            appendLine()
            appendLine("At the end, output these markers on separate lines:")
            appendLine("AUTH_FLOW_FOUND: yes")
            appendLine("CLASSES_TRACED: <comma-separated list of at least 3 class names>")
        }

        val result = agent.runPrompt(prompt, timeoutSeconds = 600)
        result.assertExitCode(0, message = "authentication flow investigation")

        val combined = result.output + "\n" + result.stderr

        // Agent must show evidence of MCP Steroid execute_code usage
        assertUsedExecuteCodeEvidence(combined)

        // Agent must mention authentication-related Keycloak concepts
        val authPatterns = listOf(
            "authenticat", "AuthenticationFlow", "AuthenticationProcessor",
            "LoginProtocol", "AuthenticatorFactory", "Authenticator",
            "KeycloakSession", "AuthenticationSession",
        )
        val foundAuthConcept = authPatterns.any { pattern ->
            combined.contains(pattern, ignoreCase = true)
        }
        check(foundAuthConcept) {
            "Agent did not find authentication-related classes.\n" +
                    "Expected one of: $authPatterns\nOutput:\n$combined"
        }

        // Agent must provide actual file paths from the project
        val hasFilePaths = combined.contains(".java") || combined.contains(".kt")
        check(hasFilePaths) {
            "Agent did not provide specific file paths from the project.\nOutput:\n$combined"
        }

        println("[TEST] Agent '$agentName' successfully investigated the authentication flow")
    }

    @MethodSource("agents")
    @ParameterizedTest(name = "{0}")
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `agent investigates module structure`(agentName: String, agent: AiAgentSession) {
        val prompt = buildString {
            appendLine("Investigate the module structure of the Keycloak project open in IntelliJ IDEA.")
            appendLine()
            appendLine("Using steroid_execute_code:")
            appendLine("1. Find how many top-level modules the project has (look at pom.xml or settings files)")
            appendLine("2. Identify which module handles OAuth2/OIDC token generation")
            appendLine("3. Show the relevant file paths for the token generation code")
            appendLine()
            appendLine("You MUST use steroid_execute_code to search and navigate the code.")
            appendLine("Do NOT rely on general knowledge — find actual file paths in the project.")
            appendLine("After your first steroid_execute_code call, include this in your final response:")
            appendLine("TOOL_EVIDENCE: <copy the line starting with Execution ID: ...>")
            appendLine()
            appendLine("At the end, output these markers on separate lines:")
            appendLine("MODULE_STRUCTURE_FOUND: yes")
            appendLine("TOKEN_MODULE: <name of the module handling token generation>")
        }

        val result = agent.runPrompt(prompt, timeoutSeconds = 600)
        result.assertExitCode(0, message = "module structure investigation")

        val combined = result.output + "\n" + result.stderr

        // Agent must show evidence of MCP Steroid execute_code usage
        assertUsedExecuteCodeEvidence(combined)

        // Agent must mention token/OIDC-related concepts
        val tokenPatterns = listOf(
            "token", "oauth", "oidc", "OIDCLoginProtocol",
            "TokenManager", "AccessToken", "JsonWebToken",
        )
        val foundTokenConcept = tokenPatterns.any { pattern ->
            combined.contains(pattern, ignoreCase = true)
        }
        check(foundTokenConcept) {
            "Agent did not find token/OIDC-related classes.\n" +
                    "Expected one of: $tokenPatterns\nOutput:\n$combined"
        }

        // Agent must mention module names (Keycloak uses Maven modules)
        val modulePatterns = listOf(
            "services", "server-spi", "core", "protocol",
            "pom.xml", "<module>",
        )
        val foundModules = modulePatterns.any { pattern ->
            combined.contains(pattern, ignoreCase = true)
        }
        check(foundModules) {
            "Agent did not identify module structure.\n" +
                    "Expected one of: $modulePatterns\nOutput:\n$combined"
        }

        println("[TEST] Agent '$agentName' successfully investigated the module structure")
    }

    @MethodSource("agents")
    @ParameterizedTest(name = "{0}")
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `agent investigates SPI architecture`(agentName: String, agent: AiAgentSession) {
        val prompt = buildString {
            appendLine("Investigate the SPI (Service Provider Interface) architecture of the Keycloak project open in IntelliJ IDEA.")
            appendLine()
            appendLine("Using steroid_execute_code:")
            appendLine("1. Find the main SPI interfaces (look for ProviderFactory, Provider, Spi classes)")
            appendLine("2. Show how custom providers are registered (look for service loader files or registration mechanisms)")
            appendLine("3. Give a concrete example of one SPI implementation with its file path")
            appendLine()
            appendLine("You MUST use steroid_execute_code to search and navigate the code.")
            appendLine("Do NOT rely on general knowledge — find actual file paths and class names in the project.")
            appendLine("After your first steroid_execute_code call, include this in your final response:")
            appendLine("TOOL_EVIDENCE: <copy the line starting with Execution ID: ...>")
            appendLine()
            appendLine("At the end, output these markers on separate lines:")
            appendLine("SPI_FOUND: yes")
            appendLine("SPI_EXAMPLE: <name of a concrete SPI implementation class>")
        }

        val result = agent.runPrompt(prompt, timeoutSeconds = 600)
        result.assertExitCode(0, message = "SPI architecture investigation")

        val combined = result.output + "\n" + result.stderr

        // Agent must show evidence of MCP Steroid execute_code usage
        assertUsedExecuteCodeEvidence(combined)

        // Agent must mention SPI-related concepts
        val spiPatterns = listOf(
            "ProviderFactory", "Provider", "Spi",
            "ServiceLoader", "META-INF/services",
            "KeycloakSessionFactory", "ProviderEvent",
        )
        val foundSpiConcept = spiPatterns.any { pattern ->
            combined.contains(pattern, ignoreCase = true)
        }
        check(foundSpiConcept) {
            "Agent did not find SPI-related classes.\n" +
                    "Expected one of: $spiPatterns\nOutput:\n$combined"
        }

        // Agent must provide actual file paths from the project
        val hasFilePaths = combined.contains(".java") || combined.contains(".kt")
        check(hasFilePaths) {
            "Agent did not provide specific file paths from the project.\nOutput:\n$combined"
        }

        println("[TEST] Agent '$agentName' successfully investigated the SPI architecture")
    }

    private fun assertUsedExecuteCodeEvidence(combined: String) {
        val toolEvidencePatterns = listOf(
            "Execution ID:",
            "execution_id:",
            "tool mcp-steroid.steroid_execute_code",
            "steroid_execute_code(",
            "TOOL_EVIDENCE:"
        )

        val hasToolEvidence = toolEvidencePatterns.any { pattern ->
            combined.contains(pattern, ignoreCase = true)
        }
        check(hasToolEvidence) {
            "Agent must show evidence of steroid_execute_code usage.\n" +
                    "Expected one of: $toolEvidencePatterns\nOutput:\n$combined"
        }
    }

    companion object {
        private const val KEYCLOAK_REPO_URL = "https://github.com/keycloak/keycloak.git"

        @JvmStatic
        val lifetime by lazy {
            CloseableStackHost()
        }

        val session by lazy {
            IdeContainer.createWithGitRepo(
                lifetime,
                "ide-agent",
                gitRepoUrl = KEYCLOAK_REPO_URL,
                cloneTimeoutSeconds = 600,
            )
        }

        @JvmStatic
        fun agents(): Stream<Arguments> = session
            .aiAgentDriver
            .aiAgents
            .entries.stream()
            .map { (name, driver) ->
                Arguments.of(name, driver)
            }

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            // Trigger session creation: clone repo, start IDE, wait for MCP readiness
            session.toString()
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            lifetime.closeAllStacks()
        }
    }
}

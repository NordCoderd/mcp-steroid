# Agent Dockerfiles for Testing

This directory contains lightweight Dockerfiles used for integration tests of AI agent sessions.

## Structure

```
docker/
├── claude-cli/Dockerfile   - Node.js + @anthropic-ai/claude-code
├── codex-cli/Dockerfile    - Node.js + openai-codex (if available)
└── gemini-cli/Dockerfile   - Node.js + @google/generative-ai
```

## Source

These Dockerfiles are **copies** of the source files located in:
`test-helper/src/main/docker/agents/`

The copies are necessary because the Docker build process runs with the Dockerfile's parent directory as the working directory, and relative paths in the build context need to resolve correctly.

## Maintenance

When updating agent Dockerfiles:
1. Edit the source files in `test-helper/src/main/docker/agents/`
2. Copy changes to `test-helper/src/test/docker/` for tests

## Integration Tests

These Dockerfiles are used by:
- `DockerClaudeProgressTest.kt` - Tests Claude progress visibility
- `DockerCodexProgressTest.kt` - Tests Codex progress visibility (when created)
- `DockerGeminiProgressTest.kt` - Tests Gemini progress visibility (when created)

Tests requiring Docker and API keys are disabled by default and run only when:
- Docker is running
- Appropriate API key environment variable is set (e.g., `ANTHROPIC_API_KEY`)

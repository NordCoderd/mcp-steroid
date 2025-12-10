# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

IntelliJ MCP Steroid - an MCP server plugin for IntelliJ IDEA that exposes IDE APIs to LLM agents via Kotlin/Groovy code execution.

## Key Documentation

- [README.md](README.md) - Full API documentation and architecture
- [Plan.md](Plan.md) - Implementation plan and phases
- [Suggestions.md](Suggestions.md) - Open questions and design decisions
- [Discussions.md](Discussions.md) - Design discussions and decisions from Q&A sessions
- [STDIO_PROXY.md](STDIO_PROXY.md) - Setup instructions for stdio-to-HTTP proxy

## Build Commands

```bash
# Build the plugin
./gradlew build

# Run plugin in a sandboxed IntelliJ instance
./gradlew runIde

# Build distributable plugin ZIP
./gradlew buildPlugin

# Run tests
./gradlew test

# Verify plugin compatibility
./gradlew verifyPlugin
```

## Technology Stack

- **Gradle**: 8.11.1 with Kotlin DSL
- **Kotlin**: 2.1.0
- **Java Toolchain**: 21
- **IntelliJ Platform**: 2024.2.4 (configured in `gradle.properties`)
- **IntelliJ Platform Gradle Plugin**: 2.1.0

## Architecture

This is an IntelliJ Platform plugin using the standard extension-point architecture:

- **Plugin descriptor**: `src/main/resources/META-INF/plugin.xml` - declares plugin metadata, dependencies, and extension points
- **Source code**: `src/main/kotlin/com/jonnyzzz/intellij/mcp/` - Kotlin implementation
- **Plugin ID**: `com.jonnyzzz.intellij.mcp-steroid`
- **Compatibility**: IntelliJ 2024.2.x - 2024.3.x (build range 242.0 - 243.*)

## Configuration

- `gradle.properties`: Contains `platformVersion` to target different IntelliJ versions
- `build.gradle.kts`: Plugin configuration using `intellijPlatform` DSL

# intellij-mcp-steroid

An MCP on Steroids for IntelliJ

## Project Structure

This is an IntelliJ IDEA plugin project built with:
- **Gradle**: 8.11.1 (latest stable)
- **Kotlin**: 2.1.0 (latest stable)
- **IntelliJ Platform**: 2024.2.4 (configurable via `gradle.properties`)
- **Java Toolchain**: 21

## Building the Plugin

```bash
./gradlew build
```

## Running the Plugin in IntelliJ

```bash
./gradlew runIde
```

## Project Configuration

The project uses a minimalistic Gradle configuration:
- `build.gradle.kts`: Main build script with explicit plugin configuration
- `settings.gradle.kts`: Project name configuration
- `gradle.properties`: Build properties and IntelliJ platform version
- `gradle/wrapper/`: Gradle wrapper for consistent builds

## Updating IntelliJ Platform Version

To target a different IntelliJ Platform version, update the `platformVersion` property in `gradle.properties`:

```properties
platformVersion=2025.3
```

## Source Structure

- `src/main/kotlin/`: Kotlin source files
- `src/main/resources/META-INF/plugin.xml`: Plugin descriptor

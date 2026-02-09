# Docker Container Cleanup Mechanism

This document describes the Docker container cleanup mechanism implemented in the test-helper module, inspired by the Testcontainers Java library.

## Overview

The cleanup mechanism ensures that Docker containers created during tests are properly cleaned up, even if the test process crashes, is killed, or exits abnormally. This prevents orphaned containers from accumulating on the Docker host.

## Architecture

The implementation consists of three main components:

### 1. DockerSessionLabels

Manages Docker labels for tracking containers:

- **Base Label**: `com.jonnyzzz.mcpSteroid.test=true` - Identifies all test containers
- **Session ID Label**: `com.jonnyzzz.mcpSteroid.test.sessionId=<uuid>` - Unique per JVM process
- **Process ID Label**: `com.jonnyzzz.mcpSteroid.test.pid=<pid>` - Process that created the container
- **Created At Label**: `com.jonnyzzz.mcpSteroid.test.createdAt=<timestamp>` - Creation timestamp

All containers started by `DockerDriver` are automatically labeled with these values.

### 2. DockerReaper

A singleton cleanup manager that:

- **Registers containers** when they are created
- **Unregisters containers** when they are cleaned up normally
- **Installs a JVM shutdown hook** (once per process) to clean up on exit
- **Provides orphaned container detection** to find and clean up containers from dead processes

The reaper runs cleanup in two phases:

1. **Shutdown Hook**: Attempts to clean up all registered containers when the JVM exits
2. **Session Filter**: Uses Docker labels to find and remove any containers from this session

### 3. DockerDriver Integration

The `DockerDriver` automatically:

- Adds session labels to all containers
- Registers containers with the reaper
- Unregisters containers on normal cleanup (via `CloseableStack`)

## How It Works

### Normal Operation

```kotlin
val lifetime = CloseableStack()
val driver = DockerDriver(workDir, "TEST")

// Start a container
val containerId = driver.startContainer(
    lifetime = lifetime,
    imageName = "alpine:latest"
)

// Container is:
// 1. Labeled with session ID and PID
// 2. Registered with DockerReaper
// 3. Registered with CloseableStack

// Normal cleanup
lifetime.close()  // Cleans up container and unregisters from reaper
```

### Crash Recovery

If the test process crashes before `lifetime.close()`:

1. JVM shutdown hook fires
2. `DockerReaper.performCleanup()` runs
3. All registered containers are killed and removed
4. Session filter catches any containers that were missed

### Orphaned Container Cleanup

To clean up containers from previous test runs that failed:

```kotlin
DockerReaper.getInstance().cleanupOrphanedContainers()
```

This scans for containers with the test base label and checks if their creator process is still alive. Dead processes' containers are cleaned up.

## Comparison with Testcontainers

Our implementation is inspired by Testcontainers but simplified for our use case:

| Feature | Testcontainers | Our Implementation |
|---------|---------------|-------------------|
| Container Labels | ✅ `org.testcontainers.sessionId` | ✅ `com.jonnyzzz.mcpSteroid.test.sessionId` |
| JVM Shutdown Hook | ✅ Via ResourceReaper | ✅ Via DockerReaper |
| Ryuk Reaper Container | ✅ TCP death detection | ❌ Not implemented |
| Process Death Detection | ✅ Via Ryuk | ✅ Via ProcessHandle |
| Network Cleanup | ✅ Yes | ❌ Not implemented |
| Volume Cleanup | ✅ Yes | ❌ Not implemented |

### Why No Ryuk?

Testcontainers uses a "Ryuk" sidecar container that:

- Listens on TCP port 8080
- Accepts connections from test processes
- Cleans up when connection dies after timeout

We chose **not** to implement Ryuk because:

1. **Simpler**: JVM shutdown hooks work well for our use case
2. **Less overhead**: No additional container needed
3. **Fewer dependencies**: No TCP server/client code
4. **Process death detection**: We use Java's `ProcessHandle` API instead

If we need more robust cleanup in the future (e.g., handling SIGKILL), we can implement Ryuk-style TCP monitoring.

## Testing

See `DockerReaperTest.kt` for comprehensive tests:

- ✅ Container has session labels
- ✅ Cleanup via CloseableStack works
- ✅ Orphaned container detection works
- ✅ Session filter lists containers correctly

## Usage Recommendations

### For Test Developers

1. **Always use CloseableStack** for container lifecycle management
2. **Call cleanupOrphanedContainers()** at the start of test suites to clean up from previous runs
3. **Don't disable the reaper** - it's a safety net for abnormal exits

### For CI/CD

Add a cleanup step before tests:

```bash
# Clean up any orphaned containers
docker ps -aq --filter "label=com.jonnyzzz.mcpSteroid.test=true" | xargs -r docker rm -f
```

## References

### Testcontainers Resources

- [Ryuk and Resource Cleanup](https://deepwiki.com/testcontainers/testcontainers-python/3.8-ryuk-and-resource-cleanup)
- [Testcontainers Java - Custom Configuration](https://java.testcontainers.org/features/configuration/)
- [Resource Reaper - Testcontainers for .NET](https://dotnet.testcontainers.org/api/resource_reaper/)
- [Garbage Collector - Testcontainers for Go](https://golang.testcontainers.org/features/garbage_collector/)
- [Ryuk the Resource Reaper - Worldline Blog](https://blog.worldline.tech/2023/01/04/ryuk.html)
- [testcontainers/ryuk - Docker Hub](https://hub.docker.com/r/testcontainers/ryuk)
- [ResourceReaper.java - GitHub](https://github.com/testcontainers/testcontainers-java/blob/main/core/src/main/java/org/testcontainers/utility/ResourceReaper.java)
- [DockerClientFactory.java - GitHub](https://github.com/testcontainers/testcontainers-java/blob/main/core/src/main/java/org/testcontainers/DockerClientFactory.java)
- [moby-ryuk GitHub Repository](https://github.com/testcontainers/moby-ryuk)

### Key Implementation Patterns from Testcontainers

1. **Session ID**: UUID generated once per JVM process
2. **Docker Labels**: Applied to all containers for filtering
3. **JVM Shutdown Hooks**: Registered once using `AtomicBoolean.compareAndSet()`
4. **Concurrent Data Structures**: `ConcurrentHashMap.newKeySet()` for thread-safe tracking
5. **Docker Filters**: `--filter label=key=value` for querying containers

## Future Enhancements

Possible improvements if needed:

1. **Ryuk Implementation**: TCP-based death detection for SIGKILL handling
2. **Network Cleanup**: Track and clean up Docker networks
3. **Volume Cleanup**: Track and clean up Docker volumes
4. **Timeout-based Cleanup**: Clean up containers older than X hours
5. **Metrics**: Track cleanup success/failure rates

## License

Same as the parent project.

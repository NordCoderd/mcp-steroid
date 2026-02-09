# Docker Container Cleanup Mechanism

This document describes the Ryuk-based Docker container cleanup mechanism implemented in the test-helper module.

## Overview

The cleanup mechanism uses **testcontainers/ryuk** with socket-based communication to ensure Docker containers are cleaned up even when the test process crashes or is killed with SIGKILL.

## How It Works

### Socket-Based Cleanup (Handles SIGKILL)

1. **Ryuk Container**: Starts `testcontainers/ryuk:0.5.1` with Docker socket mounted
2. **TCP Connection**: Java process connects to Ryuk on port 8080
3. **Ping Messages**: Java sends "ping\n" every 1 second over the socket
4. **Registration**: Container IDs are registered via session label filter
5. **Death Detection**: If no ping for 5 seconds OR connection breaks, Ryuk kills all registered containers
6. **OS-Level**: When process dies (even SIGKILL), OS closes the socket automatically

### Architecture

```
Test Process                    Ryuk Container
    |                                |
    |------ Connect TCP ------------>|
    |<------ ACK -------------------|
    |                                |
    |------ label=sessionId=UUID --->|
    |<------ ACK -------------------|
    |                                |
    |------ ping ------------------>| (every 1 second)
    |------ ping ------------------>|
    |                                |
    [SIGKILL / Crash]                |
    X (socket closed by OS)          |
                                     | (5 sec timeout)
                                     |---> docker kill <containers>
```

## Components

### 1. DockerSessionLabels

Manages Docker labels for tracking containers:

- **Session ID**: `com.jonnyzzz.mcpSteroid.test.sessionId=<uuid>`
- **Process ID**: `com.jonnyzzz.mcpSteroid.test.pid=<pid>`
- **Created At**: `com.jonnyzzz.mcpSteroid.test.createdAt=<timestamp>`
- **Base Label**: `com.jonnyzzz.mcpSteroid.test=true`

### 2. RyukReaper

Manages the Ryuk container lifecycle and socket communication:

- Starts Ryuk container with Docker socket mounted (`-v /var/run/docker.sock:/var/run/docker.sock`)
- Connects to Ryuk via TCP socket
- Sends ping messages every 1 second
- Registers session filter with Ryuk
- Handles cleanup when connection is lost

### 3. DockerDriver Integration

Automatically:
- Labels all containers with session metadata
- Starts Ryuk reaper on first container
- Registers containers with Ryuk
- Provides normal cleanup via CloseableStack

## Usage

```kotlin
val lifetime = CloseableStackHost()
val driver = DockerDriver(workDir, "TEST")

// Start container - Ryuk automatically started
val containerId = driver.startContainer(
    lifetime = lifetime,
    imageName = "alpine:latest",
    cmd = listOf("sleep", "infinity")
)

// Container is:
// 1. Labeled with session ID and PID
// 2. Registered with Ryuk via socket
// 3. Monitored by ping messages
// 4. Registered with CloseableStack for normal cleanup

// Normal cleanup
lifetime.closeAllStacks()

// If process crashes/SIGKILL:
// - OS closes socket
// - Ryuk detects loss after 5 seconds
// - Ryuk kills all containers with session label
```

## Why Ryuk?

### JVM Shutdown Hooks DON'T Work For:
- ❌ SIGKILL (`kill -9`)
- ❌ Hard crashes (segfault, OOM)
- ❌ Power loss
- ❌ Force-quit in some environments

### Ryuk Socket-Based Approach Works For:
- ✅ SIGKILL - OS closes socket
- ✅ Hard crashes - OS closes socket
- ✅ SIGTERM - Normal cleanup + socket close
- ✅ Uncaught exceptions - Normal cleanup + socket close

## Configuration

Ryuk container is started with:
```bash
docker run -d --privileged \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -p 0:8080 \
  testcontainers/ryuk:0.5.1
```

- **Port**: 8080 (mapped to random host port)
- **Timeout**: 5 seconds after last ping or connection loss
- **Ping Interval**: 1 second

## Protocol

### Line-Based Communication

**Registration**:
```
Client: label=com.jonnyzzz.mcpSteroid.test.sessionId=<uuid>\n
Server: ACK\n
```

**Ping Loop**:
```
Client: ping\n
Client: ping\n
Client: ping\n
...
```

**Cleanup Trigger**:
- No ping for 5 seconds
- Socket closed/disconnected
- Ryuk executes: `docker ps -aq --filter label=<sessionId> | xargs docker rm -f`

## Testing

See `RyukReaperTest.kt` for tests:
- ✅ Container has session labels
- ✅ Normal cleanup via CloseableStack works
- ✅ Ryuk reaper starts and registers session

## CI/CD Recommendations

### Pre-Test Cleanup
```bash
# Clean up orphaned Ryuk containers
docker ps -aq --filter "ancestor=testcontainers/ryuk:0.5.1" | xargs -r docker rm -f

# Clean up orphaned test containers
docker ps -aq --filter "label=com.jonnyzzz.mcpSteroid.test=true" | xargs -r docker rm -f
```

### Post-Test Cleanup
```bash
# Ensure all test containers are removed
docker ps -aq --filter "label=com.jonnyzzz.mcpSteroid.test=true" | xargs -r docker rm -f
```

## Advantages Over JVM Shutdown Hooks

| Scenario | JVM Shutdown Hook | Ryuk Socket |
|----------|------------------|-------------|
| Normal exit | ✅ Works | ✅ Works |
| SIGTERM | ✅ Works | ✅ Works |
| SIGKILL | ❌ Doesn't run | ✅ Works (OS closes socket) |
| Hard crash | ❌ Doesn't run | ✅ Works (OS closes socket) |
| Exception | ✅ Works | ✅ Works |

## References

- [testcontainers/ryuk](https://github.com/testcontainers/moby-ryuk)
- [Testcontainers Java](https://github.com/testcontainers/testcontainers-java)
- [Ryuk Docker Hub](https://hub.docker.com/r/testcontainers/ryuk)

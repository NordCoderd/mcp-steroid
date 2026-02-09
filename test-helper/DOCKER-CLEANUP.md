# Docker Container Cleanup Mechanism

This document describes the custom Docker container cleanup mechanism implemented in the test-helper module.

## Overview

The cleanup mechanism uses a **custom reaper container** (Docker CLI + socat) with socket-based communication to ensure Docker containers are cleaned up even when the test process crashes or is killed with SIGKILL.

## How It Works

### Socket-Based Cleanup (Handles SIGKILL)

1. **Reaper Container**: Builds and starts `mcp-steroid-reaper` with Docker socket mounted
2. **TCP Connection**: Java process connects to the reaper on port 8080
3. **Ping Messages**: Java sends "ping" every 1 second over the socket
4. **Registration**: Container IDs are registered via `container=<id>` messages
5. **Death Detection**: If no ping for 3 seconds OR connection breaks, reaper kills all registered containers
6. **OS-Level**: When process dies (even SIGKILL), OS closes the socket automatically

### Architecture

```
Test Process                    Reaper Container
    |                                |
    |------ Connect TCP ------------>|
    |                                |
    |------ container=<id1> -------->|
    |------ container=<id2> -------->|
    |                                |
    |------ ping ------------------>| (every 1 second)
    |------ ping ------------------>|
    |                                |
    [SIGKILL / Crash]                |
    X (socket closed by OS)          |
                                     | (3 sec timeout)
                                     |---> docker kill <containers>
                                     |---> exit
```

## Components

### 1. DockerReaper

Kotlin `object` that manages the reaper container lifecycle and socket communication:

- Builds and starts the reaper container using `DockerDriver` and `startContainerDriver`
- Connects via TCP socket with retries
- Sends ping messages every 1 second via a coroutine
- Consumes container IDs from a `Channel<String>(128)` buffer
- Filters out the reaper's own container ID (it exits on its own after cleanup)
- No mutable fields — socket, writer, lifetime are local to `start()` and captured by coroutines
- All background work runs on `Dispatchers.IO` (daemon threads)

### 2. Reaper Container (Dockerfile + reaper.sh)

Custom Docker image based on `docker:27-cli` with `socat`:

- Listens on port 8080 via `socat -T 3` (3-second inactivity timeout)
- Reads line-based protocol: `container=<id>` adds to kill list, `ping` is ignored
- On timeout or connection loss, kills all registered containers (skipping itself)
- Exits after cleanup

### 3. ContainerDriver Integration

`startContainerDriver()` automatically calls `DockerReaper.registerContainer()` for every container it starts. The reaper is started implicitly on first registration.

## Usage

```kotlin
val lifetime = CloseableStackHost()
val driver = DockerDriver(workDir, "TEST")

// Start container - reaper automatically started on first registration
val containerId = driver.startContainer(
    lifetime = lifetime,
    imageName = "alpine:latest",
    cmd = listOf("sleep", "infinity")
)

// Container is:
// 1. Registered with reaper via socket protocol
// 2. Monitored by ping messages
// 3. Registered with CloseableStack for normal cleanup

// Normal cleanup
lifetime.closeAllStacks()

// If process crashes/SIGKILL:
// - OS closes socket
// - Reaper detects loss after 3 seconds
// - Reaper kills all registered containers
// - Reaper exits
```

## Protocol

### Line-Based Communication

**Registration**:
```
Client: container=<containerId>
```

**Ping Loop**:
```
Client: ping
Client: ping
Client: ping
...
```

**Cleanup Trigger**:
- No message for 3 seconds (`socat -T 3`)
- Socket closed/disconnected
- Reaper executes: `docker kill <id>` + `docker rm -f <id>` for each registered container

## Testing

See `DockerReaperTest.kt` for automated tests and `ManualSigkillTest.kt` for manual SIGKILL verification.

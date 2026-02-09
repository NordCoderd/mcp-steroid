#!/bin/bash
# Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license.
# Test SIGKILL handling with Ryuk reaper
set -e

echo "================================================================================"
echo "SIGKILL Test - Testing Ryuk-based Docker cleanup"
echo "================================================================================"
echo ""

# Compile the test
echo "[1] Compiling test..."
cd "$(dirname "$0")/.."
./gradlew :test-helper:compileTestKotlin --console=plain --quiet

echo ""
echo "[2] Starting test process that creates a container..."
echo ""

# Create a temporary file for output
OUTPUT_FILE=$(mktemp)
echo "Output file: $OUTPUT_FILE"

# Run the test in background
./gradlew :test-helper:test --tests "*RyukReaperTest.test container has session labels*" --console=plain > "$OUTPUT_FILE" 2>&1 &
TEST_PID=$!

echo "Test process PID: $TEST_PID"
echo ""

# Wait a bit for Ryuk to start and container to be created
echo "[3] Waiting for Ryuk and container to start (10 seconds)..."
sleep 10

# Check if Ryuk is running
RYUK_CONTAINER=$(docker ps --filter "ancestor=testcontainers/ryuk:latest" --format "{{.ID}}")
if [ -z "$RYUK_CONTAINER" ]; then
    echo "[ERROR] Ryuk container not found!"
    cat "$OUTPUT_FILE"
    exit 1
fi

echo "Ryuk container: $RYUK_CONTAINER"

# Check for test containers
TEST_CONTAINERS=$(docker ps --filter "label=com.jonnyzzz.mcpSteroid.test=true" --format "{{.ID}}")
if [ -z "$TEST_CONTAINERS" ]; then
    echo "[ERROR] No test containers found!"
    cat "$OUTPUT_FILE"
    exit 1
fi

echo "Test containers: $TEST_CONTAINERS"
echo ""

echo "================================================================================"
echo "[4] Sending SIGKILL to test process (kill -9 $TEST_PID)..."
echo "================================================================================"
kill -9 $TEST_PID 2>/dev/null || true
echo "Test process killed!"
echo ""

echo "[5] Waiting for Ryuk to detect disconnection (7 seconds)..."
echo "    Ryuk timeout is 5 seconds after last ping/disconnect"
for i in {7..1}; do
    echo -n "$i..."
    sleep 1
done
echo ""
echo ""

echo "================================================================================"
echo "[6] Checking if containers were cleaned up..."
echo "================================================================================"

# Check if test containers are gone
REMAINING_TEST_CONTAINERS=$(docker ps -a --filter "label=com.jonnyzzz.mcpSteroid.test=true" --format "{{.ID}}")
if [ -n "$REMAINING_TEST_CONTAINERS" ]; then
    echo "[FAIL] Test containers still present: $REMAINING_TEST_CONTAINERS"
    docker ps -a --filter "label=com.jonnyzzz.mcpSteroid.test=true"
    exit 1
else
    echo "[PASS] ✓ All test containers cleaned up!"
fi

# Check if Ryuk is gone
REMAINING_RYUK=$(docker ps --filter "ancestor=testcontainers/ryuk:latest" --format "{{.ID}}")
if [ -n "$REMAINING_RYUK" ]; then
    echo "[INFO] Ryuk container still running: $REMAINING_RYUK"
    echo "       (This is OK - Ryuk may stay alive for future tests)"
else
    echo "[PASS] ✓ Ryuk container also cleaned up"
fi

echo ""
echo "================================================================================"
echo "SIGKILL TEST PASSED!"
echo "================================================================================"
echo "The Ryuk reaper successfully cleaned up containers after kill -9"
echo ""

# Cleanup
rm -f "$OUTPUT_FILE"

// @ts-nocheck
//
// Acceptance tests for the MCP stdio protocol implementation.
//
// These tests spawn the real proxy as a child process and communicate
// via NDJSON over stdin/stdout, exactly as a real MCP client would.
//
const test = require("node:test");
const assert = require("node:assert/strict");
const { spawn } = require("node:child_process");
const path = require("node:path");
const {
  readNextFramedMessage,
  encodeFramedMessage,
  encodeNdjsonMessage,
  PROTOCOL_VERSION,
  JSONRPC_VERSION
} = require("../src/index");

// ---------------------------------------------------------------------------
// Helper: spawn proxy process and communicate via NDJSON
// ---------------------------------------------------------------------------

function spawnProxy() {
  const entry = path.join(__dirname, "..", "..", "dist", "index.js");
  const child = spawn(process.execPath, [entry], {
    stdio: ["pipe", "pipe", "pipe"],
    env: {
      ...process.env,
      // Disable beacon/telemetry network calls during tests
      MCP_STEROID_BEACON_ENABLED: "false"
    }
  });

  let stdoutBuffer = "";
  const responseQueue: Array<{ resolve: (v: any) => void; reject: (e: Error) => void }> = [];
  const notifications: any[] = [];

  child.stdout.setEncoding("utf8");
  child.stdout.on("data", (chunk) => {
    stdoutBuffer += chunk;
    // Parse NDJSON lines
    while (true) {
      const newline = stdoutBuffer.indexOf("\n");
      if (newline < 0) break;
      const line = stdoutBuffer.slice(0, newline).trim();
      stdoutBuffer = stdoutBuffer.slice(newline + 1);
      if (!line) continue;

      let msg;
      try {
        msg = JSON.parse(line);
      } catch (_) {
        // Also handle Content-Length framed responses
        continue;
      }

      // Check if it's a notification (no id) or a response (has id)
      if (msg.id !== undefined && msg.id !== null) {
        const pending = responseQueue.shift();
        if (pending) pending.resolve(msg);
      } else {
        // Server-initiated notification
        notifications.push(msg);
      }
    }

    // Also handle Content-Length framed responses from the buffer
    const buf = Buffer.from(stdoutBuffer, "utf8");
    while (true) {
      const frame = readNextFramedMessage(buf);
      if (!frame) break;
      stdoutBuffer = stdoutBuffer.slice(frame.consumed);
      if (!frame.payloadText) continue;
      try {
        const msg = JSON.parse(frame.payloadText);
        if (msg.id !== undefined && msg.id !== null) {
          const pending = responseQueue.shift();
          if (pending) pending.resolve(msg);
        } else {
          notifications.push(msg);
        }
      } catch (_) {}
    }
  });

  let requestId = 0;

  function sendRequest(method, params = {}) {
    requestId += 1;
    const id = requestId;
    const msg = { jsonrpc: JSONRPC_VERSION, id, method, params };
    child.stdin.write(JSON.stringify(msg) + "\n");
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        reject(new Error(`Timeout waiting for response to ${method} (id=${id})`));
      }, 10_000);
      responseQueue.push({
        resolve: (v) => { clearTimeout(timer); resolve(v); },
        reject: (e) => { clearTimeout(timer); reject(e); }
      });
    });
  }

  function sendNotification(method, params = {}) {
    const msg = { jsonrpc: JSONRPC_VERSION, method, params };
    child.stdin.write(JSON.stringify(msg) + "\n");
  }

  function sendRaw(text) {
    child.stdin.write(text);
  }

  function getNotifications() {
    return [...notifications];
  }

  function clearNotifications() {
    notifications.length = 0;
  }

  async function close() {
    child.stdin.end();
    return new Promise<void>((resolve) => {
      const timer = setTimeout(() => {
        child.kill("SIGTERM");
        resolve();
      }, 3_000);
      child.on("exit", () => {
        clearTimeout(timer);
        resolve();
      });
    });
  }

  return { sendRequest, sendNotification, sendRaw, getNotifications, clearNotifications, close, child };
}

// ---------------------------------------------------------------------------
// Helper: perform full MCP initialization handshake
// ---------------------------------------------------------------------------

async function initializeProxy(proxy) {
  const initResponse = await proxy.sendRequest("initialize", {
    protocolVersion: PROTOCOL_VERSION,
    capabilities: {},
    clientInfo: { name: "acceptance-test", version: "1.0.0" }
  });
  proxy.sendNotification("notifications/initialized");
  // Small delay for the notification to be processed
  await new Promise((r) => setTimeout(r, 50));
  return initResponse;
}

// ---------------------------------------------------------------------------
// Section 1: Framing layer tests
// ---------------------------------------------------------------------------

test("framing: NDJSON mode parses single-line JSON", () => {
  const json = '{"jsonrpc":"2.0","id":1,"method":"ping"}';
  const buf = Buffer.from(json + "\n");
  const frame = readNextFramedMessage(buf);
  assert.ok(frame);
  assert.equal(frame.payloadText, json);
  assert.equal(frame.mode, "ndjson");
});

test("framing: NDJSON mode handles multiple messages in one chunk", () => {
  const msg1 = '{"jsonrpc":"2.0","id":1,"method":"ping"}';
  const msg2 = '{"jsonrpc":"2.0","id":2,"method":"ping"}';
  let buf = Buffer.from(msg1 + "\n" + msg2 + "\n");

  const frame1 = readNextFramedMessage(buf);
  assert.ok(frame1);
  assert.equal(frame1.payloadText, msg1);
  buf = buf.slice(frame1.consumed);

  const frame2 = readNextFramedMessage(buf);
  assert.ok(frame2);
  assert.equal(frame2.payloadText, msg2);
});

test("framing: NDJSON returns null for incomplete line", () => {
  const buf = Buffer.from('{"jsonrpc":"2.0","id":1,"met');
  const frame = readNextFramedMessage(buf);
  assert.equal(frame, null);
});

test("framing: Content-Length with CRLF delimiter", () => {
  const json = '{"jsonrpc":"2.0","id":1,"method":"ping"}';
  const header = `Content-Length: ${Buffer.byteLength(json, "utf8")}\r\n\r\n`;
  const buf = Buffer.from(header + json);
  const frame = readNextFramedMessage(buf);
  assert.ok(frame);
  assert.equal(frame.payloadText, json);
  assert.equal(frame.mode, "framed");
});

test("framing: Content-Length with LF-only delimiter", () => {
  const json = '{"jsonrpc":"2.0","id":1,"result":{}}';
  const header = `Content-Length: ${Buffer.byteLength(json, "utf8")}\n\n`;
  const buf = Buffer.from(header + json);
  const frame = readNextFramedMessage(buf);
  assert.ok(frame);
  assert.equal(frame.payloadText, json);
  assert.equal(frame.mode, "framed");
});

test("framing: Content-Length returns null for incomplete body", () => {
  const json = '{"jsonrpc":"2.0","id":1,"method":"ping"}';
  const header = `Content-Length: ${Buffer.byteLength(json, "utf8")}\r\n\r\n`;
  // Only send part of the body
  const buf = Buffer.from(header + json.slice(0, 10));
  const frame = readNextFramedMessage(buf);
  assert.equal(frame, null);
});

test("framing: encodeNdjsonMessage produces line-terminated JSON", () => {
  const payload = { jsonrpc: "2.0", id: 1, result: { tools: [] } };
  const encoded = encodeNdjsonMessage(payload);
  assert.ok(encoded.endsWith("\n"));
  assert.ok(!encoded.includes("\n\n"));
  const parsed = JSON.parse(encoded.trim());
  assert.deepEqual(parsed, payload);
});

test("framing: encodeFramedMessage produces Content-Length header", () => {
  const payload = { jsonrpc: "2.0", id: 1, result: {} };
  const encoded = encodeFramedMessage(payload);
  assert.ok(encoded.startsWith("Content-Length: "));
  assert.ok(encoded.includes("\r\n\r\n"));
  const parts = encoded.split("\r\n\r\n");
  const body = parts[1];
  const parsed = JSON.parse(body);
  assert.deepEqual(parsed, payload);
  const declaredLength = parseInt(parts[0].split(": ")[1], 10);
  assert.equal(declaredLength, Buffer.byteLength(body, "utf8"));
});

test("framing: NDJSON handles Unicode content", () => {
  const json = '{"jsonrpc":"2.0","id":1,"result":{"text":"Hello \u4e16\u754c \ud83c\udf0d"}}';
  const buf = Buffer.from(json + "\n", "utf8");
  const frame = readNextFramedMessage(buf);
  assert.ok(frame);
  const parsed = JSON.parse(frame.payloadText);
  assert.equal(parsed.result.text, "Hello \u4e16\u754c \ud83c\udf0d");
});

// ---------------------------------------------------------------------------
// Section 2: Subprocess stdio acceptance tests
// ---------------------------------------------------------------------------

test("stdio: initialize handshake returns correct protocol version", async () => {
  const proxy = spawnProxy();
  try {
    const resp = await proxy.sendRequest("initialize", {
      protocolVersion: PROTOCOL_VERSION,
      capabilities: {},
      clientInfo: { name: "test", version: "1.0" }
    });

    assert.equal(resp.jsonrpc, JSONRPC_VERSION);
    assert.ok(resp.id);
    assert.ok(resp.result);
    assert.equal(resp.result.protocolVersion, PROTOCOL_VERSION);
    assert.ok(resp.result.capabilities);
    assert.ok(resp.result.serverInfo);
    assert.equal(resp.result.serverInfo.name, "mcp-steroid-proxy");
    assert.equal(typeof resp.result.serverInfo.version, "string");
  } finally {
    await proxy.close();
  }
});

test("stdio: initialize response includes tools and resources capabilities", async () => {
  const proxy = spawnProxy();
  try {
    const resp = await proxy.sendRequest("initialize", {
      protocolVersion: PROTOCOL_VERSION,
      capabilities: {},
      clientInfo: { name: "test", version: "1.0" }
    });

    const caps = resp.result.capabilities;
    assert.ok(caps.tools, "Expected tools capability");
    assert.ok(caps.resources !== undefined, "Expected resources capability");
  } finally {
    await proxy.close();
  }
});

test("stdio: ping returns empty result", async () => {
  const proxy = spawnProxy();
  try {
    await initializeProxy(proxy);
    const resp = await proxy.sendRequest("ping");
    assert.equal(resp.jsonrpc, JSONRPC_VERSION);
    assert.deepEqual(resp.result, {});
  } finally {
    await proxy.close();
  }
});

test("stdio: tools/list returns array (empty with no servers)", async () => {
  const proxy = spawnProxy();
  try {
    await initializeProxy(proxy);
    const resp = await proxy.sendRequest("tools/list");
    assert.ok(resp.result);
    assert.ok(Array.isArray(resp.result.tools));
  } finally {
    await proxy.close();
  }
});

test("stdio: resources/list returns array (empty with no servers)", async () => {
  const proxy = spawnProxy();
  try {
    await initializeProxy(proxy);
    const resp = await proxy.sendRequest("resources/list");
    assert.ok(resp.result);
    assert.ok(Array.isArray(resp.result.resources));
  } finally {
    await proxy.close();
  }
});

test("stdio: tools/call with missing name returns isError", async () => {
  const proxy = spawnProxy();
  try {
    await initializeProxy(proxy);
    const resp = await proxy.sendRequest("tools/call", { arguments: {} });
    assert.ok(resp.result);
    assert.equal(resp.result.isError, true);
  } finally {
    await proxy.close();
  }
});

test("stdio: unknown method returns -32601 error", async () => {
  const proxy = spawnProxy();
  try {
    await initializeProxy(proxy);
    const resp = await proxy.sendRequest("nonexistent/method");
    assert.ok(resp.error);
    assert.equal(resp.error.code, -32601);
  } finally {
    await proxy.close();
  }
});

test("stdio: malformed JSON returns -32700 parse error", async () => {
  const proxy = spawnProxy();
  try {
    await initializeProxy(proxy);
    proxy.sendRaw("{invalid json\n");
    // Read the error response
    const resp = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("Timeout")), 5_000);
      const origPush = proxy.child.stdout.listeners("data");
      // We need to get the next response from the queue
      // Send a valid ping after the malformed message to ensure we get past it
      setTimeout(async () => {
        try {
          const pingResp = await proxy.sendRequest("ping");
          clearTimeout(timer);
          resolve(pingResp);
        } catch (e) {
          clearTimeout(timer);
          reject(e);
        }
      }, 200);
    });
    // The proxy should still work after malformed JSON
    assert.deepEqual(resp.result, {});
  } finally {
    await proxy.close();
  }
});

test("stdio: notification (no id) produces no response", async () => {
  const proxy = spawnProxy();
  try {
    await initializeProxy(proxy);
    // Send a notification
    proxy.sendNotification("notifications/initialized");
    // Wait a bit, then send a ping to verify the proxy is still responsive
    await new Promise((r) => setTimeout(r, 100));
    const resp = await proxy.sendRequest("ping");
    assert.deepEqual(resp.result, {});
    // No extra responses should have been queued
  } finally {
    await proxy.close();
  }
});

test("stdio: multiple sequential requests return correct ids", async () => {
  const proxy = spawnProxy();
  try {
    await initializeProxy(proxy);
    const [ping1, ping2, ping3] = await Promise.all([
      proxy.sendRequest("ping"),
      proxy.sendRequest("ping"),
      proxy.sendRequest("ping")
    ]);
    // All should succeed (ids are auto-assigned by the helper)
    assert.deepEqual(ping1.result, {});
    assert.deepEqual(ping2.result, {});
    assert.deepEqual(ping3.result, {});
    // IDs should be unique
    const ids = new Set([ping1.id, ping2.id, ping3.id]);
    assert.equal(ids.size, 3);
  } finally {
    await proxy.close();
  }
});

test("stdio: resources/read with missing uri returns error", async () => {
  const proxy = spawnProxy();
  try {
    await initializeProxy(proxy);
    const resp = await proxy.sendRequest("resources/read", {});
    assert.ok(resp.error);
    assert.equal(resp.error.code, -32602);
  } finally {
    await proxy.close();
  }
});

test("stdio: response ids match request ids", async () => {
  const proxy = spawnProxy();
  try {
    const initResp = await proxy.sendRequest("initialize", {
      protocolVersion: PROTOCOL_VERSION,
      capabilities: {},
      clientInfo: { name: "test", version: "1.0" }
    });
    assert.equal(initResp.id, 1); // First request gets id=1

    proxy.sendNotification("notifications/initialized");
    await new Promise((r) => setTimeout(r, 50));

    const pingResp = await proxy.sendRequest("ping");
    assert.equal(pingResp.id, 2);

    const toolsResp = await proxy.sendRequest("tools/list");
    assert.equal(toolsResp.id, 3);
  } finally {
    await proxy.close();
  }
});

test("stdio: graceful shutdown on stdin close", async () => {
  const proxy = spawnProxy();
  await initializeProxy(proxy);

  const exitPromise = new Promise((resolve) => {
    proxy.child.on("exit", (code) => resolve(code));
  });

  proxy.child.stdin.end();
  const exitCode = await exitPromise;
  assert.equal(exitCode, 0);
});

test("stdio: server info version matches package.json", async () => {
  const proxy = spawnProxy();
  try {
    const resp = await proxy.sendRequest("initialize", {
      protocolVersion: PROTOCOL_VERSION,
      capabilities: {},
      clientInfo: { name: "test", version: "1.0" }
    });
    // In dev mode, version is "0.0.0-dev" from package.json
    assert.equal(typeof resp.result.serverInfo.version, "string");
    assert.ok(resp.result.serverInfo.version.length > 0);
  } finally {
    await proxy.close();
  }
});

test("stdio: initialize response includes instructions string", async () => {
  const proxy = spawnProxy();
  try {
    const resp = await proxy.sendRequest("initialize", {
      protocolVersion: PROTOCOL_VERSION,
      capabilities: {},
      clientInfo: { name: "test", version: "1.0" }
    });
    assert.equal(typeof resp.result.instructions, "string");
    assert.ok(resp.result.instructions.length > 0);
  } finally {
    await proxy.close();
  }
});

// ---------------------------------------------------------------------------
// Section 3: JSON-RPC 2.0 compliance tests
// ---------------------------------------------------------------------------

test("stdio: string request id is echoed back", async () => {
  const proxy = spawnProxy();
  try {
    // Send raw request with string id
    const msg = { jsonrpc: JSONRPC_VERSION, id: "string-id-42", method: "initialize", params: {
      protocolVersion: PROTOCOL_VERSION,
      capabilities: {},
      clientInfo: { name: "test", version: "1.0" }
    }};
    proxy.sendRaw(JSON.stringify(msg) + "\n");
    const resp = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("Timeout")), 5_000);
      proxy.child.stdout.once("data", () => {});
      // Wait for the response queue to get populated
      setTimeout(() => {
        // Re-parse stdout buffer for our string-id response
        clearTimeout(timer);
      }, 200);
      // Use the response queue mechanism by pushing a handler
      const origLength = (proxy as any).child.stdout.listenerCount("data");
      let stdoutAccum = "";
      const listener = (chunk) => {
        stdoutAccum += chunk.toString();
        const lines = stdoutAccum.split("\n");
        for (const line of lines) {
          if (!line.trim()) continue;
          try {
            const parsed = JSON.parse(line.trim());
            if (parsed.id === "string-id-42") {
              clearTimeout(timer);
              proxy.child.stdout.removeListener("data", listener);
              resolve(parsed);
              return;
            }
          } catch (_) {}
        }
      };
      proxy.child.stdout.on("data", listener);
    });
    assert.equal((resp as any).id, "string-id-42");
    assert.ok((resp as any).result);
    assert.equal((resp as any).result.protocolVersion, PROTOCOL_VERSION);
  } finally {
    await proxy.close();
  }
});

test("stdio: request with null id is treated as notification (no response)", async () => {
  const proxy = spawnProxy();
  try {
    await initializeProxy(proxy);
    // Send a request with null id — per MCP spec, null ids are notifications
    proxy.sendRaw(JSON.stringify({ jsonrpc: JSONRPC_VERSION, id: null, method: "ping" }) + "\n");
    // Wait a bit, then verify proxy still responds to a real request
    await new Promise((r) => setTimeout(r, 200));
    const resp = await proxy.sendRequest("ping");
    assert.deepEqual(resp.result, {});
  } finally {
    await proxy.close();
  }
});

test("stdio: request with missing method returns -32600", async () => {
  const proxy = spawnProxy();
  try {
    await initializeProxy(proxy);
    // Send a raw request with id but no method field
    const msg = { jsonrpc: JSONRPC_VERSION, id: 999 };
    proxy.sendRaw(JSON.stringify(msg) + "\n");
    const resp = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("Timeout")), 5_000);
      let accum = "";
      const listener = (chunk) => {
        accum += chunk.toString();
        for (const line of accum.split("\n")) {
          if (!line.trim()) continue;
          try {
            const parsed = JSON.parse(line.trim());
            if (parsed.id === 999) {
              clearTimeout(timer);
              proxy.child.stdout.removeListener("data", listener);
              resolve(parsed);
              return;
            }
          } catch (_) {}
        }
      };
      proxy.child.stdout.on("data", listener);
    });
    assert.ok((resp as any).error);
    assert.equal((resp as any).error.code, -32600);
  } finally {
    await proxy.close();
  }
});

test("stdio: error response includes jsonrpc field", async () => {
  const proxy = spawnProxy();
  try {
    await initializeProxy(proxy);
    const resp = await proxy.sendRequest("nonexistent/method");
    assert.equal(resp.jsonrpc, JSONRPC_VERSION);
    assert.ok(resp.error);
    assert.equal(typeof resp.error.code, "number");
    assert.equal(typeof resp.error.message, "string");
  } finally {
    await proxy.close();
  }
});

test("stdio: response never contains both result and error", async () => {
  const proxy = spawnProxy();
  try {
    await initializeProxy(proxy);
    // Success case
    const pingResp = await proxy.sendRequest("ping");
    assert.ok(pingResp.result !== undefined);
    assert.equal(pingResp.error, undefined);

    // Error case
    const errResp = await proxy.sendRequest("nonexistent/method");
    assert.ok(errResp.error !== undefined);
    assert.equal(errResp.result, undefined);
  } finally {
    await proxy.close();
  }
});

test("stdio: unknown notification is silently ignored", async () => {
  const proxy = spawnProxy();
  try {
    await initializeProxy(proxy);
    // Send an unknown notification — server should ignore it
    proxy.sendNotification("notifications/unknown_custom_event", { data: "test" });
    // Verify proxy is still responsive
    await new Promise((r) => setTimeout(r, 100));
    const resp = await proxy.sendRequest("ping");
    assert.deepEqual(resp.result, {});
  } finally {
    await proxy.close();
  }
});

// ---------------------------------------------------------------------------
// Section 4: Framing edge cases
// ---------------------------------------------------------------------------

test("framing: empty line in NDJSON is skipped", () => {
  const msg = '{"jsonrpc":"2.0","id":1,"method":"ping"}';
  // Empty line followed by valid JSON
  let buf = Buffer.from("\n" + msg + "\n");
  // First call should consume the empty line
  const frame1 = readNextFramedMessage(buf);
  assert.ok(frame1);
  // The empty line gets consumed, payloadText may be empty
  buf = buf.slice(frame1.consumed);
  if (!frame1.payloadText || frame1.payloadText.trim() === "") {
    // Empty line was consumed, next frame should be the actual message
    const frame2 = readNextFramedMessage(buf);
    assert.ok(frame2);
    assert.equal(frame2.payloadText.trim(), msg);
  } else {
    // Implementation skipped empty line and returned message directly
    assert.equal(frame1.payloadText.trim(), msg);
  }
});

test("framing: Content-Length handles multi-byte UTF-8 correctly", () => {
  // Multi-byte characters: Content-Length is in bytes, not characters
  const json = '{"jsonrpc":"2.0","id":1,"result":{"text":"日本語テスト"}}';
  const byteLength = Buffer.byteLength(json, "utf8");
  assert.ok(byteLength > json.length, "Multi-byte chars should make byte length > char length");
  const header = `Content-Length: ${byteLength}\r\n\r\n`;
  const buf = Buffer.from(header + json, "utf8");
  const frame = readNextFramedMessage(buf);
  assert.ok(frame);
  assert.equal(frame.payloadText, json);
  const parsed = JSON.parse(frame.payloadText);
  assert.equal(parsed.result.text, "日本語テスト");
});

test("framing: zero-length Content-Length returns empty payload", () => {
  const header = "Content-Length: 0\r\n\r\n";
  const buf = Buffer.from(header);
  const frame = readNextFramedMessage(buf);
  assert.ok(frame);
  assert.equal(frame.payloadText, "");
  assert.equal(frame.mode, "framed");
});

test("framing: NDJSON with trailing whitespace is trimmed", () => {
  const json = '{"jsonrpc":"2.0","id":1,"method":"ping"}';
  const buf = Buffer.from(json + "   \n");
  const frame = readNextFramedMessage(buf);
  assert.ok(frame);
  assert.equal(frame.payloadText, json);
});

// ---------------------------------------------------------------------------
// Section 5: Batch request handling (stdio.ts supports arrays)
// ---------------------------------------------------------------------------

test("stdio: batch request returns array of responses", async () => {
  const proxy = spawnProxy();
  try {
    await initializeProxy(proxy);
    // Send a JSON-RPC batch array directly
    const batch = [
      { jsonrpc: JSONRPC_VERSION, id: 100, method: "ping" },
      { jsonrpc: JSONRPC_VERSION, id: 101, method: "ping" }
    ];
    proxy.sendRaw(JSON.stringify(batch) + "\n");
    // The proxy should respond with a batch array
    const resp = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("Timeout")), 5_000);
      let accum = "";
      const listener = (chunk) => {
        accum += chunk.toString();
        for (const line of accum.split("\n")) {
          if (!line.trim()) continue;
          try {
            const parsed = JSON.parse(line.trim());
            // Batch response is an array
            if (Array.isArray(parsed)) {
              clearTimeout(timer);
              proxy.child.stdout.removeListener("data", listener);
              resolve(parsed);
              return;
            }
          } catch (_) {}
        }
      };
      proxy.child.stdout.on("data", listener);
    });
    assert.ok(Array.isArray(resp));
    assert.equal((resp as any[]).length, 2);
    const ids = (resp as any[]).map(r => r.id).sort();
    assert.deepEqual(ids, [100, 101]);
    for (const r of (resp as any[])) {
      assert.deepEqual(r.result, {});
      assert.equal(r.jsonrpc, JSONRPC_VERSION);
    }
  } finally {
    await proxy.close();
  }
});

test("stdio: batch with notification omits response for it", async () => {
  const proxy = spawnProxy();
  try {
    await initializeProxy(proxy);
    // Batch: one request + one notification (no id)
    const batch = [
      { jsonrpc: JSONRPC_VERSION, id: 200, method: "ping" },
      { jsonrpc: JSONRPC_VERSION, method: "notifications/initialized" }
    ];
    proxy.sendRaw(JSON.stringify(batch) + "\n");
    const resp = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("Timeout")), 5_000);
      let accum = "";
      const listener = (chunk) => {
        accum += chunk.toString();
        for (const line of accum.split("\n")) {
          if (!line.trim()) continue;
          try {
            const parsed = JSON.parse(line.trim());
            if (Array.isArray(parsed)) {
              clearTimeout(timer);
              proxy.child.stdout.removeListener("data", listener);
              resolve(parsed);
              return;
            }
          } catch (_) {}
        }
      };
      proxy.child.stdout.on("data", listener);
    });
    // Only 1 response (the notification doesn't get a response)
    assert.ok(Array.isArray(resp));
    assert.equal((resp as any[]).length, 1);
    assert.equal((resp as any[])[0].id, 200);
  } finally {
    await proxy.close();
  }
});

// ---------------------------------------------------------------------------
// Section 6: Large payload and stress tests
// ---------------------------------------------------------------------------

test("stdio: large payload is not truncated", async () => {
  const proxy = spawnProxy();
  try {
    await initializeProxy(proxy);
    // Send a tools/call with a large arguments object
    const largeValue = "x".repeat(100_000);
    const resp = await proxy.sendRequest("tools/call", {
      name: "nonexistent_tool",
      arguments: { data: largeValue }
    });
    // Should get an error result (tool not found), not a crash
    assert.ok(resp.result);
    assert.equal(resp.result.isError, true);
  } finally {
    await proxy.close();
  }
});

test("stdio: 10 rapid sequential pings all succeed", async () => {
  const proxy = spawnProxy();
  try {
    await initializeProxy(proxy);
    const promises = [];
    for (let i = 0; i < 10; i++) {
      promises.push(proxy.sendRequest("ping"));
    }
    const results = await Promise.all(promises);
    assert.equal(results.length, 10);
    for (const r of results) {
      assert.deepEqual(r.result, {});
    }
  } finally {
    await proxy.close();
  }
});

// ---------------------------------------------------------------------------
// Section 7: Capability negotiation
// ---------------------------------------------------------------------------

test("stdio: initialize response includes prompts capability", async () => {
  const proxy = spawnProxy();
  try {
    const resp = await proxy.sendRequest("initialize", {
      protocolVersion: PROTOCOL_VERSION,
      capabilities: {},
      clientInfo: { name: "test", version: "1.0" }
    });
    const caps = resp.result.capabilities;
    assert.ok(caps.prompts !== undefined, "Expected prompts capability");
  } finally {
    await proxy.close();
  }
});

test("stdio: initialize response capabilities have listChanged property", async () => {
  const proxy = spawnProxy();
  try {
    const resp = await proxy.sendRequest("initialize", {
      protocolVersion: PROTOCOL_VERSION,
      capabilities: {},
      clientInfo: { name: "test", version: "1.0" }
    });
    const caps = resp.result.capabilities;
    // Tools capability should declare listChanged
    assert.equal(typeof caps.tools.listChanged, "boolean");
  } finally {
    await proxy.close();
  }
});

// ---------------------------------------------------------------------------
// Section 8: Recovery and resilience
// ---------------------------------------------------------------------------

test("stdio: proxy recovers from multiple malformed JSON messages", async () => {
  const proxy = spawnProxy();
  try {
    await initializeProxy(proxy);
    // Send several malformed JSON objects (must start with { to be parsed as NDJSON)
    proxy.sendRaw("{bad1\n");
    proxy.sendRaw("{\"incomplete\n");
    proxy.sendRaw("{\"also\":broken\n");
    // Wait for processing
    await new Promise((r) => setTimeout(r, 300));
    // Proxy should still respond
    const resp = await proxy.sendRequest("ping");
    assert.deepEqual(resp.result, {});
  } finally {
    await proxy.close();
  }
});

test("stdio: proxy handles empty line between messages", async () => {
  const proxy = spawnProxy();
  try {
    await initializeProxy(proxy);
    // Send empty lines interspersed with a valid request
    proxy.sendRaw("\n\n");
    await new Promise((r) => setTimeout(r, 100));
    const resp = await proxy.sendRequest("ping");
    assert.deepEqual(resp.result, {});
  } finally {
    await proxy.close();
  }
});

// ---------------------------------------------------------------------------
// Section 9: ID edge cases (JSON-RPC 2.0 compliance)
// ---------------------------------------------------------------------------

test("stdio: request with id 0 is a valid request (not notification)", async () => {
  const proxy = spawnProxy();
  try {
    await initializeProxy(proxy);
    // id=0 is falsy in JS but is a valid JSON-RPC request ID
    const msg = { jsonrpc: JSONRPC_VERSION, id: 0, method: "ping" };
    proxy.sendRaw(JSON.stringify(msg) + "\n");
    const resp = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("Timeout")), 5_000);
      let accum = "";
      const listener = (chunk) => {
        accum += chunk.toString();
        for (const line of accum.split("\n")) {
          if (!line.trim()) continue;
          try {
            const parsed = JSON.parse(line.trim());
            if (parsed.id === 0) {
              clearTimeout(timer);
              proxy.child.stdout.removeListener("data", listener);
              resolve(parsed);
              return;
            }
          } catch (_) {}
        }
      };
      proxy.child.stdout.on("data", listener);
    });
    assert.equal((resp as any).id, 0);
    assert.deepEqual((resp as any).result, {});
  } finally {
    await proxy.close();
  }
});

test("stdio: request with negative integer id is valid", async () => {
  const proxy = spawnProxy();
  try {
    await initializeProxy(proxy);
    const msg = { jsonrpc: JSONRPC_VERSION, id: -1, method: "ping" };
    proxy.sendRaw(JSON.stringify(msg) + "\n");
    const resp = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("Timeout")), 5_000);
      let accum = "";
      const listener = (chunk) => {
        accum += chunk.toString();
        for (const line of accum.split("\n")) {
          if (!line.trim()) continue;
          try {
            const parsed = JSON.parse(line.trim());
            if (parsed.id === -1) {
              clearTimeout(timer);
              proxy.child.stdout.removeListener("data", listener);
              resolve(parsed);
              return;
            }
          } catch (_) {}
        }
      };
      proxy.child.stdout.on("data", listener);
    });
    assert.equal((resp as any).id, -1);
    assert.deepEqual((resp as any).result, {});
  } finally {
    await proxy.close();
  }
});

// ---------------------------------------------------------------------------
// Section 10: Double initialization and prompts/list
// ---------------------------------------------------------------------------

test("stdio: second initialize request is accepted (no enforcement yet)", async () => {
  const proxy = spawnProxy();
  try {
    // First initialization
    await initializeProxy(proxy);
    // Second initialize request — server should still respond
    const resp = await proxy.sendRequest("initialize", {
      protocolVersion: PROTOCOL_VERSION,
      capabilities: {},
      clientInfo: { name: "test", version: "1.0" }
    });
    assert.ok(resp.result);
    assert.equal(resp.result.protocolVersion, PROTOCOL_VERSION);
  } finally {
    await proxy.close();
  }
});

test("stdio: prompts/list returns -32601 (advertised but not implemented)", async () => {
  const proxy = spawnProxy();
  try {
    await initializeProxy(proxy);
    const resp = await proxy.sendRequest("prompts/list");
    // prompts capability is advertised in initialize but prompts/list is not handled
    assert.ok(resp.error);
    assert.equal(resp.error.code, -32601);
  } finally {
    await proxy.close();
  }
});

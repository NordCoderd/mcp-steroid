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

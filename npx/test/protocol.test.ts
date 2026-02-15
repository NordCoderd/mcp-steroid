// @ts-nocheck
const test = require("node:test");
const assert = require("node:assert/strict");

const {
  handleRpc,
  extractClientProgressToken,
  createProgressToken,
  createServerInfo,
  jsonRpcResult,
  jsonRpcError,
  PROTOCOL_VERSION,
  JSONRPC_VERSION,
  DEFAULT_CONFIG
} = require("../src/index");

// --- helpers ---

function mockRegistry(overrides = {}) {
  return {
    config: { ...DEFAULT_CONFIG, version: "0.99.0" },
    ensureFresh: async () => {},
    buildToolGroups: () => new Map(),
    buildResourceIndex: () => [],
    resourceIndex: new Map(),
    resolveServerForToolCall: () => ({ error: "No server" }),
    callTool: async () => ({ content: [{ type: "text", text: "ok" }] }),
    callRpc: async () => ({}),
    listOnlineServersByFreshness: () => [],
    resolveServerIdHint: () => null,
    ...overrides
  };
}

// --- extractClientProgressToken ---

test("extractClientProgressToken reads token from _meta", () => {
  const params = {
    name: "my_tool",
    arguments: { foo: "bar" },
    _meta: { progressToken: "client-token-42" }
  };
  assert.equal(extractClientProgressToken(params), "client-token-42");
});

test("extractClientProgressToken reads numeric token", () => {
  const params = { _meta: { progressToken: 7 } };
  assert.equal(extractClientProgressToken(params), 7);
});

test("extractClientProgressToken returns null when no _meta", () => {
  assert.equal(extractClientProgressToken({ name: "tool" }), null);
  assert.equal(extractClientProgressToken(null), null);
  assert.equal(extractClientProgressToken(undefined), null);
});

test("extractClientProgressToken returns null for non-string/number token", () => {
  assert.equal(extractClientProgressToken({ _meta: { progressToken: true } }), null);
  assert.equal(extractClientProgressToken({ _meta: { progressToken: {} } }), null);
  assert.equal(extractClientProgressToken({ _meta: {} }), null);
});

// --- createProgressToken ---

test("createProgressToken returns a string starting with npx-", () => {
  const token = createProgressToken();
  assert.equal(typeof token, "string");
  assert.ok(token.startsWith("npx-"), `Expected token to start with "npx-", got: ${token}`);
});

test("createProgressToken generates unique tokens", () => {
  const a = createProgressToken();
  const b = createProgressToken();
  assert.notEqual(a, b);
});

// --- createServerInfo ---

test("createServerInfo returns name and version", () => {
  const info = createServerInfo({ version: "1.2.3" });
  assert.equal(info.name, "mcp-steroid-proxy");
  assert.equal(info.version, "1.2.3");
});

test("createServerInfo falls back to 0.1.0", () => {
  const info = createServerInfo({});
  assert.equal(info.version, "0.1.0");
});

// --- jsonRpcResult / jsonRpcError ---

test("jsonRpcResult wraps result with id", () => {
  const msg = jsonRpcResult(5, { tools: [] });
  assert.equal(msg.jsonrpc, JSONRPC_VERSION);
  assert.equal(msg.id, 5);
  assert.deepEqual(msg.result, { tools: [] });
  assert.equal(msg.error, undefined);
});

test("jsonRpcError wraps error with code and message", () => {
  const msg = jsonRpcError(3, -32601, "Method not found");
  assert.equal(msg.jsonrpc, JSONRPC_VERSION);
  assert.equal(msg.id, 3);
  assert.deepEqual(msg.error, { code: -32601, message: "Method not found" });
  assert.equal(msg.result, undefined);
});

// --- handleRpc: initialize ---

test("handleRpc initialize returns protocol version and capabilities", async () => {
  const registry = mockRegistry();
  const result = await handleRpc("initialize", {
    protocolVersion: PROTOCOL_VERSION,
    capabilities: {},
    clientInfo: { name: "test-client", version: "1.0" }
  }, registry);

  assert.equal(result.protocolVersion, PROTOCOL_VERSION);
  assert.ok(result.capabilities);
  assert.ok(result.capabilities.tools);
  assert.ok(result.serverInfo);
  assert.equal(result.serverInfo.name, "mcp-steroid-proxy");
  assert.equal(result.serverInfo.version, "0.99.0");
  assert.equal(typeof result.instructions, "string");
});

test("handleRpc initialize does not call ensureFresh", async () => {
  let ensureFreshCalled = false;
  const registry = mockRegistry({
    ensureFresh: async () => { ensureFreshCalled = true; }
  });
  await handleRpc("initialize", {}, registry);
  assert.equal(ensureFreshCalled, false);
});

// --- handleRpc: ping ---

test("handleRpc ping returns empty object", async () => {
  const registry = mockRegistry();
  const result = await handleRpc("ping", {}, registry);
  assert.deepEqual(result, {});
});

test("handleRpc ping does not call ensureFresh", async () => {
  let called = false;
  const registry = mockRegistry({
    ensureFresh: async () => { called = true; }
  });
  await handleRpc("ping", {}, registry);
  assert.equal(called, false);
});

// --- handleRpc: tools/list ---

test("handleRpc tools/list returns merged tools", async () => {
  const tool = {
    name: "my_tool",
    description: "A tool",
    inputSchema: { type: "object", properties: {}, required: [] }
  };
  const registry = mockRegistry({
    buildToolGroups: () => {
      const m = new Map();
      m.set("my_tool", [tool]);
      return m;
    }
  });
  const result = await handleRpc("tools/list", {}, registry);
  assert.ok(Array.isArray(result.tools));
  assert.ok(result.tools.length >= 1);
  assert.equal(result.tools[0].name, "my_tool");
});

test("handleRpc tools/list calls ensureFresh", async () => {
  let called = false;
  const registry = mockRegistry({
    ensureFresh: async () => { called = true; }
  });
  await handleRpc("tools/list", {}, registry);
  assert.equal(called, true);
});

// --- handleRpc: tools/call ---

test("handleRpc tools/call missing name returns error", async () => {
  const registry = mockRegistry();
  const result = await handleRpc("tools/call", { arguments: {} }, registry);
  assert.equal(result.isError, true);
});

test("handleRpc tools/call routes to resolved server", async () => {
  let calledToolName = null;
  let calledArgs = null;
  const registry = mockRegistry({
    resolveServerForToolCall: (name) => ({ serverId: "pid:1", toolName: name }),
    callTool: async (serverId, toolName, args) => {
      calledToolName = toolName;
      calledArgs = args;
      return { content: [{ type: "text", text: "result" }] };
    }
  });

  const result = await handleRpc("tools/call", {
    name: "steroid_execute_code",
    arguments: { code: "1+1" }
  }, registry, null, null);

  assert.equal(calledToolName, "steroid_execute_code");
  assert.deepEqual(calledArgs, { code: "1+1" });
  assert.ok(result.content);
});

test("handleRpc tools/call forwards client progress token from params._meta", async () => {
  const notifications = [];
  const notify = async (method, params) => {
    notifications.push({ method, params });
  };

  const registry = mockRegistry({
    resolveServerForToolCall: (name) => ({ serverId: "pid:1", toolName: name }),
    callTool: async () => ({ content: [{ type: "text", text: "done" }] })
  });

  await handleRpc("tools/call", {
    name: "my_tool",
    arguments: {},
    _meta: { progressToken: "client-token-99" }
  }, registry, null, notify);

  assert.ok(notifications.length >= 2, "Expected at least start+end progress notifications");
  const tokens = notifications
    .filter(n => n.method === "notifications/progress")
    .map(n => n.params.progressToken);
  assert.ok(tokens.every(t => t === "client-token-99"),
    `Expected all progress tokens to be "client-token-99", got: ${JSON.stringify(tokens)}`);
});

test("handleRpc tools/call generates token when no _meta present", async () => {
  const notifications = [];
  const notify = async (method, params) => {
    notifications.push({ method, params });
  };

  const registry = mockRegistry({
    resolveServerForToolCall: (name) => ({ serverId: "pid:1", toolName: name }),
    callTool: async () => ({ content: [{ type: "text", text: "done" }] })
  });

  await handleRpc("tools/call", {
    name: "my_tool",
    arguments: {}
  }, registry, null, notify);

  const progressNotifs = notifications.filter(n => n.method === "notifications/progress");
  assert.ok(progressNotifs.length >= 1);
  const token = progressNotifs[0].params.progressToken;
  assert.ok(typeof token === "string" && token.startsWith("npx-"),
    `Expected generated token starting with "npx-", got: ${token}`);
});

test("handleRpc tools/call resolve failure returns tool error", async () => {
  const registry = mockRegistry({
    resolveServerForToolCall: () => ({ error: "No matching server" })
  });

  const result = await handleRpc("tools/call", {
    name: "steroid_execute_code",
    arguments: {}
  }, registry, null, null);

  assert.equal(result.isError, true);
});

// --- handleRpc: resources/list ---

test("handleRpc resources/list returns aggregated resources", async () => {
  const resources = [
    { uri: "mcp-steroid://docs/guide", name: "Guide" }
  ];
  const registry = mockRegistry({
    buildResourceIndex: () => resources
  });

  const result = await handleRpc("resources/list", {}, registry);
  assert.deepEqual(result, { resources });
});

// --- handleRpc: resources/read ---

test("handleRpc resources/read missing uri returns error -32602", async () => {
  const registry = mockRegistry();

  await assert.rejects(
    () => handleRpc("resources/read", {}, registry),
    (err) => {
      assert.equal(err.code, -32602);
      assert.match(err.message, /uri/i);
      return true;
    }
  );
});

test("handleRpc resources/read routes to correct server", async () => {
  let calledMethod = null;
  let calledParams = null;
  const registry = mockRegistry({
    buildResourceIndex: () => [],
    resourceIndex: new Map([["mcp-steroid://docs/guide", ["pid:1"]]]),
    callRpc: async (serverId, method, params) => {
      calledMethod = method;
      calledParams = params;
      return { contents: [{ uri: "mcp-steroid://docs/guide", text: "content" }] };
    }
  });

  const result = await handleRpc("resources/read", {
    uri: "mcp-steroid://docs/guide"
  }, registry);

  assert.equal(calledMethod, "resources/read");
  assert.deepEqual(calledParams, { uri: "mcp-steroid://docs/guide" });
});

test("handleRpc resources/read unknown uri throws", async () => {
  const registry = mockRegistry({
    buildResourceIndex: () => [],
    resourceIndex: new Map()
  });

  await assert.rejects(
    () => handleRpc("resources/read", { uri: "mcp-steroid://no-such" }, registry),
    /not found/i
  );
});

// --- handleRpc: unknown method ---

test("handleRpc unknown method throws -32601", async () => {
  const registry = mockRegistry();

  await assert.rejects(
    () => handleRpc("nonexistent/method", {}, registry),
    (err) => {
      assert.equal(err.code, -32601);
      assert.match(err.message, /not found/i);
      return true;
    }
  );
});

// --- handleRpc: ensureFresh is called for non-lifecycle methods ---

test("handleRpc calls ensureFresh for tools/call", async () => {
  let called = false;
  const registry = mockRegistry({
    ensureFresh: async () => { called = true; }
  });
  // tools/call will fail but ensureFresh should still be called
  try {
    await handleRpc("tools/call", { name: "x", arguments: {} }, registry);
  } catch (_) {}
  assert.equal(called, true);
});

test("handleRpc calls ensureFresh for resources/list", async () => {
  let called = false;
  const registry = mockRegistry({
    ensureFresh: async () => { called = true; }
  });
  await handleRpc("resources/list", {}, registry);
  assert.equal(called, true);
});

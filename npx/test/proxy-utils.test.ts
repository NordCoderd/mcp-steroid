// @ts-nocheck
const test = require("node:test");
const assert = require("node:assert/strict");

const {
  BEACON_EVENTS,
  parseArgs,
  parseMarkerContent,
  parseJsonFlag,
  buildCliRequest,
  mergeToolGroups,
  parseNamespacedTool,
  buildAliasUri,
  parseAliasUri,
  extractJsonFromToolResult,
  extractBaseVersion,
  isVersionNewer,
  pickRecommendedVersion,
  needsUpgradeByServerRule,
  buildUpdateCheckConfig,
  buildBeaconConfig,
  readNextFramedMessage,
  parseSseEventBlock,
  handleRpc,
  ServerRegistry,
  DEFAULT_CONFIG
} = require("../src/index");

test("parseArgs defaults to stdio mode", () => {
  const parsed = parseArgs([]);
  assert.equal(parsed.mode, "stdio");
  assert.equal(parsed.help, false);
});

test("parseArgs supports CLI mode flags", () => {
  const parsed = parseArgs([
    "--cli",
    "--tool", "steroid_list_projects",
    "--arguments-json", "{\"server_id\":\"pid:1\"}"
  ]);
  assert.equal(parsed.mode, "cli");
  assert.equal(parsed.cliToolName, "steroid_list_projects");
  assert.equal(parsed.cliArgumentsJson, "{\"server_id\":\"pid:1\"}");
});

test("buildCliRequest supports generic method", () => {
  const request = buildCliRequest({
    cliMethod: "resources/read",
    cliParamsJson: "{\"uri\":\"mcp-steroid://skill/SKILL.md\"}",
    cliToolName: null,
    cliArgumentsJson: null,
    cliUri: null
  });
  assert.deepEqual(request, {
    method: "resources/read",
    params: { uri: "mcp-steroid://skill/SKILL.md" }
  });
});

test("buildCliRequest supports tool shortcut", () => {
  const request = buildCliRequest({
    cliMethod: null,
    cliParamsJson: null,
    cliToolName: "steroid_list_projects",
    cliArgumentsJson: "{\"server_id\":\"pid:1\"}",
    cliUri: null
  });
  assert.deepEqual(request, {
    method: "tools/call",
    params: {
      name: "steroid_list_projects",
      arguments: { server_id: "pid:1" }
    }
  });
});

test("parseJsonFlag rejects non-object JSON", () => {
  assert.throws(
    () => parseJsonFlag("\"x\"", "--cli-params-json"),
    /must be a JSON object/
  );
});

test("extractBaseVersion trims snapshot and suffix", () => {
  assert.equal(extractBaseVersion("0.87.0-SNAPSHOT-20260214-abcdef"), "0.87.0");
  assert.equal(extractBaseVersion("0.87.1-beta.1"), "0.87.1");
  assert.equal(extractBaseVersion("0.88.0"), "0.88.0");
});

test("isVersionNewer compares semantic parts", () => {
  assert.equal(isVersionNewer("0.88.0", "0.87.0"), true);
  assert.equal(isVersionNewer("0.87.0", "0.87.0"), false);
  assert.equal(isVersionNewer("0.86.9", "0.87.0"), false);
});

test("pickRecommendedVersion returns freshest available version", () => {
  assert.equal(pickRecommendedVersion("0.88.0", "0.87.0"), "0.88.0");
  assert.equal(pickRecommendedVersion("0.87.0", "0.88.1"), "0.88.1");
  assert.equal(pickRecommendedVersion(null, "0.88.1"), "0.88.1");
});

test("needsUpgradeByServerRule mirrors server startsWith behavior", () => {
  assert.equal(needsUpgradeByServerRule("0.88.0-SNAPSHOT-abc", "0.88.0"), false);
  assert.equal(needsUpgradeByServerRule("0.87.9", "0.88.0"), true);
  assert.equal(needsUpgradeByServerRule("0.88.1", "0.88.0"), true);
});

test("buildUpdateCheckConfig has server-like defaults", () => {
  const cfg = buildUpdateCheckConfig({});
  assert.equal(cfg.enabled, true);
  assert.equal(cfg.initialDelayMs, 30_000);
  assert.equal(cfg.intervalMs, 15 * 60 * 1000);
  assert.equal(cfg.requestTimeoutMs, 10_000);
});

test("buildBeaconConfig applies defaults and supports overrides", () => {
  const defaults = buildBeaconConfig({});
  assert.equal(defaults.enabled, true);
  assert.equal(defaults.host, "https://us.i.posthog.com");
  assert.equal(defaults.timeoutMs, 3000);

  const overrides = buildBeaconConfig({
    beacon: {
      enabled: false,
      host: "https://eu.i.posthog.com",
      timeoutMs: 1111,
      heartbeatIntervalMs: 2222,
      distinctIdFile: "~/.custom-beacon-id"
    }
  });
  assert.equal(overrides.enabled, false);
  assert.equal(overrides.host, "https://eu.i.posthog.com");
  assert.equal(overrides.timeoutMs, 1111);
  assert.equal(overrides.heartbeatIntervalMs, 2222);
  assert.match(overrides.distinctIdFile, /\.custom-beacon-id$/);
});

test("beacon event names remain stable", () => {
  assert.equal(BEACON_EVENTS.started, "npx_started");
  assert.equal(BEACON_EVENTS.heartbeat, "npx_heartbeat");
  assert.equal(BEACON_EVENTS.discoveryChanged, "npx_discovery_changed");
  assert.equal(BEACON_EVENTS.toolCall, "npx_tool_call");
  assert.equal(BEACON_EVENTS.upgradeRecommended, "npx_upgrade_recommended");
});

test("parseMarkerContent extracts url and label", () => {
  const content = [
    "http://localhost:63342/mcp",
    "",
    "MCP Steroid Server",
    "URL: http://localhost:63342/mcp",
    "",
    "IntelliJ IDEA 2025.3",
    "Build #252.123"
  ].join("\n");
  const parsed = parseMarkerContent(content, 12345);
  assert.equal(parsed.url, "http://localhost:63342/mcp");
  assert.equal(parsed.label, "IntelliJ IDEA 2025.3");
});

test("mergeToolGroups unions properties and intersects required", () => {
  const toolA = {
    name: "steroid_execute_code",
    description: "Execute",
    inputSchema: {
      type: "object",
      properties: { a: { type: "string" } },
      required: ["a", "b"]
    }
  };
  const toolB = {
    name: "steroid_execute_code",
    description: "Execute",
    inputSchema: {
      type: "object",
      properties: { b: { type: "number" } },
      required: ["a"]
    }
  };
  const groups = new Map([["steroid_execute_code", [toolA, toolB]]]);
  const merged = mergeToolGroups(groups);
  assert.equal(merged.length, 1);
  const schema = merged[0].inputSchema;
  assert.ok(schema.properties.a);
  assert.ok(schema.properties.b);
  assert.ok(schema.properties.server_id);
  assert.deepEqual(schema.required, ["a"]);
});

test("parseNamespacedTool resolves server prefix", () => {
  const servers = new Set(["pid:12345"]);
  const parsed = parseNamespacedTool("pid:12345.steroid_execute_code", servers);
  assert.deepEqual(parsed, { serverId: "pid:12345", toolName: "steroid_execute_code" });
});

test("alias uri roundtrip", () => {
  const alias = buildAliasUri("pid:1", "mcp-steroid://docs/resource-graph");
  const parsed = parseAliasUri(alias);
  assert.deepEqual(parsed, { serverId: "pid:1", uri: "mcp-steroid://docs/resource-graph" });
});

test("extractJsonFromToolResult parses JSON text", () => {
  const result = {
    content: [
      { type: "text", text: "{\"projects\":[{\"name\":\"x\",\"path\":\"/p\"}]}" }
    ]
  };
  const parsed = extractJsonFromToolResult(result);
  assert.equal(parsed.projects[0].name, "x");
});

test("extractJsonFromToolResult returns null for Markdown blocks (current limitation)", () => {
  const result = {
    content: [
      { type: "text", text: "```json\n{\"projects\":[{\"name\":\"x\"}]}\n```" }
    ]
  };
  // The proxy currently expects strict JSON from MCP servers (like IDEs).
  // It does not strip Markdown. This test documents that behavior.
  const parsed = extractJsonFromToolResult(result);
  assert.equal(parsed, null);
});

test("readNextFramedMessage does not parse partial Content-Length headers as JSON lines", () => {
  const request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
  const headerOnly = Buffer.from(`Content-Length: ${Buffer.byteLength(request, "utf8")}\r\n`);
  const complete = Buffer.concat([
    headerOnly,
    Buffer.from(`\r\n${request}`, "utf8")
  ]);

  assert.equal(readNextFramedMessage(headerOnly), null);
  const frame = readNextFramedMessage(complete);
  assert.equal(frame.payloadText, request);
});

test("resolveServerForToolCall prefers server_id and project name", () => {
  const registry = new ServerRegistry(DEFAULT_CONFIG, { log: async () => {} });
  registry.servers.set("pid:1", {
    serverId: "pid:1",
    status: "online",
    pid: 1,
    metadata: { plugin: { version: "1.0.0" }, ide: { version: "2025.3" } },
    lastSeenAt: new Date(0).toISOString()
  });
  registry.servers.set("pid:2", {
    serverId: "pid:2",
    status: "online",
    pid: 2,
    metadata: { plugin: { version: "2.0.0" }, ide: { version: "2025.3" } },
    lastSeenAt: new Date(1000).toISOString()
  });

  registry.projectIndexByName.set("ProjectA", ["pid:1"]);

  const explicit = registry.resolveServerForToolCall("steroid_execute_code", { server_id: "pid:2" });
  assert.deepEqual(explicit, { serverId: "pid:2", toolName: "steroid_execute_code" });

  const inferred = registry.resolveServerForToolCall("steroid_execute_code", { project_name: "ProjectA" });
  assert.deepEqual(inferred, { serverId: "pid:1", toolName: "steroid_execute_code" });

  const fallback = registry.resolveServerForToolCall("steroid_execute_code", {});
  assert.ok(fallback.error);
});

test("resolveServerForToolCall maps server_id alias intellij when single server is online", () => {
  const registry = new ServerRegistry(DEFAULT_CONFIG, { log: async () => {} });
  registry.servers.set("pid:1", {
    serverId: "pid:1",
    status: "online",
    pid: 1,
    label: "pid:1",
    metadata: null,
    lastSeenAt: new Date(0).toISOString()
  });

  const resolved = registry.resolveServerForToolCall("steroid_execute_code", { server_id: "intellij" });
  assert.deepEqual(resolved, { serverId: "pid:1", toolName: "steroid_execute_code" });
});

test("parseSseEventBlock parses event and JSON data", () => {
  const block = [
    "event: progress",
    "data: {\"type\":\"progress\",\"progress\":0.5,\"message\":\"Halfway\"}",
    ""
  ].join("\n");

  const parsed = parseSseEventBlock(block);
  assert.equal(parsed.type, "progress");
  assert.equal(parsed.data.type, "progress");
  assert.equal(parsed.data.progress, 0.5);
  assert.equal(parsed.data.message, "Halfway");
});

test("handleRpc aggregate call returns error for unknown server_id", async () => {
  const registry = {
    config: DEFAULT_CONFIG,
    ensureFresh: async () => {},
    listOnlineServersByFreshness: () => [],
    resolveServerIdHint: () => null
  };

  const result = await handleRpc(
    "tools/call",
    {
      name: "steroid_list_projects",
      arguments: { server_id: "missing-server" }
    },
    registry,
    null,
    null
  );

  assert.equal(result.isError, true);
  assert.match(result.content[0].text, /Unknown server_id: missing-server/);
});

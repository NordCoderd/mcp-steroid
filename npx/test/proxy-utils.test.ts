// @ts-nocheck
const test = require("node:test");
const assert = require("node:assert/strict");

const {
  parseArgs,
  parseMarkerContent,
  parseJsonFlag,
  buildCliRequest,
  mergeToolGroups,
  parseNamespacedTool,
  buildAliasUri,
  parseAliasUri,
  extractJsonFromToolResult,
  ServerRegistry,
  DEFAULT_CONFIG
} = require("../src/index.js");

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

test("parseMarkerContent extracts url and label", () => {
  const content = [
    "http://localhost:63342/mcp",
    "",
    "IntelliJ MCP Steroid Server",
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

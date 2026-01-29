const test = require("node:test");
const assert = require("node:assert/strict");

const {
  parseMarkerContent,
  mergeToolGroups,
  parseNamespacedTool,
  buildAliasUri,
  parseAliasUri,
  extractJsonFromToolResult,
  ServerRegistry,
  DEFAULT_CONFIG
} = require("../src/index.js");

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

test("resolveServerForToolCall prefers server_id and detects ambiguity", () => {
  const registry = new ServerRegistry(DEFAULT_CONFIG, { log: async () => {} });
  registry.servers.set("pid:1", { serverId: "pid:1", status: "online" });
  registry.servers.set("pid:2", { serverId: "pid:2", status: "online" });
  registry.projectIndex.set("ProjectA", ["pid:1"]);
  registry.projectIndex.set("ProjectB", ["pid:1", "pid:2"]);

  const explicit = registry.resolveServerForToolCall("steroid_execute_code", { server_id: "pid:2" });
  assert.deepEqual(explicit, { serverId: "pid:2", toolName: "steroid_execute_code" });

  const inferred = registry.resolveServerForToolCall("steroid_execute_code", { project_name: "ProjectA" });
  assert.deepEqual(inferred, { serverId: "pid:1", toolName: "steroid_execute_code" });

  const ambiguous = registry.resolveServerForToolCall("steroid_execute_code", { project_name: "ProjectB" });
  assert.ok(ambiguous.error);
});

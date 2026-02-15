#!/usr/bin/env node
// @ts-nocheck
"use strict";

const fs = require("fs");
const fsp = require("fs/promises");
const path = require("path");
const os = require("os");
const crypto = require("crypto");
const { URL } = require("url");

const PROTOCOL_VERSION = "2025-11-25";
const JSONRPC_VERSION = "2.0";
const SESSION_HEADER = "Mcp-Session-Id";

const AGGREGATE_TOOL_NAMES = {
  projects: "steroid_list_projects",
  windows: "steroid_list_windows",
  products: "steroid_list_products",
  metadata: "steroid_server_metadata"
};

const BEACON_EVENTS = Object.freeze({
  started: "npx_started",
  heartbeat: "npx_heartbeat",
  discoveryChanged: "npx_discovery_changed",
  toolCall: "npx_tool_call",
  upgradeRecommended: "npx_upgrade_recommended"
});

const DEFAULT_CONFIG = {
  homeDir: null,
  scanIntervalMs: 2000,
  allowHosts: ["127.0.0.1", "localhost"],
  cache: {
    enabled: false,
    dir: "~/.mcp-steroid/proxy",
    ttlSeconds: 5
  },
  trafficLog: {
    enabled: false,
    redactFields: ["code"]
  },
  defaultServerId: null,
  upstreamTimeoutMs: 120_000,
  updates: {
    enabled: true,
    initialDelayMs: 30_000,
    intervalMs: 15 * 60 * 1000,
    requestTimeoutMs: 10_000
  },
  beacon: {
    enabled: true,
    host: "https://us.i.posthog.com",
    apiKey: "phc_IPtbjwwy9YIGg0YNHNxYBePijvTvHEcKAjohah6obYW",
    timeoutMs: 3_000,
    heartbeatIntervalMs: 30 * 60 * 1000,
    distinctIdFile: "~/.mcp-steroid/proxy-beacon-id"
  }
};

function loadPackageVersion() {
  try {
    const pkgPath = path.join(__dirname, "..", "package.json");
    const raw = fs.readFileSync(pkgPath, "utf8");
    const pkg = JSON.parse(raw);
    return pkg.version || "0.1.0";
  } catch (_) {
    return "0.1.0";
  }
}

function parseArgs(argv) {
  const out = {
    configPath: null,
    scanIntervalMs: null,
    logTraffic: null,
    help: false,
    mode: "stdio",
    cliMethod: null,
    cliParamsJson: null,
    cliToolName: null,
    cliArgumentsJson: null,
    cliUri: null
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--config") {
      out.configPath = argv[i + 1];
      i += 1;
    } else if (arg === "--scan-interval") {
      out.scanIntervalMs = Number(argv[i + 1]);
      i += 1;
    } else if (arg === "--log-traffic") {
      out.logTraffic = true;
    } else if (arg === "--cli") {
      out.mode = "cli";
    } else if (arg === "--cli-method") {
      out.cliMethod = argv[i + 1];
      i += 1;
    } else if (arg === "--cli-params-json") {
      out.cliParamsJson = argv[i + 1];
      i += 1;
    } else if (arg === "--tool") {
      out.cliToolName = argv[i + 1];
      i += 1;
    } else if (arg === "--arguments-json") {
      out.cliArgumentsJson = argv[i + 1];
      i += 1;
    } else if (arg === "--uri") {
      out.cliUri = argv[i + 1];
      i += 1;
    } else if (arg === "-h" || arg === "--help") {
      out.help = true;
    }
  }
  return out;
}

function parseJsonFlag(rawValue, fieldName) {
  if (rawValue == null || rawValue === "") return {};
  try {
    const parsed = JSON.parse(rawValue);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      throw new Error(`${fieldName} must be a JSON object`);
    }
    return parsed;
  } catch (err) {
    throw new Error(`Invalid ${fieldName}: ${err.message}`);
  }
}

function buildCliRequest(args) {
  if (args.cliMethod) {
    return {
      method: args.cliMethod,
      params: parseJsonFlag(args.cliParamsJson, "--cli-params-json")
    };
  }

  if (args.cliToolName) {
    return {
      method: "tools/call",
      params: {
        name: args.cliToolName,
        arguments: parseJsonFlag(args.cliArgumentsJson, "--arguments-json")
      }
    };
  }

  if (args.cliUri) {
    return {
      method: "resources/read",
      params: {
        uri: args.cliUri
      }
    };
  }

  return {
    method: "tools/list",
    params: {}
  };
}

function mergeDeep(target, source) {
  const result = { ...target };
  if (!source) return result;
  for (const [key, value] of Object.entries(source)) {
    if (value && typeof value === "object" && !Array.isArray(value)) {
      result[key] = mergeDeep(result[key] || {}, value);
    } else {
      result[key] = value;
    }
  }
  return result;
}

function expandHome(value) {
  if (!value) return value;
  if (value.startsWith("~")) {
    return path.join(os.homedir(), value.slice(1));
  }
  return value;
}

async function loadConfig(args) {
  const defaultPath = path.join(os.homedir(), ".mcp-steroid", "proxy.json");
  const configPath = args.configPath ? path.resolve(args.configPath) : defaultPath;

  let fileConfig = {};
  try {
    const raw = await fsp.readFile(configPath, "utf8");
    fileConfig = JSON.parse(raw);
  } catch (err) {
    if (args.configPath && err.code !== "ENOENT") {
      throw err;
    }
  }

  const config = mergeDeep(DEFAULT_CONFIG, fileConfig);
  if (Number.isFinite(args.scanIntervalMs) && args.scanIntervalMs > 0) {
    config.scanIntervalMs = args.scanIntervalMs;
  }
  if (args.logTraffic === true) {
    config.trafficLog.enabled = true;
  }
  config.cache.dir = expandHome(config.cache.dir);
  if (config.homeDir) {
    config.homeDir = expandHome(config.homeDir);
  }
  if (config.beacon && config.beacon.distinctIdFile) {
    config.beacon.distinctIdFile = expandHome(config.beacon.distinctIdFile);
  }
  return config;
}

function isPidAlive(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch (err) {
    return err.code !== "ESRCH";
  }
}

function parseMarkerContent(content, pid) {
  const lines = content.split(/\r?\n/).map((line) => line.trim());
  const url = lines[0];
  if (!url) return null;

  let label = null;
  for (let i = 1; i < lines.length; i += 1) {
    const line = lines[i];
    if (!line) continue;
    if (line.startsWith("URL:")) continue;
    if (line.startsWith("IntelliJ MCP Steroid Server")) continue;
    if (line.startsWith("Created:")) continue;
    if (line.startsWith("Plugin ")) continue;
    if (line.startsWith("IDE ")) continue;
    label = line;
    break;
  }

  if (!label) {
    label = `pid:${pid}`;
  }
  return { url, label };
}

function isAllowedHost(urlValue, allowHosts) {
  try {
    const parsed = new URL(urlValue);
    return allowHosts.includes(parsed.hostname);
  } catch (_) {
    return false;
  }
}

async function scanMarkers(homeDir, allowHosts) {
  const entries = await fsp.readdir(homeDir, { withFileTypes: true });
  const markerRegex = /^\.(\d+)\.mcp-steroid$/;
  const results = [];

  for (const entry of entries) {
    if (!entry.isFile()) continue;
    const match = entry.name.match(markerRegex);
    if (!match) continue;

    const pid = Number(match[1]);
    if (!Number.isFinite(pid)) continue;
    if (!isPidAlive(pid)) continue;

    const fullPath = path.join(homeDir, entry.name);
    let content;
    try {
      content = await fsp.readFile(fullPath, "utf8");
    } catch (_) {
      continue;
    }

    const parsed = parseMarkerContent(content, pid);
    if (!parsed) continue;
    if (!isAllowedHost(parsed.url, allowHosts)) continue;

    results.push({
      pid,
      url: parsed.url,
      label: parsed.label,
      markerPath: fullPath
    });
  }

  return results;
}

function baseUrlFromMcpUrl(serverUrl) {
  return serverUrl.replace(/\/mcp\/?$/, "");
}

function portFromUrl(urlValue) {
  try {
    const parsed = new URL(urlValue);
    return parsed.port ? Number(parsed.port) : (parsed.protocol === "https:" ? 443 : 80);
  } catch (_) {
    return null;
  }
}

function buildServerId(pid, urlValue, existing) {
  const baseId = `pid:${pid}`;
  const existingServer = existing.get(baseId);
  if (!existingServer) return baseId;
  if (existingServer.url === urlValue) return baseId;
  const hash = crypto.createHash("sha1").update(urlValue).digest("hex").slice(0, 8);
  return `${baseId}:${hash}`;
}

function nowIso() {
  return new Date().toISOString();
}

function versionParts(value) {
  if (typeof value !== "string" || !value.trim()) return [];
  const matches = value.match(/\d+/g);
  if (!matches) return [];
  return matches.map((item) => Number(item));
}

function compareVersionPartsDescending(leftParts, rightParts) {
  const max = Math.max(leftParts.length, rightParts.length);
  for (let i = 0; i < max; i += 1) {
    const left = leftParts[i] ?? 0;
    const right = rightParts[i] ?? 0;
    if (left > right) return -1;
    if (left < right) return 1;
  }
  return 0;
}

function compareVersionStringsDescending(left, right) {
  return compareVersionPartsDescending(versionParts(left), versionParts(right));
}

function extractBaseVersion(fullVersion) {
  if (typeof fullVersion !== "string") return "";
  const trimmed = fullVersion.trim();
  if (!trimmed) return "";
  const snapshotIndex = trimmed.indexOf("-SNAPSHOT");
  if (snapshotIndex > 0) {
    return trimmed.slice(0, snapshotIndex);
  }
  const dashIndex = trimmed.indexOf("-");
  if (dashIndex > 0) {
    return trimmed.slice(0, dashIndex);
  }
  return trimmed;
}

function isVersionNewer(candidateVersion, currentVersion) {
  const candidate = extractBaseVersion(candidateVersion);
  const current = extractBaseVersion(currentVersion);
  if (!candidate || !current) return false;
  return compareVersionStringsDescending(candidate, current) < 0;
}

function compareIsoTimesDescending(left, right) {
  const leftMs = Date.parse(left || "") || 0;
  const rightMs = Date.parse(right || "") || 0;
  if (leftMs > rightMs) return -1;
  if (leftMs < rightMs) return 1;
  return 0;
}

function buildAliasUri(serverId, uri) {
  return `mcp-steroid+proxy://${encodeURIComponent(serverId)}/${encodeURIComponent(uri)}`;
}

function parseAliasUri(uri) {
  const prefix = "mcp-steroid+proxy://";
  if (!uri.startsWith(prefix)) return null;
  const rest = uri.slice(prefix.length);
  const slash = rest.indexOf("/");
  if (slash <= 0) return null;
  const serverId = decodeURIComponent(rest.slice(0, slash));
  const original = decodeURIComponent(rest.slice(slash + 1));
  return { serverId, uri: original };
}

function parseNamespacedTool(name, serverIds) {
  const dot = name.indexOf(".");
  if (dot <= 0) return null;
  const prefix = name.slice(0, dot);
  if (!serverIds.has(prefix)) return null;
  return { serverId: prefix, toolName: name.slice(dot + 1) };
}

function projectKey(name, projectPath) {
  return `${name || ""}\u0000${projectPath || ""}`;
}

function normalizeServerMetadataPayload(payload) {
  if (!payload || typeof payload !== "object") return null;
  const ide = payload.ide && typeof payload.ide === "object" ? payload.ide : null;
  const plugin = payload.plugin && typeof payload.plugin === "object" ? payload.plugin : null;
  const paths = payload.paths && typeof payload.paths === "object" ? payload.paths : null;
  const executable = payload.executable && typeof payload.executable === "object" ? payload.executable : null;

  if (!ide && !plugin && !paths && !executable && !payload.mcpUrl && !payload.pid) {
    return null;
  }

  return {
    pid: Number.isFinite(payload.pid) ? payload.pid : null,
    mcpUrl: typeof payload.mcpUrl === "string" ? payload.mcpUrl : null,
    ide: ide ? {
      name: typeof ide.name === "string" ? ide.name : null,
      version: typeof ide.version === "string" ? ide.version : null,
      build: typeof ide.build === "string" ? ide.build : null
    } : null,
    plugin: plugin ? {
      id: typeof plugin.id === "string" ? plugin.id : null,
      name: typeof plugin.name === "string" ? plugin.name : null,
      version: typeof plugin.version === "string" ? plugin.version : null
    } : null,
    paths: paths || null,
    executable: executable || null
  };
}

function metadataFromProductsPayload(payload) {
  if (!payload || typeof payload !== "object") return null;
  if (!Array.isArray(payload.products) || payload.products.length === 0) return null;
  const first = payload.products[0];
  if (!first || typeof first !== "object") return null;
  return normalizeServerMetadataPayload(first);
}

function metadataFromWindowsPayload(payload) {
  if (!payload || typeof payload !== "object") return null;
  return normalizeServerMetadataPayload(payload);
}

function metadataFromProjectsPayload(payload) {
  if (!payload || typeof payload !== "object") return null;
  return normalizeServerMetadataPayload(payload);
}

function metadataFromInitializeResult(payload) {
  if (!payload || typeof payload !== "object") return null;
  const serverInfo = payload.serverInfo && typeof payload.serverInfo === "object" ? payload.serverInfo : null;
  if (!serverInfo) return null;
  const version = typeof serverInfo.version === "string" ? serverInfo.version : null;
  if (!version) return null;
  return {
    plugin: {
      id: typeof serverInfo.name === "string" ? serverInfo.name : null,
      name: typeof serverInfo.name === "string" ? serverInfo.name : null,
      version
    }
  };
}

function mergeServerMetadata(current, patch) {
  if (!patch) return current;
  const base = current && typeof current === "object" ? current : {};
  const merged = { ...base };

  if (patch.pid != null) merged.pid = patch.pid;
  if (patch.mcpUrl) merged.mcpUrl = patch.mcpUrl;

  if (patch.ide && typeof patch.ide === "object") {
    merged.ide = {
      ...(base.ide || {}),
      ...Object.fromEntries(Object.entries(patch.ide).filter(([, value]) => value != null))
    };
  }

  if (patch.plugin && typeof patch.plugin === "object") {
    merged.plugin = {
      ...(base.plugin || {}),
      ...Object.fromEntries(Object.entries(patch.plugin).filter(([, value]) => value != null))
    };
  }

  if (patch.paths && typeof patch.paths === "object") {
    merged.paths = {
      ...(base.paths || {}),
      ...patch.paths
    };
  }

  if (patch.executable && typeof patch.executable === "object") {
    merged.executable = {
      ...(base.executable || {}),
      ...patch.executable
    };
  }

  return merged;
}

function mergeInputSchemas(schemas) {
  const properties = {};
  let required = null;

  for (const schema of schemas) {
    if (!schema || typeof schema !== "object") continue;
    const props = schema.properties && typeof schema.properties === "object" ? schema.properties : {};

    for (const [key, value] of Object.entries(props)) {
      if (!properties[key]) {
        properties[key] = value;
      }
    }

    if (Array.isArray(schema.required)) {
      const reqSet = new Set(schema.required);
      if (required === null) {
        required = reqSet;
      } else {
        required = new Set([...required].filter((item) => reqSet.has(item)));
      }
    }
  }

  if (!properties.server_id) {
    properties.server_id = {
      type: "string",
      description: "Target server id for routing (optional)"
    };
  }

  return {
    type: "object",
    properties,
    required: required ? Array.from(required) : []
  };
}

function mergeToolGroups(toolGroups) {
  const merged = [];
  for (const [name, tools] of toolGroups.entries()) {
    const inputSchemas = tools.map((tool) => tool.inputSchema || { type: "object" });
    const mergedInput = mergeInputSchemas(inputSchemas);
    const primary = tools[0] || {};
    const description = primary.description
      ? `${primary.description} (aggregated; optional server_id)`
      : "Aggregated tool (optional server_id)";

    merged.push({
      name,
      description,
      title: primary.title,
      inputSchema: mergedInput,
      outputSchema: primary.outputSchema
    });
  }
  merged.sort((a, b) => a.name.localeCompare(b.name));
  return merged;
}

function extractJsonFromToolResult(result) {
  if (!result || !Array.isArray(result.content)) return null;
  for (const item of result.content) {
    if (!item || item.type !== "text" || typeof item.text !== "string") continue;
    try {
      return JSON.parse(item.text);
    } catch (_) {
      // ignore
    }
  }
  return null;
}

class TrafficLogger {
  constructor(config) {
    this.enabled = config.trafficLog.enabled;
    this.redactFields = new Set(config.trafficLog.redactFields || []);
    this.dir = config.cache.dir;
  }

  redact(payload) {
    if (!payload || typeof payload !== "object") return payload;
    const clone = Array.isArray(payload) ? payload.map((item) => this.redact(item)) : { ...payload };

    for (const key of Object.keys(clone)) {
      if (this.redactFields.has(key)) {
        clone[key] = "[redacted]";
      } else if (clone[key] && typeof clone[key] === "object") {
        clone[key] = this.redact(clone[key]);
      }
    }
    return clone;
  }

  async log(record) {
    if (!this.enabled) return;
    try {
      const logDir = path.join(this.dir, "logs");
      await fsp.mkdir(logDir, { recursive: true });
      const date = new Date().toISOString().slice(0, 10);
      const file = path.join(logDir, `${date}.jsonl`);
      const payload = {
        ...record,
        payload: this.redact(record.payload)
      };
      await fsp.appendFile(file, `${JSON.stringify(payload)}\n`, "utf8");
    } catch (_) {
      // Ignore logging failures
    }
  }
}

function buildBeaconConfig(config) {
  const raw = config && config.beacon && typeof config.beacon === "object" ? config.beacon : {};
  const timeoutMs = Number.isFinite(raw.timeoutMs) && raw.timeoutMs > 0
    ? raw.timeoutMs
    : DEFAULT_CONFIG.beacon.timeoutMs;
  const heartbeatIntervalMs = Number.isFinite(raw.heartbeatIntervalMs) && raw.heartbeatIntervalMs > 0
    ? raw.heartbeatIntervalMs
    : DEFAULT_CONFIG.beacon.heartbeatIntervalMs;
  const host = typeof raw.host === "string" && raw.host.trim()
    ? raw.host.trim()
    : DEFAULT_CONFIG.beacon.host;
  const apiKey = typeof raw.apiKey === "string" && raw.apiKey.trim()
    ? raw.apiKey.trim()
    : DEFAULT_CONFIG.beacon.apiKey;
  const distinctIdFile = expandHome(
    typeof raw.distinctIdFile === "string" && raw.distinctIdFile.trim()
      ? raw.distinctIdFile.trim()
      : DEFAULT_CONFIG.beacon.distinctIdFile
  );
  return {
    enabled: raw.enabled !== false,
    host,
    apiKey,
    timeoutMs,
    heartbeatIntervalMs,
    distinctIdFile
  };
}

function createBeaconDistinctId() {
  if (typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  return crypto.randomBytes(16).toString("hex");
}

class NpxBeacon {
  constructor(config) {
    this.config = buildBeaconConfig(config);
    this.proxyVersion = config.version || "0.1.0";
    this.distinctId = null;
    this.lastDiscoverySignature = null;
    this.heartbeatTimer = null;
    this.client = null;

    if (!this.config.enabled) return;
    if (!this.config.apiKey) {
      this.config.enabled = false;
      return;
    }

    let PostHogClient = null;
    try {
      PostHogClient = require("posthog-node").PostHog;
    } catch (_) {
      this.config.enabled = false;
      this.client = null;
      return;
    }

    try {
      this.client = new PostHogClient(this.config.apiKey, {
        host: this.config.host,
        flushAt: 1,
        flushInterval: 1_000,
        maxQueueSize: 50,
        maxBatchSize: 5,
        requestTimeout: this.config.timeoutMs,
        preloadFeatureFlags: false,
        sendFeatureFlagEvent: false
      });
    } catch (_) {
      this.config.enabled = false;
      this.client = null;
    }
  }

  async getDistinctId() {
    if (this.distinctId) return this.distinctId;
    try {
      const existing = (await fsp.readFile(this.config.distinctIdFile, "utf8")).trim();
      if (existing) {
        this.distinctId = existing;
        return existing;
      }
    } catch (_) {
      // Ignore read failures and create a new id
    }

    const generated = createBeaconDistinctId();
    try {
      await fsp.mkdir(path.dirname(this.config.distinctIdFile), { recursive: true });
      await fsp.writeFile(this.config.distinctIdFile, `${generated}\n`, {
        encoding: "utf8",
        mode: 0o600
      });
      try {
        await fsp.chmod(this.config.distinctIdFile, 0o600);
      } catch (_) {
        // Ignore chmod failures on unsupported filesystems/platforms
      }
    } catch (_) {
      // Ignore write failures, keep in-memory id
    }
    this.distinctId = generated;
    return generated;
  }

  capture(event, properties = {}) {
    if (!this.client || !this.config.enabled) return;
    void this.captureAsync(event, properties);
  }

  async captureAsync(event, properties = {}) {
    if (!this.client || !this.config.enabled) return;
    const distinctId = await this.getDistinctId();
    try {
      this.client.capture({
        distinctId,
        event,
        properties: {
          proxy_version: this.proxyVersion,
          node_version: process.version,
          platform: process.platform,
          arch: process.arch,
          ...properties
        }
      });
    } catch (_) {
      // Ignore beacon failures
    }
  }

  captureDiscoveryChanged(registry, reason) {
    if (!this.client || !this.config.enabled) return;
    const summary = [...registry.servers.values()]
      .map((server) => `${server.serverId}:${server.status}:${server.url}`)
      .sort()
      .join("|");
    if (summary === this.lastDiscoverySignature) return;
    this.lastDiscoverySignature = summary;

    const totalServers = registry.servers.size;
    const onlineServers = registry.listOnlineServers().length;
    this.capture(BEACON_EVENTS.discoveryChanged, {
      reason,
      total_servers: totalServers,
      online_servers: onlineServers,
      offline_servers: Math.max(0, totalServers - onlineServers)
    });
  }

  startHeartbeat(registry) {
    if (!this.client || !this.config.enabled) return;
    if (this.heartbeatTimer) return;
    this.heartbeatTimer = setInterval(() => {
      this.capture(BEACON_EVENTS.heartbeat, {
        online_servers: registry.listOnlineServers().length,
        total_servers: registry.servers.size
      });
    }, this.config.heartbeatIntervalMs);
  }

  async shutdown() {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
    if (!this.client) return;
    try {
      await this.client.shutdown();
    } catch (_) {
      // Ignore beacon shutdown failures
    }
  }
}

function parseSseEventBlock(block) {
  if (typeof block !== "string") return null;
  const lines = block.split(/\r?\n/);
  let type = null;
  const dataLines = [];

  for (const rawLine of lines) {
    const line = rawLine.trimEnd();
    if (!line) continue;
    if (line.startsWith("event:")) {
      type = line.slice("event:".length).trim();
      continue;
    }
    if (line.startsWith("data:")) {
      dataLines.push(line.slice("data:".length).trimStart());
    }
  }

  if (dataLines.length === 0) return null;
  const dataText = dataLines.join("\n");
  let data = null;
  try {
    data = JSON.parse(dataText);
  } catch (_) {
    data = { message: dataText };
  }

  if (!type && data && typeof data.type === "string") {
    type = data.type;
  }

  return {
    type: type || "message",
    data
  };
}

class UpstreamClient {
  constructor(server, config, traffic) {
    this.server = server;
    this.config = config;
    this.traffic = traffic;
    this.sessionId = null;
    this.initialized = false;
    this.requestId = 0;
  }

  async ensureInitialized() {
    if (this.initialized) return;

    const params = {
      protocolVersion: PROTOCOL_VERSION,
      capabilities: {},
      clientInfo: {
        name: "mcp-steroid-proxy",
        version: this.config.version || "0.1.0"
      }
    };

    const initResult = await this.sendRequest("initialize", params, true);
    const metadataPatch = metadataFromInitializeResult(initResult);
    this.server.metadata = mergeServerMetadata(this.server.metadata, metadataPatch);

    await this.sendNotification("notifications/initialized", {});
    this.initialized = true;
  }

  async sendNotification(method, params) {
    const payload = { jsonrpc: JSONRPC_VERSION, method, params };
    await this.sendPayload(payload, false);
  }

  async sendRequest(method, params, skipInit = false) {
    if (!skipInit) {
      await this.ensureInitialized();
    }

    this.requestId += 1;
    const payload = {
      jsonrpc: JSONRPC_VERSION,
      id: String(this.requestId),
      method,
      params
    };

    const response = await this.sendPayload(payload, true);
    if (response.error) {
      throw new Error(response.error.message || "Upstream error");
    }
    return response.result;
  }

  async callTool(toolName, args, onEvent = null) {
    await this.ensureInitialized();
    try {
      return await this.callToolViaBridgeStream(toolName, args || {}, onEvent);
    } catch (err) {
      // Fallback for older plugin versions without bridge streaming support.
      if (!err || err.retryable !== true) {
        throw err;
      }
    }

    return this.sendRequest("tools/call", {
      name: toolName,
      arguments: args || {}
    });
  }

  async callToolViaBridgeStream(toolName, args, onEvent = null) {
    if (!this.server.bridgeBaseUrl) {
      const err = new Error("Bridge base URL is missing");
      err.retryable = true;
      throw err;
    }

    const url = `${this.server.bridgeBaseUrl}/tools/call/stream`;
    const payload = {
      name: toolName,
      arguments: args || {}
    };

    await this.traffic.log({
      ts: nowIso(),
      direction: "upstream-out",
      serverId: this.server.serverId,
      method: "bridge/tools/call/stream",
      payload
    });

    const controller = new AbortController();
    let timeout = null;
    const resetTimeout = () => {
      if (timeout) clearTimeout(timeout);
      timeout = setTimeout(() => controller.abort(), this.config.upstreamTimeoutMs);
    };

    resetTimeout();
    let response;
    try {
      response = await fetch(url, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Accept: "text/event-stream"
        },
        body: JSON.stringify(payload),
        signal: controller.signal
      });
    } finally {
      if (timeout) clearTimeout(timeout);
    }

    if (!response.ok) {
      let details = "";
      try {
        details = (await response.text()).slice(0, 400);
      } catch (_) {
        // Ignore body parse failures
      }
      const err = new Error(
        `Bridge HTTP ${response.status} ${response.statusText}${details ? `: ${details}` : ""}`
      );
      err.retryable = response.status === 404 || response.status === 405 || response.status === 501;
      throw err;
    }

    const reader = response.body && response.body.getReader ? response.body.getReader() : null;
    if (!reader) {
      const err = new Error("Bridge stream response has no body");
      err.retryable = true;
      throw err;
    }

    const decoder = new TextDecoder("utf8");
    let buffer = "";
    let sawEvent = false;
    let resultPayload = null;

    const handleBlock = async (block) => {
      const parsed = parseSseEventBlock(block);
      if (!parsed) return;

      sawEvent = true;
      await this.traffic.log({
        ts: nowIso(),
        direction: "upstream-in",
        serverId: this.server.serverId,
        method: "bridge/tools/call/stream",
        payload: parsed.data
      });

      if (typeof onEvent === "function" && parsed.data && typeof parsed.data === "object") {
        await onEvent(parsed.data);
      }

      if (parsed.type === "result" && parsed.data && typeof parsed.data === "object") {
        resultPayload = parsed.data.result || null;
      } else if (parsed.type === "error") {
        const message = parsed.data && typeof parsed.data.message === "string"
          ? parsed.data.message
          : "Bridge stream tool call failed";
        const err = new Error(message);
        err.retryable = false;
        throw err;
      }
    };

    try {
      while (true) {
        resetTimeout();
        const chunk = await reader.read();
        if (timeout) clearTimeout(timeout);
        if (chunk.done) break;
        buffer += decoder.decode(chunk.value, { stream: true });

        while (true) {
          const lfLf = buffer.indexOf("\n\n");
          const crlfCrlf = buffer.indexOf("\r\n\r\n");
          let sepIndex = -1;
          let sepLen = 0;

          if (lfLf >= 0 && (crlfCrlf < 0 || lfLf < crlfCrlf)) {
            sepIndex = lfLf;
            sepLen = 2;
          } else if (crlfCrlf >= 0) {
            sepIndex = crlfCrlf;
            sepLen = 4;
          }

          if (sepIndex < 0) break;
          const block = buffer.slice(0, sepIndex);
          buffer = buffer.slice(sepIndex + sepLen);
          await handleBlock(block);
        }
      }

      if (buffer.trim()) {
        await handleBlock(buffer);
      }
    } catch (err) {
      if (err && err.name === "AbortError") {
        const timeoutErr = new Error(
          `Bridge stream timeout after ${this.config.upstreamTimeoutMs}ms for ${toolName}`
        );
        timeoutErr.retryable = !sawEvent;
        throw timeoutErr;
      }
      if (err && typeof err === "object" && "retryable" in err) {
        throw err;
      }
      const streamErr = new Error(err && err.message ? err.message : String(err));
      streamErr.retryable = !sawEvent;
      throw streamErr;
    } finally {
      try {
        await reader.cancel();
      } catch (_) {
        // ignore
      }
    }

    if (resultPayload == null) {
      const err = new Error("Bridge stream completed without a result payload");
      err.retryable = !sawEvent;
      throw err;
    }
    return resultPayload;
  }

  async sendPayload(payload, expectResponse) {
    const headers = {
      "Content-Type": "application/json",
      Accept: "application/json"
    };
    if (this.sessionId) {
      headers[SESSION_HEADER] = this.sessionId;
    }

    await this.traffic.log({
      ts: nowIso(),
      direction: "upstream-out",
      serverId: this.server.serverId,
      method: payload.method,
      payload
    });

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.config.upstreamTimeoutMs);
    let response;
    try {
      response = await fetch(this.server.url, {
        method: "POST",
        headers,
        body: JSON.stringify(payload),
        signal: controller.signal
      });
    } finally {
      clearTimeout(timeout);
    }

    const newSession = response.headers.get(SESSION_HEADER.toLowerCase()) || response.headers.get(SESSION_HEADER);
    if (newSession) {
      this.sessionId = newSession;
    }

    if (!response.ok) {
      let details = "";
      try {
        details = (await response.text()).slice(0, 400);
      } catch (_) {
        // Ignore response body parse failures
      }
      const suffix = details ? `: ${details}` : "";
      throw new Error(`Upstream HTTP ${response.status} ${response.statusText}${suffix}`);
    }

    if (!expectResponse) {
      return {};
    }

    const text = await response.text();
    let json;
    try {
      json = JSON.parse(text);
    } catch (err) {
      throw new Error(`Invalid JSON from upstream: ${err.message}`);
    }

    await this.traffic.log({
      ts: nowIso(),
      direction: "upstream-in",
      serverId: this.server.serverId,
      method: payload.method,
      payload: json
    });

    return json;
  }
}

class ServerRegistry {
  constructor(config, traffic) {
    this.config = config;
    this.traffic = traffic;
    this.servers = new Map();
    this.lastScanAt = 0;
    this.refreshing = null;

    this.projectIndexByName = new Map();
    this.projectIndexByKey = new Map();
    this.projectMappings = [];
    this.windowIndex = new Map();
    this.executionIndex = new Map();
    this.resourceIndex = new Map();
  }

  listOnlineServers() {
    return [...this.servers.values()].filter((server) => server.status === "online");
  }

  compareServersByFreshness(left, right) {
    const lMeta = left.metadata || {};
    const rMeta = right.metadata || {};

    const pluginCmp = compareVersionStringsDescending(lMeta.plugin && lMeta.plugin.version, rMeta.plugin && rMeta.plugin.version);
    if (pluginCmp !== 0) return pluginCmp;

    const ideCmp = compareVersionStringsDescending(lMeta.ide && lMeta.ide.version, rMeta.ide && rMeta.ide.version);
    if (ideCmp !== 0) return ideCmp;

    const buildCmp = compareVersionStringsDescending(lMeta.ide && lMeta.ide.build, rMeta.ide && rMeta.ide.build);
    if (buildCmp !== 0) return buildCmp;

    const seenCmp = compareIsoTimesDescending(left.lastSeenAt, right.lastSeenAt);
    if (seenCmp !== 0) return seenCmp;

    if (left.pid > right.pid) return -1;
    if (left.pid < right.pid) return 1;
    return left.serverId.localeCompare(right.serverId);
  }

  listOnlineServersByFreshness() {
    return this.listOnlineServers().sort((a, b) => this.compareServersByFreshness(a, b));
  }

  getServer(serverId) {
    return this.servers.get(serverId) || null;
  }

  resolveServerIdHint(serverHint) {
    if (serverHint == null) return null;
    const raw = String(serverHint).trim();
    if (!raw) return null;
    if (this.servers.has(raw)) return raw;

    const normalized = raw.toLowerCase();
    const online = this.listOnlineServersByFreshness();

    const byLabel = online.filter((server) => String(server.label || "").trim().toLowerCase() === normalized);
    if (byLabel.length === 1) return byLabel[0].serverId;

    if ((normalized === "intellij" || normalized === "default_api" || normalized === "mcp-steroid") && online.length === 1) {
      return online[0].serverId;
    }

    return null;
  }

  getServerSummary(serverId) {
    const server = this.getServer(serverId);
    if (!server) return null;
    return {
      serverId: server.serverId,
      serverLabel: server.label,
      serverUrl: server.url,
      serverPort: server.port,
      ide: server.metadata && server.metadata.ide ? server.metadata.ide : null,
      plugin: server.metadata && server.metadata.plugin ? server.metadata.plugin : null
    };
  }

  upsert(entry) {
    const serverId = buildServerId(entry.pid, entry.url, this.servers);
    let server = this.servers.get(serverId);

    if (!server) {
      server = {
        serverId,
        pid: entry.pid,
        url: entry.url,
        baseUrl: baseUrlFromMcpUrl(entry.url),
        bridgeBaseUrl: `${baseUrlFromMcpUrl(entry.url)}/npx/v1`,
        port: portFromUrl(entry.url),
        label: entry.label,
        markerPath: entry.markerPath,
        status: "offline",
        lastSeenAt: null,
        discovered: true,

        metadata: null,
        metadataFetchedAt: 0,

        tools: null,
        toolsFetchedAt: 0,
        resources: null,
        resourcesFetchedAt: 0,
        projects: null,
        projectsFetchedAt: 0,
        windows: null,
        windowsFetchedAt: 0,
        products: null,
        productsFetchedAt: 0
      };
      server.client = new UpstreamClient(server, this.config, this.traffic);
      this.servers.set(serverId, server);
    } else {
      server.url = entry.url;
      server.baseUrl = baseUrlFromMcpUrl(entry.url);
      server.bridgeBaseUrl = `${server.baseUrl}/npx/v1`;
      server.port = portFromUrl(entry.url);
      server.label = entry.label;
      server.markerPath = entry.markerPath;
      server.discovered = true;
    }

    return server;
  }

  async refreshDiscovery() {
    if (this.refreshing) return this.refreshing;

    this.refreshing = (async () => {
      const homeDir = this.config.homeDir || os.homedir();
      const discovered = await scanMarkers(homeDir, this.config.allowHosts);
      const seen = new Set();

      for (const entry of discovered) {
        const server = this.upsert(entry);
        seen.add(server.serverId);
      }

      for (const server of this.servers.values()) {
        if (!seen.has(server.serverId)) {
          server.discovered = false;
          server.status = "offline";
        }
      }

      for (const server of this.servers.values()) {
        if (!server.discovered) continue;
        await this.checkHealth(server);
        if (server.status === "online") {
          await this.refreshServerSnapshot(server);
        }
      }

      this.rebuildProjectIndexFromCaches();
      this.rebuildWindowIndexFromCaches();
      this.lastScanAt = Date.now();
    })();

    try {
      await this.refreshing;
    } finally {
      this.refreshing = null;
    }
  }

  async ensureFresh() {
    const now = Date.now();
    if (now - this.lastScanAt > this.config.scanIntervalMs) {
      await this.refreshDiscovery();
    }
  }

  async checkHealth(server) {
    const headers = { Accept: "application/json" };
    let ok = false;
    try {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), this.config.upstreamTimeoutMs);
      let response;
      try {
        response = await fetch(server.url, { method: "GET", headers, signal: controller.signal });
      } finally {
        clearTimeout(timeout);
      }

      if (response.ok) {
        const payload = await response.json();
        ok = payload && payload.status === "available";
      }
    } catch (_) {
      ok = false;
    }

    server.status = ok ? "online" : "offline";
    if (ok) {
      server.lastSeenAt = nowIso();
    }
  }

  async refreshServerSnapshot(server) {
    const ttlMs = this.config.cache.enabled ? this.config.cache.ttlSeconds * 1000 : 0;
    const now = Date.now();

    if (!server.metadata || now - server.metadataFetchedAt > ttlMs) {
      const bridgeMetadata = await this.fetchBridgeServerMetadata(server.serverId);
      if (bridgeMetadata && typeof bridgeMetadata === "object") {
        this.applyMetadata(server.serverId, normalizeServerMetadataPayload(bridgeMetadata));
        server.metadataFetchedAt = now;
      }
    }

    if (!server.tools || now - server.toolsFetchedAt > ttlMs) {
      try {
        const result = await server.client.sendRequest("tools/list", {});
        server.tools = Array.isArray(result.tools) ? result.tools : [];
        server.toolsFetchedAt = now;
      } catch (_) {
        server.tools = server.tools || [];
      }
    }

    if (!server.resources || now - server.resourcesFetchedAt > ttlMs) {
      try {
        const result = await server.client.sendRequest("resources/list", {});
        server.resources = Array.isArray(result.resources) ? result.resources : [];
        server.resourcesFetchedAt = now;
      } catch (_) {
        server.resources = server.resources || [];
      }
    }
  }

  async fetchToolPayload(server, toolName, args) {
    try {
      const result = await server.client.sendRequest("tools/call", {
        name: toolName,
        arguments: args || {}
      });
      if (result && result.isError) return null;
      return extractJsonFromToolResult(result);
    } catch (_) {
      return null;
    }
  }

  async fetchBridgeJson(serverId, bridgePath) {
    const server = this.servers.get(serverId);
    if (!server || server.status !== "online") return null;
    const url = `${server.bridgeBaseUrl}${bridgePath}`;

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.config.upstreamTimeoutMs);
    try {
      const response = await fetch(url, {
        method: "GET",
        headers: { Accept: "application/json" },
        signal: controller.signal
      });
      if (!response.ok) return null;
      return await response.json();
    } catch (_) {
      return null;
    } finally {
      clearTimeout(timeout);
    }
  }

  async fetchBridgeServerMetadata(serverId) {
    return this.fetchBridgeJson(serverId, "/server-metadata");
  }

  async fetchBridgeProducts(serverId) {
    return this.fetchBridgeJson(serverId, "/products");
  }

  applyMetadata(serverId, patch) {
    if (!patch) return;
    const server = this.servers.get(serverId);
    if (!server) return;
    server.metadata = mergeServerMetadata(server.metadata, patch);
  }

  updateServerProjects(serverId, projects, metadataPatch = null) {
    const server = this.servers.get(serverId);
    if (!server) return;
    server.projects = Array.isArray(projects) ? projects : [];
    server.projectsFetchedAt = Date.now();
    this.applyMetadata(serverId, metadataPatch);
  }

  updateServerWindows(serverId, windows, metadataPatch = null) {
    const server = this.servers.get(serverId);
    if (!server) return;
    server.windows = Array.isArray(windows) ? windows : [];
    server.windowsFetchedAt = Date.now();
    this.applyMetadata(serverId, metadataPatch);
  }

  updateServerProducts(serverId, products, metadataPatch = null) {
    const server = this.servers.get(serverId);
    if (!server) return;
    server.products = Array.isArray(products) ? products : [];
    server.productsFetchedAt = Date.now();
    this.applyMetadata(serverId, metadataPatch);
  }

  rebuildProjectIndexFromCaches() {
    this.projectIndexByName = new Map();
    this.projectIndexByKey = new Map();
    this.projectMappings = [];

    for (const server of this.listOnlineServersByFreshness()) {
      const projects = Array.isArray(server.projects) ? server.projects : [];
      for (const project of projects) {
        if (!project || !project.name) continue;
        const projectPath = typeof project.path === "string" ? project.path : "";

        this.projectMappings.push({
          projectName: project.name,
          projectPath,
          serverId: server.serverId,
          serverLabel: server.label,
          serverUrl: server.url,
          serverPort: server.port,
          ide: server.metadata && server.metadata.ide ? server.metadata.ide : null,
          plugin: server.metadata && server.metadata.plugin ? server.metadata.plugin : null
        });

        if (!this.projectIndexByName.has(project.name)) {
          this.projectIndexByName.set(project.name, []);
        }
        const listByName = this.projectIndexByName.get(project.name);
        if (!listByName.includes(server.serverId)) {
          listByName.push(server.serverId);
        }

        const key = projectKey(project.name, projectPath);
        if (!this.projectIndexByKey.has(key)) {
          this.projectIndexByKey.set(key, []);
        }
        const listByKey = this.projectIndexByKey.get(key);
        if (!listByKey.includes(server.serverId)) {
          listByKey.push(server.serverId);
        }
      }
    }

    for (const ids of this.projectIndexByName.values()) {
      ids.sort((a, b) => this.compareServersByFreshness(this.servers.get(a), this.servers.get(b)));
    }
    for (const ids of this.projectIndexByKey.values()) {
      ids.sort((a, b) => this.compareServersByFreshness(this.servers.get(a), this.servers.get(b)));
    }
  }

  rebuildWindowIndexFromCaches() {
    this.windowIndex = new Map();
    const rawOwners = new Map();

    for (const server of this.listOnlineServersByFreshness()) {
      const windows = Array.isArray(server.windows) ? server.windows : [];
      for (const window of windows) {
        if (!window || !window.windowId) continue;
        const raw = String(window.windowId);
        const namespaced = `${server.serverId}::${raw}`;
        this.windowIndex.set(namespaced, server.serverId);

        if (!rawOwners.has(raw)) {
          rawOwners.set(raw, new Set());
        }
        rawOwners.get(raw).add(server.serverId);
      }
    }

    for (const [rawWindowId, owners] of rawOwners.entries()) {
      if (owners.size === 1) {
        this.windowIndex.set(rawWindowId, [...owners][0]);
      }
    }
  }

  buildToolGroups() {
    const groups = new Map();
    const addTool = (tool) => {
      if (!groups.has(tool.name)) {
        groups.set(tool.name, []);
      }
      groups.get(tool.name).push(tool);
    };

    for (const tool of proxyTools()) {
      addTool(tool);
    }

    for (const server of this.listOnlineServers()) {
      for (const tool of server.tools || []) {
        addTool(tool);
      }
    }

    return groups;
  }

  buildResourceIndex() {
    this.resourceIndex = new Map();
    const resources = [];
    const seen = new Set();

    for (const server of this.listOnlineServersByFreshness()) {
      for (const resource of server.resources || []) {
        if (!resource || typeof resource.uri !== "string") continue;
        if (!seen.has(resource.uri)) {
          seen.add(resource.uri);
          resources.push(resource);
          this.resourceIndex.set(resource.uri, [server.serverId]);
        } else {
          const alias = buildAliasUri(server.serverId, resource.uri);
          resources.push({ ...resource, uri: alias });
          const list = this.resourceIndex.get(resource.uri) || [];
          list.push(server.serverId);
          this.resourceIndex.set(resource.uri, list);
        }
      }
    }

    return resources;
  }

  resolveServerForToolCall(name, args) {
    const serverIds = new Set(this.servers.keys());

    if (args && args.server_id) {
      const resolved = this.resolveServerIdHint(args.server_id);
      if (!resolved) {
        return { error: `Unknown server_id: ${args.server_id}` };
      }
      return { serverId: resolved, toolName: name };
    }

    const namespaced = parseNamespacedTool(name, serverIds);
    if (namespaced) return namespaced;

    if (args && args.project_name) {
      const byPath = args.project_path ? this.projectIndexByKey.get(projectKey(args.project_name, args.project_path)) : null;
      if (byPath && byPath.length > 0) {
        return { serverId: byPath[0], toolName: name };
      }
      const byName = this.projectIndexByName.get(args.project_name);
      if (byName && byName.length > 0) {
        return { serverId: byName[0], toolName: name };
      }
    }

    if (args && args.window_id) {
      const match = this.windowIndex.get(args.window_id);
      if (match) return { serverId: match, toolName: name };
    }

    if (args && (args.execution_id || args.screenshot_execution_id)) {
      const id = args.execution_id || args.screenshot_execution_id;
      const match = this.executionIndex.get(id);
      if (match) return { serverId: match, toolName: name };
    }

    const online = this.listOnlineServersByFreshness();
    if (online.length === 1) {
      return { serverId: online[0].serverId, toolName: name };
    }

    if (this.config.defaultServerId) {
      return { serverId: this.config.defaultServerId, toolName: name };
    }

    return { error: "Unable to route tool call; specify server_id or provide project_name/project_path" };
  }

  async callTool(serverId, toolName, args, onEvent = null) {
    const resolvedServerId = this.resolveServerIdHint(serverId) || serverId;
    const server = this.servers.get(resolvedServerId);
    if (!server) {
      return toolError(`Unknown server_id: ${serverId}`);
    }
    if (server.status !== "online") {
      return toolError(`Server ${serverId} is offline`);
    }

    const cleanArgs = args ? { ...args } : {};
    delete cleanArgs.server_id;

    const result = await server.client.callTool(toolName, cleanArgs, onEvent);

    this.captureExecutionIds(resolvedServerId, result);
    return result;
  }

  async callRpc(serverId, method, params) {
    const resolvedServerId = this.resolveServerIdHint(serverId) || serverId;
    const server = this.servers.get(resolvedServerId);
    if (!server) {
      throw new Error(`Unknown server_id: ${serverId}`);
    }
    if (server.status !== "online") {
      throw new Error(`Server ${serverId} is offline`);
    }
    return server.client.sendRequest(method, params);
  }

  captureExecutionIds(serverId, result) {
    if (!result || !Array.isArray(result.content)) return;
    for (const item of result.content) {
      if (!item || item.type !== "text") continue;
      if (typeof item.text !== "string") continue;
      const match = item.text.match(/Execution ID:\s*([\w-]+)/);
      if (match) {
        this.executionIndex.set(match[1], serverId);
      }
    }
  }
}

function proxyTools() {
  const emptySchema = { type: "object", properties: {}, required: [] };
  return [
    {
      name: "proxy_list_servers",
      description: "List discovered MCP Steroid servers and metadata.",
      inputSchema: emptySchema
    },
    {
      name: "proxy_list_projects",
      description: "Aggregate projects across all running IDE servers.",
      inputSchema: emptySchema
    },
    {
      name: "proxy_list_windows",
      description: "Aggregate windows across all running IDE servers.",
      inputSchema: emptySchema
    },
    {
      name: "proxy_list_products",
      description: "Aggregate product entries across all running IDE servers.",
      inputSchema: emptySchema
    },
    {
      name: "proxy_list_server_metadata",
      description: "Aggregate server metadata across all running IDE servers.",
      inputSchema: emptySchema
    },
    {
      name: AGGREGATE_TOOL_NAMES.projects,
      description: "Aggregate projects across all running IDE servers.",
      inputSchema: emptySchema
    },
    {
      name: AGGREGATE_TOOL_NAMES.windows,
      description: "Aggregate windows across all running IDE servers.",
      inputSchema: emptySchema
    },
    {
      name: AGGREGATE_TOOL_NAMES.products,
      description: "Aggregate product entries across all running IDE servers.",
      inputSchema: emptySchema
    },
    {
      name: AGGREGATE_TOOL_NAMES.metadata,
      description: "Aggregate server metadata across all running IDE servers.",
      inputSchema: emptySchema
    }
  ];
}

function toolResult(payload, isError = false) {
  return {
    content: [{ type: "text", text: JSON.stringify(payload) }],
    isError
  };
}

function toolError(message) {
  return {
    content: [{ type: "text", text: message }],
    isError: true
  };
}

function getTargetsByFreshness(registry, args) {
  const targetId = args && args.server_id ? String(args.server_id) : null;
  if (targetId) {
    const resolved = registry.resolveServerIdHint(targetId);
    if (resolved) return { targets: [resolved], error: null };
    return { targets: [], error: `Unknown server_id: ${targetId}` };
  }
  return {
    targets: registry.listOnlineServersByFreshness().map((server) => server.serverId),
    error: null
  };
}

async function handleAggregateProjects(registry, args) {
  const { targets, error } = getTargetsByFreshness(registry, args);
  if (error) return toolError(error);
  const errors = [];
  const projects = [];

  await Promise.all(targets.map(async (serverId) => {
    try {
      const result = await registry.callTool(serverId, AGGREGATE_TOOL_NAMES.projects, {});
      if (result.isError) {
        errors.push({ serverId, message: "Upstream error" });
        return;
      }
      const payload = extractJsonFromToolResult(result);
      if (!payload || !Array.isArray(payload.projects)) {
        errors.push({ serverId, message: "Invalid response" });
        return;
      }

      registry.updateServerProjects(serverId, payload.projects, metadataFromProjectsPayload(payload));
      const summary = registry.getServerSummary(serverId);

      for (const project of payload.projects) {
        const projectPath = typeof project.path === "string" ? project.path : "";
        projects.push({
          ...project,
          projectId: `${serverId}::${projectPath || project.name}`,
          serverId,
          serverLabel: summary && summary.serverLabel,
          serverUrl: summary && summary.serverUrl,
          serverPort: summary && summary.serverPort,
          ide: summary && summary.ide,
          plugin: summary && summary.plugin
        });
      }
    } catch (err) {
      errors.push({ serverId, message: err.message });
    }
  }));

  registry.rebuildProjectIndexFromCaches();
  return toolResult({
    projects,
    projectMappings: registry.projectMappings,
    errors
  });
}

async function handleAggregateWindows(registry, args) {
  const { targets, error } = getTargetsByFreshness(registry, args);
  if (error) return toolError(error);
  const errors = [];
  const windows = [];
  const backgroundTasks = [];

  await Promise.all(targets.map(async (serverId) => {
    try {
      const result = await registry.callTool(serverId, AGGREGATE_TOOL_NAMES.windows, {});
      if (result.isError) {
        errors.push({ serverId, message: "Upstream error" });
        return;
      }
      const payload = extractJsonFromToolResult(result);
      if (!payload || !Array.isArray(payload.windows)) {
        errors.push({ serverId, message: "Invalid response" });
        return;
      }

      registry.updateServerWindows(serverId, payload.windows, metadataFromWindowsPayload(payload));
      const summary = registry.getServerSummary(serverId);

      for (const window of payload.windows) {
        const rawWindowId = String(window.windowId);
        windows.push({
          ...window,
          serverWindowId: rawWindowId,
          windowId: `${serverId}::${rawWindowId}`,
          serverId,
          serverLabel: summary && summary.serverLabel,
          serverUrl: summary && summary.serverUrl,
          serverPort: summary && summary.serverPort,
          ide: summary && summary.ide,
          plugin: summary && summary.plugin
        });
      }

      if (Array.isArray(payload.backgroundTasks)) {
        for (const task of payload.backgroundTasks) {
          backgroundTasks.push({
            ...task,
            serverId,
            serverLabel: summary && summary.serverLabel,
            serverUrl: summary && summary.serverUrl,
            serverPort: summary && summary.serverPort
          });
        }
      }
    } catch (err) {
      errors.push({ serverId, message: err.message });
    }
  }));

  registry.rebuildWindowIndexFromCaches();

  const products = targets
    .map((serverId) => {
      const summary = registry.getServerSummary(serverId);
      if (!summary) return null;
      return {
        serverId,
        serverLabel: summary.serverLabel,
        serverUrl: summary.serverUrl,
        serverPort: summary.serverPort,
        ide: summary.ide,
        plugin: summary.plugin
      };
    })
    .filter(Boolean);

  return toolResult({ windows, backgroundTasks, products, errors });
}

async function handleAggregateProducts(registry, args) {
  const { targets, error } = getTargetsByFreshness(registry, args);
  if (error) return toolError(error);
  const errors = [];
  const products = [];

  await Promise.all(targets.map(async (serverId) => {
    try {
      const summary = registry.getServerSummary(serverId);
      const bridgePayload = await registry.fetchBridgeProducts(serverId);

      if (bridgePayload && Array.isArray(bridgePayload.products)) {
        registry.updateServerProducts(serverId, bridgePayload.products, metadataFromProductsPayload(bridgePayload));
        for (const product of bridgePayload.products) {
          products.push({
            ...product,
            serverId,
            serverLabel: summary && summary.serverLabel,
            serverUrl: summary && summary.serverUrl,
            serverPort: summary && summary.serverPort
          });
        }
        return;
      }

      const result = await registry.callTool(serverId, AGGREGATE_TOOL_NAMES.products, {});

      if (result.isError) {
        // Fallback: metadata from dedicated metadata handler/caches is still source data from IDE.
        const server = registry.getServer(serverId);
        if (server && server.metadata) {
          products.push({
            pid: server.metadata.pid,
            ide: server.metadata.ide || null,
            plugin: server.metadata.plugin || null,
            serverId,
            serverLabel: summary && summary.serverLabel,
            serverUrl: summary && summary.serverUrl,
            serverPort: summary && summary.serverPort
          });
        } else {
          errors.push({ serverId, message: "Upstream error" });
        }
        return;
      }

      const payload = extractJsonFromToolResult(result);
      if (!payload || !Array.isArray(payload.products)) {
        errors.push({ serverId, message: "Invalid response" });
        return;
      }

      registry.updateServerProducts(serverId, payload.products, metadataFromProductsPayload(payload));

      for (const product of payload.products) {
        products.push({
          ...product,
          serverId,
          serverLabel: summary && summary.serverLabel,
          serverUrl: summary && summary.serverUrl,
          serverPort: summary && summary.serverPort
        });
      }
    } catch (err) {
      errors.push({ serverId, message: err.message });
    }
  }));

  return toolResult({ products, errors });
}

async function handleAggregateServerMetadata(registry, args) {
  const { targets, error } = getTargetsByFreshness(registry, args);
  if (error) return toolError(error);
  const errors = [];
  const servers = [];

  await Promise.all(targets.map(async (serverId) => {
    const summary = registry.getServerSummary(serverId);
    try {
      const bridgePayload = await registry.fetchBridgeServerMetadata(serverId);
      if (bridgePayload && typeof bridgePayload === "object") {
        const patch = normalizeServerMetadataPayload(bridgePayload);
        registry.applyMetadata(serverId, patch);
        servers.push({
          serverId,
          serverLabel: summary && summary.serverLabel,
          serverUrl: summary && summary.serverUrl,
          serverPort: summary && summary.serverPort,
          metadata: registry.getServer(serverId).metadata
        });
        return;
      }

      const result = await registry.callTool(serverId, AGGREGATE_TOOL_NAMES.metadata, {});
      if (result.isError) {
        const server = registry.getServer(serverId);
        if (server && server.metadata) {
          servers.push({
            serverId,
            serverLabel: summary && summary.serverLabel,
            serverUrl: summary && summary.serverUrl,
            serverPort: summary && summary.serverPort,
            metadata: server.metadata
          });
        } else {
          errors.push({ serverId, message: "Upstream error" });
        }
        return;
      }

      const payload = extractJsonFromToolResult(result);
      if (!payload || typeof payload !== "object") {
        errors.push({ serverId, message: "Invalid response" });
        return;
      }

      const patch = normalizeServerMetadataPayload(payload);
      registry.applyMetadata(serverId, patch);
      servers.push({
        serverId,
        serverLabel: summary && summary.serverLabel,
        serverUrl: summary && summary.serverUrl,
        serverPort: summary && summary.serverPort,
        metadata: registry.getServer(serverId).metadata
      });
    } catch (err) {
      errors.push({ serverId, message: err.message });
    }
  }));

  return toolResult({ servers, errors });
}

async function handleListServers(registry) {
  const servers = registry.listOnlineServersByFreshness().map((server) => ({
    serverId: server.serverId,
    label: server.label,
    url: server.url,
    port: server.port,
    status: server.status,
    lastSeenAt: server.lastSeenAt,
    toolCount: Array.isArray(server.tools) ? server.tools.length : 0,
    resourceCount: Array.isArray(server.resources) ? server.resources.length : 0,
    ide: server.metadata && server.metadata.ide ? server.metadata.ide : null,
    plugin: server.metadata && server.metadata.plugin ? server.metadata.plugin : null
  }));
  return toolResult({ servers });
}

function buildVersionEndpointUrl(registry) {
  const freshest = registry.listOnlineServersByFreshness()[0];
  const ideBuild = freshest
    && freshest.metadata
    && freshest.metadata.ide
    && typeof freshest.metadata.ide.build === "string"
    ? freshest.metadata.ide.build
    : null;
  if (!ideBuild) return "https://mcp-steroid.jonnyzzz.com/version.json";
  return `https://mcp-steroid.jonnyzzz.com/version.json?intellij-version=${encodeURIComponent(ideBuild)}`;
}

function buildUpdateUserAgent(config, registry) {
  const freshest = registry.listOnlineServersByFreshness()[0];
  const ideBuild = freshest
    && freshest.metadata
    && freshest.metadata.ide
    && typeof freshest.metadata.ide.build === "string"
    ? freshest.metadata.ide.build
    : null;
  const proxyVersion = config.version || "0.1.0";
  if (!ideBuild) {
    return `MCP-Steroid-Proxy/${proxyVersion}`;
  }
  return `MCP-Steroid-Proxy/${proxyVersion} (IntelliJ/${ideBuild})`;
}

function buildUpdateCheckConfig(config) {
  const raw = config && config.updates && typeof config.updates === "object" ? config.updates : {};
  const initialDelayMs = Number.isFinite(raw.initialDelayMs) && raw.initialDelayMs >= 0
    ? raw.initialDelayMs
    : DEFAULT_CONFIG.updates.initialDelayMs;
  const intervalMs = Number.isFinite(raw.intervalMs) && raw.intervalMs > 0
    ? raw.intervalMs
    : DEFAULT_CONFIG.updates.intervalMs;
  const requestTimeoutMs = Number.isFinite(raw.requestTimeoutMs) && raw.requestTimeoutMs > 0
    ? raw.requestTimeoutMs
    : DEFAULT_CONFIG.updates.requestTimeoutMs;
  return {
    enabled: raw.enabled !== false,
    initialDelayMs,
    intervalMs,
    requestTimeoutMs
  };
}

async function fetchRemoteVersionBase(registry, config) {
  const updateCheck = buildUpdateCheckConfig(config);
  const url = buildVersionEndpointUrl(registry);
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), updateCheck.requestTimeoutMs);
  try {
    const response = await fetch(url, {
      method: "GET",
      headers: {
        Accept: "application/json",
        "User-Agent": buildUpdateUserAgent(config, registry)
      },
      signal: controller.signal
    });
    if (!response.ok) return null;
    const payload = await response.json();
    if (!payload || typeof payload !== "object") return null;
    const value = typeof payload["version-base"] === "string" ? payload["version-base"] : null;
    return value ? extractBaseVersion(value) : null;
  } catch (_) {
    return null;
  } finally {
    clearTimeout(timeout);
  }
}

function pickRecommendedVersion(remoteBase, pluginBase) {
  if (remoteBase && pluginBase) {
    return isVersionNewer(remoteBase, pluginBase) ? remoteBase : pluginBase;
  }
  return remoteBase || pluginBase || null;
}

function needsUpgradeByServerRule(currentVersion, remoteVersionBase) {
  if (typeof currentVersion !== "string") return false;
  const current = currentVersion.trim();
  if (!current) return false;
  const remoteBase = extractBaseVersion(remoteVersionBase || "");
  if (!remoteBase) return false;
  // Match IDE plugin update logic: update when current does not start with remote base.
  return !current.startsWith(remoteBase);
}

async function buildUpgradeNotice(registry, config) {
  const currentVersion = typeof config.version === "string" ? config.version : "";
  const currentBase = extractBaseVersion(currentVersion);
  if (!currentBase) return null;

  const remoteBase = await fetchRemoteVersionBase(registry, config);
  if (!remoteBase) return null;
  if (!needsUpgradeByServerRule(currentVersion, remoteBase)) return null;

  return `[mcp-steroid-proxy] Upgrade recommended: current ${currentBase}, latest ${remoteBase}. Run: npm i -g mcp-steroid-proxy`;
}

function jsonRpcResult(id, result) {
  return { jsonrpc: JSONRPC_VERSION, id, result };
}

function jsonRpcError(id, code, message) {
  return { jsonrpc: JSONRPC_VERSION, id, error: { code, message } };
}

function createServerInfo(config) {
  return {
    name: "mcp-steroid-proxy",
    version: config.version || "0.1.0"
  };
}

function extractClientProgressToken(args) {
  if (!args || typeof args !== "object") return null;
  const meta = args._meta;
  if (!meta || typeof meta !== "object") return null;
  const token = meta.progressToken;
  if (typeof token === "string" || typeof token === "number") {
    return token;
  }
  return null;
}

function createProgressToken() {
  return `npx-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

async function handleRpc(method, params, registry, beacon = null, notify = null) {
  if (method !== "initialize" && method !== "ping") {
    await registry.ensureFresh();
  }

  if (method === "initialize") {
    return {
      protocolVersion: PROTOCOL_VERSION,
      capabilities: {
        tools: { listChanged: false },
        prompts: { listChanged: false },
        resources: { subscribe: false, listChanged: false }
      },
      serverInfo: createServerInfo(registry.config),
      instructions: "Proxy MCP server for MCP Steroid instances discovered from local IDE metadata."
    };
  }

  if (method === "ping") {
    return {};
  }

  if (method === "tools/list") {
    const groups = registry.buildToolGroups();
    return { tools: mergeToolGroups(groups) };
  }

  if (method === "resources/list") {
    const resources = registry.buildResourceIndex();
    return { resources };
  }

  if (method === "resources/read") {
    const uri = params && params.uri ? params.uri : null;
    if (!uri) {
      const err = new Error("Missing uri");
      err.code = -32602;
      throw err;
    }

    registry.buildResourceIndex();
    const alias = parseAliasUri(uri);
    const lookupUri = alias ? alias.uri : uri;
    const serverIds = alias ? [alias.serverId] : (registry.resourceIndex.get(lookupUri) || []);
    const serverId = serverIds[0];
    if (!serverId) {
      throw new Error(`Resource not found: ${uri}`);
    }
    return registry.callRpc(serverId, "resources/read", { uri: lookupUri });
  }

  if (method === "tools/call") {
    const toolName = params && params.name ? params.name : null;
    const args = params && params.arguments ? params.arguments : {};
    const captureToolCall = (result, route, extra = {}) => {
      if (beacon) {
        beacon.capture(BEACON_EVENTS.toolCall, {
          tool_name: toolName || "<missing>",
          route,
          status: result && result.isError ? "error" : "ok",
          ...extra
        });
      }
      return result;
    };

    if (!toolName) {
      return captureToolCall(toolError("Missing tool name"), "invalid");
    }

    if (toolName === "proxy_list_servers") {
      return captureToolCall(await handleListServers(registry), "proxy");
    }
    if (toolName === "proxy_list_projects" || toolName === AGGREGATE_TOOL_NAMES.projects) {
      return captureToolCall(await handleAggregateProjects(registry, args), "aggregate");
    }
    if (toolName === "proxy_list_windows" || toolName === AGGREGATE_TOOL_NAMES.windows) {
      return captureToolCall(await handleAggregateWindows(registry, args), "aggregate");
    }
    if (toolName === "proxy_list_products" || toolName === AGGREGATE_TOOL_NAMES.products) {
      return captureToolCall(await handleAggregateProducts(registry, args), "aggregate");
    }
    if (toolName === "proxy_list_server_metadata" || toolName === AGGREGATE_TOOL_NAMES.metadata) {
      return captureToolCall(await handleAggregateServerMetadata(registry, args), "aggregate");
    }

    const resolved = registry.resolveServerForToolCall(toolName, args || {});
    if (resolved.error) {
      return captureToolCall(toolError(resolved.error), "resolve");
    }

    const progressToken = extractClientProgressToken(args) || createProgressToken();
    let lastProgress = 0;
    const emitProgress = async (progress, message, total = null) => {
      if (typeof notify !== "function") return;
      const safeProgress = Number.isFinite(progress) ? progress : lastProgress;
      if (Number.isFinite(safeProgress)) {
        lastProgress = safeProgress;
      }
      const payload = {
        progressToken,
        progress: lastProgress
      };
      if (typeof message === "string" && message.trim()) {
        payload.message = message;
      }
      if (Number.isFinite(total)) {
        payload.total = total;
      }
      await notify("notifications/progress", payload);
    };

    await emitProgress(0, `Tool call started: ${resolved.toolName}`);
    const delegated = await registry.callTool(
      resolved.serverId,
      resolved.toolName,
      args || {},
      async (event) => {
        if (!event || typeof event !== "object") return;
        if (event.type === "progress") {
          await emitProgress(
            Number.isFinite(event.progress) ? event.progress : lastProgress,
            typeof event.message === "string" ? event.message : null,
            Number.isFinite(event.total) ? event.total : null
          );
          return;
        }
        if (event.type === "heartbeat") {
          await emitProgress(lastProgress, typeof event.message === "string" ? event.message : "Tool call heartbeat");
        }
      }
    );
    await emitProgress(1, `Tool call completed: ${resolved.toolName}`);
    return captureToolCall(delegated, "delegated", { server_id: resolved.serverId });
  }

  const err = new Error(`Method not found: ${method}`);
  err.code = -32601;
  throw err;
}

function decodeContentLength(headersText) {
  const lines = headersText.split(/\r?\n/);
  for (const line of lines) {
    const idx = line.indexOf(":");
    if (idx <= 0) continue;
    const key = line.slice(0, idx).trim().toLowerCase();
    if (key !== "content-length") continue;
    const value = Number(line.slice(idx + 1).trim());
    if (Number.isFinite(value) && value >= 0) {
      return value;
    }
  }
  return null;
}

function startsLikeJsonPayload(buffer) {
  const prefix = buffer.toString("utf8", 0, Math.min(buffer.length, 64)).trimStart();
  return prefix.startsWith("{") || prefix.startsWith("[");
}

function readNextFramedMessage(buffer) {
  const headerSep = Buffer.from("\r\n\r\n");
  const altHeaderSep = Buffer.from("\n\n");
  let headerEnd = buffer.indexOf(headerSep);
  let delimiterLength = 4;

  if (headerEnd < 0) {
    headerEnd = buffer.indexOf(altHeaderSep);
    delimiterLength = 2;
  }

  if (headerEnd >= 0) {
    const headersText = buffer.slice(0, headerEnd).toString("utf8");
    const bodyLength = decodeContentLength(headersText);
    if (bodyLength != null) {
      const total = headerEnd + delimiterLength + bodyLength;
      if (buffer.length < total) return null;
      const payloadText = buffer.slice(headerEnd + delimiterLength, total).toString("utf8");
      return {
        consumed: total,
        payloadText,
        mode: "framed"
      };
    }
  }

  // Newline-delimited JSON fallback is supported only when input starts as JSON.
  // This prevents mis-parsing partial Content-Length headers as JSON payload.
  if (!startsLikeJsonPayload(buffer)) {
    return null;
  }

  const newline = buffer.indexOf(0x0a);
  if (newline < 0) return null;
  const payloadText = buffer.slice(0, newline).toString("utf8").trim();
  return {
    consumed: newline + 1,
    payloadText,
    mode: "ndjson"
  };
}

function encodeFramedMessage(payload) {
  const text = JSON.stringify(payload);
  const bytes = Buffer.byteLength(text, "utf8");
  return `Content-Length: ${bytes}\r\n\r\n${text}`;
}

function encodeNdjsonMessage(payload) {
  return `${JSON.stringify(payload)}\n`;
}

function createStdioServer(registry, traffic, beacon = null) {
  let inputBuffer = Buffer.alloc(0);
  let chain = Promise.resolve();
  let outputMode = null;

  const writeResponse = async (payload) => {
    await traffic.log({
      ts: nowIso(),
      direction: "proxy-out",
      payload
    });
    const message = outputMode === "ndjson"
      ? encodeNdjsonMessage(payload)
      : encodeFramedMessage(payload);
    process.stdout.write(message);
  };

  const handleSingle = async (request) => {
    if (!request || typeof request !== "object") {
      return jsonRpcError(null, -32600, "Invalid request");
    }

    const id = request.id;
    const method = request.method;
    if (id === undefined || id === null) {
      // Notification
      return null;
    }

    if (!method) {
      return jsonRpcError(id, -32600, "Missing method");
    }

    try {
      const result = await handleRpc(
        method,
        request.params || {},
        registry,
        beacon,
        async (notifyMethod, notifyParams) => {
          await writeResponse({
            jsonrpc: JSONRPC_VERSION,
            method: notifyMethod,
            params: notifyParams
          });
        }
      );
      return jsonRpcResult(id, result);
    } catch (err) {
      const code = typeof err.code === "number" ? err.code : -32603;
      return jsonRpcError(id, code, err.message || "Internal error");
    }
  };

  const handlePayload = async (payload) => {
    if (!payload) {
      await writeResponse(jsonRpcError(null, -32600, "Empty request body"));
      return;
    }

    await traffic.log({
      ts: nowIso(),
      direction: "proxy-in",
      payload
    });

    if (Array.isArray(payload)) {
      const responses = [];
      for (const request of payload) {
        const response = await handleSingle(request);
        if (response) responses.push(response);
      }
      if (responses.length > 0) {
        await writeResponse(responses);
      }
      return;
    }

    if (typeof payload !== "object") {
      await writeResponse(jsonRpcError(null, -32600, "Invalid request"));
      return;
    }

    const response = await handleSingle(payload);
    if (response) {
      await writeResponse(response);
    }
  };

  const onChunk = (chunk) => {
    inputBuffer = Buffer.concat([inputBuffer, chunk]);

    while (true) {
      const frame = readNextFramedMessage(inputBuffer);
      if (!frame) break;
      inputBuffer = inputBuffer.slice(frame.consumed);
      if (!outputMode && frame.mode) {
        outputMode = frame.mode;
      }

      if (!frame.payloadText) continue;

      chain = chain
        .then(async () => {
          let parsed;
          try {
            parsed = JSON.parse(frame.payloadText);
          } catch (err) {
            await writeResponse(jsonRpcError(null, -32700, `Parse error: ${err.message}`));
            return;
          }
          await handlePayload(parsed);
        })
        .catch(async (err) => {
          await writeResponse(jsonRpcError(null, -32603, err.message || "Internal error"));
        });
    }
  };

  process.stdin.on("data", onChunk);
  process.stdin.on("end", () => {
    if (!beacon) {
      process.exit(0);
      return;
    }
    beacon.shutdown().finally(() => {
      process.exit(0);
    });
  });
  process.stdin.resume();
}

async function runCliMode(args, registry, traffic, beacon = null) {
  const request = buildCliRequest(args);
  await traffic.log({
    ts: nowIso(),
    direction: "cli-in",
    payload: request
  });

  const result = await handleRpc(request.method, request.params, registry, beacon);
  await traffic.log({
    ts: nowIso(),
    direction: "cli-out",
    payload: result
  });

  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) {
    process.stderr.write(
      [
        "Usage: mcp-steroid-proxy [--config path] [--scan-interval ms] [--log-traffic] [--cli]",
        "",
        "Default mode:",
        "  stdio MCP server over stdin/stdout",
        "",
        "CLI mode:",
        "  --cli [--cli-method <method> --cli-params-json '<json>']",
        "  --cli --tool <toolName> [--arguments-json '<json>']",
        "  --cli --uri <resourceUri>",
        "",
        "CLI defaults to --cli-method tools/list when no CLI selector is provided."
      ].join("\n") + "\n"
    );
    return;
  }

  const config = await loadConfig(args);
  config.version = loadPackageVersion();

  const traffic = new TrafficLogger(config);
  const registry = new ServerRegistry(config, traffic);
  const beacon = new NpxBeacon(config);
  const updateCheck = buildUpdateCheckConfig(config);
  beacon.capture(BEACON_EVENTS.started, { mode: args.mode });

  await registry.refreshDiscovery();
  beacon.captureDiscoveryChanged(registry, "startup");
  let upgradeNoticeShown = false;
  let updateCheckInFlight = false;
  const emitUpgradeNotice = async () => {
    if (!updateCheck.enabled || updateCheckInFlight) return;
    updateCheckInFlight = true;
    try {
      const notice = await buildUpgradeNotice(registry, config);
      if (notice && !upgradeNoticeShown) {
        upgradeNoticeShown = true;
        process.stderr.write(`${notice}\n`);
        beacon.capture(BEACON_EVENTS.upgradeRecommended, {
          current_version: extractBaseVersion(config.version || "")
        });
      }
    } finally {
      updateCheckInFlight = false;
    }
  };

  if (args.mode === "cli") {
    await emitUpgradeNotice().catch(() => {
      // Ignore update-check failures in CLI mode
    });
    await runCliMode(args, registry, traffic, beacon);
    await beacon.shutdown();
    return;
  }

  if (updateCheck.enabled) {
    setTimeout(() => {
      emitUpgradeNotice().catch(() => {
        // Ignore periodic update-check failures
      });
      setInterval(() => {
        emitUpgradeNotice().catch(() => {
          // Ignore periodic update-check failures
        });
      }, updateCheck.intervalMs);
    }, updateCheck.initialDelayMs);
  }

  setInterval(() => {
    registry.refreshDiscovery()
      .then(() => {
        beacon.captureDiscoveryChanged(registry, "interval");
      })
      .catch(() => {
        // Ignore refresh/update failures
      });
  }, config.scanIntervalMs);

  beacon.startHeartbeat(registry);
  createStdioServer(registry, traffic, beacon);
}

if (require.main === module) {
  main().catch((err) => {
    process.stderr.write(`${err.stack || err.message}\n`);
    process.exit(1);
  });
}

module.exports = {
  BEACON_EVENTS,
  DEFAULT_CONFIG,
  parseArgs,
  loadConfig,
  isPidAlive,
  parseMarkerContent,
  scanMarkers,
  parseJsonFlag,
  buildCliRequest,
  buildAliasUri,
  parseAliasUri,
  parseNamespacedTool,
  mergeToolGroups,
  extractJsonFromToolResult,
  extractBaseVersion,
  isVersionNewer,
  pickRecommendedVersion,
  needsUpgradeByServerRule,
  buildUpdateCheckConfig,
  buildBeaconConfig,
  NpxBeacon,
  mergeServerMetadata,
  metadataFromInitializeResult,
  metadataFromProductsPayload,
  metadataFromWindowsPayload,
  metadataFromProjectsPayload,
  ServerRegistry,
  readNextFramedMessage,
  encodeFramedMessage,
  parseSseEventBlock,
  handleRpc
};

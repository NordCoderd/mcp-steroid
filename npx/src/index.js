#!/usr/bin/env node
"use strict";

const fs = require("fs");
const fsp = require("fs/promises");
const path = require("path");
const os = require("os");
const http = require("http");
const crypto = require("crypto");
const { URL } = require("url");

const PROTOCOL_VERSION = "2025-06-18";
const JSONRPC_VERSION = "2.0";
const SESSION_HEADER = "Mcp-Session-Id";
const SESSION_NOTICE_HEADER = "Mcp-Session-Notice";
const UNKNOWN_SESSION_NOTICE = "Unknown session; new session created. Update stored Mcp-Session-Id.";

const DEFAULT_CONFIG = {
  server: {
    host: "127.0.0.1",
    port: 33100
  },
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
  upstreamTimeoutMs: 5000
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
    host: null,
    port: null,
    logTraffic: null,
    help: false
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--config") {
      out.configPath = argv[i + 1];
      i += 1;
    } else if (arg === "--host") {
      out.host = argv[i + 1];
      i += 1;
    } else if (arg === "--port") {
      out.port = Number(argv[i + 1]);
      i += 1;
    } else if (arg === "--log-traffic") {
      out.logTraffic = true;
    } else if (arg === "-h" || arg === "--help") {
      out.help = true;
    }
  }
  return out;
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
  let config = mergeDeep(DEFAULT_CONFIG, fileConfig);
  if (args.host) {
    config.server.host = args.host;
  }
  if (Number.isFinite(args.port) && args.port > 0) {
    config.server.port = args.port;
  }
  if (args.logTraffic === true) {
    config.trafficLog.enabled = true;
  }
  config.cache.dir = expandHome(config.cache.dir);
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

function acceptsJson(acceptHeader) {
  return acceptHeader.split(",").some((part) => {
    const mediaType = part.trim().split(";")[0].trim();
    return mediaType === "application/json" || mediaType === "*/*" || mediaType === "application/*";
  });
}

function acceptsSse(acceptHeader) {
  return acceptHeader.split(",").some((part) => {
    const mediaType = part.trim().split(";")[0].trim();
    return mediaType === "text/event-stream" || mediaType === "*/*" || mediaType === "text/*";
  });
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
      const parsed = JSON.parse(item.text);
      return parsed;
    } catch (_) {
      continue;
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
      // Ignore logging errors
    }
  }
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
    await this.sendRequest("initialize", params, true);
    await this.sendNotification("initialized", {});
    this.initialized = true;
  }

  async sendNotification(method, params) {
    const payload = { jsonrpc: JSONRPC_VERSION, method, params };
    await this.sendPayload(payload, false, true);
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
    const response = await this.sendPayload(payload, true, false);
    if (response.error) {
      throw new Error(response.error.message || "Upstream error");
    }
    return response.result;
  }

  async sendPayload(payload, expectResponse, isNotification) {
    const headers = {
      "Content-Type": "application/json",
      Accept: "application/json"
    };
    if (this.sessionId) {
      headers[SESSION_HEADER] = this.sessionId;
    }
    await this.traffic.log({
      ts: nowIso(),
      direction: "out",
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

    if (!expectResponse || isNotification) {
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
      direction: "in",
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
    this.projectIndex = new Map();
    this.windowIndex = new Map();
    this.executionIndex = new Map();
    this.resourceIndex = new Map();
  }

  listOnlineServers() {
    return [...this.servers.values()].filter((server) => server.status === "online");
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
        label: entry.label,
        markerPath: entry.markerPath,
        status: "offline",
        lastSeenAt: null,
        tools: null,
        toolsFetchedAt: 0,
        resources: null,
        resourcesFetchedAt: 0,
        discovered: true
      };
      server.client = new UpstreamClient(server, this.config, this.traffic);
      this.servers.set(serverId, server);
    } else {
      server.url = entry.url;
      server.baseUrl = baseUrlFromMcpUrl(entry.url);
      server.label = entry.label;
      server.markerPath = entry.markerPath;
      server.discovered = true;
    }
    return server;
  }

  async refreshDiscovery() {
    if (this.refreshing) return this.refreshing;
    this.refreshing = (async () => {
      const discovered = await scanMarkers(os.homedir(), this.config.allowHosts);
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
          await this.ensureCatalog(server);
        }
      }
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

  async ensureCatalog(server) {
    const ttlMs = this.config.cache.enabled ? this.config.cache.ttlSeconds * 1000 : 0;
    const now = Date.now();
    if (!server.tools || now - server.toolsFetchedAt > ttlMs) {
      try {
        const result = await server.client.sendRequest("tools/list", {});
        server.tools = result.tools || [];
        server.toolsFetchedAt = now;
      } catch (_) {
        server.tools = server.tools || [];
      }
    }
    if (!server.resources || now - server.resourcesFetchedAt > ttlMs) {
      try {
        const result = await server.client.sendRequest("resources/list", {});
        server.resources = result.resources || [];
        server.resourcesFetchedAt = now;
      } catch (_) {
        server.resources = server.resources || [];
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
    for (const server of this.listOnlineServers()) {
      for (const resource of server.resources || []) {
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
      return { serverId: args.server_id, toolName: name };
    }
    const namespaced = parseNamespacedTool(name, serverIds);
    if (namespaced) return namespaced;
    if (args && args.project_name) {
      const match = this.projectIndex.get(args.project_name);
      if (match && match.length === 1) return { serverId: match[0], toolName: name };
      if (match && match.length > 1) {
        return { error: `Ambiguous project_name '${args.project_name}' across ${match.length} servers` };
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
    const online = this.listOnlineServers();
    if (online.length === 1) return { serverId: online[0].serverId, toolName: name };
    if (this.config.defaultServerId) return { serverId: this.config.defaultServerId, toolName: name };
    return { error: "Unable to route tool call; specify server_id or use namespaced tool" };
  }

  async callTool(serverId, toolName, args) {
    const server = this.servers.get(serverId);
    if (!server) {
      return toolError(`Unknown server_id: ${serverId}`);
    }
    if (server.status !== "online") {
      return toolError(`Server ${serverId} is offline`);
    }
    const cleanArgs = args ? { ...args } : {};
    delete cleanArgs.server_id;
    const result = await server.client.sendRequest("tools/call", {
      name: toolName,
      arguments: cleanArgs
    });
    this.captureExecutionIds(serverId, result);
    return result;
  }

  async callRpc(serverId, method, params) {
    const server = this.servers.get(serverId);
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
      const match = typeof item.text === "string" ? item.text.match(/Execution ID:\s*([\w-]+)/) : null;
      if (match) {
        this.executionIndex.set(match[1], serverId);
      }
    }
  }

  updateProjectIndex(projects) {
    this.projectIndex = new Map();
    for (const project of projects) {
      if (!project || !project.name) continue;
      if (!this.projectIndex.has(project.name)) {
        this.projectIndex.set(project.name, []);
      }
      this.projectIndex.get(project.name).push(project.serverId);
    }
  }

  updateWindowIndex(windows) {
    this.windowIndex = new Map();
    for (const window of windows) {
      if (!window || !window.windowId) continue;
      this.windowIndex.set(window.windowId, window.serverId);
    }
  }
}

function proxyTools() {
  return [
    {
      name: "proxy_list_servers",
      description: "List all discovered MCP Steroid servers and their status.",
      inputSchema: { type: "object", properties: {}, required: [] }
    },
    {
      name: "proxy_list_projects",
      description: "Aggregate projects across all servers.",
      inputSchema: { type: "object", properties: {}, required: [] }
    },
    {
      name: "proxy_list_windows",
      description: "Aggregate windows across all servers.",
      inputSchema: { type: "object", properties: {}, required: [] }
    },
    {
      name: "steroid_list_projects",
      description: "Aggregate projects across all servers.",
      inputSchema: { type: "object", properties: {}, required: [] }
    },
    {
      name: "steroid_list_windows",
      description: "Aggregate windows across all servers.",
      inputSchema: { type: "object", properties: {}, required: [] }
    }
  ];
}

function toolResult(payload, isError = false) {
  return {
    content: [
      {
        type: "text",
        text: JSON.stringify(payload)
      }
    ],
    isError
  };
}

function toolError(message) {
  return {
    content: [
      {
        type: "text",
        text: message
      }
    ],
    isError: true
  };
}

async function handleAggregateProjects(registry, args) {
  const targetId = args && args.server_id ? args.server_id : null;
  const targets = targetId ? [targetId] : registry.listOnlineServers().map((s) => s.serverId);
  const errors = [];
  const projects = [];

  await Promise.all(
    targets.map(async (serverId) => {
      try {
        const result = await registry.callTool(serverId, "steroid_list_projects", {});
        if (result.isError) {
          errors.push({ serverId, message: "Upstream error" });
          return;
        }
        const payload = extractJsonFromToolResult(result);
        if (!payload || !Array.isArray(payload.projects)) {
          errors.push({ serverId, message: "Invalid response" });
          return;
        }
        for (const project of payload.projects) {
          projects.push({
            ...project,
            serverId,
            serverLabel: registry.servers.get(serverId)?.label || serverId
          });
        }
      } catch (err) {
        errors.push({ serverId, message: err.message });
      }
    })
  );

  registry.updateProjectIndex(projects);
  return toolResult({ projects, errors });
}

async function handleAggregateWindows(registry, args) {
  const targetId = args && args.server_id ? args.server_id : null;
  const targets = targetId ? [targetId] : registry.listOnlineServers().map((s) => s.serverId);
  const errors = [];
  const windows = [];
  const backgroundTasks = [];

  await Promise.all(
    targets.map(async (serverId) => {
      try {
        const result = await registry.callTool(serverId, "steroid_list_windows", {});
        if (result.isError) {
          errors.push({ serverId, message: "Upstream error" });
          return;
        }
        const payload = extractJsonFromToolResult(result);
        if (!payload || !Array.isArray(payload.windows)) {
          errors.push({ serverId, message: "Invalid response" });
          return;
        }
        for (const window of payload.windows) {
          windows.push({
            ...window,
            serverId
          });
        }
        if (Array.isArray(payload.backgroundTasks)) {
          for (const task of payload.backgroundTasks) {
            backgroundTasks.push({
              ...task,
              serverId
            });
          }
        }
      } catch (err) {
        errors.push({ serverId, message: err.message });
      }
    })
  );

  registry.updateWindowIndex(windows);
  return toolResult({ windows, backgroundTasks, errors });
}

async function handleListServers(registry) {
  const servers = [...registry.servers.values()].map((server) => ({
    serverId: server.serverId,
    label: server.label,
    url: server.url,
    status: server.status,
    lastSeenAt: server.lastSeenAt,
    toolCount: server.tools ? server.tools.length : 0,
    resourceCount: server.resources ? server.resources.length : 0
  }));
  servers.sort((a, b) => a.serverId.localeCompare(b.serverId));
  return toolResult({ servers });
}

function jsonRpcResult(id, result) {
  return { jsonrpc: JSONRPC_VERSION, id, result };
}

function jsonRpcError(id, code, message) {
  return { jsonrpc: JSONRPC_VERSION, id, error: { code, message } };
}

function createServerInfo(config) {
  return {
    name: "intellij-mcp-steroid-proxy",
    version: config.version || "0.1.0"
  };
}

async function handleRpc(method, params, registry) {
  await registry.ensureFresh();
  if (method === "initialize") {
    return {
      protocolVersion: PROTOCOL_VERSION,
      capabilities: {
        tools: { listChanged: false },
        resources: { subscribe: false, listChanged: false }
      },
      serverInfo: createServerInfo(registry.config)
    };
  }
  if (method === "ping") {
    return {};
  }
  if (method === "tools/list") {
    const groups = registry.buildToolGroups();
    const tools = mergeToolGroups(groups);
    return { tools };
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
    const serverId = alias ? alias.serverId : (registry.resourceIndex.get(lookupUri) || [])[0];
    if (!serverId) {
      throw new Error(`Resource not found: ${uri}`);
    }
    return registry.callRpc(serverId, "resources/read", { uri: lookupUri });
  }
  if (method === "tools/call") {
    const toolName = params && params.name ? params.name : null;
    const args = params && params.arguments ? params.arguments : null;
    if (!toolName) {
      return toolError("Missing tool name");
    }
    if (toolName === "proxy_list_servers") {
      return handleListServers(registry);
    }
    if (toolName === "proxy_list_projects" || toolName === "steroid_list_projects") {
      return handleAggregateProjects(registry, args || {});
    }
    if (toolName === "proxy_list_windows" || toolName === "steroid_list_windows") {
      return handleAggregateWindows(registry, args || {});
    }
    const resolved = registry.resolveServerForToolCall(toolName, args || {});
    if (resolved.error) {
      return toolError(resolved.error);
    }
    return registry.callTool(resolved.serverId, resolved.toolName, args || {});
  }
  const err = new Error(`Method not found: ${method}`);
  err.code = -32601;
  throw err;
}

function addCorsHeaders(res) {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
  res.setHeader(
    "Access-Control-Allow-Headers",
    `Content-Type, Accept, ${SESSION_HEADER}, ${SESSION_NOTICE_HEADER}`
  );
  res.setHeader("Access-Control-Expose-Headers", `${SESSION_HEADER}, ${SESSION_NOTICE_HEADER}`);
}

function createProxyServer(registry, traffic) {
  const sessions = new Map();

  return http.createServer(async (req, res) => {
    addCorsHeaders(res);
    if (req.method === "OPTIONS") {
      res.writeHead(204);
      res.end();
      return;
    }

    if (req.url === "/.well-known/mcp.json") {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        name: "intellij-mcp-steroid-proxy",
        version: registry.config.version || "0.1.0",
        endpoint: `http://${registry.config.server.host}:${registry.config.server.port}/mcp`
      }));
      return;
    }

    if (req.url !== "/mcp") {
      res.writeHead(404, { "Content-Type": "text/plain" });
      res.end("Not Found");
      return;
    }

    if (req.method === "GET") {
      const accept = req.headers.accept;
      if (!accept || acceptsJson(accept)) {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({
          name: "intellij-mcp-steroid-proxy",
          version: registry.config.version || "0.1.0",
          status: "available"
        }));
        return;
      }
      if (acceptsSse(accept)) {
        res.writeHead(405, { "Content-Type": "text/plain" });
        res.end("SSE notifications not supported");
        return;
      }
      res.writeHead(406, { "Content-Type": "text/plain" });
      res.end("Unsupported Accept header");
      return;
    }

    if (req.method === "DELETE") {
      const sessionId = req.headers[SESSION_HEADER.toLowerCase()];
      if (sessionId) {
        sessions.delete(sessionId);
        res.writeHead(204);
      } else {
        res.writeHead(400);
      }
      res.end();
      return;
    }

    if (req.method !== "POST") {
      res.writeHead(405, { "Content-Type": "text/plain" });
      res.end("Method Not Allowed");
      return;
    }

    const accept = req.headers.accept;
    if (accept && !acceptsJson(accept)) {
      res.writeHead(406, { "Content-Type": "text/plain" });
      res.end("Accept header must include application/json");
      return;
    }

    const contentType = req.headers["content-type"] || "";
    if (!contentType.includes("application/json")) {
      res.writeHead(415, { "Content-Type": "text/plain" });
      res.end("Content-Type must be application/json");
      return;
    }

    let body = "";
    req.on("data", (chunk) => {
      body += chunk;
    });
    req.on("end", async () => {
      let payload;
      try {
        payload = JSON.parse(body);
      } catch (err) {
        const errorPayload = jsonRpcError(null, -32700, `Parse error: ${err.message}`);
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify(errorPayload));
        return;
      }

      if (!payload) {
        const errorPayload = jsonRpcError(null, -32600, "Empty request body");
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify(errorPayload));
        return;
      }

      if (!Array.isArray(payload) && (typeof payload !== "object" || payload === null)) {
        const errorPayload = jsonRpcError(null, -32600, "Invalid request");
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify(errorPayload));
        return;
      }

      const sessionId = req.headers[SESSION_HEADER.toLowerCase()];
      let isNewSession = false;
      let sessionNotice = null;
      let session = sessionId ? sessions.get(sessionId) : null;
      if (!session) {
        const newId = crypto.randomUUID();
        session = { id: newId };
        sessions.set(newId, session);
        isNewSession = true;
        if (sessionId) {
          sessionNotice = UNKNOWN_SESSION_NOTICE;
        }
      }
      if (isNewSession) {
        res.setHeader(SESSION_HEADER, session.id);
      }
      if (sessionNotice) {
        res.setHeader(SESSION_NOTICE_HEADER, sessionNotice);
      }

      await traffic.log({
        ts: nowIso(),
        direction: "in",
        session: session.id,
        payload
      });

      const handleSingle = async (request) => {
        if (!request || typeof request !== "object") {
          return jsonRpcError(null, -32600, "Invalid request");
        }
        const id = request.id;
        const method = request.method;
        if (id === undefined || id === null) {
          return null;
        }
        if (!method) {
          return jsonRpcError(id, -32600, "Missing method");
        }
        try {
          const result = await handleRpc(method, request.params || {}, registry);
          return jsonRpcResult(id, result);
        } catch (err) {
          const code = typeof err.code === "number" ? err.code : -32603;
          return jsonRpcError(id, code, err.message || "Internal error");
        }
      };

      let responsePayload;
      if (Array.isArray(payload)) {
        const responses = [];
        for (const request of payload) {
          const response = await handleSingle(request);
          if (response) responses.push(response);
        }
        responsePayload = responses.length ? responses : null;
      } else {
        responsePayload = await handleSingle(payload);
      }

      if (!responsePayload) {
        res.writeHead(202);
        res.end();
        return;
      }

      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(responsePayload));
    });
  });
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) {
    process.stdout.write("Usage: mcp-steroid-proxy [--config path] [--host 127.0.0.1] [--port 33100] [--log-traffic]\n");
    return;
  }
  const config = await loadConfig(args);
  config.version = loadPackageVersion();
  const traffic = new TrafficLogger(config);
  const registry = new ServerRegistry(config, traffic);
  await registry.refreshDiscovery();

  const server = createProxyServer(registry, traffic);
  server.listen(config.server.port, config.server.host, () => {
    const address = server.address();
    const port = typeof address === "object" ? address.port : config.server.port;
    config.server.port = port;
    process.stdout.write(`MCP Steroid proxy listening on http://${config.server.host}:${port}/mcp\n`);
  });

  setInterval(() => {
    registry.refreshDiscovery().catch(() => {
      // Ignore refresh errors
    });
  }, config.scanIntervalMs);
}

if (require.main === module) {
  main().catch((err) => {
    process.stderr.write(`${err.stack || err.message}\n`);
    process.exit(1);
  });
}

module.exports = {
  DEFAULT_CONFIG,
  parseArgs,
  loadConfig,
  isPidAlive,
  parseMarkerContent,
  scanMarkers,
  buildAliasUri,
  parseAliasUri,
  parseNamespacedTool,
  mergeToolGroups,
  extractJsonFromToolResult,
  ServerRegistry
};

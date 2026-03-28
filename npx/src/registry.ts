const os = require("os");

import {
  scanMarkers,
  baseUrlFromMcpUrl,
  portFromUrl,
  buildServerId,
  nowIso,
  compareVersionStringsDescending,
  compareIsoTimesDescending,
  buildAliasUri,
  parseNamespacedTool,
  projectKey,
  normalizeServerMetadataPayload,
  mergeServerMetadata,
  extractJsonFromToolResult
} from "./utils";
import { proxyTools, toolError } from "./proxy-tools";
import { UpstreamClient } from "./upstream";

class ServerRegistry {
  config;
  traffic;
  servers;
  lastScanAt;
  refreshing;
  projectIndexByName;
  projectIndexByKey;
  projectMappings;
  windowIndex;
  executionIndex;
  resourceIndex;

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
      const match = item.text.match(/execution_id:\s*([\w-]+)/);
      if (match) {
        this.executionIndex.set(match[1], serverId);
      }
    }
  }
}

export { ServerRegistry };

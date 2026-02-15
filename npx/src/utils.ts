const path = require("path");
const os = require("os");
const fsp = require("fs/promises");
const crypto = require("crypto");
const { URL } = require("url");

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
  const properties: Record<string, any> = {};
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

export {
  mergeDeep,
  expandHome,
  isPidAlive,
  parseMarkerContent,
  isAllowedHost,
  scanMarkers,
  baseUrlFromMcpUrl,
  portFromUrl,
  buildServerId,
  nowIso,
  versionParts,
  compareVersionPartsDescending,
  compareVersionStringsDescending,
  extractBaseVersion,
  isVersionNewer,
  compareIsoTimesDescending,
  buildAliasUri,
  parseAliasUri,
  parseNamespacedTool,
  projectKey,
  normalizeServerMetadataPayload,
  metadataFromProductsPayload,
  metadataFromWindowsPayload,
  metadataFromProjectsPayload,
  metadataFromInitializeResult,
  mergeServerMetadata,
  mergeInputSchemas,
  mergeToolGroups,
  extractJsonFromToolResult
};

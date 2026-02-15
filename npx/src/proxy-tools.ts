import { AGGREGATE_TOOL_NAMES } from "./constants";
import {
  extractJsonFromToolResult,
  metadataFromProjectsPayload,
  metadataFromWindowsPayload,
  metadataFromProductsPayload,
  normalizeServerMetadataPayload
} from "./utils";

function proxyTools() {
  const emptySchema = { type: "object", properties: {}, required: [] };
  return [
    {
      name: AGGREGATE_TOOL_NAMES.projects,
      description: "Aggregate projects across all running IDE servers.",
      inputSchema: emptySchema
    },
    {
      name: AGGREGATE_TOOL_NAMES.windows,
      description: "Aggregate windows across all running IDE servers.",
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

      const result = await registry.callTool(serverId, "steroid_list_products", {});

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

      const result = await registry.callTool(serverId, "steroid_server_metadata", {});
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

export {
  proxyTools,
  toolResult,
  toolError,
  getTargetsByFreshness,
  handleAggregateProjects,
  handleAggregateWindows,
  handleAggregateProducts,
  handleAggregateServerMetadata,
  handleListServers
};

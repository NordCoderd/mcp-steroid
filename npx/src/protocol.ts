import { PROTOCOL_VERSION, JSONRPC_VERSION, AGGREGATE_TOOL_NAMES, BEACON_EVENTS } from "./constants";
import { mergeToolGroups, parseAliasUri } from "./utils";
import { toolError } from "./proxy-tools";
import {
  handleAggregateProjects,
  handleAggregateWindows
} from "./proxy-tools";

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
      const err: any = new Error("Missing uri");
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

    if (toolName === AGGREGATE_TOOL_NAMES.projects) {
      return captureToolCall(await handleAggregateProjects(registry, args), "aggregate");
    }
    if (toolName === AGGREGATE_TOOL_NAMES.windows) {
      return captureToolCall(await handleAggregateWindows(registry, args), "aggregate");
    }

    const resolved = registry.resolveServerForToolCall(toolName, args || {});
    if (resolved.error) {
      return captureToolCall(toolError(resolved.error), "resolve");
    }

    const progressToken = extractClientProgressToken(params) || createProgressToken();
    let lastProgress = 0;
    const emitProgress = async (progress, message, total = null) => {
      if (typeof notify !== "function") return;
      const safeProgress = Number.isFinite(progress) ? progress : lastProgress;
      if (Number.isFinite(safeProgress)) {
        lastProgress = safeProgress;
      }
      const payload: any = {
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

  const err: any = new Error(`Method not found: ${method}`);
  err.code = -32601;
  throw err;
}

export {
  jsonRpcResult,
  jsonRpcError,
  createServerInfo,
  extractClientProgressToken,
  createProgressToken,
  handleRpc
};

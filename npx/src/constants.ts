const PROTOCOL_VERSION = "2025-11-25";
const JSONRPC_VERSION = "2.0";
const SESSION_HEADER = "Mcp-Session-Id";

const AGGREGATE_TOOL_NAMES = {
  projects: "steroid_list_projects",
  windows: "steroid_list_windows"
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

export {
  PROTOCOL_VERSION,
  JSONRPC_VERSION,
  SESSION_HEADER,
  AGGREGATE_TOOL_NAMES,
  BEACON_EVENTS,
  DEFAULT_CONFIG
};

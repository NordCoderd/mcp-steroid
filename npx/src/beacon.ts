const fsp = require("fs/promises");
const path = require("path");
const crypto = require("crypto");

import { DEFAULT_CONFIG, BEACON_EVENTS } from "./constants";
import { expandHome } from "./utils";

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
  config;
  proxyVersion;
  distinctId;
  lastDiscoverySignature;
  heartbeatTimer;
  client;

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

export { buildBeaconConfig, NpxBeacon };

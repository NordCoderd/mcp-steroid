import { DEFAULT_CONFIG } from "./constants";
import { extractBaseVersion, isVersionNewer } from "./utils";

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

export {
  buildVersionEndpointUrl,
  buildUpdateUserAgent,
  buildUpdateCheckConfig,
  fetchRemoteVersionBase,
  pickRecommendedVersion,
  needsUpgradeByServerRule,
  buildUpgradeNotice
};

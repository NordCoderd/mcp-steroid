import { BEACON_EVENTS, DEFAULT_CONFIG } from "./constants";
import { readNextFramedMessage, encodeFramedMessage } from "./framing";
import { parseSseEventBlock } from "./sse";
import {
  isPidAlive, parseMarkerContent, scanMarkers,
  buildAliasUri, parseAliasUri, parseNamespacedTool,
  mergeToolGroups, extractJsonFromToolResult,
  extractBaseVersion, isVersionNewer,
  mergeServerMetadata,
  metadataFromInitializeResult, metadataFromProductsPayload,
  metadataFromWindowsPayload, metadataFromProjectsPayload
} from "./utils";
import { loadPackageVersion, parseArgs, parseJsonFlag, buildCliRequest, loadConfig } from "./config";
import { TrafficLogger } from "./traffic";
import { buildBeaconConfig, NpxBeacon } from "./beacon";
import { ServerRegistry } from "./registry";
import { buildUpdateCheckConfig, buildUpgradeNotice, pickRecommendedVersion, needsUpgradeByServerRule } from "./update-check";
import { handleRpc } from "./protocol";
import { createStdioServer, runCliMode } from "./stdio";

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

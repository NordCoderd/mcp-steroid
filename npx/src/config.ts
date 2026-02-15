const fs = require("fs");
const fsp = require("fs/promises");
const path = require("path");
const os = require("os");

import { DEFAULT_CONFIG } from "./constants";
import { mergeDeep, expandHome } from "./utils";

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

export {
  loadPackageVersion,
  parseArgs,
  parseJsonFlag,
  buildCliRequest,
  loadConfig
};

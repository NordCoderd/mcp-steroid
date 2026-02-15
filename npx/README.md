# MCP Steroid NPX Proxy

This folder contains the NPX proxy implemented in TypeScript.

The proxy is an MCP stdio server that:
- discovers all running MCP Steroid plugin instances via `~/.<pid>.mcp-steroid`
- aggregates MCP metadata/tools/resources across IDE instances
- routes tool calls to the freshest matching IDE/plugin instance
- maintains dynamic mapping `<project name + path> -> IDE instance + port`
- reports dedicated PostHog beacon events for runtime health and routing usage

## Runtime model

- Default mode: stdio MCP server (`stdin`/`stdout`)
- Optional mode: direct CLI invocation of the same MCP methods/tools
- Upstream transport: HTTP MCP to plugin `/mcp` endpoints discovered from marker files
- Control-plane bridge: plugin Ktor routes under `/npx/v1/*` for metadata/products/streaming
- No local HTTP server is exposed by NPX
- Update checks use the same remote `version-base` endpoint pattern as MCP Server and print upgrade notices to stderr

## TypeScript-only policy

- Source code is in `.ts` files only
- Tests are in `.ts` files only
- Generated `.js` exists only under build outputs (`dist/`, `dist-test/`)

## Quick start

```bash
npm --prefix npx install
npm --prefix npx run build
node npx/dist/index.js

# CLI mode examples
node npx/dist/index.js --cli
node npx/dist/index.js --cli --tool steroid_list_projects
node npx/dist/index.js --cli --tool steroid_execute_code --arguments-json '{"project_name":"MyProject","code":"println(\"hello\")"}'
node npx/dist/index.js --cli --cli-method resources/read --cli-params-json '{"uri":"mcp-steroid://skill/SKILL.md"}'
```

Or via package binary once published:

```bash
npx mcp-steroid-proxy
```

## Configuration

Default config path:

```
~/.mcp-steroid/proxy.json
```

Example:

```json
{
  "scanIntervalMs": 2000,
  "allowHosts": ["127.0.0.1", "localhost"],
  "cache": { "enabled": false, "dir": "~/.mcp-steroid/proxy", "ttlSeconds": 5 },
  "trafficLog": { "enabled": false, "redactFields": ["code"] },
  "upstreamTimeoutMs": 120000,
  "updates": {
    "enabled": true,
    "initialDelayMs": 30000,
    "intervalMs": 900000,
    "requestTimeoutMs": 10000
  },
  "beacon": {
    "enabled": true,
    "host": "https://us.i.posthog.com",
    "apiKey": "phc_IPtbjwwy9YIGg0YNHNxYBePijvTvHEcKAjohah6obYW",
    "timeoutMs": 3000,
    "heartbeatIntervalMs": 1800000,
    "distinctIdFile": "~/.mcp-steroid/proxy-beacon-id"
  }
}
```

CLI flags:

```
--config <path>         Custom config file
--scan-interval <ms>    Marker scan interval override
--log-traffic           Enable traffic logging
--cli                   Run single-shot CLI mode instead of stdio server
--cli-method <method>   MCP method for CLI mode (default: tools/list)
--cli-params-json <js>  JSON object for method params in CLI mode
--tool <name>           Shortcut for tools/call in CLI mode
--arguments-json <js>   JSON object for tools/call arguments in CLI mode
--uri <resourceUri>     Shortcut for resources/read in CLI mode
-h, --help              Print help
```

## Compatibility

- Older NPX + newer plugin: must continue working using MCP baseline behavior.
- Newer NPX + older plugin: NPX must degrade gracefully to MCP-only discovery/routing.
- Bridge-specific metadata must be additive and feature-detected; no hard requirement for legacy plugin versions.
- IDE metadata is sourced from running IDE/plugin instances; NPX does not invent IDE/product metadata locally.

## Bridge Contract (Plugin Ktor API)

NPX uses dedicated plugin HTTP routes for NPX-specific control-plane needs:

- `GET /npx/v1/server-metadata`
- `GET /npx/v1/products`
- `POST /npx/v1/tools/call/stream`
- `GET /npx/v1/projects`
- `GET /npx/v1/windows`
- `GET /npx/v1/resources`
- `GET /npx/v1/resources/read?uri=...`
- `GET /npx/v1/summary`

NPX still uses MCP `/mcp` for MCP data-plane operations (`tools/list`, `tools/call`, `resources/list`, `resources/read`) and falls back to MCP-only behavior if bridge routes are unavailable.

## Routing

- `steroid_list_projects` and `steroid_list_windows` fan out across all online servers and merge results.
- `steroid_list_products` and `steroid_server_metadata` use bridge endpoints first, then MCP fallback.
- NPX keeps a live `<project name + path> -> serverId` index and rebuilds it on discovery refresh.
- Project-scoped tool calls route to the freshest matching server (plugin version + IDE version/build freshness ordering).
- `server_id` can override routing; `intellij`/`default_api` aliases resolve when only one server is online.

## Progress and Timeout Safety

- During delegated `tools/call`, NPX emits `notifications/progress`.
- Sequence is start (`progress=0`), forwarded upstream `progress` events, forwarded upstream `heartbeat` events, completion (`progress=1`).
- This keeps MCP clients informed and reduces timeout risk for long-running tool calls.

## Proxy Tools

NPX adds proxy-native tools in `tools/list`:

- `proxy_list_servers`
- `proxy_list_projects`
- `proxy_list_windows`
- `proxy_list_products`
- `proxy_list_server_metadata`

Aggregate aliases are also exposed:

- `steroid_list_projects`
- `steroid_list_windows`
- `steroid_list_products`
- `steroid_server_metadata`

## Upgrade checks

- NPX periodically fetches `https://mcp-steroid.jonnyzzz.com/version.json?intellij-version=<build>` (when IDE build metadata is available).
- If current NPX version does not match remote `version-base` semantics, NPX prints an upgrade recommendation to stderr once per process.
- Checks continue in background even after the first notice, matching MCP Server behavior.

## Beacon events

NPX sends dedicated PostHog events (best-effort, non-blocking):

- `npx_started`
- `npx_heartbeat`
- `npx_discovery_changed`
- `npx_tool_call`
- `npx_upgrade_recommended`

## Architecture

- Data plane: MCP protocol over stdio (client-facing) and MCP HTTP `/mcp` (upstream).
- Control plane: plugin Ktor bridge API under `/npx/v1/*`.
- NPX never exposes its own HTTP interface to MCP clients.

## Spec

See `npx/specs.md` for detailed protocol and design notes.

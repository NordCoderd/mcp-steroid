# MCP Steroid NPX Proxy

This folder contains the NPX proxy implemented in TypeScript.

The proxy is an MCP stdio server that:
- discovers all running MCP Steroid plugin instances via `~/.<pid>.mcp-steroid`
- aggregates MCP metadata/tools/resources across IDE instances
- routes tool calls to the freshest matching IDE/plugin instance

## Runtime model

- Default mode: stdio MCP server (`stdin`/`stdout`)
- Optional mode: direct CLI invocation of the same MCP methods/tools
- Upstream transport: HTTP MCP to plugin `/mcp` endpoints discovered from marker files
- No local HTTP server is exposed by NPX

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
  "upstreamTimeoutMs": 5000
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

## Architecture direction

Current routing works through MCP API (`tools/list`, `tools/call`, `resources/list`, `resources/read`).

For NPX-internal control-plane concerns (fresh metadata snapshots, project/path mapping stability, timeout-safe progress heartbeats), the intended direction is a dedicated plugin bridge API served by Ktor, while keeping external MCP client behavior unchanged.

## Spec

See `npx/specs.md` for detailed protocol and design notes.

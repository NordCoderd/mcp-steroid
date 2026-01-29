# MCP Steroid NPX Proxy

This folder contains a Node.js proxy that aggregates multiple running IntelliJ MCP Steroid servers into a single MCP endpoint.

## Quick start

```bash
# From this repo
node npx/src/index.js

# Or via npm bin once published
npx mcp-steroid-proxy
```

The proxy scans `~/.<pid>.mcp-steroid` marker files and exposes a local MCP endpoint (default: `http://127.0.0.1:33100/mcp`).

## Config

Default config location:

```
~/.mcp-steroid/proxy.json
```

Example:

```json
{
  "scanIntervalMs": 2000,
  "allowHosts": ["127.0.0.1", "localhost"],
  "cache": { "enabled": false, "dir": "~/.mcp-steroid/proxy", "ttlSeconds": 5 },
  "trafficLog": { "enabled": false, "redactFields": ["code"] }
}
```

CLI flags:

```
--config <path>   Custom config file
--host <host>     Bind host (default 127.0.0.1)
--port <port>     Bind port (default 33100)
--log-traffic     Enable traffic logging
```

## Spec

See `npx/specs.md` for the validated proxy specification.

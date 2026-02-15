import { PROTOCOL_VERSION, JSONRPC_VERSION, SESSION_HEADER } from "./constants";
import { nowIso, metadataFromInitializeResult, mergeServerMetadata } from "./utils";
import { parseSseEventBlock } from "./sse";

class UpstreamClient {
  server;
  config;
  traffic;
  sessionId;
  initialized;
  requestId;

  constructor(server, config, traffic) {
    this.server = server;
    this.config = config;
    this.traffic = traffic;
    this.sessionId = null;
    this.initialized = false;
    this.requestId = 0;
  }

  async ensureInitialized() {
    if (this.initialized) return;

    const params = {
      protocolVersion: PROTOCOL_VERSION,
      capabilities: {},
      clientInfo: {
        name: "mcp-steroid-proxy",
        version: this.config.version || "0.1.0"
      }
    };

    const initResult = await this.sendRequest("initialize", params, true);
    const metadataPatch = metadataFromInitializeResult(initResult);
    this.server.metadata = mergeServerMetadata(this.server.metadata, metadataPatch);

    await this.sendNotification("notifications/initialized", {});
    this.initialized = true;
  }

  async sendNotification(method, params) {
    const payload = { jsonrpc: JSONRPC_VERSION, method, params };
    await this.sendPayload(payload, false);
  }

  async sendRequest(method, params, skipInit = false) {
    if (!skipInit) {
      await this.ensureInitialized();
    }

    this.requestId += 1;
    const payload = {
      jsonrpc: JSONRPC_VERSION,
      id: String(this.requestId),
      method,
      params
    };

    const response = await this.sendPayload(payload, true);
    if (response.error) {
      throw new Error(response.error.message || "Upstream error");
    }
    return response.result;
  }

  async callTool(toolName, args, onEvent = null) {
    await this.ensureInitialized();
    try {
      return await this.callToolViaBridgeStream(toolName, args || {}, onEvent);
    } catch (err: any) {
      // Fallback for older plugin versions without bridge streaming support.
      if (!err || err.retryable !== true) {
        throw err;
      }
    }

    return this.sendRequest("tools/call", {
      name: toolName,
      arguments: args || {}
    });
  }

  async callToolViaBridgeStream(toolName, args, onEvent = null) {
    if (!this.server.bridgeBaseUrl) {
      const err: any = new Error("Bridge base URL is missing");
      err.retryable = true;
      throw err;
    }

    const url = `${this.server.bridgeBaseUrl}/tools/call/stream`;
    const payload = {
      name: toolName,
      arguments: args || {}
    };

    await this.traffic.log({
      ts: nowIso(),
      direction: "upstream-out",
      serverId: this.server.serverId,
      method: "bridge/tools/call/stream",
      payload
    });

    const controller = new AbortController();
    let timeout = null;
    const resetTimeout = () => {
      if (timeout) clearTimeout(timeout);
      timeout = setTimeout(() => controller.abort(), this.config.upstreamTimeoutMs);
    };

    resetTimeout();
    let response;
    try {
      response = await fetch(url, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Accept: "text/event-stream"
        },
        body: JSON.stringify(payload),
        signal: controller.signal
      });
    } finally {
      if (timeout) clearTimeout(timeout);
    }

    if (!response.ok) {
      let details = "";
      try {
        details = (await response.text()).slice(0, 400);
      } catch (_) {
        // Ignore body parse failures
      }
      const err: any = new Error(
        `Bridge HTTP ${response.status} ${response.statusText}${details ? `: ${details}` : ""}`
      );
      err.retryable = response.status === 404 || response.status === 405 || response.status === 501;
      throw err;
    }

    const reader = response.body && response.body.getReader ? response.body.getReader() : null;
    if (!reader) {
      const err: any = new Error("Bridge stream response has no body");
      err.retryable = true;
      throw err;
    }

    const decoder = new TextDecoder("utf8");
    let buffer = "";
    let sawEvent = false;
    let resultPayload = null;

    const handleBlock = async (block) => {
      const parsed = parseSseEventBlock(block);
      if (!parsed) return;

      sawEvent = true;
      await this.traffic.log({
        ts: nowIso(),
        direction: "upstream-in",
        serverId: this.server.serverId,
        method: "bridge/tools/call/stream",
        payload: parsed.data
      });

      if (typeof onEvent === "function" && parsed.data && typeof parsed.data === "object") {
        await onEvent(parsed.data);
      }

      if (parsed.type === "result" && parsed.data && typeof parsed.data === "object") {
        resultPayload = parsed.data.result || null;
      } else if (parsed.type === "error") {
        const message = parsed.data && typeof parsed.data.message === "string"
          ? parsed.data.message
          : "Bridge stream tool call failed";
        const err: any = new Error(message);
        err.retryable = false;
        throw err;
      }
    };

    try {
      while (true) {
        resetTimeout();
        const chunk = await reader.read();
        if (timeout) clearTimeout(timeout);
        if (chunk.done) break;
        buffer += decoder.decode(chunk.value, { stream: true });

        while (true) {
          const lfLf = buffer.indexOf("\n\n");
          const crlfCrlf = buffer.indexOf("\r\n\r\n");
          let sepIndex = -1;
          let sepLen = 0;

          if (lfLf >= 0 && (crlfCrlf < 0 || lfLf < crlfCrlf)) {
            sepIndex = lfLf;
            sepLen = 2;
          } else if (crlfCrlf >= 0) {
            sepIndex = crlfCrlf;
            sepLen = 4;
          }

          if (sepIndex < 0) break;
          const block = buffer.slice(0, sepIndex);
          buffer = buffer.slice(sepIndex + sepLen);
          await handleBlock(block);
        }
      }

      if (buffer.trim()) {
        await handleBlock(buffer);
      }
    } catch (err: any) {
      if (err && err.name === "AbortError") {
        const timeoutErr: any = new Error(
          `Bridge stream timeout after ${this.config.upstreamTimeoutMs}ms for ${toolName}`
        );
        timeoutErr.retryable = !sawEvent;
        throw timeoutErr;
      }
      if (err && typeof err === "object" && "retryable" in err) {
        throw err;
      }
      const streamErr: any = new Error(err && err.message ? err.message : String(err));
      streamErr.retryable = !sawEvent;
      throw streamErr;
    } finally {
      try {
        await reader.cancel();
      } catch (_) {
        // ignore
      }
    }

    if (resultPayload == null) {
      const err: any = new Error("Bridge stream completed without a result payload");
      err.retryable = !sawEvent;
      throw err;
    }
    return resultPayload;
  }

  async sendPayload(payload, expectResponse) {
    const headers = {
      "Content-Type": "application/json",
      Accept: "application/json"
    };
    if (this.sessionId) {
      headers[SESSION_HEADER] = this.sessionId;
    }

    await this.traffic.log({
      ts: nowIso(),
      direction: "upstream-out",
      serverId: this.server.serverId,
      method: payload.method,
      payload
    });

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.config.upstreamTimeoutMs);
    let response;
    try {
      response = await fetch(this.server.url, {
        method: "POST",
        headers,
        body: JSON.stringify(payload),
        signal: controller.signal
      });
    } finally {
      clearTimeout(timeout);
    }

    const newSession = response.headers.get(SESSION_HEADER.toLowerCase()) || response.headers.get(SESSION_HEADER);
    if (newSession) {
      this.sessionId = newSession;
    }

    if (!response.ok) {
      let details = "";
      try {
        details = (await response.text()).slice(0, 400);
      } catch (_) {
        // Ignore response body parse failures
      }
      const suffix = details ? `: ${details}` : "";
      throw new Error(`Upstream HTTP ${response.status} ${response.statusText}${suffix}`);
    }

    if (!expectResponse) {
      return {};
    }

    const text = await response.text();
    let json;
    try {
      json = JSON.parse(text);
    } catch (err: any) {
      throw new Error(`Invalid JSON from upstream: ${err.message}`);
    }

    await this.traffic.log({
      ts: nowIso(),
      direction: "upstream-in",
      serverId: this.server.serverId,
      method: payload.method,
      payload: json
    });

    return json;
  }
}

export { UpstreamClient };

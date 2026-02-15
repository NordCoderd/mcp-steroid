import { JSONRPC_VERSION } from "./constants";
import { nowIso } from "./utils";
import { readNextFramedMessage, encodeFramedMessage, encodeNdjsonMessage } from "./framing";
import { jsonRpcResult, jsonRpcError, handleRpc } from "./protocol";
import { buildCliRequest } from "./config";

function createStdioServer(registry, traffic, beacon = null) {
  let inputBuffer = Buffer.alloc(0);
  let chain = Promise.resolve();
  let outputMode = null;

  const writeResponse = async (payload) => {
    await traffic.log({
      ts: nowIso(),
      direction: "proxy-out",
      payload
    });
    const message = outputMode === "ndjson"
      ? encodeNdjsonMessage(payload)
      : encodeFramedMessage(payload);
    process.stdout.write(message);
  };

  const handleSingle = async (request) => {
    if (!request || typeof request !== "object") {
      return jsonRpcError(null, -32600, "Invalid request");
    }

    const id = request.id;
    const method = request.method;
    if (id === undefined || id === null) {
      // Notification
      return null;
    }

    if (!method) {
      return jsonRpcError(id, -32600, "Missing method");
    }

    try {
      const result = await handleRpc(
        method,
        request.params || {},
        registry,
        beacon,
        async (notifyMethod, notifyParams) => {
          await writeResponse({
            jsonrpc: JSONRPC_VERSION,
            method: notifyMethod,
            params: notifyParams
          });
        }
      );
      return jsonRpcResult(id, result);
    } catch (err) {
      const code = typeof err.code === "number" ? err.code : -32603;
      return jsonRpcError(id, code, err.message || "Internal error");
    }
  };

  const handlePayload = async (payload) => {
    if (!payload) {
      await writeResponse(jsonRpcError(null, -32600, "Empty request body"));
      return;
    }

    await traffic.log({
      ts: nowIso(),
      direction: "proxy-in",
      payload
    });

    if (Array.isArray(payload)) {
      const responses = [];
      for (const request of payload) {
        const response = await handleSingle(request);
        if (response) responses.push(response);
      }
      if (responses.length > 0) {
        await writeResponse(responses);
      }
      return;
    }

    if (typeof payload !== "object") {
      await writeResponse(jsonRpcError(null, -32600, "Invalid request"));
      return;
    }

    const response = await handleSingle(payload);
    if (response) {
      await writeResponse(response);
    }
  };

  const onChunk = (chunk) => {
    inputBuffer = Buffer.concat([inputBuffer, chunk]);

    while (true) {
      const frame = readNextFramedMessage(inputBuffer);
      if (!frame) break;
      inputBuffer = inputBuffer.slice(frame.consumed);
      if (!outputMode && frame.mode) {
        outputMode = frame.mode;
      }

      if (!frame.payloadText) continue;

      chain = chain
        .then(async () => {
          let parsed;
          try {
            parsed = JSON.parse(frame.payloadText);
          } catch (err) {
            await writeResponse(jsonRpcError(null, -32700, `Parse error: ${err.message}`));
            return;
          }
          await handlePayload(parsed);
        })
        .catch(async (err) => {
          await writeResponse(jsonRpcError(null, -32603, err.message || "Internal error"));
        });
    }
  };

  process.stdin.on("data", onChunk);
  process.stdin.on("end", () => {
    if (!beacon) {
      process.exit(0);
      return;
    }
    beacon.shutdown().finally(() => {
      process.exit(0);
    });
  });
  process.stdin.resume();
}

async function runCliMode(args, registry, traffic, beacon = null) {
  const request = buildCliRequest(args);
  await traffic.log({
    ts: nowIso(),
    direction: "cli-in",
    payload: request
  });

  const result = await handleRpc(request.method, request.params, registry, beacon);
  await traffic.log({
    ts: nowIso(),
    direction: "cli-out",
    payload: result
  });

  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
}

export { createStdioServer, runCliMode };

function decodeContentLength(headersText) {
  const lines = headersText.split(/\r?\n/);
  for (const line of lines) {
    const idx = line.indexOf(":");
    if (idx <= 0) continue;
    const key = line.slice(0, idx).trim().toLowerCase();
    if (key !== "content-length") continue;
    const value = Number(line.slice(idx + 1).trim());
    if (Number.isFinite(value) && value >= 0) {
      return value;
    }
  }
  return null;
}

function startsLikeJsonPayload(buffer) {
  const prefix = buffer.toString("utf8", 0, Math.min(buffer.length, 64)).trimStart();
  return prefix.startsWith("{") || prefix.startsWith("[");
}

function readNextFramedMessage(buffer) {
  const headerSep = Buffer.from("\r\n\r\n");
  const altHeaderSep = Buffer.from("\n\n");
  let headerEnd = buffer.indexOf(headerSep);
  let delimiterLength = 4;

  if (headerEnd < 0) {
    headerEnd = buffer.indexOf(altHeaderSep);
    delimiterLength = 2;
  }

  if (headerEnd >= 0) {
    const headersText = buffer.slice(0, headerEnd).toString("utf8");
    const bodyLength = decodeContentLength(headersText);
    if (bodyLength != null) {
      const total = headerEnd + delimiterLength + bodyLength;
      if (buffer.length < total) return null;
      const payloadText = buffer.slice(headerEnd + delimiterLength, total).toString("utf8");
      return {
        consumed: total,
        payloadText,
        mode: "framed"
      };
    }
  }

  // Newline-delimited JSON fallback is supported only when input starts as JSON.
  // This prevents mis-parsing partial Content-Length headers as JSON payload.
  if (!startsLikeJsonPayload(buffer)) {
    return null;
  }

  const newline = buffer.indexOf(0x0a);
  if (newline < 0) return null;
  const payloadText = buffer.slice(0, newline).toString("utf8").trim();
  return {
    consumed: newline + 1,
    payloadText,
    mode: "ndjson"
  };
}

function encodeFramedMessage(payload) {
  const text = JSON.stringify(payload);
  const bytes = Buffer.byteLength(text, "utf8");
  return `Content-Length: ${bytes}\r\n\r\n${text}`;
}

function encodeNdjsonMessage(payload) {
  return `${JSON.stringify(payload)}\n`;
}

export {
  decodeContentLength,
  startsLikeJsonPayload,
  readNextFramedMessage,
  encodeFramedMessage,
  encodeNdjsonMessage
};

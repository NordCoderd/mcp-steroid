function parseSseEventBlock(block) {
  if (typeof block !== "string") return null;
  const lines = block.split(/\r?\n/);
  let type = null;
  const dataLines = [];

  for (const rawLine of lines) {
    const line = rawLine.trimEnd();
    if (!line) continue;
    if (line.startsWith("event:")) {
      type = line.slice("event:".length).trim();
      continue;
    }
    if (line.startsWith("data:")) {
      dataLines.push(line.slice("data:".length).trimStart());
    }
  }

  if (dataLines.length === 0) return null;
  const dataText = dataLines.join("\n");
  let data = null;
  try {
    data = JSON.parse(dataText);
  } catch (_) {
    data = { message: dataText };
  }

  if (!type && data && typeof data.type === "string") {
    type = data.type;
  }

  return {
    type: type || "message",
    data
  };
}

export { parseSseEventBlock };

const fsp = require("fs/promises");
const path = require("path");

class TrafficLogger {
  enabled;
  redactFields;
  dir;

  constructor(config) {
    this.enabled = config.trafficLog.enabled;
    this.redactFields = new Set(config.trafficLog.redactFields || []);
    this.dir = config.cache.dir;
  }

  redact(payload) {
    if (!payload || typeof payload !== "object") return payload;
    const clone = Array.isArray(payload) ? payload.map((item) => this.redact(item)) : { ...payload };

    for (const key of Object.keys(clone)) {
      if (this.redactFields.has(key)) {
        clone[key] = "[redacted]";
      } else if (clone[key] && typeof clone[key] === "object") {
        clone[key] = this.redact(clone[key]);
      }
    }
    return clone;
  }

  async log(record) {
    if (!this.enabled) return;
    try {
      const logDir = path.join(this.dir, "logs");
      await fsp.mkdir(logDir, { recursive: true });
      const date = new Date().toISOString().slice(0, 10);
      const file = path.join(logDir, `${date}.jsonl`);
      const payload = {
        ...record,
        payload: this.redact(record.payload)
      };
      await fsp.appendFile(file, `${JSON.stringify(payload)}\n`, "utf8");
    } catch (_) {
      // Ignore logging failures
    }
  }
}

export { TrafficLogger };

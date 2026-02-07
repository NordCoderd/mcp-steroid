/**
 * Lightweight HTTP server that streams a growing MP4 file and serves
 * an HTML dashboard page for live test monitoring.
 *
 * Usage: node video-server.js <path-to-mp4> <port> <run-id>
 *
 * Routes:
 *   GET /          — HTML dashboard with embedded video, run ID, and status
 *   GET /video.mp4 — raw video stream (chunked transfer encoding)
 *   GET /status    — JSON { running: true/false } for the status indicator
 */
const http = require('http');
const fs = require('fs');

const VIDEO_FILE = process.argv[2];
const PORT = parseInt(process.argv[3], 10) || 8765;
const RUN_ID = process.argv[4] || 'unknown';

if (!VIDEO_FILE) {
  console.error('Usage: node video-server.js <path-to-mp4> <port> <run-id>');
  process.exit(1);
}

function isVideoReady() {
  try {
    return fs.existsSync(VIDEO_FILE) && fs.statSync(VIDEO_FILE).size >= 1024;
  } catch (_) {
    return false;
  }
}

function serveHtml(req, res) {
  const html = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>MCP Steroid — ${RUN_ID}</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;700&display=swap" rel="stylesheet">
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  html, body {
    font-family: 'JetBrains Mono', monospace;
    background: #1e1e2e;
    color: #cdd6f4;
    width: 100vw;
    height: 100vh;
    overflow: hidden;
  }
  .header {
    position: fixed;
    top: 0;
    left: 0;
    right: 0;
    z-index: 10;
    display: flex;
    align-items: center;
    gap: 16px;
    padding: 8px 16px;
    background: rgba(30, 30, 46, 0.85);
    backdrop-filter: blur(8px);
  }
  .title {
    font-size: 16px;
    font-weight: 700;
    color: #cba6f7;
  }
  .run-id {
    font-size: 12px;
    color: #6c7086;
    background: #313244;
    padding: 3px 8px;
    border-radius: 6px;
  }
  .status {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-left: auto;
    font-size: 12px;
  }
  .dot {
    width: 10px;
    height: 10px;
    border-radius: 50%;
    background: #6c7086;
    transition: background 0.3s;
  }
  .dot.running {
    background: #a6e3a1;
    box-shadow: 0 0 8px #a6e3a1;
    animation: pulse 2s ease-in-out infinite;
  }
  .dot.stopped {
    background: #f38ba8;
  }
  @keyframes pulse {
    0%, 100% { opacity: 1; }
    50% { opacity: 0.5; }
  }
  .status-text { color: #a6adc8; }
  video {
    position: absolute;
    top: 0;
    left: 0;
    width: 100vw;
    height: 100vh;
    object-fit: contain;
    background: #000;
  }
  .waiting {
    position: absolute;
    top: 0;
    left: 0;
    width: 100vw;
    height: 100vh;
    display: flex;
    align-items: center;
    justify-content: center;
    color: #6c7086;
    font-size: 14px;
    background: #1e1e2e;
  }
</style>
</head>
<body>
  <div class="header">
    <span class="title">MCP Steroid</span>
    <span class="run-id">${RUN_ID}</span>
    <div class="status">
      <div class="dot" id="statusDot"></div>
      <span class="status-text" id="statusText">checking...</span>
    </div>
  </div>
  <div class="waiting" id="waiting">Waiting for video stream...</div>
  <video id="video" autoplay muted style="display:none"></video>
<script>
  const dot = document.getElementById('statusDot');
  const statusText = document.getElementById('statusText');
  const video = document.getElementById('video');
  const waiting = document.getElementById('waiting');

  let videoStarted = false;

  async function startVideoStream() {
    if (videoStarted) return;
    videoStarted = true;

    video.style.display = 'block';
    waiting.style.display = 'none';

    // Use fetch + ReadableStream to pipe growing MP4 into MediaSource
    if (window.MediaSource && MediaSource.isTypeSupported('video/mp4; codecs="avc1.42E01E"')) {
      const ms = new MediaSource();
      video.src = URL.createObjectURL(ms);

      ms.addEventListener('sourceopen', async () => {
        const sb = ms.addSourceBuffer('video/mp4; codecs="avc1.42E01E"');
        const response = await fetch('/video.mp4');
        const reader = response.body.getReader();

        async function pump() {
          const { done, value } = await reader.read();
          if (done) {
            if (ms.readyState === 'open') ms.endOfStream();
            return;
          }
          // Wait for the buffer to finish updating before appending more
          if (sb.updating) {
            await new Promise(r => sb.addEventListener('updateend', r, { once: true }));
          }
          sb.appendBuffer(value);
          await new Promise(r => sb.addEventListener('updateend', r, { once: true }));
          pump();
        }
        pump();
      });
    } else {
      // Fallback: direct src (won't stream progressively but may work for completed files)
      video.src = '/video.mp4';
      video.load();
    }

    video.play().catch(() => {});
  }

  async function checkStatus() {
    try {
      const r = await fetch('/status');
      const data = await r.json();
      if (data.running) {
        dot.className = 'dot running';
        statusText.textContent = 'test running';
      } else {
        dot.className = 'dot stopped';
        statusText.textContent = 'test stopped';
      }
      if (data.videoReady && !videoStarted) {
        startVideoStream();
      }
    } catch (_) {
      dot.className = 'dot stopped';
      statusText.textContent = 'server unreachable';
    }
  }

  checkStatus();
  setInterval(checkStatus, 2000);
</script>
</body>
</html>`;

  res.writeHead(200, {
    'Content-Type': 'text/html; charset=utf-8',
    'Cache-Control': 'no-cache',
  });
  res.end(html);
}

function serveStatus(req, res) {
  res.writeHead(200, {
    'Content-Type': 'application/json',
    'Cache-Control': 'no-cache',
  });
  res.end(JSON.stringify({
    running: true,
    videoReady: isVideoReady(),
    runId: RUN_ID,
  }));
}

function serveVideo(req, res) {
  if (!isVideoReady()) {
    res.writeHead(503, {'Content-Type': 'text/plain'});
    res.end('Video file not ready');
    return;
  }

  // Stream with chunked transfer encoding (no Content-Length) so the
  // browser keeps receiving new data as ffmpeg writes it.
  res.writeHead(200, {
    'Content-Type': 'video/mp4',
    'Cache-Control': 'no-cache',
  });

  let position = 0;

  function sendChunk() {
    if (res.destroyed) return;

    let currentSize;
    try {
      currentSize = fs.statSync(VIDEO_FILE).size;
    } catch (_) {
      res.end();
      return;
    }

    if (currentSize > position) {
      const stream = fs.createReadStream(VIDEO_FILE, {start: position, end: currentSize - 1});
      stream.on('data', (chunk) => {
        if (!res.destroyed) res.write(chunk);
      });
      stream.on('end', () => {
        position = currentSize;
        setTimeout(sendChunk, 500);
      });
      stream.on('error', () => {
        res.end();
      });
    } else {
      setTimeout(sendChunk, 500);
    }
  }

  sendChunk();

  res.on('close', () => {
    console.log('[video-server] Client disconnected from video stream');
  });
}

const server = http.createServer((req, res) => {
  console.log(`[video-server] ${req.method} ${req.url}`);

  if (req.url === '/' || req.url === '/index.html') {
    serveHtml(req, res);
  } else if (req.url === '/video.mp4') {
    serveVideo(req, res);
  } else if (req.url === '/status') {
    serveStatus(req, res);
  } else {
    res.writeHead(404, {'Content-Type': 'text/plain'});
    res.end('Not found');
  }
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`[video-server] Dashboard at http://0.0.0.0:${PORT}/`);
  console.log(`[video-server] Video stream at http://0.0.0.0:${PORT}/video.mp4`);
  console.log(`[video-server] Run ID: ${RUN_ID}`);
});

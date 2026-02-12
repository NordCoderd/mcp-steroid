/**
 * Lightweight HTTP server that streams a growing MP4 file and serves
 * an HTML dashboard page for live test monitoring.
 *
 * Usage: node video-server.js <path-to-mp4> <port> <run-id> [background-image]
 *
 * Routes:
 *   GET /              — HTML dashboard with embedded video, run ID, and status
 *   GET /video.mp4     — raw video stream (chunked transfer encoding)
 *   GET /status        — JSON { running: true/false, videoReady: boolean, videoBytes: number }
 *   GET /background.jpg — branded dashboard background image
 */
const http = require('http');
const fs = require('fs');

const VIDEO_FILE = process.argv[2];
const PORT = parseInt(process.argv[3], 10) || 8765;
const RUN_ID = process.argv[4] || 'unknown';
const BACKGROUND_FILE = process.argv[5] || '/usr/share/images/mcp-steroid-wallpaper.jpg';

if (!VIDEO_FILE) {
  console.error('Usage: node video-server.js <path-to-mp4> <port> <run-id> [background-image]');
  process.exit(1);
}

function getVideoStat() {
  try {
    return fs.statSync(VIDEO_FILE);
  } catch (_) {
    return null;
  }
}

function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

function serveHtml(_req, res) {
  const safeRunId = escapeHtml(RUN_ID);
  const html = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>MCP Steroid - ${safeRunId}</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;700;800&display=swap" rel="stylesheet">
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  html, body {
    font-family: 'JetBrains Mono', 'DejaVu Sans Mono', monospace;
    width: 100vw;
    height: 100vh;
    overflow: hidden;
    background: #06090f;
    color: #d3deee;
  }
  .background {
    position: fixed;
    inset: 0;
    background-image:
      radial-gradient(1200px 720px at 15% 85%, rgba(49, 99, 255, 0.22), transparent 55%),
      radial-gradient(1000px 640px at 85% 15%, rgba(238, 80, 121, 0.19), transparent 58%),
      url('/background.jpg');
    background-size: cover;
    background-position: center;
    filter: saturate(1.18) brightness(0.62);
    transform: scale(1.02);
    z-index: 0;
  }
  .background::after {
    content: '';
    position: absolute;
    inset: 0;
    background: linear-gradient(180deg, rgba(4, 7, 12, 0.62), rgba(4, 7, 12, 0.78));
  }
  .header {
    position: fixed;
    top: 0;
    left: 0;
    right: 0;
    z-index: 6;
    display: flex;
    align-items: center;
    gap: 14px;
    padding: 10px 16px;
    background: rgba(6, 10, 17, 0.68);
    backdrop-filter: blur(10px);
    border-bottom: 1px solid rgba(129, 160, 217, 0.24);
  }
  .title {
    font-size: 15px;
    font-weight: 800;
    color: #cde0ff;
  }
  .run-id {
    font-size: 12px;
    color: #95a9cb;
    background: rgba(14, 22, 34, 0.72);
    border: 1px solid rgba(129, 160, 217, 0.24);
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
    background: #6e7f9c;
    transition: background 0.2s;
  }
  .dot.running {
    background: #7de0a3;
    box-shadow: 0 0 10px rgba(125, 224, 163, 0.7);
    animation: pulse 1.8s ease-in-out infinite;
  }
  .dot.stopped {
    background: #e86f7f;
    box-shadow: 0 0 10px rgba(232, 111, 127, 0.55);
  }
  @keyframes pulse {
    0%, 100% { opacity: 1; }
    50% { opacity: 0.45; }
  }
  .status-text {
    color: #aec0df;
  }
  .waiting {
    position: fixed;
    inset: 0;
    z-index: 4;
    display: flex;
    align-items: center;
    justify-content: center;
    transition: opacity 0.32s ease;
    pointer-events: none;
  }
  .waiting.hidden {
    opacity: 0;
    visibility: hidden;
  }
  .waiting-card {
    pointer-events: auto;
    min-width: min(92vw, 680px);
    max-width: 92vw;
    background: rgba(5, 9, 15, 0.68);
    border: 1px solid rgba(129, 160, 217, 0.34);
    border-radius: 16px;
    padding: 18px 22px;
    box-shadow: 0 22px 90px rgba(0, 0, 0, 0.45);
  }
  .wait-main {
    font-size: clamp(16px, 2.4vw, 28px);
    color: #d6e4fe;
    margin-bottom: 8px;
  }
  .wait-sub {
    font-size: clamp(11px, 1.3vw, 13px);
    color: #9db2d6;
  }
  video {
    position: fixed;
    inset: 0;
    width: 100vw;
    height: 100vh;
    object-fit: contain;
    z-index: 2;
    opacity: 0;
    transition: opacity 0.22s ease;
    background: transparent;
  }
  video.visible {
    opacity: 1;
  }
  .controls {
    position: fixed;
    right: 16px;
    bottom: 14px;
    z-index: 7;
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 8px 10px;
    border-radius: 10px;
    background: rgba(6, 10, 17, 0.72);
    border: 1px solid rgba(129, 160, 217, 0.28);
    backdrop-filter: blur(8px);
    color: #d3deee;
    font-size: 12px;
  }
  .controls button,
  .controls select {
    font-family: inherit;
    font-size: 12px;
    background: #0f1727;
    color: #d3deee;
    border: 1px solid #334a74;
    border-radius: 6px;
    padding: 5px 8px;
  }
  .controls button:disabled,
  .controls select:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
</style>
</head>
<body>
  <div class="background" aria-hidden="true"></div>

  <div class="header">
    <span class="title">MCP Steroid</span>
    <span class="run-id">${safeRunId}</span>
    <div class="status">
      <div class="dot" id="statusDot"></div>
      <span class="status-text" id="statusText">checking...</span>
    </div>
  </div>

  <div class="waiting" id="waiting">
    <div class="waiting-card">
      <div class="wait-main">Waiting for video to start...</div>
      <div class="wait-sub" id="waitSub">Preparing stream...</div>
    </div>
  </div>

  <video id="video" autoplay muted controls playsinline preload="auto"></video>

  <div class="controls">
    <button id="playPause" disabled>Pause</button>
    <label for="speedSelect">Speed</label>
    <select id="speedSelect" disabled>
      <option value="0.25">0.25x</option>
      <option value="0.5">0.5x</option>
      <option value="0.75">0.75x</option>
      <option value="1" selected>1.0x</option>
      <option value="1.25">1.25x</option>
      <option value="1.5">1.5x</option>
      <option value="2">2.0x</option>
    </select>
  </div>

<script>
  const dot = document.getElementById('statusDot');
  const statusText = document.getElementById('statusText');
  const video = document.getElementById('video');
  const waiting = document.getElementById('waiting');
  const waitSub = document.getElementById('waitSub');
  const playPause = document.getElementById('playPause');
  const speedSelect = document.getElementById('speedSelect');

  const STATUS_POLL_INTERVAL_MS = 300;
  const MAX_STATUS_FAILURES = 6;
  const AUTO_CLOSE_DELAY_MS = 2500;

  let streamRequested = false;
  let streamRetryTimer = null;
  let statusPollTimer = null;
  let autoCloseTimer = null;
  let consecutiveStatusFailures = 0;
  let serverDown = false;
  let lastStatusText = '';

  function setWaitingSubtext(text) {
    waitSub.textContent = text;
  }

  function setStatusText(text) {
    if (lastStatusText === text) return;
    lastStatusText = text;
    statusText.textContent = text;
  }

  function enablePlaybackControls() {
    playPause.disabled = false;
    speedSelect.disabled = false;
  }

  function disablePlaybackControls() {
    playPause.disabled = true;
    speedSelect.disabled = true;
  }

  function clearUiTimers() {
    if (streamRetryTimer != null) {
      clearTimeout(streamRetryTimer);
      streamRetryTimer = null;
    }
    if (statusPollTimer != null) {
      clearInterval(statusPollTimer);
      statusPollTimer = null;
    }
  }

  function enterServerDownState(waitingMessage) {
    if (serverDown) return;
    serverDown = true;

    clearUiTimers();

    dot.className = 'dot stopped';
    setStatusText('server stopped');

    video.pause();
    video.removeAttribute('src');
    video.classList.remove('visible');
    waiting.classList.remove('hidden');

    setWaitingSubtext(waitingMessage || 'Video server stopped. Closing window...');
    disablePlaybackControls();

    autoCloseTimer = setTimeout(() => {
      window.close();
      setTimeout(() => {
        window.location.replace('about:blank');
      }, 350);
    }, AUTO_CLOSE_DELAY_MS);
  }

  function showVideo() {
    video.classList.add('visible');
    waiting.classList.add('hidden');
    enablePlaybackControls();
  }

  function startVideoStream() {
    if (serverDown || streamRequested) return;
    streamRequested = true;

    setWaitingSubtext('Connecting to stream...');

    const src = '/video.mp4?run=' + encodeURIComponent('${safeRunId}') + '&t=' + Date.now();
    video.src = src;
    video.load();
    video.play().catch(() => {});
  }

  function scheduleStreamRetry(delayMs) {
    if (serverDown) return;
    if (streamRetryTimer != null) return;
    streamRetryTimer = setTimeout(() => {
      streamRetryTimer = null;
      streamRequested = false;
      startVideoStream();
    }, delayMs);
  }

  video.addEventListener('loadedmetadata', () => {
    showVideo();
  });

  video.addEventListener('loadeddata', () => {
    showVideo();
  });

  video.addEventListener('playing', () => {
    if (serverDown) return;
    showVideo();
    playPause.textContent = 'Pause';
  });

  video.addEventListener('pause', () => {
    playPause.textContent = 'Play';
  });

  video.addEventListener('error', () => {
    if (serverDown) return;
    video.classList.remove('visible');
    waiting.classList.remove('hidden');
    setWaitingSubtext('Stream not ready yet, retrying...');
    scheduleStreamRetry(300);
  });

  playPause.addEventListener('click', () => {
    if (video.paused) {
      video.play().catch(() => {});
    } else {
      video.pause();
    }
  });

  speedSelect.addEventListener('change', () => {
    const nextRate = Number(speedSelect.value) || 1;
    video.playbackRate = nextRate;
  });

  video.playbackRate = Number(speedSelect.value) || 1;

  async function checkStatus() {
    if (serverDown) return;
    try {
      const r = await fetch('/status', {cache: 'no-store'});
      const data = await r.json();
      consecutiveStatusFailures = 0;

      if (data.running) {
        dot.className = 'dot running';
        setStatusText('test running');
      } else {
        enterServerDownState('Test completed. Closing window...');
        return;
      }

      if (!data.videoReady) {
        setWaitingSubtext('Preparing stream... ' + (data.videoBytes || 0) + ' bytes');
      } else if (!video.classList.contains('visible')) {
        setWaitingSubtext('Video stream is ready, waiting for first frame...');
      }
    } catch (_) {
      consecutiveStatusFailures += 1;
      if (consecutiveStatusFailures >= MAX_STATUS_FAILURES) {
        enterServerDownState('Video server unavailable. Closing window...');
        return;
      }

      dot.className = 'dot stopped';
      setStatusText('reconnecting...');
      setWaitingSubtext('Video server unavailable, retrying...');
      scheduleStreamRetry(500);
    }
  }

  startVideoStream();
  checkStatus();
  statusPollTimer = setInterval(checkStatus, STATUS_POLL_INTERVAL_MS);
</script>
</body>
</html>`;

  res.writeHead(200, {
    'Content-Type': 'text/html; charset=utf-8',
    'Cache-Control': 'no-cache',
  });
  res.end(html);
}

function serveStatus(_req, res) {
  const stat = getVideoStat();
  res.writeHead(200, {
    'Content-Type': 'application/json',
    'Cache-Control': 'no-cache',
  });
  res.end(JSON.stringify({
    running: true,
    videoReady: !!stat && stat.size > 0,
    videoBytes: stat ? stat.size : 0,
    runId: RUN_ID,
  }));
}

function serveBackground(_req, res) {
  if (!fs.existsSync(BACKGROUND_FILE)) {
    res.writeHead(404, {'Content-Type': 'text/plain'});
    res.end('Background image not found');
    return;
  }

  res.writeHead(200, {
    'Content-Type': 'image/jpeg',
    'Cache-Control': 'public, max-age=3600',
  });
  fs.createReadStream(BACKGROUND_FILE).pipe(res);
}

function serveVideo(_req, res) {
  // Keep the response open and start streaming bytes as soon as they appear.
  // This avoids 503/reconnect loops and reduces first-frame latency.
  res.writeHead(200, {
    'Content-Type': 'video/mp4',
    'Cache-Control': 'no-cache',
    'Connection': 'keep-alive',
  });

  let position = 0;
  let nextTick = null;

  function scheduleNext(delayMs) {
    if (res.destroyed) return;
    nextTick = setTimeout(sendChunk, delayMs);
  }

  function sendChunk() {
    if (res.destroyed) return;

    const stat = getVideoStat();
    if (!stat) {
      scheduleNext(100);
      return;
    }

    const currentSize = stat.size;
    if (currentSize > position) {
      const stream = fs.createReadStream(VIDEO_FILE, {start: position, end: currentSize - 1});
      stream.on('data', (chunk) => {
        if (!res.destroyed) res.write(chunk);
      });
      stream.on('end', () => {
        position = currentSize;
        scheduleNext(75);
      });
      stream.on('error', () => {
        scheduleNext(150);
      });
    } else {
      scheduleNext(100);
    }
  }

  sendChunk();

  res.on('close', () => {
    if (nextTick != null) {
      clearTimeout(nextTick);
      nextTick = null;
    }
    console.log('[video-server] Client disconnected from video stream');
  });
}

const server = http.createServer((req, res) => {
  const requestUrl = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const pathname = requestUrl.pathname;
  console.log(`[video-server] ${req.method} ${requestUrl.pathname}${requestUrl.search}`);

  if (pathname === '/' || pathname === '/index.html') {
    serveHtml(req, res);
  } else if (pathname === '/video.mp4') {
    serveVideo(req, res);
  } else if (pathname === '/status') {
    serveStatus(req, res);
  } else if (pathname === '/background.jpg') {
    serveBackground(req, res);
  } else {
    res.writeHead(404, {'Content-Type': 'text/plain'});
    res.end('Not found');
  }
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`[video-server] Dashboard at http://0.0.0.0:${PORT}/`);
  console.log(`[video-server] Video stream at http://0.0.0.0:${PORT}/video.mp4`);
  console.log(`[video-server] Background image: ${BACKGROUND_FILE}`);
  console.log(`[video-server] Run ID: ${RUN_ID}`);
});

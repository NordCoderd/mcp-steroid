/**
 * Lightweight HTTP server that streams a growing MP4 file.
 *
 * Usage: node video-server.js <path-to-mp4> [port]
 *
 * The server responds on GET /video.mp4 with chunked transfer encoding,
 * continuously tailing the file as ffmpeg appends to it.
 */
const http = require('http');
const fs = require('fs');

const VIDEO_FILE = process.argv[2];
const PORT = parseInt(process.argv[3], 10) || 8765;

if (!VIDEO_FILE) {
  console.error('Usage: node video-server.js <path-to-mp4> [port]');
  process.exit(1);
}

const server = http.createServer((req, res) => {
  console.log(`[video-server] ${req.method} ${req.url}`);

  if (req.url !== '/video.mp4') {
    res.writeHead(404, {'Content-Type': 'text/plain'});
    res.end('Not found');
    return;
  }

  if (!fs.existsSync(VIDEO_FILE)) {
    res.writeHead(503, {'Content-Type': 'text/plain'});
    res.end('Video file not ready');
    return;
  }

  const stat = fs.statSync(VIDEO_FILE);
  if (stat.size < 1024) {
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
      // File may have been removed (test cleanup)
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
      // No new data yet — poll again
      setTimeout(sendChunk, 500);
    }
  }

  sendChunk();

  res.on('close', () => {
    console.log('[video-server] Client disconnected');
  });
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`[video-server] Serving ${VIDEO_FILE} on http://0.0.0.0:${PORT}/video.mp4`);
});

// server.mjs — isolated static server for the survey prototypes (loopback only).
// Serves the prototypes dir plus the worktree's vendored monaco at /monaco/*.
import { createServer } from 'node:http';
import { readFileSync, statSync } from 'node:fs';
import { join, normalize, extname } from 'node:path';

const PORT = Number(process.env.PROTO_PORT || 8477);
const ROOT = '/private/tmp/stand-mobile-diff-survey-20261118/delivery/mobile-diff-survey-20261118/prototypes';
const MONACO = '/private/tmp/stand-mobile-diff-survey-20261118/src/main/resources/web/vendor/monaco';
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.ttf': 'font/ttf', '.woff': 'font/woff', '.woff2': 'font/woff2', '.map': 'application/json' };

const server = createServer((req, res) => {
  let p = decodeURIComponent(new URL(req.url, 'http://127.0.0.1').pathname);
  let base = ROOT;
  if (p.startsWith('/monaco/')) { base = MONACO; p = p.slice('/monaco'.length); }
  if (p === '/') p = '/index.html';
  const file = normalize(join(base, p));
  if (!file.startsWith(base)) { res.writeHead(403); res.end(); return; }
  try {
    if (statSync(file).isDirectory()) { res.writeHead(404); res.end('dir'); return; }
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(readFileSync(file));
  } catch { res.writeHead(404); res.end('not found'); }
});
server.listen(PORT, '127.0.0.1', () => console.log(`prototype server on http://127.0.0.1:${PORT}`));

process.on('SIGTERM', () => { server.close(); process.exit(0); });
process.on('SIGINT', () => { server.close(); process.exit(0); });

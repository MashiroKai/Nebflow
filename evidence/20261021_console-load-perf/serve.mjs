#!/usr/bin/env node
// serve.mjs — stand-in static server for waterfall A/B measurement (zero deps).
// Mimics the gateway's static semantics: gzip on compressible types, ETag +
// Last-Modified validators, Cache-Control: no-cache, 304 on If-None-Match.
// Usage: node serve.mjs <rootDir> <port>
import { createServer } from 'node:http';
import { createReadStream, statSync } from 'node:fs';
import { createGzip } from 'node:zlib';
import { extname, join, normalize, resolve } from 'node:path';

const ROOT = resolve(process.argv[2] || '.');
const PORT = Number(process.argv[3] || 8098);

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'application/javascript', '.mjs': 'application/javascript',
  '.css': 'text/css', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.ico': 'image/x-icon', '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf',
  '.map': 'application/json', '.wasm': 'application/wasm', '.md': 'text/markdown',
};
const COMPRESSIBLE = /\.(html|js|mjs|css|json|svg|md|map|txt)$/;
const GZIP_FLOOR = 860; // http4s GZip gate below this skips compression

createServer((req, res) => {
  try {
    const url = new URL(req.url, 'http://x');
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    const file = normalize(join(ROOT, p));
    if (!file.startsWith(ROOT)) { res.writeHead(404).end(); return; }
    const st = statSync(file);
    if (!st.isFile()) { res.writeHead(404).end(); return; }
    const etag = `"${st.size.toString(16)}-${st.mtimeMs.toString(16)}"`;
    const inm = req.headers['if-none-match'];
    if (inm && inm.split(',').map((s) => s.trim()).includes(etag)) {
      res.writeHead(304, { ETag: etag, 'Cache-Control': 'no-cache' }).end();
      return;
    }
    const type = MIME[extname(file).toLowerCase()] || 'application/octet-stream';
    // W2 仿真：/assets/** 是内容哈希 dist 资产 → immutable（重启后引擎的真实行为）；
    // 其余静态面 no-cache（引擎现状语义）。
    const immutable = p.startsWith('/assets/');
    const headers = {
      'Content-Type': type,
      'Cache-Control': immutable ? 'public, max-age=31536000, immutable' : 'no-cache',
      ETag: etag,
      'Last-Modified': st.mtime.toUTCString(),
      Vary: 'Accept-Encoding',
    };
    const canGz = COMPRESSIBLE.test(file) && st.size >= GZIP_FLOOR &&
      /\bgzip\b/.test(req.headers['accept-encoding'] || '');
    if (canGz) headers['Content-Encoding'] = 'gzip';
    res.writeHead(200, headers);
    if (req.method === 'HEAD') { res.end(); return; }
    const stream = createReadStream(file);
    if (canGz) stream.pipe(createGzip()).pipe(res);
    else stream.pipe(res);
  } catch {
    res.writeHead(404, { 'Content-Length': 0 }).end();
  }
}).listen(PORT, '127.0.0.1', () => console.log(`serving ${ROOT} on http://127.0.0.1:${PORT}`));

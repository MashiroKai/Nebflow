// serve-proxy.mjs — 新前端 × 真机引擎联调座（本地 127.0.0.1 专用）。
//
// 背景：现役引擎 ：8080 是旧构建（web root 无本批新件），且禁杀禁重启。本座在
// 127.0.0.1:<port> 起 static（= 本 worktree 的 src/main/resources/web 新面）+
// 反向代理（/api → :8080、/ws upgrade → :8080 盲转发）——浏览器拿到新前端、
// 数据面仍是真机引擎的真实 delegate。只读联调，零写。
//
// 用法： node serve-proxy.mjs [port]   （默认 8123；Ctrl-C 退场）

import { createServer, request as httpRequest } from 'node:http';
import { readFileSync, existsSync, statSync } from 'node:fs';
import { join, normalize, extname } from 'node:path';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import net from 'node:net';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_ROOT = join(HERE, '..', '..', 'src', 'main', 'resources', 'web');
const TARGET_HOST = '127.0.0.1';
const TARGET_PORT = 8080;
const PORT = Number(process.argv[2] || 8123);

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.jpg': 'image/jpeg', '.ico': 'image/x-icon', '.json': 'application/json',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf', '.map': 'application/json',
};

const proxyToEngine = (req, res) => {
  const headers = { ...req.headers, host: `${TARGET_HOST}:${TARGET_PORT}` };
  if (headers.origin) headers.origin = `http://${TARGET_HOST}:${TARGET_PORT}`;
  if (headers.referer) headers.referer = `http://${TARGET_HOST}:${TARGET_PORT}/`;
  const proxy = httpRequest({
    host: TARGET_HOST, port: TARGET_PORT, path: req.url, method: req.method, headers,
  });
  proxy.on('response', (pr) => {
    res.writeHead(pr.statusCode, pr.headers);
    pr.pipe(res);
  });
  proxy.on('error', () => { res.writeHead(502); res.end('proxy error'); });
  req.pipe(proxy);
};

const server = createServer((req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${PORT}`);
  const p = decodeURIComponent(url.pathname);

  if (p === '/api' || p.startsWith('/api/')) {
    proxyToEngine(req, res); // 反代（Origin/Referer 改写在内）
    return;
  }

  // console-ticket 落地必须由引擎处理（消费一次性 ticket → 303 + Set-Cookie 会话）；
  // 静态座只管无 ticket 的普通导航。
  if (url.searchParams.has('ticket')) {
    proxyToEngine(req, res);
    return;
  }

  // 静态面（worktree 新前端）；'/' → index.html；越界拒绝。
  let file = normalize(join(WEB_ROOT, p === '/' ? 'index.html' : p));
  if (!file.startsWith(WEB_ROOT)) { res.writeHead(403); res.end('forbidden'); return; }
  if (!existsSync(file) || !statSync(file).isFile()) { res.writeHead(404); res.end('not found'); return; }
  res.writeHead(200, { 'content-type': MIME[extname(file)] || 'application/octet-stream' });
  res.end(readFileSync(file));
});

// /ws upgrade：盲 TCP 转发（握手头原样递给真机引擎，之后双向裸管道）。
server.on('upgrade', (req, socket, head) => {
  const target = net.connect(TARGET_PORT, TARGET_HOST, () => {
    const lines = [`${req.method} ${req.url} HTTP/1.1`];
    for (let i = 0; i < req.rawHeaders.length; i += 2) {
      const name = req.rawHeaders[i];
      const lower = name.toLowerCase();
      let value = req.rawHeaders[i + 1];
      if (lower === 'host') value = `${TARGET_HOST}:${TARGET_PORT}`; // WS 握手同源校验
      else if (lower === 'origin') value = `http://${TARGET_HOST}:${TARGET_PORT}`;
      lines.push(`${name}: ${value}`);
    }
    target.write(lines.join('\r\n') + '\r\n\r\n');
    if (head && head.length) target.write(head);
    socket.pipe(target);
    target.pipe(socket);
  });
  target.on('error', () => { try { socket.destroy(); } catch { /* noop */ } });
  socket.on('error', () => { try { target.destroy(); } catch { /* noop */ } });
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`delegate-panorama dev seat: http://127.0.0.1:${PORT} (web=${WEB_ROOT} → api :${TARGET_PORT})`);
});

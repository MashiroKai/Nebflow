// media-resolver-fallback.spec.mjs — resolver 腿出错回落票腿
// （mediaface-resolver-fallback 批 2026-10-22，#665 发送图片「媒体加载失败」续）的回归网。
//
// 症状链（作者高频踩）：长命标签页会话 cookie 滑动窗过期 / 网关重启窗 ⇒
//   用户附件面的 /uploads/ <img> GET 403/404 ⇒ hydrateMedia 的 resolver 腿
//   **直接 fail() 终态**（修前形态）⇒ 「媒体加载失败」注记粘死；而 nf-file 票腿
//   自带 reMint + 铸票 401/403 的 Bearer 重试 + 旧根字面路径双读（b9ed91bb6），
//   自愈能力永远等不到出场（前腿先死）。
// 修复（前端单点 popArtifacts.js hydrateMedia）：resolver URL 出错 ⇒ 摘监听、
//   进**同一条票腿**；双腿都败才落终态（fail 闩一次）。
//
// 本 spec 用小源服务器同时提供两条腿的面：
//   - /uploads/** 静态根：tmpRoot/uploads/ 下**故意缺件** ⇒ resolver 腿 404；
//   - POST /api/nf-ticket + GET /api/nf-file?path&ticket：按真实 nfTicket 协议
//     铸票/取件（真字节）⇒ 票腿可服务 ⇒ 回落链可实证「脸活了」。
// 负例（双腿都败）证明终态注记仍会出现（禁静默契约不破）。
//
// Run: node node_modules/@playwright/test/cli.js test tests/media-resolver-fallback.spec.mjs

import { test, expect } from '@playwright/test';
import fs from 'node:fs';
import http from 'node:http';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/media-resolver-fallback/harness.html';
// 票腿认可的路径前缀（= 服务根）。其它路径铸票拒答 ⇒ 双腿都败的负例面。
const SERVED = 'served';
const PNG_1X1 = Buffer.from(
  '89504e470d0a1a0a0000000d494844520000000100000001080600000'
  + '01f15c4890000000d49444154789c626001000000ffff030000060005'
  + '57bfabd40000000049454e44ae426082',
  'hex'
);
const MIME = { '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.html': 'text/html', '.css': 'text/css', '.js': 'text/javascript', '.mjs': 'text/javascript' };

let port;
let server;
let tmpRoot;
let servedRoot;
let mintCount = 0;

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.listen(0, '127.0.0.1', () => {
      const p = srv.address().port;
      srv.close(() => resolve(p));
    });
    srv.on('error', reject);
  });
}

/** 票腿协议（与 nfTicket.js mintRound/ticketUrl 同形）：
 *  POST /api/nf-ticket  {sessionId, paths[]} → {tickets: {path: {t, exp, uses}}, rejected[]}
 *  GET  /api/nf-file?path=…&ticket=…        → 200 字节 / 404。
 * 其余路径 = 静态服务（模块树 symlink + uploads 根**故意缺件** ⇒ resolver 404 面）。 */
function startOrigin(p) {
  const tickets = new Map(); // ticket → path
  server = http.createServer((req, res) => {
    const u = new URL(req.url, 'http://127.0.0.1');
    if (u.pathname === '/api/nf-ticket' && req.method === 'POST') {
      let body = '';
      req.on('data', (c) => { body += c; });
      req.on('end', () => {
        mintCount += 1;
        let paths = [];
        try { paths = (JSON.parse(body).paths) || []; } catch { /* malformed → 全拒 */ }
        const out = {};
        const rejected = [];
        for (const pth of paths) {
          if (typeof pth === 'string' && pth.startsWith(servedRoot + path.sep) && fs.existsSync(pth)) {
            const t = 'tk-' + Math.random().toString(36).slice(2);
            tickets.set(t, pth);
            out[pth] = { t, exp: Date.now() + 30 * 60 * 1000, uses: -1 };
          } else {
            rejected.push({ path: pth, reason: 'not-found' });
          }
        }
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ tickets: out, rejected }));
      });
      return;
    }
    if (u.pathname === '/api/nf-file' && req.method === 'GET') {
      const rawPath = u.searchParams.get('path') || '';
      const tk = u.searchParams.get('ticket') || '';
      const real = tickets.get(tk);
      let file = null;
      try { file = rawPath && decodeURIComponent(rawPath); } catch { file = null; }
      if (real && file === real && fs.existsSync(real)) {
        res.writeHead(200, { 'Content-Type': MIME[path.extname(real).toLowerCase()] || 'application/octet-stream' });
        fs.createReadStream(real).pipe(res);
      } else {
        res.writeHead(404); res.end('not found');
      }
      return;
    }
    // 静态：防穿越后从 tmpRoot 出件
    const rel = decodeURIComponent(u.pathname).replace(/^\/+/, '');
    if (rel.includes('..')) { res.writeHead(404); res.end(); return; }
    const file = path.join(tmpRoot, rel);
    if (!file.startsWith(tmpRoot)) { res.writeHead(404); res.end(); return; }
    if (fs.existsSync(file) && fs.statSync(file).isDirectory()) {
      res.writeHead(404); res.end(); return;
    }
    if (!fs.existsSync(file)) { res.writeHead(404); res.end(); return; }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(file).toLowerCase()] || 'application/octet-stream' });
    fs.createReadStream(file).pipe(res);
  });
  return new Promise((resolve) => server.listen(p, '127.0.0.1', resolve));
}

test.beforeAll(async () => {
  expect(fs.existsSync(path.join(REPO_ROOT, HARNESS_PATH.replace(/^\//, '')))).toBe(true);
  tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'nf-media-resolver-'));
  fs.symlinkSync(path.join(REPO_ROOT, 'src'), path.join(tmpRoot, 'src'), 'dir');
  fs.symlinkSync(path.join(REPO_ROOT, 'tests'), path.join(tmpRoot, 'tests'), 'dir');
  // uploads 静态根：**空目录** —— resolver 腿对任何文件名都 404
  // （旧根迁移后现役根缺件 / cookie 死 403 的同一面：快路取不到字节）。
  fs.mkdirSync(path.join(tmpRoot, 'uploads', 'sess-1'), { recursive: true });
  // 票腿服务根：真字节在**这里**（模拟 b9ed91bb6 双读：票腿能从别树拿到）。
  servedRoot = path.join(tmpRoot, SERVED);
  fs.mkdirSync(path.join(servedRoot, 'uploads', 'sess-1'), { recursive: true });
  // 非 uploads 族的服务路径（resolver 拒答面用）
  fs.mkdirSync(path.join(servedRoot, 'media'), { recursive: true });
  fs.writeFileSync(path.join(servedRoot, 'uploads', 'sess-1', '截屏2026-10-22 17.34.25.png'), PNG_1X1);
  fs.writeFileSync(path.join(servedRoot, 'media', 'plain.png'), PNG_1X1);
  port = await freePort();
  await startOrigin(port);
});

test.afterAll(async () => {
  if (server) await new Promise((r) => server.close(r));
  if (tmpRoot) fs.rmSync(tmpRoot, { recursive: true, force: true });
});

test.beforeEach(async ({ page }) => {
  mintCount = 0;
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction('window.__ready === true');
});

test('A1: resolver 命中但 URL 404 ⇒ 回落票腿取件成功 ⇒ 脸活、无失败注记（旧根双读治愈面）', async ({ page }) => {
  // uploads 族真路径（票腿服务根内、字节存在）⇒ attachmentImageUrl 必命中
  // /uploads/sess-1/截屏….png；但**静态 uploads 根无此件** ⇒ resolver 404
  // ⇒ 修前形态在此直接 fail；修后回落票腿 ⇒ nf-file 200 真字节。
  const p = path.join(servedRoot, 'uploads', 'sess-1', '截屏2026-10-22 17.34.25.png');
  const mounted = await page.evaluate((pp) => window.__mount({
    name: '截屏2026-10-22 17.34.25.png', type: 'image', path: pp,
  }), p);
  expect(mounted).toBe(true);
  const states = await page.evaluate(() => window.__imgStates());
  expect(states).toHaveLength(1);
  // 中途的 resolver URL（404 面）已被票腿 URL 取代。
  expect(new URL(states[0].src).pathname).toBe('/api/nf-file');
  expect(states[0].failed).toBe(false);
  expect(states[0].loaded).toBe(true); // 票腿字节真解码
  expect(await page.evaluate(() => window.__failedNotes())).toHaveLength(0);
});

test('A2: resolver 拒答（非 uploads 族）⇒ 直进票腿（媒体统一适配批语义不变）', async ({ page }) => {
  const p = path.join(servedRoot, 'media', 'plain.png');
  const mounted = await page.evaluate((pp) => window.__mount({
    name: 'plain.png', type: 'image', path: pp,
  }), p);
  expect(mounted).toBe(true);
  const states = await page.evaluate(() => window.__imgStates());
  expect(states).toHaveLength(1);
  expect(new URL(states[0].src).pathname).toBe('/api/nf-file');
  expect(states[0].failed).toBe(false);
  expect(states[0].loaded).toBe(true);
  expect(await page.evaluate(() => window.__failedNotes())).toHaveLength(0);
});

test('A3: 双腿都败（resolver 404 + 票腿拒答）⇒ 终态注记恰一次（禁静默契约不破）', async ({ page }) => {
  const mounted = await page.evaluate(() => window.__mount({
    name: 'gone.png', type: 'image',
    path: '/tmp/nf-media-resolver/uploads/sess-1/gone.png', // uploads 族 ⇒ resolver 404；文件不存在 ⇒ 票腿拒答
  }));
  expect(mounted).toBe(true);
  const states = await page.evaluate(() => window.__imgStates());
  expect(states).toHaveLength(1);
  expect(states[0].failed).toBe(true);
  expect(states[0].failedFlag).toBe(true);
  const notes = await page.evaluate(() => window.__failedNotes());
  expect(notes).toHaveLength(1); // 终态闩：双腿各败一次也只落一行
  expect(notes[0]).toContain('gone.png');
});

test('A4: inline preview 恒赢 ⇒ 零铸票、零回落（快路不变式）', async ({ page }) => {
  const dataUrl = 'data:image/png;base64,' + PNG_1X1.toString('base64');
  const p = path.join(servedRoot, 'media', 'plain.png');
  const mounted = await page.evaluate(({ src, pp }) => window.__mount({
    name: 'plain.png', type: 'image', path: pp, preview: src,
  }), { src: dataUrl, pp: p });
  expect(mounted).toBe(true);
  const states = await page.evaluate(() => window.__imgStates());
  expect(states).toHaveLength(1);
  expect(states[0].src).toBe(dataUrl);
  expect(states[0].loaded).toBe(true);
  expect(await page.evaluate(() => window.__failedNotes())).toHaveLength(0);
  expect(mintCount).toBe(0); // preview 在 ⇒ hydrateMedia 早退：两条腿都不进
});

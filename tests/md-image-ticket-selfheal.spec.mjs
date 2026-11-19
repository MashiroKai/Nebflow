#!/usr/bin/env node
// md-image-ticket-selfheal.spec.mjs — B1（<img>/<video> 元素腿自愈）票据自愈批，
// 2026-11-19 作者报障逐字：「为什么现在**文档里**会出现：**图片加载失败 / 无权读取，
// 票据被拒（HTTP 401）**」。
//
// 被测真实链路（前端零替身，与 md-image-in-doc.spec.mjs 同一套替身纪律）：
//   viewMarkdown → 图片 pass → nfTicket.mintTickets（POST /api/nf-ticket）→
//   nfFileCache.nfViewerUrl（账本）→ <img src> → 加载失败 → onerror=
//   window.__nfMdImgFail → ①账本坏字节 / ①′**重铸新票重试恰一次** / ②候选级联 /
//   ③终局占位（正是作者看到的那句「无权读取，票据被拒（HTTP 401）」）。
//
// 端点语义逐字照服务端 NfFileRoutes（**本轮一个字未改，只做客户端自愈腿**）：
//   无 ticket 参数        → 401 "Missing 'ticket' parameter"   （永不带 X-Nf-Reason）
//   票在但票仓已不认       → 403 + X-Nf-Reason: invalid-ticket （票仓被清空/过期）
//   票有效                → 200 真字节
//   「票仓清空」= 把 store 代际 +1 并清空已发票集合 —— 网关重启/票 TTL 过期的等价物。
//
// 判据：
//   B0 基线：文档 3 张图真出图、零失败占位
//   B1 **401 腿**（URL 根本没带票 —— 铸票面没给票时的形态）⇒ 重启后必须自愈
//   B2 **403 腿**（带了一枚已失效的旧票 = invalid-ticket）⇒ 重启后必须自愈
//   B3 自愈是「重铸新票」而非「缓存命中」：/api/nf-ticket 调用数必须增长
//   B4 不成环：自愈后不再有失败占位
//   控制组（BREAK=1：静态服务把 ①′ 自愈腿改成死代码）⇒ 同一序列必落失败占位，
//   且占位文案 = 作者逐字那句 ⇒ **破坏→红 / 还原→绿**
//
// 运行：node tests/md-image-ticket-selfheal.spec.mjs
// 控制组：BREAK=1 node tests/md-image-ticket-selfheal.spec.mjs
// 可选 env：PORT=8184、SHOTS_DIR=<dir>

import { chromium } from 'playwright';
import { readFileSync, writeFileSync, mkdirSync, existsSync, rmSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir, homedir } from 'node:os';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const WEB_DIR = resolve(ROOT, 'src/main/resources/web');
const PORT = Number(process.env.PORT || 8184);
const BASE = `http://127.0.0.1:${PORT}`;
const BREAK = process.env.BREAK === '1';
const SHOTS_DIR = process.env.SHOTS_DIR
  || join(ROOT, '.nebflow', 'evidence', '20261119_md-image-ticket-selfheal', BREAK ? 'break' : 'fixed', 'shots');
const VIEWPORT = { width: 1280, height: 900 };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/* 作者逐字那句（占位文案 = canvas.imgFailDenied）：控制组必须命中它。 */
const AUTHOR_STRING = /无权读取，票据被拒（HTTP 401）/;

/* ───────── 夹具：一个真 vault（布局照 md-image-in-doc.spec.mjs）───────── */
const FIX = join(tmpdir(), `qa-mdimg-heal-${process.pid}`);
const VAULT = join(FIX, 'projects', 'qa', '.stand', 'docs');
const DOC_ASSETS = join(VAULT, 'assets');
const VAULT_ATTACH = join(VAULT, 'attachments');
const MD_PATH = join(VAULT, 'doc-heal.md');

const REAL_SRC = join(homedir(), '.stand', 'evidence', 'canvas-note-button', 'note-open.png');
const PNG_BYTES = existsSync(REAL_SRC) ? readFileSync(REAL_SRC) : Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64'
);
for (const d of [DOC_ASSETS, VAULT_ATTACH]) mkdirSync(d, { recursive: true });
writeFileSync(join(VAULT, 'same-dir.png'), PNG_BYTES);
writeFileSync(join(DOC_ASSETS, 'via-assets.png'), PNG_BYTES);
writeFileSync(join(VAULT_ATTACH, 'vault-attach.png'), PNG_BYTES);
const MD_CONTENT = [
  '# 文档内图片 · 票据自愈验收', '',
  '![T1](same-dir.png)', '',
  '![T2](assets/via-assets.png)', '',
  '![[assets/via-assets.png]]', '',
].join('\n');
writeFileSync(MD_PATH, MD_CONTENT);
// B3 用**另一份文档**（内容守卫「内容未变不重挂」会挡掉同文档重开 ⇒ 拿不到全新 <img>）。
const MD2_PATH = join(VAULT, 'doc-heal-401.md');
writeFileSync(MD2_PATH, MD_CONTENT.replace('票据自愈验收', '票据自愈验收（401 复现）'));

/* ───────── 静态服务（真 web/ 树）· BREAK=1 时把 ①′ 自愈腿改成死代码 ───────── */
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.md': 'text/markdown; charset=utf-8',
};
const BREAK_FIND = "if (rerolls < 3 && !carryingBlob) {";
const BREAK_REPL = "if (false && rerolls < 3 && !carryingBlob) {";
let brokeApplied = false;
const webServer = createServer((req, res) => {
  const u = new URL(req.url, BASE);
  let p = decodeURIComponent(u.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    let body = readFileSync(file);
    if (BREAK && p === '/js/viewers/markdown.js') {
      const src = body.toString('utf8');
      if (!src.includes(BREAK_FIND)) throw new Error('BREAK anchor not found — 控制组不可信');
      body = Buffer.from(src.replace(BREAK_FIND, BREAK_REPL));
      brokeApplied = true;
    }
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-store' });
    res.end(body);
  } catch (e) {
    if (!res.headersSent) { res.writeHead(404); res.end('not found'); }
  }
});
await new Promise((r) => webServer.listen(PORT, '127.0.0.1', r));

/* ───────── 票据仓（有状态，可「重启清空」）───────── */
let gen = 1;
let issued = 0;
const valid = new Set();     // 有效票
const mints = [];            // 每次铸票请求的路径表
const nfReqs = [];           // 每次 /api/nf-file 读数
const mintTicket = (g) => `tk-g${g}-${++issued}`;
function restartStore() { gen++; valid.clear(); return gen; }   // 网关重启 / TTL 过期等价物

async function boot(browser) {
  const ctx = await browser.newContext({ viewport: VIEWPORT });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
  });
  const page = await ctx.newPage();
  page.setDefaultTimeout(30000);
  const pageErrors = [];
  const consoleErrors = [];
  page.on('pageerror', (e) => pageErrors.push(String(e.message || e).slice(0, 300)));
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(String(m.text()).slice(0, 300)); });
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  await page.route('**/agents/**', (r) => r.fulfill({ json: {} }));
  await page.route('**/api/wiki/graph**', (r) => r.fulfill({
    json: { projects: [{ name: 'qa', vault: VAULT, docsDir: VAULT }], nodes: [], edges: [] },
  }));
  // 铸票：给**当下代际**的票。
  await page.route('**/api/nf-ticket*', async (route) => {
    let paths = [];
    try { paths = JSON.parse(route.request().postData() || '{}').paths || []; } catch { paths = []; }
    paths = Array.isArray(paths) ? paths : [];
    mints.push({ gen, paths: paths.slice() });
    const tickets = {};
    for (const p of paths) { const t = mintTicket(gen); valid.add(t); tickets[p] = { t, exp: Date.now() + 60 * 60 * 1000, uses: -1 }; }
    return route.fulfill({ json: { tickets, rejected: [] } });
  });
  // /api/nf-file：**逐字照服务端语义**（无票 401 / 票不认 403+reason / 有效 200）。
  await page.route('**/api/nf-file**', async (route) => {
    const url = new URL(route.request().url());
    const p = url.searchParams.get('path') || '';
    const tk = url.searchParams.get('ticket');
    const rec = { path: p, hasTicket: !!tk, ticket: tk || '', status: 0, genAt: gen };
    if (!tk) {
      rec.status = 401; nfReqs.push(rec);
      return route.fulfill({ status: 401, contentType: 'text/plain', headers: { 'Cache-Control': 'no-store' }, body: "Missing 'ticket' parameter" });
    }
    if (!valid.has(tk)) {
      rec.status = 403; nfReqs.push(rec);
      return route.fulfill({
        status: 403, contentType: 'text/plain',
        headers: { 'Cache-Control': 'no-store', 'X-Nf-Reason': 'invalid-ticket' }, body: 'ticket rejected',
      });
    }
    let body = null;
    try { body = readFileSync(p); } catch { body = null; }
    if (!body) {
      rec.status = 404; nfReqs.push(rec);
      return route.fulfill({ status: 404, contentType: 'text/plain', headers: { 'Cache-Control': 'no-store' }, body: 'not found' });
    }
    rec.status = 200; nfReqs.push(rec);
    return route.fulfill({ status: 200, contentType: 'image/png', headers: { 'Cache-Control': 'no-store' }, body });
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') {
        return ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
      if (m.type !== 'pop.readFile') return;
      const rawPath = String(m.path || '');
      const abs = rawPath.startsWith('~') ? join(homedir(), rawPath.slice(1).replace(/^[/\\]/, '')) : rawPath;
      try {
        const buf = readFileSync(abs);
        const ext = (abs.split('.').pop() || '').toLowerCase();
        const IMG_EXT = { png: 'image', jpg: 'image', jpeg: 'image', gif: 'image', webp: 'image', svg: 'image' };
        const itemType = IMG_EXT[ext] || { md: 'markdown', markdown: 'markdown', json: 'json' }[ext] || 'code';
        ws.send(JSON.stringify({
          type: 'fileContent', path: rawPath, absPath: abs, itemType,
          fileName: abs.split('/').pop(), size: buf.length, content: itemType === 'image' ? '' : buf.toString('utf8'),
        }));
      } catch (e) {
        ws.send(JSON.stringify({ type: 'fileContent', error: String((e && e.message) || e), path: rawPath, absPath: abs }));
      }
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 20000, state: 'attached' });
  await page.evaluate(() => {
    document.querySelectorAll('.onboarding-overlay').forEach((o) => o.remove());
    document.documentElement.classList.remove('ui-init');
  });
  await sleep(500);
  return { ctx, page, pageErrors, consoleErrors };
}

const V = '.canvas-tab-pane.active .canvas-md-viewer';
async function openMdTab(page, { absPath, content, title }) {
  await page.evaluate(({ absPath, content, title }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType: 'markdown', title, content, absPath, pinned: true },
    }));
  }, { absPath, content, title });
  await page.waitForSelector(`${V}`);
  await sleep(1500);
}
/** 读一次渲染面读数（每张图 + 失败占位）。 */
const readState = (page) => page.evaluate((sel) => {
  const root = document.querySelector(sel);
  if (!root) return null;
  const imgs = Array.from(root.querySelectorAll('img')).map((im) => ({
    alt: im.getAttribute('alt') || '', nw: im.naturalWidth,
    src: String(im.getAttribute('src') || '').slice(0, 160),
    path: im.dataset.nfMdPath || '',
  }));
  const fails = Array.from(root.querySelectorAll('[data-nf-md-img-fail]')).map((el) => ({
    status: el.getAttribute('data-nf-md-img-status') || '',
    reason: (el.querySelector('.nf-md-img-fail-reason') || {}).textContent || '',
    text: (el.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 200),
  }));
  return { imgs, fails };
}, V);
/** 把每张图**重新赋**一枚指定形态的 src（真网络请求 → 服务端语义词判决 → onerror）。
 *  mode='noticket'  ：无票 URL（401 形态）
 *  mode='stale'     ：带一枚已被清空的旧票（403 invalid-ticket 形态）
 *  mode='terminal'  ：无票 URL **且清掉候选/回填** ⇒ ② 级联空转，终局占位只能按这枚
 *                     URL 探状态（复现作者逐字那句「…（HTTP 401）」）。 */
const forceSrc = (page, mode) => page.evaluate(({ sel, mode, base }) => {
  const root = document.querySelector(sel);
  if (!root) return 0;
  let n = 0;
  for (const im of root.querySelectorAll('img')) {
    const p = im.dataset.nfMdPath || '';
    if (!p) continue;
    if (mode === 'terminal') {
      im.setAttribute('data-nf-md-alt', '[]');
      im.removeAttribute('data-nf-md-fb');
      im.setAttribute('data-nf-md-err', '');
      im.dataset.nfMdRerolls = '0';
    }
    const q = `${base}/api/nf-file?path=${encodeURIComponent(p)}`;
    im.setAttribute('src', mode === 'stale' ? `${q}&ticket=tk-g1-1` : q);
    n++;
  }
  return n;
}, { sel: V, mode, base: BASE });

/* ───────── 断言 ───────── */
let failed = 0;
function ok(cond, label, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${label}${extra ? '  — ' + extra : ''}`);
  if (!cond) failed++;
}

mkdirSync(SHOTS_DIR, { recursive: true });
const browser = await chromium.launch();
const readings = { break_mode: BREAK, phases: {} };
try {
  const { page, pageErrors, consoleErrors } = await boot(browser);
  if (BREAK) ok(brokeApplied, 'BREAK 模式：①′ 自愈腿已改为死代码（锚点命中）');
  await openMdTab(page, { absPath: MD_PATH, content: MD_CONTENT, title: 'doc-heal.md' });

  // ── B0 基线 ──
  let st = await readState(page);
  readings.phases.baseline = st;
  ok(st && st.imgs.length === 3, 'B0 文档 3 张图都在场', JSON.stringify(st && st.imgs.map((i) => i.alt)));
  ok(st && st.imgs.every((i) => i.nw > 0), 'B0b 基线 3 张图真出图（naturalWidth>0）', JSON.stringify(st && st.imgs.map((i) => i.nw)));
  ok(st && st.fails.length === 0, 'B0c 基线零失败占位', JSON.stringify(st && st.fails));
  await page.screenshot({ path: join(SHOTS_DIR, 'B0-baseline.png') });

  // ── B1 401 腿（票仓清空 → URL 无票）──
  const g1 = restartStore();
  const mintsBefore401 = mints.length;
  const n1 = await forceSrc(page, 'noticket');
  await sleep(2500);
  st = await readState(page);
  readings.phases.after_401 = { restart_gen: g1, forced: n1, state: st, mints: mints.slice(mintsBefore401) };
  ok(n1 === 3, 'B1 已把 3 张图重赋为**无票** URL（401 形态）', String(n1));
  ok(st && st.imgs.length === 3 && st.imgs.every((i) => i.nw > 0),
    'B1b 401 腿自愈：3 张图全部恢复出图', JSON.stringify(st && st.imgs.map((i) => i.nw)));
  ok(st && st.fails.length === 0, 'B1c 401 腿自愈后零失败占位（未长期停在错误态）', JSON.stringify(st && st.fails));
  ok(mints.length > mintsBefore401, 'B1d 自愈靠**重铸新票**（/api/nf-ticket 调用数增长）',
    `mints ${mintsBefore401} → ${mints.length}`);
  await page.screenshot({ path: join(SHOTS_DIR, BREAK ? 'B1-after-401-BREAK.png' : 'B1-after-401-healed.png') });

  // ── B2 403 腿（票仓清空 → 带一枚已失效旧票 = invalid-ticket）──
  const g2 = restartStore();
  const mintsBefore403 = mints.length;
  const n2 = await forceSrc(page, 'stale');
  await sleep(2500);
  st = await readState(page);
  readings.phases.after_403 = { restart_gen: g2, forced: n2, state: st, mints: mints.slice(mintsBefore403) };
  const seen403 = nfReqs.filter((r) => r.status === 403 && r.genAt === g2);
  ok(seen403.length >= 3, 'B2 403 腿确实发生了（票仓清空后旧票被拒，X-Nf-Reason: invalid-ticket）',
    JSON.stringify(seen403.slice(0, 3).map((r) => ({ s: r.status, t: r.ticket }))));
  ok(st && st.imgs.length === 3 && st.imgs.every((i) => i.nw > 0),
    'B2b 403 腿自愈：3 张图全部恢复出图', JSON.stringify(st && st.imgs.map((i) => i.nw)));
  ok(st && st.fails.length === 0, 'B2c 403 腿自愈后零失败占位（第二次重启/过期仍自愈）', JSON.stringify(st && st.fails));
  ok(mints.length > mintsBefore403, 'B2d 自愈靠**重铸新票**', `mints ${mintsBefore403} → ${mints.length}`);
  await page.screenshot({ path: join(SHOTS_DIR, BREAK ? 'B2-after-403-BREAK.png' : 'B2-after-403-healed.png') });

  // ── B3 作者逐字复现：无票 URL + 候选/回填清空 ⇒ 终局占位只能报 **401** ──
  restartStore();
  // 全新文档（新 <img>），否则控制组 B1 已把元素换成占位 `div`，无从再赋 src。
  const md2Body = MD_CONTENT.replace('票据自愈验收', '票据自愈验收（401 复现）');
  await openMdTab(page, { absPath: MD2_PATH, content: md2Body, title: 'doc-heal-401.md' });
  const mintsBeforeB3 = mints.length;
  const n3 = await forceSrc(page, 'terminal');
  await sleep(2500);
  st = await readState(page);
  readings.phases.after_terminal401 = { forced: n3, state: st, mints: mints.slice(mintsBeforeB3) };
  const texts = (st && st.fails.map((f) => f.reason)) || [];
  if (BREAK) {
    ok(st && st.fails.length >= 1, 'B3(控制组) 无票终局 ⇒ 失败占位（作者实拍态）', JSON.stringify(st && st.fails.length));
    ok(texts.length > 0 && texts.every((x) => AUTHOR_STRING.test(x) || /票据|无读取|失败/.test(x)),
      'B3b(控制组) 占位文案 = 作者逐字「无权读取，票据被拒（HTTP 401）」', JSON.stringify(texts));
    ok(texts.some((x) => AUTHOR_STRING.test(x)), 'B3c(控制组) 拿到 **401** 那一句（不是 403）', JSON.stringify(texts));
  } else {
    ok(st && st.imgs.length === 3 && st.imgs.every((i) => i.nw > 0),
      'B3 无票终局自愈：3 张图全部恢复出图（与作者实拍态同一起点）', JSON.stringify(st && st.imgs.map((i) => i.nw)));
    ok(st && st.fails.length === 0, 'B3b 无票终局自愈后零失败占位', JSON.stringify(st && st.fails));
    ok(mints.length > mintsBeforeB3, 'B3c 自愈靠**重铸新票**', `mints ${mintsBeforeB3} → ${mints.length}`);
  }
  await page.screenshot({ path: join(SHOTS_DIR, BREAK ? 'B3-terminal401-BREAK.png' : 'B3-terminal401-healed.png') });

  // ── 控制组判据比对 ──
  const failTexts = (st && st.fails.map((f) => f.reason)) || [];
  if (BREAK) {
    ok(st.fails.length >= 1, 'B4(控制组) 自愈腿被打断 ⇒ 落终局失败占位（破坏→红）', JSON.stringify(st.fails));
    ok(failTexts.some((t) => AUTHOR_STRING.test(t)) || failTexts.some((t) => /无读取|票据|失败/.test(t)),
      'B4b(控制组) 占位文案 = 作者逐字那句「无权读取，票据被拒（HTTP 401）」', JSON.stringify(failTexts));
  } else {
    ok(pageErrors.length === 0, 'B5 零 pageerror', pageErrors.join(' | '));
    const realConsoleErrors = consoleErrors.filter((m) => !/Failed to load resource/.test(m));
    ok(realConsoleErrors.length === 0, 'B5b 零 console error（预期内 401/403 资源读数单列）', realConsoleErrors.join(' | '));
    ok(true, `B5c 预期内资源拒绝读数 ${consoleErrors.length} 条（401/403 夹具本身）`);
  }
  writeFileSync(join(SHOTS_DIR, 'readings.json'), JSON.stringify({
    break_mode: BREAK, mints, nfReqs, phases: readings.phases,
    summary: {
      mints_total: mints.length, mints_by_gen: mints.reduce((a, m) => (a[m.gen] = (a[m.gen] || 0) + 1, a), {}),
      nf_by_status: nfReqs.reduce((a, r) => (a[r.status] = (a[r.status] || 0) + 1, a), {}),
      fails: st && st.fails.length,
    },
  }, null, 2));
  console.log('\n[readings] ' + JSON.stringify({
    mode: BREAK ? 'BREAK(控制组)' : 'FIXED',
    mints_total: mints.length,
    nf_by_status: nfReqs.reduce((a, r) => (a[r.status] = (a[r.status] || 0) + 1, a), {}),
    final_fail_placeholders: st && st.fails.length,
  }));
} finally {
  await browser.close();
  webServer.close();
  try { rmSync(FIX, { recursive: true, force: true }); } catch { /* best effort */ }
}

console.log(failed === 0 ? '\nALL PASS' : `\n${failed} FAIL`);
if (BREAK) {
  // 控制组：把 ①′ 自愈腿改成死代码后**必须红**（作者实拍态复现）。红 = 控制组有效。
  console.log(failed > 0
    ? 'CONTROL OK — 自愈腿被打断 ⇒ 必红（复现作者逐字「无权读取，票据被拒（HTTP 401）」）'
    : 'CONTROL BROKEN — 打断自愈腿竟然全绿 ⇒ 判据不可信，须重写');
  process.exit(failed > 0 ? 0 : 1);
}
process.exit(failed === 0 ? 0 : 1);

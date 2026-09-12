// canvas-preview-nav-parity.spec.mjs — Canvas 预览面等效性（方案 A：Canvas 内导航路由）
//
// 作者裁定 2026-09-12 07:58（逐字）：「浏览器能做到的事，我们 canvas 也要能。」
// ⇒ 本地交付物在 Canvas 预览框内的体验必须与浏览器等效；「引导用户去浏览器」口径
//   已作废。本 spec 验收实施批 A1–A5a + 同批必修（跨源静默失明 / 缩放引擎回收 /
//   改写器越界改写），按取证报告 §6 的 R1–R6 判红口径逐条给读数：
//
//   R1 交付物不改：普通 `<a href>`（相对 / 绝对 fs，**无 target**）点击后目标内容呈现、
//      预览帧不销毁、无 "Rendering was stopped"；
//   R2 真样本可达：真汇总页 4 条链接（3×.html + 1×.md）点击均落到内容
//      （静态档 = 真交付物逐字节内容 + 磁盘真身目标；真实例档见 LIVE 段）；
//   R3 形态等价：无 target / `target="_blank"` 行为一致；聊天卡面点击不得静默无反应；
//   R4 真护栏不回退：`location.href = <app 根绝对 URL>` 仍显示递归提示；
//   R5 安全不回退：/api/nf-file 读面负控矩阵逐条不变（LIVE 段，需真隔离实例）；
//   R6 重写不伤非属性文本：`onclick` / 正文 `href=` / `data-*` 渲染后与源逐字一致。
//
// 被测真实链路（前端零替身）：
//   workspace-open-item → canvas.js openWorkspaceItem → viewers/html.js viewHtml
//   → viewers/shared.js resolveLocalFiles（标签上下文改写）→ srcdoc iframe
//   → 帧内 localLinkNavScript（capture 点击拦截）→ postMessage
//   → 父侧 bindLocalLinkBridge → workspace-open-item（file: / url:）
//   → canvas 标签（HTML / markdown / URL 查看器）。
// 后端替身仅两处：/api/nf-file + /api/nf-ticket（tests/nf-ticket-mock.mjs 假票），
// 以及 WS `pop.readFile` 应答（按 WebSocketRoutes.scala:2280 真语义实现：~ 展开 +
// 绝对路径 + 存在性 + ≤10MB + FileTypeRegistry 扩展判定）。LIVE 段用真后端复核
// R2 与 R5。
//
// 运行：
//   node tests/canvas-preview-nav-parity.spec.mjs                        # 门禁档（默认）
//   NEBFLOW_LIVE_BASE=http://127.0.0.1:8098 NEBFLOW_LIVE_HOME=/tmp/qa-x \
//     node tests/canvas-preview-nav-parity.spec.mjs                      # 追加真实例档
// 可选 env：PORT=8131、CROSS_PORT=8132、SHOTS_DIR=<dir>

import { chromium } from 'playwright';
import { readFileSync, writeFileSync, mkdirSync, rmSync, existsSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir, homedir } from 'node:os';
import { installTicketMock, ticketGuard } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const WEB_DIR = resolve(ROOT, 'src/main/resources/web');
const PORT = Number(process.env.PORT || 8131);
const CROSS_PORT = Number(process.env.CROSS_PORT || 8132);
const BASE = `http://127.0.0.1:${PORT}`;
const CROSS_BASE = `http://127.0.0.1:${CROSS_PORT}`;
const FIX_DIR = join(tmpdir(), 'canvas-preview-nav-parity-fixtures');
const SHOTS_DIR = process.env.SHOTS_DIR || '';
const LIVE_BASE = process.env.NEBFLOW_LIVE_BASE || '';
const LIVE_HOME = process.env.NEBFLOW_LIVE_HOME || '';
// 真交付物（只读引用；本批禁改——其自身缺陷已另派修复）
const REAL_PAGE = join(homedir(), '.nebflow/docs/Nebflow/assets/20260912_friendmsg-batch-plan/index.html');
const MARKERS = { a: 'TARGET-A-MARKER', b: 'TARGET-B-MARKER', c: 'TARGET-C-MARKER', md: 'TARGET-MD-MARKER' };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/* ─────────────────────────── 夹具（运行时生成，不落 repo） ─────────────────────────── */
const F = {
  a: join(FIX_DIR, 'target-a.html'),
  b: join(FIX_DIR, 'target-b.html'),
  c: join(FIX_DIR, 'target-c.html'),
  md: join(FIX_DIR, 'doc.md'),
  png: join(FIX_DIR, 'img.png'),
  probe: join(FIX_DIR, 'probe.html'),
};
const PNG_1X1 = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64');

function writeFixtures() {
  rmSync(FIX_DIR, { recursive: true, force: true });
  mkdirSync(FIX_DIR, { recursive: true });
  writeFileSync(F.a, `<!DOCTYPE html><html><body><h1>${MARKERS.a}</h1></body></html>`);
  writeFileSync(F.b, `<!DOCTYPE html><html><body><h1>${MARKERS.b}</h1></body></html>`);
  writeFileSync(F.c, `<!DOCTYPE html><html><body><h1>${MARKERS.c}</h1></body></html>`);
  writeFileSync(F.md, `# ${MARKERS.md}\n\nmarkdown target body.\n`);
  writeFileSync(F.png, PNG_1X1);
  writeFileSync(F.probe, `<!DOCTYPE html><html><head><meta charset="utf-8"><title>probe</title></head><body>
<h1 id="probe-root">PROBE ROOT</h1>
<button id="pollute-button" onclick="location.href='/zzz'">pollute</button>
<div id="data-src" data-src="${F.png}"></div>
<p id="prose">prose that mentions href='/prose-not-a-link' and src='/also-not'</p>
<script>var cfg={href:'/from-inline-script'};<\/script>
<a id="rel-link" href="target-a.html">rel</a>
<a id="abs-link" href="${F.b}">abs</a>
<a id="abs-link-blank" href="${F.c}" target="_blank" rel="noopener">abs blank</a>
<a id="md-link" href="${F.md}">md</a>
<a id="proxied-link" href="/api/nf-file?path=${encodeURIComponent(F.a)}">proxied</a>
<a id="ext-link" href="${CROSS_BASE}/ext.html">ext</a>
<a id="hash-link" href="#tail">hash</a>
<a id="mail-link" href="mailto:a@b.c">mail</a>
<img id="probe-img" src="${F.png}" alt="i">
<button id="go-app-root" onclick="location.href='${BASE}/'">app root</button>
<button id="go-proxy" onclick="location.href='/api/nf-file?path=${encodeURIComponent(F.png)}&ticket=MOCK'">proxy</button>
<button id="go-cross" onclick="location.href='${CROSS_BASE}/ext.html'">cross</button>
<div style="height:2400px"></div>
<h2 id="tail">TAIL</h2>
</body></html>`);
}

/* ─────────────────────────── 静态服务（真 web/ 树 + 跨源源站） ─────────────────────────── */
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
};
const crossHits = { n: 0 };
const webServer = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch { res.writeHead(404); res.end('not found'); }
});
const crossServer = createServer((req, res) => {
  crossHits.n++;
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
  res.end('<!DOCTYPE html><html><body><h1>CROSS-ORIGIN-MARKER</h1></body></html>');
});
await new Promise((r) => webServer.listen(PORT, '127.0.0.1', r));
await new Promise((r) => crossServer.listen(CROSS_PORT, '127.0.0.1', r));
writeFixtures();
function realPageReadable() { try { readFileSync(REAL_PAGE); return true; } catch { return false; } }

/* ─────────────────────────── /api/nf-file 模拟（真端点语义） ─────────────────────────── */
async function nfFileRoute(route) {
  if (await ticketGuard(route)) return;               // 无票一律 401（真端点判据）
  const p = new URL(route.request().url()).searchParams.get('path') || '';
  try {
    const body = readFileSync(p);
    return route.fulfill({ status: 200, contentType: MIME[extname(p)] || 'application/octet-stream', body });
  } catch {
    return route.fulfill({ status: 404, contentType: 'text/plain', body: 'not found' });
  }
}

/* ─────────────────────────── 应用引导（WS 替身：pop.readFile 真语义） ─────────────────────────── */
const EXT_ITEM = { html: 'html', htm: 'html', md: 'markdown', markdown: 'markdown', json: 'json', png: 'image', jpg: 'image', svg: 'image', pdf: 'pdf' };
const BINARY = new Set(['png', 'jpg', 'jpeg', 'gif', 'svg', 'webp', 'bmp', 'pdf']);
const MAX_READ = 10 * 1024 * 1024;

async function boot(browser, live = false) {
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 } });
  let token = 't';
  if (live) {
    // 真实例的 <home>/auth.json 是**裸 token 字符串**（不是 {token: …} 对象；实测 45 字节，
    // 无换行）。两种形态都吃：JSON 字符串 → 解出来，非 JSON → 原样。
    try {
      const raw = readFileSync(join(LIVE_HOME, 'auth.json'), 'utf8').trim();
      let val = raw;
      try { const j = JSON.parse(raw); val = (typeof j === 'string' ? j : (j.token || j.gatewayToken || raw)); } catch { /* 裸串 */ }
      if (val) token = val;
    } catch { /* 无 auth.json：cookie 路径仍可用 */ }
  }
  await ctx.addInitScript((tok) => {
    localStorage.setItem('nebflow_token', tok); localStorage.setItem('neblink_token', tok);
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
  }, token);
  const page = await ctx.newPage();
  page.setDefaultTimeout(8000);
  const errors = [];
  const fileRequests = [];
  const readFileCalls = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  page.on('request', (r) => { if (r.url().includes('/api/nf-file')) fileRequests.push(decodeURIComponent(r.url())); });
  if (!live) {
    await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
    await page.route(/\/api\/nf-file\?/, nfFileRoute);
    await installTicketMock(page);      // 后注册者优先 → 覆盖上面的 catch-all
    await page.routeWebSocket(/\/ws/, (ws) => {
      ws.onMessage((raw) => {
        let m; try { m = JSON.parse(raw); } catch { return; }
        if (m.type === 'getHistory') {
          return ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
        }
        if (m.type !== 'pop.readFile') return;
        const rawPath = String(m.path || '');
        const abs = rawPath.startsWith('~') ? join(homedir(), rawPath.slice(1).replace(/^[/\\]/, '')) : rawPath;
        readFileCalls.push(abs);
        try {
          const buf = readFileSync(abs);
          if (buf.length > MAX_READ) throw new Error('file exceeds 10MB limit');
          const ext = (abs.split('.').pop() || '').toLowerCase();
          const msg = { type: 'fileContent', path: rawPath, absPath: abs, itemType: EXT_ITEM[ext] || 'code', fileName: abs.split('/').pop(), size: buf.length };
          if (!BINARY.has(ext)) msg.content = buf.toString('utf8');
          ws.send(JSON.stringify(msg));
        } catch (e) {
          ws.send(JSON.stringify({ type: 'fileContent', error: String((e && e.message) || e), path: rawPath, absPath: abs }));
        }
      });
      ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
      ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
    });
  }
  // 真实 gateway 的开发档入口是 `/`（web/index.html 由 Root 路由装配 brand 注入后返回）；
  // `/index.html` 在真实例上是 404（静态路由里没有这条）。静态 harness 反过来直接吃文件。
  await page.goto(live ? LIVE_BASE + '/' : BASE + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 20000 });
  // onboarding 遮罩（真实例可能弹）——harness 侧移除，不属被测面
  await page.evaluate(() => { document.querySelectorAll('.onboarding-overlay').forEach((o) => o.remove()); });
  await sleep(400);
  const h = { ctx, page, errors, fileRequests, readFileCalls, mocked: !live };
  LAST = h;
  return h;
}

/* ─────────────────────────── 页面操作助手 ─────────────────────────── */
/** 最后启动的 harness（一次只跑一个页面，块内唯一 —— 供 openHtmlDoc 里的静默期用）。 */
let LAST = null;
/** 等 Canvas 自己的「标签激活后后台刷新」这一趟往返走完。
 *
 *  canvas.js setActiveTab → scheduleFileRefresh（300ms 防抖）→ pop.readFile →
 *  fileContent → explorer 派回 workspace-open-item → 标签重渲染一次。这趟重渲染与被测行为
 *  无关，却正好落在断言窗口里：R4 的递归护栏把 pane 换成提示之后，刷新应答会把文档重新渲染
 *  回来，护栏读数被抹掉（实测：不等待 → `href=about:srcdoc` + 有 iframe；等完 → 护栏稳定）。
 *  等到该文件的 readFile 应答已到 + 冷却/渲染落定的宽限期，之后唯一能改变 pane 的就是本块的
 *  点击。（真实例档没有 WS 替身可观测，用固定宽限期。） */
async function settleAfterMount(absPath) {
  if (!LAST) return;
  if (LAST.mocked) {
    const t0 = Date.now();
    while (Date.now() - t0 < 4000 && !LAST.readFileCalls.includes(absPath)) await sleep(100);
  }
  await sleep(1200);
}

/** 以 workspace-open-item 打开真身文件为 Canvas HTML 标签（Pop 工具同款腿）。 */
async function openHtmlDoc(page, absPath, content, title, pinned = false) {
  await page.evaluate(({ absPath, content, title, pinned }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType: 'html', title, content, absPath, pinned },
    }));
  }, { absPath, content, title, pinned });
  await page.waitForSelector('.canvas-tab-pane.active iframe[data-nf-canvas-html]');
  await settleAfterMount(absPath);
  return waitFrameInPane(page, 'file:' + absPath);
}
/** 帧内元素点击。用 `el.click()` 而不是 Playwright pointer click：Canvas 的标签激活
 *  在本 harness 里是竞态的——上一次 pop.readFile 应答晚到时 openWorkspaceItem 会
 *  setActiveTab 把非活动 pane 抢回去，pane display:none，pointer click 报
 *  "element is not visible"。`el.click()` 派发的是带激活行为的真实 click（与用户点击
 *  走同一条 capture 监听 → preventDefault → postMessage 链），只是不做命中测试；
 *  R1 的第一个点击另用真 pointer click 覆盖命中面。 */
async function clickInProbe(page, probeId, selector) {
  const fr = await waitFrameInPane(page, probeId);
  if (!fr) { results.push(`      (frame missing for ${probeId})`); return null; }
  const hit = await fr.evaluate((sel) => {
    const el = document.querySelector(sel);
    if (!el) return false;
    el.click();
    return true;
  }, selector).catch(() => false);
  if (!hit) results.push(`      (anchor missing in frame: ${selector})`);
  return fr;
}
/** 父文档内元素点击（返回条按钮等）：真 pointer click，失败退化为 DOM click。 */
async function clickSel(page, selector) {
  try { await page.click(selector, { timeout: 3000 }); return true; }
  catch {
    return page.evaluate((sel) => {
      const el = document.querySelector(sel);
      if (!el) return false;
      el.click();
      return true;
    }, selector).catch(() => false);
  }
}
/** 指定 pane 内的 preview 帧（pane 可处于非活动态）。 */
async function waitFrameInPane(page, tabId) {
  for (let i = 0; i < 30; i++) {
    const el = await page.$(`.canvas-tab-pane[data-tab-id="${tabId}"] iframe[data-nf-canvas-html]`);
    if (el) {
      const fr = await el.contentFrame();
      if (fr) {
        const ready = await fr.evaluate(() => document.readyState !== 'loading' && !!document.body).catch(() => false);
        if (ready) return fr;
      }
    }
    await sleep(200);
  }
  return null;
}
async function frameText(fr) {
  if (!fr) return '';
  const html = await fr.locator('body').innerText().catch(() => '');
  return html || (await fr.evaluate(() => document.body ? document.body.textContent : '').catch(() => ''));
}
async function tabCount(page) {
  return page.evaluate(() => document.querySelectorAll('.canvas-tab').length);
}
/** 「点击不得走代理读面」的判据只看**文档类**请求。
 *  一个真实交付物的预览页（如 fm-attach-style-preview.html）自带大量
 *  `<img src="shots/*.png">` 截图，它们由同一个改写器代理成 /api/nf-file —— 那是**目标页面
 *  自己的资源面**，不是点击的路由腿，构不成「点击偷偷用了代理」的证据。这里按扩展名把文档类
 *  请求挑出来判红，非文档类剔除（明细行仍打印原始 URL，读数不隐藏）。 */
const DOC_PROXY_RE = /\.(html?|xhtml|md|markdown|txt|json|csv|ya?ml|xml)$/i;
function isDocProxyRequest(u) {
  const m = /[?&]path=([^&]*)/.exec(u);
  if (!m) return true;                                   // 无法解析 → 视为文档类，宁可判红
  try { return DOC_PROXY_RE.test(decodeURIComponent(m[1])); } catch { return true; }
}
/** 等某个标签（按 tab id 定位，与「谁是活动标签」无关——标签激活在 harness 里是
 *  竞态的）里出现目标内容。HTML 目标渲染在自成一层的 srcdoc 里，markdown/纯文本
 *  目标直接落在 pane 上，两条路都覆盖。 */
async function waitForTabContent(page, tabId, marker, ms = 10000) {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) {
    const el = await page.$(`.canvas-tab-pane[data-tab-id="${tabId}"] iframe[data-nf-canvas-html]`);
    if (el) {
      const txt = await frameText(await el.contentFrame());
      if (txt.includes(marker)) return true;
    }
    const paneText = await page.evaluate((id) => document.querySelector(`.canvas-tab-pane[data-tab-id="${id}"]`)?.textContent || '', tabId);
    if (paneText.includes(marker)) return true;
    await sleep(200);
  }
  return false;
}
async function stoppedAnywhere(page) {
  return page.evaluate(() => (document.body.textContent || '').includes('Rendering was stopped'));
}
/** probe 标签的 pane 是否还活着、且仍在渲染原文档（「导航被拦下」的直接读数）。 */
async function probePaneAlive(page, probeId, marker) {
  const el = await page.$(`.canvas-tab-pane[data-tab-id="${probeId}"] iframe[data-nf-canvas-html]`);
  if (!el) return { alive: false, sameDoc: false, url: 'no-frame' };
  const fr = await el.contentFrame();
  const txt = await frameText(fr);
  return { alive: true, sameDoc: txt.includes(marker), url: await fr.evaluate(() => location.href).catch(() => '?') };
}

let pass = 0, fail = 0, skip = 0;
const results = [];
function ok(name, cond, detail = '') {
  if (cond) { pass++; results.push(`PASS  ${name}${detail ? '  — ' + detail : ''}`); }
  else { fail++; results.push(`FAIL  ${name}${detail ? '  — ' + detail : ''}`); }
}
function skipped(name, why) { skip++; results.push(`SKIP  ${name}  — ${why}`); }

const browser = await chromium.launch();
const PROBE_ID = 'file:' + F.probe;

/* ════════════ R6 · 改写器：只改真属性，不伤文本 / onclick / data-* ════════════ */
{
  const { ctx, page } = await boot(browser);
  const out = await page.evaluate(async ({ fixDir }) => {
    const { resolveLocalFiles } = await import('/js/viewers/shared.js');
    const src = [
      `<button onclick="location.href='/zzz'">go</button>`,
      `<div data-src="${fixDir}/img.png" data-href="${fixDir}/target-a.html"></div>`,
      `<p>prose that mentions href='/prose-not-a-link' and src='/also-not'</p>`,
      `<script>var cfg={href:'/from-inline-script'};<\/script>`,
      `<script>var s = '<a href="${fixDir}/in-script.html">x</a>';<\/script>`,
      `<img src="./rel-img.png" alt="i">`,
      `<link rel="stylesheet" href="${fixDir}/style.css">`,
      `<script src="https://cdn.example.com/a.js"><\/script>`,
      `<a href="page.html" class="card">page</a>`,
      `<a href="${fixDir}/target-a.html" target="_blank" rel="noopener">abs</a>`,
      `<a href="/api/nf-file?path=${encodeURIComponent(fixDir + '/proxied.html')}&ticket=OLD">proxied</a>`,
      `<a href="https://example.com/x">ext</a>`,
      `<a href="#sec">hash</a>`,
      `<a href="mailto:a@b.c">mail</a>`,
      `<img src="${fixDir}/not-a-real-link-in-image.png">`,
    ].join('\n');
    return await resolveLocalFiles(src, fixDir);
  }, { fixDir: FIX_DIR });

  ok('R6 onclick 逐字不变', out.includes(`onclick="location.href='/zzz'"`));
  ok('R6 data-src 逐字不变', out.includes(`data-src="${FIX_DIR}/img.png"`));
  ok('R6 data-href 逐字不变', out.includes(`data-href="${FIX_DIR}/target-a.html"`));
  ok('R6 正文 href/src 字面量逐字不变', out.includes(`<p>prose that mentions href='/prose-not-a-link' and src='/also-not'</p>`));
  ok('R6 行内脚本对象字面量逐字不变', out.includes(`var cfg={href:'/from-inline-script'};`));
  ok('R6 <script> 体内的标签串不被当作标记', out.includes(`var s = '<a href="${FIX_DIR}/in-script.html">x</a>';`));
  ok('A1 资源型 img src 仍走代理+票据', /<img src="\/api\/nf-file\?path=[^"]*rel-img\.png&ticket=[^"]*"/.test(out));
  ok('A1 资源型 link href 仍走代理+票据', /<link rel="stylesheet" href="\/api\/nf-file\?path=[^"]*style\.css&ticket=[^"]*"/.test(out));
  ok('A1 外链 script src 不动', out.includes('src="https://cdn.example.com/a.js"'));
  ok('A2 相对锚 → data-nf-local-link 解析为绝对路径', out.includes(`<a href="page.html" class="card" data-nf-local-link="${FIX_DIR}/page.html">`));
  ok('A2 绝对锚 → data-nf-local-link（href 与 target 属性保留原样）',
    out.includes(`<a href="${FIX_DIR}/target-a.html" target="_blank" rel="noopener" data-nf-local-link="${FIX_DIR}/target-a.html">`));
  ok('A2 锚不再产出代理 URL', !/<a[^>]*href="\/api\/nf-file/.test(out.replace(/<a href="\/api\/nf-file\?path=[^"]*ticket=OLD"/, '')));
  ok('A2 已代理锚解出 path 并标记', out.includes(`data-nf-local-link="${FIX_DIR}/proxied.html"`));
  ok('外链 / hash / mailto 锚不动',
    out.includes('<a href="https://example.com/x">ext</a>') && out.includes('<a href="#sec">hash</a>') && out.includes('<a href="mailto:a@b.c">mail</a>'));
  await ctx.close();
}

/* ════════════ R1 / R3 · 预览帧内本地链接（无 target 与 target=_blank 等价） ════════════ */
{
  const { ctx, page, errors, fileRequests, readFileCalls } = await boot(browser);
  const frame = await openHtmlDoc(page, F.probe, readFileSync(F.probe, 'utf8'), 'probe.html', true);
  ok('R1 预览帧已挂载（真身 probe）', !!frame);

  const dom = await frame.evaluate(() => ({
    onclick: document.getElementById('pollute-button')?.getAttribute('onclick'),
    dataSrc: document.getElementById('data-src')?.getAttribute('data-src'),
    prose: document.getElementById('prose')?.textContent,
    relHref: document.getElementById('rel-link')?.getAttribute('href'),
    relMark: document.getElementById('rel-link')?.getAttribute('data-nf-local-link'),
    blankHref: document.getElementById('abs-link-blank')?.getAttribute('href'),
    blankMark: document.getElementById('abs-link-blank')?.getAttribute('data-nf-local-link'),
    proxiedMark: document.getElementById('proxied-link')?.getAttribute('data-nf-local-link'),
  }));
  ok('R6 渲染后 onclick 未被改写', dom.onclick === "location.href='/zzz'", String(dom.onclick));
  ok('R6 渲染后 data-src 未被改写', dom.dataSrc === F.png, String(dom.dataSrc));
  ok('R6 渲染后正文未污染', String(dom.prose).includes("href='/prose-not-a-link'"));
  ok('A2 渲染后相对锚已标记（href 保留相对语义）', dom.relMark === F.a && dom.relHref === 'target-a.html', `${dom.relHref} | ${dom.relMark}`);
  ok('A2 渲染后 target=_blank 锚同样被标记', dom.blankMark === F.c && dom.blankHref === F.c, `${dom.blankHref} | ${dom.blankMark}`);

  /* ── R1：无 target 的普通 <a href> ── */
  const nf0 = fileRequests.length;
  // R1 的第一击用真 pointer click（命中面覆盖），其余用帧内 el.click()。
  {
    await page.evaluate(async (id) => { const m = await import('/js/canvas.js'); m.setActiveTab(id); }, PROBE_ID);
    await sleep(400);
    const el = await page.$(`.canvas-tab-pane[data-tab-id="${PROBE_ID}"] iframe[data-nf-canvas-html]`);
    const pf = el ? await el.contentFrame() : null;
    if (pf) await pf.click('#rel-link');
  }
  const landedA = await waitForTabContent(page, 'file:' + F.a, MARKERS.a);
  const probeA = await probePaneAlive(page, PROBE_ID, 'PROBE ROOT');
  ok('R1 无 target 本地链接 → 目标内容呈现', landedA, MARKERS.a);
  ok('R1 预览帧未被导航走（仍在渲染原文档）', probeA.alive && probeA.sameDoc, JSON.stringify(probeA));
  ok('R1 无 “Rendering was stopped”', !(await stoppedAnywhere(page)));
  const r1Proxy = fileRequests.slice(nf0);
  ok('R1 零文档类 /api/nf-file 请求（走 pop.readFile 腿）', r1Proxy.filter(isDocProxyRequest).length === 0,
    `raw +${r1Proxy.length}: ${r1Proxy.slice(0, 2).join(' | ')}`);
  ok('R1 目标经 pop.readFile 真语义读取', readFileCalls.includes(F.a), readFileCalls.slice(-3).join(','));

  /* ── R3：target="_blank" 形态 ── */
  const tabsBeforeBlank = await tabCount(page);
  const nf1 = fileRequests.length;
  await clickInProbe(page, PROBE_ID, '#abs-link-blank');
  const landedC = await waitForTabContent(page, 'file:' + F.c, MARKERS.c);
  const probeC = await probePaneAlive(page, PROBE_ID, 'PROBE ROOT');
  const paneAAfterBlank = await page.$(`.canvas-tab-pane[data-tab-id="file:${F.a}"]`);
  ok('R3 target=_blank 本地链接 → 目标内容呈现', landedC, MARKERS.c);
  ok('R3 target=_blank 亦不导航预览帧', probeC.alive && probeC.sameDoc, JSON.stringify(probeC));
  const r3Proxy = fileRequests.slice(nf1);
  ok('R3 target=_blank 零文档类 /api/nf-file 请求', r3Proxy.filter(isDocProxyRequest).length === 0,
    `raw +${r3Proxy.length}: ${r3Proxy.slice(0, 2).join(' | ')}`);
  // 等价性判据（不数「标签数 +1」）：把同一条链在两种形态下各走一次，读数必须同类。
  // 第三击 —— 无 target 形态点回 A。Canvas 的既有标签语义是「未 pin 的文件预览标签互相
  // 替换」（canvas.js:51 previewKeyOf → 所有文件标签共享 'file' 键；canvas.js:976-989），
  // 所以 blank 形态把 A 换成 C、这一击再把 C 换成 A，计数两步都不变 —— 与浏览器里「点链接
  // 导航掉当前页」同类。两种形态若有一方不走这条路（例如 target=_blank 被沙箱吞掉变成死
  // 点击），第三击的落点/标签账就对不上了。
  const tabsAfterBlank = await tabCount(page);
  await clickInProbe(page, PROBE_ID, '#rel-link');
  const landedA3 = await waitForTabContent(page, 'file:' + F.a, MARKERS.a);
  const tabsAfterRel = await tabCount(page);
  const paneCAfter = await page.$(`.canvas-tab-pane[data-tab-id="file:${F.c}"]`);
  const probeA3 = await probePaneAlive(page, PROBE_ID, 'PROBE ROOT');
  ok('R3 两形态行为一致（同落点、同标签账、预览帧都不动、无护栏提示）',
    landedA && landedC && landedA3 && probeC.alive && probeC.sameDoc && probeA3.alive && probeA3.sameDoc &&
    tabsAfterBlank === tabsBeforeBlank && tabsAfterRel === tabsAfterBlank &&
    !paneAAfterBlank && !paneCAfter && !(await stoppedAnywhere(page)),
    `tabs ${tabsBeforeBlank}→${tabsAfterBlank}(blank)→${tabsAfterRel}(rel) landed[A:${landedA} C:${landedC} A2:${landedA3}] ` +
    `pane[A after blank:${!!paneAAfterBlank} C after rel:${!!paneCAfter}] probeC=${JSON.stringify(probeC)} probeA3=${JSON.stringify(probeA3)}`);

  /* ── 已代理锚（/api/nf-file?path=…，无票）── */
  const nf2 = fileRequests.length;
  await clickInProbe(page, PROBE_ID, '#proxied-link');
  const landedA2 = await waitForTabContent(page, 'file:' + F.a, MARKERS.a);
  ok('A2 已代理锚 → 解出 path 后落到内容（而非 401 代理页）', landedA2, MARKERS.a);
  const a2Proxy = fileRequests.slice(nf2);
  ok('A2 已代理锚零文档类 /api/nf-file 请求', a2Proxy.filter(isDocProxyRequest).length === 0,
    `raw +${a2Proxy.length}: ${a2Proxy.slice(0, 2).join(' | ')}`);

  /* ── A5a：外链 → Canvas URL 标签 ── */
  const crossBefore = crossHits.n;
  await clickInProbe(page, PROBE_ID, '#ext-link');
  await sleep(900);
  const urlTab = await page.evaluate((cross) => {
    const el = document.querySelector(`.canvas-tab-pane iframe[src^="${cross}"]`);
    return { found: !!el, src: el ? el.getAttribute('src') : '' };
  }, CROSS_BASE);
  ok('A5a 外链 → Canvas URL 标签（未弹真实浏览器标签）', urlTab.found, urlTab.src);
  ok('A5a URL 标签真的加载了外链', crossHits.n > crossBefore, `crossHits ${crossBefore} → ${crossHits.n}`);

  /* ── hash 锚：既有 in-frame 滚动语义不受影响 ── */
  const tabsBeforeHash = await tabCount(page);
  const probeFrame = await clickInProbe(page, PROBE_ID, '#hash-link');
  await sleep(900);
  const hashState = await probeFrame.evaluate(() => ({
    url: location.href,
    y: Math.max(window.scrollY || 0, document.documentElement.scrollTop || 0, document.body ? document.body.scrollTop : 0),
  }));
  ok('hash 锚：不导航、不开标签（滚动量读数见明细）',
    hashState.url === 'about:srcdoc' && (await tabCount(page)) === tabsBeforeHash,
    `url=${hashState.url} scrollTop=${hashState.y}`);

  ok('R0 主文档无 JS 运行时错误', errors.length === 0, errors.slice(0, 3).join(' | '));
  if (SHOTS_DIR) { mkdirSync(SHOTS_DIR, { recursive: true }); await page.screenshot({ path: join(SHOTS_DIR, 'r1-r3-local-link.png') }); }
  await ctx.close();
}

/* ════════════ R4 · 真护栏不回退（app 根绝对 URL 仍提示递归） ════════════ */
{
  const { ctx, page } = await boot(browser);
  await openHtmlDoc(page, F.probe, readFileSync(F.probe, 'utf8'), 'probe.html', true);
  await clickInProbe(page, PROBE_ID, '#go-app-root');
  await sleep(1800);
  const st = await page.evaluate((id) => {
    const pane = document.querySelector(`.canvas-tab-pane[data-tab-id="${id}"]`);
    return {
      stopped: (document.body.textContent || '').includes('Rendering was stopped'),
      hasIframe: !!pane?.querySelector('iframe[data-nf-canvas-html]'),
      zoomBar: !!pane?.querySelector('.canvas-zoom-bar'),
    };
  }, PROBE_ID);
  ok('R4 app 根 URL 导航仍停渲染（递归提示）', st.stopped);
  ok('R4 停渲染时 pane 被替换（iframe 消失）', !st.hasIframe);
  ok('R4 停渲染时缩放引擎回收', !st.zoomBar);
  await ctx.close();
}

/* ════════════ 同批必修 · 代理 URL 导航：不再销毁 pane + 可见返回条 + 引擎回收 ════════════ */
{
  const { ctx, page } = await boot(browser);
  await openHtmlDoc(page, F.probe, readFileSync(F.probe, 'utf8'), 'probe.html', true);
  await clickInProbe(page, PROBE_ID, '#go-proxy');
  await sleep(1000);
  const st = await page.evaluate((id) => {
    const pane = document.querySelector(`.canvas-tab-pane[data-tab-id="${id}"]`);
    return {
      bar: !!pane?.querySelector('[data-nf-frame-navigated]'),
      hasIframe: !!pane?.querySelector('iframe[data-nf-canvas-html]'),
      zoomBar: !!pane?.querySelector('.canvas-zoom-bar'),
      stopped: (document.body.textContent || '').includes('Rendering was stopped'),
    };
  }, PROBE_ID);
  ok('修② 代理 URL 导航不销毁 pane', st.hasIframe && !st.stopped, JSON.stringify(st));
  ok('修② 代理 URL 导航显示顶部返回条', st.bar);
  ok('修② 代理 URL 导航回收缩放引擎', !st.zoomBar);
  const backClicked = await clickSel(page, `.canvas-tab-pane[data-tab-id="${PROBE_ID}"] .nf-frame-nav-back`);
  ok('返回条按钮可点', backClicked);
  const restored = await waitForTabContent(page, PROBE_ID, 'PROBE ROOT');
  const after = await page.evaluate((id) => {
    const pane = document.querySelector(`.canvas-tab-pane[data-tab-id="${id}"]`);
    return { bar: !!pane?.querySelector('[data-nf-frame-navigated]'), zoomBar: !!pane?.querySelector('.canvas-zoom-bar') };
  }, PROBE_ID);
  ok('修② “Back to document” 回到原文档（重渲染可用）', restored);
  ok('修② 返回后返回条消失、缩放引擎重建', !after.bar && after.zoomBar, JSON.stringify(after));
  if (SHOTS_DIR) { await page.screenshot({ path: join(SHOTS_DIR, 'nav-bar-sameorigin.png') }); }
  await ctx.close();
}

/* ════════════ 同批必修 · 跨源导航：静默失明 → 可见化 + 引擎回收 ════════════ */
{
  const { ctx, page } = await boot(browser);
  await openHtmlDoc(page, F.probe, readFileSync(F.probe, 'utf8'), 'probe.html', true);
  const crossBefore = crossHits.n;
  await clickInProbe(page, PROBE_ID, '#go-cross');
  await sleep(1400);
  const st = await page.evaluate((id) => {
    const pane = document.querySelector(`.canvas-tab-pane[data-tab-id="${id}"]`);
    return {
      bar: !!pane?.querySelector('[data-nf-frame-navigated]'),
      hasIframe: !!pane?.querySelector('iframe[data-nf-canvas-html]'),
      zoomBar: !!pane?.querySelector('.canvas-zoom-bar'),
    };
  }, PROBE_ID);
  ok('修③ 跨源导航可见化（顶部返回条，不再静默）', st.bar, JSON.stringify(st));
  ok('修③ 跨源导航 pane / frame 保留', st.hasIframe);
  ok('修③ 跨源导航回收缩放引擎', !st.zoomBar);
  ok('修③ 跨源页真的加载（浏览器语义不变）', crossHits.n > crossBefore, `crossHits ${crossBefore} → ${crossHits.n}`);
  const backClicked = await clickSel(page, `.canvas-tab-pane[data-tab-id="${PROBE_ID}"] .nf-frame-nav-back`);
  ok('返回条按钮可点', backClicked);
  ok('修③ Back 回到原文档', await waitForTabContent(page, PROBE_ID, 'PROBE ROOT'));
  if (SHOTS_DIR) { await page.screenshot({ path: join(SHOTS_DIR, 'nav-bar-crossorigin.png') }); }
  await ctx.close();
}

/* ════════════ R3 · 聊天卡面：点击不得静默无反应 ════════════ */
{
  const { ctx, page, fileRequests, readFileCalls } = await boot(browser);
  const cardHtml = `<div>card body</div>
<a id="card-abs" href="${F.c}" target="_blank" rel="noopener">abs link</a>
<a id="card-rel" href="relative.html">rel link</a>`;
  await page.evaluate(async (html) => {
    const host = document.createElement('div');
    host.id = 'nf-card-probe';
    host.style.cssText = 'position:fixed;top:80px;left:80px;width:420px;z-index:9999;background:#fff';
    document.body.appendChild(host);
    const mod = await import('/js/cardRegistry.js');
    window.__cardProbeRendered = mod.renderWithRegistry(host, { html, title: 'probe card' });
  }, cardHtml);
  ok('R3 卡面渲染器可用（renderWithRegistry 直渲）', await page.evaluate(() => window.__cardProbeRendered === true));
  await page.waitForSelector('#nf-card-probe iframe.html-card-iframe[srcdoc]', { timeout: 12000 });
  await sleep(700);
  const cardEl = await page.$('#nf-card-probe iframe.html-card-iframe');
  const card = cardEl ? await cardEl.contentFrame() : null;
  ok('R3 卡面帧存在', !!card);

  const tabBefore = await tabCount(page);
  const nf0 = fileRequests.length;
  await card.click('#card-abs');
  const landed = await waitForTabContent(page, 'file:' + F.c, MARKERS.c);
  ok('R3 卡面本地链接点击有反应（开成 Canvas 标签）', landed, MARKERS.c);
  ok('R3 卡面链接零 /api/nf-file 请求', fileRequests.length === nf0);
  ok('R3 卡面链接经 pop.readFile 读取', readFileCalls.includes(F.c), readFileCalls.slice(-3).join(','));
  ok('R3 卡面打开的是 Canvas 标签（标签数 +1）', (await tabCount(page)) >= tabBefore + 1);
  const cardUrl = await card.evaluate(() => location.href).catch(() => 'gone');
  ok('R3 卡面帧未被导航走', cardUrl === 'about:srcdoc', String(cardUrl));

  await card.click('#card-rel');
  await sleep(500);
  const note = await card.evaluate(() => {
    const n = document.querySelector('[data-nf-link-unsolved-note]');
    return { present: !!n, text: n ? n.textContent : '', stillSrcdoc: location.href === 'about:srcdoc' };
  }).catch(() => ({ present: false, text: '', stillSrcdoc: false }));
  ok('R3 卡面不可解析的相对链接给出可见提示（非静默）', note.present, note.text);
  ok('R3 卡面不可解析链接未把卡框导航走', note.stillSrcdoc);
  if (SHOTS_DIR) { await page.screenshot({ path: join(SHOTS_DIR, 'card-face.png') }); }
  await ctx.close();
}

/* ════════════ R2 · 真交付物汇总页 4 条链接（静态档：真身内容 + 磁盘真身目标） ════════════ */
const REAL_MARKERS = ['好友消息改造批', '消息面视觉规格预览', '好友面板控件面', '样式同源与附件双入口'];
{
  if (!realPageReadable()) {
    skipped('R2 真样本 4 条链接可达', `真交付物不可读：${REAL_PAGE}`);
  } else {
    const realSrc = readFileSync(REAL_PAGE, 'utf8');
    const links = [...realSrc.matchAll(/<a[^>]*href="([^"]+)"/g)].map((m) => m[1]);
    const { ctx, page, fileRequests, readFileCalls } = await boot(browser);
    await openHtmlDoc(page, REAL_PAGE, realSrc, 'index.html', true);
    ok('R2 真样本页链接数 = 4', links.length === 4, `links=${links.length}`);
    let landedCount = 0;
    for (let i = 0; i < links.length; i++) {
      const target = links[i];
      const nfBefore = fileRequests.length;
      await clickInProbe(page, 'file:' + REAL_PAGE, `a[href="${target}"]`);
      const hit = await waitForTabContent(page, 'file:' + target, REAL_MARKERS[i] || '', 15000);
      if (hit) landedCount++;
      results.push(`      ${hit ? 'HIT ' : 'MISS'} ${target.split('/').pop()}  (nf-file req +${fileRequests.length - nfBefore})`);
    }
    ok('R2 真样本 4 条链接全部落到内容', landedCount === 4, `${landedCount}/4`);
    const docReqs = fileRequests.filter(isDocProxyRequest);
    ok('R2 真样本点击零文档类 /api/nf-file 请求', docReqs.length === 0,
      `raw ${fileRequests.length}（其中文档类 ${docReqs.length}）: ${fileRequests.slice(0, 2).join(' | ')}`);
    ok('R2 真样本目标经 pop.readFile 读取', links.every((l) => readFileCalls.includes(l)), readFileCalls.slice(-4).join(','));
    await ctx.close();
  }
}

/* ════════════ LIVE 段 · 真隔离实例：R5 安全负控矩阵 + R2 真后端腿 ════════════ */
if (!LIVE_BASE) {
  skipped('R5 安全负控矩阵（真实例）', 'no NEBFLOW_LIVE_BASE（门禁档；真实例档读数见结果正文）');
  skipped('R2 真实例腿（真 pop.readFile）', 'no NEBFLOW_LIVE_BASE（门禁档）');
} else {
  const { ctx, page } = await boot(browser, true);
  // 真实例夹具**自备**（只写 LIVE_HOME 与 os.tmpdir() 两个操作者指定的临时区；绝不碰 ~/.nebflow）。
  // 判据链照 WebSocketRoutes.nfFileVerdict 实现读：not-found(不存在) → credential-path
  // （先 dataRoot 白名单头，再 `<workspace>/.nebflow`，再 home 凭据条目/段/文件名）→ file-type(扩展名)。
  // 因此每条期望都要求夹具先落地成**真文件**，否则先撞 not-found —— 上一版期望就是踩了这个。
  const P = {
    docsMd: join(LIVE_HOME, 'docs/Nebflow/x.md'),
    docsHtml: join(LIVE_HOME, 'docs/Nebflow/assets/x.html'),
    logs: join(LIVE_HOME, 'logs/x.json'),
    results: join(LIVE_HOME, 'results/y.json'),
    tmp: join(LIVE_HOME, 'tmp/z.png'),
    auth: join(LIVE_HOME, 'auth.json'),
    ok: join(LIVE_HOME, 'projects/p1/ok.png'),
    other: join(LIVE_HOME, 'projects/p1/other.png'),
    board: join(LIVE_HOME, '.nebflow/task-board.json'),
    absHtml: join(tmpdir(), 'canvasprev-live-assets/abs.html'),
    absPng: join(tmpdir(), 'canvasprev-live-assets/abs.png'),
    sshKey: join(homedir(), '.ssh/id_ed25519'),
    knownHosts: join(homedir(), '.ssh/known_hosts'),
  };
  const PNG_1PX = Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');
  const hasSsh = existsSync(join(homedir(), '.ssh'));
  {
    mkdirSync(join(LIVE_HOME, 'projects/p1'), { recursive: true });
    mkdirSync(dirname(P.absHtml), { recursive: true });
    writeFileSync(P.docsMd, '# live fixture\n');
    writeFileSync(P.docsHtml, '<!DOCTYPE html><html><body>live</body></html>\n');
    writeFileSync(P.logs, '{"live":1}\n');
    writeFileSync(P.results, '{"live":2}\n');
    writeFileSync(P.tmp, PNG_1PX);
    writeFileSync(P.ok, PNG_1PX);
    writeFileSync(P.other, PNG_1PX);
    writeFileSync(P.board, '{"tasks":[]}\n');
    writeFileSync(P.absHtml, '<!DOCTYPE html><html><body>abs</body></html>\n');
    writeFileSync(P.absPng, PNG_1PX);
    // auth.json 由真实例自己写（token 的来源）+ 它是「home 根下的文件 ⇒ 非白名单头 ⇒ credential-path」的负控对象，不覆盖。
    if (!existsSync(P.auth)) results.push('      (注意：真实例 home 尚无 auth.json —— 本档靠 cookie/Bearer 之外的 localStorage token 兜底)');
  }
  const mint = await page.evaluate(async (list) => {
    const r = await fetch('/api/nf-ticket', {
      method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sessionId: '', paths: list }),
    });
    return { status: r.status, body: await r.json().catch(() => null) };
  }, Object.values(P));
  const reasons = {};
  for (const r of (mint.body && mint.body.rejected) || []) reasons[r.path] = r.reason;
  const tickets = (mint.body && mint.body.tickets) || {};
  results.push(`      mint status=${mint.status} allowed=${Object.keys(tickets).length} rejected=${(mint.body && mint.body.rejected || []).length}`);
  const expect = [
    ['docs/** .md', P.docsMd, 'credential-path'], ['docs/** .html', P.docsHtml, 'credential-path'],
    ['logs/', P.logs, 'credential-path'], ['results/', P.results, 'credential-path'],
    ['tmp/', P.tmp, 'credential-path'], ['auth.json', P.auth, 'credential-path'],
    ['.nebflow/（项目凭据树）', P.board, 'credential-path'],
    ['dataRoot 外 .html', P.absHtml, 'file-type'],
  ];
  if (hasSsh) {
    expect.push(['~/.ssh/id_ed25519', P.sshKey, 'credential-path'], ['~/.ssh/known_hosts', P.knownHosts, 'credential-path']);
  } else {
    skipped('R5-a 铸票拒绝 ~/.ssh/** → credential-path', '本机无 ~/.ssh');
  }
  for (const [label, p, want] of expect) {
    ok(`R5-a 铸票拒绝 ${label} → ${want}`, reasons[p] === want, `got=${reasons[p]}`);
  }
  ok('R5-a projects/** 白名单头仍放行', !!tickets[P.ok], `allowed=${Object.keys(tickets).join(',')}`);
  ok('R5-a dataRoot 外允许扩展名（.png）仍放行 —— 与上面 .html 的 file-type 成对',
    !!tickets[P.absPng], `allowed=${Object.keys(tickets).join(',')}`);

  const readCase = (url) => page.evaluate(async (u) => {
    const r = await fetch(u, { credentials: 'same-origin' });
    return { status: r.status, reason: r.headers.get('X-Nf-Reason') || '' };
  }, url);
  const enc = encodeURIComponent;
  ok('R5-b 无票 → 401', (await readCase('/api/nf-file?path=' + enc(P.ok))).status === 401);
  const tk = tickets[P.ok];
  if (tk) {
    const okRead = await readCase('/api/nf-file?path=' + enc(P.ok) + '&ticket=' + enc(tk.t));
    ok('R5-b 白名单路径 + 有效票 → 200', okRead.status === 200, JSON.stringify(okRead));
    // 异路径换票：必须是**同扩展名**的另一条路径。换 /etc/hosts 会先撞扩展名判据（实测 400
    // file-type，因为 file-type 排在票据路径比对之前），那样读到的不是 path-mismatch。
    const mismatch = await readCase('/api/nf-file?path=' + enc(P.other) + '&ticket=' + enc(tk.t));
    ok('R5-b 异路径票（同扩展名）→ 403 path-mismatch', mismatch.status === 403 && /mismatch/.test(mismatch.reason), JSON.stringify(mismatch));
    const noExt = await readCase('/api/nf-file?path=/etc/hosts&ticket=' + enc(tk.t));
    ok('R5-b 无允许扩展名 + 有效票 → 400 file-type（扩展名判据在票据比对之前）',
      noExt.status === 400 && /file-type/.test(noExt.reason), JSON.stringify(noExt));
  } else {
    ok('R5-b 白名单路径取得有效票（前置）', false, 'no ticket issued for whitelisted path');
  }
  const docsRead = await readCase('/api/nf-file?path=' + enc(P.docsMd) + '&ticket=ANY');
  ok('R5-b docs/** 任意票 → 403 credential-path', docsRead.status === 403 && /credential-path/.test(docsRead.reason), JSON.stringify(docsRead));
  const htmlRead = await readCase('/api/nf-file?path=' + enc(F.a) + '&ticket=ANY');
  ok('R5-b .html 任意票 → 400 file-type', htmlRead.status === 400 && /file-type/.test(htmlRead.reason), JSON.stringify(htmlRead));
  const mdRead = await readCase('/api/nf-file?path=' + enc(F.md) + '&ticket=ANY');
  ok('R5-b .md 任意票 → 400 file-type', mdRead.status === 400 && /file-type/.test(mdRead.reason), JSON.stringify(mdRead));
  await ctx.close();

  if (!realPageReadable()) {
    skipped('R2 真实例腿（真 pop.readFile）', '真交付物不可读');
  } else {
    const realSrc = readFileSync(REAL_PAGE, 'utf8');
    const links = [...realSrc.matchAll(/<a[^>]*href="([^"]+)"/g)].map((m) => m[1]);
    const { ctx: c2, page: p2, fileRequests: fr2 } = await boot(browser, true);
    await openHtmlDoc(p2, REAL_PAGE, realSrc, 'index.html', true);
    let landed = 0;
    for (let i = 0; i < links.length; i++) {
      await clickInProbe(p2, 'file:' + REAL_PAGE, `a[href="${links[i]}"]`);
      const hit = await waitForTabContent(p2, 'file:' + links[i], REAL_MARKERS[i] || '', 15000);
      if (hit) landed++;
      results.push(`      [live] ${hit ? 'HIT ' : 'MISS'} ${links[i].split('/').pop()}`);
    }
    ok('R2(live) 真样本 4 条链接全部落到内容（真后端 pop.readFile）', landed === 4, `${landed}/4`);
    const liveDocReqs = fr2.filter(isDocProxyRequest);
    ok('R2(live) 点击零文档类 /api/nf-file 请求', liveDocReqs.length === 0,
      `raw ${fr2.length}（其中文档类 ${liveDocReqs.length}）: ${fr2.slice(0, 2).join(' | ')}`);
    await c2.close();
  }
}

await browser.close();
webServer.close();
crossServer.close();
console.log(results.join('\n'));
console.log(`\n═══ canvas-preview-nav-parity: ${pass} PASS / ${fail} FAIL / ${skip} SKIP ═══`);
if (SHOTS_DIR) console.log(`shots: ${SHOTS_DIR}`);
process.exit(fail ? 1 : 0);

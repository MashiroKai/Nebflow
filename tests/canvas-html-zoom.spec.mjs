// canvas-html-zoom.spec.mjs — Canvas HTML 查看器独立缩放验收
// （2026-09-04「海报 poster-v8.html 无法缩放」修复：定性=HTML viewer 从未有
//   缩放（enableViewerZoom 仅接入 image/PDF），本 spec 验收新实现。）
//
// 被测真实链路（无 mock 替身层跳过）：
//   workspace-open-item → canvas.js openWorkspaceItem → viewers/html.js viewHtml
//   → srcdoc iframe（sandbox 契约不变）+ viewers/zoom.js 玻璃工具条
//   + iframe 内 zoomBridgeScript：ctrl/cmd+wheel 与 ⌘/ctrl +/−/0 经
//   postMessage(_nfZoomWheel/_nfZoomKey) 跨框转发（跨框事件不冒泡），
//   父侧 sender 校验（e.source === iframe.contentWindow）后按当前
//   transform 映射锚点，translate+scale（origin 0 0）CAD 式缩放。
//
// 断言面：
//   Z1 打开态：工具条 + 100% + 无 transform；sandbox 契约快照；
//   Z2 工具钮放大：computed matrix scale=1.25、比例徽标 125%；
//   Z3 工具钮复位（Fit to view）→ 恒等矩阵 + 100%；
//   Z4 ctrl+wheel 锚点不变（CAD 式）：#zoom-marker boundingBox 中心
//      缩放前后 ≤±1px；比例徽标与实际 scale 同步；
//   Z5 纯滚轮不缩放：无修饰键 wheel → iframe 内部 scrollTop 增长
//      （原生滚动未被拦截），scale/徽标纹丝不动；
//   Z6 上钳制 3.0（300%）；
//   Z7 下钳制 0.3（30%）；
//   Z8 复位回 100%（恒等矩阵）；
//   Z9 iframe 内键盘桥：iframe 聚焦后 Control+= 放大 / Control+0 复位；
//   Z10 亮暗双主题截图（缩放中态 + 复位态）；
//   R0 主文档无 JS 运行时错误。
//
// 运行（仓库根 node_modules 提供 playwright）：
//   node tests/canvas-html-zoom.spec.mjs
// 可选 env：
//   PORT=8123                       静态服务端口（≥8100，非 8080）
//   CANVAS_HTML_ZOOM_SHOTS_DIR=<dir> 截图输出目录（默认 /tmp/nb-canvas-html-zoom）

import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const FIXTURE = resolve(HERE, 'fixtures/canvas-html-zoom/poster.html');
const PORT = Number(process.env.PORT || 8123);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS_DIR = process.env.CANVAS_HTML_ZOOM_SHOTS_DIR || '/tmp/nb-canvas-html-zoom';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── 静态服务（serve 真实 web/ 前端源码树；非 8080 端口）──
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.woff2': 'font/woff2',
  '.woff': 'font/woff',
};
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(404); res.end('not found');
  }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

// ── /api/nf-file 模拟（与后端白名单修复后行为一致）──
function nfFileRoute() {
  const ALLOWED = ['png', 'jpg', 'jpeg', 'gif', 'svg', 'webp', 'ico', 'mp4', 'webm', 'mp3', 'wav', 'woff', 'woff2', 'ttf', 'otf', 'pdf', 'docx', 'xlsx', 'pptx', 'epub', 'js', 'mjs', 'css', 'json'];
  return async (route) => {
    const url = new URL(route.request().url());
    const p = url.searchParams.get('path') || '';
    const ext = (p.split('.').pop() || '').toLowerCase();
    if (!ALLOWED.includes(ext)) return route.fulfill({ status: 400, contentType: 'text/plain', body: 'File type not allowed' });
    try {
      return route.fulfill({ status: 200, contentType: 'application/octet-stream', body: readFileSync(p) });
    } catch {
      return route.fulfill({ status: 404, contentType: 'text/plain', body: 'not found' });
    }
  };
}

async function bootPage(browser, { colorScheme = 'light' } = {}) {
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 } });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
  });
  const page = await ctx.newPage();
  await page.emulateMedia({ colorScheme });
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  await page.route(/\/api\/nf-file\?/, nfFileRoute());
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
  await sleep(400);
  const content = readFileSync(FIXTURE, 'utf8');
  const absPath = FIXTURE;
  await page.evaluate(({ content, absPath }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType: 'html', title: 'poster.html', content, absPath, pinned: false },
    }));
  }, { content, absPath });
  await page.waitForSelector('iframe[data-nf-canvas-html]', { timeout: 10000 });
  let frame = null;
  for (let i = 0; i < 20 && !frame; i++) {
    await sleep(300);
    frame = page.frames().find((f) => f.url() === 'about:srcdoc') || null;
  }
  await sleep(300);
  return { ctx, page, frame, errors };
}

// computed transform → { scale, tx, ty }（'none' 视为恒等）
async function getZoom(page) {
  return page.evaluate(() => {
    const iframe = document.querySelector('iframe[data-nf-canvas-html]');
    const tr = getComputedStyle(iframe).transform;
    let scale = 1, tx = 0, ty = 0;
    if (tr && tr !== 'none') {
      const m = tr.match(/matrix\(([^)]+)\)/);
      if (m) {
        const v = m[1].split(',').map(Number);
        scale = v[0]; tx = v[4]; ty = v[5];
      }
    }
    const pct = document.querySelector('.canvas-zoom-pct')?.textContent?.trim() || '';
    return { scale, tx, ty, pct, raw: tr };
  });
}

async function markerCenter(frame) {
  const bb = await frame.locator('#zoom-marker').boundingBox();
  return { x: bb.x + bb.width / 2, y: bb.y + bb.height / 2, bb };
}

let pass = 0, fail = 0;
const results = [];
function ok(name, cond, detail = '') {
  if (cond) { pass++; results.push(`PASS  ${name}${detail ? '  — ' + detail : ''}`); }
  else { fail++; results.push(`FAIL  ${name}${detail ? '  — ' + detail : ''}`); }
}
const approx = (a, b, eps) => Math.abs(a - b) <= eps;

const browser = await chromium.launch();
mkdirSync(SHOTS_DIR, { recursive: true });

/* ════════ 主场景（浅色）════════ */
{
  const { ctx, page, frame, errors } = await bootPage(browser);

  /* Z1 打开态：工具条 + 100% + 无 transform + sandbox 契约快照 */
  const z1 = await getZoom(page);
  const barCount = await page.locator('.canvas-zoom-bar').count();
  const sandbox = await page.evaluate(() => document.querySelector('iframe[data-nf-canvas-html]')?.getAttribute('sandbox'));
  ok('Z1 缩放工具条存在（glass bar + 徽标）', barCount === 1, `bars=${barCount}`);
  ok('Z1 初始 100% 且无 transform', z1.pct === '100%' && (z1.raw === 'none' || approx(z1.scale, 1, 1e-9)), `pct=${z1.pct} raw=${z1.raw}`);
  ok('Z1 sandbox 契约快照不变', sandbox === 'allow-scripts allow-same-origin allow-forms allow-popups', sandbox);

  /* Z2 工具钮放大 → scale 1.25 + 徽标 125%（computed transform 变化）*/
  await page.locator('.canvas-zoom-btn[title="Zoom in"]').click();
  await sleep(150);
  const z2 = await getZoom(page);
  ok('Z2 工具钮放大 → computed scale=1.25', approx(z2.scale, 1.25, 0.002), `scale=${z2.scale}`);
  ok('Z2 比例徽标同步 125%', z2.pct === '125%', `pct=${z2.pct}`);

  /* Z3 工具钮复位 → 恒等 + 100% */
  await page.locator('.canvas-zoom-btn[title="Fit to view"]').click();
  await sleep(150);
  const z3 = await getZoom(page);
  ok('Z3 工具钮复位 → 恒等矩阵 + 100%', approx(z3.scale, 1, 1e-9) && approx(z3.tx, 0, 1e-9) && approx(z3.ty, 0, 1e-9) && z3.pct === '100%', `scale=${z3.scale} tx=${z3.tx} ty=${z3.ty} pct=${z3.pct}`);

  /* Z4 ctrl+wheel CAD 式锚点：光标下内容点屏幕位置不变（±1px 口径）。
     测量仪器：Playwright 的 frame-internal boundingBox 不合成父侧 iframe
     transform（实测偏差 = translate 量级），故用真实浏览器几何合成——
     父侧 iframe.getBoundingClientRect()（含 translate/scale）⊕ 框内
     getBoundingClientRect()（未缩放布局坐标）：visual = rect.left + s*inner。
     另加 elementFromPoint 命中不变性（跨 transform 的真实 hit-test）。*/
  const geom = () => page.evaluate(() => {
    const iframe = document.querySelector('iframe[data-nf-canvas-html]');
    const r = iframe.getBoundingClientRect();
    const ir = iframe.contentDocument.querySelector('#zoom-marker').getBoundingClientRect();
    const tr = getComputedStyle(iframe).transform;
    const m = tr === 'none' ? [1, 0, 0, 1, 0, 0] : tr.match(/matrix\(([^)]+)\)/)[1].split(',').map(Number);
    return { s: m[0], tx: m[4], ty: m[5], rleft: r.left, rtop: r.top,
             vx: r.left + m[0] * (ir.left + ir.width / 2), vy: r.top + m[0] * (ir.top + ir.height / 2) };
  });
  const g0 = await geom();
  const cursor = { x: g0.vx, y: g0.vy };               // s=1：合成坐标 = 屏幕真值
  await page.mouse.move(cursor.x, cursor.y);
  await page.keyboard.down('Control');
  await page.mouse.wheel(0, -240);
  await page.keyboard.up('Control');
  await sleep(400);  // postMessage 跨框往返 + 应用 transform
  const g1 = await geom();
  const dx = Math.abs(g1.vx - g0.vx), dy = Math.abs(g1.vy - g0.vy);
  const mx = cursor.x - g0.rleft, my = cursor.y - g0.rtop;   // 光标的 iframe 内容坐标
  ok('Z4 ctrl+wheel → 缩放生效（scale≈1.433）', approx(g1.s, 1.4329, 0.01), `scale=${g1.s}`);
  ok('Z4 锚点数学：translate 精确锁定光标（tx=my*(1−s) ±0.5px）', approx(g1.tx, mx * (1 - g1.s), 0.5) && approx(g1.ty, my * (1 - g1.s), 0.5), `tx=${g1.tx.toFixed(3)} 期望=${(mx * (1 - g1.s)).toFixed(3)} ty=${g1.ty.toFixed(3)} 期望=${(my * (1 - g1.s)).toFixed(3)}`);
  ok('Z4 鼠标锚点不变（|Δx|≤1px 且 |Δy|≤1px，几何合成）', dx <= 1 && dy <= 1, `Δx=${dx.toFixed(3)} Δy=${dy.toFixed(3)}`);
  const hitAfter = await frame.evaluate(([cx, cy]) => document.elementFromPoint(cx, cy)?.id || '', [(cursor.x - g1.rleft) / g1.s, (cursor.y - g1.rtop) / g1.s]);
  ok('Z4 elementFromPoint 命中不变（光标下仍是 #zoom-marker）', hitAfter === 'zoom-marker', `hit=${hitAfter}`);
  const z4 = await getZoom(page);
  ok('Z4 比例徽标与实际 scale 同步', z4.pct === String(Math.round(z4.scale * 100)) + '%', `pct=${z4.pct} scale=${z4.scale}`);
  await page.screenshot({ path: join(SHOTS_DIR, '20260904_canvas-html-zoom-light-zoomed.png') });

  /* Z5 纯滚轮：iframe 内部原生滚动，绝不缩放（光标取 pane 中心——
     s≥1 时 iframe 可视区必然覆盖 pane，位置不依赖任何 bb 合成）*/
  const paneC = await page.evaluate(() => {
    const r = document.querySelector('.canvas-tab-pane.active').getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  });
  const preZ5 = await getZoom(page);
  await page.mouse.move(paneC.x, paneC.y);
  await page.mouse.wheel(0, 240);
  await sleep(300);
  const scrollTop = await frame.evaluate(() => document.documentElement.scrollTop || document.body.scrollTop);
  const postZ5 = await getZoom(page);
  ok('Z5 纯滚轮 → iframe 内部滚动（scrollTop>0，原生滚动未被拦截）', scrollTop > 0, `scrollTop=${scrollTop}`);
  ok('Z5 纯滚轮 → scale 与徽标纹丝不动', postZ5.raw === preZ5.raw && postZ5.pct === preZ5.pct, `raw ${preZ5.raw} → ${postZ5.raw} pct=${postZ5.pct}`);

  /* Z6 上钳制 3.0 */
  for (let i = 0; i < 10; i++) await page.locator('.canvas-zoom-btn[title="Zoom in"]').click();
  await sleep(150);
  const z6 = await getZoom(page);
  await page.locator('.canvas-zoom-btn[title="Zoom in"]').click();
  await sleep(150);
  const z6b = await getZoom(page);
  ok('Z6 上钳制：scale ≤ 3.0 且徽标 300%', z6.scale <= 3.0005 && z6.pct === '300%', `scale=${z6.scale} pct=${z6.pct}`);
  ok('Z6 钳制后继续放大仍 3.0', approx(z6b.scale, 3, 1e-9) && z6b.pct === '300%', `scale=${z6b.scale}`);

  /* Z7 下钳制 0.3 */
  for (let i = 0; i < 20; i++) await page.locator('.canvas-zoom-btn[title="Zoom out"]').click();
  await sleep(150);
  const z7 = await getZoom(page);
  ok('Z7 下钳制：scale ≥ 0.3 且徽标 30%', z7.scale >= 0.29995 && z7.pct === '30%', `scale=${z7.scale} pct=${z7.pct}`);

  /* Z8 复位回 100% */
  await page.locator('.canvas-zoom-btn[title="Fit to view"]').click();
  await sleep(150);
  const z8 = await getZoom(page);
  ok('Z8 复位回 100%（恒等矩阵）', approx(z8.scale, 1, 1e-9) && approx(z8.tx, 0, 1e-9) && approx(z8.ty, 0, 1e-9) && z8.pct === '100%', `scale=${z8.scale} pct=${z8.pct}`);
  await page.screenshot({ path: join(SHOTS_DIR, '20260904_canvas-html-zoom-light-reset.png') });

  /* Z9 iframe 内键盘桥：聚焦 iframe 后 Control+= 放大 / Control+0 复位 */
  await page.locator('.canvas-zoom-btn[title="Zoom in"]').click();
  await sleep(150);
  await frame.click('#zoom-marker');   // 焦点移入 iframe（真实用户点击路径）
  await page.keyboard.press('Control+=');
  await sleep(400);
  const z9a = await getZoom(page);
  await page.keyboard.press('Control+0');
  await sleep(400);
  const z9b = await getZoom(page);
  ok('Z9 键盘桥 Control+= → 1.25×1.25=1.5625', approx(z9a.scale, 1.5625, 0.01), `scale=${z9a.scale}`);
  ok('Z9 键盘桥 Control+0 → 复位 100%', approx(z9b.scale, 1, 1e-9) && z9b.pct === '100%', `scale=${z9b.scale} pct=${z9b.pct}`);

  /* R0 主文档无 JS 运行时错误 */
  ok('R0 主文档无 JS 运行时错误', errors.length === 0, errors.slice(0, 3).join(' | '));
  await ctx.close();
}

/* ════════ 暗色主题（缩放中态 + 复位态截图）════════ */
{
  const { ctx, page, frame, errors } = await bootPage(browser, { colorScheme: 'dark' });
  const darkVars = await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--color-bg').trim());
  const c0 = await markerCenter(frame);
  await page.mouse.move(c0.x, c0.y);
  await page.keyboard.down('Control');
  await page.mouse.wheel(0, -240);
  await page.keyboard.up('Control');
  await sleep(400);
  const zd = await getZoom(page);
  ok('Z10 暗色：ctrl+wheel 缩放生效 + 徽标同步', zd.scale > 1.2 && zd.pct === String(Math.round(zd.scale * 100)) + '%', `scale=${zd.scale} pct=${zd.pct} bg=${darkVars}`);
  await page.screenshot({ path: join(SHOTS_DIR, '20260904_canvas-html-zoom-dark-zoomed.png') });
  await page.locator('.canvas-zoom-btn[title="Fit to view"]').click();
  await sleep(150);
  const zr = await getZoom(page);
  ok('Z10 暗色：复位 100%', approx(zr.scale, 1, 1e-9) && zr.pct === '100%', `scale=${zr.scale} pct=${zr.pct}`);
  await page.screenshot({ path: join(SHOTS_DIR, '20260904_canvas-html-zoom-dark-reset.png') });
  ok('R0 暗色主文档无 JS 运行时错误', errors.length === 0, errors.slice(0, 3).join(' | '));
  await ctx.close();
}

await browser.close();
server.close();
console.log(results.join('\n'));
console.log(`\n═══ canvas-html-zoom: ${pass} PASS / ${fail} FAIL ═══`);
process.exit(fail ? 1 : 0);

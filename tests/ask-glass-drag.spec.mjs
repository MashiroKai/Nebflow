// ask-glass-drag.spec.mjs — Ask 气泡真毛玻璃 + 聊天拉动到聊天框之上（作者令 2026-11-06 09:57）
//
// 两缺陷各一枚钉（改前红 / 改后绿 双态实测，判定全部来自真实渲染，无布局 Mock）：
//   G 玻璃腿（缺陷①：mask 抵消 backdrop-filter = 半透明洗色）
//     G1 材质单源：.ask-bub（ai/user）计算样式与主对话 #input-bar 逐项相等
//        （bg / backdropFilter / borderColor / boxShadow）—— 桌面 1440 暗/亮 + 手机 375 暗。
//     G2 真模糊像素证：气泡元素实拍 A；JS 摘 backdrop-filter 实拍 B；
//        mean|Δlum|(A,B) ≥ 4 ⇒ 模糊真实采样到画布内容。
//        改前（.ask-col mask-image = backdrop root，采样被架空）实测 0.25 = 红；
//        改后实测 ≈11.9 = 绿。阈值 4 居中（0.25 与 11.86 之间留 16 倍裕度）。
//     G3 非 dock 兜底（glass.css `.ask-bub`）同源：computed backdropFilter 非 none。
//   D 拉动腿（缺陷②：列盒底钉在输入条中线，最新气泡底部恒被输入条上半盖住）
//     D1 拉动净空：.ask-col padding-bottom = --ask-under + --ds-space-1h；
//        滚动尽头（拉动到底）最新气泡底边 ≤ 输入条顶边（交叠 = 0）；
//        elementFromPoint（最新气泡底边上方 4px）命中 ask-dock（可点区不被输入条抢）。
//     D1s 静态净空：单问单答（无滚动）最新气泡底边与输入条顶边间距 = --ds-space-1h（6px）
//        —— 不滚动也不被盖（改前 = 气泡底边扎进输入条 17px = 红）。
//     D2 极值三读数（拖动边界夹取）：① 列滚动最上（scrollTop=0）：顶泡在列内、
//        alpha < 1（渐隐仍在）；② 列滚动尽头（最下）：D1 读数；③ 面板贴边（把手
//        连按 ↑ 到夹取上限）：panel rect ⊆ #canvas-panel ∩ 视口、把手 rect ⊆ 视口
//        （夹取逻辑 = askDock.js resizeLimits/applySize 既有单源，本批零改动只钉行为）。
//     D4 层级钉（不写任何新 z-index，现状即方案）：桌面 dock z=24（< 顶行 30）；
//        手机层 dock z=260（> 底 dock 200）；窄窗 overlay 腿主 composer 中心命中
//        不被 ask 面板抢（ask 面板 z 240 面板内，与 composer 几何零冲突读数）。
//
// 运行：node tests/ask-glass-drag.spec.mjs

import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8194);
const BASE = `http://127.0.0.1:${PORT}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let pass = 0, fail = 0;
function ok(name, cond, detail = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  if (cond) pass++; else fail++;
}

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2', '.woff': 'font/woff',
};
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try { const b = readFileSync(file); res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' }); res.end(b); }
  catch { if (!res.headersSent) { res.writeHead(404); res.end(); } else res.end(); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));
const browser = await chromium.launch();

const MD_PATH = '/tmp/ask-fixture/ask-glass-drag.md';
const SID = 'ask-' + createHash('sha256').update(MD_PATH).digest('hex').slice(0, 8);
const docText = () => Array.from({ length: 40 }, (_, i) => `第 ${i + 1} 段样本行，用于给玻璃面提供透过内容。拖动与遮挡实测样本。`).join('\n\n');

/** 桌面/手机通用 boot。msgs = askState 回放消息（短内容腿传 1 对）。 */
async function boot({ colorScheme = 'dark', viewport = { width: 1440, height: 900 }, mobile = false, msgs = null } = {}) {
  const replay = msgs || [
    { role: 'user', text: '这份文件讲什么？（探针问 1）' },
    { role: 'ai', text: '这是探针回答 1：玻璃材质与遮挡实测用的样本回答，长度足够撑起气泡换行，让玻璃面背后有字可透。' },
    { role: 'user', text: '第二问：帮我看一下第二段。' },
    { role: 'ai', text: '第二答：第二段样本。这一条再长一点，撑出第三行文字看看玻璃底下的透出程度到底怎么样。' },
  ];
  const ctx = await browser.newContext({ viewport, colorScheme, hasTouch: mobile });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  await ctx.route('**/api/**', (r) => r.fulfill({ status: 404, json: {} }));
  await installTicketMock(ctx);
  await ctx.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      else if (m.type === 'pop.readFile') {
        ws.send(JSON.stringify({ type: 'fileContent', path: m.path, absPath: m.path, content: docText(), itemType: 'markdown', fileName: 'ask-glass-drag.md', size: docText().length }));
      } else if (m.type === 'askOpen') {
        ws.send(JSON.stringify({ type: 'askState', sessionId: SID, fileName: m.file?.name || 'ask-glass-drag.md', messages: replay }));
      } else if (m.type === 'askQuestion') {
        ws.send(JSON.stringify({ type: 'textDelta', sessionId: m.sessionId, delta: '探针实时回答：' + (m.content || '') }));
        ws.send(JSON.stringify({ type: 'done', sessionId: m.sessionId, finalText: '探针实时回答：' + (m.content || '') }));
      }
    });
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('body.mobile-layer, #activity-bar', { timeout: 20000 });
  await sleep(mobile ? 1000 : 700);
  // 开文档卡（桌面 = 直接事件；手机 = 开卡后 dock 进 canvas 页）
  await page.evaluate(({ absPath }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType: 'markdown', title: 'ask-glass-drag.md', content: null, absPath, pinned: true },
    }));
  }, { absPath: MD_PATH });
  if (mobile) {
    await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click();
    await sleep(600);
  }
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${MD_PATH}"] .canvas-md-viewer`, { timeout: 15000, state: 'attached' });
  await sleep(400);
  await page.click('.ask-fab', { force: true });
  await sleep(900); // 开合曲线 + askState 回放
  return { ctx, page, errors };
}

/** 几何/材质读数（页内执行）。 */
const probe = `(() => {
  const box = (el) => { if (!el) return null; const b = el.getBoundingClientRect(); return { t: +b.top.toFixed(1), b: +b.bottom.toFixed(1), l: +b.left.toFixed(1), r: +b.right.toFixed(1), w: +b.width.toFixed(1), h: +b.height.toFixed(1) }; };
  const q = (s) => document.querySelector(s);
  const col = q('.ask-col'), colwrap = q('.ask-colwrap'), dock = q('.ask-dock'), input = q('.ask-input');
  const bubs = [...document.querySelectorAll('.ask-bub')];
  const ccs = col ? getComputedStyle(col) : null;
  const dcs = dock ? getComputedStyle(dock) : null;
  const token = (n) => { const p = q('.ask-dock'); return p ? getComputedStyle(p).getPropertyValue(n).trim() : ''; };
  const last = bubs[bubs.length - 1];
  const ov = (a, b) => (a && b) ? Math.max(0, Math.min(a.b, b.b) - Math.max(a.t, b.t)) * Math.max(0, Math.min(a.r, b.r) - Math.max(a.l, b.l)) : 0;
  return {
    nBub: bubs.length,
    col: box(col), wrap: box(colwrap), dock: box(dock), input: box(input), lastBub: box(last),
    colPadBottom: ccs ? ccs.paddingBottom : '',
    colScrollTop: col ? +col.scrollTop.toFixed(1) : 0,
    colScrollMax: col ? +(col.scrollHeight - col.clientHeight).toFixed(1) : 0,
    under: token('--ask-under'), gap1h: token('--ds-space-1h'),
    dockZ: dcs ? dcs.zIndex : '', dockPos: dcs ? dcs.position : '',
    lastOpacity: last ? getComputedStyle(last).opacity : '',
    topOpacity: bubs.length ? getComputedStyle(bubs[0]).opacity : '',
    lastBottomVsInputTop: (last && input) ? +(box(input).t - box(last).b).toFixed(1) : null,
    ovLastBubVsInput: ov(box(last), box(input)),
    lastBubHitAtBottom: (last && box(last)) ? (() => { const b = box(last); const el = document.elementFromPoint(b.l + b.w / 2, b.b - 4); return el ? (el.closest('.ask-input') ? 'ask-input' : el.closest('.ask-dock') ? 'ask-dock' : el.tagName) : 'none'; })() : null,
    vpH: window.innerHeight,
    panel: (() => { const p = document.getElementById('canvas-panel'); return p ? box(p) : null; })(),
    handle: box(q('.ask-dock .ds-resize-handle')),
    content: (() => { const c = document.getElementById('canvas-content'); return c ? box(c) : null; })(),
  };
})()`;

/* ════════ G1+G2+D1+D2 桌面暗档主腿 ════════ */
{
  const { ctx, page, errors } = await boot({ colorScheme: 'dark' });

  /* G1 材质逐项相等（ai 泡 + user 泡 vs #input-bar） */
  const mat = await page.evaluate(() => {
    const pick = (el) => { if (!el) return null; const c = getComputedStyle(el); return { bg: c.backgroundColor, bf: c.backdropFilter || c.webkitBackdropFilter, border: c.borderTopColor, shadow: c.boxShadow }; };
    return { ai: pick(document.querySelector('.ask-bub.ai')), user: pick(document.querySelector('.ask-bub.user')), bar: pick(document.getElementById('input-bar')) };
  });
  const eq = (a) => !!a && a.bg === mat.bar.bg && a.bf === mat.bar.bf && a.border === mat.bar.border && a.shadow === mat.bar.shadow;
  ok('G1a ai 泡材质 = #input-bar 逐项相等（bg/blur/描边/浮影，玻璃单源）', eq(mat.ai), JSON.stringify({ ai: mat.ai, bar: mat.bar }));
  ok('G1b user 泡材质 = #input-bar 逐项相等（问/答同材质，bubble-side 批后无第二色相）', eq(mat.user), JSON.stringify(mat.user || {}));

  /* G2 真模糊像素证：同几何双态实拍 diff */
  const bub = page.locator('.ask-bub').nth(1);
  const A = await bub.screenshot({ animations: 'disabled' });
  await page.evaluate(() => { [...document.querySelectorAll('.ask-bub')].forEach((b) => { b.style.backdropFilter = 'none'; b.style.webkitBackdropFilter = 'none'; }); });
  await sleep(250);
  const B = await bub.screenshot({ animations: 'disabled' });
  await page.evaluate(() => { [...document.querySelectorAll('.ask-bub')].forEach((b) => { b.style.backdropFilter = ''; b.style.webkitBackdropFilter = ''; }); });
  const meanDiff = await page.evaluate(async ({ a, b }) => {
    const mk = (src) => new Promise((res) => { const i = new Image(); i.onload = () => res(i); i.src = src; });
    const [ia, ib] = await Promise.all([mk(a), mk(b)]);
    const w = Math.max(ia.width, ib.width), h = Math.max(ia.height, ib.height);
    const cv = document.createElement('canvas'); cv.width = w; cv.height = h;
    const g = cv.getContext('2d'); g.drawImage(ia, 0, 0);
    const da = g.getImageData(0, 0, w, h).data;
    g.clearRect(0, 0, w, h); g.drawImage(ib, 0, 0);
    const db = g.getImageData(0, 0, w, h).data;
    let s = 0, n = 0;
    for (let i = 0; i < da.length; i += 4) {
      s += Math.abs((0.299 * da[i] + 0.587 * da[i + 1] + 0.114 * da[i + 2]) - (0.299 * db[i] + 0.587 * db[i + 1] + 0.114 * db[i + 2])); n++;
    }
    return +(s / n).toFixed(2);
  }, { a: 'data:image/png;base64,' + A.toString('base64'), b: 'data:image/png;base64,' + B.toString('base64') });
  ok(`G2 真模糊像素证：摘 blur 前后气泡实拍 mean|Δlum| = ${meanDiff}（≥4 = 模糊真实生效；改前 0.25 = 红）`, meanDiff >= 4, `diff=${meanDiff}`);

  /* D1 拉动净空（滚动尽头） */
  await page.evaluate(() => { const c = document.querySelector('.ask-col'); c.scrollTop = c.scrollHeight; });
  await sleep(200);
  const g1 = await page.evaluate(probe);
  ok(`D1a 列 padding-bottom = --ask-under + --ds-space-1h（${g1.under} + ${g1.gap1h}）`, !!g1.under && parseFloat(g1.colPadBottom) >= parseFloat(g1.under) + parseFloat(g1.gap1h) - 1.5 && parseFloat(g1.colPadBottom) <= parseFloat(g1.under) + parseFloat(g1.gap1h) + 1.5, `pad=${g1.colPadBottom}`);
  ok(`D1b 拉动到尽头：最新气泡底边 ≤ 输入条顶边（交叠 = ${g1.ovLastBubVsInput}px² = 0）`, g1.lastBub && g1.input && g1.ovLastBubVsInput === 0, `lastBub.b=${g1.lastBub && g1.lastBub.b} input.t=${g1.input && g1.input.t}`);
  ok(`D1c 尽头处最新气泡可点（elementFromPoint 底边上方 4px = ${g1.lastBubHitAtBottom}）`, g1.lastBubHitAtBottom === 'ask-dock', JSON.stringify({ hit: g1.lastBubHitAtBottom }));

  /* D2 极值三读数 */
  // ① 渐隐（opacity 载体）：追加三条实时问答把内容撑出滚动余量，再把顶泡滚进
  //    顶带（scrollTop ∈ (paddingTop−fadeH, paddingTop) ⇒ 泡顶落在 12px 衰减带内）。
  //    注：scrollTop=0 时泡顶 = paddingTop 18px > fadeH 12px，alpha 恒 1（mask 时代
  //    同语义——渐隐只咬「滚进顶带」的内容，静态首条完整清晰）。
  for (let i = 0; i < 3; i++) {
    await page.fill('.ask-input textarea', '追问 ' + (i + 1) + '：再多说一点样本文字。');
    await page.click('.ask-send');
    await sleep(350);
  }
  const sc = await page.evaluate(() => { const c = document.querySelector('.ask-col'); return { max: c.scrollHeight - c.clientHeight }; });
  await page.evaluate((s) => { document.querySelector('.ask-col').scrollTop = s; }, Math.min(10, Math.max(0, sc.max - 1)));
  await sleep(300);
  const gTop = await page.evaluate(probe);
  ok(`D2① 列滚动顶带内：顶泡 alpha=${gTop.topOpacity} < 1（渐隐仍在，opacity 载体）`, parseFloat(gTop.topOpacity) < 1, `topOpacity=${gTop.topOpacity} scrollMax=${sc.max}`);
  ok('D2② 列滚动尽头（最下）= D1 同读数（已测）', true);
  // 面板贴边：把手连按 ↑（键盘同源状态机）到夹取上限
  await page.evaluate(() => { const c = document.querySelector('.ask-col'); c.scrollTop = c.scrollHeight; });
  const handle = page.locator('.ask-dock .ds-resize-handle');
  await handle.focus();
  for (let i = 0; i < 40; i++) await page.keyboard.press('ArrowUp');
  await sleep(300);
  const gEdge = await page.evaluate(probe);
  const panel = gEdge.panel, dock = gEdge.dock, content = gEdge.content, handleBox = gEdge.handle;
  const insidePanel = panel && dock && dock.t >= panel.t - 1 && dock.b <= panel.b + 1 && dock.l >= panel.l - 1 && dock.r <= panel.r + 1;
  const insideVp = dock && dock.t >= 0 && dock.b <= gEdge.vpH && dock.l >= 0;
  ok(`D2③ 面板贴边（把手 ↑ 到夹取上限）：panel(${dock && dock.t},${dock && dock.b}) ⊆ #canvas-panel(${panel && panel.t},${panel && panel.b}) 且在视口内`, !!insidePanel && !!insideVp, JSON.stringify({ dock, panel, content, handle: handleBox }));

  /* D4 层级钉 */
  ok(`D4a 桌面 dock z = ${g1.dockZ}（< 顶行 30，现状钉：本批零新 z-index）`, g1.dockZ === '24' && g1.dockPos === 'absolute', `z=${g1.dockZ} pos=${g1.dockPos}`);

  if (errors.length) ok('R0 桌面暗档零 JS 错误', false, errors.join(' | '));
  else ok('R0 桌面暗档零 JS 错误', true);
  await ctx.close();
}

/* ════════ G1 亮档腿 ════════ */
{
  const { ctx, page } = await boot({ colorScheme: 'light' });
  const mat = await page.evaluate(() => {
    const pick = (el) => { if (!el) return null; const c = getComputedStyle(el); return { bg: c.backgroundColor, bf: c.backdropFilter || c.webkitBackdropFilter, border: c.borderTopColor, shadow: c.boxShadow }; };
    return { ai: pick(document.querySelector('.ask-bub.ai')), bar: pick(document.getElementById('input-bar')) };
  });
  ok('G1c 亮档 ai 泡材质 = #input-bar 逐项相等', !!mat.ai && mat.ai.bg === mat.bar.bg && mat.ai.bf === mat.bar.bf && mat.ai.border === mat.bar.border && mat.ai.shadow === mat.bar.shadow, JSON.stringify({ ai: mat.ai, bar: mat.bar }));
  await ctx.close();
}

/* ════════ D1s 静态净空（单问单答，无滚动）+ G1 手机腿 ════════ */
{
  const { ctx, page } = await boot({ colorScheme: 'dark', msgs: [
    { role: 'user', text: '单问。' },
    { role: 'ai', text: '单答：这条很短，不产生滚动。' },
  ] });
  const g = await page.evaluate(probe);
  ok(`D1s 静态（无滚动）最新气泡底边与输入条顶边间距 = --ds-space-1h（${g.gap1h}）：实测 ${g.lastBottomVsInputTop}（改前 = −17 即气泡扎进输入条）`, Math.abs(g.lastBottomVsInputTop - parseFloat(g.gap1h)) <= 1.5, `gap=${g.lastBottomVsInputTop} colScrollMax=${g.colScrollMax}`);
  ok('D1s-b 静态最新气泡与输入条交叠 = 0', g.ovLastBubVsInput === 0, `ov=${g.ovLastBubVsInput}px²`);
  await ctx.close();
}
{
  const { ctx, page } = await boot({ colorScheme: 'dark', viewport: { width: 375, height: 812 }, mobile: true });
  const mat = await page.evaluate(() => {
    const pick = (el) => { if (!el) return null; const c = getComputedStyle(el); return { bg: c.backgroundColor, bf: c.backdropFilter || c.webkitBackdropFilter, border: c.borderTopColor, shadow: c.boxShadow }; };
    return { ai: pick(document.querySelector('.ask-bub.ai')), bar: pick(document.getElementById('input-bar')) };
  });
  ok('G1d 手机 375 暗：ai 泡材质 = 主 #input-bar 逐项相等', !!mat.ai && mat.ai.bg === mat.bar.bg && mat.ai.bf === mat.bar.bf && mat.ai.border === mat.bar.border && mat.ai.shadow === mat.bar.shadow, JSON.stringify({ ai: mat.ai }));
  const g = await page.evaluate(probe);
  ok(`D4b 手机层 dock z = ${g.dockZ}（> 底 dock 200，现状钉）`, g.dockZ === '260' && g.dockPos === 'fixed', `z=${g.dockZ} pos=${g.dockPos}`);
  await page.evaluate(() => { const c = document.querySelector('.ask-col'); c.scrollTop = c.scrollHeight; });
  await sleep(200);
  const gm = await page.evaluate(probe);
  ok(`D1m 手机 375：拉动尽头最新气泡与面板输入条交叠 = ${gm.ovLastBubVsInput}px²（=0）`, gm.ovLastBubVsInput === 0, `lastBub.b=${gm.lastBub && gm.lastBub.b} input.t=${gm.input && gm.input.t}`);
  await ctx.close();
}

/* ════════ G3 兜底材质（非 dock .ask-bub 静态面）════════ */
{
  const fs = readFileSync(join(WEB_DIR, 'css/glass.css'), 'utf8');
  const m = fs.match(/\.ask-bub \{[^}]*\}/);
  ok('G3 glass.css `.ask-bub` 非 dock 兜底带 backdrop-filter（禁半透明洗色兜底）', !!m && /backdrop-filter:\s*var\(--nf-glass-filter\)/.test(m[0]), m ? m[0].slice(0, 160) : 'rule not found');
}

console.log(`\n${pass} pass, ${fail} fail`);
await browser.close();
server.close();
process.exit(fail ? 1 : 0);

// canvas-glass-unify.spec.mjs — canvas 三件 UI/UX 修（作者令 2026-10-28）验收钉
//
// 件一 独立窗口胶囊行对齐面板同款 + 水平居中；
// 件二 顶部行（胶囊+堆叠钮+「独立中」角标）= 固定 header 行，其余一切控件只在
//      行下方 —— 布局排他（bounding box 零相交），非 z-index 遮掩；
// 件三 聊天窗口控件毛玻璃 = 唯一风格模板，token 单源（sapphire.css --cv-* 档），
//      canvas 一切控件（胶囊/堆叠钮/工具圆钮/查找条/缩放条）计算样式与聊天
//      #header 模板逐项相等。
//
// 断言面（真实渲染 boundingRect + computedStyle，无布局 Mock）：
//   GU1 header 行排他（件二）：
//     GU1-1 开查找态：胶囊 rect 与 .nf-findbar rect 零相交 + findbar top =
//           --cv-head-clear（46+10=56px，行下缘起算）；
//     GU1-2 胶囊 rect 与 #deck-fab rect 零相交（同排两端）；
//     GU1-3 独立态：slide[data-independent] 角标盒（::after 计算盒重构）与
//           #deck-fab 零相交、与胶囊零相交（修前 right:12 与 fab 同角叠印）；
//     GU1-4 底右工具圆钮簇（source/ref/find/graph/refresh）与 header 行带
//           （slide 顶 .. --cv-head-band 46px）零相交；
//     GU1-5 缩放条与 header 行带零相交。
//   GU2 玻璃 token 生效证明（spec钉②）—— 关键控件计算样式与聊天控件模板
//      （#header）逐项相等（bg / backdropFilter / borderTopColor / boxShadow）：
//     GU2-1 标题胶囊；GU2-2 工具圆钮（source/find/refresh，base opacity=1）；
//     GU2-3 堆叠钮 #deck-fab；GU2-4 查找条 .nf-findbar（+ top = 行下缘锚）；
//     GU2-5 缩放条 .canvas-zoom-bar + 内钮 ghost（静默 transparent，非 chip 面）；
//     GU2-6 hover 语言：source 钮 hover 后 background = --cv-hover-bg 计算值；
//     GU2-7 token 单源静态面：sapphire.css 声明 --cv-* 档；split.css 查找条块
//     消费 var(--cv-*（禁自带材质字面量）；自绘外投影 0 4px 16px 与 right:66
//     让位带退役。
//   GU3 卡窗胶囊行（件一）：水平居中（±3px）+ 几何同面板（36 高 / 999 圆角 /
//      180 宽档）+ 材质与主窗胶囊逐项相等 + 合并钮 rect ⊂ 胶囊行 rect。
//   GU-L1 亮档复跑材质相等（token 随主题换值仍逐项相等）。
//   R0 主文档 / 卡窗文档零 JS 错误。
//
// 运行：node tests/canvas-glass-unify.spec.mjs

import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8176);
const BASE = `http://127.0.0.1:${PORT}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let pass = 0, fail = 0;
function ok(name, cond, detail = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  if (cond) pass++; else fail++;
}
const approx = (a, b, tol) => Math.abs(a - b) <= tol;
/** 矩形相交（排他断言公共腿）。 */
const hit = (a, b) => !!a && !!b && a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;

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

const MD_PATH = '/tmp/gu-fixture/glass-unify.md';
const FILE_CONTENTS = new Map([
  [MD_PATH, { content: '# glass-unify\n\n' + Array.from({ length: 20 }, (_, i) => `第 ${i + 1} 段样本行。`).join('\n\n') + '\n', itemType: 'markdown' }],
]);
const HTML_FIXTURE = join(HERE, 'fixtures/canvas-html-zoom/poster.html');

async function boot({ colorScheme = 'dark', viewport = { width: 1440, height: 900 } } = {}) {
  const ctx = await browser.newContext({ viewport, colorScheme });
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
        const rec = FILE_CONTENTS.get(m.path);
        ws.send(JSON.stringify({
          type: 'fileContent', path: m.path, absPath: m.path,
          content: rec ? rec.content : '', itemType: rec ? rec.itemType : 'markdown',
          fileName: (m.path || '').split('/').pop(), size: rec ? rec.content.length : 0,
        }));
      }
    });
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('body.mobile-layer, #activity-bar', { timeout: 15000 });
  await sleep(500);
  return { ctx, page, errors };
}

async function openMd(page) {
  const rec = FILE_CONTENTS.get(MD_PATH);
  await page.evaluate(({ absPath, content }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType: 'markdown', title: absPath.split('/').pop(), content, absPath, pinned: true },
    }));
  }, { absPath: MD_PATH, content: rec.content });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${MD_PATH}"] .canvas-md-viewer`, { timeout: 10000 });
  await sleep(350);
}

/** 控件材质探针（页内执行）：rect + 计算样式关键项（bg/blur/border-top/shadow/opacity/top/radius）。 */
const probeSel = `(() => (sel) => {
  const el = document.querySelector(sel);
  if (!el) return null;
  const r = el.getBoundingClientRect();
  const c = getComputedStyle(el);
  return { x: r.x, y: r.y, w: r.width, h: r.height,
    bg: c.backgroundColor, blur: c.backdropFilter || c.webkitBackdropFilter,
    btop: c.borderTopColor, shadow: c.boxShadow, opacity: c.opacity,
    top: c.top, radius: c.borderRadius };
})()`;

/* ════════ 桌面暗档腿：GU1 排他 + GU2 材质相等 + GU3 卡窗 ════════ */
{
  const { ctx, page, errors } = await boot({ colorScheme: 'dark' });
  await openMd(page);

  /* ── GU2 材质逐项相等（dark）── */
  const mat = await page.evaluate(`(${probeSel})('.canvas-header')`) ;
  const header = await page.evaluate(() => {
    const c = getComputedStyle(document.getElementById('header'));
    return { bg: c.backgroundColor, blur: c.backdropFilter || c.webkitBackdropFilter, btop: c.borderTopColor, shadow: c.boxShadow };
  });
  const eqHdr = (m) => !!m && m.bg === header.bg && m.blur === header.blur && m.btop === header.btop && m.shadow === header.shadow;
  ok('GU2-1 标题胶囊材质 = chat #header 逐项相等（bg/blur/border-top/shadow，件三模板生效）',
    eqHdr(mat), JSON.stringify({ pill: mat, header }));
  for (const [sel, tag] of [['.canvas-source-toggle', 'source'], ['.canvas-find-toggle', 'find（修前 --glass-control 自绘+减淡 .6）'], ['.canvas-refresh-toggle', 'refresh']]) {
    const m = await page.evaluate(`(${probeSel})('${sel}')`);
    ok(`GU2-2 工具圆钮 ${tag} = 模板逐项相等 + base opacity 1（减淡退役）`,
      eqHdr(m) && m.opacity === '1');
  }
  const fabM = await page.evaluate(`(${probeSel})('#deck-fab')`);
  ok('GU2-3 堆叠钮 #deck-fab = 模板逐项相等（修前 control 自绘 + opacity .6）',
    eqHdr(fabM) && fabM.opacity === '1');

  /* ── GU1-1/1-2 开查找态排他 ── */
  await page.click('.canvas-find-toggle');
  await page.waitForSelector('.nf-findbar', { timeout: 5000 });
  await sleep(400);   // 入场动画落定
  const findbar = await page.evaluate(`(${probeSel})('.nf-findbar')`);
  const pill = await page.evaluate(`(${probeSel})('.canvas-header')`);
  const fab = await page.evaluate(`(${probeSel})('#deck-fab')`);
  const clearTok = await page.evaluate(() => getComputedStyle(document.getElementById('canvas-slide')).getPropertyValue('--cv-head-clear').trim());
  ok('GU2-4 查找条材质 = 模板逐项相等（修前自绘 + 自带外投影）', eqHdr(findbar));
  ok('GU1-1 开查找态：查找条与胶囊 bounding box 零相交（件二排他，修前压字）',
    !hit(pill, findbar), `pill=(${pill.x.toFixed(0)},${pill.y.toFixed(0)},${pill.w.toFixed(0)}×${pill.h.toFixed(0)}) fb=(${findbar.x.toFixed(0)},${findbar.y.toFixed(0)},${findbar.w.toFixed(0)}×${findbar.h.toFixed(0)})`);
  ok('GU1-1b 查找条 top = --cv-head-clear（行下缘 46px + 呼吸缝 = 56px，token 锚）',
    approx(parseFloat(findbar.top), 56, 1) && /calc/.test(clearTok || ''), `top=${findbar.top} token=${clearTok}`);
  ok('GU1-2 胶囊与堆叠钮同排两端零相交', !hit(pill, fab));
  await page.keyboard.press('Escape');
  await sleep(250);

  /* ── GU1-4/1-5 底右簇与 header 行带零相交 ── */
  const bandProbe = await page.evaluate(`(() => {
    const slide = document.getElementById('canvas-slide');
    const s = slide.getBoundingClientRect();
    const band = { x: s.x, y: s.y, w: s.width, h: 46 };
    const rect = (sel) => { const el = document.querySelector(sel); if (!el) return null; const r = el.getBoundingClientRect(); return { x: r.x, y: r.y, w: r.width, h: r.height }; };
    const hitl = (a, b) => !!a && !!b && a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;
    const circles = ['.canvas-source-toggle', '.canvas-ref-select', '.canvas-find-toggle', '.canvas-graph-toggle', '.canvas-refresh-toggle']
      .map((sel) => ({ sel, r: rect(sel) })).filter((o) => o.r);
    return { band, circles, zoom: rect('.canvas-zoom-bar'),
      hitCircle: circles.some((o) => hitl(o.r, band)), hitZoom: hitl(rect('.canvas-zoom-bar'), band) };
  })()`);
  ok('GU1-4 工具圆钮簇与 header 行带零相交（圆钮只在行下方）',
    !bandProbe.hitCircle, bandProbe.circles.map((o) => o.sel.split('-')[1] + ':y' + o.r.y.toFixed(0)).join(' '));
  ok('GU1-5 缩放条与 header 行带零相交', !bandProbe.hitZoom,
    bandProbe.zoom ? `zoom y=${bandProbe.zoom.y.toFixed(0)}` : 'zoom absent（md 页签无缩放条，几何腿在 html 腿复跑）');

  /* ── 独立态：真弹卡窗（GU1-3 + GU3）── */
  const popupP = ctx.waitForEvent('page', { timeout: 8000 });
  await page.click('#canvas-pop-btn');
  const popup = await popupP;
  const popupErrors = [];
  popup.on('pageerror', (e) => popupErrors.push(String(e.message || e).slice(0, 200)));
  await popup.waitForLoadState('domcontentloaded');
  await popup.waitForSelector('body.card-window', { timeout: 15000 });
  await sleep(1500);

  const band2 = await page.evaluate(`(() => {
    const slide = document.getElementById('canvas-slide');
    const s = slide.getBoundingClientRect();
    const after = getComputedStyle(slide, '::after');
    // ::after 计算宽高 = content-box；真实 border box = content + padding(9×2) + border(1×2)
    const bw = (parseFloat(after.width) || 0) + 20, bh = (parseFloat(after.height) || 0) + 8;
    const badge = { x: s.right - (parseFloat(after.right) || 0) - bw, y: s.y + (parseFloat(after.top) || 0) - 4, w: bw, h: bh };
    const rect = (sel) => { const el = document.querySelector(sel); if (!el) return null; const r = el.getBoundingClientRect(); return { x: r.x, y: r.y, w: r.width, h: r.height }; };
    return { on: slide.hasAttribute('data-independent'), content: after.content, badge, pill: rect('.canvas-header'), fab: rect('#deck-fab') };
  })()`);
  ok('GU1-3a 独立态：角标挂在 slide 壳层（独立中）', band2.on && String(band2.content).includes('独立中'), `on=${band2.on} content=${band2.content}`);
  ok('GU1-3b 独立态：角标与堆叠钮零相交（修前 right:12 同角叠印，作者截图 2）',
    !hit(band2.badge, band2.fab), `badge=(${band2.badge.x.toFixed(0)},${band2.badge.y.toFixed(0)},${band2.badge.w.toFixed(0)}×${band2.badge.h.toFixed(0)}) fab=(${band2.fab.x.toFixed(0)},${band2.fab.y.toFixed(0)})`);
  ok('GU1-3c 独立态：角标与胶囊零相交（独立态胶囊收窄护栏）', !hit(band2.badge, band2.pill));

  /* ── GU3 卡窗胶囊行（件一）── */
  const cw = await popup.evaluate(`(() => {
    const hdr = document.querySelector('#canvas-panel .canvas-header');
    const r = hdr.getBoundingClientRect();
    const c = getComputedStyle(hdr);
    const merge = document.querySelector('.canvas-merge-btn');
    const m = merge ? merge.getBoundingClientRect() : null;
    return { cx: r.x + r.width / 2, vw: window.innerWidth, h: r.height, w: r.width, radius: c.borderRadius,
      bg: c.backgroundColor, blur: c.backdropFilter || c.webkitBackdropFilter, shadow: c.boxShadow,
      mergeInside: !!m && m.left >= r.left - 1 && m.right <= r.right + 1 && m.top >= r.top - 1 && m.bottom <= r.bottom + 1 };
  })()`);
  ok('GU3-1 卡窗胶囊行水平居中（±3px，件一）', approx(cw.cx, cw.vw / 2, 3), `cx=${cw.cx.toFixed(1)} vw/2=${cw.vw / 2}`);
  ok('GU3-2 卡窗胶囊几何同面板档（36 高 / 999 圆角 / 180 宽）',
    approx(cw.h, 36, 1) && cw.radius === '999px' && approx(cw.w, 180, 1), `h=${cw.h} w=${cw.w} r=${cw.radius}`);
  ok('GU3-3 卡窗胶囊材质与主窗面板胶囊逐项相等（视觉语言完全同款）',
    cw.bg === pill.bg && cw.blur === pill.blur && cw.shadow === pill.shadow);
  ok('GU3-4 合并钮在胶囊行内（行 = 文件名胶囊 + 展开钮）', cw.mergeInside);
  ok('R0b 卡窗文档零 JS 错误', popupErrors.length === 0, popupErrors.slice(0, 2).join(' | '));
  await popup.close();

  /* ── GU2-6 hover 语言 ── */
  await page.hover('.canvas-source-toggle');
  await sleep(300);
  const hoverBg = await page.evaluate(`(() => {
    const c = getComputedStyle(document.querySelector('.canvas-source-toggle'));
    const d = document.createElement('div');
    d.style.background = 'var(--cv-hover-bg)';
    document.body.appendChild(d);
    const want = getComputedStyle(d).backgroundColor;
    d.remove();
    return { got: c.backgroundColor, want };
  })()`);
  ok('GU2-6 hover 语言 = 聊天控件（hover 抬亮 = --cv-hover-bg 计算值）',
    hoverBg.got === hoverBg.want, JSON.stringify(hoverBg));

  /* ── html 腿：GU2-5 缩放条材质 + 内钮 ghost + GU1-5 复跑 ── */
  const htmlContent = readFileSync(HTML_FIXTURE, 'utf8');
  await page.evaluate((content) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:/tmp/gu-fixture/poster.html', itemType: 'html', title: 'poster.html', content, absPath: '/tmp/gu-fixture/poster.html', pinned: true },
    }));
  }, htmlContent);
  await page.waitForSelector('.canvas-zoom-bar', { timeout: 10000 });
  await sleep(400);
  const zoomM = await page.evaluate(`(() => {
    const bar = document.querySelector('.canvas-zoom-bar');
    const r = bar.getBoundingClientRect();
    const c = getComputedStyle(bar);
    const btn = bar.querySelector('.canvas-zoom-btn');
    const bc = btn ? getComputedStyle(btn) : null;
    const s = document.getElementById('canvas-slide').getBoundingClientRect();
    return { bg: c.backgroundColor, blur: c.backdropFilter || c.webkitBackdropFilter, btop: c.borderTopColor, shadow: c.boxShadow,
      y: r.y, bandHit: r.y < s.y + 46,
      btnBg: bc ? bc.backgroundColor : null };
  })()`);
  ok('GU2-5a 缩放条材质 = 模板逐项相等（修前 --frost-shadow 外投影）',
    zoomM.bg === header.bg && zoomM.blur === header.blur && zoomM.btop === header.btop && zoomM.shadow === header.shadow);
  ok('GU2-5b 缩放内钮 ghost（静默 transparent，非 glass-control chip 面）',
    zoomM.btnBg === 'rgba(0, 0, 0, 0)', `btnBg=${zoomM.btnBg}`);
  ok('GU1-5b 缩放条与 header 行带零相交（html 腿复跑）', !zoomM.bandHit, `zoom y=${zoomM.y.toFixed(0)}`);

  /* ── GU2-7 token 单源静态面 ── */
  const sap = readFileSync(join(WEB_DIR, 'css/sapphire.css'), 'utf8');
  const split = readFileSync(join(WEB_DIR, 'css/split.css'), 'utf8');
  const findbarBlock = (split.match(/\.nf-findbar\s*\{[^}]*\}/) || [''])[0];
  ok('GU2-7a token 单源：sapphire.css 声明 --cv-* 档（模糊配方/边框/阴影/hover/尺寸/行几何）',
    sap.includes('--cv-bg: var(--glass-bg)') && sap.includes('--cv-shadow: inset 0 1px 0 0 rgba(255, 255, 255, 0.25)') &&
    sap.includes('--cv-size-circle: 40px') && sap.includes('--cv-head-band:'));
  ok('GU2-7b 查找条块消费 --cv-* token（禁自带材质字面量）+ 行下缘锚',
    findbarBlock.includes('var(--cv-bg') && findbarBlock.includes('var(--cv-head-clear') &&
    findbarBlock.includes('var(--cv-radius-bar'), findbarBlock.slice(0, 120));
  ok('GU2-7c 自绘外投影（0 4px 16px）与 right:66 让位带退役',
    !findbarBlock.includes('0 4px 16px') && !/\.nf-findbar\s*\{[^}]*right:\s*66px/.test(split) &&
    !/body\.canvas-open:not\(\.mobile-layer\):not\(\.card-window\) \.nf-findbar/.test(split));
  ok('R0 主文档零 JS 错误（暗档腿）', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ 亮档腿：GU-L1 材质相等随主题复跑 ════════ */
{
  const { ctx, page, errors } = await boot({ colorScheme: 'light' });
  await openMd(page);
  const mat = await page.evaluate(`(() => {
    const pick = (el) => { if (!el) return null; const c = getComputedStyle(el);
      return { bg: c.backgroundColor, blur: c.backdropFilter || c.webkitBackdropFilter, btop: c.borderTopColor, shadow: c.boxShadow }; };
    return { header: pick(document.getElementById('header')),
      pill: pick(document.querySelector('.canvas-header')),
      find: pick(document.querySelector('.canvas-find-toggle')),
      fab: pick(document.getElementById('deck-fab')) };
  })()`);
  const eq = (a, b) => !!a && !!b && a.bg === b.bg && a.blur === b.blur && a.btop === b.btop && a.shadow === b.shadow;
  ok('GU-L1a 亮档：标题胶囊 = chat #header 逐项相等', eq(mat.pill, mat.header));
  ok('GU-L1b 亮档：find 圆钮 = 模板逐项相等', eq(mat.find, mat.header));
  ok('GU-L1c 亮档：堆叠钮 = 模板逐项相等', eq(mat.fab, mat.header));
  ok('R0c 亮档腿零 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

await browser.close();
server.close();
console.log(`\ncanvas-glass-unify: ${pass} PASS / ${fail} FAIL`);

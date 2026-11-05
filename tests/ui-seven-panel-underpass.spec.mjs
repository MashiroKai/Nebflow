// ui-seven-panel-underpass.spec.mjs — 件4「穿顶设计推广」验收钉（作者令 2026-11-05）
//
// 作者原话（逐字）：「并且canvas顶部的这种内容一开始不允许在按钮下，但是滑动后可以
// 从按钮下穿过的这种设计要落实到其他的面板。因为其他面板的样式基本也是有顶部的胶囊
// 标题和一起返回键。这样式和Canvas的统一。」
// 两条铁律：元素不许为了腾地方而消失；两态都要成立 —— 初始不遮挡/不重叠，滚动后才
// 允许从按钮/胶囊下穿过。
//
// 机制（本 spec 的判据全部锚它，canvas 面 = 参照腿 REF）：
//   · 胶囊/返回条 = **滚动容器之外的绝对浮层**（z 序在内容之上 + 玻璃本体
//     backdrop-filter），内容不被压是因为**滚动容器自身 padding-top 让位**
//     （padding 随内容滚走 ⇒ 滚动后内容从胶囊底下穿过），不是因为任何遮罩层。
//   · 判据必须是**裁剪感知**的：`visibleTop = max(首块 rect.top, 滚动容器 rect.top)`
//     —— 只读未裁剪 rect 时「滚动容器整体下移（top:56）」也会假绿。
//   · 禁遮罩：滚动带上 elementFromPoint 必须命中内容本身；且滚动带内不得存在
//     「背景非透明 + z 序高于内容 + pointer-events:none」的新增覆盖层。
//
// 面板矩阵（逐面板 × 双主题；桌面 1440，手机 375 腿在 mobile 侧已由页面壳自证）：
//   P1 projects-list     项目列表面板（.team-scroll 卡片列表）
//   P2 projects-panorama 项目全景视图（.flowmap-nav-bar 返回条 + .delegate-chain-view-body）
//   P3 projects-chain-task 委托链 per-task 视图（返回条 + 地图面，滚动腿 N/A → 见注）
//   REF canvas-md        canvas markdown 查看面（参照实现，只读钉机制活变量）
//
// 断言面：
//   A1 初始（scrollTop=0）：首内容块 rect.top ≥ 胶囊 rect.bottom（有返回键时另加
//      与返回键零相交），且首块 h>0 / visibility≠hidden / opacity>0（元素不许消失）。
//   A2 同一元素同一 rect 前后 ±1：胶囊与返回键 width>0、可见、两次采样位移 ≤1px。
//   B1 滚动（scrollTop=200）：裁剪感知首块可见顶 < 胶囊 rect.bottom（确实从下方
//      穿过）+ 胶囊玻璃本体 backdrop-filter ≠ none。
//   B2 滚动后内容可读：胶囊下方那条内容带上 elementFromPoint 命中内容子树（不是
//      遮罩/veil），且该带内零「非透明 + pointer-events:none + 覆盖面 ≥ 面板宽 50%」
//      的覆盖层。
//   C1 回归护栏（负向）：全站不得出现 .scroll-under-glass 之外的新增
//      「position:absolute/fixed + pointer-events:none + 非透明底 + 横向 ≥50% 面宽」
//      遮罩层 —— 命中清单必须为空。
//
// 运行：PORT=8253 node tests/ui-seven-panel-underpass.spec.mjs
//   （本仓约定：静态服务器从磁盘发 web/，/api 与 WS 全打桩，不碰宿主实例）

import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const WEB_DIR = resolve(ROOT, 'src/main/resources/web');
const PORT = Number(process.env.PORT || 8253);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS = resolve(ROOT, 'evidence/20261105_ui-seven/c3');
mkdirSync(SHOTS, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let pass = 0, fail = 0;
const lines = [];
function ok(name, cond, detail = '') {
  const line = `${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`;
  console.log(line);
  lines.push(line);
  if (cond) pass++; else fail++;
}

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.jpg': 'image/jpeg', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ico': 'image/x-icon',
};
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const b = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(b);
  } catch { if (!res.headersSent) { res.writeHead(404); res.end(); } else res.end(); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

/* ── 夹具（可滚动内容：列表必须够长，否则滚动腿无从读数）──────────────── */
const now = Date.now();
const iso = (ms) => new Date(ms).toISOString();
const PROJECTS = {
  projects: Array.from({ length: 14 }, (_, i) => ({
    name: `proj-${i}`, workspace: `/Users/dev/work/proj-${i}`, agentFile: `proj-${i}`,
    description: `项目 ${i}：跨面板穿顶验收夹具`, createdAt: iso(now - i * 86400e3),
  })),
};
const DELEGATE_ROWS = {
  rows: Array.from({ length: 12 }, (_, i) => ({
    id: `d${i + 1}`, taskId: String(100 + i), title: `委托任务 ${i + 1}：穿顶验收夹具行`,
    name: `批次 ${i + 1}`, executor: i % 3 === 0 ? 'claude' : 'nebflow',
    project: 'proj-0', reportStatus: '', taskStatus: i % 4 === 0 ? 'running' : 'completed',
    createdAt: now - i * 3600e3, orchestrationMode: 'dispatcher', mode: 'kernel',
  })),
};
const CHAIN_TASK = {
  record: { id: 'd1', taskId: '100', title: '委托任务 1', project: 'proj-0', executor: 'claude', taskStatus: 'running' },
  nodes: [
    { id: 'n1', name: '拆解', agent: 'planner', status: 'completed', in: [], out: 'n2' },
    { id: 'n2', name: '实现', agent: 'coder', status: 'running', in: ['n1'], out: 'Nebula' },
  ],
};
const MD_PATH = '/tmp/ui-seven-c3/underpass.md';
const MD_CONTENT = '# underpass\n\n' + Array.from({ length: 30 }, (_, i) => `第 ${i + 1} 段正文，用于 canvas 参照腿滚动读数。`).join('\n\n') + '\n';

const browser = await chromium.launch();

async function boot({ colorScheme = 'dark', viewport = { width: 1440, height: 900 } } = {}) {
  const ctx = await browser.newContext({ viewport, colorScheme });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  await ctx.route('**/api/**', (r) => r.fulfill({ json: {} }));
  await ctx.route('**/api/projects', (r) => r.fulfill({ json: PROJECTS }));
  await ctx.route('**/api/delegates', (r) => r.fulfill({ json: DELEGATE_ROWS }));
  await ctx.route('**/api/chains', (r) => r.fulfill({ json: { rows: DELEGATE_ROWS.rows } }));
  await ctx.route('**/api/chains/**', (r) => r.fulfill({ json: CHAIN_TASK }));
  await ctx.route('**/api/delegates/**', (r) => r.fulfill({ json: CHAIN_TASK }));
  await ctx.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }],
      activeId: 'sess-1', folders: [],
    }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      } else if (m.type === 'pop.readFile') {
        ws.send(JSON.stringify({
          type: 'fileContent', path: m.path, absPath: m.path, content: MD_CONTENT,
          itemType: 'markdown', fileName: (m.path || '').split('/').pop(), size: MD_CONTENT.length,
        }));
      }
    });
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 240)));
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('body.mobile-layer, #activity-bar', { timeout: 20000 });
  await sleep(700);
  return { ctx, page, errors };
}

/* ── 页内读数（裁剪感知 + 命中 + 遮罩扫描；全部真实渲染，无布局 Mock）──── */
const PROBE = `(() => (cfg) => {
  const q = (s) => (s ? document.querySelector(s) : null);
  const R = (el) => { if (!el) return null; const r = el.getBoundingClientRect();
    return { x: +r.x.toFixed(1), y: +r.y.toFixed(1), w: +r.width.toFixed(1), h: +r.height.toFixed(1), top: +r.top.toFixed(1), bottom: +r.bottom.toFixed(1), left: +r.left.toFixed(1), right: +r.right.toFixed(1) }; };
  const desc = (el) => { if (!el) return 'null';
    const cls = (typeof el.className === 'string' && el.className.trim()) ? el.className.trim().split(/\\s+/).join('.') : '';
    return el.tagName.toLowerCase() + (cls ? '.' + cls : ''); };
  const pill = q(cfg.pill), back = q(cfg.back || ''), sc = q(cfg.scroller), first = q(cfg.first), panel = q(cfg.panel);
  const pcs = pill ? getComputedStyle(pill) : null;
  const glassOf = (el) => { if (!el) return 'null';
    const c = getComputedStyle(el);
    const self = (c.backdropFilter || c.webkitBackdropFilter || 'none');
    const b4 = getComputedStyle(el, '::before');
    const before = (b4.backdropFilter || b4.webkitBackdropFilter || 'none');
    return self !== 'none' ? self : before; };
  const styleOf = (el) => { if (!el) return null; const c = getComputedStyle(el);
    return { position: c.position, z: c.zIndex, bg: c.backgroundColor, opacity: c.opacity,
      visibility: c.visibility, display: c.display, pe: c.pointerEvents, paddingTop: c.paddingTop,
      top: c.top, overflowY: c.overflowY, radius: c.borderRadius, borderBottom: c.borderBottomColor }; };
  let hits = [], veil = [];
  if (pill && sc) {
    const pr = pill.getBoundingClientRect();
    for (const dy of [2, 10, 20]) {
      const el = document.elementFromPoint(Math.round(pr.x + pr.width / 2), Math.round(pr.bottom + dy));
      hits.push({ dy, el: desc(el), inContent: !!(el && first && (first.contains(el) || el.contains(first) || (sc && sc.contains(el)))) });
    }
    // 遮罩扫描：绝对/固定定位 + pointer-events:none + 非透明底 + 横向 ≥ 面板宽 50%
    const panelEl = panel || sc;
    const box = panelEl.getBoundingClientRect();
    const walk = (root) => {
      const all = root.querySelectorAll('*');
      for (const el of all) {
        if (el.closest('.scroll-under-glass')) continue;   // 既有裙边族（作者裁定保留）
        const c = getComputedStyle(el);
        if (c.position !== 'absolute' && c.position !== 'fixed') continue;
        if (c.pointerEvents !== 'none') continue;
        const bg = c.backgroundColor || '';
        const m = bg.match(/rgba?\\(([^)]+)\\)/);
        let alpha = 1;
        if (m) { const parts = m[1].split(',').map((s) => parseFloat(s)); alpha = parts.length > 3 ? parts[3] : 1; }
        if (!(alpha > 0.02)) continue;
        const r = el.getBoundingClientRect();
        if (r.width < box.width * 0.5 || r.height < 12) continue;
        if (r.bottom < box.top || r.top > box.bottom) continue;
        veil.push(desc(el) + ' [' + bg + ' z=' + c.zIndex + ' ' + Math.round(r.width) + 'x' + Math.round(r.height) + ']');
      }
    };
    // 扫描域 = 本面板子树（app 级既有 scrim/deck 层不在面板内，不参与判定）
    walk(panelEl);
  }
  const sr = sc ? sc.getBoundingClientRect() : null;
  const fr = first ? first.getBoundingClientRect() : null;
  const tp = q(cfg.tabPill || '');
  let tabHit = null;
  if (tp) {
    const r = tp.getBoundingClientRect();
    tabHit = desc(document.elementFromPoint(Math.round(r.x + r.width / 2), Math.round(r.y + r.height / 2)));
  }
  return {
    tabPill: R(tp), tabHit,
    pill: R(pill), back: R(back), scroller: R(sc), first: R(first),
    pillStyle: styleOf(pill), backStyle: styleOf(back), scStyle: styleOf(sc), firstStyle: styleOf(first),
    glass: glassOf(pill), pillZ: pcs ? pcs.zIndex : null, pillPos: pcs ? pcs.position : null,
    // 裁剪感知可见顶：内容真正被画出来的最高点
    visibleTop: (fr && sr) ? +Math.max(fr.top, sr.top).toFixed(1) : null,
    scrollTop: sc ? +sc.scrollTop.toFixed(1) : null,
    scrollHeight: sc ? sc.scrollHeight : null, clientHeight: sc ? sc.clientHeight : null,
    hits, veil,
  };
})()`;
const measure = (page, cfg) => page.evaluate(`${PROBE}(${JSON.stringify(cfg)})`);

const f = (r) => (r ? `(${r.x},${r.y} ${r.w}×${r.h})` : 'null');
const hit = (a, b) => !!a && !!b && a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom;

/** 逐面板跑 A1/A2/B1/B2/C1。cfg: {name, pill, back, scroller, first, panel, notes} */
async function runPanel(page, cfg, shotPrefix) {
  const tag = `${cfg.name}`;
  const m0 = await measure(page, cfg);
  if (!m0.first || !m0.pill) {
    ok(`${tag} A1 首内容块与胶囊均在 DOM`, false, `pill=${!!m0.pill} first=${!!m0.first}`);
    return;
  }
  // A1 初始：不遮挡 + 元素不消失
  const a1 = m0.first.top >= m0.pill.bottom - 0.5
    && !(cfg.back && hit(m0.first, m0.back))
    && m0.first.h > 0 && m0.firstStyle.visibility !== 'hidden'
    && m0.firstStyle.display !== 'none' && Number(m0.firstStyle.opacity) > 0;
  ok(`${tag} A1 初始不遮挡：首块 ${f(m0.first)} ≥ 胶囊底 ${m0.pill.bottom}`
    + (cfg.back ? ` / 与返回键 ${f(m0.back)} 零相交` : '')
    + ` + 元素在场(h=${m0.first.h} vis=${m0.firstStyle.visibility} op=${m0.firstStyle.opacity})`,
    a1, a1 ? '' : `first.top=${m0.first.top} pill.bottom=${m0.pill.bottom}`);
  await sleep(180);
  const m1 = await measure(page, cfg);
  const near = (a, b) => !!a && !!b && Math.abs(a.x - b.x) <= 1 && Math.abs(a.y - b.y) <= 1
    && Math.abs(a.w - b.w) <= 1 && Math.abs(a.h - b.h) <= 1;
  const a2 = near(m0.pill, m1.pill) && m0.pill.w > 0 && m0.pillStyle.visibility !== 'hidden'
    && (!cfg.back || (near(m0.back, m1.back) && m0.back.w > 0 && m0.backStyle.visibility !== 'hidden'));
  ok(`${tag} A2 胶囊/返回键在场且同一 rect ±1（胶囊 w=${m0.pill.w}${cfg.back ? ` 返回键 w=${m0.back && m0.back.w}` : ''}）`,
    a2, a2 ? '' : `pill0=${f(m0.pill)} pill1=${f(m1.pill)} back0=${f(m0.back)} back1=${f(m1.back)}`);

  // 截图（初始态）
  await page.screenshot({ path: join(SHOTS, `${shotPrefix}-a1-initial.png`) });

  // B1/B2：滚动 200
  const scrollable = m0.scrollHeight > m0.clientHeight + 40;
  if (!cfg.noScroll) {
    ok(`${tag} B0 滚动容器可滚（夹具足够长）`, scrollable, `sh=${m0.scrollHeight} ch=${m0.clientHeight}`);
  }
  await page.evaluate((c) => {
    const sc = document.querySelector(c.scroller);
    if (sc) sc.scrollTop = 200;
  }, cfg);
  await sleep(320);
  const m2 = await measure(page, cfg);
  await page.screenshot({ path: join(SHOTS, `${shotPrefix}-b1-scrolled200.png`) });
  if (cfg.noScroll) {
    // 地图面：无纵向滚动轴 ⇒ 不要求「穿过」，要求「初态不压」（A1 已钉）+ 条为浮层。
    ok(`${tag} B1 滚动腿 N/A（${cfg.noScroll}）—— 以 A1/B1g/B2 为准`, true, `scrollTop=${m2.scrollTop}`);
  } else {
    const b1 = m2.visibleTop < m2.pill.bottom - 0.5;
    ok(`${tag} B1 scrollTop=200 内容从胶囊下穿过：裁剪感知可见顶 ${m2.visibleTop} < 胶囊底 ${m2.pill.bottom}`,
      b1, b1 ? '' : `visibleTop=${m2.visibleTop} pill.bottom=${m2.pill.bottom} scrollTop=${m2.scrollTop}`);
  }
  const glassOk = m2.glass !== 'none';
  ok(`${tag} B1g 胶囊玻璃本体在（backdrop-filter = ${m2.glass}）`, glassOk, `glass=${m2.glass}`);
  const b2a = m2.hits.length > 0 && m2.hits.every((h) => h.inContent);
  const b2b = m2.veil.length === 0;
  ok(`${tag} B2 胶囊下带命中内容本身（非遮罩）+ 零新覆盖层：${m2.hits.map((h) => `+${h.dy}:${h.el}`).join(' ')}`,
    b2a && b2b, (b2a ? '' : 'hit 未落在内容子树；') + (b2b ? '' : 'veil=' + JSON.stringify(m2.veil)));
  if (cfg.tabPill) {
    // 铁律「元素不许为了腾地方而消失」的机械腿：面板自带返回条不得盖掉 canvas 页签胶囊
    // （两件都是 z 30 浮层 —— 同带叠印就会把胶囊整条盖没，本块第一版实测踩过）。
    const covered = hit(m2.pill, m2.tabPill) && !(m2.tabHit || '').includes('canvas-header');
    ok(`${tag} A3 页签胶囊不被面板返回条遮挡：条 ${f(m2.pill)} 与胶囊 ${f(m2.tabPill)} 零相交`
      + `（胶囊中心命中=${m2.tabHit}）`, !covered,
      covered ? `条与胶囊相交且中心命中=${m2.tabHit}` : '');
  }
  ok(`${tag} C1 回归护栏：本面板带内零「非透明 + pointer-events:none + ≥50% 面宽」新增覆盖层`,
    m2.veil.length === 0, JSON.stringify(m2.veil));
  return m2;
}

/* ════════ 桌面腿（双主题）════════ */
for (const theme of ['dark', 'light']) {
  const { page, errors } = await boot({ colorScheme: theme });
  const T = (s) => `${theme.toUpperCase()} ${s}`;

  // ── REF：canvas md 查看面（参照实现，只读钉机制活变量）──
  await page.evaluate(({ absPath, content }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType: 'markdown', title: 'underpass.md', content, absPath, pinned: true },
    }));
  }, { absPath: MD_PATH, content: MD_CONTENT });
  await page.waitForSelector('.canvas-md-viewer', { timeout: 20000 });
  await sleep(700);
  const ref = await measure(page, {
    pill: '#canvas-panel .canvas-header',
    scroller: '#canvas-content .canvas-tab-pane.active .canvas-md-scroll',
    first: '#canvas-content .canvas-tab-pane.active .canvas-md-viewer',
  });
  ok(`REF canvas-md 机制活变量：胶囊 ${ref.pillPos} z=${ref.pillZ} 玻璃=${ref.glass}；滚动容器 top=${ref.scStyle.top} 让位 padding-top=${ref.scStyle.paddingTop}（padding 让位而非遮罩）`,
    ref.pillPos === 'absolute' && ref.glass !== 'none' && parseFloat(ref.scStyle.paddingTop) > 0
    && ref.scStyle.top === '0px', JSON.stringify({ pos: ref.pillPos, z: ref.pillZ, glass: ref.glass, top: ref.scStyle.top, pad: ref.scStyle.paddingTop }));

  // ── P1 项目列表面板 ──
  await page.click('#projects-btn');
  await page.waitForSelector('.canvas-tab-pane[data-tab-id="projects"] .project-card', { timeout: 20000 });
  await sleep(700);
  await runPanel(page, {
    name: T('P1 projects-list'),
    panel: '.canvas-tab-pane[data-tab-id="projects"]',
    pill: '#canvas-panel .canvas-header',
    // 桌面画板胶囊无返回键（返回由页签/侧栏承担；#canvas-close-btn 桌面 display:none
    // = 既有契约，修前实测 0×0）⇒ A2 只钉胶囊本体。
    scroller: '.canvas-tab-pane[data-tab-id="projects"] .team-scroll',
    first: '.canvas-tab-pane[data-tab-id="projects"] .project-card',
  }, `${theme}-p1-projects-list`);

  // ── P2 项目全景视图（项目卡标题点按 → 同一 pane 就地换视图）──
  await page.click('.project-card[data-project="proj-0"] .team-card-title');
  await page.waitForSelector('.canvas-tab-pane[data-tab-id="projects"] .delegate-chain-view-body', { timeout: 20000 });
  await sleep(1100);
  await runPanel(page, {
    name: T('P2 projects-panorama'),
    panel: '.canvas-tab-pane[data-tab-id="projects"]',
    tabPill: '#canvas-panel .canvas-header',
    pill: '.canvas-tab-pane[data-tab-id="projects"] .flowmap-nav-bar',
    back: '.canvas-tab-pane[data-tab-id="projects"] .flowmap-back-btn',
    scroller: '.canvas-tab-pane[data-tab-id="projects"] .delegate-chain-view-body',
    first: '.canvas-tab-pane[data-tab-id="projects"] .delegate-chain-view-body > *:first-child',
  }, `${theme}-p2-panorama`);

  // ── P3 委托链 per-task 视图（地图面：滚动腿 N/A，A1/A2/B2 为准）──
  await page.evaluate(async () => {
    const m = await import('/js/delegateChainView.js');
    await m.openDelegateChainTask('100', { taskNo: '100', title: '委托任务 1', status: 'running' });
  });
  await page.waitForSelector('.canvas-tab-pane[data-tab-id="projects"] .delegate-chain-view-body.delegate-mode-task', { timeout: 20000 });
  await sleep(1200);
  await runPanel(page, {
    name: T('P3 projects-chain-task'),
    panel: '.canvas-tab-pane[data-tab-id="projects"]',
    tabPill: '#canvas-panel .canvas-header',
    pill: '.canvas-tab-pane[data-tab-id="projects"] .flowmap-nav-bar',
    back: '.canvas-tab-pane[data-tab-id="projects"] .flowmap-back-btn',
    scroller: '.canvas-tab-pane[data-tab-id="projects"] .delegate-chain-view-body',
    first: '.canvas-tab-pane[data-tab-id="projects"] .delegate-chain-view-body > *:first-child',
    noScroll: '委托链 per-task 面 = pan/zoom 地图，无纵向滚动轴',
  }, `${theme}-p3-chain-task`);

  ok(`${T('R0')} 主文档零 JS 错误`, errors.length === 0, JSON.stringify(errors.slice(0, 3)));
  await page.context().close();
}

await browser.close();
server.close();
console.log(`\n── 汇总：PASS ${pass} / FAIL ${fail}`);
if (fail > 0) process.exit(1);

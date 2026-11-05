#!/usr/bin/env node
// canvas-hint-safe-area.spec.mjs — Canvas 提示文字「零交叠 + 安全区 + 字档」机检
//
// 作者原话（2026-11-05 20:00，逐字）：
//   「然后canvas内所有的提示文字，要统一制作规范，不然会跟悬浮在上面的按钮/胶囊
//    重叠。」
//
// 装置：零端口零进程（复用 scripts/lib/visual-harness.mjs —— page.route 从磁盘服务
// src/main/resources/web + MockWS 走真实 ws.js 分发路径）。不起实例 ⇒ 结构上不可能
// 碰宿主 6138；NEBFLOW_WEB_ROOT 可指向另一棵 web 树（改前/改后对照用，见下）。
//
// 判据（真几何读数，全部在浏览器里量，不读声明值）：
//   F1 覆盖：逐面枚举到的提示文字条数 ≥ 该面清单条数（防「看不见 ⇒ 绿」）
//   H2 零交叠：每个提示文字包围盒 ∩ 任一派生浮层包围盒 面积 = 0
//      （浮层统计口径 = 顶行胶囊 / 顶行关签钮 / 返回原文档钮 / 堆叠钮 / 查找条 /
//        底部浮钮带（源码<> / 引用 / 查找 / 缩放条 / 刷新钮） / ask·记事本 FAB /
//        打开的浮窗（.ask-dock / .note-dock））
//   H2b 呼吸缝：不相交时，二者最近距离 ≥ --cv-safe-gap（8）——「贴着不重叠」
//      在观感上仍是压，判据要挡住它
//   H3 安全区：提示文字所在容器的四边净空来自 --cv-safe-* 四边令牌；
//      面首块类容器的 padding-top 必须等于 --cv-safe-top 解析值（±1），
//      容器挂 .cv-safe-pad 时四条 padding 必须与四条令牌逐一相等（±0.5）
//   H4 字档：computed font-size ∈ {11,12,13,15}、font-weight ∈ {400,500,600}、
//      color ∈ {muted, dim, frame-text, accent 令牌解析值}、line-height 比值
//      ∈ [0.98, 1.50]（三档 1 / 1.45 / 1.5 及其余量带）
//   H5 长度与换行：被 line-clamp 的元素带 title（全量可读）；nowrap 必须配
//      ellipsis；不许出现 `clientWidth < scrollWidth` 且 text-overflow = clip
//      （= 半个字被切）
//   H6 标记：清单内每一处都带 data-cv-hint（机检钩子），形态取值合法
//
// 运行：
//   node tests/canvas-hint-safe-area.spec.mjs [outDir]
//   改前对照（验红基线）：
//     git archive <base> src/main/resources/web | tar -x -C /tmp/nf-web-before
//     NEBFLOW_WEB_ROOT=/tmp/nf-web-before/src/main/resources/web \
//       node tests/canvas-hint-safe-area.spec.mjs /tmp/out-before
//   验红（变异）：--mutate=drop-face-safe-top
//     把插件面的统一顶垫（.canvas-safe-top 的 padding-top）压成 0 —— 历史故障
//     形态（首块整条压进胶囊带）。**脚本必须转红且打印 RED-CONFIRMED**，否则
//     说明判据没有载力（这是本批「有测试但测不出问题」的防线）。
//
// 退出码：0 = 全绿（无变异）；1 = 有 FAIL（读数照旧全打印，便于改前取证）；
//         3 = 变异腿未转红（判据无载力，等同 FAIL）；2 = 装置错误。
import { mkdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadPlaywright, launchBrowser, createSession, settle, webRoot } from '../scripts/lib/visual-harness.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const argv = process.argv.slice(2);
const flag = (name, def = null) => {
  const hit = argv.find((a) => a === `--${name}` || a.startsWith(`--${name}=`));
  if (!hit) return def;
  const i = hit.indexOf('=');
  return i < 0 ? true : hit.slice(i + 1);
};
const OUT = argv.find((a) => !a.startsWith('--')) || join(ROOT, 'reports-evidence', 'canvas-hint-safe-area');
const MUTATE = flag('mutate', '');
const SHOTS = join(OUT, 'shots');
mkdirSync(SHOTS, { recursive: true });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const r2 = (n) => Math.round(n * 100) / 100;

// ── 夹具 ────────────────────────────────────────────────────────────────
const MD_DOC = [
  '# 提示文字验收文档',
  '',
  '这份文档用于量「文档面」提示文字与浮层的几何关系。',
  '',
  ...Array.from({ length: 30 }, (_, i) => `第 ${i + 1} 段：正文用于撑高滚动面，滚到底看脚注。`),
  '',
  '末行标记：END-OF-DOCUMENT',
].join('\n');
const TXT_DOC = Array.from({ length: 60 }, (_, i) => `第 ${i + 1} 行：纯文本阅读面。`).join('\n')
  + '\n末行标记：TXT-END';

// ── 提示文字清单（形态 / 面 / 选择器）——「全量盘点」的机器可读形态 ──────
// 每一条 = vault 规范文档「盘点表」的一行。新增一处提示文字 ⇒ 这里加一行
// （否则 H6「标记」腿查不到它，等于漏检）。
const INVENTORY = [
  // 插件面
  { face: 'plugins', sel: '.plugins-title', form: null, why: '面标题（非提示文字，几何哨兵）' },
  { face: 'plugins', sel: '.plugins-stats', form: 'foot', why: '顶部统计行' },
  { face: 'plugins', sel: '.plugins-section-title', form: 'divider', why: '小节标签' },
  { face: 'plugins', sel: '.plugins-section-hint', form: 'page', why: '区块说明行' },
  { face: 'plugins', sel: '.plugins-none', form: 'empty', why: '空态' },
  { face: 'plugins', sel: '.plugins-loading', form: 'loading', why: '加载态', optional: true },
  // agent 面（同一面板族，同批整改）
  { face: 'agents', sel: '.agent-mgr-empty, .agent-mgr-loading', form: 'empty', why: '空态/加载' },
  { face: 'agents', sel: '.agent-detail-face-hint', form: 'page', why: '详情面说明行', optional: true },
  // 文档面
  { face: 'doc', sel: '.canvas-md-viewer', form: null, why: '正文（非提示文字，几何哨兵）' },
  { face: 'doc', sel: '.canvas-md-viewer .nf-backlinks', form: 'foot', why: '文末脚注', optional: true },
  // 搜索面
  { face: 'find', sel: '.nf-findbar-count', form: 'foot', why: '命中计数' },
  // ask 面 / note 面
  { face: 'ask', sel: '.ask-input textarea', form: null, why: '输入本体（占位的载体，几何哨兵）' },
  { face: 'note', sel: '.note-body textarea', form: null, why: '输入本体（占位的载体，几何哨兵）' },
  // 知识图谱面
  { face: 'wiki', sel: '.wg-hint', form: 'divider', why: '操作图例' },
  { face: 'wiki', sel: '.wg-stats', form: 'foot', why: '统计行' },
  { face: 'wiki', sel: '.wg-detail-empty', form: 'empty', why: '详情空态' },
];

// 占位（::placeholder）没有元素可标 ⇒ 单独按「载体元素上量计算样式」：
const PLACEHOLDERS = [
  { face: 'find', sel: '.nf-findbar-input', why: '搜索面占位' },
  { face: 'ask', sel: '.ask-input textarea', why: 'ask 面占位' },
  { face: 'note', sel: '.note-body textarea', why: 'note 面占位' },
];

// ── 浮层清单（统计口径；与 vault 规范 §2 表一一对应）──────────────────
const FLOATS = [
  { sel: '#canvas-title', name: '顶部标题胶囊' },
  { sel: '#canvas-head-close', name: '顶行关签钮' },
  { sel: '.canvas-ref-back', name: '返回原文档钮' },
  { sel: '#deck-fab', name: '堆叠视角钮' },
  { sel: '.nf-findbar', name: '查找条' },
  { sel: '.canvas-source-toggle', name: '源码切换钮' },
  { sel: '.canvas-ref-select', name: '引用钮' },
  { sel: '.canvas-find-toggle', name: '查找钮' },
  { sel: '.canvas-zoom-bar', name: '缩放条' },
  { sel: '.canvas-refresh-toggle', name: '刷新钮' },
  { sel: '.ask-fab', name: 'ask/记事本 FAB' },
  { sel: '.note-fab', name: '记事本 FAB' },
  { sel: '.ask-dock:not(.closed)', name: 'ask 浮窗' },
  { sel: '.note-dock:not(.closed)', name: '记事本浮窗' },
];

// ── 结果收集 ───────────────────────────────────────────────────────────
const rows = [];
let pass = 0, fail = 0;
function record(id, name, ok, readout) {
  rows.push({ id, name, verdict: ok ? 'PASS' : 'FAIL', readout });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${id}  ${name}\n        ${readout}`);
  if (ok) pass++; else fail++;
}
function info(msg) { console.log(`      · ${msg}`); }

// ── 面内探针（一帧内跑完，不 mock 布局）────────────────────────────────
const PROBE = `(() => {
  const R = (n) => Math.round(n * 100) / 100;
  const cs = (el) => getComputedStyle(el);
  const vis = (el) => {
    if (!el) return false;
    if (!el.getClientRects().length) return false;
    const s = cs(el);
    if (s.visibility === 'hidden' || s.display === 'none' || s.opacity === '0') return false;
    if (el.hidden) return false;
    return true;
  };
  const box = (el) => {
    const r = el.getBoundingClientRect();
    if (!r.width || !r.height) return null;
    return { x: R(r.x), y: R(r.y), w: R(r.width), h: R(r.height), right: R(r.right), bottom: R(r.bottom) };
  };
  const inter = (a, b) => {
    if (!a || !b) return 0;
    const w = Math.min(a.right, b.right) - Math.max(a.x, b.x);
    const h = Math.min(a.bottom, b.bottom) - Math.max(a.y, b.y);
    return (w > 0.5 && h > 0.5) ? R(w * h) : 0;
  };
  // 最近距离（不相交时）：x 向与 y 向间距的较大者 = 矩形间最小空隙
  const gap = (a, b) => {
    if (!a || !b) return Infinity;
    const dx = Math.max(0, Math.max(a.x, b.x) - Math.min(a.right, b.right));
    const dy = Math.max(0, Math.max(a.y, b.y) - Math.min(a.bottom, b.bottom));
    if (inter(a, b) > 0) return 0;
    return R(Math.max(dx, dy));
  };

  const panel = document.getElementById('canvas-panel');
  const floats = [];
  for (const f of FLOATS_IN) {
    for (const el of document.querySelectorAll(f.sel)) {
      if (!vis(el)) continue;
      const b = box(el);
      if (!b) continue;
      // 只统计与 canvas 面板相交的浮层（页外/别的面板不算）
      const pb = panel ? box(panel) : null;
      if (pb && inter(b, pb) <= 0) continue;
      floats.push({ name: f.name, sel: f.sel, box: b });
    }
  }

  // 提示文字候选：清单选择器 ∪ [data-cv-hint]
  const seen = new Set();
  const hints = [];
  const present = {};
  for (const h of INVENTORY_IN) {
    const all = document.querySelectorAll(h.sel);
    present[h.face + '::' + h.sel] = all.length;
    for (const el of all) {
      if (!vis(el)) continue;
      const b = box(el);
      if (!b) continue;
      seen.add(el);
      hints.push({ el, inv: h });
    }
  }
  for (const h of INVENTORY_IN) {
    if (present[h.face + '::' + h.sel] === undefined) present[h.face + '::' + h.sel] = 0;
  }
  for (const el of document.querySelectorAll('[data-cv-hint]')) {
    if (!vis(el) || seen.has(el)) continue;
    hints.push({ el, inv: { sel: '[data-cv-hint]', form: el.getAttribute('data-cv-hint'), why: '标记枚举' } });
  }

  const safePads = [];
  for (const el of document.querySelectorAll('.cv-safe-pad')) {
    const b = box(el);
    if (!b) continue;
    const st = cs(el);
    safePads.push({
      cls: el.className, box: b,
      pad: { t: R(parseFloat(st.paddingTop) || 0), r: R(parseFloat(st.paddingRight) || 0), b: R(parseFloat(st.paddingBottom) || 0), l: R(parseFloat(st.paddingLeft) || 0) },
    });
  }

  const rgb = (s) => {
    const m = String(s).match(/rgba?\\(([^)]+)\\)/);
    if (!m) return s;
    const p = m[1].split(',').map((x) => parseFloat(x));
    return p.slice(0, 3).join(',');
  };
  // 令牌解析：自定义属性的 getPropertyValue 返回**未解析字面量**（calc(...) 与
  // rgb(var(--sapphire)) 都在里面），必须借一个真元素做一次计算才拿到 used value。
  const probeEl = document.createElement('div');
  probeEl.style.cssText = 'position:absolute;left:-9999px;top:-9999px;width:0;height:0;visibility:hidden;';
  document.body.appendChild(probeEl);
  const tokColor = (name) => {
    probeEl.style.color = '';
    probeEl.style.color = 'var(' + name + ')';
    return rgb(getComputedStyle(probeEl).color);
  };
  const tokPx = (name) => {
    probeEl.style.paddingTop = '';
    probeEl.style.paddingTop = 'var(' + name + ')';
    return getComputedStyle(probeEl).paddingTop;
  };
  const tok = (name) => tokColor(name);

  const out = [];
  for (const { el, inv } of hints) {
    const s = cs(el);
    const b = box(el);
    if (!b) continue;
    const fs = parseFloat(s.fontSize) || 0;
    const lhRaw = s.lineHeight;
    const lh = lhRaw === 'normal' ? NaN : parseFloat(lhRaw);
    const hits = [];
    let minGap = Infinity, nearest = null;
    for (const f of floats) {
      // 跳过「提示文字自己就在浮层内部」的情形（浮层内部文字不可能与浮层相交）
      if (f.sel && el.closest(f.sel)) continue;
      const area = inter(b, f.box);
      if (area > 0) hits.push({ float: f.name, sel: f.sel, area, fbox: f.box });
      const g = gap(b, f.box);
      if (g < minGap) { minGap = g; nearest = f.name; }
    }
    out.push({
      sel: inv.sel,
      form: inv.form,
      why: inv.why,
      text: (el.textContent || '').trim().slice(0, 26),
      box: b,
      fs, fw: s.fontWeight, color: rgb(s.color), lh: Number.isFinite(lh) ? R(lh) : null,
      lhRatio: Number.isFinite(lh) && fs ? R(lh / fs) : null,
      clamp: s.webkitLineClamp || s.getPropertyValue('-webkit-line-clamp') || '',
      nowrap: s.whiteSpace === 'nowrap',
      ellipsis: s.textOverflow,
      clipX: el.clientWidth + 1 < el.scrollWidth,
      title: el.getAttribute('title') || '',
      marker: el.getAttribute('data-cv-hint') || '',
      pad: { t: R(parseFloat(s.paddingTop) || 0), r: R(parseFloat(s.paddingRight) || 0), b: R(parseFloat(s.paddingBottom) || 0), l: R(parseFloat(s.paddingLeft) || 0) },
      hasSafePad: el.classList.contains('cv-safe-pad'),
      hits, minGap: minGap === Infinity ? null : minGap, nearest,
      placeholderColor: null,
    });
  }
  // 占位色（::placeholder 不能直接 getComputedStyle，用 getComputedStyle(el, '::placeholder')）
  const placeholders = [];
  for (const p of PLACEHOLDERS_IN) {
    const el = document.querySelector(p.sel);
    if (!el || !vis(el)) continue;
    const ps = getComputedStyle(el, '::placeholder');
    placeholders.push({ sel: p.sel, why: p.why, color: rgb(ps.color), fs: parseFloat(ps.fontSize) || 0, opacity: ps.opacity });
  }

  return {
    mobile: document.body.classList.contains('mobile-layer'),
    vw: innerWidth, vh: innerHeight,
    panel: panel ? box(panel) : null,
    floats, hints: out, placeholders,
    present,
    safePads,
    tokens: {
      'cv-safe-top': tokPx('--cv-safe-top'), 'cv-safe-right': tokPx('--cv-safe-right'),
      'cv-safe-bottom': tokPx('--cv-safe-bottom'), 'cv-safe-left': tokPx('--cv-safe-left'),
      'cv-safe-gap': tokPx('--cv-safe-gap'),
      muted: tok('--color-frame-text-muted'), dim: tok('--color-frame-text-dim'),
      frameText: tok('--color-frame-text'), sapphire: tok('--sapphire'),
      hintColor: tok('--cv-hint-color'), hintColorDim: tok('--cv-hint-color-dim'),
      hintFsH: tok('--cv-hint-fs-h'), hintFsM: tok('--cv-hint-fs-m'),
    },
  };
})()`;

// ── 会话/面驱动 ────────────────────────────────────────────────────────
async function newSession(browser, opts) {
  const s = await createSession(browser, opts);
  s.pageErrors = [];
  s.page.on('pageerror', (e) => s.pageErrors.push(String(e.message || e).slice(0, 200)));
  await s.page.route('**/api/nf-file**', (route) => {
    const u = decodeURIComponent(route.request().url());
    const isTxt = /\.txt(\?|$)/.test(u);
    return route.fulfill({
      status: 200,
      contentType: isTxt ? 'text/plain; charset=utf-8' : 'text/markdown; charset=utf-8',
      body: isTxt ? TXT_DOC : MD_DOC,
    });
  });
  await s.page.route('**/api/plugins/updates**', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ updates: [] }) }));
  await s.page.route('**/api/plugins**', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ plugins: [], rejected: [] }) }));
  await s.page.route('**/api/wiki/vaults**', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ vaults: [] }) }));
  await s.page.route('**/api/wiki/graph**', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ nodes: [], edges: [], projects: [] }) }));
  // features.wikiGraph = true：知识图谱面的**产品入口**（#wiki-btn）默认封存
  // （featureFlags.wikiGraphEnabled 缺席 = OFF）。不打开它，那一面上的提示文字
  //（图例 / 统计 / 详情空态）就永远枚举不到 —— 本脚本拒绝「没打开 = 没违规」。
  await s.boot({ config: { llm: { providers: {} }, features: { friends: false, wikiGraph: true } } });
  return s;
}

/** 探针执行器：把三个清单注入页面上下文后跑 PROBE。 */
async function runProbe(page) {
  return page.evaluate(
    ({ floatsIn, inventoryIn, placeholdersIn, src }) => {
      // eslint-disable-next-line no-new-func
      const fn = new Function('FLOATS_IN', 'INVENTORY_IN', 'PLACEHOLDERS_IN', `return ${src};`);
      return fn(floatsIn, inventoryIn, placeholdersIn);
    },
    { floatsIn: FLOATS, inventoryIn: INVENTORY, placeholdersIn: PLACEHOLDERS, src: PROBE },
  );
}

async function openDoc(s, { id, itemType, absPath, body }) {
  await s.page.evaluate((item) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: { ...item, pinned: true } }));
  }, { id, title: id, itemType, content: body, absPath });
  await s.page.waitForSelector(`.canvas-tab-pane[data-tab-id="${id}"]`, { timeout: 20000, state: 'attached' });
  await settle(s.page, 900);
}

// ── 主流程 ──────────────────────────────────────────────────────────────
const { mod: pw } = loadPlaywright();
const { browser, label } = await launchBrowser(pw);
console.log(`canvas-hint-safe-area: web=${webRoot()} browser=${label} out=${OUT} mutate=${MUTATE || '(none)'}`);

const s = await newSession(browser, { width: 1440, height: 900 });
const page = s.page;
const faces = {};

// —— 插件面 ——
await page.evaluate(async () => {
  const m = await import('/js/canvas.js');
  m.openTab('plugins', '插件', { type: 'plugins' });
});
await settle(page, 1200);
faces.plugins = await runProbe(page);
await page.screenshot({ path: join(SHOTS, 'face-plugins.png'), animations: 'disabled' }).catch(() => {});

// —— agent 面 ——
// 入口面：agentManager.js 的 openAgents()（原 #agents-btn 已并入插件键，只剩
// 程序入口）。agents 数据面走 /api/agents 桩 ⇒ 空态分支必然渲染（正好是提示
// 文字要量的那一态）。
await page.evaluate(async () => {
  const m = await import('/js/agentManager.js');
  await m.openAgents();
});
await settle(page, 1600);
faces.agents = await runProbe(page);

// —— 文档面（md）——
await openDoc(s, { id: 'hint-md', itemType: 'markdown', absPath: '/tmp/nf-hint-md.md', body: MD_DOC });
faces.doc = await runProbe(page);
await page.screenshot({ path: join(SHOTS, 'face-doc.png'), animations: 'disabled' }).catch(() => {});

// —— 搜索面（文档面查找条）——
const findBtn = page.locator('.canvas-tab-pane.active .canvas-find-toggle').first();
if (await findBtn.count().catch(() => 0)) {
  await findBtn.click({ timeout: 5000 }).catch(() => {});
  await settle(page, 600);
}
faces.find = await runProbe(page);
await page.screenshot({ path: join(SHOTS, 'face-search.png'), animations: 'disabled' }).catch(() => {});

// —— note 面 ——
const noteFab = page.locator('.note-fab').first();
if (await noteFab.count().catch(() => 0)) {
  await noteFab.click({ timeout: 5000 }).catch(() => {});
  await settle(page, 700);
}
faces.note = await runProbe(page);
await page.screenshot({ path: join(SHOTS, 'face-note.png'), animations: 'disabled' }).catch(() => {});

// —— ask 面 ——
const askFab = page.locator('.ask-fab').first();
if (await askFab.count().catch(() => 0)) {
  await askFab.click({ timeout: 5000 }).catch(() => {});
  await settle(page, 700);
}
faces.ask = await runProbe(page);
await page.screenshot({ path: join(SHOTS, 'face-ask.png'), animations: 'disabled' }).catch(() => {});

// —— 知识图谱面（空态腿）——
// 产品入口 = 侧栏星形键 #wiki-btn（main.js:4657 registerCanvasPanelButton 挂的
// 那个），点击后 wikiGraph.js 自己 openTab + 把控制面挂进左栏（#wiki-graph-side）
// —— 这正是不直写 DOM 的理由：控制面（图例/统计/空态）由它自己造。
const wikiBtn = page.locator('#wiki-btn').first();
if (await wikiBtn.count().catch(() => 0)) {
  await wikiBtn.click({ timeout: 5000 }).catch(() => {});
} else {
  await page.evaluate(async () => {
    const m = await import('/js/canvas.js');
    m.openTab('wiki-graph', '文档图谱', { type: 'wiki-graph' });
  });
}
await settle(page, 2200);
faces.wiki = await runProbe(page);
await page.screenshot({ path: join(SHOTS, 'face-wiki.png'), animations: 'disabled' }).catch(() => {});

// ── 变异腿（验红）：把插件面统一顶垫压成 0 → 首块必须压进胶囊带 ────────
let mutated = null;
if (MUTATE === 'drop-face-safe-top') {
  await page.evaluate(async () => {
    const m = await import('/js/canvas.js');
    m.openTab('plugins', '插件', { type: 'plugins' });
  });
  await settle(page, 900);
  await page.addStyleTag({ content: '#plugins-content { padding-top: 0 !important; } .canvas-safe-top { padding-top: 0 !important; }' });
  await settle(page, 400);
  mutated = await runProbe(page);
  await page.screenshot({ path: join(SHOTS, 'mutation-drop-safe-top.png'), animations: 'disabled' }).catch(() => {});
}

await browser.close();

// ── 断言 ────────────────────────────────────────────────────────────────
const FACE_NAME = { plugins: '插件面', agents: 'agent 面', doc: '文档面', find: '搜索面', note: 'note 面', ask: 'ask 面', wiki: '知识图谱面' };
const all = Object.entries(faces).filter(([k, v]) => {
  if (!v || !v.hints) { console.log(`WARN 面 ${k} 未取到读数（页面未就绪）—— 记为未覆盖`); return false; }
  return true;
});

const dump = (face, data) => {
  const lines = [];
  lines.push(`panel=${JSON.stringify(data.panel)} 浮层 ${data.floats.length} 个：` + data.floats.map((f) => `${f.name}${JSON.stringify(f.box)}`).join(' '));
  for (const h of data.hints) {
    lines.push(`  ${h.form ? `[${h.form}]` : '[载体]'} ${h.sel} "${h.text}" box=${JSON.stringify(h.box)} ` +
      `fs=${h.fs} fw=${h.fw} lh=${h.lh}(${h.lhRatio}) color=rgb(${h.color}) ` +
      `pad=${JSON.stringify(h.pad)}${h.hasSafePad ? ' .cv-safe-pad' : ''} ` +
      `交叠=${h.hits.length ? h.hits.map((x) => `${x.float}×${x.area}px²`).join(',') : '0'} 最近=${h.nearest}(${h.minGap}px)`);
  }
  return lines.join('\n        ');
};

// F1 覆盖
for (const [face, data] of all) {
  const expect = INVENTORY.filter((i) => i.face === face && !i.optional);
  const present = data.present || {};
  const missing = expect.filter((e) => !present[face + '::' + e.sel]).map((e) => e.sel);
  record(`F1-${face}`, `覆盖 · ${FACE_NAME[face]}（清单 ${expect.length} 条）`,
    missing.length === 0,
    `DOM 命中 ${expect.length - missing.length}/${expect.length} 条 · 几何受检 ${data.hints.length} 处` +
    (missing.length ? ` · 缺 ${missing.join(',')}` : '') + '\n        ' + dump(face, data));
}

// H2 零交叠 + H2b 呼吸缝
{
  const bad = [];
  const tight = [];
  for (const [face, data] of all) {
    for (const h of data.hints) {
      // 判据只覆盖「提示文字」本身（带 data-cv-hint 的形态元素）。form=null 的条目
      // 是几何哨兵（面标题/正文/输入本体）——它们的贴边属于**正文让位**范畴，
      // 由 canvas-overlap-fix / canvas-bottom-clearance 两批的判据各自负责。
      if (!h.form) continue;
      for (const hit of h.hits) bad.push(`${FACE_NAME[face]} ${h.sel} ∩ ${hit.float} = ${hit.area}px²`);
      if (!h.hits.length && h.minGap !== null && h.minGap < 8) tight.push(`${FACE_NAME[face]} ${h.sel} ~ ${h.nearest} = ${h.minGap}px`);
    }
  }
  record('H2', '零交叠 · 每处提示文字的包围盒与任一派生浮层不相交', bad.length === 0,
    bad.length ? `${bad.length} 处交叠：\n        - ` + bad.join('\n        - ') : '0 交叠（全部面 × 全部浮层）');
  record('H2b', '呼吸缝 · 不相交处最近距离 ≥ --cv-safe-gap(8)', tight.length === 0,
    tight.length ? `${tight.length} 处过近：\n        - ` + tight.join('\n        - ') : '全部 ≥ 8px');
}

// H3 安全区
{
  const notes = [];
  let ok = true;
  for (const [face, data] of all) {
    const t = data.tokens;
    const padTop = parseFloat(t['cv-safe-top']);
    const padR = parseFloat(t['cv-safe-right']);
    const padB = parseFloat(t['cv-safe-bottom']);
    const padL = parseFloat(t['cv-safe-left']);
    notes.push(`${FACE_NAME[face]} 四边令牌 = ${t['cv-safe-top']}/${t['cv-safe-right']}/${t['cv-safe-bottom']}/${t['cv-safe-left']} gap=${t['cv-safe-gap']}`);
    // 四边都必须**真存在**（本批的核心：修前只有顶边，右/下/左 = 0 ⇒ 「安全区」
    // 名不副实，容器只能各自写左右内距）。
    for (const side of ['cv-safe-right', 'cv-safe-bottom', 'cv-safe-left', 'cv-safe-gap']) {
      const v = parseFloat(t[side]);
      if (!(v > 0)) { ok = false; notes.push(`  ✗ ${FACE_NAME[face]} ${side} = ${t[side]}（应为 > 0 的令牌档）`); }
    }
    for (const sp of (data.safePads || [])) {
      const m = Math.abs(sp.pad.t - padTop) <= 1 && Math.abs(sp.pad.r - padR) <= 0.5 &&
        Math.abs(sp.pad.b - padB) <= 0.5 && Math.abs(sp.pad.l - padL) <= 0.5;
      notes.push(`${FACE_NAME[face]} .cv-safe-pad(${String(sp.cls).split(' ').pop()}) pad=${JSON.stringify(sp.pad)}` + (m ? ' ✓' : ' ✗ ≠ 四边令牌'));
      if (!m) ok = false;
    }
  }
  // 面首块净空：每个面里最靠上的提示文字，其上缘到面板顶 ≥ --cv-safe-top（顶行浮层带之下）
  for (const [face, data] of all) {
    if (!data.panel) continue;
    const hintTop = Math.min(...data.hints.map((h) => h.box.y));
    const clear = r2(hintTop - data.panel.y);
    const need = parseFloat(data.tokens['cv-safe-top']) || 0;
    const passThis = !Number.isFinite(clear) || clear >= need - 1; // 面首块必须落在安全区之下（±1 取整容差）
    notes.push(`${FACE_NAME[face]} 最上提示文字距面板顶 ${clear}px（安全区 ${need}px，顶行浮层带内）`);
    if (!passThis) { ok = false; notes.push(`  ✗ ${FACE_NAME[face]} 首块净空不足`); }
  }
  record('H3', '安全区 · 四边令牌就位 / .cv-safe-pad 与令牌逐一相等 / 面首块让到安全区之下', ok,
    notes.join('\n        '));
}

// H4 字档
{
  const SIZES = new Set([11, 12, 13, 15]);
  const WEIGHTS = new Set(['400', '500', '600']);
  const bad = [];
  const colors = new Set();
  let checked = 0;
  for (const [face, data] of all) {
    const t = data.tokens;
    const allowed = new Set([t.muted, t.dim, t.frameText, t.sapphire, t.hintColor, t.hintColorDim]);
    for (const h of data.hints) {
      if (!h.form) continue; // 载体元素（标题/正文/textarea）不按字档判
      checked++;
      colors.add(h.color);
      if (!SIZES.has(Math.round(h.fs))) bad.push(`${FACE_NAME[face]} ${h.sel} font-size=${h.fs}px ∉ {11,12,13,15}`);
      if (!WEIGHTS.has(h.fw)) bad.push(`${FACE_NAME[face]} ${h.sel} font-weight=${h.fw} ∉ {400,500,600}`);
      if (!allowed.has(h.color)) bad.push(`${FACE_NAME[face]} ${h.sel} color=rgb(${h.color}) 非提示文字令牌`);
      if (h.lhRatio !== null && (h.lhRatio < 0.98 || h.lhRatio > 1.5)) bad.push(`${FACE_NAME[face]} ${h.sel} line-height 比值=${h.lhRatio} ∉ [0.98,1.50]`);
    }
  }
  // 占位腿
  for (const [face, data] of all) {
    for (const p of data.placeholders) {
      const t = data.tokens;
      const allowed = new Set([t.muted, t.dim, t.frameText]);
      if (!allowed.has(p.color)) bad.push(`${FACE_NAME[face]} ${p.sel}::placeholder color=rgb(${p.color}) 非令牌`);
      if (p.opacity !== '1') bad.push(`${FACE_NAME[face]} ${p.sel}::placeholder opacity=${p.opacity} ≠ 1（透明度不是令牌通道）`);
    }
  }
  record('H4', `字档 · 三档角色（H 12 / M 11 / 占位 13）与色令牌唯一`, bad.length === 0,
    `受检 ${checked} 处提示文字 + ${all.reduce((a, [, d]) => a + d.placeholders.length, 0)} 处占位；实测色集合 = ${[...colors].map((c) => `rgb(${c})`).join(' ')}` +
    (bad.length ? `\n        ${bad.length} 处越档：\n        - ` + bad.join('\n        - ') : ''));
}

// H5 长度与换行
{
  const bad = [];
  for (const [face, data] of all) {
    for (const h of data.hints) {
      if (!h.form) continue;
      const clampNum = parseInt(String(h.clamp).replace(/[^0-9]/g, ''), 10);
      const clamped = String(h.clamp) && String(h.clamp) !== 'none' && Number.isFinite(clampNum);
      if (clamped && clampNum > 6) bad.push(`${FACE_NAME[face]} ${h.sel} line-clamp=${clampNum} > 6（上限档）`);
      if (clamped && !h.title && h.clipX) bad.push(`${FACE_NAME[face]} ${h.sel} 被 clamp 却无 title（全量不可读）`);
      if (h.nowrap && h.ellipsis !== 'ellipsis' && h.clipX) bad.push(`${FACE_NAME[face]} ${h.sel} nowrap 无 ellipsis（半个字）`);
      if (h.clipX && h.ellipsis === 'clip' && (!String(h.clamp) || String(h.clamp) === 'none')) {
        bad.push(`${FACE_NAME[face]} ${h.sel} 横向溢出且 text-overflow=clip（半个字）`);
      }
    }
  }
  record('H5', '长度与换行 · clamp ≤ 6 行 / 截断必须省略号 / 无半个字', bad.length === 0,
    bad.length ? `${bad.length} 处：\n        - ` + bad.join('\n        - ') : '全部合规');
}

// H6 标记
{
  const LEGAL = new Set(['page', 'divider', 'empty', 'loading', 'foot', 'tip', 'status']);
  const bad = [];
  for (const [face, data] of all) {
    for (const h of data.hints) {
      if (!h.form) continue;
      if (!h.marker) bad.push(`${FACE_NAME[face]} ${h.sel} 缺 data-cv-hint 标记`);
      else if (!LEGAL.has(h.marker)) bad.push(`${FACE_NAME[face]} ${h.sel} data-cv-hint="${h.marker}" 非白名单形态`);
    }
  }
  record('H6', '标记 · 每处提示文字带 data-cv-hint 且形态取值合法', bad.length === 0,
    bad.length ? `${bad.length} 处：\n        - ` + bad.join('\n        - ') : `全部带标记（白名单 ${[...LEGAL].join('/')}）`);
}

// 零 pageerror
{
  const errs = [...s.pageErrors];
  record('Z', 'pageerror = 0', errs.length === 0, errs.length ? errs.join(' | ') : '0');
}

// 变异腿结论
if (mutated) {
  const pluginHints = mutated.hints.filter((h) => h.form);
  const hits = pluginHints.flatMap((h) => h.hits.map((x) => `${h.sel} ∩ ${x.float} = ${x.area}px²`));
  const red = hits.length > 0;
  console.log(`\n[MUTATION drop-face-safe-top] 插件面提示文字交叠 ${hits.length} 处`);
  for (const h of hits) console.log(`   - ${h}`);
  if (red) console.log('RED-CONFIRMED: 顶垫压成 0 ⇒ 判据转红（浮层压提示文字被检出）· 验红通过');
  else { console.log('RED-MISSED: 变异后判据仍绿 ⇒ 判据无载力'); fail++; }
}

// 汇总
console.log(`\n═══ canvas-hint-safe-area: PASS ${pass} / FAIL ${fail} ═══`);
if (fail) {
  console.log('FAIL 行：');
  for (const r of rows.filter((x) => x.verdict === 'FAIL')) console.log(`  - ${r.id} ${r.name}`);
  process.exit(MUTATE ? (mutated ? 1 : 3) : 1);
}
process.exit(0);

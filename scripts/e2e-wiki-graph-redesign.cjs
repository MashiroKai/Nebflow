#!/usr/bin/env node
// e2e-wiki-graph-redesign.cjs — wiki-graph 控制面迁左栏批的 GUI 黑盒验收。
//
// 作者令 2026-10-21（图谱布局重构 · 控制面迁左栏）：canvas 只留图谱本体；
// 左栏切换为图谱控制面（搜索/目录 chips/详情卡/断链清单/统计行）。
// 验收（批令）：真实 docs 数据 playwright 截图——canvas 纯图态 / 左栏控制面 /
// 点选联动 / 断链区块 四态 × 亮暗两主题；窄面板容器查询 + 移动层 panebar；
// 零新增 console error。
//
// 自包含打桩（零端口、不碰 8080，与 e2e-fm-scroll-follow.cjs 同路线）：
// page.route 服务**真实 web 树**；/api/wiki/graph 返回从本仓 docs/ **实抽**
// 的引用图（抽取规则镜像 WikiIndex.scala：frontmatter/fence 屏蔽/wikilink/
// md link/唯一 basename 解析/phantom stub/摘要 140 字），其余 API 打最小桩。
// 悬停/预览的指针事件用 window.__wikiGraphProbe()（wikiGraph.js 只读探针）
// 读布局坐标后走真实 mouse 路径——canvas 是一张位图，DOM 读不到节点。
//
// Run（仓库根）：node scripts/e2e-wiki-graph-redesign.cjs
// 截图证据：.nebflow/evidence/20261022_wiki-graph-redesign/shots/
const { chromium } = require('playwright');
const { readFileSync, readdirSync, statSync, mkdirSync, writeFileSync } = require('node:fs');
const { join, extname, relative, resolve } = require('node:path');

const WEB = process.env.N6_WEB
  ? resolve(process.env.N6_WEB)
  : join(__dirname, '..', 'src', 'main', 'resources', 'web');
const DOCS = process.env.N6_DOCS
  ? resolve(process.env.N6_DOCS)
  : join(__dirname, '..', 'docs');
const SHOTS = join(__dirname, '..', 'evidence', '20261022_wiki-graph-sidepanel', 'shots');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

// ── 图谱抽取（镜像 WikiIndex.scala 的解析面）─────────────────────
const DOC_EXT = new Set(['.md', '.markdown', '.html']);
const ATT_EXT = new Set(['.pdf', '.docx', '.pptx', '.xlsx', '.epub', '.png', '.jpg', '.jpeg', '.gif', '.webp', '.svg', '.mp4', '.webm', '.mov', '.mp3', '.wav', '.csv']);
const SUMMARY_MAX = 140;

function frontmatterOf(text) {
  const lines = text.split('\n');
  if (!lines.length || lines[0].trim() !== '---') return { body: text, offset: 0 };
  let end = -1;
  for (let i = 1; i < lines.length; i++) if (lines[i].trim() === '---') { end = i; break; }
  if (end < 0) return { body: text, offset: 0 };
  const fields = {};
  for (const l of lines.slice(1, end)) {
    const idx = l.indexOf(':');
    if (idx > 0) fields[l.slice(0, idx).trim()] = l.slice(idx + 1).trim().replace(/^"|"$/g, '');
  }
  return { fields, body: lines.slice(end + 1).join('\n'), offset: end + 1 };
}

function fencedMask(lines) {
  const mask = new Array(lines.length).fill(false);
  let open = false, marker = '';
  for (let i = 0; i < lines.length; i++) {
    const m = lines[i].match(/^\s*(```+|~~~+)/);
    if (m && !open) { open = true; marker = m[1].slice(0, 3); }
    else if (m && open && m[1].startsWith(marker)) { open = false; marker = ''; }
    mask[i] = open || (!!m && !open);
  }
  return mask;
}

function cleanSummary(s) {
  return s
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/!?\[\[[^\]|]+\|([^\]]+)\]\]/g, '$1')
    .replace(/!?\[\[([^\]]+)\]\]/g, '$1')
    .replace(/\*\*([^*]+)\*\*/g, '$1')
    .replace(/__([^_]+)__/g, '$1')
    .replace(/\*([^*]+)\*/g, '$1')
    .replace(/~~([^~]+)~~/g, '$1')
    .replace(/`/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

function summaryOf(lines, mask, fromIdx) {
  if (fromIdx < 0 || fromIdx >= lines.length) return null;
  const isBreak = (l) => { const t = l.trim(); return !t || t.startsWith('#') || /^\s*(```+|~~~+)/.test(t); };
  let i = fromIdx;
  while (i < lines.length && (mask[i] || isBreak(lines[i]))) i++;
  const buf = [];
  while (i < lines.length && !mask[i] && !isBreak(lines[i])) {
    buf.push(lines[i].replace(/^\s*>\s?/, '').replace(/^\s*(?:[-*+]|\d+[.)])\s+/, ''));
    i++;
  }
  const cleaned = cleanSummary(buf.join(' '));
  if (!cleaned) return null;
  return cleaned.length <= SUMMARY_MAX ? cleaned : [...cleaned].slice(0, SUMMARY_MAX - 1).join('') + '…';
}

function parseDoc(text) {
  const fm = frontmatterOf(text);
  const lines = fm.body.split('\n');
  const mask = fencedMask(lines);
  const headings = [];
  const links = [];
  let title = null;
  let afterFirstH1 = -1;
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const lineNo = i + 1 + fm.offset;
    if (mask[i]) continue;
    const h = line.match(/^(#{1,6})\s+(.+?)\s*#*\s*$/);
    if (h) {
      if (h[2].trim()) headings.push({ t: h[2].trim(), line: lineNo });
      if (line.startsWith('# ')) { if (!title) title = h[2].trim(); if (afterFirstH1 < 0) afterFirstH1 = i + 1; }
      continue;
    }
    for (const m of line.matchAll(/(!?)\[\[([^\[\]]+?)\]\]/g)) {
      const inner = m[2].split('|')[0];
      const hash = inner.indexOf('#');
      const target = hash < 0 ? inner.trim() : inner.slice(0, hash).trim();
      const anchor = hash < 0 ? null : '#' + inner.slice(hash + 1).trim();
      if (target) links.push({ target, anchor, kind: m[1] ? 'embed' : 'link', line: lineNo });
    }
    for (const m of line.matchAll(/\[([^\]]*)\]\(([^)\s]+\.md(?:#[^\)\s]*)?)\)/g)) {
      let dest = m[2];
      try { dest = decodeURIComponent(dest); } catch { /* keep raw */ }
      const hash = dest.indexOf('#');
      const target = (hash < 0 ? dest : dest.slice(0, hash)).trim();
      const anchor = hash < 0 ? null : '#' + dest.slice(hash + 1).trim();
      if (target) links.push({ target, anchor, kind: 'link', line: lineNo });
    }
  }
  return { title, headings, links, summary: summaryOf(lines, mask, afterFirstH1) };
}

function normalizeTarget(t) {
  let s = t.replace(/\\/g, '/');
  while (s.startsWith('./')) s = s.slice(2);
  if (!s.toLowerCase().endsWith('.md') && !s.includes('.')) s += '.md';
  return s;
}

function walkDocs(dir, base, out, depth) {
  if (depth > 8 || out.length > 2000) return;
  for (const name of readdirSync(dir).sort()) {
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) walkDocs(p, base, out, depth + 1);
    else out.push({ rel: relative(base, p).split('\\').join('/'), abs: p });
  }
}

/** Build the API-shaped graph JSON from the real docs/ tree. */
function buildGraph() {
  const files = [];
  walkDocs(DOCS, DOCS, files, 0);
  const docs = [];
  const attachments = [];
  for (const f of files) {
    const ext = extname(f.rel).toLowerCase();
    if (DOC_EXT.has(ext)) docs.push({ rel: f.rel, text: readFileSync(f.abs, 'utf8') });
    else if (ATT_EXT.has(ext)) attachments.push(f.rel);
  }
  const known = new Set([...docs.map(d => d.rel), ...attachments]);
  const byBasename = new Map();
  for (const r of known) {
    const b = r.split('/').pop().replace(/\.md$/i, '').toLowerCase();
    if (!byBasename.has(b)) byBasename.set(b, []);
    byBasename.get(b).push(r);
  }
  const nodes = [];
  const edges = [];
  for (const d of docs) {
    const parsed = parseDoc(d.text);
    nodes.push({
      id: `stand:${d.rel}`, project: 'stand', device: 'self', rel: d.rel,
      title: parsed.title || d.rel.split('/').pop().replace(/\.(md|markdown|html)$/i, ''),
      headings: parsed.headings, blocks: [], stub: false, kind: 'doc',
      type: frontmatterOf(d.text).fields?.type || null,
      status: frontmatterOf(d.text).fields?.status || null,
      summary: parsed.summary,
    });
    for (const l of parsed.links) {
      const target = normalizeTarget(l.target);
      const key = target.toLowerCase();
      let resolved = known.has(target) ? target : (known.has(key) ? [...known].find(r => r.toLowerCase() === key) : null);
      if (!resolved) {
        const cands = byBasename.get(target.split('/').pop().replace(/\.md$/i, '').toLowerCase());
        resolved = cands && cands.length === 1 ? cands[0] : null;
      }
      const to = resolved || target;
      edges.push({ from: `stand:${d.rel}`, to: `stand:${to}`, kind: l.kind, anchor: l.anchor, line: l.line });
    }
  }
  for (const rel of attachments) {
    nodes.push({
      id: `stand:${rel}`, project: 'stand', device: 'self', rel,
      title: rel.split('/').pop(), headings: [], blocks: [], stub: false,
      kind: 'attachment', type: null, status: null, summary: null,
    });
  }
  // Phantom stubs for unresolved targets (the frontend counts them, never draws).
  const byId = new Map(nodes.map(n => [n.id, n]));
  for (const e of edges) {
    if (byId.has(e.to)) continue;
    const rel = e.to.slice('stand:'.length);
    const node = { id: e.to, project: 'stand', device: 'self', rel, title: rel.split('/').pop(), headings: [], blocks: [], stub: true, kind: 'doc', type: null, status: null, summary: null };
    byId.set(e.to, node);
    nodes.push(node);
  }
  return {
    generatedAt: Date.now(),
    projects: [{ name: 'stand', device: 'self', workspace: '/mock/stand', vault: '/mock/stand/docs', docCount: nodes.filter(n => !n.stub && n.kind === 'doc').length, error: null, stub: false }],
    nodes, edges,
  };
}

// ── Harness ─────────────────────────────────────────────────────
(async () => {
  mkdirSync(SHOTS, { recursive: true });
  const graph = buildGraph();
  writeFileSync(join(SHOTS, 'graph-data.json'), JSON.stringify(graph));
  const realNodes = graph.nodes.filter(n => !n.stub).length;
  // 未解析引用 = 指向 stub 的去重 (from,to) 对（与 wikiGraph.js 同口径）。
  // 断链区块四态需要 ≥1 条未解析引用：仓内 docs 已零断链时注入一条合成
  // phantom stub（与前端口径一致——只计数、不立节点），保证该态可验收。
  const byIdAll = new Map(graph.nodes.map(n => [n.id, n]));
  if (!graph.edges.some(e => { const t = byIdAll.get(e.to); return t && t.stub; })) {
    const fromDoc = graph.nodes.find(n => !n.stub && n.kind === 'doc');
    const phantom = { id: 'stand:mock/断链样例.md', project: 'stand', device: 'self', rel: 'mock/断链样例.md', title: '断链样例', headings: [], blocks: [], stub: true, kind: 'doc', type: null, status: null, summary: null };
    graph.nodes.push(phantom);
    graph.edges.push({ from: fromDoc.id, to: phantom.id, kind: 'link', anchor: null, line: 0 });
    byIdAll.set(phantom.id, phantom);
    console.log('note: docs are zero-broken — injected one synthetic phantom stub for the broken-block state');
  }
  const stubIds = new Set(graph.nodes.filter(n => n.stub).map(n => n.id));
  const unresolvedSeen = new Set();
  const unresolvedPairs = [];
  for (const e of graph.edges) {
    const f = byIdAll.get(e.from), tt = byIdAll.get(e.to);
    if (!f || !tt || !tt.stub) continue;
    const key = e.from + '|' + e.to;
    if (unresolvedSeen.has(key)) continue;
    unresolvedSeen.add(key);
    unresolvedPairs.push({ from: f.rel || f.title || e.from, target: tt.rel || tt.title || e.to });
  }
  const unresolved = unresolvedPairs.length;
  // 真实边数 = 真实节点间的去重 (from,to) 对（与前端统计行同口径）
  const realIds = new Set(graph.nodes.filter(n => !n.stub).map(n => n.id));
  const seenE = new Set();
  let realEdges = 0;
  for (const e of graph.edges) {
    if (!realIds.has(e.from) || !realIds.has(e.to) || e.from === e.to) continue;
    const k = e.from + '|' + e.to;
    if (seenE.has(k)) continue;
    seenE.add(k); realEdges++;
  }
  const dirCount = new Set(graph.nodes.filter(n => !n.stub)
    .map(n => (n.rel.includes('/') ? n.rel.split('/')[0] : '.'))).size;
  console.log(`graph: ${realNodes} real nodes · ${realEdges} edges · ${unresolved} unresolved refs · ${dirCount} dirs (from real docs/)`);

  const results = [];
  const ok = (name, cond, extra) => {
    results.push([name, !!cond]);
    console.log(`${cond ? 'PASS' : 'FAIL'}: ${name}${extra !== undefined ? '  — ' + extra : ''}`);
  };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  /** 节点屏幕坐标（探针世界坐标 → 页面像素；canvas 有面板偏移）。
   *  pg = page · pr = 探针读数 · n = 节点。 */
  const screenOf = async (/** @type {import('playwright').Page} */ pg, pr, n) => {
    const box = await (await pg.$('.wiki-canvas')).boundingBox();
    return {
      x: box.x + (n.x - pr.W / 2) * pr.scale + pr.W / 2 + pr.vx,
      y: box.y + (n.y - pr.H / 2) * pr.scale + pr.H / 2 + pr.vy,
    };
  };
  const nodeTitle = (id) => (graph.nodes.find(n => n.id === id) || {}).title || '';

  /** 左栏控制面 + canvas 纯度的一次性读数（页面内执行）。 */
  const readFace = (/** @type {import('playwright').Page} */ pg) => pg.evaluate(() => {
    const face = document.getElementById('wiki-graph-side');
    const explorer = document.getElementById('explorer-section');
    const panel = document.getElementById('panel-sessions');
    return {
      mounted: !!face,
      visible: !!face && getComputedStyle(face).display === 'flex',
      bodyFlag: document.body.classList.contains('wg-side-active'),
      sidebarOpen: !document.body.classList.contains('sidebar-collapsed'),
      panelActive: !!panel && panel.classList.contains('active'),
      explorerHidden: !!explorer && getComputedStyle(explorer).display === 'none',
      search: !!face && !!face.querySelector('.wg-search-input'),
      chips: face ? face.querySelectorAll('.wg-chip').length : -1,
      stats: face && face.querySelector('.wg-stats') ? face.querySelector('.wg-stats').textContent : '',
      emptyShown: !!face && !!face.querySelector('.wg-detail-empty') && !face.querySelector('.wg-detail-empty').hidden,
      brokenNone: !!face && !!face.querySelector('.wg-broken') && face.querySelector('.wg-broken').classList.contains('none'),
    };
  });

  async function openGraphAndSettle(/** @type {import('playwright').Page} */ pg) {
    await pg.click('#wiki-btn');
    await pg.waitForSelector('.wiki-graph-pane', { timeout: 15000 });
    // 布局收敛（设计 §4：<1.2s 后停帧）——等 alpha 低于停帧阈值
    await pg.waitForFunction(() => {
      const p = window.__wikiGraphProbe && window.__wikiGraphProbe();
      return p && p.alpha < 0.02;
    }, { timeout: 15000 });
  }

  /** 静态服务 + /api 打桩 + WS 启动帧 + zh-CN locale（每主题一个全新 page）。 */
  async function bootPage(browser, theme, viewport) {
    const page = await browser.newPage({ viewport: viewport || { width: 1440, height: 900 }, colorScheme: theme });
    const pageErrors = [];
    const consoleErrors = [];
    page.on('pageerror', (e) => pageErrors.push(e.message));
    page.on('console', (msg) => { if (msg.type() === 'error') consoleErrors.push(msg.text()); });
    await page.route('**/*', async (route) => {
      const req = route.request();
      const path = new URL(req.url()).pathname;
      if (path.startsWith('/api/')) {
        if (path === '/api/wiki/graph') return route.fulfill({ json: graph });
        if (path === '/api/neblink/status') {
          return route.fulfill({ json: { loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', email: 'me@example.com', userDescription: '', avatarUrl: '' }, peers: [], relay: { available: true } } });
        }
        return route.fulfill({ json: {} });
      }
      if (path === '/' || path === '/index.html') {
        return route.fulfill({ body: readFileSync(join(WEB, 'index.html'), 'utf8'), contentType: 'text/html' });
      }
      try {
        const file = join(WEB, decodeURIComponent(path));
        return route.fulfill({ body: readFileSync(file), contentType: MIME[extname(file)] || 'application/octet-stream' });
      } catch {
        if (path === '/favicon.ico') return route.fulfill({ status: 204, body: '' });
        if (/^\/agents\/[^/]+\/agent\.json$/.test(path)) return route.fulfill({ json: { name: 'Nebula', description: '', prompts: [] } });
        console.error(`[route-404] ${path}`);
        return route.fulfill({ status: 404, body: '' });
      }
    });
    await page.routeWebSocket(/\/ws/, (ws) => {
      ws.onMessage(() => { /* boot-level frames only; the graph tab is REST */ });
      ws.send(JSON.stringify({ type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
      ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
    });
    await page.addInitScript(() => {
      localStorage.setItem('nebflow_token', 't');
      localStorage.setItem('neblink_token', 't');
      localStorage.setItem('nebflow_locale', 'zh-CN');
      localStorage.setItem('neblink_locale', 'zh-CN');
    });
    await page.goto('http://mock.local/', { waitUntil: 'domcontentloaded' });
    await page.waitForSelector('#wiki-btn', { state: 'attached', timeout: 20000 });
    await sleep(400);
    return { page, pageErrors, consoleErrors };
  }

  async function runTheme(theme) {
    const browser = await chromium.launch();
    const { page, pageErrors, consoleErrors } = await bootPage(browser, theme);

    // 打开图谱（真实入口：activity-bar 按钮 → openWikiGraphTab）
    await openGraphAndSettle(page);
    const probe1 = await page.evaluate(() => window.__wikiGraphProbe());
    ok(`[${theme}] 布局收敛并停帧（alpha<0.02）`, probe1 && probe1.alpha < 0.02);
    ok(`[${theme}] 节点数=真实 docs 图（${realNodes}）`, probe1 && probe1.nodes.length === realNodes, `got ${probe1 && probe1.nodes.length}`);
    ok(`[${theme}] 断链不立节点（未解析引用只计数）`, probe1 && probe1.unresolved === unresolved, `counted=${probe1 && probe1.unresolved}`);
    const hubLabels = probe1.nodes.filter(n => n.rank < 12).length;
    ok(`[${theme}] 默认态仅枢纽标签在图（hub 集 ≤12）`, hubLabels > 0 && hubLabels <= 12, `hub=${hubLabels}`);

    const shot = (name) => page.screenshot({ path: join(SHOTS, `${name}-${theme}.png`) });
    const shotEl = (sel, name) => page.locator(sel).screenshot({ path: join(SHOTS, `${name}-${theme}.png`) });

    // ① canvas 纯图态：pane 里只有 canvas + tooltip（+桌面隐藏的 panebar）；
    //    旧控件面（topbar/预览卡/信息行/图例）全数撤出。
    const purity = await page.evaluate(() => {
      const pane = document.querySelector('.wiki-graph-pane');
      const kids = [...pane.children].map(e => String(e.className));
      const legacy = ['.wg-topbar', '.wg-preview', '.wg-infoline', '.wg-legend'].filter(s => pane.querySelector(s));
      const bar = pane.querySelector('.wg-panebar');
      return { kids, legacy, barDisplay: bar ? getComputedStyle(bar).display : null };
    });
    ok(`[${theme}] canvas 纯图态（pane = canvas+tooltip+隐藏panebar，旧控件零残留）`,
      purity.kids.length === 3 && purity.kids.includes('wiki-canvas') && purity.kids.includes('wg-tip') && purity.legacy.length === 0,
      JSON.stringify(purity));
    ok(`[${theme}] 桌面 panebar 不显示（display:none）`, purity.barDisplay === 'none', String(purity.barDisplay));

    // ② 左栏控制面：挂载 + 自动展开 + explorer 还位（隐藏）+ 内容齐
    const face = await readFace(page);
    const statsWant = `${realNodes} 篇文档 · ${realEdges} 条引用 · ${unresolved} 条未解析`;
    ok(`[${theme}] 左栏控制面挂载并可见（body.wg-side-active）`,
      face.mounted && face.visible && face.bodyFlag, JSON.stringify(face));
    ok(`[${theme}] 自动展开 + files 面板激活 + explorer 让位（隐藏）`,
      face.sidebarOpen && face.panelActive && face.explorerHidden,
      `open=${face.sidebarOpen} panel=${face.panelActive} explorerHidden=${face.explorerHidden}`);
    ok(`[${theme}] 控制面内容：搜索框 + 目录 chips（${dirCount + 1}）+ 详情空态`,
      face.search && face.chips === dirCount + 1 && face.emptyShown && !face.brokenNone,
      `chips=${face.chips} empty=${face.emptyShown} brokenNone=${face.brokenNone}`);
    ok(`[${theme}] 统计行口径（N 篇文档 · M 条引用 · K 条未解析）`,
      face.stats === statsWant, `"${face.stats}" vs "${statsWant}"`);

    await shotEl('.wiki-graph-pane', '1-canvas-pure');
    await shot('2-sidepanel');

    // 悬停 tooltip（canvas 面保留的唯一交互面）
    let p = await page.evaluate(() => window.__wikiGraphProbe());
    const hub = p.nodes.reduce((a, b) => (b.degree > a.degree ? b : a));
    {
      const s = await screenOf(page, p, hub);
      await page.mouse.move(s.x, s.y, { steps: 6 });
      await sleep(250);
      const tipVisible = await page.evaluate(() => {
        const tip = document.querySelector('.wiki-graph-pane .wg-tip');
        return tip && tip.style.display === 'block' && tip.querySelector('.tt').textContent.length > 0;
      });
      ok(`[${theme}] 悬停 tooltip 出现（标题+摘要+度数）`, tipVisible);
      await page.mouse.move(10, 500, { steps: 4 }); // 离开，收 tooltip
      await sleep(150);
    }

    // ③ 点选联动：canvas 点节点 → 左栏详情刷新
    p = await page.evaluate(() => window.__wikiGraphProbe());
    {
      const s = await screenOf(page, p, hub);
      await page.mouse.click(s.x, s.y);
      await sleep(320);
      const link = await page.evaluate(() => {
        const card = document.querySelector('#wiki-graph-side .wg-detail-body');
        return {
          shown: !!card && !card.hidden,
          h2: card && card.querySelector('h2') ? card.querySelector('h2').textContent : '',
          refs: card ? card.querySelectorAll('.ref').length : 0,
          sel: window.__wikiGraphProbe().selected,
        };
      });
      ok(`[${theme}] canvas 点节点 → 左栏详情刷新（标题一致·可点引用）`,
        link.shown && link.sel === hub.id && link.h2 === nodeTitle(hub.id) && link.refs > 0,
        JSON.stringify(link));
      await shot('3-linkage');
    }
    // 左栏词条（详情卡引用行）点击 → canvas 换选并居中（选中即屏面中心；
    // 力导向在 wake 后仍会微收敛，用屏幕坐标近中心断言而非坐标公式）
    {
      const ref = await page.$('#wiki-graph-side .wg-detail-body .ref');
      const before = (await page.evaluate(() => window.__wikiGraphProbe())).selected;
      if (ref) {
        await ref.click();
        await sleep(320);
        const q = await page.evaluate(() => {
          const pr = window.__wikiGraphProbe();
          const n = pr.nodes.find(nn => nn.id === pr.selected);
          const box = document.querySelector('.wiki-canvas').getBoundingClientRect();
          if (!n) return null;
          return {
            sel: pr.selected,
            sx: box.x + (n.x - pr.W / 2) * pr.scale + pr.W / 2 + pr.vx,
            sy: box.y + (n.y - pr.H / 2) * pr.scale + pr.H / 2 + pr.vy,
            cx: box.x + box.width / 2, cy: box.y + box.height / 2,
          };
        });
        const centered = !!q && Math.abs(q.sx - q.cx) < 60 && Math.abs(q.sy - q.cy) < 60;
        ok(`[${theme}] 左栏词条点击 → canvas 换选并居中`,
          !!q && q.sel !== before && centered,
          JSON.stringify(q && { sel: q.sel, dx: Math.round(q.sx - q.cx), dy: Math.round(q.sy - q.cy) }));
      } else {
        ok(`[${theme}] 左栏词条点击 → canvas 换选并居中`, false, 'no .ref button');
      }
    }
    // 左栏目录 chips → canvas 过滤（单选）
    {
      const chip = await page.$('#wiki-graph-side .wg-chips .wg-chip:nth-child(2)');
      if (chip) {
        const label = (await chip.textContent()).trim();
        await chip.click();
        await sleep(250);
        const st = await page.evaluate(() => ({
          dir: window.__wikiGraphProbe().dirFilter,
          on: [...document.querySelectorAll('#wiki-graph-side .wg-chip.on')].map(b => b.textContent),
        }));
        ok(`[${theme}] 左栏目录 chips → canvas 过滤（单选 ${label}）`,
          st.dir !== '' && st.on.length === 1 && st.on[0] === label, JSON.stringify(st));
        // Esc 逐层退出：选中 → 过滤 → 无
        await page.keyboard.press('Escape'); await sleep(150);
        const mid = await page.evaluate(() => window.__wikiGraphProbe().selected);
        await page.keyboard.press('Escape'); await sleep(150);
        const st2 = await page.evaluate(() => window.__wikiGraphProbe());
        ok(`[${theme}] Esc 逐层退出（选中→过滤→无）`,
          mid === null && st2.dirFilter === '' && st2.selected === null,
          JSON.stringify({ mid, dir: st2.dirFilter, sel: st2.selected }));
      } else {
        ok(`[${theme}] 左栏目录 chips 渲染（≥2 片）`, false, 'no second chip');
      }
    }
    // 左栏搜索框 → canvas 过滤
    {
      await page.fill('#wiki-graph-side .wg-search-input', 'zzz-no-hit');
      await sleep(200);
      const f1 = await page.evaluate(() => window.__wikiGraphProbe().filter);
      await page.fill('#wiki-graph-side .wg-search-input', '');
      await sleep(150);
      const f2 = await page.evaluate(() => window.__wikiGraphProbe().filter);
      ok(`[${theme}] 左栏搜索框 → canvas 过滤（可清空）`, f1 === 'zzz-no-hit' && f2 === '', `${f1} → ${f2}`);
    }

    // ④ 断链区块 = 面板内固定区块（非浮层）；点行 → 源文档选中
    {
      const bk = await page.evaluate(() => {
        const el = document.querySelector('#wiki-graph-side .wg-broken');
        if (!el) return null;
        const cs = getComputedStyle(el);
        return {
          visible: cs.display !== 'none' && !el.classList.contains('none'),
          staticPos: cs.position === 'static',
          rows: el.querySelectorAll('.row').length,
          inFace: !!el.closest('#wiki-graph-side'),
        };
      });
      ok(`[${theme}] 断链区块 = 面板内固定区块（非浮层·${bk && bk.rows} 行）`,
        !!bk && bk.visible && bk.staticPos && bk.inFace && bk.rows > 0, JSON.stringify(bk));
      if (unresolvedPairs.length) {
        const src = graph.nodes.find(n => n.rel === unresolvedPairs[0].from);
        await page.click('#wiki-graph-side .wg-broken .row');
        await sleep(300);
        const sel = await page.evaluate(() => window.__wikiGraphProbe().selected);
        ok(`[${theme}] 断链行点击 → 源文档选中`, sel === (src && src.id), `${sel} vs ${src && src.id}`);
        await page.keyboard.press('Escape');
        await sleep(120);
      }
      await shotEl('#wiki-graph-side', '4-broken');
    }

    // ⑤ focusWikiNode 契约（md-viewer 图谱按钮入口）：选中 + 详情直显
    {
      const focused = await page.evaluate(async () => {
        const m = await import('/js/wikiGraph.js');
        m.focusWikiNode('stand', 'glossary.md');
        await new Promise(r => setTimeout(r, 350));
        const card = document.querySelector('#wiki-graph-side .wg-detail-body');
        return {
          open: !!card && !card.hidden,
          h2: card && card.querySelector('h2') ? card.querySelector('h2').textContent : '',
          sel: window.__wikiGraphProbe().selected,
        };
      });
      ok(`[${theme}] focusWikiNode 入口：选中+详情直显`,
        focused.open && (focused.sel || '').endsWith('glossary.md') && focused.h2 === '词汇表',
        JSON.stringify(focused));
    }

    ok(`[${theme}] 零 page error`, pageErrors.length === 0, pageErrors.slice(0, 3).join(' | '));
    const noise = consoleErrors.filter(t => !/favicon|Failed to load resource.*40[34]/.test(t));
    ok(`[${theme}] 零新增 console error`, noise.length === 0, noise.slice(0, 3).join(' | '));

    await browser.close();
  }

  await runTheme('light');
  await runTheme('dark');

  // ── 窄面板（container query：侧栏收窄 → 控制面紧凑档）──
  {
    const browser = await chromium.launch();
    const { page, pageErrors } = await bootPage(browser, 'light');
    await openGraphAndSettle(page);
    // 用应用自身的侧栏宽度变量（col-resizer 拖拽写的是同一条 inline flex）
    // 把侧栏压到 180px —— 真实复现「窄面板」容器面。
    await page.evaluate(() => { document.getElementById('sidebar').style.flex = '0 0 180px'; });
    await sleep(300);
    const narrow = await page.evaluate(() => {
      const face = document.getElementById('wiki-graph-side');
      const chip = face.querySelector('.wg-chip');
      return { w: face.clientWidth, chipFont: getComputedStyle(chip).fontSize };
    });
    ok(`[narrow] 窄面板容器查询（面宽 ${narrow.w}px → chips 紧凑 11px）`,
      parseFloat(narrow.chipFont) === 11, JSON.stringify(narrow));
    await page.screenshot({ path: join(SHOTS, '5-narrow-light.png') });
    ok(`[narrow] 零 page error`, pageErrors.length === 0, pageErrors.slice(0, 3).join(' | '));
    await browser.close();
  }

  // ── 移动层 panebar（无左栏 → 紧凑控制条随 pane 收编进移动页）──
  {
    const browser = await chromium.launch();
    const { page, pageErrors } = await bootPage(browser, 'light', { width: 375, height: 812 });
    // 真实用户路径：顶栏右圆钮液态下拉列的 graph face（navController navigateTo）。
    await page.waitForSelector('.mh-dd[data-dd="right"] [data-face="graph"]', { state: 'attached', timeout: 10000 });
    await page.evaluate(() => document.querySelector('.mh-dd[data-dd="right"] [data-face="graph"]').click());
    await page.waitForFunction(() => document.querySelector('.canvas-tab-pane.active')?.dataset.tabId === 'wiki-graph', { timeout: 10000 });
    await sleep(600);
    const bar = await page.evaluate(() => {
      const el = document.querySelector('.wiki-graph-pane .wg-panebar');
      const input = el && el.querySelector('.wg-search-input');
      return {
        display: el ? getComputedStyle(el).display : null,
        inputVisible: input ? input.getBoundingClientRect().height > 0 : false,
        chips: el ? el.querySelectorAll('.wg-chip').length : 0,
      };
    });
    ok(`[mobile] panebar 随 pane 收编（display:flex·chips ${bar.chips}·搜索可见）`,
      bar.display === 'flex' && bar.inputVisible, JSON.stringify(bar));
    if (bar.inputVisible) {
      await page.fill('.wiki-graph-pane .wg-panebar .wg-search-input', 'zzz');
      await sleep(200);
      const f = await page.evaluate(() => window.__wikiGraphProbe().filter);
      ok(`[mobile] panebar 搜索 → canvas 过滤`, f === 'zzz', f);
    }
    await page.screenshot({ path: join(SHOTS, '6-mobile-light.png') });
    ok(`[mobile] 零 page error`, pageErrors.length === 0, pageErrors.slice(0, 3).join(' | '));
    await browser.close();
  }

  const failed = results.filter(r => !r[1]);
  console.log(`\n== ${results.length - failed.length}/${results.length} PASS · shots → ${SHOTS}`);
  process.exit(failed.length ? 1 : 0);
})();

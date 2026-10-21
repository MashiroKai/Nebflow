#!/usr/bin/env node
// e2e-wiki-graph-redesign.cjs — wiki-graph 极简化重设计批的 GUI 黑盒验收。
//
// 设计稿：docs/design/wiki-graph-redesign-design.zh-CN.md（四病灶+交互表+§4 视觉规格）
// 验收（批令）：真实 docs 数据 playwright 截图——默认态 / hover / 预览卡 /
// chips 过滤 四态 × 亮暗两主题；与样张视觉对照；零新增 console error。
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
const SHOTS = join(__dirname, '..', '.nebflow', 'evidence', '20261022_wiki-graph-redesign', 'shots');
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
  // 未解析引用 = 指向 stub 的去重 (from,to) 对（与 wikiGraph.js 同口径）
  const stubIds = new Set(graph.nodes.filter(n => n.stub).map(n => n.id));
  const unresolved = new Set(graph.edges.filter(e => stubIds.has(e.to)).map(e => e.from + '|' + e.to)).size;
  console.log(`graph: ${realNodes} real nodes · ${graph.edges.length} edges · ${unresolved} unresolved refs (from real docs/)`);

  const results = [];
  const ok = (name, cond, extra) => {
    results.push([name, !!cond]);
    console.log(`${cond ? 'PASS' : 'FAIL'}: ${name}${extra !== undefined ? '  — ' + extra : ''}`);
  };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  async function runTheme(theme) {
    const browser = await chromium.launch();
    const pageErrors = [];
    const consoleErrors = [];
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, colorScheme: theme });
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

    // 打开图谱（真实入口：activity-bar 按钮 → openWikiGraphTab）
    await page.click('#wiki-btn');
    await page.waitForSelector('.wiki-graph-pane', { timeout: 15000 });
    // 布局收敛（设计 §4：<1.2s 后停帧）——等 alpha 低于停帧阈值
    const settled = await page.waitForFunction(() => {
      const p = window.__wikiGraphProbe && window.__wikiGraphProbe();
      return p && p.alpha < 0.02;
    }, { timeout: 15000 }).then(() => true).catch(() => false);
    ok(`[${theme}] 布局收敛并停帧（alpha<0.02）`, settled);
    const probe1 = await page.evaluate(() => window.__wikiGraphProbe());
    ok(`[${theme}] 节点数=真实 docs 图（${realNodes}）`, probe1 && probe1.nodes.length === realNodes, `got ${probe1 && probe1.nodes.length}`);
    ok(`[${theme}] 断链不立节点（未解析引用只计数）`, probe1 && probe1.unresolved === unresolved, `counted=${probe1 && probe1.unresolved}`);

    const shot = (name) => page.screenshot({ path: join(SHOTS, `${name}-${theme}.png`) });
    /** 节点屏幕坐标（探针世界坐标 → 页面像素；canvas 有面板偏移）。 */
    const screenOf = async (p, n) => {
      const box = await (await page.$('.wiki-canvas')).boundingBox();
      return {
        x: box.x + (n.x - p.W / 2) * p.scale + p.W / 2 + p.vx,
        y: box.y + (n.y - p.H / 2) * p.scale + p.H / 2 + p.vy,
      };
    };
    const nodeById = (p, suffix) => p.nodes.find(n => n.id.endsWith(suffix));

    // ① 默认态
    await shot('1-default');
    const defLabels = await page.evaluate(() => {
      const p = window.__wikiGraphProbe();
      return p ? p.nodes.filter(n => n.rank < 12).length : 0;
    });
    ok(`[${theme}] 默认态仅枢纽标签在图（hub 集 ${defLabels} 个，整改生效）`, defLabels > 0 && defLabels <= 12);

    // ② 悬停态：真实 mouse 移到被引最多的枢纽（glossary）
    let p = await page.evaluate(() => window.__wikiGraphProbe());
    const hub = nodeById(p, 'glossary.md');
    ok(`[${theme}] 找到枢纽节点 glossary`, !!hub, hub && `degree=${hub.degree}`);
    if (hub) {
      const s = await screenOf(p, hub);
      await page.mouse.move(s.x, s.y, { steps: 6 });
      await sleep(250);
      const tipVisible = await page.evaluate(() => {
        const tip = document.querySelector('.wiki-graph-pane .wg-tip');
        return tip && tip.style.display === 'block' && tip.querySelector('.tt').textContent.length > 0;
      });
      ok(`[${theme}] 悬停 tooltip 出现（标题+摘要+度数）`, tipVisible);
      await shot('2-hover');
      await page.mouse.move(10, 500, { steps: 4 }); // 离开，收 tooltip
      await sleep(150);
    }

    // ③ 预览卡：单击 glossary（真实 click 路径）
    if (hub) {
      p = await page.evaluate(() => window.__wikiGraphProbe());
      const s = await screenOf(p, nodeById(p, 'glossary.md'));
      await page.mouse.click(s.x, s.y);
      await sleep(320); // 滑入 180ms + 余量
      const pv = await page.evaluate(() => {
        const card = document.querySelector('.wiki-graph-pane .wg-preview');
        const open = card && card.classList.contains('open');
        const h2 = card && card.querySelector('h2').textContent;
        const refs = card ? card.querySelectorAll('.ref').length : 0;
        const hasOpen = card && card.querySelector('.openbtn').textContent.length > 0;
        return { open, h2, refs, hasOpen };
      });
      ok(`[${theme}] 预览卡滑入（标题/可点引用/打开按钮）`, pv.open && pv.refs > 0 && pv.hasOpen, `h2="${pv.h2}" refs=${pv.refs}`);
      await shot('3-preview');
      // 单击画布空白处收起（探针找真正空点：距任一节点 >40px 的候选位）
      const p3 = await page.evaluate(() => window.__wikiGraphProbe());
      const blank = (() => {
        const cands = [];
        for (const fx of [0.06, 0.5, 0.94]) for (const fy of [0.08, 0.5, 0.94]) cands.push([fx * p3.W, fy * p3.H]);
        for (const [cx, cy] of cands) {
          let minD = Infinity;
          for (const n of p3.nodes) minD = Math.min(minD, Math.hypot(n.x - cx, n.y - cy));
          if (minD > 40) return { x: cx, y: cy };
        }
        return { x: p3.W * 0.03, y: p3.H * 0.03 };
      })();
      const cbox = await (await page.$('.wiki-canvas')).boundingBox();
      await page.mouse.click(cbox.x + blank.x, cbox.y + blank.y);
      await sleep(260);
      const afterBlank = await page.evaluate(() => {
        const card = document.querySelector('.wiki-graph-pane .wg-preview');
        return { open: card.classList.contains('open'), h2: card.querySelector('h2').textContent };
      });
      ok(`[${theme}] 空白单击收起预览卡`, !afterBlank.open || afterBlank.h2 !== pv.h2, `now="${afterBlank.h2}"`);
      await page.click('.wiki-graph-pane .wg-preview .close').catch(() => {});
      await sleep(260);
      const closed = await page.evaluate(() => {
        const card = document.querySelector('.wiki-graph-pane .wg-preview');
        return card && !card.classList.contains('open');
      });
      ok(`[${theme}] 关闭后预览卡收起（chips 无遮挡）`, closed);
    }

    // ④ chips 过滤：点「design/」片（首片 = 「全部」，默认亮）
    const chipText = 'design/';
    const tAll = await page.evaluate(() => {
      const first = document.querySelector('.wiki-graph-pane .wg-chip');
      return first ? first.textContent : '';
    });
    const chip = await page.$(`.wg-chips .wg-chip:text-is("${chipText}")`);
    ok(`[${theme}] 目录 chips 渲染（含 ${chipText}）`, !!chip);
    if (chip) {
      await chip.click();
      await sleep(250);
      const after = await page.evaluate(() => {
        const on = [...document.querySelectorAll('.wiki-graph-pane .wg-chip.on')];
        return { chips: document.querySelectorAll('.wiki-graph-pane .wg-chip').length, on: on.map(b => b.textContent) };
      });
      ok(`[${theme}] chips 单选生效（唯一切换到 ${chipText}）`, after.on.length === 1 && after.on[0] === chipText, JSON.stringify(after));
      await shot('4-chips');
      // Esc 逐层退出：过滤 → 无（回到「全部」片亮）
      await page.keyboard.press('Escape');
      await sleep(200);
      const escState = await page.evaluate(() => {
        const on = [...document.querySelectorAll('.wiki-graph-pane .wg-chip.on')];
        return { on: on.map(b => b.textContent), dir: window.__wikiGraphProbe().dirFilter };
      });
      ok(`[${theme}] Esc 退出过滤（回到「全部」）`, escState.on.length === 1 && escState.on[0] === tAll && escState.dir === '', JSON.stringify(escState));
    }

    // 信息行：文档数 · 引用数 · 未解析引用
    const info = await page.evaluate(() => {
      const el = document.querySelector('.wiki-graph-pane .wg-infoline');
      return el ? el.textContent : '';
    });
    ok(`[${theme}] 信息行计数齐全`, /篇文档/.test(info) && /条引用/.test(info) && /未解析引用/.test(info), info.trim());

    // ⑤ md-viewer 图谱按钮入口契约：focusWikiNode 居中选中并开预览
    const focused = await page.evaluate(async () => {
      const m = await import('/js/wikiGraph.js');
      m.focusWikiNode('stand', 'glossary.md');
      await new Promise(r => setTimeout(r, 350));
      const card = document.querySelector('.wiki-graph-pane .wg-preview');
      return { open: card.classList.contains('open'), h2: card.querySelector('h2').textContent };
    });
    ok(`[${theme}] focusWikiNode 入口：选中+预览直开`, focused.open && focused.h2 === '词汇表', `h2="${focused.h2}"`);
    await page.evaluate(() => document.querySelector('.wiki-graph-pane .wg-preview .close').click());

    ok(`[${theme}] 零 page error`, pageErrors.length === 0, pageErrors.slice(0, 3).join(' | '));
    const noise = consoleErrors.filter(t => !/favicon|Failed to load resource.*40[34]/.test(t));
    ok(`[${theme}] 零新增 console error`, noise.length === 0, noise.slice(0, 3).join(' | '));

    await browser.close();
  }

  await runTheme('light');
  await runTheme('dark');

  // ── 窄面板（响应式：legend 随容器宽收起）──
  // 用应用自身的面板宽度变量（--canvas-width，拖拽手柄写的同一个值）把
  // canvas 面板压到 500px——真实复现「桌面窄卡」容器面。
  {
    const browser = await chromium.launch();
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, colorScheme: 'light' });
    await page.route('**/*', async (route) => {
      const req = route.request();
      const path = new URL(req.url()).pathname;
      if (path.startsWith('/api/')) {
        if (path === '/api/wiki/graph') return route.fulfill({ json: graph });
        if (path === '/api/neblink/status') return route.fulfill({ json: { loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', email: '', userDescription: '', avatarUrl: '' }, peers: [], relay: { available: true } } });
        return route.fulfill({ json: {} });
      }
      if (path === '/' || path === '/index.html') return route.fulfill({ body: readFileSync(join(WEB, 'index.html'), 'utf8'), contentType: 'text/html' });
      try {
        const file = join(WEB, decodeURIComponent(path));
        return route.fulfill({ body: readFileSync(file), contentType: MIME[extname(file)] || 'application/octet-stream' });
      } catch { return route.fulfill({ status: 404, body: '' }); }
    });
    await page.routeWebSocket(/\/ws/, (ws) => {
      ws.send(JSON.stringify({ type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
      ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
    });
    await page.addInitScript(() => {
      localStorage.setItem('nebflow_token', 't');
      localStorage.setItem('neblink_token', 't');
      localStorage.setItem('nebflow_locale', 'zh-CN');
    });
    await page.goto('http://mock.local/', { waitUntil: 'domcontentloaded' });
    await page.waitForSelector('#wiki-btn', { state: 'attached', timeout: 20000 });
    await page.evaluate(() => document.body.style.setProperty('--canvas-width', '500px'));
    await page.click('#wiki-btn');
    await page.waitForSelector('.wiki-graph-pane', { timeout: 15000 });
    await page.waitForFunction(() => {
      const p = window.__wikiGraphProbe && window.__wikiGraphProbe();
      return p && p.alpha < 0.02;
    }, { timeout: 15000 }).catch(() => {});
    const responsive = await page.evaluate(() => {
      const pane = document.querySelector('.wiki-graph-pane');
      const legend = getComputedStyle(pane.querySelector('.wg-legend')).display;
      return { paneW: pane.clientWidth, legendHidden: legend === 'none' };
    });
    ok(`[narrow] 窄面板图例收起（container query）`, responsive.legendHidden, `paneW=${responsive.paneW}`);
    await page.screenshot({ path: join(SHOTS, '5-narrow-light.png') });
    await browser.close();
  }

  const failed = results.filter(r => !r[1]);
  console.log(`\n== ${results.length - failed.length}/${results.length} PASS · shots → ${SHOTS}`);
  process.exit(failed.length ? 1 : 0);
})();

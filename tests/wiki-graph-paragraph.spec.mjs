// wiki-graph-paragraph.spec.mjs — 文档图谱「段落级引用 + hover 快照」契约 spec
// （隔离静态服务器 + 页内 mock，真实 UI 代码全量执行；8100+ 端口纪律，永不触碰宿主 8080）。
//
// 台账 #744-A（图谱演进 A 批）：引用精确到段落——名词解释场景 hover 小窗显示
// 「那一段」的内容（非整文档），点击跳转目标文档并高亮该段。设计稿
// docs/design/wiki-graph-paragraph-ref-design.zh-CN.md §4 交互表的本 spec 钉面：
//
//   §P0 图谱入口封存闸（存量红清欠批 2026-10-29 新增）：graphseal 批
//       （82c273a2a，作者手写想法① 2026-10-29「词典边栏的图谱入口暂时隐藏…
//        但是保留词条有关的各种关联、引用的功能」）boot 摘挂 #wiki-btn，
//        features.wikiGraph 显式 true 才回挂（featureFlags.wikiGraphEnabled，
//        缺席 = 封存 = 默认 OFF）。本 spec 原 openGraph 直点 #wiki-btn——封存
//        后入口不在 DOM，10 用例全挂（词条二批报告留档：干净树基线同挂）。
//        双态都钉：开关关 = 入口不在 DOM（负断言，摘挂非 CSS 藏）；开关开 =
//        入口回挂静态槽（正断言）。§P1–P8 全族改在开关开档跑。
//        ⚠ 清欠批核实发现：开关开的回挂入口点击无响应 = 封存批自身的产品回归
//        （回挂接线从未建立，归因与修法见 §P0b 注）——回挂接线修复批
//        （2026-10-30，canvas.js 改 #activity-bar 事件委托）落地后全族转绿。
//   §P1 边锚保真：payload 边的 anchor 在前端去重后保留（带锚边中点画 2.4px
//       石墨圆点——canvas 是单一位图，spec 经 __wikiGraphProbe 读模型态）；
//   §P2 引用边 hover = 段落快照小窗：.wg-tip.para 毛玻璃浮层，§ 段标题 +
//       那一段 text（内嵌 paragraphs 索引，零请求）+ probe.hoverEdge 同步；
//   §P3 索引未覆盖该锚 → 回退文档级预览（.para 不挂，td 仍给跳转提示）；
//       空白处 hover → 浮层隐藏；
//   §P4 带锚边点击 → workspace-open-item 载荷携带 anchor = 段落跳转载荷
//       {kind:'block',id}（markdown 查看器滚动 + nf-jump-flash 高亮的既有
//       通道，本批接通图谱侧）；
//   §P5 无锚边点击 → 选中目标节点（画布语义不变）；
//   §P6 详情卡引出词条带锚 chip（信息性）；
//   §P7 亮暗双主题截图（SHOTS_DIR，默认 evidence/20261022_wiki-graph-paragraph/shots）；
//   R0 全流程 console 零错误（每个用例尾部断言）。

import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '..', 'src', 'main', 'resources', 'web');
const PORT = 8194; // fixed, 8100+ — never the host 8080; disjoint from wiki-graph-ux (8193)
const SHOTS_DIR = process.env.SHOTS_DIR ||
  resolve(HERE, '..', 'evidence', '20261022_wiki-graph-paragraph', 'shots');
const MIME = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml' };

// 模拟图谱载荷：glossary.md 内嵌段落索引（块锚 #^vault — 名词解释场景真源形态），
// a.md 一条带锚边 + 一条无锚边 + 一条锚在索引中未覆盖的边（§P3 回退腿）。
const WIKI_GRAPH = {
  projects: [{ name: 'alpha', vault: '/w/alpha', docCount: 3 }],
  nodes: [
    { id: 'alpha:g.md', project: 'alpha', rel: 'g.md', title: '词汇表', kind: 'doc', type: 'glossary', status: 'current',
      summary: '文档库的唯一定义源。',
      paragraphs: [
        { anchor: '#^vault', kind: 'block', title: '文档库（vault）', line: 21, endLine: 21,
          text: '文档库（vault） — 项目工作区内 docs/ 目录整体：Obsidian 兼容的文档库，文档、词表与附件都住在这里，图谱视图只扫这个目录。' },
        { anchor: '#词汇表', kind: 'heading', title: '词汇表', line: 6, endLine: 53, text: '词汇表正文……' },
      ] },
    { id: 'alpha:a.md', project: 'alpha', rel: 'a.md', title: 'Alpha Note', kind: 'doc', type: 'spec', status: 'open',
      summary: 'Alpha 首篇笔记。', paragraphs: [] },
    { id: 'alpha:p.md', project: 'alpha', rel: 'p.md', title: 'Plain Doc', kind: 'doc',
      summary: '没有段落索引的普通文档。', paragraphs: [] },
  ],
  edges: [
    { from: 'alpha:a.md', to: 'alpha:g.md', anchor: '#^vault' },
    { from: 'alpha:a.md', to: 'alpha:p.md' },
    { from: 'alpha:p.md', to: 'alpha:g.md', anchor: '#^ghost' },
  ],
};

function startServer() {
  return new Promise((resolveDone) => {
    const server = createServer(async (req, res) => {
      try {
        let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
        if (p === '/') p = '/index.html';
        if (p === '/favicon.ico') { res.writeHead(204); res.end(); return; }
        const file = resolve(join(WEB, p));
        if (!file.startsWith(WEB)) { res.writeHead(403); res.end(); return; }
        const data = await readFile(file);
        res.writeHead(200, { 'content-type': MIME[extname(file)] || 'application/octet-stream' });
        res.end(data);
      } catch {
        res.writeHead(404); res.end('not found');
      }
    });
    server.listen(PORT, '127.0.0.1', () => resolveDone(server));
  });
}

let server;
const base = `http://127.0.0.1:${PORT}`;

async function boot(browser, { colorScheme = 'dark', viewport = { width: 1440, height: 900 }, wikiGraph = false } = {}) {
  const context = await browser.newContext();
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(String(e && e.message || e)));
  page.on('console', (m) => { if (m.type() === 'error') pageErrors.push(m.text()); });
  await page.setViewportSize(viewport);
  await page.emulateMedia({ colorScheme });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('stand_locale', 'zh-CN');
    // capture workspace-open-item BEFORE app listeners (§P4 assert seam)
    window.__openEvents = [];
    window.addEventListener('workspace-open-item', (e) => {
      const d = e.detail || {};
      window.__openEvents.push({ id: d.id, absPath: d.absPath, anchor: d.anchor, itemType: d.itemType });
    }, true);
  });
  await page.route('**/api/**', r => r.fulfill({ json: {} }));
  await page.route('**/api/wiki/graph', r => r.fulfill({ json: WIKI_GRAPH }));
  await page.route('**/agents/**', r => r.fulfill({ json: {} }));
  await page.route('**/api/neblink/status', r => r.fulfill({ json: { loggedIn: false, device: null, peers: [] } }));
  await page.routeWebSocket(/\/ws/, ws => {
    // graphseal 批闸帧（82c273a2a）：首帧 configData 一次性判定入口封存态——
    // 缺席 features.wikiGraph = 封存（#wiki-btn 摘挂）；显式 true = 回挂静态槽。
    ws.send(JSON.stringify({
      type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {},
      config: JSON.stringify(wikiGraph ? { features: { wikiGraph: true } } : {}),
    }));
    ws.onMessage(raw => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      if (m.type === 'autostartStatus') ws.send(JSON.stringify({ type: 'autostartStatusResult', enabled: false, supported: true }));
      if (m.type === 'pop.readFile') ws.send(JSON.stringify({ type: 'pop.readFileResult', ok: false, path: m.path, error: 'spec-mock' }));
    });
  });
  await page.goto(base);
  return { context, page, pageErrors };
}

/** Open the graph tab through the activity-bar entry (same state-1 entry the
 *  ux spec uses) and wait for a FROZEN layout — two probes ≥400ms apart agree,
 *  so coordinate-derived clicks never race the refetch rebuild. */
async function openGraph(page) {
  await page.click('#wiki-btn');
  await page.waitForSelector('.wiki-graph-pane', { state: 'attached', timeout: 10000 });
  await page.waitForFunction(() => {
    const p = window.__wikiGraphProbe && window.__wikiGraphProbe();
    return p && p.alpha < 0.02 && p.nodes.length === 3;
  }, null, { timeout: 15000 });
  await expect.poll(async () => page.evaluate(async () => {
    const a = window.__wikiGraphProbe();
    await new Promise(r => setTimeout(r, 400));
    const b = window.__wikiGraphProbe();
    const frozen = (p) => JSON.stringify(p.nodes.map(n => [n.id, Math.round(n.x), Math.round(n.y)]));
    return a.alpha < 0.02 && b.alpha < 0.02 && a.vx === b.vx && a.vy === b.vy && frozen(a) === frozen(b);
  }), { timeout: 15000 }).toBe(true);
  return page.evaluate(() => window.__wikiGraphProbe());
}

/** World → screen coordinates (the exact inverse of wikiGraph.js toWorld). */
function screenOf(probe, wx, wy) {
  return {
    x: (wx - probe.W / 2) * probe.scale + probe.W / 2 + probe.vx,
    y: (wy - probe.H / 2) * probe.scale + probe.H / 2 + probe.vy,
  };
}

/** Probe → screen point of an edge midpoint (settled layout). `anchor` selects
 *  the edge (string anchor, or `null` = the plain unanchored edge). Returns
 *  VIEWPORT coordinates (page.mouse takes client coords; wikiGraph.js reads
 *  canvas-local px as clientX − rect.left). */
async function edgeMidpoint(page, anchor) {
  return page.evaluate((anchor) => {
    const p = window.__wikiGraphProbe();
    const e = p.edges.find(e => (e.anchor || null) === anchor &&
      (anchor ? true : e.from === 'alpha:a.md'));
    if (!e) return null;
    const a = p.nodes.find(n => n.id === e.from), b = p.nodes.find(n => n.id === e.to);
    const mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2;
    const rect = document.querySelector('.wiki-graph-pane .wiki-canvas').getBoundingClientRect();
    return {
      x: rect.left + (mx - p.W / 2) * p.scale + p.W / 2 + p.vx,
      y: rect.top + (my - p.H / 2) * p.scale + p.H / 2 + p.vy,
      edge: e, a, b,
    };
  }, anchor);
}

/** Probe → viewport point of a node (canvas-local → client, same mapping). */
async function nodePoint(page, id) {
  return page.evaluate((id) => {
    const p = window.__wikiGraphProbe();
    const n = p.nodes.find(n => n.id === id);
    if (!n) return null;
    const rect = document.querySelector('.wiki-graph-pane .wiki-canvas').getBoundingClientRect();
    return {
      x: rect.left + (n.x - p.W / 2) * p.scale + p.W / 2 + p.vx,
      y: rect.top + (n.y - p.H / 2) * p.scale + p.H / 2 + p.vy,
    };
  }, id);
}

test.beforeAll(async () => { server = await startServer(); });
test.afterAll(async () => { server.close(); });

test.describe('§P0 图谱入口封存闸（graphseal 批 82c273a2a，双态都钉）', () => {
  test('开关关（features.wikiGraph 缺席）→ #wiki-btn 不在 DOM（封存 = 摘挂，非 CSS 藏）', async ({ browser }) => {
    const { context, page, pageErrors } = await boot(browser, {}); // 默认 = 封存态（无 wikiGraph 键）
    // 封存判定窗：configData latch 走完（seal spec 同款 600ms 窗 + 余量）后仍不在场
    await page.waitForTimeout(900);
    const dump = await page.evaluate(() => {
      const bar = document.getElementById('activity-bar');
      const ids = bar ? [...bar.children].map(e => e.id) : [];
      const pi = ids.indexOf('projects-btn'), ai = ids.indexOf('agents-btn');
      return {
        wikiCount: document.querySelectorAll('#wiki-btn').length,
        wikiConnected: document.getElementById('wiki-btn')?.isConnected ?? false,
        projectsAgentsAdjacent: pi !== -1 && ai === pi + 1, // 图谱静态槽空出、边栏不塌
        paneAttached: !!document.querySelector('.wiki-graph-pane'),
      };
    });
    expect(dump.wikiCount, '🔴 开关关 = 入口不可见（不在 DOM，防复活钉）').toBe(0);
    expect(dump.wikiConnected, '入口摘挂（DOM 摘挂姿态，非 display 藏）').toBe(false);
    expect(dump.projectsAgentsAdjacent, '图谱静态槽空出：projects → agents 相邻').toBe(true);
    expect(dump.paneAttached, '封存态无图谱 pane').toBe(false);
    expect(pageErrors, 'R0 console 零错误').toEqual([]);
    await context.close();
  });

  test('开关开（features.wikiGraph:true）→ #wiki-btn 回挂静态槽（入口在位正断言）', async ({ browser }) => {
    const { context, page, pageErrors } = await boot(browser, { wikiGraph: true });
    await page.waitForSelector('#wiki-btn', { state: 'attached', timeout: 15000 });
    const dump = await page.evaluate(() => {
      const bar = document.getElementById('activity-bar');
      const ids = bar ? [...bar.children].map(e => e.id) : [];
      const pi = ids.indexOf('projects-btn'), wi = ids.indexOf('wiki-btn'), ai = ids.indexOf('agents-btn');
      return { connected: document.getElementById('wiki-btn')?.isConnected ?? false, order: { pi, wi, ai }, title: document.getElementById('wiki-btn')?.title || '' };
    });
    expect(dump.connected, '🔴 开关开 = 入口在位（静态槽回挂）').toBe(true);
    expect(dump.order.pi !== -1 && dump.order.wi === dump.order.pi + 1 && dump.order.ai === dump.order.wi + 1,
      '槽位契约：projects → wiki → agents（静态槽精确还原）').toBe(true);
    expect(dump.title, 'i18n 标题就位').not.toBe('');
    expect(pageErrors, 'R0 console 零错误').toEqual([]);
    await context.close();
  });

  // ── 产品回归钉（清欠批 2026-10-29 发现，回挂接线修复批 2026-10-30 落地转绿）──
  // 曾病：开关开的回挂入口**点了没反应**——点击接线在 main.js 动态 import().then
  // 里 boot 时注册，此刻 #wiki-btn 已被 activityBar.js detachWikiGraphEntry()
  // 摘下 DOM ⇒ per-element getElementById = null ⇒ 监听从未挂上；
  // enableWikiGraphEntry() 只回挂元素+补标题，救不了从未建立的接线（封存批
  // 「元素重插即自然复活」注释对可见性成立、对点击接线不成立；seal spec W2
  // 只断言挂载未点击，故漏网）。修法 = canvas.js 点击接线改 #activity-bar
  // 事件委托：监听挂容器一次、注册只写映射表，元素重插即复活。清欠批预留的
  // .fixme 已摘，本用例转真断言（§P1–P8 同因全族随之转绿）。
  test('开关开 → 回挂入口可点开图谱 pane（封存批回挂接线回归钉）', async ({ browser }) => {
    const { context, page } = await boot(browser, { wikiGraph: true });
    await page.waitForSelector('#wiki-btn', { state: 'attached', timeout: 15000 });
    await page.click('#wiki-btn');
    await page.waitForSelector('.wiki-graph-pane', { state: 'attached', timeout: 10000 });
    await context.close();
  });
});

test.describe('§P1 边锚保真', () => {
  test('payload 边 anchor 在去重后保留；带锚边计数入 probe', async ({ browser }) => {
    const { context, page, pageErrors } = await boot(browser, { wikiGraph: true });
    const probe = await openGraph(page);
    const edges = await page.evaluate(() => window.__wikiGraphProbe().edges);
    const anchored = edges.find(e => e.from === 'alpha:a.md' && e.to === 'alpha:g.md');
    expect(anchored, '带锚边在场').toBeTruthy();
    expect(anchored.anchor, '带锚边的 anchor 保留（edge-anchor 同形）').toBe('#^vault');
    const plain = edges.find(e => e.from === 'alpha:a.md' && e.to === 'alpha:p.md');
    expect(plain.anchor, '无锚边 anchor=null').toBeNull();
    expect(probe.anchoredEdges, '带锚边计数（含索引未覆盖的）').toBe(2);
    expect(pageErrors, 'R0 console 零错误').toEqual([]);
    await context.close();
  });
});

test.describe('§P2 引用边 hover = 段落快照小窗', () => {
  test('hover 带锚边 → .wg-tip.para 显示那一段（§ 段标题 + 段文本），probe.hoverEdge 同步', async ({ browser }) => {
    const { context, page, pageErrors } = await boot(browser, { wikiGraph: true });
    await openGraph(page);
    const mid = await edgeMidpoint(page, '#^vault');
    expect(mid, '带锚边在场').toBeTruthy();
    await page.mouse.move(mid.x, mid.y, { steps: 4 });
    const tip = page.locator('.wg-tip');
    await expect(tip).toBeVisible();
    await expect(tip).toHaveClass(/para/);
    expect(await tip.locator('.tt').textContent(), '§ 段标题（词表词条名）').toBe('§ 文档库（vault）');
    expect(await tip.locator('.ts').textContent(), '小窗 = 那一段，不是整个文档')
      .toContain('项目工作区内 docs/ 目录整体');
    expect(await tip.locator('.ts').textContent(), '非整文档摘要').not.toContain('唯一定义源');
    const he = await page.evaluate(() => window.__wikiGraphProbe().hoverEdge);
    expect(he, 'probe.hoverEdge 同步').toEqual({ from: 'alpha:a.md', to: 'alpha:g.md', anchor: '#^vault' });
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

test.describe('§P3 索引未覆盖 → 文档级回退；空白 → 隐藏', () => {
  test('锚不在段落索引 → tip 无 .para（回退文档摘要，td 仍给跳转提示）；空白处 hover → 隐藏', async ({ browser }) => {
    const { context, page, pageErrors } = await boot(browser, { wikiGraph: true });
    await openGraph(page);
    const mid = await edgeMidpoint(page, '#^ghost');
    await page.mouse.move(mid.x, mid.y, { steps: 4 });
    const tip = page.locator('.wg-tip');
    await expect(tip).toBeVisible();
    expect(await tip.getAttribute('class'), '索引未覆盖 → 无 .para（文档级预览回退）').not.toContain('para');
    expect(await tip.locator('.td').textContent(), '跳转提示仍在').toContain('点击跳转并高亮该段');
    // 空白处 hover → 浮层隐藏、hoverEdge 复位
    await page.mouse.move(30, 30, { steps: 3 });
    await expect(tip).toBeHidden();
    expect(await page.evaluate(() => window.__wikiGraphProbe().hoverEdge)).toBeNull();
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

test.describe('§P4 带锚边点击 → 跳转载荷', () => {
  test('click（无拖动）→ workspace-open-item 带 anchor={kind:block,id:vault}', async ({ browser }) => {
    const { context, page, pageErrors } = await boot(browser, { wikiGraph: true });
    await openGraph(page);
    const mid = await edgeMidpoint(page, '#^vault');
    await page.mouse.move(mid.x, mid.y, { steps: 3 });
    await page.mouse.down();
    await page.mouse.up();
    await page.waitForTimeout(300); // openDoc → dynamic import → dispatch
    const events = await page.evaluate(() => window.__openEvents);
    expect(events, '恰好派一次 open').toHaveLength(1);
    expect(events[0].absPath, '目标文档 = 带锚边的 to').toBe('/w/alpha/g.md');
    expect(events[0].id, 'file:<abs> 同一通道').toBe('file:/w/alpha/g.md');
    expect(events[0].anchor, '段落跳转载荷（markdown 查看器滚动+高亮通道）')
      .toEqual({ kind: 'block', id: 'vault' });
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

test.describe('§P5 无锚边点击 → 选中目标', () => {
  test('click plain edge → selected = 目标节点（画布语义不变）', async ({ browser }) => {
    const { context, page, pageErrors } = await boot(browser, { wikiGraph: true });
    await openGraph(page);
    const mid = await edgeMidpoint(page, null);
    await page.mouse.move(mid.x, mid.y, { steps: 3 });
    await page.mouse.down();
    await page.mouse.up();
    await page.waitForFunction(() => window.__wikiGraphProbe().selected === 'alpha:p.md', null, { timeout: 5000 });
    expect(await page.evaluate(() => window.__wikiGraphProbe().selected)).toBe('alpha:p.md');
    expect(await page.evaluate(() => window.__openEvents), '无锚边不开文档').toEqual([]);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

test.describe('§P6 详情卡引出词条带锚 chip', () => {
  test('选中 a.md → 引出列表 g.md 行显示 #^vault 锚 chip（信息性）', async ({ browser }) => {
    const { context, page, pageErrors } = await boot(browser, { wikiGraph: true });
    await openGraph(page);
    const pt = await nodePoint(page, 'alpha:a.md');
    await page.mouse.click(pt.x, pt.y);
    await page.waitForSelector('.wg-detail-body:not([hidden]) .out .ref-anchor', { timeout: 5000 });
    expect(await page.locator('.wg-detail-body .out .ref-anchor').first().textContent()).toBe('#^vault');
    expect(await page.locator('.wg-detail-body .in .ref-anchor').count(), '无锚出边无 chip').toBe(0);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

test.describe('§P8 点击→高亮 端到端（真 markdown 查看器）', () => {
  // §P4 钉到载荷为止；本节走完最后一公里：同一 anchor 载荷经 workspace-open-item
  // 进真 viewMarkdown → applyJumpAnchor → 滚动定位 + .nf-jump-flash 落在 ^vault 段。
  for (const scheme of ['light', 'dark']) {
    test(`${scheme} · anchor 载荷 → 滚动+高亮落在那一段`, async ({ browser }) => {
      const { context, page, pageErrors } = await boot(browser, { colorScheme: scheme, wikiGraph: true });
      await openGraph(page);
      const md = [
        '# 词汇表', '',
        '前置段落，不在高亮目标内。', '',
        '文档库（vault） — 项目工作区内 docs/ 目录整体：图谱视图只扫这个目录。 ^vault', '',
        '尾部段落，不在高亮目标内。',
      ].join('\n');
      await page.evaluate((md) => {
        window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: {
          id: 'wiki-remote:alpha:g.md', itemType: 'markdown', title: '词汇表',
          readOnly: true, content: md, anchor: { kind: 'block', id: 'vault' },
        } }));
      }, md);
      await page.waitForSelector('.canvas-md-viewer', { timeout: 10000 });
      await page.waitForFunction(() => {
        const el = document.querySelector('.canvas-md-viewer .nf-jump-flash');
        return el && (el.textContent || '').includes('文档库（vault）');
      }, null, { timeout: 5000 });
      await page.screenshot({ path: join(SHOTS_DIR, `paragraph-highlight-${scheme}.png`) });
      expect(pageErrors).toEqual([]);
      await context.close();
    });
  }
});

test.describe('§P7 亮暗双主题截图', () => {
  for (const scheme of ['light', 'dark']) {
    test(`${scheme} · 段落快照小窗截图`, async ({ browser }) => {
      const { context, page } = await boot(browser, { colorScheme: scheme, wikiGraph: true });
      await openGraph(page);
      const mid = await edgeMidpoint(page, '#^vault');
      await page.mouse.move(mid.x, mid.y, { steps: 4 });
      await page.locator('.wg-tip').waitFor({ state: 'visible', timeout: 5000 });
      await page.waitForTimeout(250); // backdrop-filter settle
      const pane = page.locator('.wiki-graph-pane');
      await pane.screenshot({ path: join(SHOTS_DIR, `paragraph-hover-${scheme}.png`) });
      await context.close();
    });
  }
});

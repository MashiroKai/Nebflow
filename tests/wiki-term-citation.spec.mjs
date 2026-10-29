// wiki-term-citation.spec.mjs — 词条引用第二批（前端交互）契约 spec
// （隔离静态服务器 + 页内 mock，真实 UI 代码全量执行；8100+ 端口纪律，永不触碰宿主 8080）。
//
// 施工图 = docs/design/20261029_111029_term-citation-research__delegate-nebflow-253ec234.zh-CN.md
// §7 F1-F5 + §5.2/§5.5 交互口径（返回键左上角 = 作者 2026-10-29 11:49 改判）。
// 基座 = 第一批（引擎注册表 + GET /api/wiki/term + nodeJson.terms[]）已落 main。
//
//   §T1 hover 气泡主路径（载荷命中零请求）：graph 载荷 terms[] 直读 — 词条名 +
//       定义 + 所在文档 + 跳转提示，且 /api/wiki/term 请求数 = 0；
//   §T2 hover 气泡兜底腿（词条不在载荷）：载荷无 terms[] 时 hover 打
//       GET /api/wiki/term（恰 1 次，缓存后不重复）→ 同形态气泡；未注册（404
//       面）→ 不出泡不报错；
//   §T3 点击深链跳转：点词条引用 → 开目标文档 → applyJumpAnchor 滚动 +
//       .nf-jump-flash 高亮落在词条定义段（复用既有腿零新机制）；
//   §T4 返回原文档钮（左上角）：跳转后 #canvas-refback-btn 出现在左上角；
//       单击回原页面原位置（来源页 scrollTop 保真）；来源页签关闭 ⇒ 钮隐；
//       手动打开的页签 ⇒ 钮不出现（触发条件面）；
//   §T5 非词条普通文档级 wikilink 不挂气泡（克制口径 §5.2）；
//   §T6 移动端（(hover:none)）轻点即开气泡、再点跳转（气泡内点击 + 链接二次
//       点击两腿）；
//   §T7 亮暗双主题截图（气泡 / 高亮 / 返回钮，SHOTS_DIR）；
//   R0 全流程 console 零错误（每个用例尾部断言）。

import { test, expect, devices } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '..', 'src', 'main', 'resources', 'web');
const PORT = 8199; // fixed, 8100+ — never the host 8080; disjoint from wiki-graph-paragraph (8194)
const SHOTS_DIR = process.env.SHOTS_DIR ||
  resolve(HERE, '..', 'evidence', '20261029_term-citation-batch2', 'shots');
const MIME = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml' };

// 第一批 nodeJson.terms[] 投影的消费夹具：glossary.md 两条词条（含派生别名的
// 形态面由引擎 spec 钉，此处消费面钉「载荷命中零请求」与「兜底打 API」两腿）。
const TERM_GCR = {
  name: 'GCR', aliases: [], source: 'glossary', rel: 'glossary.md',
  anchor: '#^gcr', line: 21, endLine: 21,
  definition: '银河宇宙线（Galactic Cosmic Rays）——来自太阳系外的高能带电粒子流，成分以质子为主，本文档的分析对象。',
};
const TERM_PANCHOR = {
  name: 'paragraph-anchor', aliases: [], source: 'glossary', rel: 'glossary.md',
  anchor: '#^paragraph-anchor', line: 26, endLine: 26,
  definition: '段落锚——行尾 ^id 标记的块级锚点，引用可以精确到段。',
};

function nodeWithTerms(terms) {
  const node = {
    id: 'alpha:glossary.md', project: 'alpha', device: 'self', rel: 'glossary.md',
    title: '词汇表', kind: 'doc', type: 'glossary', status: 'current',
    summary: '文档库的唯一定义源。',
    paragraphs: [
      { anchor: '#^gcr', kind: 'block', title: 'GCR', line: 21, endLine: 21, text: TERM_GCR.definition },
      { anchor: '#^paragraph-anchor', kind: 'block', title: 'paragraph-anchor', line: 26, endLine: 26, text: TERM_PANCHOR.definition },
    ],
  };
  if (terms !== null) node.terms = terms;   // null = 整键缺席（陈旧载荷形态）
  return node;
}

const WIKI_GRAPH = (terms) => ({
  projects: [{ name: 'alpha', vault: '/w/alpha', docCount: 3 }],
  nodes: [
    nodeWithTerms(terms),
    { id: 'alpha:a.md', project: 'alpha', device: 'self', rel: 'a.md', title: 'Alpha Note', kind: 'doc',
      summary: 'Alpha 首篇笔记。', paragraphs: [], terms: [] },
    { id: 'alpha:p.md', project: 'alpha', device: 'self', rel: 'p.md', title: 'Plain Doc', kind: 'doc',
      summary: '没有词条的普通文档。', paragraphs: [], terms: [] },
  ],
  edges: [],
});

// 引用来源文档：一条词条短引 [[GCR]] + 一条普通文档级 wikilink [[p.md]]（§T5）。
const SRC_MD = [
  '# Alpha Note', '',
  '开头段。', '',
  '这里的 [[GCR]] 是词条短形引用，[[p.md]] 是普通文档级引用。', '',
  ...Array.from({ length: 40 }, (_, i) => `填充段落 ${i + 1} —— 撑出滚动距离。`),
  '',
].join('\n');

// 词条目标文档：^gcr 块锚定义段在页中段（深链跳转后可见滚动 + 高亮）。
// 50 个前置段落 —— 内容必须真溢出视口（scrollHeight > clientHeight），否则
// scrollHeadingToTop 的 clamp 把滚动收 0（浏览器自有行为，§P8 同先例）。
const GLOSSARY_MD = [
  '# 词汇表', '',
  ...Array.from({ length: 50 }, (_, i) => `前置段落 ${i + 1} —— 把定义段推到滚动可见距离之外。`),
  '',
  'GCR — 银河宇宙线（Galactic Cosmic Rays）——来自太阳系外的高能带电粒子流。 ^gcr', '',
  '收尾段落。',
].join('\n');

const FILES = {
  '/w/alpha/a.md': { content: SRC_MD, fileName: 'a.md' },
  '/w/alpha/glossary.md': { content: GLOSSARY_MD, fileName: 'glossary.md' },
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

/** boot：隔离静态服务器 + mock 面。`graphTerms` 控制载荷 terms[]（null = 整键
 *  缺席，数组 = 注入词条）；termApi=false 时 term 端点应 404（未注册面）。
 *  返回 getTermApiHits()（Node 侧请求计数——兜底腿请求数的唯一真源）。 */
async function boot(browser, {
  colorScheme = 'dark',
  viewport = { width: 1440, height: 900 },
  graphTerms = [TERM_GCR, TERM_PANCHOR],
  termApi = true,
  deviceDescriptor = null,
} = {}) {
  const contextOptions = deviceDescriptor
    ? { ...deviceDescriptor, colorScheme }
    : { colorScheme, viewport };
  const context = await browser.newContext(contextOptions);
  const page = await context.newPage();
  const pageErrors = [];
  let termApiHits = 0;
  page.on('pageerror', (e) => pageErrors.push(String(e && e.message || e)));
  page.on('console', (m) => { if (m.type() === 'error') pageErrors.push(m.text()); });
  if (viewport && !deviceDescriptor) await page.setViewportSize(viewport);
  await page.emulateMedia({ colorScheme });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('stand_locale', 'zh-CN');
    window.__openEvents = [];
    // capture phase：记全量 open 事件（含 refSourceTab），供记账面断言
    window.addEventListener('workspace-open-item', (e) => {
      const d = e.detail || {};
      window.__openEvents.push({
        id: d.id, absPath: d.absPath, anchor: d.anchor, itemType: d.itemType, refSourceTab: d.refSourceTab,
      });
    }, true);
  });
  await page.route('**/api/**', r => r.fulfill({ json: {} }));
  await page.route('**/api/wiki/graph', r => r.fulfill({ json: WIKI_GRAPH(graphTerms) }));
  await page.route('**/api/wiki/term**', (r) => {
    termApiHits += 1;
    if (!termApi) { r.fulfill({ json: { found: false, resolved: false, name: 'X' } }); return; }
    r.fulfill({ json: { found: true, resolved: true, term: TERM_GCR } });
  });
  await page.route('**/agents/**', r => r.fulfill({ json: {} }));
  await page.route('**/api/neblink/status', r => r.fulfill({ json: { loggedIn: false, device: null, peers: [] } }));
  await page.routeWebSocket(/\/ws/, ws => {
    ws.onMessage(raw => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      if (m.type === 'autostartStatus') ws.send(JSON.stringify({ type: 'autostartStatusResult', enabled: false, supported: true }));
      if (m.type === 'pop.readFile') {
        const f = FILES[m.path];
        if (f) {
          ws.send(JSON.stringify({
            type: 'fileContent', path: m.path, absPath: m.path,
            content: f.content, fileName: f.fileName, itemType: 'markdown', size: f.content.length,
          }));
        } else {
          ws.send(JSON.stringify({ type: 'fileContent', path: m.path, absPath: m.path, error: 'spec-mock: no fixture' }));
        }
      }
    });
  });
  await page.goto(base);
  return { context, page, pageErrors, getTermApiHits: () => termApiHits };
}

/** 直接以带内容 payload 打开真页签（readFile 往返旁路——§P8 同法），
 *  等 markdown 查看器渲染完成。 */
async function openSourceDoc(page) {
  await page.evaluate((content) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: {
      id: 'file:/w/alpha/a.md', itemType: 'markdown', title: 'a.md',
      content, absPath: '/w/alpha/a.md',
    } }));
  }, FILES['/w/alpha/a.md'].content);
  await page.waitForSelector('.canvas-md-viewer', { timeout: 10000 });
  // 词条面就绪（F3 消解 pass 完成的可观察面：术语链接带 nf-wikilink-term）
  await page.waitForSelector('a.nf-wikilink-term', { timeout: 10000 });
  // 版面落定再 hover：开页初期字体/图标仍在搬运，链接会位移——鼠标落点下的
  // 位移会立刻 mouseleave 把 150ms 防抖计时清掉（悬停出泡的正当收场，非缺陷）。
  // 断言面等几何两次读数一致再动鼠标。
  await expect.poll(async () => page.evaluate(() => {
    const a = document.querySelector('a.nf-wikilink-term');
    return a ? JSON.stringify(a.getBoundingClientRect()) : 'pending';
  }).then(async (r1) => {
    await new Promise(res => setTimeout(res, 300));
    const r2 = await page.evaluate(() => {
      const a = document.querySelector('a.nf-wikilink-term');
      return a ? JSON.stringify(a.getBoundingClientRect()) : 'pending';
    });
    return r1 !== 'pending' && r1 === r2;
  }), { timeout: 10000 }).toBe(true);
}

/** 打开目标词条文档（真 readFile 腿 mock 应答）并等高亮落点 + 滚动静止。 */
async function openGlossaryViaReadFile(page) {
  await page.waitForFunction(() => document.querySelectorAll('.canvas-md-viewer').length >= 2, null, { timeout: 10000 });
  await page.waitForFunction(() => {
    const el = document.querySelector('.canvas-md-viewer .nf-jump-flash');
    return el && (el.textContent || '').includes('GCR');
  }, null, { timeout: 8000 });
  // smooth scrollTo 落定（scrollHeadingToTop 走 smooth 档，落定后离顶）
  await page.waitForTimeout(900);
  const landed = await page.evaluate(() => {
    const panes = [...document.querySelectorAll('.canvas-tab-pane.active .canvas-md-scroll')];
    return panes.length ? panes[0].scrollTop : -1;
  });
  expect(landed, '定义段滚动定位（块锚 → 行号映射，落点后已离顶）').toBeGreaterThan(0);
}

const activeTabId = (page) => page.evaluate(() => {
  const pane = document.querySelector('.canvas-tab-pane.active');
  return pane ? pane.dataset.tabId : null;
});

test.beforeAll(async () => { server = await startServer(); });
test.afterAll(async () => { server.close(); });

test.describe('§T1 hover 气泡主路径（载荷命中零请求）', () => {
  test('terms[] 直读出泡：词条名+定义+所在文档+跳转提示；/api/wiki/term 请求数=0', async ({ browser }) => {
    const { context, page, pageErrors, getTermApiHits } = await boot(browser);
    await openSourceDoc(page);
    const link = page.locator('a.nf-wikilink-term', { hasText: 'GCR' });
    await expect(link).toHaveClass(/nf-wikilink-resolved/);
    await link.hover();
    const tip = page.locator('.nf-term-tip');
    await expect(tip).toBeVisible({ timeout: 5000 });
    expect(await tip.locator('.tt').textContent(), '词条名').toBe('GCR');
    expect(await tip.locator('.ts').textContent(), '定义文本（载荷 terms[].definition）')
      .toContain('银河宇宙线（Galactic Cosmic Rays）');
    expect(await tip.locator('.td').textContent(), '所在文档标题').toContain('词汇表');
    expect(await tip.locator('.td').textContent(), '跳转提示').toContain('点击跳转');
    expect(await tip.getAttribute('class'), '桌面面无 .touch（pointer-events:none 不挡点击）').not.toContain('touch');
    await page.waitForTimeout(400);  // 兜底腿若被误挂，150ms 防抖+fetch 已会落地
    expect(getTermApiHits(), '载荷命中 = 兜底 API 零请求').toBe(0);
    expect(pageErrors, 'R0 console 零错误').toEqual([]);
    await context.close();
  });

  test('hover 防抖：掠过不闪泡', async ({ browser }) => {
    const { context, page, pageErrors } = await boot(browser);
    await openSourceDoc(page);
    const link = page.locator('a.nf-wikilink-term', { hasText: 'GCR' });
    // 掠过（<150ms 即离场）→ 不出泡
    await link.hover();
    await page.waitForTimeout(60);
    await page.mouse.move(30, 30);
    await page.waitForTimeout(400);
    await expect(page.locator('.nf-term-tip')).toBeHidden();
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

test.describe('§T2 hover 气泡兜底腿（词条不在载荷 → GET /api/wiki/term）', () => {
  test('载荷 terms[] 缺席：hover 恰打 1 次 term API → 同形态气泡；二次 hover 走缓存不重复打', async ({ browser }) => {
    const { context, page, pageErrors, getTermApiHits } = await boot(browser, { graphTerms: null });
    await openSourceDoc(page);
    const link = page.locator('a.nf-wikilink-term', { hasText: 'GCR' });
    await expect(link).toHaveClass(/nf-wikilink-unresolved/);   // 文档三腿未命中 → 现状 unresolved 态
    await link.hover();
    const tip = page.locator('.nf-term-tip');
    await expect(tip).toBeVisible({ timeout: 5000 });
    expect(await tip.locator('.tt').textContent(), '兜底腿同名词条').toBe('GCR');
    expect(await tip.locator('.ts').textContent()).toContain('银河宇宙线');
    await expect.poll(() => getTermApiHits(), { timeout: 5000 }).toBe(1);
    // 移出再回：缓存腿（零新请求）
    await page.mouse.move(30, 30);
    await expect(tip).toBeHidden();
    await link.hover();
    await expect(tip).toBeVisible({ timeout: 5000 });
    expect(getTermApiHits(), 'termApiCache 命中 → 恰 1 次').toBe(1);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('API 未注册（404 面）：不出泡不报错', async ({ browser }) => {
    const { context, page, pageErrors } = await boot(browser, { graphTerms: null, termApi: false });
    await openSourceDoc(page);
    const link = page.locator('a.nf-wikilink-term', { hasText: 'GCR' });
    await link.hover();
    await page.waitForTimeout(500);
    await expect(page.locator('.nf-term-tip')).toBeHidden();
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

test.describe('§T3 点击深链跳转 + 高亮', () => {
  for (const scheme of ['light', 'dark']) {
    test(`${scheme} · 点 [[GCR]] → 开词汇表 → 滚动定位 + nf-jump-flash 落在定义段`, async ({ browser }) => {
      const { context, page, pageErrors } = await boot(browser, { colorScheme: scheme });
      await openSourceDoc(page);
      // 带锚产物在 dataset（F1 消解链）；点击腿产 anchor={kind:block,id:gcr}+refSourceTab
      const openBefore = await page.evaluate(() => window.__openEvents.length);
      await page.locator('a.nf-wikilink-term', { hasText: 'GCR' }).click();
      await page.waitForFunction((n) => window.__openEvents.length > n, openBefore, { timeout: 5000 });
      // 取跳转载荷（带 anchor 的那一跳）——readFile 应答再派发的 open 无 anchor
      const ev = (await page.evaluate(() => window.__openEvents)).find(e => e.absPath === '/w/alpha/glossary.md' && e.anchor);
      expect(ev, '词条跳转载荷在场').toBeTruthy();
      expect(ev.anchor, '词条命中落点 = 定义块锚（F2）').toEqual({ kind: 'block', id: 'gcr' });
      expect(ev.refSourceTab, '来源页签 id（F5 记账面）').toBe('file:/w/alpha/a.md');
      // 真渲染链：readFile mock 应答 → 查看器 → applyJumpAnchor 滚动+高亮
      await openGlossaryViaReadFile(page);
      await page.screenshot({ path: join(SHOTS_DIR, `term-jump-highlight-${scheme}.png`) });
      expect(pageErrors).toEqual([]);
      await context.close();
    });
  }
});

test.describe('§T4 返回原文档钮（左上角；单击回原页面原位置）', () => {
  test('跳转后钮在左上角；单击回来源页签且 scrollTop 保真；手动打开无钮', async ({ browser }) => {
    const { context, page, pageErrors } = await boot(browser);
    await openSourceDoc(page);
    // 手动打开态（此刻无引用跳转来历）：钮不存在
    expect(await page.locator('#canvas-refback-btn').count(), '手动打开页签不出现返回钮').toBe(0);
    // 来源页滚出非零阅读位，但把词条链接留在视口内——若链接被滚出视口，
    // Playwright click 的 scrollIntoViewIfNeeded 会先把滚动盒改写（真实用户
    // 鼠标点击从不滚动；那不是产品行为），阅读位断言就失去意义。
    const srcScroll = await page.evaluate(() => {
      const sc = document.querySelector('.canvas-tab-pane.active .canvas-md-scroll');
      const a = document.querySelector('a.nf-wikilink-term');
      const delta = a.getBoundingClientRect().top - sc.getBoundingClientRect().top - sc.clientHeight / 3;
      sc.scrollTop = Math.max(80, sc.scrollTop + delta);
      sc.dispatchEvent(new Event('scroll'));
      return sc.scrollTop;
    });
    expect(srcScroll, '非零阅读位且链接仍在视口内').toBeGreaterThan(0);
    await expect(page.locator('a.nf-wikilink-term', { hasText: 'GCR' })).toBeVisible();
    // 词条引用跳转（链接已在视口内，click 协议步不会改滚动盒）
    await page.locator('a.nf-wikilink-term', { hasText: 'GCR' }).click();
    await openGlossaryViaReadFile(page);
    expect(await activeTabId(page), '当前页签 = 词汇表').toBe('file:/w/alpha/glossary.md');
    const btn = page.locator('#canvas-refback-btn');
    await expect(btn).toBeVisible();
    expect(await btn.textContent(), '钮文案（i18n）').toContain('返回原文档');
    // 左上角落点（作者令 2026-10-29 11:49）：钮左上角坐标在面板左上角区
    const geom = await page.evaluate(() => {
      const b = document.getElementById('canvas-refback-btn').getBoundingClientRect();
      const panel = document.getElementById('canvas-panel').getBoundingClientRect();
      return { btnTop: b.top, btnLeft: b.left, panelTop: panel.top, panelLeft: panel.left };
    });
    expect(geom.btnTop - geom.panelTop, '左上角：贴面板顶行').toBeLessThan(30);
    expect(geom.btnLeft - geom.panelLeft, '左上角：贴面板左缘').toBeLessThan(30);
    await page.screenshot({ path: join(SHOTS_DIR, 'term-back-button.png') });
    // 单击回原页面原位置
    await btn.click();
    await expect.poll(async () => activeTabId(page), { timeout: 5000 })
      .toBe('file:/w/alpha/a.md');
    const backScroll = await page.evaluate(() =>
      document.querySelector('.canvas-tab-pane.active .canvas-md-scroll').scrollTop);
    expect(backScroll, '来源页 scrollTop 保真（pane 切换不销毁阅读位）').toBe(srcScroll);
    await expect(btn, '回到来源页（无引用来历）→ 钮隐').toBeHidden();
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('来源页签关闭 ⇒ 钮即时隐去', async ({ browser }) => {
    const { context, page, pageErrors } = await boot(browser);
    await openSourceDoc(page);
    await page.locator('a.nf-wikilink-term', { hasText: 'GCR' }).click();
    await openGlossaryViaReadFile(page);
    await expect(page.locator('#canvas-refback-btn')).toBeVisible();
    // 关闭来源页签（a.md）
    await page.evaluate(() => {
      const tabs = document.querySelectorAll('#canvas-tab-bar .canvas-tab');
      const aTab = [...tabs].find(t => t.dataset.tabId === 'file:/w/alpha/a.md');
      aTab.querySelector('.canvas-tab-close').click();
    });
    await page.waitForTimeout(400);  // 关闭动画 + teardownTab finalize
    await expect(page.locator('#canvas-refback-btn')).toBeHidden({ timeout: 5000 });
    expect(await page.locator('#canvas-refback-btn').count(), 'DOM 已移除').toBe(0);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

test.describe('§T5 非词条普通文档级 wikilink 不挂气泡（克制口径 §5.2）', () => {
  test('[[p.md]]（普通文档引用）hover 零泡零请求', async ({ browser }) => {
    const { context, page, pageErrors, getTermApiHits } = await boot(browser);
    await openSourceDoc(page);
    const plain = page.locator('a.nf-wikilink', { hasText: 'p.md' });
    await expect(plain).toHaveClass(/nf-wikilink-resolved/);
    expect(await plain.getAttribute('class'), '非词条链接不挂术语面').not.toContain('nf-wikilink-term');
    await plain.hover();
    await page.waitForTimeout(450);
    await expect(page.locator('.nf-term-tip')).toBeHidden();
    expect(getTermApiHits(), '零 term API 请求').toBe(0);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

test.describe('§T6 移动端（(hover:none)）轻点即开气泡、再点跳转', () => {
  const iPhone = devices['iPhone 13'];

  // 手机层 boot 抑制闸（layoutController watchBootAutoOpen）：首次用户交互前
  // 出现的 canvas-open 一律收起——先做一次无净副作用的真实交互（左列键开合）
  // 再开文档，卡片层才守得住。
  async function touchMobileLayer(page) {
    const head = page.locator('.mh-dd-hd').first();
    await head.click();
    await head.click();
  }

  test('首击开泡不导航；气泡内点击跳转', async ({ browser }) => {
    const { context, page, pageErrors } = await boot(browser, { deviceDescriptor: iPhone });
    await touchMobileLayer(page);
    expect(await page.evaluate(() => window.matchMedia('(hover: none)').matches), '(hover:none) 环境成立').toBe(true);
    await openSourceDoc(page);
    const ev0 = await page.evaluate(() => window.__openEvents.length);
    const link = page.locator('a.nf-wikilink-term', { hasText: 'GCR' });
    // 首击：开泡、零导航
    await link.tap();
    const tip = page.locator('.nf-term-tip');
    await expect(tip).toBeVisible({ timeout: 5000 });
    expect(await tip.getAttribute('class'), '触屏面 .touch（气泡可点）').toContain('touch');
    expect(await tip.locator('.tt').textContent()).toBe('GCR');
    expect(await page.evaluate((n) => window.__openEvents.length, ev0), '首击不导航').toBe(ev0);
    await page.screenshot({ path: join(SHOTS_DIR, 'term-tap-mobile.png') });
    // 气泡内点击 = 跳转
    await tip.click();
    await page.waitForFunction((n) => window.__openEvents.length > n, ev0, { timeout: 5000 });
    const ev = (await page.evaluate(() => window.__openEvents)).find(e => e.absPath === '/w/alpha/glossary.md' && e.anchor);
    expect(ev, '词条跳转载荷在场').toBeTruthy();
    expect(ev.anchor).toEqual({ kind: 'block', id: 'gcr' });
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('链接二次点击 = 跳转（首击只开泡）', async ({ browser }) => {
    const { context, page, pageErrors } = await boot(browser, { deviceDescriptor: iPhone });
    await touchMobileLayer(page);
    await openSourceDoc(page);
    const ev0 = await page.evaluate(() => window.__openEvents.length);
    const link = page.locator('a.nf-wikilink-term', { hasText: 'GCR' });
    await link.tap();
    await expect(page.locator('.nf-term-tip')).toBeVisible({ timeout: 5000 });
    expect(await page.evaluate((n) => window.__openEvents.length, ev0), '首击仍零导航').toBe(ev0);
    await link.tap();   // 二击：气泡已开 → 落回深链跳转腿
    await page.waitForFunction((n) => window.__openEvents.length > n, ev0, { timeout: 5000 });
    const ev = (await page.evaluate(() => window.__openEvents)).find(e => e.absPath === '/w/alpha/glossary.md' && e.anchor);
    expect(ev, '词条跳转载荷在场').toBeTruthy();
    expect(ev.anchor).toEqual({ kind: 'block', id: 'gcr' });
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});


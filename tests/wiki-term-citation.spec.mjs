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
//   §T3 点击预览：桌面词条点击显示定义弹层，不生成导航事件；
//   §T4 返回按钮边界：预览不创建目标页签或引用返回按钮；
//   §T5 非词条普通文档级 wikilink 不挂气泡（克制口径 §5.2）；
//   §T6 iPhone 13 隔离模拟触屏下的轻点预览、视口约束、关闭/焦点、长定义滚动；
//   §T7 亮暗主题截图（桌面与模拟触屏弹层，SHOTS_DIR）；
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
  await page.waitForSelector('.canvas-md-viewer', { state: 'attached', timeout: 10000 });
  if (await page.evaluate(() => window.matchMedia('(hover: none)').matches)) {
    await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click();
  }
  await page.waitForSelector('.canvas-md-viewer', { state: 'visible', timeout: 10000 });
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

test.beforeAll(async () => { server = await startServer(); });
test.afterAll(async () => { server.close(); });

test.describe('§T1 hover 气泡主路径（载荷命中零请求）', () => {
  test('terms[] 直读出泡：词条名+定义+跳转提示（文档名下沉到词条索引块）；/api/wiki/term 请求数=0', async ({ browser }) => {
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
    // 作者令（term-system-v2 词条视觉面批 2026-11-05）：「词条被使用的时候，应该是
    // 一个蓝色的文字，然后不需要显示文档名」——气泡的显示字符串只剩词条名 + 定义，
    // 所在文档整体下沉到正文尾部的词条索引块（markdown.js renderTermIndex）。
    expect(await tip.locator('.td').textContent(), '气泡不再显示文档名').not.toContain('词汇表');
    expect(await tip.locator('.td').textContent(), '预览不显示导航提示').toBe('');
    const index = page.locator('.canvas-md-viewer .nf-term-index');
    await expect(index, '词条索引块（本文引用的词条所在文档）').toHaveCount(1);
    expect(await index.locator('summary').textContent(), 'summary = 词条索引（N）').toBe('词条索引（1）');
    expect(await index.locator('li').count(), 'GCR 一条目').toBe(1);
    expect(await index.locator('.nf-term-index-docname').first().textContent(), '条目带所在文档').toBe('词汇表');
    expect(await index.locator('a.nf-term-index-term').first().getAttribute('data-wiki-resolved'),
      '条目可点击跳转（复用既有 wikilink 委派）').toBeTruthy();
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

test.describe('§T3 点击词条显示预览', () => {
  for (const scheme of ['light', 'dark']) {
    test(`${scheme} · 点 [[GCR]] 显示释义，不导航`, async ({ browser }) => {
      const { context, page, pageErrors } = await boot(browser, { colorScheme: scheme });
      await openSourceDoc(page);
      // Click-preview contract: activating a term link shows its definition without navigation.
      const openBefore = await page.evaluate(() => window.__openEvents.length);
      await page.locator('a.nf-wikilink-term', { hasText: 'GCR' }).click();
      const tip = page.locator('.nf-term-tip');
      await expect(tip).toBeVisible();
      expect(await tip.locator('.tt').textContent()).toBe('GCR');
      expect(await tip.locator('.ts').textContent()).toContain('银河宇宙线');
      expect(await page.evaluate(() => window.__openEvents.length), '点击预览不导航').toBe(openBefore);
      expect(await page.locator('.canvas-md-viewer').count()).toBe(1);
      await page.screenshot({ path: join(SHOTS_DIR, `term-popover-${scheme}.png`) });
      expect(pageErrors).toEqual([]);
      await context.close();
    });
  }
});

test.describe('§T4 返回按钮不随预览出现', () => {
  test('词条预览不创建返回按钮或目标页签', async ({ browser }) => {
    const { context, page, pageErrors } = await boot(browser);
    await openSourceDoc(page);
    expect(await page.locator('#canvas-refback-btn').count()).toBe(0);
    const openBefore = await page.evaluate(() => window.__openEvents.length);
    await page.locator('a.nf-wikilink-term', { hasText: 'GCR' }).click();
    await expect(page.locator('.nf-term-tip')).toBeVisible();
    expect(await page.evaluate(() => window.__openEvents.length), '点击预览不导航').toBe(openBefore);
    await expect(page.locator('#canvas-refback-btn')).toHaveCount(0);
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

test.describe('§T6 移动端词条点击小弹层', () => {
  const iPhone = devices['iPhone 13'];

  for (const scheme of ['light', 'dark']) {
    test(`${scheme} · 词条点击显示视口内小弹层，不导航、不切换手机页面`, async ({ browser }) => {
      const { context, page, pageErrors } = await boot(browser, { deviceDescriptor: iPhone, colorScheme: scheme });
      expect(await page.evaluate(() => window.matchMedia('(hover: none)').matches), '(hover:none) 环境成立').toBe(true);
      await openSourceDoc(page);
      const ev0 = await page.evaluate(() => window.__openEvents.length);
      const nav0 = await page.evaluate(() => JSON.stringify(window.__nfMobileNav?.stack || []));
      const link = page.locator('a.nf-wikilink-term', { hasText: 'GCR' });
      await link.tap();
      const tip = page.locator('.nf-term-tip');
      await expect(tip).toBeVisible({ timeout: 5000 });
      expect(await tip.locator('.tt').textContent()).toBe('GCR');
      expect(await tip.getAttribute('class')).not.toContain('touch');
      const geom = await tip.evaluate(el => {
        const r = el.getBoundingClientRect();
        return { x: r.x, y: r.y, width: r.width, height: r.height, vw: innerWidth, vh: innerHeight };
      });
      expect(geom.width).toBeLessThan(geom.vw);
      expect(geom.height).toBeLessThan(geom.vh);
      expect(geom.x).toBeGreaterThanOrEqual(0);
      expect(geom.y).toBeGreaterThanOrEqual(0);
      expect(geom.x + geom.width).toBeLessThanOrEqual(geom.vw);
      expect(geom.y + geom.height).toBeLessThanOrEqual(geom.vh);
      expect(await page.evaluate(n => window.__openEvents.length, ev0), '打开弹层不导航').toBe(ev0);
      expect(await page.evaluate(() => JSON.stringify(window.__nfMobileNav?.stack || [])), '导航栈不变').toBe(nav0);
      await page.screenshot({ path: join(SHOTS_DIR, `term-popover-mobile-${scheme}.png`) });
      await tip.evaluate(el => el.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true })));
      await tip.click();
      expect(await page.evaluate(n => window.__openEvents.length, ev0), '点弹层本身仍不导航').toBe(ev0);
      await page.locator('body').tap({ position: { x: 8, y: 8 } });
      await expect(tip).toBeHidden();
      expect(pageErrors).toEqual([]);
      await context.close();
    });
  }

  test('超长定义在弹层内滚动；Escape 关闭并还焦', async ({ browser }) => {
    const { context, page, pageErrors } = await boot(browser, { deviceDescriptor: iPhone, colorScheme: 'dark' });
    await openSourceDoc(page);
    const link = page.locator('a.nf-wikilink-term', { hasText: 'GCR' });
    await link.evaluate(a => {
      a.dataset.wikiResolved = JSON.stringify({ term: { name: 'GCR', definition: '长定义。'.repeat(500) } });
      const style = document.createElement('style');
      style.textContent = '.nf-term-tip { max-height: 120px !important; }';
      document.head.appendChild(style);
    });
    await link.tap();
    const tip = page.locator('.nf-term-tip');
    await expect(tip).toBeVisible({ timeout: 5000 });
    expect(await tip.evaluate(el => el.scrollHeight > el.clientHeight), '内容可在弹层内滚动').toBe(true);
    await page.keyboard.press('Escape');
    await expect(tip).toBeHidden();
    expect(await link.evaluate(el => el === document.activeElement), '焦点返回触发词条').toBe(true);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});


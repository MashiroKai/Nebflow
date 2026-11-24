// tests/canvas-document-mentions-reveal-real.mjs — S2 文档词条 → 文件浏览器定位
// （#2936 阶段二，canvas-doc-terms 批 2026-11-24 接手批）。
//
// 四态断言（已裁定）：①根内逐级定位+高亮+焦点 ②根外提示+动作钮（不静默换根）
// ③不存在短提示不抛错 ④侧栏折叠先展开。附带：超长文件名（不溢出/不换行/行高
// 不变）、逐级等待时序（子请求不早于父应答）、缺省根（无 explorerRoot 时以根列
// 应答 resolvedPath 为基点）。
//
// 矩阵：node tests/canvas-document-mentions-reveal-real.mjs [engine] [viewport]
//   [fault] [baselineWebRoot] —— engine∈chromium|webkit，viewport∈desktop|mobile
//   （1280 / 375）。同名环境变量（ENGINE/VIEWPORT/FAULT/NEBFLOW_WEB_ROOT）作
//   为回退面同样生效。
// 负控（详见 tests/canvas-document-mentions-controls.md）：
//   · 基点负控：argv[4] = 基点 4164dd49f 工作树的 web 根 —— 词条化不存在 ⇒
//     首条断言（词条链接在场）即红；
//   · argv[2] = noreveal|silent-reroot|missing-silent|nowait —— 实现树响应时
//     故障注入，各自应在指定断言红；无注入全绿。
import { createRequire } from 'node:module';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const engine = process.argv[2] || process.env.ENGINE || 'chromium';
const viewport = process.argv[3] || process.env.VIEWPORT || 'desktop';
const fault = process.argv[4] || process.env.FAULT || '';
if (process.argv[5]) process.env.NEBFLOW_WEB_ROOT = process.argv[5]; // 动态导入前生效
const { createSession, webRoot } = await import('../scripts/lib/visual-harness.mjs');
const isBaseline = !!process.env.NEBFLOW_WEB_ROOT;
const require = createRequire(import.meta.url);
const pw = require('/Users/kaiyu/Claude code/Stand/node_modules/playwright');
const browser = await pw[engine].launch();

// ── 夹具 ────────────────────────────────────────────────────────────────
const ROOT = '/fixture/home';   // explorer 显式根（localStorage 持久）
const VAULT = '/fixture';       // 项目 vault（词条 absPath 基 = vault + rel）
const LONG = 'AVeryVeryLongDocumentNameForRevealOverflowCheckBatch0123456789abcdef';
const file = (name) => ({ name, type: 'file', size: 64, mtime: 1757000000000 });
const deepEntries = [
  ...Array.from({ length: 40 }, (_, i) => file(`filler-${String(i).padStart(2, '0')}.md`)),
  file('TargetDoc.md'),
  file(LONG + '.md'),
];
const trees = {
  [`${ROOT}||`]: { resolvedPath: ROOT, entries: [{ name: 'design', type: 'dir' }] },
  [`${ROOT}||design`]: { resolvedPath: ROOT, delay: 150, entries: [{ name: 'deep', type: 'dir' }] },
  [`${ROOT}||design/deep`]: { resolvedPath: ROOT, entries: deepEntries },
  '/fixture/away||': { resolvedPath: '/fixture/away', entries: [file('OutsideDoc.md')] },
};
const content = `# Reveal fixture\n\nTargetDoc MissingDoc OutsideDoc ${LONG}\n`;
const nodes = [
  { project: 'p', device: 'self', rel: 'home/current.md', title: 'Current', stub: false },
  { project: 'p', device: 'self', rel: `home/design/deep/TargetDoc.md`, title: 'TargetDoc', stub: false },
  { project: 'p', device: 'self', rel: `home/design/deep/${LONG}.md`, title: LONG, stub: false },
  { project: 'p', device: 'self', rel: 'home/design/deep/MissingDoc.md', title: 'MissingDoc', stub: false },
  { project: 'p', device: 'self', rel: 'away/OutsideDoc.md', title: 'OutsideDoc', stub: false },
];
const graph = { projects: [{ name: 'p', device: 'self', vault: VAULT, workspace: VAULT }], nodes, edges: [], stats: {} };

/** listDir 打桩置换：按 (rootPath||path) 键答树，带可选延迟；全程记录
 *  sent/sentAt/replyAt —— 逐级等待的时序断言面。 */
async function installMockTrees(session, page, treesArg) {
  await session.page.evaluate((trees) => {
    const mock = window.__wsMock;
    window.__revealLog = { sent: {}, sentAt: {}, replyAt: {} };
    const answer = (f) => {
      const key = (f.rootPath || '') + '||' + (f.path || '');
      window.__revealLog.sent[key] = (window.__revealLog.sent[key] || 0) + 1;
      window.__revealLog.sentAt[key] = Date.now();
      const entry = trees[key];
      const delay = entry && entry.delay ? entry.delay : 0;
      const reply = {
        type: 'dirListing', path: f.path,
        resolvedPath: entry ? entry.resolvedPath : (f.rootPath || ''),
        entries: entry ? entry.entries : [],
      };
      setTimeout(() => {
        window.__revealLog.replyAt[key] = Date.now();
        try { mock.onmessage && mock.onmessage({ data: JSON.stringify(reply) }); } catch (_) {}
      }, delay);
    };
    mock.send = function (data) {
      let f = null;
      try { f = JSON.parse(data); } catch (_) {}
      if (f) window.__wsSent.push(f);
      if (f && f.type === 'listDir') answer(f);
    };
    // autoRespond:false 挂起的 boot 期根列请求补答（只此一次，不重复记 sent）。
    for (const f of (window.__wsSent || []).slice()) if (f && f.type === 'listDir') answer(f);
  }, treesArg);
}

/** 响应时故障注入（S1 同法：源码字符串替换 + 目标在场断言）。 */
async function installFaultRoutes(session) {
  if (!fault) return;
  const file = fault === 'noreveal' ? '/js/viewers/markdown.js' : '/js/explorer.js';
  await session.page.route('**' + file, async (route) => {
    let source = readFileSync(webRoot() + file, 'utf8');
    if (fault === 'noreveal') {
      const original = 'if (wikilink.dataset.wikiMention) revealMentionInExplorer(';
      assert.ok(source.includes(original), 'fault injection target present: noreveal');
      source = source.replace(original, 'if ((window.__revealFaultVisits = (window.__revealFaultVisits || 0) + 1, false)) revealMentionInExplorer(');
    } else if (fault === 'silent-reroot') {
      const original = "window.__showToast?.(t('explorer.revealOutsideRoot'), 'info', {";
      assert.ok(source.includes(original), 'fault injection target present: silent-reroot');
      source = source.replace(original, "openExplorerAt(dir); window.__showToast?.(t('explorer.revealOutsideRoot'), 'info', {");
    } else if (fault === 'missing-silent') {
      const original = "if (outcome === 'missing') window.__showToast?.(t('explorer.revealMissing'), 'info');";
      assert.ok(source.includes(original), 'fault injection target present: missing-silent');
      source = source.replace(original, "if ((window.__revealFaultVisits = (window.__revealFaultVisits || 0) + 1, false)) window.__showToast?.(t('explorer.revealMissing'), 'info');");
    } else if (fault === 'nowait') {
      const original = 'await awaitDirChildren(dir)';
      assert.ok(source.includes(original), 'fault injection target present: nowait');
      source = source.replace(original, "await Promise.resolve('done')");
    } else {
      assert.fail('unknown FAULT: ' + fault);
    }
    await route.fulfill({ status: 200, contentType: 'text/javascript', body: source });
  });
}

const awaitReveal = (page, rel) => page.waitForFunction((p) => {
  const row = document.querySelector(`.explorer-item[data-path="${CSS.escape(p)}"]`);
  return !!(row && row.classList.contains('reveal-target'));
}, rel, { timeout: 10000 });

/** 词条气泡是常驻 dialog（fixed 盖在相邻文本上）：连续点第二个词条前走产品
 *  自身的关闭钮收起（S1 的点击显气泡行为不变，这里只是复现用户的自然顺序）。 */
const closeTip = async (page) => {
  if (await page.locator('.nf-term-tip').isVisible()) {
    await page.locator('.nf-term-tip .term-tip-close').click();
  }
};

const rowState = (page, rel) => page.evaluate((p) => {
  const row = document.querySelector(`.explorer-item[data-path="${CSS.escape(p)}"]`);
  if (!row) return null;
  const cs = getComputedStyle(row);
  const r = row.getBoundingClientRect();
  return {
    focused: document.activeElement === row,
    roving: row.tabIndex,
    outlineStyle: cs.outlineStyle,
    outlineWidth: cs.outlineWidth,
    inViewport: r.top >= 0 && r.bottom <= window.innerHeight,
  };
}, rel);

try {
  const width = viewport === 'mobile' ? 375 : 1280;
  const height = viewport === 'mobile' ? 812 : 800;
  const session = await createSession(browser, {
    // localhost = 安全上下文（crypto.subtle 在场——askDock 的会话 id 派生腿
    // 不再因假源非安全上下文抛 digest 异常；S1 的 stand-fixture.test 假源同款
    // 噪声本就未入断言面）。
    baseURL: 'http://localhost:9130/',
    apiStubs: { '/api/wiki/graph': { body: graph } },
    width, height,
    autoRespond: false, // listDir 由本测试的树桩接管（键含 rootPath）
  });
  const page = session.page;
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(String(e?.message || e)));
  await page.addInitScript((root) => {
    try {
      localStorage.setItem('stand_explorer_root', root);
      // localhost 安全源会成功注册 /sw.js（stand-fixture 假源上注册被拒）——
      // SW 接管 fetch 会吃掉打桩的 /api 应答。走 swReg.js 自己的逃生门
      //（swReg.js:9-10：localStorage <lower>_sw='0' ⇒ 跳过注册 + 反向注销）。
      localStorage.setItem('stand_sw', '0');
    } catch (_) {}
  }, ROOT);
  await installFaultRoutes(session);
  await session.boot();
  await installMockTrees(session, page, trees);

  // 移动端前置事实：≤768px 预绘脚本自动折叠侧栏（④ 态的天然起点）。
  if (viewport === 'mobile') {
    assert.equal(await page.evaluate(() => document.body.classList.contains('sidebar-collapsed')), true,
      'mobile starts with the sidebar collapsed');
  }

  // 打开夹具文档（S1 同法：真实 workspace-open-item 事件）。
  const tabId = `file:${ROOT}/current.md`;
  await page.evaluate(({ content, id, absPath }) => window.dispatchEvent(new CustomEvent('workspace-open-item', {
    detail: { id, itemType: 'markdown', title: 'current.md', pinned: true, content, absPath, size: content.length },
  })), { content, id: tabId, absPath: `${ROOT}/current.md` });
  const host = page.locator(`.canvas-tab-pane[data-tab-id="${tabId}"] .canvas-md-viewer`);
  // 移动端多层页面：文档打开后 canvas 页仍藏层（visibility:hidden）——走产品
  // 导航腿 dockTo('canvas') 显层（tests/mobile-canvas-toolbar-layout.spec.mjs 同法）。
  if (viewport === 'mobile') {
    await page.evaluate(async () => { (await import('/js/mobile/navController.js')).dockTo('canvas'); });
  }
  await host.waitFor();
  // 手机层（≤768px ⇒ body.mobile-layer）：#sidebar display:none（mobileLayer.css
  // 「桌面壳退场」段）——文件浏览器在移动端**没有呈现面**，树仍在共享 DOM 里
  // （定位的 DOM 机制照跑：展开/高亮/换根/提示都可断言），但焦点/滚动/省略号
  // 这类**渲染树断言**只对真实呈现的树成立（treeRows() 本就过滤零尺寸行，
  // explorer.js:843）——移动腿断 DOM 机制面，桌面腿断完整呈现面。
  const rendered = viewport !== 'mobile';
  const mention = (name) => host.locator('a.nf-term').filter({ hasText: name });
  await mention('TargetDoc').waitFor({ timeout: 10000 }); // 基点负控在此红：S1 之前无词条化
  assert.equal(await mention('TargetDoc').count(), 1, 'TargetDoc mention');
  assert.equal(await mention('MissingDoc').count(), 1, 'MissingDoc mention');
  assert.equal(await mention('OutsideDoc').count(), 1, 'OutsideDoc mention');
  assert.equal(await mention(LONG).count(), 1, 'long-name mention');
  assert.equal(await mention('TargetDoc').first().evaluate((a) => a.dataset.wikiMention), 'doc',
    'mention links carry the wikiMention marker');

  // ── 状态①：根内定位（逐级展开 → 滚动 → 高亮 → 焦点）───────────────────
  await mention('TargetDoc').click();
  if (fault === 'noreveal') {
    assert.ok(await page.evaluate(() => (window.__revealFaultVisits || 0) > 0), 'injected predicate executed');
  }
  // ④（手机腿并入①）：boot 折叠态起步（前置已断言），定位先展开面板（类机制
  // 由 showSidePanel 走 setSideBarCollapsed(false)）再走树。
  if (viewport === 'mobile') {
    await page.waitForFunction(() => !document.body.classList.contains('sidebar-collapsed'), null, { timeout: 5000 });
  }
  await awaitReveal(page, 'design/deep/TargetDoc.md');
  const st = await rowState(page, 'design/deep/TargetDoc.md');
  assert.ok(st, 'target row exists');
  if (rendered) {
    assert.equal(st.focused, true, 'focus lands on the target row (keyboard reachable)');
    assert.equal(st.roving, 0, 'roving tabindex=0 on the target row');
    assert.equal(st.outlineStyle, 'solid', 'reveal highlight uses the focus ring');
    assert.equal(st.outlineWidth, '2px', 'focus ring width token');
    assert.equal(st.inViewport, true, 'row scrolled into view');
  } else {
    assert.equal(st.outlineStyle, 'solid', 'reveal highlight rule applies (shared DOM)');
  }
  assert.equal(await page.evaluate(() => localStorage.getItem('stand_explorer_root')), ROOT,
    'root unchanged (no silent reroot)');
  assert.equal(await page.locator('.explorer-root > .explorer-children > .explorer-dir-wrapper[data-path="design"]').count(), 1,
    'first level unchanged (root listing intact)');
  assert.equal(await page.locator('.explorer-dir-wrapper[data-path="design"] > .explorer-folder[aria-expanded="true"]').count(), 1,
    'design expanded');
  assert.equal(await page.locator('.explorer-dir-wrapper[data-path="design/deep"] > .explorer-folder[aria-expanded="true"]').count(), 1,
    'design/deep expanded');
  assert.equal(await page.locator('.nebflow-toast').count(), 0, 'no toast on the happy path');
  const seq = await page.evaluate(() => ({
    sentDeep: window.__revealLog.sentAt['/fixture/home||design/deep'],
    replyDesign: window.__revealLog.replyAt['/fixture/home||design'],
  }));
  assert.ok(typeof seq.sentDeep === 'number' && typeof seq.replyDesign === 'number',
    'level-by-level requests recorded');
  assert.ok(seq.sentDeep >= seq.replyDesign, 'child listing waits for the parent reply (no prefetch)');

  // ── 状态①超长文件名：不溢出、不换行、行高不变 ─────────────────────────
  await closeTip(page);
  await mention(LONG).click();
  await awaitReveal(page, `design/deep/${LONG}.md`);
  const long = await page.evaluate((p) => {
    const row = document.querySelector(`.explorer-item[data-path="${CSS.escape(p)}"]`);
    const label = row?.querySelector('.explorer-label');
    const tree = document.getElementById('explorer-tree');
    if (!row || !label || !tree) return null;
    const r = row.getBoundingClientRect();
    const t = tree.getBoundingClientRect();
    const cs = getComputedStyle(label);
    return {
      rightWithin: r.right <= t.right + 1,
      inViewport: r.top >= 0 && r.bottom <= window.innerHeight,
      ellipsis: cs.textOverflow,
      white: cs.whiteSpace,
      h: row.offsetHeight,
    };
  }, `design/deep/${LONG}.md`);
  assert.ok(long, 'long-name row exists');
  if (rendered) {
    assert.equal(long.rightWithin, true, 'long row stays inside the tree width');
    assert.equal(long.inViewport, true, 'long row scrolled into view');
    assert.equal(long.ellipsis, 'ellipsis', 'long label truncates with ellipsis (established pattern)');
    assert.equal(long.white, 'nowrap', 'long label stays single-line');
    const heights = await page.evaluate((ps) => ps.map((p) => document.querySelector(`.explorer-item[data-path="${CSS.escape(p)}"]`)?.offsetHeight),
      ['design/deep/TargetDoc.md', `design/deep/${LONG}.md`]);
    assert.ok(Math.abs(heights[0] - heights[1]) <= 1, 'long name does not change row height (no overlap)');
  } else {
    // 手机层：行不在渲染树 ⇒ 只断 DOM 面（文本完整在场，无截断副作用）。
    assert.equal(long.ellipsis, 'ellipsis', 'long label rule present in shared DOM');
    assert.equal(long.white, 'nowrap', 'single-line rule present in shared DOM');
  }

  // ── 状态③：目标不存在 → 短提示、不导航、不抛错 ─────────────────────────
  await closeTip(page);
  await mention('MissingDoc').click();
  if (fault === 'missing-silent') {
    assert.ok(await page.evaluate(() => (window.__revealFaultVisits || 0) > 0), 'injected missing leg executed');
  }
  await page.locator('.nebflow-toast', { hasText: '未找到该文件' }).waitFor({ state: 'visible', timeout: 5000 });
  assert.equal(await page.evaluate(() => localStorage.getItem('stand_explorer_root')), ROOT,
    'missing target does not navigate');
  assert.equal(await page.locator('.explorer-item[data-path="design/deep/MissingDoc.md"]').count(), 0,
    'missing target has no row');
  await page.locator('.nebflow-toast', { hasText: '未找到该文件' }).waitFor({ state: 'hidden', timeout: 6000 });

  // ── 状态②（前半）：根外 → 提示 + 动作钮，绝不静默换根 ──────────────────
  await closeTip(page);
  await mention('OutsideDoc').click();
  await page.locator('.nebflow-toast', { hasText: '该路径在当前根目录之外' }).waitFor({ state: 'visible', timeout: 5000 });
  const actionBtn = page.locator('.nebflow-toast-action', { hasText: '切换到该目录' });
  assert.equal(await actionBtn.count(), 1, 'outside-root toast carries the switch action');
  assert.equal(await page.evaluate(() => localStorage.getItem('stand_explorer_root')), ROOT,
    'root NOT changed without the explicit user action');
  assert.equal((await page.evaluate(() => window.__revealLog.sent['/fixture/away||'])) || 0, 0,
    'no listing of the away root before the user opts in');
  assert.equal(await page.locator('.explorer-item[data-path="OutsideDoc.md"]').count(), 0,
    'outside target has no row yet');

  // ── 状态④：侧栏折叠 → 先展开面板再定位 ────────────────────────────────
  if (viewport === 'mobile') {
    // 手机层没有侧栏呈现面：#mobile-scrim 埋在 mf 页面层之下（opacity 0，
    // elementFromPoint 命中的是 canvas 页内容）——「点 scrim 收抽屉」在移动层
    // 是死路。④ 的「折叠起步 → 先展开面板 → 再定位」语义由①的 boot 折叠前置
    // + ①内嵌的展开断言承载（见上），此处不再重复走死路。
  } else {
    await page.locator('#sidebar-toggle').click();
    assert.equal(await page.evaluate(() => document.body.classList.contains('sidebar-collapsed')), true,
      'desktop sidebar collapsed via the toggle (product path)');
    await closeTip(page);
    await mention('TargetDoc').click();
    // 点击的 input 派发与页面 click 处理之间有真实间隔——④ 态按事件驱动等待。
    await page.waitForFunction(() => !document.body.classList.contains('sidebar-collapsed'), null, { timeout: 5000 });
    await awaitReveal(page, 'design/deep/TargetDoc.md');
    const st4 = await rowState(page, 'design/deep/TargetDoc.md');
    assert.equal(st4.focused, true, 'collapsed-sidebar reveal still lands focus');
    assert.equal(st4.inViewport, true, 'collapsed-sidebar reveal still scrolls into view');
  }

  // ── 状态②（后半）：用户点动作钮 → 显式换根（openExplorerAt）────────────
  // force：移动端抽屉 scrim 盖在 toast 上层时跳过命中测试——onClick 走同一产品腿。
  await actionBtn.click({ force: true });
  await page.waitForFunction(() => !!document.querySelector('.explorer-item[data-path="OutsideDoc.md"]'), null, { timeout: 5000 });
  assert.equal(await page.evaluate(() => localStorage.getItem('stand_explorer_root')), '/fixture/away',
    'explicit action switches and persists the root');

  assert.deepEqual(pageErrors, [], 'no JS exceptions (state ③: reveal never throws)');

  // ── 缺省根基点（仅桌面绿灯档）：无 explorerRoot 时以根列应答 resolvedPath 为基 ──
  if (viewport === 'desktop' && !fault && !isBaseline) {
    const nullTrees = {
      '||': { resolvedPath: VAULT, entries: [{ name: 'design', type: 'dir' }] },
      '||design': { resolvedPath: VAULT, entries: [{ name: 'deep', type: 'dir' }] },
      '||design/deep': { resolvedPath: VAULT, entries: deepEntries },
    };
    const nullGraph = {
      projects: [{ name: 'p', device: 'self', vault: VAULT, workspace: VAULT }],
      nodes: [
        { project: 'p', device: 'self', rel: 'current.md', title: 'Current', stub: false },
        { project: 'p', device: 'self', rel: 'design/deep/TargetDoc.md', title: 'TargetDoc', stub: false },
      ], edges: [], stats: {},
    };
    const s2 = await createSession(browser, {
      // 不同端口的 localhost = 独立 origin（干净 localStorage，前一会话持久过的
      // 根不串场）且仍是安全上下文（crypto.subtle 在场）。
      baseURL: 'http://localhost:9131/',
      apiStubs: { '/api/wiki/graph': { body: nullGraph } },
      width, height,
      autoRespond: false,
    });
    const p2 = s2.page;
    const errors2 = [];
    p2.on('pageerror', (e) => errors2.push(String(e?.message || e)));
    // 与主相同源的 SW 问题（WebKit 上 /sw.js 接管 fetch 吃掉打桩应答）——同一
    // 逃生门 stand_sw='0'；刻意**不**设 explorer 根（本相位的断言前提）。
    await p2.addInitScript(() => { try { localStorage.setItem('stand_sw', '0'); } catch (_) {} });
    await s2.boot();
    await installMockTrees(s2, p2, nullTrees);
    await p2.evaluate(({ content }) => window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:/fixture/current.md', itemType: 'markdown', title: 'current.md', pinned: true, content, absPath: '/fixture/current.md', size: content.length },
    })), { content: '# Null root\n\nTargetDoc\n' });
    const host2 = p2.locator('.canvas-tab-pane[data-tab-id="file:/fixture/current.md"] .canvas-md-viewer');
    await host2.waitFor();
    await host2.locator('a.nf-term').filter({ hasText: 'TargetDoc' }).waitFor({ timeout: 10000 });
    await host2.locator('a.nf-term').filter({ hasText: 'TargetDoc' }).click();
    await awaitReveal(p2, 'design/deep/TargetDoc.md');
    assert.equal(await p2.evaluate(() => localStorage.getItem('stand_explorer_root')), null,
      'default-root reveal does not persist an explorer root');
    assert.deepEqual(errors2, [], 'no JS exceptions (null-root phase)');
    await s2.close();
    console.log('PASS', engine, viewport, 'null-root basis (resolvedPath)');
  }

  console.log('PASS', engine, viewport, fault ? 'fault=' + fault : 'reveal four states; long text; level-by-level');
  await session.close();
} finally { await browser.close(); }

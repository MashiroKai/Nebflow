#!/usr/bin/env node
// verify-viewer-reading-batch.mjs — 阅读/编辑体验优化批（2026-11-01）的可自动化断言。
//
// 三组断言，逐条对应本批落地的改动（改动前的红/绿见批报告「验红实证」段）：
//   ① .txt 阅读面重渲不叠 DOM、切签/重渲都保住阅读位置
//      （viewers/textReader.js + canvas.js setActiveTab 派发判据）
//   ② viewer 协议 dispose 真的在关页签时被调用
//      （utils.js ViewerProtocol.dispose + fileViewers.disposeViewer
//       + canvas.js disposeTabEditor）—— 判据用「卸载槽被清空」
//   ③ 源码↔渲染往返不再把脏缓冲误判为干净
//      （monacoEditor.markDirty + viewers/shared.js addSourceToggle）
//
// 装置：复用 scripts/lib/visual-harness.mjs（零端口零进程：page.route 从磁盘服务
// web/，MockWS 走真实 ws.js 分发路径）—— 因此结构上不可能碰到宿主实例。
//
// 用法：NEBFLOW_PW_PATH=<playwright|playwright-core> node scripts/verify-viewer-reading-batch.mjs
// 退出码：全绿 0；任一断言失败 1；装置错误 2。

import { loadPlaywright, launchBrowser, createSession, settle } from './lib/visual-harness.mjs';

const results = [];
const ok = (name, cond, extra = '') => { results.push({ name, pass: !!cond, extra }); };

const TXT_ID = 'file:/tmp/verify-reading-note.txt';
const MD_ID = 'file:/tmp/verify-reading-note.md';
const IMG_ID = 'file:/tmp/verify-reading-image.svg';

const TXT = Array.from({ length: 240 }, (_, i) => `第 ${i + 1} 行 — 阅读位置保持夹具`).join('\n');
const MD = ['# 脏点往返夹具', '', '这一行用来验证源码态编辑的脏点标记。', '', '- 源码切换钮：`.canvas-source-toggle`'].join('\n');
const IMG_URI = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(
  '<svg xmlns="http://www.w3.org/2000/svg" width="320" height="200"><rect width="320" height="200" fill="#22304a"/>'
  + '<circle cx="160" cy="100" r="60" fill="#7f5ad4"/></svg>',
);

/** 打开一个文件页签（走产品真实入口 workspace-open-item → openWorkspaceItem）。 */
async function openFile(page, detail) {
  await page.evaluate((d) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: d }));
  }, detail);
}

const paneSel = (id) => `#canvas-panel .canvas-tab-pane[data-tab-id="${id}"]`;

async function main() {
  const pwRes = loadPlaywright();
  const pw = pwRes.mod.playwright || pwRes.mod;
  const { browser, label } = await launchBrowser(pw);
  const s = await createSession(browser, { colorScheme: 'dark' });
  try {
    await s.boot();
    const { page } = s;

    // ── ① .txt 阅读面：重渲不叠 DOM + 位置保持 ────────────────────────────
    await openFile(page, {
      id: TXT_ID, itemType: 'code', title: 'verify-reading-note.txt', pinned: true,
      content: TXT, absPath: '/tmp/verify-reading-note.txt', size: TXT.length, mtimeMs: 1757000000000,
    });
    await page.waitForSelector(`${paneSel(TXT_ID)} .canvas-txt-scroll`, { state: 'visible', timeout: 15000 });
    await settle(page, 200);

    // 滚动到 600px，并让记账监听吃到这次滚动（程序化写 scrollTop 也会派发 scroll）
    await page.evaluate((sel) => {
      const sc = document.querySelector(sel + ' .canvas-txt-scroll');
      sc.scrollTop = 600;
      sc.dispatchEvent(new Event('scroll'));
    }, paneSel(TXT_ID));
    await page.waitForTimeout(120);
    const scrolled = await page.evaluate((sel) => document.querySelector(sel + ' .canvas-txt-scroll').scrollTop, paneSel(TXT_ID));
    ok('①a .txt 可滚动到 600px（夹具足够长）', scrolled > 500, `scrollTop=${scrolled}`);

    // 同内容重渲一次（= 页签激活刷新的那条路径：readFile 应答 → renderFile）
    await openFile(page, {
      id: TXT_ID, itemType: 'code', title: 'verify-reading-note.txt', pinned: true,
      content: TXT, absPath: '/tmp/verify-reading-note.txt', size: TXT.length, mtimeMs: 1757000000000,
    });
    await page.waitForTimeout(500);
    const afterRerender = await page.evaluate((sel) => {
      const pane = document.querySelector(sel);
      const scrollers = pane ? pane.querySelectorAll('.canvas-txt-scroll') : [];
      return { count: scrollers.length, top: scrollers.length ? scrollers[scrollers.length - 1].scrollTop : -1 };
    }, paneSel(TXT_ID));
    ok('①b 重渲后 pane 内只有一份 .canvas-txt-scroll（不叠 DOM）', afterRerender.count === 1, `count=${afterRerender.count}`);
    ok('①c 重渲后阅读位置保持（不被甩回顶部）', afterRerender.top > 500, `scrollTop=${afterRerender.top}`);

    // 切签：开第二个页签让 .txt 变成 display:none，再切回来（真实 setActiveTab）
    await openFile(page, {
      id: MD_ID, itemType: 'markdown', title: 'verify-reading-note.md', pinned: true,
      content: MD, absPath: '/tmp/verify-reading-note.md', size: MD.length, mtimeMs: 1757000000000,
    });
    await page.waitForTimeout(400);
    const hiddenTop = await page.evaluate((sel) => {
      const all = document.querySelectorAll(sel + ' .canvas-txt-scroll');
      return all.length ? all[all.length - 1].scrollTop : -2;
    }, paneSel(TXT_ID));
    await page.evaluate(async (id) => {
      const { setActiveTab } = await import('/js/canvas.js');
      setActiveTab(id);
    }, TXT_ID);
    await page.waitForTimeout(400);
    const restoredTop = await page.evaluate((sel) => {
      const sc = document.querySelector(sel + ' .canvas-txt-scroll');
      return sc ? sc.scrollTop : -2;
    }, paneSel(TXT_ID));
    ok('①d 切走时滚动盒被浏览器重置（前提成立，夹具可信）', hiddenTop < 100, `hidden scrollTop=${hiddenTop}`);
    ok('①e 切回该页签后阅读位置被还原', restoredTop > 500, `restored scrollTop=${restoredTop}`);

    // ── ② viewer dispose 在关页签时被调用 ───────────────────────────────
    await openFile(page, {
      id: IMG_ID, itemType: 'image', title: 'verify-reading-image.svg', pinned: true,
      content: '', absPath: '/tmp/verify-reading-image.svg', size: 0, mtimeMs: 1757000000000, objectUrl: IMG_URI,
    });
    await page.waitForSelector(`${paneSel(IMG_ID)} .canvas-zoom-bar`, { state: 'visible', timeout: 15000 });
    await page.waitForTimeout(300);
    const beforeClose = await page.evaluate((sel) => {
      const pane = /** @type {any} */ (document.querySelector(sel));
      window.__nfProbePane = pane;   // 关闭后 pane 已脱离 DOM，句柄留在 window 上
      return {
        hasDisposeSlot: typeof pane._nfViewerDispose === 'function',
        hasTeardown: typeof pane._nfImageTeardown === 'function',
      };
    }, paneSel(IMG_ID));
    ok('②a 渲染后 pane 上记下了 viewer 的 dispose 槽', beforeClose.hasDisposeSlot);
    ok('②b 图片 viewer 注册了卸载函数（ResizeObserver/缩放引擎/Space 键）', beforeClose.hasTeardown);

    await page.evaluate(async (id) => {
      const { closeTab } = await import('/js/canvas.js');
      closeTab(id);
    }, IMG_ID);
    await page.waitForTimeout(700);  // closeTab 有 0.15s 过渡后才 teardown
    const afterClose = await page.evaluate(() => {
      const pane = /** @type {any} */ (window.__nfProbePane);
      return {
        connected: !!(pane && pane.isConnected),
        slotCleared: pane ? pane._nfViewerDispose === null : false,
        teardownConsumed: pane ? pane._nfImageTeardown === null : false,
      };
    });
    ok('②c 页签真的关掉了（pane 已脱离 DOM）', afterClose.connected === false);
    ok('②d 关页签触发了 dispose（卸载槽被消费）', afterClose.slotCleared && afterClose.teardownConsumed,
      JSON.stringify(afterClose));

    // ── ③ 源码↔渲染往返保留脏点 ────────────────────────────────────────
    await openFile(page, {
      id: MD_ID, itemType: 'markdown', title: 'verify-reading-note.md', pinned: true,
      content: MD, absPath: '/tmp/verify-reading-note.md', size: MD.length, mtimeMs: 1757000000000,
    });
    await page.waitForSelector(`${paneSel(MD_ID)} .canvas-source-toggle`, { state: 'visible', timeout: 15000 });
    // 渲染 → 源码（用 DOM click 而不是鼠标事件：ask-fab 圆钮压在画面上，
    // hit-test 会被它拦下 —— 本断言测的是切换逻辑，不是点击命中面积）
    const toggleSource = () => page.evaluate((sel) => {
      const btn = document.querySelector(sel + ' .canvas-source-toggle');
      if (btn) btn.click();
    }, paneSel(MD_ID));
    await toggleSource();
    await page.waitForSelector(`${paneSel(MD_ID)} .monaco-editor`, { state: 'visible', timeout: 20000 });
    await page.waitForTimeout(600);
    // 用 model API 制造一次「未保存编辑」（等价于用户敲字，同样触发 onDidChangeContent）
    const edited = await page.evaluate(async (absPath) => {
      const pane = /** @type {any} */ (document.querySelector('#canvas-panel .canvas-tab-pane[data-tab-id="' + absPath + '"]'));
      const handle = pane && pane._editorHandle;
      if (!handle) return { ok: false, why: 'no editor handle' };
      handle.model.setValue(handle.model.getValue() + '\n\n新增一行（未保存）\n');
      return { ok: true, dirty: handle.getDirty() };
    }, MD_ID);
    ok('③a 源码态编辑后 handle 报告脏', edited.ok && edited.dirty === true, JSON.stringify(edited));

    // 源码 → 渲染 → 源码（往返一次）
    await toggleSource();
    await page.waitForSelector(`${paneSel(MD_ID)} .canvas-md-scroll`, { state: 'visible', timeout: 15000 });
    await page.waitForTimeout(400);
    await toggleSource();
    await page.waitForSelector(`${paneSel(MD_ID)} .monaco-editor`, { state: 'visible', timeout: 20000 });
    await page.waitForTimeout(600);
    const afterRoundTrip = await page.evaluate((id) => {
      const pane = /** @type {any} */ (document.querySelector('#canvas-panel .canvas-tab-pane[data-tab-id="' + id + '"]'));
      const handle = pane && pane._editorHandle;
      const tab = document.querySelector(`#canvas-tab-bar .canvas-tab[data-tab-id="${id}"] .canvas-tab-label`);
      return {
        dirty: handle ? handle.getDirty() : null,
        tabDirtyClass: !!(tab && tab.classList.contains('dirty')),
        contentKept: handle ? handle.model.getValue().includes('新增一行（未保存）') : false,
      };
    }, MD_ID);
    ok('③b 往返后编辑内容仍在缓冲区', afterRoundTrip.contentKept === true);
    ok('③c 往返后脏点仍然成立（不再被误清零）', afterRoundTrip.dirty === true, `dirty=${afterRoundTrip.dirty}`);
    ok('③d 页签脏指示仍在（⌘S 可用）', afterRoundTrip.tabDirtyClass === true);
  } finally {
    await s.close();
    await browser.close();
  }

  const failed = results.filter((r) => !r.pass);
  for (const r of results) {
    console.log(`  ${r.pass ? 'PASS' : 'FAIL'}  ${r.name}${r.extra ? `  (${r.extra})` : ''}`);
  }
  console.log(`\nverify-viewer-reading-batch: ${results.length - failed.length}/${results.length} passed (browser=${label})`);
  process.exit(failed.length ? 1 : 0);
}

main().catch((e) => {
  console.error('verify-viewer-reading-batch: harness error —', e && e.message ? e.message : e);
  if (e && e.stack) console.error(e.stack.split('\n').slice(1, 4).join('\n'));
  process.exit(2);
});

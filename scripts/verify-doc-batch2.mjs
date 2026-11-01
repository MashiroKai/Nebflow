#!/usr/bin/env node
// verify-doc-batch2.mjs — B7 文档后续批的断言组（脏页签确认 / 外部改动冲突 / 树搜索排序）。
//
// 用法：
//   NEBFLOW_PW_PATH=<playwright-core> node scripts/verify-doc-batch2.mjs
//   node scripts/verify-doc-batch2.mjs --web-root build/web-dist
// 退出码：0 全绿；1 有断言失败；2 装置错误（无浏览器 / playwright 不可解析）。
//
// 装置与 visual-baseline / keyboard-check 同源（scripts/lib/visual-harness.mjs：
// page.route 从磁盘服务 + MockWS 走真实 ws.js 分发 ⇒ 零端口零进程），验证对象切换
// 也走**同一套** webRoot()/setWebRoot()（G3/G6）。本脚本的断言全部走 DOM 与真实
// 用户路径（点关闭钮、点冲突条按钮、往搜索框打字），不 import 产品模块 ——
// 因此**源树与产物两种形态都能跑**，不需要探针登记表。
//
// B7-3（epub 懒加载）不在本脚本内：本批未做（无 EPUB 夹具 + 零依赖约束下要先造
// 一个 zip，属另一个批），见批次报告的「已证实/未验证」分界。

import { loadPlaywright, launchBrowser, createSession, setWebRoot, webRoot, settle } from './lib/visual-harness.mjs';

const argVal = (/** @type {string} */ name) => {
  const i = process.argv.indexOf(name);
  return i >= 0 ? (process.argv[i + 1] || null) : null;
};
const ROOT_OVERRIDE = argVal('--web-root');
if (ROOT_OVERRIDE) setWebRoot(ROOT_OVERRIDE);

const pw = loadPlaywright();
const { browser, label } = await launchBrowser(pw.mod, {});

let pass = 0;
let fail = 0;
const failures = [];

/** @param {string} name @param {boolean} cond @param {string} [detail] */
function check(name, cond, detail = '') {
  if (cond) { pass++; console.log(`  PASS  ${name}`); }
  else { fail++; failures.push(name + (detail ? ` — ${detail}` : '')); console.log(`  FAIL  ${name}${detail ? ' — ' + detail : ''}`); }
}

async function fresh() {
  const s = await createSession(browser, {
    apiStubs: { '/api/agents': { body: { agents: [] } } },
  });
  await s.boot({ config: {}, messages: [] });
  await s.page.waitForTimeout(400);
  return s;
}

const DOC = { id: 'file:/tmp/b7-a.md', title: 'b7-a.md', content: '# A\n\nB7 批。\n', absPath: '/tmp/b7-a.md' };

/** 走真实打开通道（与 visual-baseline / keyboard-check 同一条）。 */
async function openDoc(/** @type {any} */ s, d = DOC) {
  await s.page.evaluate((/** @type {any} */ x) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: x.id, itemType: 'markdown', title: x.title, content: x.content, absPath: x.absPath, size: x.content.length, mtimeMs: 1757000000000 },
    }));
  }, d);
  await s.page.waitForSelector(`#canvas-panel .canvas-tab-pane[data-tab-id="${d.id}"]`, { state: 'visible', timeout: 15000 });
  await s.page.waitForTimeout(300);
}

/** 把页签标脏（`isTabDirty` 的第二条来源：pane._dirty —— 编辑器转发的脏位）。 */
async function markDirty(/** @type {any} */ s, /** @type {string} */ id) {
  return s.page.evaluate((/** @type {string} */ tabId) => {
    const pane = /** @type {any} */ (document.querySelector(`#canvas-panel .canvas-tab-pane[data-tab-id="${tabId}"]`));
    if (!pane) return false;
    pane._dirty = true;
    const label = document.querySelector(`.canvas-tab[data-tab-id="${tabId}"] .canvas-tab-label`);
    label?.classList.add('dirty');   // 第三条来源：UI 脏标记（canonical 指示位）
    return true;
  }, id);
}

/**
 * 应答一次在飞的 readFile，把页签的 `_refreshing` 放掉。
 * 为什么必须做：产品对「刷新已在飞」有硬闸（10s 安全窗），打桩不答 ⇒ 页签在窗口
 * 内一律拒绝再刷 —— 不答就断言「没发帧」，测的是打桩的沉默，不是产品的行为。
 */
async function settleRefresh(/** @type {any} */ s, d = DOC) {
  await s.frame({
    type: 'fileContent', path: d.absPath.split('/').pop(), absPath: d.absPath,
    content: d.content, size: d.content.length, itemType: 'markdown', mtimeMs: 1757000000000,
  });
  await s.page.waitForTimeout(400);
}

const tabOpen = (/** @type {any} */ s, /** @type {string} */ id) => s.page.evaluate(
  (/** @type {string} */ t) => !!document.querySelector(`#canvas-panel .canvas-tab-pane[data-tab-id="${t}"]`), id);

const modalOn = (/** @type {any} */ s) => s.page.evaluate(() => {
  const ov = document.getElementById('modal-overlay');
  const box = document.getElementById('delete-box');
  return { on: !!ov && ov.classList.contains('on'), boxShown: !!box && box.style.display !== 'none', title: document.getElementById('delete-title')?.textContent || '' };
});

console.log(`verify-doc-batch2 — browser=${label}`);
console.log(`  web    : ${webRoot()}`);
console.log('');

// ── B7-1 脏页签关闭确认 ────────────────────────────────────────────────
console.log('B7-1 脏页签关闭确认');
{
  const s = await fresh();
  await openDoc(s);
  await markDirty(s, DOC.id);

  // ① 脏页签点关闭 ⇒ 弹确认（不是直接关掉）
  await s.page.evaluate((/** @type {string} */ id) => {
    /** @type {any} */ (document.querySelector(`.canvas-tab[data-tab-id="${id}"] .canvas-tab-close`))?.click();
  }, DOC.id);
  await s.page.waitForTimeout(400);
  const m1 = await modalOn(s);
  check('脏页签点关闭 ⇒ 弹确认框（不再直接丢弃编辑）', m1.on === true && m1.boxShown === true, JSON.stringify(m1));
  check('确认框文案走 i18n（标题非空、不是 key 原文）',
    !!m1.title && !/^canvas\./.test(m1.title) && m1.title.trim().length > 0, m1.title);

  // ② Esc 取消 ⇒ 确认框收起、页签还在（编辑没丢）
  await s.page.keyboard.press('Escape');
  await s.page.waitForTimeout(350);
  const m2 = await modalOn(s);
  const stillOpen = await tabOpen(s, DOC.id);
  check('Esc 取消 ⇒ 不关闭页签', m2.on === false && stillOpen === true, `${JSON.stringify(m2)} tabOpen=${stillOpen}`);

  // ③ 再点关闭 → 确认 ⇒ 页签真的关掉
  await s.page.evaluate((/** @type {string} */ id) => {
    /** @type {any} */ (document.querySelector(`.canvas-tab[data-tab-id="${id}"] .canvas-tab-close`))?.click();
  }, DOC.id);
  await s.page.waitForTimeout(350);
  await s.page.click('#delete-confirm');
  await s.page.waitForTimeout(600);
  const gone = await tabOpen(s, DOC.id);
  check('确认后页签关闭', gone === false, `tabOpen=${gone}`);

  // ④ 非脏页签 ⇒ 零确认（直接关）
  await openDoc(s, { ...DOC, id: 'file:/tmp/b7-b.md', title: 'b7-b.md', absPath: '/tmp/b7-b.md' });
  await s.page.evaluate(() => {
    /** @type {any} */ (document.querySelector('.canvas-tab[data-tab-id="file:/tmp/b7-b.md"] .canvas-tab-close'))?.click();
  });
  await s.page.waitForTimeout(500);
  const m4 = await modalOn(s);
  const gone4 = await tabOpen(s, 'file:/tmp/b7-b.md');
  check('非脏页签 ⇒ 零确认直接关闭', m4.on === false && gone4 === false, `${JSON.stringify(m4)} tabOpen=${gone4}`);
  await s.close();
}

// ── B7-2 外部改动冲突条 ───────────────────────────────────────────────
console.log('B7-2 外部改动冲突条');
{
  const s = await fresh();
  await openDoc(s);
  await settleRefresh(s);          // 放掉打开时那条在飞的刷新（否则 10s 内一律拒刷）
  await markDirty(s, DOC.id);

  // ⑤ 脏页签 + nf-cache-invalidate ⇒ 出现冲突条（不是静默跳过）
  await s.frame({ type: 'nf-cache-invalidate', path: DOC.absPath });
  await s.page.waitForTimeout(600);
  const bar = await s.page.evaluate(() => {
    const pane = document.querySelector('#canvas-panel .canvas-tab-pane[data-tab-id="file:/tmp/b7-a.md"]');
    const b = pane?.querySelector('.nf-file-conflict');
    return { exists: !!b, keep: !!b?.querySelector('.nf-file-conflict-keep'), reload: !!b?.querySelector('.nf-file-conflict-reload') };
  });
  check('脏页签收到外部改动 ⇒ 冲突条出现（保留 / 重载 两键齐备）',
    bar.exists === true && bar.keep === true && bar.reload === true, JSON.stringify(bar));

  // ⑥ 「保留」⇒ 条子收起、脏位保留（用户接着编辑）
  await s.page.evaluate(() => {
    /** @type {any} */ (document.querySelector('.nf-file-conflict-keep'))?.click();
  });
  await s.page.waitForTimeout(300);
  const afterKeep = await s.page.evaluate(() => {
    const pane = /** @type {any} */ (document.querySelector('#canvas-panel .canvas-tab-pane[data-tab-id="file:/tmp/b7-a.md"]'));
    return { bar: !!pane?.querySelector('.nf-file-conflict'), dirty: !!pane?._dirty };
  });
  check('「保留」⇒ 冲突条收起且本地脏位保留', afterKeep.bar === false && afterKeep.dirty === true, JSON.stringify(afterKeep));

  // ⑦ 「重载」⇒ 条子收起、真的发出重读帧（force 腿绕过脏位拦截）
  await s.frame({ type: 'nf-cache-invalidate', path: DOC.absPath });
  await s.page.waitForTimeout(400);
  await s.page.evaluate(() => { window.__wsSent = window.__wsSent || []; window.__wsSent.length = 0; });
  await s.page.evaluate(() => {
    /** @type {any} */ (document.querySelector('.nf-file-conflict-reload'))?.click();
  });
  await s.page.waitForTimeout(900);
  const afterReload = await s.page.evaluate(() => ({
    bar: !!document.querySelector('.nf-file-conflict'),
    reads: (window.__wsSent || []).filter((f) => f.type === 'pop.readFile').map((f) => f.path),
  }));
  check('「重载」⇒ 冲突条收起且发出 readFile（读盘新内容）',
    afterReload.bar === false && afterReload.reads.includes(DOC.absPath), JSON.stringify(afterReload));

  // ⑧ 干净页签 + 外部改动 ⇒ 无冲突条，直接重读（不打扰人）
  await s.close();
  const s2 = await fresh();
  await openDoc(s2);
  await settleRefresh(s2);
  await s2.page.waitForTimeout(2200);   // 越过 2s 冷却窗（冷却不是缺陷，是既有防环闸）
  await s2.page.evaluate(() => { window.__wsSent = window.__wsSent || []; window.__wsSent.length = 0; });
  await s2.frame({ type: 'nf-cache-invalidate', path: DOC.absPath });
  await s2.page.waitForTimeout(1400);
  const clean = await s2.page.evaluate(() => ({
    bar: !!document.querySelector('.nf-file-conflict'),
    reads: (window.__wsSent || []).filter((f) => f.type === 'pop.readFile').map((f) => f.path),
  }));
  check('干净页签收到外部改动 ⇒ 无冲突条、直接重读',
    clean.bar === false && clean.reads.includes(DOC.absPath), JSON.stringify(clean));
  await s2.close();
}

// ── B7-4 树搜索 + 排序 ────────────────────────────────────────────────
console.log('B7-4 树搜索 + 排序');
{
  const s = await fresh();
  const ctl = await s.page.evaluate(() => {
    const row = document.getElementById('explorer-search-row');
    return {
      rowExists: !!row,
      hiddenByDefault: !!(row && row.hidden),
      // 只查 hidden 属性会漏「属性 true 但内联 display:flex 照样占位」这个坑（踩过），
      // 所以直接量盒子：占位高度必须为 0，否则整棵树下移就是视觉 DIFF。
      boxHeight: row ? row.getBoundingClientRect().height : -1,
      sort: !!document.getElementById('explorer-sort'),
      rows: document.querySelectorAll('#explorer-tree .explorer-item').length,
    };
  });
  check('搜索条默认收起（零占位：盒子高度 0）+ 排序钮在条内',
    ctl.rowExists && ctl.hiddenByDefault && ctl.boxHeight === 0 && ctl.sort === true && ctl.rows >= 4, JSON.stringify(ctl));

  // 过滤前的可见文件行数（分母）：必须在**任何过滤之前**量，否则量到的就是上一次过滤的残局
  const filesBefore = await s.page.evaluate(() => [...document.querySelectorAll('#explorer-tree .explorer-file')].filter((el) => !(/** @type {any} */ (el)).hidden).length);

  // 展开腿：树行上敲可打印字符 = typeahead（不新造快捷键，也不抢既有 combo ——
  // Ctrl+F 实测已被产品占用：按下后焦点落到 #search-keyword，属既有搜索面）
  await s.page.evaluate(() => {
    /** @type {any} */ (document.querySelector('#explorer-tree .explorer-item[role="treeitem"]'))?.focus();
  });
  await s.page.keyboard.press('r');
  await s.page.waitForTimeout(250);
  const leg1 = await s.page.evaluate(() => ({
    hidden: document.getElementById('explorer-search-row')?.hidden,
    value: /** @type {HTMLInputElement|null} */ (document.getElementById('explorer-search'))?.value,
    focused: document.activeElement?.id === 'explorer-search',
  }));
  check('树行上敲字符 ⇒ typeahead 展开、落焦输入框并带首个字符',
    leg1.hidden === false && leg1.value === 'r' && leg1.focused === true, JSON.stringify(leg1));

  // Esc 两拍：有词时先清词（行恢复），再按一次收起（VS Code 同手感）
  await s.page.keyboard.press('Escape');
  await s.page.waitForTimeout(250);
  const cleared = await s.page.evaluate(() => ({
    hidden: document.getElementById('explorer-search-row')?.hidden,
    value: /** @type {HTMLInputElement|null} */ (document.getElementById('explorer-search'))?.value,
    visibleFiles: [...document.querySelectorAll('#explorer-tree .explorer-file')].filter((el) => !(/** @type {any} */ (el)).hidden).length,
  }));
  check('Esc 第一拍只清过滤词（行恢复，条还在）', cleared.hidden === false && cleared.value === '' && cleared.visibleFiles >= 2, JSON.stringify(cleared));
  await s.page.keyboard.press('Escape');
  await s.page.waitForTimeout(250);
  const closedLeg = await s.page.evaluate(() => ({ hidden: document.getElementById('explorer-search-row')?.hidden }));
  check('Esc 第二拍收起搜索条', closedLeg.hidden === true, JSON.stringify(closedLeg));

  // 重新展开以继续做过滤断言
  await s.page.evaluate(() => {
    /** @type {any} */ (document.querySelector('#explorer-tree .explorer-item[role="treeitem"]'))?.focus();
  });
  await s.page.keyboard.press('x');
  await s.page.waitForTimeout(200);

  // 输入关键字 ⇒ 可见文件行下降（分母 = filesBefore，上面已量）
  await s.page.fill('#explorer-search', 'readme');
  await s.page.waitForTimeout(250);
  const filtered = await s.page.evaluate(() => {
    const vis = [...document.querySelectorAll('#explorer-tree .explorer-file')].filter((el) => !(/** @type {any} */ (el)).hidden);
    return { visible: vis.length, names: vis.map((el) => (/** @type {any} */ (el)).dataset.path) };
  });
  check('输入关键字后可见文件行下降且命中含 README',
    filtered.visible >= 1 && filtered.visible < filesBefore && filtered.names.some((n) => /readme/i.test(n)),
    `${JSON.stringify(filtered)} baseline=${filesBefore}`);

  // ⑪ Enter ⇒ 打开首条命中（走既有激活路径 = readFile 帧）
  await s.page.evaluate(() => { window.__wsSent = window.__wsSent || []; window.__wsSent.length = 0; });
  await s.page.focus('#explorer-search');
  await s.page.keyboard.press('Enter');
  await s.page.waitForTimeout(500);
  const opened = await s.page.evaluate(() => (window.__wsSent || []).filter((f) => f.type === 'readFile').map((f) => f.path));
  check('Enter 打开首条命中（发出 readFile）', opened.some((p) => /readme/i.test(p)), JSON.stringify(opened));

  // 清空查询 ⇒ 行恢复（分母 = 过滤前实测的可见行数，不写死）
  await s.page.fill('#explorer-search', '');
  await s.page.waitForTimeout(250);
  const restored = await s.page.evaluate(() => [...document.querySelectorAll('#explorer-tree .explorer-file')].filter((el) => !(/** @type {any} */ (el)).hidden).length);
  check('清空查询后文件行恢复到过滤前水平', restored === filesBefore, `restored=${restored} baseline=${filesBefore}`);

  // 排序钮切换 ⇒ 标签变化 + **目录恒在前**（换排序不把目录挪走）
  const before = await s.page.evaluate(() => document.getElementById('explorer-sort')?.textContent || '');
  await s.page.click('#explorer-sort');
  await s.page.waitForTimeout(900);
  const sorted = await s.page.evaluate(() => {
    // 根 children 里，目录是 .explorer-dir-wrapper（内含 .explorer-folder 行），
    // 文件是 .explorer-file 行本身 —— 两种形态都要算进去，否则「目录在前」测不到。
    const kids = [...document.querySelectorAll('#explorer-tree > .explorer-dir-wrapper.explorer-root > .explorer-children > *')];
    const isDir = (el) => el.classList.contains('explorer-dir-wrapper') || el.classList.contains('explorer-folder');
    const firstFile = kids.findIndex((el) => !isDir(el));
    const lastDir = kids.map(isDir).lastIndexOf(true);
    return {
      label: document.getElementById('explorer-sort')?.textContent || '',
      dirsFirst: firstFile === -1 || lastDir < firstFile,
      dirs: kids.filter(isDir).length,
      files: kids.filter((el) => !isDir(el)).length,
    };
  });
  check('排序钮切换后标签变化且目录仍在文件之前（同级重排）',
    sorted.label !== before && sorted.dirsFirst === true && sorted.dirs >= 1 && sorted.files >= 1,
    `before=${before} after=${JSON.stringify(sorted)}`);
  await s.close();
}

await browser.close();
console.log('');
console.log(`verify-doc-batch2: ${pass} passed, ${fail} failed`);
if (fail) { console.log('FAILURES:\n  - ' + failures.join('\n  - ')); process.exit(1); }
console.log('DOC BATCH2 PASS');

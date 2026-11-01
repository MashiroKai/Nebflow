#!/usr/bin/env node
// keyboard-audit.mjs — 键盘可达性勘察（只读，产出证据而非断言）。
//
// 用法：NEBFLOW_PW_PATH=<playwright-core> node scripts/keyboard-audit.mjs [--json]
//
// 零端口零进程（复用 scripts/lib/visual-harness.mjs 的 page.route 打桩），
// 结构上不可能碰到宿主实例端口。输出人读摘要 + JSON，供设计文档与回归引用。
// 退出码恒 0：这是勘察装置，不是门禁（门禁是 scripts/keyboard-check.mjs）。

import { loadPlaywright, launchBrowser, createSession, settle } from './lib/visual-harness.mjs';

const pw = loadPlaywright();
const { browser, label } = await launchBrowser(pw.mod, {});
const out = { browser: label, probes: {} };

/** 焦点元素的稳定描述（注入到页面里用）。 */
const FOCUS_DESCR = `(() => {
  const el = document.activeElement;
  if (!el || el === document.body) return 'BODY';
  const tag = el.tagName.toLowerCase();
  const id = el.id ? '#' + el.id : '';
  const cls = el.className && typeof el.className === 'string'
    ? '.' + el.className.trim().split(/\\s+/).slice(0, 2).join('.') : '';
  const lbl = (el.getAttribute('aria-label') || el.getAttribute('title') || el.textContent || '')
    .trim().replace(/\\s+/g, ' ').slice(0, 22);
  return tag + id + cls + (lbl ? ' "' + lbl + '"' : '');
})()`;

const focusNow = (page) => page.evaluate(FOCUS_DESCR);

async function fresh(opts = {}) {
  const s = await createSession(browser, {
    apiStubs: {
      '/api/agents': { body: { agents: [] } },
      '/api/projects': { body: { projects: [
        { id: 'p1', name: 'nebflow-stand', path: 'D:/workspace@zcode/nebflow/nebflow-stand' },
        { id: 'p2', name: 'nebflow', path: 'D:/workspace@zcode/nebflow' },
      ] } },
    },
  });
  await s.boot({ config: {}, ...opts });
  await s.page.waitForTimeout(400);
  return s;
}

/** 结构性可聚焦判定：进不进 Tab 序 + 有没有键盘 handler 的迹象。 */
const STRUCT = (sels) => ({
  sels,
});

// ── (A) Tab 序：从 body 连按 N 次 ───────────────────────────────────────
{
  const s = await fresh();
  await s.page.evaluate(() => document.body.focus());
  const order = [];
  for (let i = 0; i < 36; i++) {
    await s.page.keyboard.press('Tab');
    order.push(await focusNow(s.page));
  }
  out.probes.tabOrder = order;
  out.probes.tabReaches = {
    activityBar: order.some((o) => /activity-btn|messages-btn|files-btn|settings-btn/.test(o)),
    explorerTree: order.some((o) => /explorer-item/.test(o)),
    canvasDeck: order.some((o) => /deck-card/.test(o)),
    chatInput: order.some((o) => /chat-input|#input/.test(o)),
  };
  await s.close();
}

// ── (B) 主流程元素结构判定 ────────────────────────────────────────────
{
  const s = await fresh();
  const probe = (sels) => s.page.evaluate((list) => list.map((sel) => {
    const els = [...document.querySelectorAll(sel)];
    if (!els.length) return { sel, count: 0 };
    const f = els[0];
    return {
      sel, count: els.length, tag: f.tagName.toLowerCase(),
      tabindex: f.getAttribute('tabindex'), role: f.getAttribute('role'),
      inTabOrder: f.tabIndex >= 0,
      cls: typeof f.className === 'string' ? f.className : '',
    };
  }), sels);
  out.probes.structure = await probe([
    '.explorer-item.explorer-file',   // 文件树 = 打开文档的唯一自然入口
    '.explorer-item.explorer-folder',
    '.canvas-tab',                    // 桌面已 display:none
    '.deck-card',                     // 桌面叠卡
    '#deck-fab',
    '.activity-btn',
    '#chat-input', '#input', 'textarea',
    '.fm-conv-row',
    '.msg-bubble',
  ]);
  // 对话列表行要数据才有 —— 显式渲染一轮最小会话
  await s.frame({ type: 'conversationList', conversations: [
    { conversationId: 'c1', kind: 'friend', friend: { name: '阿甲' }, lastMessage: { text: 'hi', createdAt: 1757000000000 }, unreadCount: 0 },
    { conversationId: 'c2', kind: 'friend', friend: { name: '阿乙' }, lastMessage: { text: 'yo', createdAt: 1757000001000 }, unreadCount: 1 },
  ] });
  await s.page.waitForTimeout(400);
  out.probes.structureConvRows = await probe(['#fm-conversations .fm-conv-row']);
  await s.close();
}

// ── (C) 模态焦点：打开时去哪 / Tab 是否逃逸 / 关闭后是否回焦 ──────────
{
  const s = await fresh();
  await s.page.focus('#settings-btn');
  const before = await focusNow(s.page);
  await s.page.click('#settings-btn');
  await s.page.waitForTimeout(800);
  const onOpen = await focusNow(s.page);
  const inside = await s.page.evaluate(`(() => {
    const el = document.activeElement;
    const host = document.getElementById('settings-overlay');
    return { hostFound: !!host, focusInsideHost: !!(el && host && host.contains(el)) };
  })()`);
  const tabs = [];
  for (let i = 0; i < 24; i++) {
    await s.page.keyboard.press('Tab');
    tabs.push(await focusNow(s.page));
  }
  const leaked = tabs.filter((t) => /BODY|#input|#chat/.test(t));
  await s.page.keyboard.press('Escape');
  await s.page.waitForTimeout(500);
  const afterClose = await focusNow(s.page);
  out.probes.settingsModal = {
    before, onOpen, ...inside, tabEscapedTo: leaked.slice(0, 4), afterClose,
    restored: afterClose === before,
  };
  await s.close();
}

// ── (D) 右键弹层（showPopupMenu）焦点行为 ─────────────────────────────
{
  const s = await fresh();
  await s.page.evaluate(async () => {
    const mod = await import('/js/contextMenu.js');
    window.__showPopupMenu = mod.showPopupMenu;
  });
  await s.page.evaluate(() => window.__showPopupMenu(300, 300, [
    { label: '转发给 agent', onClick: () => {} },
    { label: '删除好友', danger: true, onClick: () => {} },
  ]));
  await s.page.waitForTimeout(200);
  const onOpen = await focusNow(s.page);
  const meta = await s.page.evaluate(`(() => {
    const m = document.querySelector('.fm-context-menu');
    if (!m) return 'menu missing';
    const b = [...m.querySelectorAll('button')];
    return { menuTabIndex: m.tabIndex, menuRole: m.getAttribute('role'),
             btnCount: b.length, btnTabIndex: b.map((x) => x.tabIndex) };
  })()`);
  await s.page.keyboard.press('ArrowDown');
  const afterArrow = await focusNow(s.page);
  await s.page.keyboard.press('Escape');
  await s.page.waitForTimeout(200);
  out.probes.popupMenu = { onOpen, ...(typeof meta === 'object' ? meta : { meta }), afterArrow, menuGone: await s.page.evaluate(() => !document.querySelector('.fm-context-menu')) };
  await s.close();
}

// ── (E) 重渲丢焦点：容器 innerHTML 整体重写后焦点是否存活 ─────────────
{
  const s = await fresh();
  out.probes.rerender = await s.page.evaluate(`(() => {
    const list = document.getElementById('explorer-tree');
    const item = list.querySelector('.explorer-item');
    item.setAttribute('tabindex', '0');
    item.focus();
    const focusedBefore = document.activeElement === item;
    // 与真实重渲同机制：读 innerHTML → 清空 → 回写
    const html = list.innerHTML;
    list.innerHTML = '';
    list.innerHTML = html;
    const el = document.activeElement;
    return {
      focusedBefore,
      focusedAfter: !el || el === document.body ? 'BODY' : el.tagName,
      focusLost: el !== item,
      // 新节点连旧引用都不在，焦点不可能靠同引用判定恢复
      oldNodeDetached: !document.contains(item),
    };
  })()`);
  await s.close();
}

// ── (F) 现有修饰键快捷键 ───────────────────────────────────────────────
{
  const s = await fresh();
  const sb = () => s.page.evaluate(() => document.body.className);
  const b0 = await sb();
  await s.page.keyboard.press('Control+b');
  await s.page.waitForTimeout(300);
  const b1 = await sb();
  await s.page.keyboard.press('Control+k');
  await s.page.waitForTimeout(300);
  const k = await s.page.evaluate(`(() => {
    const hits = [...document.querySelectorAll('[class*=palette],[class*=command-pal]')];
    return hits.length ? hits.map((h) => h.className) : 'nothing';
  })()`);
  await s.page.keyboard.press('Control+f');
  await s.page.waitForTimeout(400);
  out.probes.shortcuts = {
    ctrlB_changesBodyClass: b0 !== b1, bodyBefore: b0, bodyAfter: b1,
    ctrlK: k,
    ctrlF_after: await focusNow(s.page),
  };
  await s.close();
}

await browser.close();

// ── 人读摘要 ───────────────────────────────────────────────────────────
const P = out.probes;
const line = (s) => console.log(s);
line(`\nkeyboard-audit — browser=${out.browser}`);
line('\n(A) Tab 序（前 36 步）:');
P.tabOrder.forEach((t, i) => line(`  ${String(i + 1).padStart(2)}. ${t}`));
line(`  触达: ${JSON.stringify(P.tabReaches)}`);

line('\n(B) 主流程元素结构:');
for (const r of P.structure) line(`  ${r.sel.padEnd(30)} n=${String(r.count).padStart(3)} tag=${r.tag || '-'} tabindex=${r.tabindex} role=${r.role} inTab=${r.inTabOrder}`);
line(`  对话行: ${JSON.stringify(P.structureConvRows)}`);

line('\n(C) 设置模态焦点:');
line(`  before   = ${P.settingsModal.before}`);
line(`  onOpen   = ${P.settingsModal.onOpen}   hostFound=${P.settingsModal.hostFound} focusInside=${P.settingsModal.focusInsideHost}`);
line(`  Tab 逃逸到 = ${JSON.stringify(P.settingsModal.tabEscapedTo)}`);
line(`  afterEsc = ${P.settingsModal.afterClose}   restored=${P.settingsModal.restored}`);

line('\n(D) 右键弹层:');
line(`  ${JSON.stringify(P.popupMenu)}`);

line('\n(E) 重渲丢焦点:');
line(`  ${JSON.stringify(P.rerender)}`);

line('\n(F) 现有快捷键:');
line(`  ${JSON.stringify(P.shortcuts)}`);

if (process.argv.includes('--json')) line('\n' + JSON.stringify(out, null, 2));

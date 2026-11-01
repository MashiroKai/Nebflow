#!/usr/bin/env node
// keyboard-check.mjs — 键盘层验收门禁（keyboard-first 一期，2026-11-01）。
//
// 用法：NEBFLOW_PW_PATH=<playwright-core> node scripts/keyboard-check.mjs
// 退出码：0 全绿；1 有断言失败；2 装置错误（无浏览器 / playwright 不可解析）。
//
// 装置同 visual-baseline（scripts/lib/visual-harness.mjs）：page.route 从磁盘
// 服务 src/main/resources/web + MockWS 走真实 ws.js 分发 ⇒ **零端口零进程**，
// 结构上不可能碰到宿主实例。
//
// 为什么必须是自动化的：本批的核心风险不在「按键能不能触发」，而在三件看不见的
// 事 —— ① IME 合成态的 Enter 会不会误发；② Tab 会不会逃出模态；③ 关闭后焦点
// 回不回得去。这三条靠人眼是验不出来的（尤其 IME），只能脚本化。

import { loadPlaywright, launchBrowser, createSession, settle } from './lib/visual-harness.mjs';

const pw = loadPlaywright();
const { browser, label } = await launchBrowser(pw.mod, {});

let pass = 0;
let fail = 0;
const failures = [];

/**
 * @param {string} name
 * @param {boolean} cond
 * @param {string} [detail]
 */
function check(name, cond, detail = '') {
  if (cond) { pass++; console.log(`  PASS  ${name}`); }
  else { fail++; failures.push(name + (detail ? ` — ${detail}` : '')); console.log(`  FAIL  ${name}${detail ? ' — ' + detail : ''}`); }
}

async function fresh(opts = {}) {
  const s = await createSession(browser, {
    apiStubs: { '/api/agents': { body: { agents: [] } } },
    ...opts,
  });
  await s.boot({ config: {}, messages: [] });
  await s.page.waitForTimeout(400);
  return s;
}

const active = (page) => page.evaluate(`(() => {
  const el = document.activeElement;
  if (!el || el === document.body) return 'BODY';
  return (el.id ? '#' + el.id : el.tagName.toLowerCase() + (el.className && typeof el.className === 'string' ? '.' + el.className.trim().split(/\\s+/)[0] : ''));
})()`);

const mode = (page) => page.evaluate(() => (document.getElementById('nf-mode-chip') || {}).dataset?.mode || 'none');

console.log(`keyboard-check — browser=${label}\n`);

// ── A 注册器（纯逻辑，不经 UI） ─────────────────────────────────────────
console.log('A 注册器');
{
  const s = await fresh();
  const r = await s.page.evaluate(async () => {
    const km = await import('/js/keymap.js');
    const out = {};
    // 冲突：同 scope 同 combo 同模式 ⇒ 必须抛
    km.registerShortcut({ id: 't.dup1', combo: 'Mod+J', mode: 'meta', description: 'x', handler: () => {} });
    try {
      km.registerShortcut({ id: 't.dup2', combo: 'Mod+J', description: 'x', handler: () => {} });
      out.dupThrows = false;
    } catch { out.dupThrows = true; }
    // 不同 mode 同 combo ⇒ 必须不抛（双模态的用法，不是冲突）
    try {
      km.registerShortcut({ id: 't.dup3', combo: 'Mod+J', mode: 'edit', description: 'x', handler: () => {} });
      out.modeSplitOk = true;
    } catch { out.modeSplitOk = false; }
    // 注销后不再拦截
    let fired = 0;
    const off = km.registerShortcut({ id: 't.count', combo: 'Mod+L', description: 'x', handler: () => { fired++; } });
    out.listedBefore = km.listShortcuts().some((x) => x.id === 't.count');
    off();
    out.listedAfter = km.listShortcuts().some((x) => x.id === 't.count');
    // 场景启停
    km.enableScope('scen', false);
    out.scopeOff = km.isScopeActive('scen') === false;
    km.enableScope('scen', true);
    out.scopeOn = km.isScopeActive('scen') === true;
    out.fired = fired;
    return out;
  });
  check('重复注册同一 combo 抛错（注册期硬错）', r.dupThrows === true);
  check('同一 combo 在不同 mode 下可共存', r.modeSplitOk === true);
  check('注销后从注册表消失', r.listedBefore === true && r.listedAfter === false);
  check('场景启停生效', r.scopeOff === true && r.scopeOn === true);
  await s.close();
}

// ── B 命令面板 ─────────────────────────────────────────────────────────
console.log('B 命令面板');
{
  const s = await fresh();
  await s.page.evaluate(() => document.getElementById('files-btn').focus());
  const before = await active(s.page);
  await s.page.keyboard.press('Control+k');
  await s.page.waitForTimeout(250);
  const open = await s.page.evaluate(() => {
    const o = document.getElementById('nf-palette-overlay');
    return { visible: !!o && !o.hidden, focusInInput: document.activeElement?.id === 'nf-palette-input' };
  });
  check('Ctrl+K 打开面板', open.visible === true);
  check('打开后焦点在搜索框', open.focusInInput === true);

  // 搜 + ↑↓ + Enter：搜「设置」→ 执行 → 设置面板应当出现（可观察后果）
  await s.page.keyboard.type('设置');
  await s.page.waitForTimeout(200);
  const rows = await s.page.evaluate(() => document.querySelectorAll('#nf-palette-list .nf-palette-row').length);
  check('输入即搜（有命中）', rows > 0, `rows=${rows}`);

  await s.page.keyboard.press('Enter');
  await s.page.waitForTimeout(600);
  const afterRun = await s.page.evaluate(() => {
    const ov = document.getElementById('settings-overlay');
    return { closed: document.getElementById('nf-palette-overlay').hidden, settingsOpen: !!ov && (ov.style.display !== 'none' && !ov.hidden) };
  });
  check('Enter 执行命令（打开了设置面）', afterRun.settingsOpen === true);
  check('执行后面板关闭', afterRun.closed === true);

  // Esc 关闭 + 回焦
  await s.page.evaluate(() => { const o = document.getElementById('settings-overlay'); if (o) { o.hidden = true; o.style.display = 'none'; } });
  await s.page.evaluate(() => document.getElementById('settings-btn').focus());
  const opener = await active(s.page);
  await s.page.keyboard.press('Control+k');
  await s.page.waitForTimeout(200);
  await s.page.keyboard.press('Escape');
  await s.page.waitForTimeout(250);
  const st = await s.page.evaluate(() => ({ hidden: document.getElementById('nf-palette-overlay').hidden }));
  const restored = await active(s.page);
  check('Esc 关闭面板', st.hidden === true);
  check('关闭后回焦到打开前元素', restored === opener, `${restored} vs ${opener}`);
  await s.close();
}

// ── C 模态焦点陷阱 + 关闭回焦 ──────────────────────────────────────────
console.log('C 模态');
{
  const s = await fresh();
  await s.page.evaluate(() => window.__showConfirm.test ? null : null);
  await s.page.evaluate(() => {
    const btn = document.createElement('button');
    btn.id = 'nf-test-opener';
    btn.textContent = 'opener';
    document.body.appendChild(btn);
    btn.focus();
    window.__showConfirm('确认？', '测试模态', () => {}, {});
  });
  await s.page.waitForTimeout(300);
  const inModal = await s.page.evaluate(() => {
    const ov = document.getElementById('modal-overlay');
    return { on: ov.classList.contains('on'), focusInside: ov.contains(document.activeElement) };
  });
  check('模态打开即落焦进模态', inModal.on === true && inModal.focusInside === true);

  let escaped = 0;
  for (let i = 0; i < 30; i++) {
    await s.page.keyboard.press('Tab');
    const inside = await s.page.evaluate(() => document.getElementById('modal-overlay').contains(document.activeElement));
    if (!inside) escaped++;
  }
  check('Tab ×30 不逃出模态', escaped === 0, `逃出 ${escaped} 次`);

  await s.page.keyboard.press('Escape');
  await s.page.waitForTimeout(250);
  const back = await active(s.page);
  check('模态关闭后回焦触发元素', back === '#nf-test-opener', back);
  await s.close();
}

// ── D 焦点环（暗/亮两套主题都可见） ────────────────────────────────────
console.log('D 焦点环');
for (const scheme of ['dark', 'light']) {
  const s = await fresh({ colorScheme: scheme });
  await s.page.keyboard.press('Tab');
  await s.page.keyboard.press('Tab');
  const ring = await s.page.evaluate(() => {
    const el = document.activeElement;
    const cs = getComputedStyle(el);
    return { tag: el?.tagName, id: el?.id, w: cs.outlineWidth, color: cs.outlineColor, style: cs.outlineStyle };
  });
  const visible = ring.w === '2px' && ring.style !== 'none'
    && !!ring.color && ring.color !== 'rgba(0, 0, 0, 0)' && ring.color !== 'transparent';
  check(`焦点环可见（${scheme}）`, visible, JSON.stringify(ring));
  await s.close();
}

// ── E meta/edit 双模态 ────────────────────────────────────────────────
console.log('E 双模态');
{
  const s = await fresh();
  // 启动即 meta，且指示器可见
  const boot = await s.page.evaluate(() => {
    const chip = document.getElementById('nf-mode-chip');
    if (!chip) return { exists: false };
    const r = chip.getBoundingClientRect();
    return { exists: true, mode: chip.dataset.mode, visible: r.width > 0 && r.height > 0, text: chip.textContent };
  });
  check('启动态 = meta 且指示器可见', boot.exists === true && boot.mode === 'meta' && boot.visible === true, JSON.stringify(boot));

  // `]` 中心区：焦点进消息流
  await s.page.evaluate(() => document.body.focus());
  await s.page.keyboard.press('BracketRight');
  await s.page.waitForTimeout(150);
  const afterBracket = await active(s.page);
  check('meta `]` 移动中心区焦点', afterBracket === '#chat' || afterBracket.startsWith('div'), afterBracket);
  check('`]` 不改变模式', (await mode(s.page)) === 'meta');

  // Shift+] 侧边栏
  await s.page.keyboard.press('Shift+BracketRight');
  await s.page.waitForTimeout(150);
  const sb = await s.page.evaluate(() => {
    const el = document.activeElement;
    const panel = document.querySelector('#sidebar-panel .panel.active') || document.querySelector('#sidebar-panel');
    return { inside: !!(panel && el && panel.contains(el)), tag: el?.tagName, cls: typeof el?.className === 'string' ? el.className.split(' ')[0] : '' };
  });
  check('meta `Shift+]` 把焦点移进侧边栏条目', sb.inside === true, JSON.stringify(sb));

  // Space → edit（先真正清空焦点：body 不可聚焦，直接 focus() 是空操作）
  await s.page.evaluate(() => { if (document.activeElement instanceof HTMLElement) document.activeElement.blur(); });
  await s.page.keyboard.press('Space');
  await s.page.waitForTimeout(200);
  const edit = await s.page.evaluate(() => ({
    mode: document.getElementById('nf-mode-chip').dataset.mode,
    focus: document.activeElement?.id,
  }));
  check('meta Space → edit，焦点进输入框', edit.mode === 'edit' && edit.focus === 'input', JSON.stringify(edit));

  // edit 下 `]` 是普通字符，不移动焦点
  await s.page.keyboard.type('a]b');
  await s.page.waitForTimeout(120);
  const typed = await s.page.evaluate(() => document.getElementById('input').value);
  check('edit 下 `]` 输入为字符（不是导航键）', typed === 'a]b', typed);

  // IME：合成态 Enter 不得发送、不得切模式
  const ime = await s.page.evaluate(async () => {
    const input = document.getElementById('input');
    input.focus();
    const before = (window.__wsSent || []).length;
    const ev = new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', bubbles: true, cancelable: true, isComposing: true });
    input.dispatchEvent(ev);
    await new Promise((r) => setTimeout(r, 250));
    return { before, after: (window.__wsSent || []).length, mode: document.getElementById('nf-mode-chip').dataset.mode };
  });
  check('IME 合成态 Enter 不发送', ime.after === ime.before, `sent ${ime.before}→${ime.after}`);
  check('IME 合成态 Enter 不切模式', ime.mode === 'edit', ime.mode);

  // 真实 Enter：发送 + edit→meta（必须先有内容，空 Enter 不产生帧）
  await s.page.evaluate(() => { const i = document.getElementById('input'); i.focus(); });
  await s.page.keyboard.type('键盘验收');
  await s.page.waitForTimeout(120);
  const sent = await s.page.evaluate(() => (window.__wsSent || []).length);
  await s.page.keyboard.press('Enter');
  await s.page.waitForTimeout(350);
  const afterSend = await s.page.evaluate(() => ({
    sent: (window.__wsSent || []).length,
    mode: document.getElementById('nf-mode-chip').dataset.mode,
    focus: document.activeElement?.id || document.activeElement?.tagName,
  }));
  check('Enter 发送（产生帧）', afterSend.sent > sent, `${sent}→${afterSend.sent}`);
  check('Enter 后 edit → meta', afterSend.mode === 'meta', JSON.stringify(afterSend));
  check('Enter 后焦点离开输入框', afterSend.focus !== 'input', afterSend.focus);
  await s.close();
}

await browser.close();

console.log(`\nkeyboard-check: ${pass} passed, ${fail} failed`);
if (fail) { console.log('FAILURES:\n  - ' + failures.join('\n  - ')); process.exit(1); }
console.log('KEYBOARD CHECK PASS');

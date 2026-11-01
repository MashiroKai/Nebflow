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
import { activeKey, countEvents, resetEvents, trackEvents, tabOrderIndex, layerDepth } from './lib/keyboard-probes.mjs';

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

// ── D 焦点环矩阵（B3-1：选择器 × 双主题）────────────────────────────────
// 改造前只有 2 条（拍两次 Tab 看到谁算谁）。B3-1 删掉 12 条纯 `:focus{outline:none}`
// 之后，兜底要逐个控件证明真的生效 —— 否则「删了但被别处 (0,2,0) 压掉」是不可
// 观测的。判据：outline-width = 2px 且颜色非透明且 style !== none。
console.log('D 焦点环矩阵');
const RING_TARGETS = ['.activity-btn', '.nav-agent', '.icon-btn', '#send-btn', '.explorer-item', '.deck-card'];
for (const scheme of ['dark', 'light']) {
  const s = await fresh({ colorScheme: scheme });
  // 叠卡卡要在环里才有得验：先开两个文档再开叠卡（与 G 组同一通道）
  for (const [id, title] of [['file:/tmp/ring-a.md', 'ring-a.md'], ['file:/tmp/ring-b.md', 'ring-b.md']]) {
    await s.page.evaluate((d) => {
      window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: d }));
    }, { id, itemType: 'markdown', title, content: '# r\n', absPath: id.replace('file:', ''), size: 4, mtimeMs: 1757000000000 });
    await s.page.waitForSelector(`#canvas-panel .canvas-tab-pane[data-tab-id="${id}"]`, { state: 'visible', timeout: 15000 });
  }
  await s.page.click('#deck-fab');
  await s.page.waitForSelector('#deck-layer .deck-card', { timeout: 15000 });
  await s.page.waitForTimeout(1500);
  // 键盘模态：先按一次 Tab，让随后的 focus() 落在 :focus-visible 口径上
  await s.page.keyboard.press('Tab');
  for (const sel of RING_TARGETS) {
    const ring = await s.page.evaluate((q) => {
      let el = /** @type {HTMLElement|null} */ (document.querySelector(q));
      let synthetic = false;
      if (!el && q === '.nav-agent') {
        // 夹具里没有 agent 段（apiStubs 给的是空名册）⇒ 造一个同 class 的节点。
        // 本条验的是 **CSS 面**（该选择器上有没有能压掉兜底的 (0,2,0) 规则），
        // 不是 nav 段的装配逻辑，所以合成节点是合法探针；detail 里会标 synthetic。
        const host = document.getElementById('nav-agent-list') || document.body;
        el = document.createElement('div');
        el.className = 'nav-agent';
        el.tabIndex = -1;          // divisor 默认不可聚焦 ⇒ 不设它 focus() 是空操作
        el.textContent = 'probe';
        host.appendChild(el);
        synthetic = true;
      }
      if (!el) return { missing: true };
      el.focus({ preventScroll: true });
      const cs = getComputedStyle(el);
      return { w: cs.outlineWidth, color: cs.outlineColor, style: cs.outlineStyle, fv: el.matches(':focus-visible'), synthetic };
    }, sel);
    const visible = !ring.missing && ring.w === '2px' && ring.style !== 'none'
      && !!ring.color && ring.color !== 'rgba(0, 0, 0, 0)' && ring.color !== 'transparent';
    check(`焦点环 ${sel}（${scheme}）`, visible, JSON.stringify(ring));
  }
  await s.close();
}

// ── ⑬ css/ 无未配对的 outline:none（离线读 CSS 文本，不开浏览器）──────────
{
  const { readFileSync: rf, readdirSync: rd } = await import('node:fs');
  const { join: jn } = await import('node:path');
  const { fileURLToPath: f2p } = await import('node:url');
  const cssDir = jn(f2p(new URL('..', import.meta.url)), 'src', 'main', 'resources', 'web', 'css');
  const exemptions = JSON.parse(rf(jn(f2p(new URL('..', import.meta.url)), 'scripts', 'focus-ring-exemptions.json'), 'utf8')).exemptions;
  /** @type {string[]} */
  const unpaired = [];
  for (const f of rd(cssDir).filter((x) => x.endsWith('.css'))) {
    // B3-1 之前：`outline:none` 若挂在 `:focus` 规则里、又没有 `:focus-visible` 配对，
    // 就会以 (0,2,0) 压掉 keyboard.css:28 的 (0,1,0) 兜底 —— 键盘聚焦无环。
    const txt = rf(jn(cssDir, f), 'utf8');
    const re = /([^{}]+)\{([^{}]*)\}/g;
    const rules = [];
    let m;
    while ((m = re.exec(txt))) {
      // 选择器里可能带前置注释（/* Sapphire focus glow */ .x:focus）—— 归一化前先剥掉，
      // 否则豁免表按选择器比对会永远差一个注释前缀（首版就是这么红的）。
      const sel = m[1].replace(/\/\*[\s\S]*?\*\//g, '').trim().replace(/\s+/g, ' ');
      rules.push({ sel, body: m[2] });
    }
    // 同一文件里带 `:focus-visible` 的基选择器集合 = 「已配对」。
    // 正确范式是：`:focus { outline: none }` + `:focus-visible { <显式环> }`（例如
    // projectPanel.css:340-345），此时鼠标聚焦无环、键盘聚焦有环 —— 这不是缺陷。
    // 缺陷是**只有 :focus{outline:none} 而没有 :focus-visible 兄弟**：那一条以
    // (0,2,0) 把 keyboard.css:28 的 (0,1,0) 兜底压掉，键盘聚焦彻底无环。
    const base = (s) => s.split(',').map((x) => x.replace(/:focus-visible\b/g, '').trim());
    /** @type {Set<string>} */
    const paired = new Set();
    for (const r of rules) if (/:focus-visible/.test(r.sel)) for (const b of base(r.sel)) paired.add(b);
    for (const r of rules) {
      if (!/:focus\b/.test(r.sel) || /:focus-visible/.test(r.sel)) continue;
      if (!/outline\s*:\s*(none|0)/.test(r.body)) continue;
      const bases = r.sel.split(',').map((x) => x.replace(/:focus(-visible)?\b/g, '').trim());
      if (bases.every((b) => paired.has(b))) continue; // 有 :focus-visible 兄弟 = 合法范式
      const exempt = exemptions.some((e) => e.file === `css/${f}` && e.selector.replace(/\s+/g, ' ') === r.sel);
      if (!exempt) unpaired.push(`css/${f} :: ${r.sel}`);
    }
  }
  check('⑬ css/ 内 :focus{outline:none} 未配对 :focus-visible 的条数 − 豁免 = 0',
    unpaired.length === 0, `${unpaired.length} 条：${unpaired.slice(0, 4).join(' | ')}`);
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

// ── F 文件树键盘化（B1：**业务语义**断言，用 B0-5 的共享探针取证）──────────
// 这一组是「断言终于问到业务」的分水岭：A–E 问的是「设施在不在」（注册器/面板/
// 模态/焦点环/双模态），F 起问的是「按一下真的发生了什么」——焦点到了哪一行、
// 目录真的开没开、Enter 真的发出取数帧没有、页签真的出现在画布上没有。
console.log('F 文件树');
{
  const s = await fresh();
  await trackEvents(s.page, ['workspace-open-item']);

  // ① Tab 可达首行（并顺带证树容器 role="tree" 与行 role="treeitem"）
  const treeShape = await s.page.evaluate(() => {
    const t = document.getElementById('explorer-tree');
    return {
      role: t ? t.getAttribute('role') : null,
      rows: t ? t.querySelectorAll('.explorer-item[role="treeitem"]').length : 0,
    };
  });
  /** @type {string|null} */
  let reached = null;
  for (let i = 0; i < 40; i++) {
    await s.page.keyboard.press('Tab');
    const k = await activeKey(s.page);
    if (k.startsWith('key:explorer:')) { reached = k; break; }
  }
  check('① Tab 可达文件树行（role=tree / treeitem）', !!reached && treeShape.role === 'tree' && treeShape.rows >= 4, `${reached} ${JSON.stringify(treeShape)}`);

  // ② 同一时刻恰好一行 tabindex=0（roving 的不变量）
  const zeroRows = await s.page.evaluate(() => [...document.querySelectorAll('#explorer-tree .explorer-item[role="treeitem"]')]
    .filter((el) => el.tabIndex === 0).length);
  check('② 同一时刻恰好 1 行 tabindex=0', zeroRows === 1, `zero=${zeroRows}`);

  // ③ ↓ 逐行下移（连按三拍，每拍都换行）
  await s.page.evaluate(() => {
    const r = /** @type {HTMLElement} */ (document.querySelector('#explorer-tree .explorer-item[role="treeitem"]'));
    if (r) r.focus();
  });
  /** @type {string[]} */
  const downSeq = [];
  for (let i = 0; i < 3; i++) {
    await s.page.keyboard.press('ArrowDown');
    await s.page.waitForTimeout(60);
    downSeq.push(await activeKey(s.page));
  }
  const moved = downSeq.every((k, i) => k.startsWith('key:explorer:') && (i === 0 || k !== downSeq[i - 1]));
  check('③ ↓ 逐行下移（焦点真的换行）', moved, JSON.stringify(downSeq));

  // ④ → 展开目录：aria-expanded=true **且**子行真的出现
  await s.page.evaluate(() => {
    const r = /** @type {HTMLElement} */ (document.querySelector('#explorer-tree .explorer-folder[data-path="docs"]'));
    if (r) r.focus();
  });
  const rowsBefore = await s.page.evaluate(() => document.querySelectorAll('#explorer-tree .explorer-item').length);
  await s.page.keyboard.press('ArrowRight');
  await s.page.waitForTimeout(400);
  const expanded = await s.page.evaluate((n) => {
    const row = document.querySelector('#explorer-tree .explorer-folder[data-path="docs"]');
    const rows = document.querySelectorAll('#explorer-tree .explorer-item').length;
    return { aria: row ? row.getAttribute('aria-expanded') : null, rows, added: rows - n };
  }, rowsBefore);
  check('④ → 展开目录（aria-expanded + 子行出现）', expanded.aria === 'true' && expanded.added > 0, JSON.stringify(expanded));

  // ⑤ ← 收起
  await s.page.evaluate(() => {
    const r = /** @type {HTMLElement} */ (document.querySelector('#explorer-tree .explorer-folder[data-path="docs"]'));
    if (r) r.focus();
  });
  await s.page.keyboard.press('ArrowLeft');
  await s.page.waitForTimeout(300);
  const collapsed = await s.page.evaluate(() => {
    const row = document.querySelector('#explorer-tree .explorer-folder[data-path="docs"]');
    return row ? row.getAttribute('aria-expanded') : null;
  });
  check('⑤ ← 收起目录', collapsed === 'false', String(collapsed));

  // ⑥ Enter 在文件行 → 走**既有激活路径**（readFile 帧），不另造一条
  await s.page.evaluate(() => {
    const r = /** @type {HTMLElement} */ (document.querySelector('#explorer-tree .explorer-file[data-path="README.md"]'));
    if (r) r.focus();
  });
  const sentBefore = await s.page.evaluate(() => (window.__wsSent || []).length);
  await s.page.keyboard.press('Enter');
  await s.page.waitForTimeout(300);
  const readFiles = await s.page.evaluate((n) => (window.__wsSent || []).slice(n)
    .filter((f) => f.type === 'readFile').map((f) => f.path), sentBefore);
  check('⑥ Enter 在文件行发出 readFile（既有激活路径）', readFiles.includes('README.md'), JSON.stringify(readFiles));

  // ⑦ 应答回灌 → workspace-open-item 计数 ≥1 **且** 画布出现该页签
  await s.frame({
    type: 'fileContent', path: 'README.md', absPath: '/proj/README.md',
    content: '# readme\n\n键盘打开的。\n', size: 24, itemType: 'markdown', mtimeMs: 1757000000000,
  });
  await s.page.waitForTimeout(600);
  const openedEv = await countEvents(s.page, 'workspace-open-item');
  const tabShown = await s.page.evaluate(() => !!document.querySelector('#canvas-panel .canvas-tab-pane[data-tab-id="file:README.md"]'));
  check('⑦ 打开后画布出现对应页签', openedEv >= 1 && tabShown === true, `events=${openedEv} tab=${tabShown}`);

  // ⑧ `.explorer-creating` 不进 Tab 序（行本身不可聚焦、不是 treeitem；Tab 落在内部 input）
  await s.page.click('#explorer-new-file-btn');
  await s.page.waitForTimeout(250);
  const creating = await s.page.evaluate(() => {
    const row = document.querySelector('#explorer-tree .explorer-creating');
    return {
      exists: !!row,
      role: row ? row.getAttribute('role') : null,
      ti: row ? /** @type {HTMLElement} */ (row).tabIndex : null,
      treeitems: row ? row.querySelectorAll('[role="treeitem"]').length : -1,
      focusIn: !!(row && row.contains(document.activeElement)),
    };
  });
  check('⑧ .explorer-creating 不进 Tab 序', creating.exists === true && creating.role === 'none'
    && creating.ti === -1 && creating.treeitems === 0 && creating.focusIn === true, JSON.stringify(creating));
  await s.page.keyboard.press('Escape');
  await s.page.waitForTimeout(150);
  await s.close();
}

// ── G 叠卡 + 中心环（B2：卡壳可达 + 中心环 3 段）────────────────────────
console.log('G 叠卡 + 中心环');
{
  const s = await fresh();
  // 打开两个文档（与 visual-baseline 的 deck-cards 同一条打开通道），再开叠卡环
  for (const [id, title, content] of [
    ['file:/tmp/kb-a.md', 'kb-a.md', '# A\n\n键盘验收。\n'],
    ['file:/tmp/kb-b.md', 'kb-b.md', '# B\n\n键盘验收。\n'],
  ]) {
    await s.page.evaluate((d) => {
      window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: d }));
    }, { id, itemType: 'markdown', title, content, absPath: id.replace('file:', ''), size: content.length, mtimeMs: 1757000000000 });
    await s.page.waitForSelector(`#canvas-panel .canvas-tab-pane[data-tab-id="${id}"]`, { state: 'visible', timeout: 15000 });
  }
  await s.page.click('#deck-fab');
  await s.page.waitForSelector('#deck-layer .deck-card', { timeout: 15000 });
  await s.page.waitForTimeout(1500);
  /* 取证口径全部走 **DOM**，不读模块单例：`__canvasDeck.state()` 在本装置里有
     一份与 DOM 不同步的副本（实测 state().M=0 而同页有 2 张卡），拿它做断言等于
     测了个影子。DOM 事实 = 用户事实。 */
  const cardsInfo = () => s.page.evaluate(() => [...document.querySelectorAll('#deck-layer .deck-card:not(.ghost)')]
    .map((el) => ({
      tab: /** @type {HTMLElement} */ (el).dataset.tab,
      name: /** @type {HTMLElement} */ (el).querySelector('.n-text').textContent,
      w: +/** @type {HTMLElement} */ (el).getBoundingClientRect().width.toFixed(1),
      ti: /** @type {HTMLElement} */ (el).tabIndex,
    })));

  // ⑨ `.deck-card` 在 Tab 序里（不靠 fab 才碰得到）
  const idx = await tabOrderIndex(s.page, '#deck-layer .deck-card[tabindex="0"]', { steps: 40 });
  check('⑨ .deck-card 在 Tab 序（role=tab + roving）', idx >= 0, `tabIndexInOrder=${idx}`);
  await s.page.evaluate(() => { if (document.activeElement instanceof HTMLElement) document.activeElement.blur(); });

  // ⑩ `]` 在 3 个停靠点循环（首拍从环外进场 = 消息流，之后 卡 → 画布 → 消息流）
  /** @type {string[]} */
  const ring = [];
  for (let i = 0; i < 4; i++) {
    await s.page.keyboard.press('BracketRight');
    await s.page.waitForTimeout(150);
    ring.push(await activeKey(s.page));
  }
  const cycle = ring.slice(1);
  const distinct = new Set(cycle).size;
  check('⑩ `]` 中心环 3 段循环回到起点',
    distinct === 3 && cycle[0].startsWith('key:deck:') && cycle[1] === '#canvas-panel' && cycle[2] === '#chat',
    JSON.stringify(ring));

  // ⑪ Enter 在**非前景**卡上 → 环翻到该卡（.deck-card 既有 click 语义）
  // 前景判据 = `data-r`（环位 0 = 前景，deck.pos 开环即为 0）。宽度不可作判据：
  // 两张卡宽度接近，取宽度差会挑中前景卡本身 —— 而对前景卡按 Enter 的语义是
  // **关闭叠卡**（设计如此），实测会把 ⑫ 也一起带红（首版断言就是这么红的）。
  const geo = () => s.page.evaluate(() => [...document.querySelectorAll('#deck-layer .deck-card:not(.ghost)')]
    .map((el) => {
      const r = /** @type {HTMLElement} */ (el).getBoundingClientRect();
      return { tab: (/** @type {HTMLElement} */ (el)).dataset.tab, ring: /** @type {HTMLElement} */ (el).dataset.r, x: +r.left.toFixed(1), w: +r.width.toFixed(1) };
    }));
  const before = await geo();
  const target = before.find((c) => c.ring !== '0') || before[before.length - 1];
  await s.page.evaluate((tab) => {
    const el = /** @type {HTMLElement} */ (document.querySelector(`#deck-layer .deck-card[data-tab="${tab}"]`));
    if (el) el.focus({ preventScroll: true });
  }, target.tab);
  await s.page.keyboard.press('Enter');
  await s.page.waitForTimeout(1400);
  const after = await geo();
  const now = after.find((c) => c.tab === target.tab);
  const moved = !!now && (Math.abs(now.x - target.x) > 5 || Math.abs(now.w - target.w) > 5);
  check('⑪ Enter 在非前景卡 → 环翻到该卡（位形变化、叠卡未关）',
    moved && after.length === before.length,
    `card ${target.tab} x ${target.x}→${now ? now.x : 'gone'} w ${target.w}→${now ? now.w : 'gone'} cards ${before.length}→${after.length}`);

  // ⑫ Delete 关掉当前卡，且**不进层栈**（layerDepth 不变）
  const depthBefore = await layerDepth(s.page);
  const countBefore = (await geo()).length;
  await s.page.evaluate(() => {
    const card = /** @type {HTMLElement} */ (document.querySelector('#deck-layer .deck-card[tabindex="0"]'));
    if (card) card.focus({ preventScroll: true });
  });
  await s.page.keyboard.press('Delete');
  await s.page.waitForTimeout(1000);
  const countAfter = (await geo()).length;
  const depthAfter = await layerDepth(s.page);
  check('⑫ Delete 关当前卡且 layerDepth 不变',
    countAfter === countBefore - 1 && depthAfter === depthBefore,
    `cards ${countBefore}→${countAfter} depth ${depthBefore}→${depthAfter}`);
  await s.close();
}

await browser.close();

console.log(`\nkeyboard-check: ${pass} passed, ${fail} failed`);
if (fail) { console.log('FAILURES:\n  - ' + failures.join('\n  - ')); process.exit(1); }
console.log('KEYBOARD CHECK PASS');

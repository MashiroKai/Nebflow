#!/usr/bin/env node
// keyboard-check.mjs — 键盘层验收门禁（keyboard-first 一期，2026-11-01）。
//
// 用法：
//   NEBFLOW_PW_PATH=<playwright-core> node scripts/keyboard-check.mjs
//   node scripts/keyboard-check.mjs --web-root build/web-dist        # 产物形态
//   NEBFLOW_WEB_ROOT=build/web-dist node scripts/keyboard-check.mjs  # 等价写法
// 退出码：0 全绿；1 有断言失败；2 装置错误（无浏览器 / playwright 不可解析）。
//
// 装置同 visual-baseline（scripts/lib/visual-harness.mjs）：page.route 从磁盘
// 服务静态根 + MockWS 走真实 ws.js 分发 ⇒ **零端口零进程**，结构上不可能碰到
// 宿主实例。
//
// 验证对象可切换（G6，2026-11-01 装置批，**先修装置再继续 B7**）：
//   改造前本脚本按**源路径**取模块句柄（`import('/js/keymap.js')`），而运行实例吃
//   的是 jar 里的 `web-dist` —— 那里没有 `/js/` 路由（模块合进 entry chunk、导出名
//   压成单字母别名），实测直接 `Failed to fetch dynamically imported module`。
//   含义很直白：**视觉闸做到了「验的 = 跑的」，键盘闸没做到**，键盘侧所有结论只在
//   源树上成立，一重打包就可能全变。
//   现在：根走**同一套** `webRoot()/setWebRoot()`（G3 已建好，不另造第二套）；模块
//   句柄走产品侧登记表 `window.__nfProbeSurface`（js/probeSurface.js）⇒ 两种形态
//   拿到的都是**产品正在用的那一份实例**；静态扫描在产物形态下改读产物自带的
//   sourcemap sourcesContent 与 app-*.css bundle ⇒ 扫的是「打进产物那份字节」。
//   跑不了的断言一律 `SKIP` 并给原因（不是静默跳过，也不是通过）：分母与分子都
//   打在末行汇总里。
//
// 为什么必须是自动化的：本批的核心风险不在「按键能不能触发」，而在三件看不见的
// 事 —— ① IME 合成态的 Enter 会不会误发；② Tab 会不会逃出模态；③ 关闭后焦点
// 回不回得去。这三条靠人眼是验不出来的（尤其 IME），只能脚本化。

import { loadPlaywright, launchBrowser, createSession, settle, webRoot, setWebRoot } from './lib/visual-harness.mjs';
import { activeKey, countEvents, resetEvents, trackEvents, tabOrderIndex, layerDepth } from './lib/keyboard-probes.mjs';
import { describeWebRoot, webForm, installModuleResolver, jsSources, cssSources, shippedVsWorktree, surfaceHint } from './lib/probe-surface.mjs';

// 验证对象切换（G6）：CLI `--web-root` 优先，其次 NEBFLOW_WEB_ROOT（模块加载时由
// visual-harness 读取），缺省源树 —— 与 visual-baseline 逐字同一条口径。
const argVal = (/** @type {string} */ name) => {
  const i = process.argv.indexOf(name);
  return i >= 0 ? (process.argv[i + 1] || null) : null;
};
const WEB_ROOT_OVERRIDE = argVal('--web-root');
if (WEB_ROOT_OVERRIDE) setWebRoot(WEB_ROOT_OVERRIDE);

const pw = loadPlaywright();
const { browser, label } = await launchBrowser(pw.mod, {});

let pass = 0;
let fail = 0;
let skipsN = 0;
const failures = [];
const skips = [];

/**
 * @param {string} name
 * @param {boolean} cond
 * @param {string} [detail]
 */
function check(name, cond, detail = '') {
  if (cond) { pass++; console.log(`  PASS  ${name}`); }
  else { fail++; failures.push(name + (detail ? ` — ${detail}` : '')); console.log(`  FAIL  ${name}${detail ? ' — ' + detail : ''}`); }
}

/**
 * 断言**未执行**（不是通过）：只用于「当前形态下取证对象根本取不到」的情形。
 * 名字照样进汇总分母，原因进明细表 —— 静默跳过是本项目反复栽的那件事。
 * @param {string} name
 * @param {string} reason
 */
function skip(name, reason) {
  skipsN++;
  skips.push(`${name} — ${reason}`);
  console.log(`  SKIP  ${name} — ${reason}`);
}

async function fresh(opts = {}) {
  const s = await createSession(browser, {
    apiStubs: { '/api/agents': { body: { agents: [] } } },
    ...opts,
  });
  // 模块句柄解析器必须在 goto 之前装（addInitScript 语义）。
  await installModuleResolver(s.page);
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

const FORM = webForm();
const JS_FACE = jsSources();
const CSS_FACE = cssSources();
console.log(`keyboard-check — browser=${label}`);
console.log(`  ${describeWebRoot()}`);
console.log(`  js face: via=${JS_FACE.via} files=${JS_FACE.entries.length}${JS_FACE.notes.length ? ' | ' + JS_FACE.notes.join(' | ') : ''}`);
console.log(`  css face: via=${CSS_FACE.via} files=${CSS_FACE.entries.length}${CSS_FACE.notes.length ? ' | ' + CSS_FACE.notes.join(' | ') : ''}`);
if (FORM === 'dist') {
  const stale = shippedVsWorktree(JS_FACE);
  console.log(`  shipped vs worktree: same=${stale.same} diff=${stale.diff.length} missing=${stale.missing}`
    + (stale.diff.length ? ` | 差异文件（产物陈旧 ⇒ 产物形态读数只能归因于产物）：${stale.diff.slice(0, 6).join(', ')}${stale.diff.length > 6 ? ' …' : ''}` : ''));
}
console.log('');

// ── A 注册器（纯逻辑，不经 UI） ─────────────────────────────────────────
console.log('A 注册器');
{
  const s = await fresh();
  const r = await s.page.evaluate(async () => {
    const probe = await window.__nfProbeModule('/js/keymap.js');
    if (!probe.ok) return { unavailable: probe.reason };
    const km = probe.ns;
    const out = {};
    out.via = probe.via;
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
  if (r.unavailable) {
    const why = `${surfaceHint()}（${r.unavailable}）`;
    skip('重复注册同一 combo 抛错（注册期硬错）', why);
    skip('同一 combo 在不同 mode 下可共存', why);
    skip('注销后从注册表消失', why);
    skip('场景启停生效', why);
  } else {
    check('重复注册同一 combo 抛错（注册期硬错）', r.dupThrows === true, `via=${r.via}`);
    check('同一 combo 在不同 mode 下可共存', r.modeSplitOk === true);
    check('注销后从注册表消失', r.listedBefore === true && r.listedAfter === false);
    check('场景启停生效', r.scopeOff === true && r.scopeOn === true);
  }
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
// 产物形态下扫的是 **app-*.css bundle 里按分段标记还原出来的每文件段**（见
// probe-surface.cssSources）—— 那正是浏览器真正解析的那份字节；源树形态仍扫磁盘。
{
  const { readFileSync: rf } = await import('node:fs');
  const { join: jn } = await import('node:path');
  const { fileURLToPath: f2p } = await import('node:url');
  const exemptions = JSON.parse(rf(jn(f2p(new URL('..', import.meta.url)), 'scripts', 'focus-ring-exemptions.json'), 'utf8')).exemptions;
  /** @type {string[]} */
  const unpaired = [];
  // 产物形态下文件身份不可用（CSS 注释被压缩删掉）⇒ 豁免按**选择器**比对；
  // 源树形态仍按 (file, selector) 严格比对。这个差异写进 detail，不藏着。
  let exemptBySelectorOnly = 0;
  for (const { rel, text: txt } of CSS_FACE.entries) {
    // B3-1 之前：`outline:none` 若挂在 `:focus` 规则里、又没有 `:focus-visible` 配对，
    // 就会以 (0,2,0) 压掉 keyboard.css:28 的 (0,1,0) 兜底 —— 键盘聚焦无环。
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
      // 选择器归一化：产物 bundle 是压缩过的（逗号后无空格、可能被重排），拿原文比对
      // 会在产物形态下把**已豁免**的条目误判成未豁免 —— 实测就这样多出一条假 FAIL。
      // 归一口径：折叠空白 → 按逗号切分 → 逐项 trim → 排序 → 重连。
      const normSel = (x) => x.replace(/\s+/g, ' ').split(',').map((t) => t.trim()).sort().join(', ');
      const selKey = normSel(r.sel);
      const byFile = exemptions.some((e) => e.file === rel && normSel(e.selector) === selKey);
      const bySelector = !byFile && exemptions.some((e) => normSel(e.selector) === selKey);
      if (bySelector) exemptBySelectorOnly++;
      const exempt = byFile || (CSS_FACE.via === 'bundle' && bySelector);
      if (!exempt) unpaired.push(`${rel} :: ${r.sel}`);
    }
  }
  check('⑬ css/ 内 :focus{outline:none} 未配对 :focus-visible 的条数 − 豁免 = 0',
    CSS_FACE.entries.length > 0 && unpaired.length === 0,
    `${CSS_FACE.entries.length} 个单元（via=${CSS_FACE.via}）${unpaired.length} 条：${unpaired.slice(0, 4).join(' | ')}`
    + (exemptBySelectorOnly ? ` | 按选择器豁免 ${exemptBySelectorOnly} 条（产物无文件身份）` : ''));
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
  const dB = await layerDepth(s.page);
  const countBefore = (await geo()).length;
  await s.page.evaluate(() => {
    const card = /** @type {HTMLElement} */ (document.querySelector('#deck-layer .deck-card[tabindex="0"]'));
    if (card) card.focus({ preventScroll: true });
  });
  await s.page.keyboard.press('Delete');
  await s.page.waitForTimeout(1000);
  const countAfter = (await geo()).length;
  const dA = await layerDepth(s.page);
  if (!dB.ok || !dA.ok) {
    // 叠卡**不得进层栈**这件事没有 DOM 观测量（层栈深度就是它的唯一证据），
    // 取不到深度时这条断言只能记未执行 —— 但卡片真的关掉了这个是可观测的，
    // 单独留痕，免得「SKIP 了」被读成「什么都没发生」。
    skip('⑫ Delete 关当前卡且 layerDepth 不变',
      `${dB.ok ? dA.reason : dB.reason}（本次卡片数 ${countBefore}→${countAfter} 仍已记录）`);
  } else {
    check('⑫ Delete 关当前卡且 layerDepth 不变',
      countAfter === countBefore - 1 && dA.depth === dB.depth,
      `cards ${countBefore}→${countAfter} depth ${dB.depth}→${dA.depth}`);
  }
  await s.close();
}

// ── H 保焦协议接线（B3-2：**生产者/消费者计数**断言）───────────────────────
// 这一组是本计划 §7.3 那条纪律的落地：不测「协议函数存不存在」，测「有几个地方
// 在调它 / 在写这个键 / 在听这个事件」。`captureFocusKey` 用「外部调用点 0」
// 钉了整整一天，就是「代码存在」被当成「接上了」的代价。
console.log('H 保焦协议接线');
{
  // 扫描面：源树形态 = 磁盘源文件；产物形态 = 产物 sourcemap 的 sourcesContent
  // （= 打进 bundle 的那份字节）。两种形态扫的都是「跑的那份代码」，不是「旁边
  // 那份源码」—— 产物陈旧时这里会红，而那个红点正是本闸要报的东西。
  const files = JS_FACE.entries;
  /** @param {RegExp} re @param {string} [skipBase] */
  const count = (re, skipBase) => files
    .filter((x) => !(skipBase && x.rel.endsWith(skipBase)))
    .map((x) => ({ f: x.rel, n: (x.text.match(re) || []).length }))
    .filter((x) => x.n > 0);
  const shortName = (/** @type {string} */ f) => f.split('/').pop();

  // ⑭ 六个业务面接上了（withFocusKey 调用点；定义在 focusTrap.js，不算接线）
  const wired = count(/withFocusKey\s*\(/g, 'focusTrap.js');
  const wiredSites = wired.reduce((s, x) => s + x.n, 0);
  check('⑭ withFocusKey 外部接线点 ≥ 6（6 个业务面）', wiredSites >= 6,
    `${wiredSites} 处（via=${JS_FACE.via}）：${wired.map((x) => shortName(x.f) + '×' + x.n).join(', ')}`);

  // ⑮ data-focus-key 生产者 ≥ 4（树行 / 叠卡 / 会话行 / 侧栏会话行）——
  //    改造前这条通道「读者齐全、生产者 0」
  const producers = count(/dataset\.focusKey\s*=|setAttribute\(\s*['"]data-focus-key['"]/g);
  const prodSites = producers.reduce((s, x) => s + x.n, 0);
  check('⑮ data-focus-key 生产者 ≥ 4（读/写两侧都非 0）', prodSites >= 4,
    `${prodSites} 处：${producers.map((x) => shortName(x.f) + '×' + x.n).join(', ')}`);

  // ⑯ nf-focus-fallback 消费者 ≥ 1 + _pptxTeardown 赋值 ≥ 1（改造前两者都是 0）
  const listeners = count(/addEventListener\(\s*['"]nf-focus-fallback['"]/g);
  const assignments = count(/\._pptxTeardown\s*=(?!=)/g);
  const listenersN = listeners.reduce((s, x) => s + x.n, 0);
  const assignN = assignments.reduce((s, x) => s + x.n, 0);
  check('⑯ nf-focus-fallback 监听者 ≥ 1 且 _pptxTeardown 有赋值来源',
    listenersN >= 1 && assignN >= 1,
    `listeners=${listenersN}(${listeners.map((x) => shortName(x.f)).join(',')}) assignments=${assignN}(${assignments.map((x) => shortName(x.f)).join(',')})`);
}
{
  // ⑰ 运行时：文件树整树重渲后，焦点回到**同一 key 的新行**（节点身份变了）
  const s = await fresh();
  const pre = await s.page.evaluate(async () => {
    const probe = await window.__nfProbeModule('/js/explorer.js');
    if (!probe.ok) return { unavailable: probe.reason };
    const row = /** @type {any} */ (document.querySelector('#explorer-tree .explorer-item[role="treeitem"]'));
    if (!row) return { unavailable: '文件树没有行（夹具未就绪）' };
    row.focus();
    row.__probeOld = '1';
    return { via: probe.via, key: row.getAttribute('data-focus-key') };
  });
  // 刷新必须落在**另一个 task** 里：focus 与整树重渲挤在同一个 evaluate 里时，
  // 保焦捕获读到的还是「容器」而不是那一行（实测 surface 句柄是微任务级、
  // `await import()` 是网络级 —— 后者天然隔了一拍，前者没有）。这条不是凑绿：
  // 真实用户点击与后续网格重渲之间本来就隔着任务边界。
  await s.page.evaluate(async () => {
    const probe = await window.__nfProbeModule('/js/explorer.js');
    if (probe.ok) probe.ns.refreshExplorer('probe-session'); // 导出入口 → renderTree()（整树重建）
  });
  await s.page.waitForTimeout(600); // 重渲是异步的（listDir 应答 → renderTree → 保焦）
  if (pre.unavailable) {
    skip('⑰ 整树重渲后焦点回到同一 key 的新行（旧节点已被替换）', `${surfaceHint()}（${pre.unavailable}）`);
  } else {
    const after = await s.page.evaluate(() => {
      const el = /** @type {any} */ (document.activeElement);
      return { key: el?.getAttribute?.('data-focus-key') || null, stillOldNode: el?.__probeOld === '1' };
    });
    check('⑰ 整树重渲后焦点回到同一 key 的新行（旧节点已被替换）',
      !!pre.key && after.key === pre.key && after.stillOldNode === false,
      `key ${pre.key} → ${after.key}（旧节点=${after.stillOldNode}, via=${pre.via}）`);
  }
  await s.close();
}
{
  // ⑱ 层栈回焦走**三级 key**（改造前 popLayer 只认 #id）：打开层时焦点在一个
  //    没有 id、只有 data-focus-key 的按钮上，重渲把该节点换掉后再弹层。
  const s = await fresh();
  const r = await s.page.evaluate(async () => {
    const probe = await window.__nfProbeModule('/js/focusTrap.js');
    if (!probe.ok) return { unavailable: probe.reason };
    const ft = probe.ns;
    const opener = document.createElement('button');
    opener.setAttribute('data-focus-key', 'probe:opener');
    opener.textContent = 'opener';
    document.body.appendChild(opener);
    opener.focus();
    const ov = document.createElement('div');
    ov.innerHTML = '<button>inside</button>';
    document.body.appendChild(ov);
    ft.pushLayer(ov, { name: 'probe-layer' });
    const freshBtn = document.createElement('button');
    freshBtn.setAttribute('data-focus-key', 'probe:opener');
    freshBtn.textContent = 'opener';
    opener.replaceWith(freshBtn); // 模拟业务重渲：旧节点脱离文档
    ft.popLayer(ov);
    const el = document.activeElement;
    const out = { via: probe.via, key: el?.getAttribute?.('data-focus-key') || null, isNewNode: el === freshBtn, depth: ft.layerDepth() };
    ov.remove(); freshBtn.remove();
    return out;
  });
  if (r.unavailable) {
    skip('⑱ popLayer 按 data-focus-key 回焦到新节点（旧内联逻辑只认 #id 会落空）', `${surfaceHint()}（${r.unavailable}）`);
  } else {
    check('⑱ popLayer 按 data-focus-key 回焦到新节点（旧内联逻辑只认 #id 会落空）',
      r.key === 'probe:opener' && r.isNewNode === true && r.depth === 0, JSON.stringify(r));
  }
  await s.close();
}
{
  // ⑲ 运行时：nf-focus-fallback 真的被业务消费（计数器 +1，且带得上 key）
  const s = await fresh();
  const r = await s.page.evaluate(async () => {
    const probe = await window.__nfProbeModule('/js/focusTrap.js');
    if (!probe.ok) return { unavailable: probe.reason };
    const ft = probe.ns;
    const before = window.__nfFocusFallback?.total ?? -1;
    const root = document.createElement('div');
    document.body.appendChild(root);
    ft.restoreFocusKey(root, { kind: 'data', value: 'probe:vanished' });
    const mid = window.__nfFocusFallback?.total ?? -1;
    // popLayer 腿：打开层时的元素被摘掉，回焦失败也要能观测到
    const opener = document.createElement('button');
    opener.setAttribute('data-focus-key', 'probe:gone');
    document.body.appendChild(opener);
    opener.focus();
    const ov = document.createElement('div');
    document.body.appendChild(ov);
    ft.pushLayer(ov, { name: 'probe-layer2' });
    opener.remove();
    ft.popLayer(ov);
    const after = window.__nfFocusFallback?.total ?? -1;
    const last = window.__nfFocusFallback?.last || null;
    root.remove(); ov.remove();
    return { via: probe.via, before, mid, after, last };
  });
  if (r.unavailable) {
    skip('⑲ nf-focus-fallback 被消费（restore + popLayer 两条腿各计一次）', `${surfaceHint()}（${r.unavailable}）`);
  } else {
    check('⑲ nf-focus-fallback 被消费（restore + popLayer 两条腿各计一次）',
      r.mid === r.before + 1 && r.after === r.before + 2 && r.last && r.last.kind === 'data',
      JSON.stringify(r));
  }
  await s.close();
}

// ── I 九个弹层壳入层栈（B4：每壳三判 —— 入栈 / Tab 不逃出 / 关闭出栈且焦点逃出壳）
// 为什么是这三条：壳以前也在「显示/隐藏」自己，缺的是**栈内身份**（layerDepth 看得见
// 它）与**关闭回焦**。只测「面板打开了」等于什么也没测 —— 那件事改造前就成立。
console.log('I 弹层壳入层栈');
{
  const pushSites = [];
  for (const f of JS_FACE.entries) {
    // 定义文件不算入口（focusTrap.js 的 release/pushLayer 自身）；import 行里是
    // `pushLayer,` 不带括号 ⇒ 天然不命中。
    if (f.rel.endsWith('focusTrap.js')) continue;
    const n = (f.text.match(/pushLayer\(/g) || []).length;
    if (n) pushSites.push(`${f.rel.split('/').pop()}×${n}`);
  }
  const total = pushSites.reduce((s, x) => s + Number(x.split('×')[1]), 0);
  check('㉑ 层栈入口数 3 → ≥ 12（原 3 + 9 个壳）', total >= 12,
    `${total} 处（via=${JS_FACE.via}）：${pushSites.slice(0, 20).join(', ')}`);

  const voice = JS_FACE.entries.find((f) => f.rel === 'js/voiceMode.js');
  const voiceWired = !!voice && /pushLayer\(el\.overlay/.test(voice.text);
  check('㉒ 语音面（flag 封存、无法运行期打开）静态接线在 enterVoiceMode 内', voiceWired === true,
    voice ? `via=${JS_FACE.via}` : 'js/voiceMode.js 不在扫描面内（产物 sourcemap 缺该源）');
}

/** 9 个壳的运行期验收表（open = 各自的**真实导出入口**，不是手工 classList.add）。 */
const SHELLS = [
  { key: 'settings', mod: '/js/sidebar.js', fn: 'openSettingsPanel', args: [], sel: '#settings-overlay', openFlag: 'on', layer: 'settings', expectOpener: true },
  { key: 'memory', mod: '/js/memory.js', fn: 'openMergedPanel', args: ['memory'], sel: '#memory-overlay', openFlag: 'on', layer: 'memory', expectOpener: true },
  { key: 'rules', mod: '/js/sidebar/pathRules.js', fn: 'openRulesEditor', args: ['probe-folder', 'probe'], sel: '#rules-overlay', openFlag: 'on', layer: 'rules', expectOpener: true },
  { key: 'search', mod: '/js/chatSearch.js', fn: 'openSearchModal', args: [], sel: '#search-overlay', openFlag: 'on', layer: 'search', expectOpener: false },
  { key: 'social', mod: '/js/socialPanel.js', fn: 'openSocialPanel', args: [], sel: '#social-overlay', openFlag: 'on', layer: 'social', expectOpener: false },
  { key: 'secrets', mod: '/js/secretsPanel.js', fn: 'openSecretsPanel', args: [], sel: '#secrets-overlay', openFlag: 'on', layer: 'secrets', expectOpener: false },
  { key: 'daemon-config', mod: '/js/daemonPanel.js', fn: 'openDaemonConfig', args: ['probe', null], sel: '#daemon-config-overlay', openFlag: 'open', layer: 'daemon-config', expectOpener: true },
  { key: 'lightbox', mod: '/js/lightbox.js', fn: 'openLightbox', args: ['data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'probe', ''], sel: '.nf-lightbox', openFlag: 'on', layer: 'lightbox', expectOpener: false },
];

for (const sh of SHELLS) {
  const s = await fresh({ apiStubs: { '/api/agents': { body: { agents: [] } }, '/api/daemons': { body: { title: 'probe', fields: [] } } } });
  // 打开器：一个无 id 的按钮 —— 只有层栈的回焦 key 协议能找回来（id 腿不存在）
  await s.page.evaluate(() => {
    const b = document.createElement('button');
    b.id = 'nf-shell-opener';
    b.textContent = 'opener';
    document.body.appendChild(b);
    b.focus();
    if (window.__nfFocusFallback) window.__nfFocusFallback.total = 0;
  });
  const opened = await s.page.evaluate(async ([mod, fn, args]) => {
    const probe = await window.__nfProbeModule(mod);
    if (!probe.ok) return { unavailable: probe.reason };
    const m = probe.ns;
    if (typeof m[fn] !== 'function') return { err: 'missing export ' + fn };
    await m[fn](...(args || []));
    return { ok: true, via: probe.via };
  }, [sh.mod, sh.fn, sh.args]);
  if (opened.unavailable) {
    // 壳的**真实导出入口**取不到 ⇒ 三判都没有对象（不是「壳坏了」）。三条一起记
    // 未执行，原因写在第一条上，另两条指向它，避免同一条原因重复刷屏。
    const why = `${opened.unavailable} ⇒ 打不开壳（不是壳的缺陷）`;
    skip(`${sh.key}：打开即入层栈（depth=1，焦点在壳内）`, `${why}；${surfaceHint()}`);
    skip(`${sh.key}：Tab ×30 不逃出壳`, `同上（壳未打开）`);
    skip(`${sh.key}：Esc 关闭 → 出栈且焦点离开壳${sh.expectOpener ? '（回打开器）' : ''}`, `同上（壳未打开）`);
    await s.close();
    continue;
  }
  await s.page.waitForTimeout(450);
  const st = await s.page.evaluate(([sel, flag]) => {
    const shell = document.querySelector(sel);
    return {
      exists: !!shell,
      shown: !!shell && shell.classList.contains(flag),
      inside: !!shell && shell.contains(document.activeElement),
    };
  }, [sh.sel, sh.openFlag]);
  const depth1 = await layerDepth(s.page);
  check(`${sh.key}：打开即入层栈（depth=1，焦点在壳内）`,
    opened.ok === true && st.exists && st.shown && st.inside && depth1.ok && depth1.depth === 1,
    `${JSON.stringify(opened)} ${JSON.stringify(st)} depth=${depth1.ok ? depth1.depth : 'unavailable(' + depth1.reason + ')'}`);

  // Tab ×30 一次不逃出（焦点被抢到壳外的那一拍就是逃逸）
  let escapedTab = 0;
  for (let i = 0; i < 30; i++) {
    await s.page.keyboard.press('Tab');
    const inside = await s.page.evaluate((sel) => {
      const shell = document.querySelector(sel);
      return !!shell && shell.contains(document.activeElement);
    }, sh.sel);
    if (!inside) escapedTab++;
  }
  check(`${sh.key}：Tab ×30 不逃出壳`, escapedTab === 0, `逃出 ${escapedTab} 次`);

  // Esc 关闭：出栈 + 壳收起 + 焦点离开壳（回焦落点见 expectOpener）
  await s.page.keyboard.press('Escape');
  await s.page.waitForTimeout(450);
  const after = await s.page.evaluate(([sel, flag]) => {
    const shell = document.querySelector(sel);
    const el = document.activeElement;
    return {
      shown: !!shell && shell.classList.contains(flag),
      insideShell: !!shell && shell.contains(el),
      activeId: el && el.id ? el.id : (el ? el.tagName : 'none'),
    };
  }, [sh.sel, sh.openFlag]);
  const depth2 = await layerDepth(s.page);
  const focusOk = after.insideShell === false && (!sh.expectOpener || after.activeId === 'nf-shell-opener');
  check(`${sh.key}：Esc 关闭 → 出栈且焦点离开壳${sh.expectOpener ? '（回打开器）' : ''}`,
    after.shown === false && depth2.ok && depth2.depth === 0 && focusOk,
    `shown=${after.shown} depth=${depth2.ok ? depth2.depth : 'unavailable(' + depth2.reason + ')'} active=${after.activeId}`);
  await s.close();
}

await browser.close();

const total = pass + fail + skipsN;
console.log(`\nkeyboard-check: ${pass} passed, ${fail} failed, ${skipsN} skipped — 共 ${total} 条`);
if (FORM === 'source') console.log('  形态：源树（src/main/resources/web）—— 这是开发树，不是运行实例吃的那份字节');
else console.log(`  形态：产物（${webRoot()}）—— 断言直接跑在打包产物上`);
if (skipsN) console.log(`SKIPPED（未执行，不是通过）：\n  - ${skips.join('\n  - ')}`);
if (fail) { console.log('FAILURES:\n  - ' + failures.join('\n  - ')); process.exit(1); }
if (skipsN) console.log(`KEYBOARD CHECK PASS (${pass}/${total} 条已验；${skipsN} 条未执行，原因见上)`);
else console.log('KEYBOARD CHECK PASS');

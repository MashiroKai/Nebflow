// ime-guard.spec.mjs — ⑤ 中文输入（IME）收归验收（作者裁定 2026-09-12，方案
// §4.1 步骤级规格 / §5.5 判据 A1–A7）。
//
// 断言面（每一面都成对：组字期间**不**触发动作 + 组字结束后 Enter **正常**触发，
// 后者是前者「不是因为它本来就坏的」反证）：
//   U    imeGuard 单元语义：isImeComposing 三臂真值表 / commitEnter 语义（裸
//        Enter ⇒ true 且已 preventDefault；Shift+Enter / 组字态 ⇒ false 且不动
//        事件）/ bindImeGuard 幂等 + `dataset.imeComposing` 写入/延迟清零
//   A1   主对话框组字 Enter：未发送 + defaultPrevented === false（⑤A1）
//   A2   组字结束后**紧邻下一个任务**的 Enter 立即发送（⑤A2：延迟清零不得吞键）
//   A2c  与 compositionend **同一执行块**的 Enter 仍视为该次按键的尾随事件（不发送）
//        —— 这正是「确认候选 ≠ 发送」的保护；真实第二次按键必然是新任务，见 A2/A2b
//   A2b  同上、零延迟宏任务边界（sleep(0) 后立即 Enter）
//   A3   好友会话输入框（作者点名的面）组字 Enter 不触发 doSend()；组字后触发（⑤A3）
//   A4a  联系人搜索框组字 Enter 不提交；组字后提交出结果卡（⑤A4）
//   A4b  验证附言组字 Enter 不直发；组字后直发（⑤A4）
//   A5   组字期 ↑ 不下沉历史；组字后 ↑ 正常下沉（⑤A5）
//   A6   斜杠下拉 on + 组字 Enter 不选命令；组字后 Enter 选中（⑤A6）
//   A7   非组字全路径不变：Shift+Enter 换行不发送 / Esc 关下拉（⑤A7）
//   G1   「单一写入点」静态证明：`dataset.imeComposing` 全树只出现在 imeGuard.js
//   G4   哨兵 `scripts/check-ime-guard.mjs` 自身语义（F-1 修复回归网）：出现点级判定、
//        允许清单锚定文本（不得掩盖其替代者）、注释提及的边界处置——全部在 /tmp 夹具
//        副本上变异，仓库业务文件零改动
//
// 隔离：自包含——静态文件按 route 直接读盘（无静态服务器、无端口）、WS =
// routeWebSocket mock、好友 REST 面走 `fm_api_mock` seed。不碰宿主 8080 /
// 宿主进程；跑完 browser.close()（无残留进程）。
// Run: node tests/ime-guard.spec.mjs
import { chromium } from 'playwright-core';
import { execFileSync } from 'node:child_process';
import { cpSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
let SCRIPT_SRC = '';
try { SCRIPT_SRC = readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', 'scripts', 'check-ime-guard.mjs'), 'utf8'); }
catch { SCRIPT_SRC = ''; }
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

const FRIEND = { userId: 'u-f', neblinkId: 'buddy7', name: '老友', avatarUrl: '' };
const CID = 'c-f';
const SEED = {
  self: { userId: 'me', neblinkId: 'me@example.com', name: 'Me', avatarUrl: '' },
  users: [{ userId: 'u-x', username: 'xiaoming', email: 'xm@example.com', displayName: '小明', avatar: '' }],
  friends: [{ ...FRIEND, since: new Date().toISOString() }],
  incoming: [],
  outgoing: [],
  conversations: [{ conversationId: CID, friend: FRIEND, lastMessage: null, unreadCount: 0 }],
  messages: { [CID]: [] },
};

let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));

const browser = await chromium.launch();

/** boot — 自包含页面（静态文件读盘 + WS mock + 好友 mock seed）。 */
async function boot() {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  await ctx.addInitScript(([seedJson]) => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
    // key('slash.enabled') = `<lowerName>_<suffix>`（branding.js key()）——斜杠门是
    // 前缀下划线形态，不是点号形态（首版写成点号 ⇒ 下拉恒不开、A6 假红）。
    localStorage.setItem('nebflow_slash.enabled', '1'); // 斜杠下拉可用（⑤A6 面）
    localStorage.setItem('fm_api_mock', '1');
    localStorage.setItem('fm_api_mock_seed', seedJson);
    localStorage.setItem('fm_api_mock_delay', '20');
  }, [JSON.stringify(SEED)]);
  const page = await ctx.newPage();
  const pageErrors = [];
  page.on('pageerror', e => pageErrors.push(e.message));
  // catch-all first — 后注册的 route 优先（Playwright 逆序匹配）
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(normalize(WEB))) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await page.route('**/api/neblink/status', r => r.fulfill({
    json: { loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '' }, peers: [] },
  }));
  const frames = []; // client → server WS 帧（主对话框发送 = 无 type + content 字段）
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => { try { frames.push(JSON.parse(raw)); } catch { /* non-JSON */ } });
    ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 's1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 's1', folders: [] }));
  });
  await page.goto('http://localhost:1/index.html');
  await page.waitForSelector('#input', { state: 'attached', timeout: 15000 });
  await sleep(800);
  return { ctx, page, pageErrors, frames };
}

// ── synthetic IME 事件（真实 CompositionEvent / KeyboardEvent；isComposing 可置）──
const IMESTART = (sel) => `(() => {
  const el = document.querySelector(${JSON.stringify(sel)});
  el.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true }));
  return el.dataset.imeComposing || null;
})()`;
const IMEEND = (sel) => `(() => {
  const el = document.querySelector(${JSON.stringify(sel)});
  el.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true }));
  return el.dataset.imeComposing || null;
})()`;
const KEY = (sel, key, composing = false, shift = false) => `(() => {
  const el = document.querySelector(${JSON.stringify(sel)});
  const ev = new KeyboardEvent('keydown', { key: ${JSON.stringify(key)}, isComposing: ${composing}, shiftKey: ${shift}, bubbles: true, cancelable: true });
  el.dispatchEvent(ev);
  return { prevented: ev.defaultPrevented, value: el.value };
})()`;

/** 组字 Enter：compositionstart + 组字态 Enter —— 返回 {flag, prevented, value}。 */
async function composingEnter(page, sel) {
  const flag = await page.evaluate(IMESTART(sel));
  const res = await page.evaluate(KEY(sel, 'Enter', true));
  return { flag, ...res };
}

/** 组字收尾 + 相邻任务 Enter —— 返回 {stillFlagged, ...}（A2 判定用）。 */
async function endThenEnter(page, sel, gapMs = 0) {
  const sameTask = await page.evaluate(IMEEND(sel));
  if (gapMs > 0) await sleep(gapMs);
  else await sleep(0);
  const res = await page.evaluate(KEY(sel, 'Enter'));
  return { sameTask, ...res };
}

try {
  // ══ U · imeGuard 单元语义 ══════════════════════════════════════
  {
    const { ctx, page, pageErrors } = await boot();
    const u = await page.evaluate(async () => {
      const g = await import('/js/imeGuard.js');
      const el = document.createElement('input');
      document.body.appendChild(el);
      g.bindImeGuard(el);
      g.bindImeGuard(el); // 幂等：不得重复绑定 / 不得清掉既有状态
      const idempotent = el.__imeGuardBound === true;
      const before = el.dataset.imeComposing || null;
      el.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true }));
      const during = el.dataset.imeComposing || null;
      // 三臂真值表（任一臂为真 ⇒ 组字中）
      const armEvent = g.isImeComposing(new KeyboardEvent('keydown', { key: 'Enter', isComposing: true }), null);
      const armKeyCode = g.isImeComposing({ keyCode: 229 }, null);
      const armDataset = g.isImeComposing(new KeyboardEvent('keydown', { key: 'Enter' }), el);
      const armNone = g.isImeComposing(new KeyboardEvent('keydown', { key: 'Enter' }), document.createElement('input'));
      // 组字态 commitEnter 不得动事件
      const composingEv = new KeyboardEvent('keydown', { key: 'Enter', isComposing: true, cancelable: true });
      const composingCommit = g.commitEnter(composingEv, el);
      const composingPrevented = composingEv.defaultPrevented;
      el.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true }));
      const sameTask = el.dataset.imeComposing || null;
      await new Promise(r => setTimeout(r, 0));
      const nextTask = el.dataset.imeComposing || null;
      const bare = new KeyboardEvent('keydown', { key: 'Enter', cancelable: true });
      const bareCommit = g.commitEnter(bare, el);
      const shiftEv = new KeyboardEvent('keydown', { key: 'Enter', shiftKey: true, cancelable: true });
      const shiftCommit = g.commitEnter(shiftEv, el);
      return {
        idempotent, before, during, armEvent, armKeyCode, armDataset, armNone, composingCommit, composingPrevented,
        sameTask, nextTask, bareCommit, barePrevented: bare.defaultPrevented, shiftCommit, shiftPrevented: shiftEv.defaultPrevented,
      };
    });
    ok('U1 bindImeGuard 幂等 + 未组字时无标志',
      u.idempotent && u.before === null, JSON.stringify({ idempotent: u.idempotent, before: u.before }));
    ok('U2 compositionstart ⇒ dataset.imeComposing = "1"', u.during === '1', `during=${u.during}`);
    ok('U3 isImeComposing 三臂真值表（isComposing / keyCode229 / dataset）+ 全无 ⇒ false',
      u.armEvent && u.armKeyCode && u.armDataset && u.armNone === false,
      JSON.stringify({ armEvent: u.armEvent, armKeyCode: u.armKeyCode, armDataset: u.armDataset, armNone: u.armNone }));
    ok('U4 延迟清零：compositionend 同执行块仍置位、下一任务已清（尾随保护 + 不吞后续键）',
      u.sameTask === '1' && u.nextTask === null, JSON.stringify({ sameTask: u.sameTask, nextTask: u.nextTask }));
    ok('U5 commitEnter：组字态 ⇒ false 且不 preventDefault',
      u.composingCommit === false && u.composingPrevented === false,
      JSON.stringify({ commit: u.composingCommit, prevented: u.composingPrevented }));
    ok('U6 commitEnter：裸 Enter ⇒ true 且已 preventDefault；Shift+Enter ⇒ false 且不动事件',
      u.bareCommit === true && u.barePrevented === true && u.shiftCommit === false && u.shiftPrevented === false,
      JSON.stringify({ bareCommit: u.bareCommit, barePrevented: u.barePrevented, shiftCommit: u.shiftCommit, shiftPrevented: u.shiftPrevented }));
    ok('U7 零 page error', pageErrors.length === 0, pageErrors.join('; '));
    await ctx.close();
  }

  // ══ A1/A2/A2b/A2c/A5/A6/A7 · 主对话框（#input）═════════════════
  {
    const { ctx, page, pageErrors, frames } = await boot();
    const sentWith = (text) => frames.filter(m => m.content === text).length;

    // ── A1 组字期 Enter 不发送 ──
    await page.fill('#input', '组字A1');
    const a1 = await composingEnter(page, '#input');
    await sleep(150);
    ok('A1 主对话框组字 Enter：未发送 + defaultPrevented=false + 文本未清',
      a1.flag === '1' && a1.prevented === false && a1.value === '组字A1' && sentWith('组字A1') === 0,
      JSON.stringify({ flag: a1.flag, prevented: a1.prevented, value: a1.value, frames: sentWith('组字A1') }));

    // ── A2c 尾随事件：与 compositionend 同一执行块 ⇒ 仍不发送 ──
    const a2c = await page.evaluate(`(() => {
      const el = document.getElementById('input');
      el.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true }));
      const ev = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true });
      el.dispatchEvent(ev);
      return { prevented: ev.defaultPrevented, value: el.value, flag: el.dataset.imeComposing || null };
    })()`);
    await sleep(150);
    ok('A2c 同执行块 Enter = 该次按键的尾随事件（不发送）：确认候选 ≠ 发送',
      a2c.flag === '1' && a2c.prevented === false && sentWith('组字A1') === 0,
      JSON.stringify({ ...a2c, frames: sentWith('组字A1') }));

    // ── A2b 延迟清零后、紧邻下一任务 Enter 立即发送（同 tick 边界）──
    const a2b = await page.evaluate(`(async () => {
      const el = document.getElementById('input');
      el.value = '组字A1';
      el.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true }));
      await new Promise(r => setTimeout(r, 0));   // 零延迟宏任务边界
      const ev = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true });
      el.dispatchEvent(ev);
      return { prevented: ev.defaultPrevented, value: el.value, flag: el.dataset.imeComposing || null };
    })()`);
    await sleep(250);
    ok('A2b 组字结束后零延迟下一任务 Enter ⇒ 立即发送（未被延迟清零吞掉）',
      a2b.prevented === true && a2b.value === '' && sentWith('组字A1') === 1,
      JSON.stringify({ ...a2b, frames: sentWith('组字A1') }));

    // ── A2 正常时序：compositionend → 相邻任务 Enter ⇒ 发送 ──
    // 前置：A2b 的发送把会话置为 busy（send() 尾部 setBusy；mock WS 不发终止帧 ⇒
    // busy 不自清），busy 会话的 Enter 走本地队列（值会清但无 WS 帧）——这里显式
    // 模拟「turn 结束」清掉 busy，再等过 300ms `isSending` 去抖窗口。
    await page.evaluate(async () => { (await import('/js/chat.js')).clearBusy('s1'); });
    await sleep(400);
    await page.fill('#input', '组字A2');
    await page.evaluate(IMESTART('#input'));
    const a2 = await endThenEnter(page, '#input');
    await sleep(250);
    ok('A2 组字结束后 Enter ⇒ 发送（值已清 + 帧已出）',
      a2.prevented === true && a2.value === '' && sentWith('组字A2') === 1,
      JSON.stringify({ ...a2, frames: sentWith('组字A2') }));

    // ── A5 组字期 ↑ 不下沉历史；组字后正常下沉 ──
    await page.evaluate(async () => {
      const cv = await import('/js/chatView.js');
      const st = (await import('/js/state.js')).default;
      st.inputHistory = ['历史消息X'];
      if (cv.activeView) cv.activeView.historyIndex = -1;
    });
    const a5a = await page.evaluate(`(() => {
      const el = document.getElementById('input');
      el.value = '正在组字';
      el.setSelectionRange(0, 0);
      el.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true }));
      const ev = new KeyboardEvent('keydown', { key: 'ArrowUp', isComposing: true, bubbles: true, cancelable: true });
      el.dispatchEvent(ev);
      return { prevented: ev.defaultPrevented, value: el.value };
    })()`);
    ok('A5a 组字期 ↑（候选词）：不下沉历史 + defaultPrevented=false',
      a5a.prevented === false && a5a.value === '正在组字', JSON.stringify(a5a));
    await page.evaluate(IMEEND('#input'));
    await sleep(0);
    const a5b = await page.evaluate(`(() => {
      const el = document.getElementById('input');
      el.setSelectionRange(0, 0);
      const ev = new KeyboardEvent('keydown', { key: 'ArrowUp', bubbles: true, cancelable: true });
      el.dispatchEvent(ev);
      return { prevented: ev.defaultPrevented, value: el.value };
    })()`);
    ok('A5b 非组字 ↑ ⇒ 历史正常下沉（对照：该路径本来是活的）',
      a5b.prevented === true && a5b.value === '历史消息X', JSON.stringify(a5b));

    // ── A6 斜杠下拉 + 组字 Enter 不选命令；组字后 Enter 选中 ──
    await page.fill('#input', '/');
    await sleep(120);
    const ddOn = await page.evaluate(() => document.getElementById('slash-dropdown').classList.contains('on'));
    const a6a = await composingEnter(page, '#input');
    const ddAfterComposing = await page.evaluate(() => ({
      on: document.getElementById('slash-dropdown').classList.contains('on'),
      items: document.querySelectorAll('#slash-dropdown .slash-item').length,
    }));
    await sleep(150);
    ok('A6a 斜杠下拉 on + 组字 Enter ⇒ 不选命令（下拉仍在、文本未清）',
      ddOn === true && a6a.prevented === false && a6a.value === '/' && ddAfterComposing.on === true && ddAfterComposing.items > 0,
      JSON.stringify({ ddOn, prevented: a6a.prevented, value: a6a.value, ddAfterComposing }));
    const a6b = await endThenEnter(page, '#input');
    const ddAfterEnd = await page.evaluate(() => ({
      on: document.getElementById('slash-dropdown').classList.contains('on'),
      value: /** @type {HTMLTextAreaElement} */ (document.getElementById('input')).value,
    }));
    ok('A6b 非组字 Enter ⇒ 选中命令（下拉关闭 + 文本已清）',
      a6b.prevented === true && ddAfterEnd.on === false && ddAfterEnd.value === '',
      JSON.stringify({ prevented: a6b.prevented, ddAfterEnd }));

    // ── A7a Shift+Enter 换行不发送（真键盘，非组字）──
    await page.click('#input');
    await page.evaluate(() => {
      const el = /** @type {HTMLTextAreaElement} */ (document.getElementById('input'));
      el.value = '换行A7';
      el.dispatchEvent(new Event('input', { bubbles: true }));
    });
    await page.keyboard.press('Shift+Enter');
    await sleep(150);
    const a7a = await page.evaluate(() => /** @type {HTMLTextAreaElement} */ (document.getElementById('input')).value);
    ok('A7a Shift+Enter ⇒ 换行、不发送', a7a.includes('\n') && sentWith('换行A7') === 0, JSON.stringify({ value: a7a, frames: sentWith('换行A7') }));

    ok('A1/A2/A5/A6/A7a 零 page error', pageErrors.length === 0, pageErrors.join('; '));
    await ctx.close();
  }

  // ══ A7b/A7c · 非组字全路径（独立页：A6b 选中的是 /ask ⇒ askMode 已开，
  //    它会让 Esc 先走「取消 ask 模式」（既有有序行为），故本例独立成页）══
  {
    const { ctx, page, pageErrors, frames } = await boot();
    const sentWith = (text) => frames.filter(m => m.content === text).length;

    // A7b Esc 关斜杠下拉（真键盘，非组字）
    await page.fill('#input', '/');
    await sleep(150);
    const onBefore = await page.evaluate(() => document.getElementById('slash-dropdown').classList.contains('on'));
    await page.keyboard.press('Escape');
    await sleep(150);
    const onAfter = await page.evaluate(() => document.getElementById('slash-dropdown').classList.contains('on'));
    ok('A7b 非组字 Esc ⇒ 关闭斜杠下拉（既有行为不变）', onBefore === true && onAfter === false,
      JSON.stringify({ onBefore, onAfter }));

    // A7c 非组字裸 Enter ⇒ 发送（真键盘）
    await page.click('#input');
    await page.evaluate(() => {
      const el = /** @type {HTMLTextAreaElement} */ (document.getElementById('input'));
      el.value = '裸EnterA7';
      el.dispatchEvent(new Event('input', { bubbles: true }));
    });
    await page.keyboard.press('Enter');
    await sleep(300);
    const left = await page.evaluate(() => /** @type {HTMLTextAreaElement} */ (document.getElementById('input')).value);
    ok('A7c 非组字裸 Enter ⇒ 发送（真键盘路径：值清 + 帧出）',
      left === '' && sentWith('裸EnterA7') === 1, JSON.stringify({ value: left, frames: sentWith('裸EnterA7') }));

    ok('A7b/A7c 零 page error', pageErrors.length === 0, pageErrors.join('; '));
    await ctx.close();
  }

  // ══ A3 · 好友会话输入框（作者点名的面）═════════════════════════
  {
    const { ctx, page, pageErrors } = await boot();
    await page.click('#messages-btn');
    await sleep(500);
    await page.click(`#fm-conversations .fm-conv-row[data-conversation-id="${CID}"]`);
    await page.waitForSelector('.fm-input', { timeout: 10000 });
    await sleep(300);

    await page.fill('.fm-input', '组字A3');
    const a3a = await composingEnter(page, '.fm-input');
    await sleep(200);
    const outAfterComposing = await page.locator('.fm-msg.out').count();
    ok('A3a 好友会话组字 Enter ⇒ 不触发 doSend()（无出向气泡 + 输入未清）',
      a3a.flag === '1' && a3a.prevented === false && a3a.value === '组字A3' && outAfterComposing === 0,
      JSON.stringify({ prevented: a3a.prevented, value: a3a.value, out: outAfterComposing }));

    const a3b = await endThenEnter(page, '.fm-input');
    await sleep(300);
    const outAfterEnd = await page.locator('.fm-msg.out', { hasText: '组字A3' }).count();
    ok('A3b 组字结束后 Enter ⇒ 正常发送（出向气泡出现 + 输入已清）',
      a3b.prevented === true && a3b.value === '' && outAfterEnd === 1,
      JSON.stringify({ prevented: a3b.prevented, value: a3b.value, out: outAfterEnd }));

    ok('A3 零 page error', pageErrors.length === 0, pageErrors.join('; '));
    await ctx.close();
  }

  // ══ A4a · 联系人搜索框 ═════════════════════════════════════════
  {
    const { ctx, page, pageErrors } = await boot();
    await page.click('#contacts-btn');
    await page.waitForSelector('.fm-search-input', { timeout: 10000 });
    await sleep(300);

    await page.fill('.fm-search-input', 'xiaoming');
    const a4a = await composingEnter(page, '.fm-search-input');
    await sleep(400);
    const cardsDuringComposing = await page.locator('.fm-result-card, .fm-searching').count();
    ok('A4a-1 搜索框组字 Enter ⇒ 不提交（无结果卡 / 无加载态 + 输入未清）',
      a4a.flag === '1' && a4a.prevented === false && a4a.value === 'xiaoming' && cardsDuringComposing === 0,
      JSON.stringify({ prevented: a4a.prevented, value: a4a.value, cards: cardsDuringComposing }));

    const a4b = await endThenEnter(page, '.fm-search-input');
    await sleep(600);
    const cardText = await page.$eval('.fm-result-card', e => e.textContent).catch(() => '');
    ok('A4a-2 组字结束后 Enter ⇒ 正常提交（出结果卡）',
      a4b.prevented === true && cardText.includes('小明'),
      JSON.stringify({ prevented: a4b.prevented, card: cardText.slice(0, 40) }));

    // ── A4b 验证附言 ──
    await page.click('.fm-result-card .fm-add-btn');
    await page.waitForSelector('.fm-verify-input', { timeout: 5000 });
    await page.fill('.fm-verify-input', '求通过');
    const a4c = await composingEnter(page, '.fm-verify-input');
    await sleep(400);
    const cardDuringComposing = await page.$eval('.fm-result-card', e => e.textContent).catch(() => '');
    const stillVerify = await page.locator('.fm-verify-box').count();
    ok('A4b-1 验证附言组字 Enter ⇒ 不直发（仍停在验证输入态 + 输入未清）',
      a4c.prevented === false && a4c.value === '求通过' && stillVerify === 1 && !cardDuringComposing.includes('等待对方处理'),
      JSON.stringify({ prevented: a4c.prevented, value: a4c.value, stillVerify, card: cardDuringComposing.slice(0, 40) }));

    const a4d = await endThenEnter(page, '.fm-verify-input');
    await sleep(600);
    const cardAfterEnd = await page.$eval('.fm-result-card', e => e.textContent).catch(() => '');
    ok('A4b-2 组字结束后 Enter ⇒ 请求直发（卡片转「等待对方处理」）',
      a4d.prevented === true && cardAfterEnd.includes('等待对方处理'),
      JSON.stringify({ prevented: a4d.prevented, card: cardAfterEnd.slice(0, 40) }));

    ok('A4 零 page error', pageErrors.length === 0, pageErrors.join('; '));
    await ctx.close();
  }

  // ══ G1 · 单一写入点静态证明 ════════════════════════════════════
  {
    const jsDir = join(WEB, 'js');
    const files = [];
    (function walk(d) {
      for (const e of readdirSync(d, { withFileTypes: true })) {
        const p = join(d, e.name);
        if (e.isDirectory()) walk(p);
        else if (e.name.endsWith('.js')) files.push(p);
      }
    })(jsDir);
    // 注释剥离后再判定（注释里提到标志名是正常的说明文字，不是写入点）。
    const stripComments = (s) => s.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^[ \t]*\/\/.*$/gm, '');
    const datasetWriters = [];
    const composingOccurrences = {};
    let chatViewDeprecated = false;
    for (const f of files) {
      const src = stripComments(readFileSync(f, 'utf8'));
      const rel = f.slice(WEB.length + 1).split('\\').join('/');
      if (rel !== 'js/imeGuard.js' && src.includes('imeComposing')) datasetWriters.push(rel);
      const all = (src.match(/view\.composing/g) || []).length;
      const writes = (src.match(/view\.composing\s*=[^=]/g) || []).length;
      if (all) composingOccurrences[rel] = { all, writes };
      if (rel === 'js/chatView.js' && /this\.composing\s*=/.test(src)) {
        chatViewDeprecated = readFileSync(f, 'utf8').includes('@deprecated');
      }
    }
    ok('G1 `dataset.imeComposing` 全树唯一写入/读取点 = js/imeGuard.js（⑤ 防再分叉）',
      datasetWriters.length === 0, JSON.stringify(datasetWriters));
    const readLeft = Object.entries(composingOccurrences).filter(([, v]) => v.all !== v.writes);
    ok('G2 视图级 `view.composing` 零读者（全部出现均为赋值；⑤-A4 收归）',
      readLeft.length === 0, JSON.stringify(composingOccurrences));
    ok('G3 ⑤-A4：`view.composing` 字段 + @deprecated 注释保留一版（不删）', chatViewDeprecated === true,
      `chatViewDeprecated=${chatViewDeprecated}`);
  }

  // ══ G4 · 哨兵自身语义（出现点级 + 允许清单锚定，F-1 修复回归网）══════
  // 装置：把 `scripts/check-ime-guard.mjs` **复制**进 /tmp 夹具树（脚本把 ROOT 解析为
  // 自身所在目录的父级，故副本 + web/js 副本即一个完整被测仓），只对副本做变异——
  // 仓库内任何业务文件零改动（本 spec 自身对真仓只读）。G4-2 即 F-1 最小反例。
  {
    const WT = join(dirname(fileURLToPath(import.meta.url)), '..');
    const fixtures = [];
    /** Build a fixture: scripts/<script> + src/main/resources/web/js copy. Returns its root. */
    const buildFixture = (mutate) => {
      const dir = mkdtempSync(join(tmpdir(), 'ime-guard-sentinel-'));
      fixtures.push(dir);
      mkdirSync(join(dir, 'scripts'), { recursive: true });
      mkdirSync(join(dir, 'src', 'main', 'resources', 'web'), { recursive: true });
      writeFileSync(join(dir, 'scripts', 'check-ime-guard.mjs'), SCRIPT_SRC);
      const jsDir = join(dir, 'src', 'main', 'resources', 'web', 'js');
      cpSync(join(WEB, 'js'), jsDir, { recursive: true });
      if (mutate) mutate(jsDir);
      return dir;
    };
    /** Run the sentinel inside a fixture. Returns {code, out}. */
    const runSentinel = (dir) => {
      try {
        return { code: 0, out: execFileSync(process.execPath, [join(dir, 'scripts', 'check-ime-guard.mjs')], { encoding: 'utf8', stdio: 'pipe' }) };
      } catch (e) {
        return { code: e.status, out: `${e.stdout || ''}${e.stderr || ''}` };
      }
    };
    const patchLines = (file, fn) => {
      const lines = readFileSync(file, 'utf8').split('\n');
      fn(lines);
      writeFileSync(file, lines.join('\n'));
    };
    try {
      // G4-1 干净树 ⇒ exit 0
      const clean = runSentinel(buildFixture(null));
      ok('G4-1 哨兵在干净仓上 exit 0（出现点级语义下全仓零裸判定）', clean.code === 0 && /^ime-guard PASS: \d+ files scanned/.test(clean.out),
        `exit=${clean.code} ${clean.out.split('\n')[0]}`);

      // G4-2 F-1 最小反例：向**已 import** imeGuard 的 messages.js 插 2 行手搓判定 ⇒ 必红
      const ce = runSentinel(buildFixture((jsDir) => patchLines(join(jsDir, 'messages.js'), (l) => l.splice(1, 0,
        '// hand-rolled fork (F-1 counterexample)',
        'if (e.isComposing || e.keyCode === 229) return;'))));
      ok('G4-2 F-1 反例：已 import 文件内手搓判定 ⇒ exit≠0 且逐条列 file:line',
        ce.code === 1 && ce.out.includes('js/messages.js:3') && ce.out.includes('[isComposing, keyCode === 229]'),
        `exit=${ce.code} ${(ce.out.split('\n').find(l => l.includes('messages.js')) || '').trim()}`);

      // G4-3 允许清单内两行仍 PASS（且不被计入违规）
      const al = runSentinel(buildFixture(null));
      ok('G4-3 允许清单承载 input.js:1175-1176 ⇒ exit 0 且输出可见豁免条目',
        al.code === 0 && al.out.includes('allowlisted occurrence: js/input.js:1175,1176'),
        `exit=${al.code} ${(al.out.split('\n').find(l => l.includes('allowlisted occurrence')) || '').trim()}`);

      // G4-4 反掩蔽：允许清单锚定的是**文本**——同两行被换成真代码（行号不变）⇒ 必红
      const swap = runSentinel(buildFixture((jsDir) => patchLines(join(jsDir, 'input.js'), (l) => {
        l[1174] = '  if (e.isComposing || e.keyCode === 229) return;';
        l[1175] = '  if (e.keyCode === 229) return;';
      })));
      ok('G4-4 反掩蔽：允许清单同两行被换成手搓判定 ⇒ 仍红（豁免不得掩盖其替代者）',
        swap.code === 1 && swap.out.includes('js/input.js:1175') && swap.out.includes('js/input.js:1176'),
        `exit=${swap.code} ${(swap.out.split('\n').filter(l => l.includes('input.js:')).join(' | ') || '').trim()}`);

      // G4-5 清单外新增裸判定（新文件）⇒ 必红
      const nf = runSentinel(buildFixture((jsDir) => writeFileSync(join(jsDir, 'zz-probe.js'),
        'export function g(e) { if (e.isComposing || e.keyCode === 229) return; }\n')));
      ok('G4-5 清单外新文件裸判定 ⇒ exit≠0 并列出 js/zz-probe.js:1',
        nf.code === 1 && nf.out.includes('js/zz-probe.js:1'),
        `exit=${nf.code} ${(nf.out.split('\n').find(l => l.includes('zz-probe')) || '').trim()}`);

      // G4-6 边界申报：注释里的提及是文档（不计），且以「not counted」显式列出
      const cm = runSentinel(buildFixture((jsDir) => writeFileSync(join(jsDir, 'zz-doc.js'),
        '// Every composition test goes through isImeComposing (isComposing / keyCode 229).\nexport const x = 1;\n')));
      ok('G4-6 注释行提及触发词 ⇒ exit 0 且列为 not counted（边界可见，不静默）',
        cm.code === 0 && cm.out.includes('not counted') && cm.out.includes('js/zz-doc.js:1'),
        `exit=${cm.code} ${(cm.out.split('\n').find(l => l.includes('not counted')) || '').trim()}`);
    } finally {
      for (const dir of fixtures) rmSync(dir, { recursive: true, force: true });
    }
  }
} finally {
  await browser.close();
}

console.log(failures === 0 ? '\nALL PASS' : `\n${failures} FAILED`);
process.exit(failures === 0 ? 0 : 1);

#!/usr/bin/env node
// ask-input-composer.spec.mjs — canvas ask 输入行为包验收（2026-11-02 作者令
// 「文字输入框的逻辑做成一个包，参考主 agent 会话窗口的输入模式」+ 两项点名：
// ① 中文 IME 回车逻辑与主会话统一；② ask 加 interrupt 状态）。
//
// 断言面（组合中拦截 + 确认后发送成对出现——「不是因为它本来就坏的」反证律，
// 与 ime-guard.spec 同门）：
//   K1  组字期 Enter：不发送 + defaultPrevented=false + 文本保留（组合中拦截证据）
//   K2  与 compositionend 同一执行块的 Enter（候选确认尾随事件）：不发送
//   K3  组字结束后（新任务）Enter：发送（帧出 + 值清 + user 泡上屏）
//   K4  英文直接回车发送（真键盘路径，行为不变）
//   K5  Shift+Enter 换行：textarea 值含 \n、不发送；随后裸 Enter 带换行正文发送
//   K6  组字期 Esc：dock 不收起（Esc 属于输入法——主会话同律）；非组字 Esc 收起
//   I1  发问 → busy 臂：发送钮隐、中断钮上位、typing 在场
//   I2  流中 textDelta → 部分 ai 泡在场（中断钮持续在位）
//   I3  点中断 → interrupt 帧出（sessionId=ask-xxx）+ typing 撤 + 已生成部分保留
//       + 发送钮回归
//   I4  interrupted 终态帧 → 幂等收口；可继续追问（新 askQuestion 帧出）
//   I5  续问 done → 自然终态收口（不打断路径回归）
//   C   双引擎（chromium + webkit）× 双主题（dark + light）实拍
//       evidence/ask-input-composer/<engine>-<theme>/*.png + manifest.json
//   E   全程零 pageerror（每 combo）
//
// 隔离：自包含——静态文件本机起服务、WS = routeWebSocket mock、不碰宿主 8080 /
// 宿主进程；IME 事件 = 真实 CompositionEvent / KeyboardEvent 派发（isComposing
// 可置；ime-guard.spec 同一约定——无头浏览器无真实 IME 管线，合成事件即该库
// 的既定验证面）。跑完 browser.close() 零残留。
// Run: node tests/ask-input-composer.spec.mjs [--engine chromium|webkit|both]
import { chromium, webkit } from 'playwright';
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, extname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '..', 'src', 'main', 'resources', 'web');
const SHOTS_ROOT = resolve(HERE, '..', 'evidence', 'ask-input-composer');
const PORT = Number(process.env.PORT || 8231);
const BASE = `http://127.0.0.1:${PORT}`;
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };
const ENGINE_ARG = (process.argv[2] || '').includes('webkit') ? 'webkit' : (process.argv[2] || '').includes('chromium') ? 'chromium' : 'both';

// ── 静态文件服务（canvas-ask-dock.spec 同式）────────────────────────────────
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch { res.writeHead(404); res.end('not found'); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

const sidOf = (p) => 'ask-' + createHash('sha256').update(p, 'utf8').digest('hex').slice(0, 8);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let pass = 0, fail = 0;
const failures = [];
function ok(engine, theme, name, cond, detail = '') {
  const line = `${cond ? 'PASS' : 'FAIL'}  [${engine}/${theme}]  ${name}${detail ? '  — ' + detail : ''}`;
  console.log(line);
  if (cond) pass++; else { fail++; failures.push(line); }
}

// ── 合成 IME 事件（ime-guard.spec 同约定；sel = ask textarea）───────────────
const IME_START = `(() => { const el = document.querySelector('.ask-dock textarea');
  el.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true }));
  return el.dataset.imeComposing || null; })()`;
const IME_END = `(() => { const el = document.querySelector('.ask-dock textarea');
  el.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true }));
  return el.dataset.imeComposing || null; })()`;
/** 组合键派发：返回 {flag, prevented, value}。 */
const COMPOSE_KEY = (key, composing) => `(() => {
  const el = document.querySelector('.ask-dock textarea');
  const ev = new KeyboardEvent('keydown', { key: ${JSON.stringify(key)}, isComposing: ${!!composing}, bubbles: true, cancelable: true });
  el.dispatchEvent(ev);
  return { flag: el.dataset.imeComposing || null, prevented: ev.defaultPrevented, value: el.value };
})()`;

// ── boot：单页（engine × theme），WS mock 面（askOpen→askState；流帧测试侧推）─
async function boot(browserType, { colorScheme }) {
  const browser = await browserType.launch();
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 }, colorScheme });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 300)));
  const inbound = []; // page → server 帧留档（协议断言面）
  let ws = null;
  const wsSend = (obj) => { if (ws) ws.send(JSON.stringify(obj)); };
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  await page.route(/\/api\/nf-file\?/, (route) => {
    const p = new URL(route.request().url()).searchParams.get('path') || '';
    return route.fulfill({ status: 200, contentType: 'text/markdown', body: `# ${p}\n\nmock body\n` });
  });
  await installTicketMock(page);
  await page.routeWebSocket(/\/ws/, (sock) => {
    ws = sock;
    sock.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      inbound.push(m);
      if (m.type === 'getHistory') sock.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      if (m.type === 'askOpen') {
        const sid = sidOf((m.file && m.file.absPath) || '');
        sock.send(JSON.stringify({ type: 'askState', sessionId: sid, sessionKey: 'ask:' + sid.slice(4, 8), file: (m.file && m.file.absPath) || '', fileName: (m.file && m.file.name) || 'file', messages: [] }));
      }
    });
    sock.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    sock.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Stand', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 20000 });
  await sleep(700);
  return { browser, ctx, page, errors, inbound, wsSend };
}

/** 开一个可问文件 tab + 打开 dock。返回 absPath。 */
async function openAskable(page, name) {
  const absPath = `/tmp/ask-composer-fixture/${name}`;
  await page.evaluate(({ name: n, absPath: ap }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + ap, itemType: 'markdown', title: n, content: `# ${n}\n\nmock body\n`, absPath: ap, pinned: true },
    }));
  }, { name, absPath });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${absPath}"]`, { state: 'visible', timeout: 10000 });
  await sleep(300);
  await page.click('.ask-fab');
  await sleep(350);
  return absPath;
}

const ui = (page) => page.evaluate(() => {
  const send = /** @type {HTMLElement|null} */ (document.querySelector('.ask-input .ask-send'));
  const stop = /** @type {HTMLElement|null} */ (document.querySelector('.ask-input .ask-stop'));
  return {
    sendDisplay: send ? getComputedStyle(send).display : null,
    stopDisplay: stop ? getComputedStyle(stop).display : null,
    typing: document.querySelectorAll('.ask-dock .ask-bub.typing').length,
    userBubs: document.querySelectorAll('.ask-dock .ask-bub.user').length,
    aiBubs: document.querySelectorAll('.ask-dock .ask-bub.ai').length,
    lastAi: (() => { const n = document.querySelectorAll('.ask-dock .ask-bub.ai'); return n.length ? n[n.length - 1].textContent : ''; })(),
    dockClosed: !!document.querySelector('.ask-dock.closed'),
    value: (/** @type {HTMLTextAreaElement|null} */ (document.querySelector('.ask-dock textarea')))?.value ?? null,
  };
});

/** 等谓词帧命中（自 mark 起扫——mark 必须在触发动作**之前**取；跨进程帧可能
 *  在触发 evaluate 尚未返回 Node 时就已入账）。 */
function waitForFrame(inbound, mark, predicate, timeoutMs = 5000) {
  return new Promise((res) => {
    const timer = setTimeout(() => res(null), timeoutMs);
    const poll = setInterval(() => {
      const hit = inbound.slice(mark).find(predicate);
      if (hit) { clearInterval(poll); clearTimeout(timer); res(hit); }
    }, 40);
  });
}

async function settleWithAnswer(wsSend, sid, text) {
  wsSend({ type: 'textDelta', sessionId: sid, delta: text });
  await sleep(90);
  wsSend({ type: 'done', sessionId: sid });
  await sleep(250);
}

// ── 单 combo 全场景 ─────────────────────────────────────────────────────────
async function runCombo(engineName, browserType, colorScheme) {
  const tag = `${engineName}-${colorScheme}`;
  const shotsDir = join(SHOTS_ROOT, tag);
  mkdirSync(shotsDir, { recursive: true });
  const shot = (page, name) => page.screenshot({ path: join(shotsDir, name + '.png'), fullPage: false });
  const manifest = { engine: engineName, theme: colorScheme, assertions: [] };
  const record = (name, cond, detail) => manifest.assertions.push({ name, cond: !!cond, detail: String(detail).slice(0, 300) });
  const okT = (name, cond, detail = '') => { ok(engineName, colorScheme, name, cond, detail); record(name, cond, detail || '(no detail)'); };

  const { browser, ctx, page, errors, inbound, wsSend } = await boot(browserType, { colorScheme });
  try {
    const abs = await openAskable(page, '记忆检索调优笔记.md');
    const sid = sidOf(abs);

    // ══ K · IME 回车契约（与主会话统一）═══════════════════════════════════
    // K1 组字期 Enter：不发送 + 不拦截默认 + 文本保留（组合中拦截证据）
    await page.evaluate(() => { const el = document.querySelector('.ask-dock textarea'); el.value = '人工智能的延迟是'; });
    const flag = await page.evaluate(IME_START);
    const k1 = await page.evaluate(COMPOSE_KEY('Enter', true));
    await sleep(250);
    const k1ui = await ui(page);
    okT('K1 组字期 Enter：不发送 + defaultPrevented=false + 文本保留',
      flag === '1' && k1.flag === '1' && k1.prevented === false && k1.value === '人工智能的延迟是'
      && !inbound.some((m) => m.type === 'askQuestion') && k1ui.userBubs === 0,
      JSON.stringify({ flag, ...k1, frames: inbound.length }));
    await shot(page, '01-composing-enter-blocked');

    // K2 同一执行块的 compositionend + Enter（候选确认尾随事件）：不发送
    const k2 = await page.evaluate(`(() => {
      const el = document.querySelector('.ask-dock textarea');
      el.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true }));
      const ev = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true });
      el.dispatchEvent(ev);
      return { prevented: ev.defaultPrevented, value: el.value, flag: el.dataset.imeComposing || null };
    })()`);
    await sleep(250);
    okT('K2 同块尾随 Enter（确认候选）：不发送', k2.flag === '1' && k2.prevented === false && !inbound.some((m) => m.type === 'askQuestion'),
      JSON.stringify(k2));

    // K3 组字结束后（新任务）Enter：发送
    await sleep(50); // 新宏任务边界（ime-guard A2 同拍）
    const k3mark = inbound.length;
    const k3 = await page.evaluate(COMPOSE_KEY('Enter', false));
    const k3frame = await waitForFrame(inbound, k3mark, (m) => m.type === 'askQuestion');
    await sleep(200);
    const k3ui = await ui(page);
    okT('K3 组字结束后 Enter：发送（帧出 + 值清 + user 泡上屏）',
      k3.prevented === true && !!k3frame && k3frame.content === '人工智能的延迟是' && k3frame.sessionId === sid
      && k3ui.value === '' && k3ui.userBubs === 1 && k3ui.typing === 1,
      JSON.stringify({ ...k3, frame: !!k3frame, value: k3ui.value, userBubs: k3ui.userBubs }));
    await settleWithAnswer(wsSend, sid, '约 30 天（半衰期口径）。');

    // K4 英文直接回车发送（真键盘；行为不变）
    await page.fill('.ask-dock textarea', 'what about the retry budget?');
    const k4mark = inbound.length;
    await page.press('.ask-dock textarea', 'Enter');
    const k4frame = await waitForFrame(inbound, k4mark, (m) => m.type === 'askQuestion' && m.content === 'what about the retry budget?');
    await sleep(200);
    const k4ui = await ui(page);
    okT('K4 英文直接回车发送（真键盘路径不变）', !!k4frame && k4ui.value === '' && k4ui.userBubs === 2,
      JSON.stringify({ frame: !!k4frame, value: k4ui.value }));
    await settleWithAnswer(wsSend, sid, 'Retry budget: 3 attempts.');
    await shot(page, '02-english-enter-sent');

    // K5 Shift+Enter 换行（真键盘）：不发送 + 值含 \n；随后裸 Enter 带换行正文发送
    await page.fill('.ask-dock textarea', '多行提问第一行');
    await page.press('.ask-dock textarea', 'Shift+Enter');
    await sleep(150);
    const k5a = await ui(page);
    okT('K5a Shift+Enter：换行入 textarea、不发送', (k5a.value || '').includes('\n') && k5a.value === '多行提问第一行\n' && !inbound.some((m) => m.type === 'askQuestion' && String(m.content || '').includes('多行提问第一行')),
      JSON.stringify({ value: JSON.stringify(k5a.value) }));
    await page.evaluate(() => { const el = document.querySelector('.ask-dock textarea'); el.value += '第二行'; });
    await shot(page, '03-shiftenter-newline');
    const k5mark = inbound.length;
    await page.press('.ask-dock textarea', 'Enter');
    const k5frame = await waitForFrame(inbound, k5mark, (m) => m.type === 'askQuestion' && String(m.content || '').includes('第二行'));
    await sleep(150);
    okT('K5b 随后裸 Enter：带换行正文发送', !!k5frame && k5frame.content === '多行提问第一行\n第二行',
      JSON.stringify({ content: k5frame && k5frame.content }));
    await settleWithAnswer(wsSend, sid, '两行都收到了。');

    // K6 组字期 Esc：dock 不收起；非组字 Esc：收起（既有回归）
    await page.evaluate(() => { const el = document.querySelector('.ask-dock textarea'); el.value = '正在组字'; });
    await page.evaluate(IME_START);
    await page.evaluate(COMPOSE_KEY('Escape', true));
    await sleep(200);
    const k6a = await ui(page);
    okT('K6a 组字期 Esc：dock 不收起（Esc 属于输入法，主会话同律）', k6a.dockClosed === false, JSON.stringify({ dockClosed: k6a.dockClosed }));
    await page.evaluate(IME_END);
    await sleep(50);
    await page.keyboard.press('Escape');
    await sleep(350);
    const k6b = await ui(page);
    okT('K6b 非组字 Esc：dock 收起（既有通路回归不破）', k6b.dockClosed === true, JSON.stringify({ dockClosed: k6b.dockClosed }));
    await page.click('.ask-fab');
    await sleep(350);

    // ══ I · interrupt 状态 ═══════════════════════════════════════════════
    // I1 发问 → busy 臂：发送隐、中断上位、typing 在场
    await page.fill('.ask-dock textarea', '请总结这份文档的结论');
    const i1mark = inbound.length;
    await page.press('.ask-dock textarea', 'Enter');
    const i1frame = await waitForFrame(inbound, i1mark, (m) => m.type === 'askQuestion' && m.content === '请总结这份文档的结论');
    await sleep(250);
    const i1 = await ui(page);
    okT('I1 发问 → busy 臂：发送钮隐、中断钮上位、typing 在场',
      !!i1frame && i1.sendDisplay === 'none' && i1.stopDisplay === 'flex' && i1.typing === 1,
      JSON.stringify({ frame: !!i1frame, send: i1.sendDisplay, stop: i1.stopDisplay, typing: i1.typing }));
    const beforeI = { userBubs: i1.userBubs, aiBubs: i1.aiBubs }; // 计数基准（K 组问答已累积泡面，I 组断言全部相对化）

    // I2 流中 textDelta：部分 ai 泡在场，中断持续在位
    wsSend({ type: 'textDelta', sessionId: sid, delta: '部分回答：检索按近因加权…' });
    await sleep(200);
    const i2 = await ui(page);
    okT('I2 流中：部分 ai 泡在场 + 中断钮持续在位',
      i2.aiBubs === beforeI.aiBubs + 1 && i2.lastAi.includes('近因加权') && i2.stopDisplay === 'flex' && i2.sendDisplay === 'none',
      JSON.stringify({ ai: i2.lastAi.slice(0, 24), stop: i2.stopDisplay, send: i2.sendDisplay }));
    await shot(page, '04-streaming-stop-armed');

    // I3 点中断：interrupt 帧出（包单点，帧形态与主会话 #stop-btn 逐字节一致）
    // + typing 撤 + 部分保留 + 发送钮回归
    const i3mark = inbound.length;
    await page.click('.ask-input .ask-stop');
    const i3frame = await waitForFrame(inbound, i3mark, (m) => m.type === 'interrupt' && m.sessionId === sid);
    await sleep(250);
    const i3 = await ui(page);
    okT('I3 点中断：interrupt 帧出 + typing 撤 + 已生成部分保留 + 发送钮回归',
      !!i3frame && i3.typing === 0 && i3.aiBubs === beforeI.aiBubs + 1 && i3.lastAi.includes('近因加权') && i3.sendDisplay === 'flex' && i3.stopDisplay === 'none',
      JSON.stringify({ frame: i3frame && { type: i3frame.type, sessionId: i3frame.sessionId }, typing: i3.typing, aiKept: i3.lastAi.slice(0, 24), send: i3.sendDisplay, stop: i3.stopDisplay }));
    await shot(page, '05-after-interrupt-partial-kept');

    // I4 interrupted 终态帧：幂等收口；可继续追问
    wsSend({ type: 'interrupted', sessionId: sid });
    await sleep(300);
    const i4 = await ui(page);
    await page.fill('.ask-dock textarea', '继续：第二问');
    const i4mark = inbound.length;
    await page.press('.ask-dock textarea', 'Enter');
    const i4frame = await waitForFrame(inbound, i4mark, (m) => m.type === 'askQuestion' && m.content === '继续：第二问');
    await sleep(200);
    const i4b = await ui(page);
    okT('I4 interrupted 帧幂等收口 + 可继续追问（新 askQuestion 帧出）',
      i4.sendDisplay === 'flex' && i4.stopDisplay === 'none' && i4.aiBubs === beforeI.aiBubs + 1 // 收口不回退 UI（已生成部分保留）
      && !!i4frame && i4b.userBubs === beforeI.userBubs + 1 && i4b.typing === 1,
      JSON.stringify({ settledIdle: i4.sendDisplay, frame2: !!i4frame, userBubs: i4b.userBubs }));

    // I5 续问 done：自然终态收口（不打断路径回归）
    await settleWithAnswer(wsSend, sid, '第二问的回答。');
    const i5 = await ui(page);
    okT('I5 续问 done：typing 撤 + 发送钮在位（自然终态收口）',
      i5.typing === 0 && i5.sendDisplay === 'flex' && i5.stopDisplay === 'none' && i5.aiBubs === beforeI.aiBubs + 2,
      JSON.stringify({ typing: i5.typing, send: i5.sendDisplay, ai: i5.aiBubs }));
    await shot(page, '06-continued-after-interrupt');

    // E · 零 pageerror
    okT('E 零 pageerror', errors.length === 0, errors.join('; '));
    writeFileSync(join(shotsDir, 'manifest.json'), JSON.stringify(manifest, null, 2));
  } finally {
    await browser.close();
  }
}

try {
  const combos = [];
  if (ENGINE_ARG !== 'webkit') combos.push(['chromium', chromium, 'dark'], ['chromium', chromium, 'light']);
  if (ENGINE_ARG !== 'chromium') combos.push(['webkit', webkit, 'dark'], ['webkit', webkit, 'light']);
  for (const [engine, type, theme] of combos) {
    console.log(`\n════ ${engine} × ${theme} ════`);
    await runCombo(engine, type, theme);
  }
} finally {
  server.close();
}

console.log(fail === 0 ? `\nALL PASS (${pass})` : `\n${fail} FAILED`);
process.exit(fail === 0 ? 0 : 1);

// voice-seal.spec.mjs — voiceseal 批（批 #508）封存护栏：全双工语音 + 同源
// 拖动居中语音气泡 的 flag 化隐藏（featureFlags.voiceEnabled，`features.voice`，
// 默认封存 = OFF）。
//
// 断言面（任务要求⑤「flag OFF 两入口不可见 + flag ON 功能恢复」两组用例）：
//   S1 封存态（生产默认：configData 不带 features.voice）
//     V1a 面 B（拖动居中语音气泡 #mic-orb-wrap）在 DOM（零删除）但不可见
//         （`hidden` 属性 + computed display:none），拖动提示随之不可见；
//     V1b 面 A（全双工 #voice-mode-overlay）在 DOM 但 `hidden` + 无 `.on`；
//     V1c orb 单例不挂载（getMicOrb() === null ⇒ GL 上下文/1s 轮询零占用），
//         #voice-btn 的 aria-label 保持 HTML 静态值（未被 orb 运行时改写）；
//     V1d 全双工直呼兜底：voiceMode.enterVoiceMode(...) 在封存态 no-op
//         （isVoiceModeActive() === false）；
//     V1e 拖动手势兜底：对（已隐藏的）气泡派发合成 pointer 拖右事件，
//         body 不进入 voice-mode-active；
//     V1f 范围护栏：header 语音输出开关 #voice-toggle-btn（独立功能，非本批
//         封存面）两面状态下都保持可见。
//   S2 恢复态（同一 flag 开启：configData 带 `features.voice:true`）
//     V2a 两面 `hidden` 摘除：气泡可见（computed display:flex）；overlay 恢复
//         CSS 默认（`hidden` 摘除，display 回到样式表层值）；
//     V2b orb 单例按原路径挂载并绑定产品面画布（#mic-canvas）；
//     V2c 拖动手势打开全双工：真鼠标拖右越过阈值 → body.voice-mode-active +
//         overlay `.on` + isVoiceModeActive() === true（SpeechRecognition 用
//         最小 mock，走 voiceEngine Web Speech 路径，无后端）；
//     V2d Esc 退出 → 默认形态恢复（body 类摘除、overlay 退出 `.on`、气泡仍在）。
//   两态零 page error。
//
// 隔离：自包含 —— route 拦截静态文件（直接从 web 树读盘）+ routeWebSocket
// mock（friend-trust-sealed.spec.mjs 同款口径），无后端、无真实端口、不碰宿主
// 8080。flag 两态 = configData 帧的 config 字段差异，无需副本树。
// Run: node tests/voice-seal.spec.mjs
import { chromium } from 'playwright-core';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));

const browser = await chromium.launch();

/**
 * bootPage — 自包含页面（静态文件从 web 树读盘；WS = mock）。
 * @param {{voiceOn?: boolean}} opts voiceOn: configData 是否携带 features.voice
 */
async function bootPage({ voiceOn = false } = {}) {
  const features = voiceOn ? { voice: true } : {};
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'en');
    localStorage.setItem('neblink_locale', 'en');
    // voiceseal spec 专用 SpeechRecognition 最小 mock：voiceEngine 走 Web Speech
    // 路径（configData 无 stt ⇒ useCloud()=false），start() 同步 listening，
    // 全双工会话因此保持打开（V2c 断言面）。
    class MockSR {
      constructor() { this.lang = ''; this.continuous = false; this.interimResults = false; }
      start() { this.onstart && this.onstart({}); this.onaudiostart && this.onaudiostart({}); }
      abort() { queueMicrotask(() => this.onend && this.onend({})); }
      stop() { queueMicrotask(() => this.onend && this.onend({})); }
    }
    window.SpeechRecognition = MockSR;
  });
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
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  let serverWs = null;
  await page.routeWebSocket(/\/ws/, (ws) => {
    serverWs = ws;
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', config: JSON.stringify({ features }), configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 's1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 's1', folders: [] }));
  });
  await page.goto('http://localhost:1/index.html');
  await page.waitForSelector('#input', { state: 'attached', timeout: 20000 });
  // latch 等待：unseal 态等 `hidden` 被摘除；seal 态等 latch 落定（静态已 hidden，
  // 等 applyVoiceSeal 的幂等重申窗口过去即可）。
  await page.waitForFunction((on) => {
    const wrap = document.getElementById('mic-orb-wrap');
    return !!wrap && (on ? !wrap.hasAttribute('hidden') : wrap.hasAttribute('hidden'));
  }, voiceOn, { timeout: 15000 });
  await sleep(500);
  return { ctx, page, pageErrors };
}

/** 两面 + 护栏的可见性/属性读数。 */
async function faceSnapshot(page) {
  return page.evaluate(() => {
    const disp = (id) => { const el = document.getElementById(id); return el ? getComputedStyle(el).display : null; };
    const wrap = document.getElementById('mic-orb-wrap');
    const overlay = document.getElementById('voice-mode-overlay');
    const toggle = document.getElementById('voice-toggle-btn');
    return {
      wrapAttached: !!wrap,
      wrapHiddenAttr: !!wrap && wrap.hasAttribute('hidden'),
      wrapDisplay: disp('mic-orb-wrap'),
      wrapVisible: !!wrap && wrap.offsetParent !== null && getComputedStyle(wrap).display !== 'none',
      hintDisplay: (() => { const h = document.querySelector('#mic-orb-wrap .voice-drag-hint'); return h ? getComputedStyle(h).display : null; })(),
      overlayAttached: !!overlay,
      overlayHiddenAttr: !!overlay && overlay.hasAttribute('hidden'),
      overlayDisplay: disp('voice-mode-overlay'),
      overlayOn: !!overlay && overlay.classList.contains('on'),
      voiceBtnAria: document.getElementById('voice-btn')?.getAttribute('aria-label') ?? null,
      // 范围护栏读数：语音输出开关是独立面（chat.js VoicePlayer / <voice> TTS），
      // 不在本批封存面内。可见性受 header 碰撞链（initHeaderResizeObserver，
      // bypass/voice 为最低优先级成员）环境性影响，故护栏断「在 DOM 且未被打
      // 上封存 hidden」——本批对它零触碰。
      voiceToggleAttached: !!document.getElementById('voice-toggle-btn'),
      voiceToggleHidden: !!document.getElementById('voice-toggle-btn')?.hasAttribute('hidden'),
    };
  });
}

/** 对 #voice-btn 派发一轮合成「按下→拖右 80px→抬起」pointer 序列（V1e 用：
 *  事件 handler 会跑，但 display:none ⇒ 不产生任何入口效果）。 */
async function syntheticDragRight(page) {
  return page.evaluate(() => {
    const btn = document.getElementById('voice-btn');
    if (!btn) return false;
    const pe = (type, x) => btn.dispatchEvent(new PointerEvent(type, {
      bubbles: true, cancelable: true, pointerId: 7, clientX: x, clientY: 300,
    }));
    pe('pointerdown', 100);
    pe('pointermove', 140);
    pe('pointermove', 180); // > 56px 阈值
    pe('pointerup', 180);
    return true;
  });
}

// ══ S1 封存态（生产默认：configData 不带 features.voice）══════════════
{
  const sealed = await bootPage({ voiceOn: false });
  const { page, pageErrors } = sealed;
  const s = await faceSnapshot(page);
  ok('V1a 面 B 气泡：DOM 在位（零删除）但 hidden + display:none（不可见）',
    s.wrapAttached && s.wrapHiddenAttr && s.wrapDisplay === 'none' && !s.wrapVisible,
    JSON.stringify({ hidden: s.wrapHiddenAttr, display: s.wrapDisplay }));
  ok('V1a-b 拖动提示不可见（随封存面整组隐藏）', s.hintDisplay === 'none', `display=${s.hintDisplay}`);
  ok('V1b 面 A 全双工 overlay：DOM 在位但 hidden + 无 `.on`',
    s.overlayAttached && s.overlayHiddenAttr && !s.overlayOn && s.overlayDisplay === 'none',
    JSON.stringify({ hidden: s.overlayHiddenAttr, on: s.overlayOn, display: s.overlayDisplay }));
  const orb = await page.evaluate(async () => {
    const m = await import('/js/micOrb.js');
    return { singleton: m.getMicOrb() };
  });
  ok('V1c orb 单例不挂载（getMicOrb()=null ⇒ GL/轮询零占用）', orb.singleton === null, `singleton=${String(orb.singleton)}`);
  ok('V1c-b 气泡 aria-label 保持 HTML 静态值（orb 运行时未接管）',
    s.voiceBtnAria === 'Voice input', `aria=${JSON.stringify(s.voiceBtnAria)}`);
  const vmode = await page.evaluate(async () => {
    const m = await import('/js/voiceMode.js');
    m.enterVoiceMode({ sendText: () => true, ensureDictationIdle: () => {}, getSessionId: () => 's1' });
    return m.isVoiceModeActive();
  });
  ok('V1d 全双工直呼兜底：enterVoiceMode 封存态 no-op', vmode === false, `active=${String(vmode)}`);
  ok('V1d-b 直呼后 overlay 仍未打开', !(await page.evaluate(() => document.getElementById('voice-mode-overlay')?.classList.contains('on') ?? false)));
  await syntheticDragRight(page);
  await sleep(400);
  ok('V1e 拖动手势兜底：合成拖右不进入 voice-mode-active',
    !(await page.evaluate(() => document.body.classList.contains('voice-mode-active'))));
  ok('V1f 范围护栏：header 语音输出开关（独立面）未被本批触碰（在 DOM、无封存 hidden）',
    s.voiceToggleAttached === true && s.voiceToggleHidden === false,
    `attached=${String(s.voiceToggleAttached)} hidden=${String(s.voiceToggleHidden)}`);
  ok('S1 零 page error', pageErrors.length === 0, pageErrors.join('; '));
  await sealed.ctx.close();
}

// ══ S2 恢复态（同一 flag 开启：features.voice = true）═════════════════
{
  const unsealed = await bootPage({ voiceOn: true });
  const { page, pageErrors } = unsealed;
  const s = await faceSnapshot(page);
  ok('V2a 面 B 气泡恢复：hidden 摘除 + 可见（display:flex）',
    s.wrapAttached && !s.wrapHiddenAttr && s.wrapDisplay === 'flex' && s.wrapVisible,
    JSON.stringify({ hidden: s.wrapHiddenAttr, display: s.wrapDisplay }));
  ok('V2a-b 面 A overlay 恢复：hidden 摘除（回 CSS 默认态，未 .on）',
    s.overlayAttached && !s.overlayHiddenAttr && !s.overlayOn && s.overlayDisplay !== 'none',
    JSON.stringify({ hidden: s.overlayHiddenAttr, display: s.overlayDisplay }));
  const orb = await page.evaluate(async () => {
    const m = await import('/js/micOrb.js');
    const o = m.getMicOrb();
    return { singleton: !!o, bound: !!o && o.canvas?.id === 'mic-canvas' && o.btn?.id === 'voice-btn' };
  });
  ok('V2b orb 单例挂载并绑定产品面（#voice-btn/#mic-canvas）',
    orb.singleton && orb.bound, JSON.stringify(orb));
  // V2c 真鼠标拖右（pointerdown → 右移过阈值 → 抬起）打开全双工
  const box = await page.locator('#voice-btn').boundingBox();
  ok('V2c 前置：气泡可命中（boundingBox 在位）', !!box, JSON.stringify(box));
  if (box) {
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    await page.mouse.down();
    await page.mouse.move(box.x + box.width / 2 + 30, box.y + box.height / 2, { steps: 4 });
    await page.mouse.move(box.x + box.width / 2 + 80, box.y + box.height / 2, { steps: 4 });
    await page.mouse.up();
    await page.waitForFunction(() => document.body.classList.contains('voice-mode-active'), null, { timeout: 8000 })
      .catch(() => {});
  }
  const vmodeActive = await page.evaluate(async () => {
    const m = await import('/js/voiceMode.js');
    return m.isVoiceModeActive();
  });
  const overlayOn = await page.evaluate(() => document.getElementById('voice-mode-overlay')?.classList.contains('on') ?? false);
  ok('V2c 拖右打开全双工（body 类 + overlay.on + isVoiceModeActive）',
    !!(box) && vmodeActive && overlayOn
      && (await page.evaluate(() => document.body.classList.contains('voice-mode-active'))),
    `active=${String(vmodeActive)} overlayOn=${String(overlayOn)}`);
  // V2d Esc 退出 → 默认形态恢复
  await page.keyboard.press('Escape');
  await sleep(600);
  const after = await faceSnapshot(page);
  const exited = await page.evaluate(async () => {
    const m = await import('/js/voiceMode.js');
    return m.isVoiceModeActive();
  });
  ok('V2d Esc 退出：mode 关闭 + overlay 退出 `.on`', exited === false && !after.overlayOn,
    `active=${String(exited)} on=${String(after.overlayOn)}`);
  ok('V2d-b 退出后气泡仍在（默认形态完整）', after.wrapVisible === true && !after.wrapHiddenAttr);
  ok('V2d-c 范围护栏（恢复态）：header 语音输出开关仍未被触碰',
    after.voiceToggleAttached === true && after.voiceToggleHidden === false);
  ok('S2 零 page error', pageErrors.length === 0, pageErrors.join('; '));
  await unsealed.ctx.close();
}

await browser.close();
console.log(`\n${failures === 0 ? 'ALL PASS' : failures + ' FAILED'}`);
process.exit(failures ? 1 : 0);

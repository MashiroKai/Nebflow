// voice-seal.spec.mjs — voiceseal 批（批 #508）→ **纠正批（批 #513）** 护栏。
// 作者澄清令逐字：「怎么语音气泡直接没了，我要去掉的是hover拖动这些功能。」
// —— #508 把拖动居中语音气泡整个藏了 = 理解过宽；#513 纠正为**分面受控**：
//
//   面 A = 全双工语音：维持 #508 封存不变（voiceEnabled()，`features.voice`，
//          默认 OFF）—— #voice-mode-overlay 静态 hidden、拖动手势入口
//          （input.js pointer 序列）闸死、enterVoiceMode 终闸 no-op、
//          WS conversation 腿零启动。
//   面 B = 语音气泡（#mic-orb-wrap + micOrb 单例）：回归**默认产品形态**
//          = 可见可点（点击听写等非全双工基础功能照常），但**纯静态**——
//          hover 悬停反馈（.voice-drag-hint，css/voiceMode.css `.voice-static`
//          姿态规则压死）与拖动/回弹手势（input.js pointerdown 的
//          voiceEnabled() 闸）交互面全部摘除，气泡固定默认位。
//   面 B 独立轻量开关 voiceOrbEnabled()（`features.voiceOrb`，默认显示；
//          显式 false 才藏 —— #508 的隐藏能力保留但与全双工解耦）。
//
// 断言面：
//   S1 默认态（生产默认：configData 不带 features）
//     N1 面 B 气泡可见可点：无 `hidden`、display:flex、`voice-static` 姿态类在位；
//     N2 hover 反馈死：真鼠标悬停气泡 ⇒ .voice-drag-hint computed display 仍 none；
//     N3 面 A overlay：DOM 在位但 `hidden` + 无 `.on`；
//     N4 orb 单例按默认挂载并绑定产品面画布（#voice-btn/#mic-canvas，s-idle 在位）；
//     N5 基础功能：点击气泡 ⇒ 听写启动（voice-dictating + recording + aria-pressed）
//         再点 ⇒ 停止；全程零 voice-mode、零 WS transcribe/tts 帧（Web Speech 腿）；
//     N6 全双工直呼兜底：voiceMode.enterVoiceMode(...) 默认态 no-op；
//     N7 拖动手势兜底：合成 pointer 拖右 ⇒ 不进 voice-mode-active，wrap transform
//         保持 ''（零跟手）；
//     N8 真鼠标拖右越阈值 ⇒ 全双工不开（overlay 仍 hidden、mode 仍 false、transform 仍 ''）；
//     N9 范围护栏：header 语音输出开关 #voice-toggle-btn（独立功能）两面状态下都可见。
//   S2 恢复态（features.voice:true = 面 A 解封，#508 封存/恢复机制原样）
//     R1 气泡仍可见仍静态（voice-static 不摘）；overlay `hidden` 摘除（回 CSS 默认）；
//     R2 orb 单例在位（同默认）；
//     R3 hover 反馈仍死（voice-static 是产品姿态，不随 flag 回归）；
//     R4 拖右开全双工（手势随面 A 原样回归：body.voice-mode-active + overlay.on +
//         isVoiceModeActive）；
//     R5 Esc 退出 → 默认形态恢复（气泡仍在、mode 关、overlay 退 `.on`）。
//   S3 气泡轻量开关（features.voiceOrb:false，voice 仍默认封存）
//     O1 气泡 `hidden` + display:none；aria 保持 HTML 静态值（orb 未接管）；
//     O2 orb 单例不挂载（getMicOrb() === null ⇒ GL/轮询零占用）；
//     O3 面 A 仍封存：overlay hidden + enterVoiceMode no-op；
//     O4 程序化点击兜底：隐藏气泡上 click ⇒ 听写不启动（voiceOrbEnabled 闸）。
//   三态零 page error。
//
// 隔离：自包含 —— route 拦截静态文件（直接从 web 树读盘）+ routeWebSocket
// mock（friend-trust-sealed.spec.mjs 同款口径），无后端、无真实端口、不碰宿主
// 8080。三态 = configData 帧的 config 字段差异，无需副本树。
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
 * @param {{voiceOn?: boolean, orbOff?: boolean}} opts
 *   voiceOn: configData 是否携带 features.voice（面 A 解封）；
 *   orbOff:  configData 是否携带 features.voiceOrb:false（面 B 显式隐藏）。
 */
async function bootPage({ voiceOn = false, orbOff = false } = {}) {
  const features = {};
  if (voiceOn) features.voice = true;
  if (orbOff) features.voiceOrb = false;
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'en');
    localStorage.setItem('neblink_locale', 'en');
    // voiceseal spec 专用 SpeechRecognition 最小 mock：voiceEngine 走 Web Speech
    // 路径（configData 无 stt ⇒ useCloud()=false），start() 同步 listening，
    // 全双工会话因此保持打开（R4 断言面）。
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
  const wsClientFrames = []; // client→server 帧（听写腿零后端占用的证据面）
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
      wsClientFrames.push(m.type);
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', config: JSON.stringify({ features }), configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 's1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 's1', folders: [] }));
  });
  await page.goto('http://localhost:1/index.html');
  await page.waitForSelector('#input', { state: 'attached', timeout: 20000 });
  // latch 等待：orbOff 态等 `hidden` 被补挂；其余态等气泡确认在位（默认显示，
  // 静态无 hidden；显式 voiceOrb:false 由 applyVoiceSeal 补挂）。
  await page.waitForFunction((off) => {
    const wrap = document.getElementById('mic-orb-wrap');
    return !!wrap && (off ? wrap.hasAttribute('hidden') : !wrap.hasAttribute('hidden'));
  }, orbOff, { timeout: 15000 });
  await sleep(500);
  return { ctx, page, pageErrors, wsClientFrames };
}

/** 两面 + 护栏 + 姿态的可见性/属性读数。 */
async function faceSnapshot(page) {
  return page.evaluate(() => {
    const disp = (id) => { const el = document.getElementById(id); return el ? getComputedStyle(el).display : null; };
    const wrap = document.getElementById('mic-orb-wrap');
    const overlay = document.getElementById('voice-mode-overlay');
    return {
      wrapAttached: !!wrap,
      wrapHiddenAttr: !!wrap && wrap.hasAttribute('hidden'),
      wrapDisplay: disp('mic-orb-wrap'),
      wrapVisible: !!wrap && wrap.offsetParent !== null && getComputedStyle(wrap).display !== 'none',
      wrapStaticCls: !!wrap && wrap.classList.contains('voice-static'),
      wrapTransform: wrap ? wrap.style.transform : null,
      hintDisplay: (() => { const h = document.querySelector('#mic-orb-wrap .voice-drag-hint'); return h ? getComputedStyle(h).display : null; })(),
      overlayAttached: !!overlay,
      overlayHiddenAttr: !!overlay && overlay.hasAttribute('hidden'),
      overlayDisplay: disp('voice-mode-overlay'),
      overlayOn: !!overlay && overlay.classList.contains('on'),
      voiceBtnAria: document.getElementById('voice-btn')?.getAttribute('aria-label') ?? null,
      modeActive: document.body.classList.contains('voice-mode-active'),
      // 范围护栏读数：语音输出开关是独立面（chat.js VoicePlayer / <voice> TTS），
      // 不在本批封存面内。可见性受 header 碰撞链（initHeaderResizeObserver，
      // bypass/voice 为最低优先级成员）环境性影响，故护栏断「在 DOM 且未被打
      // 上封存 hidden」——本批对它零触碰。
      voiceToggleAttached: !!document.getElementById('voice-toggle-btn'),
      voiceToggleHidden: !!document.getElementById('voice-toggle-btn')?.hasAttribute('hidden'),
    };
  });
}

/** 真鼠标拖右（pointerdown → 右移过 56px 阈值 → 抬起）。 */
async function realDragRight(page) {
  const box = await page.locator('#voice-btn').boundingBox();
  if (!box) return null;
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width / 2 + 30, box.y + box.height / 2, { steps: 4 });
  await page.mouse.move(box.x + box.width / 2 + 80, box.y + box.height / 2, { steps: 4 });
  await page.mouse.up();
  return box;
}

/** 对 #voice-btn 派发一轮合成「按下→拖右 80px→抬起」pointer 序列（手势体兜底）。 */
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

/** 真悬停气泡中心（:hover 由真鼠标位置驱动，合成事件不可达）。 */
async function realHoverBubble(page) {
  const box = await page.locator('#voice-btn').boundingBox();
  if (!box) return false;
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2, { steps: 3 });
  await sleep(250);
  return true;
}

// ══ S1 默认态（生产默认：configData 不带 features）══════════════════════
{
  const seeded = await bootPage({ voiceOn: false, orbOff: false });
  const { page, pageErrors, wsClientFrames } = seeded;
  const s = await faceSnapshot(page);
  ok('N1 面 B 气泡可见（#513 纠正）：无 hidden + display:flex + offsetParent 在位',
    s.wrapAttached && !s.wrapHiddenAttr && s.wrapDisplay === 'flex' && s.wrapVisible,
    JSON.stringify({ hidden: s.wrapHiddenAttr, display: s.wrapDisplay }));
  ok('N1-b 气泡纯静态姿态：voice-static 类在位、transform 干净（固定默认位）',
    s.wrapStaticCls && s.wrapTransform === '', `static=${String(s.wrapStaticCls)} transform=${JSON.stringify(s.wrapTransform)}`);
  // N2 hover 反馈死：真鼠标悬停 ⇒ chevrons 仍 display:none（voice-static 姿态规则压死）
  const hovered = await realHoverBubble(page);
  const hintAfterHover = await page.evaluate(() => {
    const h = document.querySelector('#mic-orb-wrap .voice-drag-hint');
    return h ? getComputedStyle(h).display : null;
  });
  ok('N2 hover 反馈死：真悬停气泡后 .voice-drag-hint 仍 display:none',
    hovered && hintAfterHover === 'none', `hover=${String(hovered)} hint=${hintAfterHover}`);
  ok('N3 面 A 全双工 overlay：DOM 在位但 hidden + 无 `.on`（#508 封存不变）',
    s.overlayAttached && s.overlayHiddenAttr && !s.overlayOn && s.overlayDisplay === 'none',
    JSON.stringify({ hidden: s.overlayHiddenAttr, on: s.overlayOn, display: s.overlayDisplay }));
  // N4 orb 单例默认挂载 + 绑定产品面 + live（s-idle 状态类 = 1s 轮询在跑）
  const orb = await page.evaluate(async () => {
    const m = await import('/js/micOrb.js');
    const o = m.getMicOrb();
    return {
      singleton: !!o,
      bound: !!o && o.canvas?.id === 'mic-canvas' && o.btn?.id === 'voice-btn',
      webglOk: !!o && o.webglOk === true,
    };
  });
  await page.waitForFunction(() => {
    const wrap = document.getElementById('mic-orb-wrap');
    return !!wrap && wrap.classList.contains('s-idle');
  }, null, { timeout: 15000 }).catch(() => {});
  const idle = await page.evaluate(() => document.getElementById('mic-orb-wrap')?.classList.contains('s-idle') ?? false);
  ok('N4 orb 单例默认挂载并绑定产品面（#voice-btn/#mic-canvas）+ live（s-idle）',
    orb.singleton && orb.bound && idle, JSON.stringify({ ...orb, idle }));
  // N5 基础功能：点击 ⇒ 听写启动；再点 ⇒ 停止；全程零全双工、零后端语音帧
  await page.click('#voice-btn');
  await sleep(300);
  const dictating = await page.evaluate(() => {
    const btn = document.getElementById('voice-btn');
    return {
      cls: document.getElementById('input')?.classList.contains('voice-dictating') ?? false,
      recording: btn?.classList.contains('recording') ?? false,
      pressed: btn?.getAttribute('aria-pressed') ?? null,
    };
  });
  ok('N5 点击听写启动（voice-dictating + recording + aria-pressed）',
    dictating.cls && dictating.recording && dictating.pressed === 'true', JSON.stringify(dictating));
  await page.click('#voice-btn');
  await sleep(300);
  const stopped = await page.evaluate(() => {
    const btn = document.getElementById('voice-btn');
    return {
      cls: document.getElementById('input')?.classList.contains('voice-dictating') ?? false,
      recording: btn?.classList.contains('recording') ?? false,
    };
  });
  ok('N5-b 再点停止听写', !stopped.cls && !stopped.recording, JSON.stringify(stopped));
  const midSnapshot = await faceSnapshot(page);
  ok('N5-c 听写期间零全双工（mode 不激活、overlay 仍 hidden）',
    !midSnapshot.modeActive && midSnapshot.overlayHiddenAttr && !midSnapshot.overlayOn,
    `mode=${String(midSnapshot.modeActive)} hidden=${String(midSnapshot.overlayHiddenAttr)}`);
  const voiceFrames = wsClientFrames.filter(t => t === 'transcribe' || t === 'tts' || t === 'ttsStop');
  ok('N5-d 听写走 Web Speech 腿：零 WS transcribe/tts 帧（后端腿零占用）',
    voiceFrames.length === 0, `frames=${JSON.stringify(voiceFrames)}`);
  // N6 全双工直呼兜底：enterVoiceMode 默认态 no-op（voiceMode 终闸原样）
  const vmode = await page.evaluate(async () => {
    const m = await import('/js/voiceMode.js');
    m.enterVoiceMode({ sendText: () => true, ensureDictationIdle: () => {}, getSessionId: () => 's1' });
    return m.isVoiceModeActive();
  });
  ok('N6 全双工直呼兜底：enterVoiceMode 默认态 no-op', vmode === false, `active=${String(vmode)}`);
  ok('N6-b 直呼后 overlay 仍未打开',
    !(await page.evaluate(() => document.getElementById('voice-mode-overlay')?.classList.contains('on') ?? false)));
  // N7 拖动手势兜底（合成序列：手势体在，闸生效 ⇒ 零跟手、零模式）
  await syntheticDragRight(page);
  await sleep(400);
  const afterSyn = await faceSnapshot(page);
  ok('N7 拖动手势兜底：合成拖右不进 voice-mode-active、零跟手（transform 仍 \'\'）',
    !afterSyn.modeActive && afterSyn.wrapTransform === '',
    `mode=${String(afterSyn.modeActive)} transform=${JSON.stringify(afterSyn.wrapTransform)}`);
  // N8 真鼠标拖右越阈值 ⇒ 全双工不开（交互面已摘）
  const box = await realDragRight(page);
  await sleep(500);
  const afterReal = await faceSnapshot(page);
  ok('N8 真鼠标拖右：全双工不开（mode false + overlay hidden + 零跟手）',
    !!box && !afterReal.modeActive && afterReal.overlayHiddenAttr && !afterReal.overlayOn && afterReal.wrapTransform === '',
    `box=${JSON.stringify(box)} mode=${String(afterReal.modeActive)} transform=${JSON.stringify(afterReal.wrapTransform)}`);
  ok('N9 范围护栏：header 语音输出开关（独立面）未被本批触碰（在 DOM、无封存 hidden）',
    s.voiceToggleAttached === true && s.voiceToggleHidden === false,
    `attached=${String(s.voiceToggleAttached)} hidden=${String(s.voiceToggleHidden)}`);
  ok('S1 零 page error', pageErrors.length === 0, pageErrors.join('; '));
  await seeded.ctx.close();
}

// ══ S2 恢复态（features.voice:true = 面 A 解封，#508 封存/恢复机制原样）══
{
  const unsealed = await bootPage({ voiceOn: true });
  const { page, pageErrors } = unsealed;
  const s = await faceSnapshot(page);
  ok('R1 面 A overlay 解封：hidden 摘除（回 CSS 默认态，未 .on）',
    s.overlayAttached && !s.overlayHiddenAttr && !s.overlayOn && s.overlayDisplay !== 'none',
    JSON.stringify({ hidden: s.overlayHiddenAttr, display: s.overlayDisplay }));
  ok('R1-b 面 B 气泡不受面 A flag 影响：仍可见、仍静态（voice-static 不摘）',
    s.wrapVisible && !s.wrapHiddenAttr && s.wrapStaticCls,
    JSON.stringify({ visible: s.wrapVisible, static: s.wrapStaticCls }));
  const orb = await page.evaluate(async () => {
    const m = await import('/js/micOrb.js');
    const o = m.getMicOrb();
    return { singleton: !!o, bound: !!o && o.canvas?.id === 'mic-canvas' && o.btn?.id === 'voice-btn' };
  });
  ok('R2 orb 单例在位并绑定产品面', orb.singleton && orb.bound, JSON.stringify(orb));
  // R3 hover 反馈仍死（voice-static = 产品姿态，不随 flag 回归）
  const hovered = await realHoverBubble(page);
  const hintAfterHover = await page.evaluate(() => {
    const h = document.querySelector('#mic-orb-wrap .voice-drag-hint');
    return h ? getComputedStyle(h).display : null;
  });
  ok('R3 hover 反馈仍死（恢复态 chevrons 也不回归）', hovered && hintAfterHover === 'none',
    `hover=${String(hovered)} hint=${hintAfterHover}`);
  // R4 拖右打开全双工（手势随面 A 原样回归 = #508 unseal 语义不变）
  const box = await realDragRight(page);
  await page.waitForFunction(() => document.body.classList.contains('voice-mode-active'), null, { timeout: 8000 })
    .catch(() => {});
  const vmodeActive = await page.evaluate(async () => {
    const m = await import('/js/voiceMode.js');
    return m.isVoiceModeActive();
  });
  const overlayOn = await page.evaluate(() => document.getElementById('voice-mode-overlay')?.classList.contains('on') ?? false);
  ok('R4 拖右打开全双工（body 类 + overlay.on + isVoiceModeActive）',
    !!box && vmodeActive && overlayOn
      && (await page.evaluate(() => document.body.classList.contains('voice-mode-active'))),
    `active=${String(vmodeActive)} overlayOn=${String(overlayOn)}`);
  // R5 Esc 退出 → 默认形态恢复
  await page.keyboard.press('Escape');
  await sleep(600);
  const after = await faceSnapshot(page);
  const exited = await page.evaluate(async () => {
    const m = await import('/js/voiceMode.js');
    return m.isVoiceModeActive();
  });
  ok('R5 Esc 退出：mode 关闭 + overlay 退出 `.on`', exited === false && !after.overlayOn,
    `active=${String(exited)} on=${String(after.overlayOn)}`);
  ok('R5-b 退出后气泡仍在（默认形态完整、仍静态）',
    after.wrapVisible === true && !after.wrapHiddenAttr && after.wrapStaticCls);
  ok('R5-c 范围护栏（恢复态）：header 语音输出开关仍未被触碰',
    after.voiceToggleAttached === true && after.voiceToggleHidden === false);
  ok('S2 零 page error', pageErrors.length === 0, pageErrors.join('; '));
  await unsealed.ctx.close();
}

// ══ S3 气泡轻量开关（features.voiceOrb:false；voice 仍默认封存）═════════
{
  const orbOff = await bootPage({ voiceOn: false, orbOff: true });
  const { page, pageErrors } = orbOff;
  const s = await faceSnapshot(page);
  ok('O1 voiceOrb:false ⇒ 气泡 hidden + display:none',
    s.wrapAttached && s.wrapHiddenAttr && s.wrapDisplay === 'none' && !s.wrapVisible,
    JSON.stringify({ hidden: s.wrapHiddenAttr, display: s.wrapDisplay }));
  ok('O1-b aria 保持 HTML 静态值（orb 运行时未接管）',
    s.voiceBtnAria === 'Voice input', `aria=${JSON.stringify(s.voiceBtnAria)}`);
  const orb = await page.evaluate(async () => {
    const m = await import('/js/micOrb.js');
    return { singleton: m.getMicOrb() };
  });
  ok('O2 orb 单例不挂载（getMicOrb()=null ⇒ GL/轮询零占用）',
    orb.singleton === null, `singleton=${String(orb.singleton)}`);
  ok('O3 面 A 仍封存：overlay hidden + 无 .on',
    s.overlayHiddenAttr && !s.overlayOn && s.overlayDisplay === 'none',
    `hidden=${String(s.overlayHiddenAttr)} on=${String(s.overlayOn)}`);
  const vmode = await page.evaluate(async () => {
    const m = await import('/js/voiceMode.js');
    m.enterVoiceMode({ sendText: () => true, ensureDictationIdle: () => {}, getSessionId: () => 's1' });
    return m.isVoiceModeActive();
  });
  ok('O3-b enterVoiceMode 仍 no-op', vmode === false, `active=${String(vmode)}`);
  // O4 程序化点击兜底：气泡隐藏 ⇒ onVoiceToggle 的 voiceOrbEnabled 闸拦住
  await page.evaluate(() => document.getElementById('voice-btn')?.click());
  await sleep(300);
  const guard = await page.evaluate(() => ({
    cls: document.getElementById('input')?.classList.contains('voice-dictating') ?? false,
    recording: document.getElementById('voice-btn')?.classList.contains('recording') ?? false,
  }));
  ok('O4 程序化点击兜底：隐藏气泡 click 不启动听写（voiceOrb 闸）',
    !guard.cls && !guard.recording, JSON.stringify(guard));
  ok('O4-b 范围护栏（开关态）：header 语音输出开关仍未被触碰',
    s.voiceToggleAttached === true && s.voiceToggleHidden === false);
  ok('S3 零 page error', pageErrors.length === 0, pageErrors.join('; '));
  await orbOff.ctx.close();
}

await browser.close();
console.log(`\n${failures === 0 ? 'ALL PASS' : failures + ' FAILED'}`);
process.exit(failures ? 1 : 0);

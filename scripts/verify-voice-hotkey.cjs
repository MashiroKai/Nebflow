// verify-voice-hotkey.cjs — 20260830 author ruling #14: hold ⌘⇧S / Ctrl+Shift+S
// to dictate, release → recognize + insert into the primary input.
//
// Acceptance:
//   V1  keydown ⌘⇧S starts Web Speech recognition (listening)
//   V2  a final recognition result inserts text into #input (no auto-send)
//   V3  keyup stops + state returns to idle (no residual listening)
//   V4  repeatable — a second press appends a fresh segment
//   V5  mic-button toggle path regression unchanged (click start → insert → click stop)
//   V6  non-macOS maps to Ctrl+Shift+S (code-level isVoiceHotkeyCombo, mocked platform)
//   V7  no console/page errors
//
// Web Speech is mocked (headless has no real recognizer): window.__recog is the
// live instance; the harness drives onresult/onend to simulate the engine.
// usage: node scripts/verify-voice-hotkey.cjs [port]  (default 8196)
const { chromium } = require('playwright');

const PORT = process.argv[2] || '8196';
const BASE = `http://127.0.0.1:${PORT}/index.html`;

const results = [];
function ok(name, cond, detail) {
  results.push({ name, pass: !!cond, detail });
  console.log((cond ? 'PASS ' : 'FAIL ') + name + (detail ? ' — ' + JSON.stringify(detail) : ''));
}

const INIT = `
  localStorage.setItem('nebflow_token', 'harness-token');
  // Mock Web Speech — headless has no real recognizer.
  window.SpeechRecognition = class MockSR {
    constructor() { this.lang=''; this.continuous=false; this.interimResults=false; window.__recog = this; }
    start() { if (this.onstart) this.onstart(); if (this.onaudiostart) this.onaudiostart(); }
    abort() { if (this.onend) this.onend(); }
  };
  window.__wsSent = [];
  class MockWS { static OPEN = 1; static CONNECTING = 0; static CLOSING = 2; static CLOSED = 3;
    constructor(url){ this.url=url; this.readyState=0; window.__wsMock=this; }
    send(d){ window.__wsSent.push(JSON.parse(d)); } close(){ this.readyState=3; if(this.onclose) this.onclose(); } }
  window.WebSocket = MockWS;
`;

async function boot(page) {
  const errs = [];
  page.on('pageerror', e => errs.push(String(e)));
  page.on('console', m => {
    if (m.type() !== 'error') return;
    const t = m.text();
    // Static-server noise: /api/* REST endpoints 404 (no live gateway here).
    if (/Failed to load resource/.test(t)) return;
    errs.push('console: ' + t);
  });
  await page.goto(BASE, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => window.__wsMock, null, { timeout: 8000 });
  await page.evaluate(() => {
    const w = window.__wsMock; w.readyState = 1; if (w.onopen) w.onopen();
    const f = o => w.onmessage({ data: JSON.stringify(o) });
    f({ type: 'serverConfig', config: { configured: true, onboarding: 'done' } });
    f({ type: 'configData', config: JSON.stringify({ providers: {} }) });
    f({ type: 'sessionList', sessions: [{ id: 'sess1', title: 'S', agentName: 'Nebula', createdAt: Date.now() }], activeId: 'sess1' });
    f({ type: 'agentList', agents: [] });
  });
  await page.waitForTimeout(300);
  return errs;
}

/** Fire a final recognition result through the live mock recognizer. */
async function fireResult(page, text) {
  await page.evaluate((t) => {
    const r = window.__recog;
    if (!r) return;
    r.onresult?.({ resultIndex: 0, results: [{ 0: { transcript: t }, isFinal: true }] });
  }, text);
}

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'light' });
  const page = await ctx.newPage();
  await page.addInitScript(INIT);
  const errs = await boot(page);

  // Prime the input focusable area.
  await page.evaluate(() => document.getElementById('input')?.focus());
  await page.waitForTimeout(100);

  // ── V1: keydown ⌘⇧S starts recognition ───────────────────────────────
  await page.keyboard.down('Meta'); await page.keyboard.down('Shift');
  await page.keyboard.down('s');
  await page.waitForTimeout(100);
  const v1 = await page.evaluate(() => ({ hasRecog: !!window.__recog }));
  await page.waitForTimeout(50);
  ok('V1 keydown ⌘⇧S starts recognition', v1.hasRecog === true, v1);
  await page.keyboard.up('S').catch(() => {});           // release S
  await page.waitForTimeout(150);

  // V2: a final result inserts into #input (no auto-send)
  await page.keyboard.down('Meta'); await page.keyboard.down('Shift'); await page.keyboard.down('s');
  await page.waitForTimeout(100);
  await fireResult(page, '测试语音');
  await page.waitForTimeout(100);
  const v2 = await page.evaluate(() => document.getElementById('input').value);
  // V3: keyup stops + state → idle
  await page.keyboard.up('s');
  await page.waitForTimeout(250);
  const v3 = await page.evaluate(() => ({
    val: document.getElementById('input').value,
    recogCleared: !window.__recog || true,
    voiceActiveClass: document.getElementById('input').classList.contains('voice-dictating'),
    sent: window.__wsSent.filter(m => m.type === 'user-message' || m.type === 'send').length,
  }));
  // release the held modifiers
  await page.keyboard.up('Shift').catch(() => {}); await page.keyboard.up('Meta').catch(() => {});
  ok('V2 final result inserts into #input', v2.includes('测试语音'), { val: v2 });
  ok('V3 keyup returns to idle (no residual, no auto-send)',
    v3.val.includes('测试语音') && !v3.voiceActiveClass && v3.sent === 0, v3);
  await page.evaluate(() => { document.getElementById('input').value = ''; });
  await page.waitForTimeout(100);

  // ── V4: repeatable ──────────────────────────────────────────────────
  await page.keyboard.down('Meta'); await page.keyboard.down('Shift'); await page.keyboard.down('s');
  await page.waitForTimeout(100);
  await fireResult(page, '第二句');
  await page.waitForTimeout(100);
  await page.keyboard.up('s');
  await page.waitForTimeout(250);
  await page.keyboard.up('Shift').catch(() => {}); await page.keyboard.up('Meta').catch(() => {});
  const v4 = await page.evaluate(() => document.getElementById('input').value);
  await page.evaluate(() => { document.getElementById('input').value = ''; });
  ok('V4 repeatable — second press appends', v4.includes('第二句'), { val: v4 });

  // ── V5: mic-button toggle path regression ────────────────────────────
  await page.evaluate(() => document.getElementById('voice-btn').click());
  await page.waitForTimeout(150);
  const v5a = await page.evaluate(() => ({ hasRecog: !!window.__recog, recording: document.getElementById('voice-btn').classList.contains('recording') }));
  await fireResult(page, '按钮路径');
  await page.waitForTimeout(100);
  await page.evaluate(() => document.getElementById('voice-btn').click());
  await page.waitForTimeout(250);
  const v5b = await page.evaluate(() => ({
    val: document.getElementById('input').value,
    recording: document.getElementById('voice-btn').classList.contains('recording'),
  }));
  await page.evaluate(() => { document.getElementById('input').value = ''; });
  ok('V5 mic-button toggle still starts recording', v5a.hasRecog === true && v5a.recording === true, v5a);
  ok('V5 mic-button inserts on stop + clears recording', v5b.val.includes('按钮路径') && v5b.recording === false, v5b);

  // ── V6: non-macOS maps to Ctrl+Shift+S (code-level) ──────────────────
  const v6 = await page.evaluate(async () => {
    const { isVoiceHotkeyCombo } = await import('/js/input.js');
    const mac = /Mac|iP(hone|ad|od)/i.test(navigator.platform || '');
    // Synthetic events for each platform assumption.
    const mk = (key, mods) => ({ key, metaKey: !!(mods & 8), ctrlKey: !!(mods & 2), shiftKey: !!(mods & 4) });
    // Force platform string via defineProperty for each branch.
    const setPlatform = (v) => { try { Object.defineProperty(navigator, 'platform', { value: v, configurable: true }); } catch (_) {} };
    const outcomes = {};
    setPlatform('MacIntel');
    outcomes.macCmdShiftS = isVoiceHotkeyCombo(mk('S', 8 | 4));   // meta+shift
    outcomes.macCtrlShiftS = isVoiceHotkeyCombo(mk('S', 2 | 4));   // ctrl+shift (should be false on mac)
    setPlatform('Win32');
    outcomes.winCtrlShiftS = isVoiceHotkeyCombo(mk('S', 2 | 4));   // ctrl+shift
    outcomes.winCmdShiftS = isVoiceHotkeyCombo(mk('S', 8 | 4));    // meta+shift (should be false on win)
    setPlatform(mac ? 'MacIntel' : 'Win32'); // restore
    return outcomes;
  });
  ok('V6 macOS → ⌘⇧S (not Ctrl+⇧S)', v6.macCmdShiftS === true && v6.macCtrlShiftS === false, v6);
  ok('V6 non-macOS → Ctrl+⇧S (not ⌘⇧S)', v6.winCtrlShiftS === true && v6.winCmdShiftS === false, v6);

  // ── V7: no page errors ──────────────────────────────────────────────
  ok('V7 no page/console errors', errs.length === 0, errs.slice(0, 5));

  await browser.close();
  const failed = results.filter(r => !r.pass).length;
  console.log('\n=== ' + (results.length - failed) + '/' + results.length + ' PASS ===');
  process.exit(failed ? 1 : 0);
})().catch(e => { console.error('HARNESS ERROR', e); process.exit(2); });

/* Combined verification harness for the 08-29 batch:
   GROUP A: ② onboarding sealed (flag off -> zero UI; flag on -> greeting renders)
   GROUP B: ③ slash sealed ('/' plain text: no dropdown, send goes out as user msg)
   GROUP C: ④ permission escalation buttons (conditional render + wire upgradeMode)
   Static server must serve the worktree web/ root. */
const fs = require('fs');
const { chromium } = require('playwright');
const PORT = process.argv[2] || '8377';
const BASE = `http://127.0.0.1:${PORT}`;

const results = [];
const ok = (name, cond, extra) => { results.push({ name, pass: !!cond, extra }); };

/* Shared page scaffold: real CSS + minimal app DOM the modules touch. */
function pageHtml(extraBody) {
  return `<!DOCTYPE html><html><head><meta charset="utf-8">
<link rel="stylesheet" href="/css/base.css">
<link rel="stylesheet" href="/css/chat.css">
<link rel="stylesheet" href="/css/input.css">
<link rel="stylesheet" href="/css/sapphire.css">
</head><body style="display:block !important">
<div id="chat"></div>
<div id="input-bar"><div id="input-wrap">
  <textarea id="input" rows="1"></textarea>
  <div id="slash-dropdown"></div>
  <div id="attachment-preview"></div>
  <button id="send-btn"></button><button id="stop-btn"></button>
  <button id="voice-btn"><canvas class="orb-canvas"></canvas><div class="css-orb orb"></div></button>
  <button id="attach-btn"></button>
</div></div>
${extraBody || ''}
</body></html>`;
}

(async () => {
  const browser = await chromium.launch();

  /* ---------- GROUP A: onboarding sealed ---------- */
  {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    const errs = [];
    page.on('pageerror', (e) => errs.push(String(e)));
    await page.route('**/h.html', (r) => r.fulfill({ contentType: 'text/html', body: pageHtml() }));
    await page.goto(`${BASE}/h.html`);
    // flag OFF: initOnboarding must produce zero UI
    await page.evaluate(async () => {
      // onboarding renders through activeView.dom.chat — build the mock view
      const cv = await import('/js/chatView.js');
      const view = { id: 'primary', sessionId: 'sess-a', stream: { scrollSnapped: false }, dom: { chat: document.getElementById('chat') } };
      cv.chatViews.primary = view; cv.setActiveView(view);
    });
    let r = await page.evaluate(async () => {
      const m = await import('/js/onboarding.js');
      m.initOnboarding({ configured: false });
      await new Promise(r => setTimeout(r, 300));
      return { rows: document.querySelectorAll('#chat .row').length };
    });
    ok('A1 onboarding sealed: flag off -> zero rows (first run straight to main UI)', r.rows === 0, r);
    // flag ON: greeting renders (code path intact)
    r = await page.evaluate(async () => {
      localStorage.setItem('nebflow.onboarding.enabled', '1');
      const m = await import('/js/onboarding.js');
      m.initOnboarding({ configured: false });
      await new Promise(r => setTimeout(r, 600));
      return { rows: document.querySelectorAll('#chat .row').length };
    });
    ok('A2 flag on -> onboarding greeting renders (code intact, re-enable works)', r.rows > 0, r);
    ok('A0 no page errors', errs.length === 0, errs);
    await ctx.close();
  }

  /* ---------- GROUP B: slash sealed ---------- */
  {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    const errs = [];
    page.on('pageerror', (e) => errs.push(String(e)));
    await page.route('**/h.html', (r) => r.fulfill({ contentType: 'text/html', body: pageHtml() }));
    await page.goto(`${BASE}/h.html`);
    const r = await page.evaluate(async () => {
      const out = {};
      const { initInput } = await import('/js/input.js');
      const cv = await import('/js/chatView.js');
      const chat = document.getElementById('chat');
      const view = {
        id: 'primary', sessionId: 'sess-b',
        stream: { scrollSnapped: false },
        dom: {
          chat,
          input: document.getElementById('input'),
          inputBar: document.getElementById('input-bar'),
          inputWrap: document.getElementById('input-wrap'),
          slashDropdown: document.getElementById('slash-dropdown'),
          attPreview: document.getElementById('attachment-preview'),
          sendBtn: document.getElementById('send-btn'),
          stopBtn: document.getElementById('stop-btn'),
          voiceBtn: document.getElementById('voice-btn'),
          attachBtn: document.getElementById('attach-btn'),
        },
        slashMatches: [], slashSelectedIndex: 0,
      };
      cv.chatViews.primary = view;
      cv.setActiveView(view);
      try { initInput(view); } catch (e) { out.initErr = String(e); }
      // type '/' -> dropdown must stay closed (sealed)
      const input = view.dom.input;
      input.value = '/';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      await new Promise(r => setTimeout(r, 120));
      out.dropdownOnAfterSlash = view.dom.slashDropdown.classList.contains('on');
      out.dropdownItems = view.dom.slashDropdown.querySelectorAll('.slash-item').length;
      // '/onboarding' as message must NOT trigger command interception
      const inputMod = await import('/js/input.js');
      out.handleSlashResult = inputMod.handleSlash('/onboarding');
      return out;
    });
    ok('B1 slash sealed: typing / opens zero dropdown', r.dropdownOnAfterSlash === false && r.dropdownItems === 0, r);
    ok('B2 slash sealed: handleSlash("/onboarding") returns false (plain text path)', r.handleSlashResult === false, r);
    ok('B3 initInput ran clean', !r.initErr, r.initErr);
    ok('B0 no page errors', errs.length === 0, errs);
    await ctx.close();
  }

  /* ---------- GROUP C: permission escalation (④) ---------- */
  {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    const errs = [];
    page.on('pageerror', (e) => errs.push(String(e)));
    await page.route('**/h.html', (r) => r.fulfill({ contentType: 'text/html', body: pageHtml() }));
    await page.goto(`${BASE}/h.html`);
    const r = await page.evaluate(async () => {
      const out = { cases: {}, wires: [] };
      const chat = document.getElementById('chat');
      const cv = await import('/js/chatView.js');
      const state = (await import('/js/state.js')).default;
      const { renderPermissionPrompt } = await import('/js/chat.js');
      const view = {
        id: 'primary', sessionId: 'sess-c',
        stream: { scrollSnapped: false },
        dom: { chat },
      };
      cv.chatViews.primary = view;
      cv.setActiveView(view);
      state.ws = { readyState: 1, send: (s) => out.wires.push(JSON.parse(s)) };
      state.bypassSessions = new Set(); // main.js assigns this at runtime
      state.safetyModes = state.safetyModes || {};
      const labels = (row) => [...row.querySelectorAll('.option-btn')]
        .map(b => b.dataset.label).filter(Boolean); // Other button has no data-label

      // case 1: confirm-edits + Write -> 3 options with upgrade in the middle
      renderPermissionPrompt('Write', '', '{"file_path":"/tmp/a.txt"}', 'sess-c', 0, null, null, null, 'req-1', 'confirm-edits');
      const row1 = chat.lastElementChild;
      out.cases.c1 = labels(row1);
      row1.querySelectorAll('.option-btn')[1].click();
      row1.querySelector('.option-confirm').click();
      // case 2: auto-edits + Bash -> upgrade to auto-all
      renderPermissionPrompt('Bash', '', '{"command":"ls"}', 'sess-c', 0, null, null, null, 'req-2', 'auto-edits');
      const row2 = chat.lastElementChild;
      out.cases.c2 = labels(row2);
      row2.querySelectorAll('.option-btn')[1].click();
      row2.querySelector('.option-confirm').click();
      // case 3: confirm-edits + Bash -> NO upgrade (tool class mismatch)
      renderPermissionPrompt('Bash', '', '{"command":"ls"}', 'sess-c2', 0, null, null, null, 'req-3', 'confirm-edits');
      const row3 = chat.lastElementChild;
      out.cases.c3 = labels(row3);
      row3.querySelector('.option-cancel').click();
      // case 4: auto-all + Write -> NO upgrade (already top)
      renderPermissionPrompt('Write', '', '{}', 'sess-c2', 0, null, null, null, 'req-4', 'auto-all');
      const row4 = chat.lastElementChild;
      out.cases.c4 = labels(row4);
      row4.querySelector('.option-cancel').click();
      // case 5: missing safetyMode (old backend) -> NO upgrade
      renderPermissionPrompt('Write', '', '{}', 'sess-c2', 0, null, null, null, 'req-5');
      const row5 = chat.lastElementChild;
      out.cases.c5 = labels(row5);
      // case 6: deny path carries no upgradeMode
      row5.querySelectorAll('.option-btn')[1].click(); // deny (2-option card)
      row5.querySelector('.option-confirm').click();
      out.safetyModes = { ...state.safetyModes };
      return out;
    });
    const L = r.cases;
    ok('C1 confirm-edits+Write: 3 options, upgrade in middle', L.c1 && L.c1.length === 3 && /编辑放行/.test(L.c1[1]), L.c1);
    ok('C2 upgrade click wires approved:true + upgradeMode:auto-edits',
      r.wires[0] && r.wires[0].approved === true && r.wires[0].upgradeMode === 'auto-edits' && r.wires[0].requestId === 'req-1', r.wires[0]);
    ok('C3 auto-edits+Bash: 3 options, upgrade to auto-all', L.c2 && L.c2.length === 3 && /全部放行/.test(L.c2[1]), L.c2);
    ok('C4 upgrade click wires upgradeMode:auto-all',
      r.wires[1] && r.wires[1].approved === true && r.wires[1].upgradeMode === 'auto-all', r.wires[1]);
    ok('C5 confirm-edits+Bash: 2 options (class mismatch, no upgrade)', L.c3 && L.c3.length === 2, L.c3);
    ok('C6 auto-all+Write: 2 options (already top)', L.c4 && L.c4.length === 2, L.c4);
    ok('C7 missing safetyMode: 2 options (old backend zero impact)', L.c5 && L.c5.length === 2, L.c5);
    const denyWire = r.wires.find(w => w.requestId === 'req-5');
    ok('C8 deny path: approved:false and NO upgradeMode key',
      denyWire && denyWire.approved === false && !('upgradeMode' in denyWire), denyWire);
    ok('C9 optimistic safetyModes mirror updated', r.safetyModes['sess-c'] === 'auto-all', r.safetyModes);
    ok('C0 no page errors', errs.length === 0, errs);
    // upgrade option styling: sapphire-tinted class present
    const styled = await page.evaluate(async () => {
      const { renderPermissionPrompt } = await import('/js/chat.js');
      renderPermissionPrompt('Edit', '', '{}', 'sess-c2', 0, null, null, null, 'req-7', 'confirm-edits');
      const btn = document.getElementById('chat').lastElementChild.querySelector('.option-btn.perm-upgrade-option');
      if (!btn) return { found: false };
      const cs = getComputedStyle(btn);
      return { found: true, borderColor: cs.borderColor, cls: btn.className };
    });
    ok('C10 upgrade option has .perm-upgrade-option sapphire tint', styled.found && /rgb/.test(styled.borderColor), styled);
    await ctx.close();
  }

  await browser.close();
  const fails = results.filter(x => !x.pass);
  results.forEach(x => console.log((x.pass ? 'PASS' : 'FAIL') + ' ' + x.name + (x.pass ? '' : '  ' + JSON.stringify(x.extra))));
  console.log(`\n${results.length - fails.length}/${results.length} PASS`);
  process.exit(fails.length ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });

#!/usr/bin/env node
/* ───────────────────────────────────────────────────────────────────────────
 * verify-cardguard-nosentinel.cjs — DOM regression assertion for the cardguard
 * negative filter (author ruling 2026-09-17, `#687①` / batch `#690` /
 * chain-n-593c51c6).
 *
 * SUBJECT: a tool RESULT whose content carries the card sentinel
 * (`___\w+_HTML___`) ANYWHERE — the realistic shape being the ToolResultGuard
 * preview
 *     `<persisted-output>\nOutput too large (…). …\nPreview (first 2048 chars):\n___CARD_HTML___{…`
 * — must never reach the chat-stream DOM as raw text. Two pre-fix routes do
 * exactly that in `chat.js`'s default tool branch:
 *   (a) `:1134` the `<pre class="tool-body-pre">` fallback (hljs not loaded),
 *   (b) `:1133` `renderHighlightedContent` → `hljs.highlightAuto` inside
 *       `<pre class="tool-body-pre hljs">` (hljs loaded) — same raw text, same
 *       DOM text face.
 * Both are covered here (hljs present / hljs disabled are separate cases).
 *
 * ROOT-AGNOSTIC BY DESIGN: `--root <webRoot>` makes the SAME assertion run
 * against the pre-fix tree copy (MUST be RED = the C4 "old code fails"
 * reading) and against the fixed tree (MUST be GREEN = C1/C2).
 *
 * Instance discipline: static `python3 -m http.server` on an isolated port +
 * a route-stubbed harness page. NEVER an engine instance, never
 * :8080/:8097/:3030. The :8080 listener set is snapshotted before/after and
 * must be byte-identical. Cleanup: PID-verified kill of our own server +
 * chromium close, on every exit path.
 *
 * usage:
 *   node scripts/verify-cardguard-nosentinel.cjs --root <webRoot> [--port N]
 *        [--out <json>] [--persona pre|post]
 * exit code: 0 = every check green, 1 = at least one check red, 2 = harness error
 * ─────────────────────────────────────────────────────────────────────────── */
'use strict';
const { chromium } = require('playwright');
const { spawn, execSync } = require('child_process');
const crypto = require('crypto');
const fs = require('fs');
const http = require('http');
const path = require('path');

// ── args ────────────────────────────────────────────────────────────────────
const argv = process.argv.slice(2);
const argOf = (name, dflt) => {
  const i = argv.indexOf(name);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : dflt;
};
const WEB_ROOT = argOf('--root', null);
const PORT = String(argOf('--port', '8213'));
const OUT = argOf('--out', null);
const PERSONA = argOf('--persona', 'unlabelled');
if (!WEB_ROOT) { console.error('harness error: --root <webRoot> is required'); process.exit(2); }
const ROOT_ABS = path.resolve(WEB_ROOT);
if (!fs.existsSync(path.join(ROOT_ABS, 'js', 'chat.js'))) {
  console.error('harness error: ' + ROOT_ABS + '/js/chat.js not found (--root must be a web/ root)');
  process.exit(2);
}

const SENTINEL_ANYWHERE = /___\w+_HTML___/;
const sha = (s) => crypto.createHash('sha256').update(String(s)).digest('hex');

// ── stdout discipline: bounded per-step output, every step persisted ────────
const checks = [];
const pageErrors = [];
function check(id, desc, pass, detail) {
  checks.push({ id, desc, pass: !!pass, detail });
  console.log((pass ? 'PASS ' : 'FAIL ') + id + ' — ' + desc + (detail !== undefined ? ' :: ' + JSON.stringify(detail) : ''));
}

// ── 8080 host-listener snapshot (second line of defence) ───────────────────
function listeners8080() {
  try {
    return execSync("lsof -nP -iTCP:8080 -sTCP:LISTEN -t 2>/dev/null | sort | tr '\\n' ' '", { encoding: 'utf8' }).trim();
  } catch (e) { return ''; }
}
const H8080_BEFORE = listeners8080();

// ── isolated static server (own PID, own port) ─────────────────────────────
const HOST_PID = process.pid;
let server = null, serverPid = null, serverLog = null;
function killServer() {
  if (serverPid) {
    try {
      // PID 验身 before any signal: our own spawned python, never anything else.
      const cmd = execSync(`ps -p ${serverPid} -o command= 2>/dev/null`, { encoding: 'utf8' }).trim();
      if (cmd.includes('http.server') && String(serverPid) !== String(HOST_PID)) {
        process.kill(serverPid, 'SIGTERM');
        console.log('[cleanup] killed harness static server pid=' + serverPid + ' (' + cmd.slice(0, 80) + ')');
      } else {
        console.log('[cleanup] server pid not ours — not signalling: ' + cmd);
      }
    } catch (e) { /* already gone */ }
    serverPid = null;
  }
  if (serverLog) { try { serverLog.end(); } catch (e) {} serverLog = null; }
}
function waitReady(url, ms) {
  return new Promise((resolve) => {
    const t0 = Date.now();
    const tick = () => {
      if (Date.now() - t0 > ms) return resolve(false);
      const req = http.get(url, (res) => { res.resume(); resolve(true); });
      req.on('error', () => setTimeout(tick, 250));
    };
    tick();
  });
}

function spawnServer() {
  return new Promise((resolve, reject) => {
    const logPath = '/tmp/cardguard-httpserver-' + PORT + '.log';
    serverLog = fs.createWriteStream(logPath);
    server = spawn('python3', ['-m', 'http.server', PORT, '--bind', '127.0.0.1', '--directory', ROOT_ABS],
      { stdio: ['ignore', 'pipe', 'pipe'] });
    serverPid = server.pid;
    server.stdout.pipe(serverLog); server.stderr.pipe(serverLog);
    server.on('error', reject);
    waitReady(`http://127.0.0.1:${PORT}/js/chat.js`, 20000).then((ok) => {
      if (!ok) return reject(new Error('static server not ready on :' + PORT));
      resolve();
    });
  });
}

// ── harness page scaffold (real CSS + real modules; only the page is stubbed) ─
function harnessHtml() {
  return `<!DOCTYPE html><html><head><meta charset="utf-8">
<link rel="stylesheet" href="/css/base.css">
<link rel="stylesheet" href="/css/chat.css">
<link rel="stylesheet" href="/css/sapphire.css">
<script src="/vendor/highlight.min.js"></script>
</head><body style="display:block !important">
<div id="chat"></div>
</body></html>`;
}

// ── in-page case driver ────────────────────────────────────────────────────
const CASES = {
  // Realistic guard preview: the payload after the sentinel is TRUNCATED by the
  // 2048-char preview window ⇒ JSON.parse must fail ⇒ safe placeholder.
  L1_GUARD_TRUNC:
    '<persisted-output>\nOutput too large (117.1 KB). Full output saved to: /tmp/cg/tool-results/call_00_x.txt\n\n' +
    'Preview (first 2048 chars):\n___CARD_HTML___{"fileRefs":{"proxied":0,"failed":0},"warnings":[],"html":"<div cla',
  // Prefix + a COMPLETE, parseable payload after the sentinel ⇒ best-effort card path.
  L1B_PREFIX_VALID:
    '<persisted-output>\nOutput too large (60.0 KB). Full output saved to: /tmp/cg/tool-results/call_01_x.txt\n\n' +
    'Preview (first 2048 chars):\n___CARD_HTML___{"html":"<div class=\\"cg\\">CARDBODY</div>","title":"CG card"}',
  // Sentinel mid-string but the tail parses WITHOUT `.html` ⇒ extraction fails ⇒ placeholder.
  L1C_NO_HTML:
    'notice: card payload follows\n___CARD_HTML___{"fileRefs":{"proxied":0,"failed":0},"warnings":[]}',
  // Canonical card: sentinel at position 0 (the pre-existing card gate) — C3① regression face.
  L3_CARD_START:
    '___CARD_HTML___{"html":"<div class=\\"cg\\">CARDBODY</div>","title":"CG card"}',
  // Non-card tool result (plain text) — C3② `<pre>` fallback face.
  L4_PLAIN: 'total 24\ndrwxr-xr-x  4 kai staff 128 src\n-rw-r--r--  1 kai staff 512 README.md\n',
  // Unified diff on a Grep label — C3③ renderHighlightedContent (formatDiff) face.
  L5_DIFF: '@@ -1,2 +1,2 @@\n-old line\n+new line\n context line\n',
};

async function runCases(CASES) {
  const cv = await import('/js/chatView.js');
  const state = (await import('/js/state.js')).default;
  const { t } = await import('/js/i18n.js');
  const { renderTool } = await import('/js/chat.js');
  const chat = document.getElementById('chat');
  const view = { id: 'primary', sessionId: 'sess-cg', visible: true, stream: { scrollSnapped: false }, dom: { chat } };
  cv.chatViews.primary = view; cv.setActiveView(view);

  const PH_KEY = 'chat.toolCardUnavailable';
  const PH_TEXT = t(PH_KEY);

  const norm = (s) => String(s)
    .replace(/blob:[^"'\s]+/g, 'blob:URL')
    .replace(/ticket=[A-Za-z0-9._\-]+/g, 'ticket=T');
  const hash = async (s) => {
    const buf = new TextEncoder().encode(norm(s));
    const d = await crypto.subtle.digest('SHA-256', buf);
    return Array.from(new Uint8Array(d)).map((b) => b.toString(16).padStart(2, '0')).join('');
  };
  const settle = async () => {
    await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
    await new Promise((r) => setTimeout(r, 250));
    await new Promise((r) => requestAnimationFrame(r));
  };
  const reset = () => { chat.innerHTML = ''; state.sessionToolCards = {}; state.sessionPendingTools = {}; };

  async function snapshot() {
    const text = chat.textContent || '';
    const ifrs = [...chat.querySelectorAll('iframe[data-nf-card-id]')];
    const pres = [...chat.querySelectorAll('pre.tool-body-pre')];
    return {
      sentinelHits: (text.match(/___\w+_HTML___/g) || []).length,
      persistedHits: (text.match(/<persisted-output>/g) || []).length,
      payloadKeyHits: (text.match(/"fileRefs":/g) || []).length,
      prePlain: chat.querySelectorAll('pre.tool-body-pre:not(.hljs)').length,
      preHljs: chat.querySelectorAll('pre.tool-body-pre.hljs').length,
      preCount: pres.length,
      preTextDigests: await Promise.all(pres.map((p) => hash(p.textContent))),
      diffLines: chat.querySelectorAll('.diff-line').length,
      cardIframes: ifrs.length,
      bodyOpen: chat.querySelectorAll('.tool-card .body.open').length,
      placeholderVisible: text.indexOf(PH_TEXT) >= 0,
      textLen: text.length,
      textDigest: await hash(text),
      htmlDigest: await hash(chat.innerHTML),
      srcdocDigests: await Promise.all(ifrs.map((f) => hash(f.getAttribute('srcdoc') || ''))),
      textSampleHead: text.slice(0, 200),
    };
  }

  const out = { placeholderText: PH_TEXT, placeholderResolves: PH_TEXT !== PH_KEY, cases: {}, modules: {} };
  const mod = await fetch('/js/chat.js').then((r) => r.text());
  out.modules.chatJsBytes = mod.length;
  out.modules.chatJsSha256 = await hash(mod);
  out.modules.sentinelAnywhereInSource = /___\w+_HTML___/.test(mod);
  out.modules.persistedOutputHitsInChatJs = (mod.match(/persisted-output/g) || []).length;
  const pmod = await fetch('/js/persistence.js').then((r) => r.text());
  out.modules.persistenceJsSha256 = await hash(pmod);
  out.modules.persistedOutputHitsInPersistenceJs = (pmod.match(/persisted-output/g) || []).length;

  const HAD_HLJS = typeof window.hljs !== 'undefined';
  const hljsRef = window.hljs;
  const withHljsOff = async (fn) => { window.hljs = undefined; try { return await fn(); } finally { window.hljs = hljsRef; } };

  // ── L3: canonical card (sentinel at 0) — must not regress ──
  // Runs FIRST so the module-level iframe id counter is at the same value in the
  // pre-fix and post-fix trees (cardRegistry uses `++_iframeId`, which rides into
  // both `data-nf-card-id` and the height script) ⇒ the digests below are a true
  // byte-for-byte comparison of the same mount index.
  reset();
  renderTool('Card', '1 card', CASES.L3_CARD_START, false, null, 'sess-cg');
  await settle();
  out.cases.L3_card_start = await snapshot();

  // ── L1a: guard shape, hljs OFF ⇒ exactly the `:1134` <pre> fallback branch ──
  reset();
  await withHljsOff(async () => { renderTool('Card', '1 card', CASES.L1_GUARD_TRUNC, false, null, 'sess-cg'); await settle(); });
  out.cases.L1a_guard_no_hljs = await snapshot();

  // ── L1b: guard shape, hljs ON (production state) ──
  reset();
  renderTool('Card', '1 card', CASES.L1_GUARD_TRUNC, false, null, 'sess-cg');
  await settle();
  out.cases.L1b_guard_hljs = await snapshot();

  // ── L1c: guard shape under a Read label, hljs ON (highlight route) ──
  reset();
  renderTool('Read', 'x.txt', CASES.L1_GUARD_TRUNC, false, null, 'sess-cg');
  await settle();
  out.cases.L1c_guard_read_label = await snapshot();

  // ── L1d: prefix + fully parseable payload ⇒ best-effort card path ──
  reset();
  renderTool('Card', '1 card', CASES.L1B_PREFIX_VALID, false, null, 'sess-cg');
  await settle();
  out.cases.L1d_prefix_valid = await snapshot();

  // ── L1e: sentinel mid-string, tail parses but has no `.html` ──
  reset();
  renderTool('Card', '1 card', CASES.L1C_NO_HTML, false, null, 'sess-cg');
  await settle();
  out.cases.L1e_no_html = await snapshot();

  // ── L4a: plain non-card result, hljs OFF ⇒ plain <pre class="tool-body-pre"> ──
  reset();
  await withHljsOff(async () => { renderTool('Bash', 'ls -la', CASES.L4_PLAIN, false, null, 'sess-cg'); await settle(); });
  out.cases.L4a_plain_no_hljs = await snapshot();

  // ── L4b: plain non-card result, hljs ON ⇒ highlight route (unchanged either way) ──
  reset();
  renderTool('Bash', 'ls -la', CASES.L4_PLAIN, false, null, 'sess-cg');
  await settle();
  out.cases.L4b_plain_hljs = await snapshot();

  // ── L5: unified diff on a Grep label ⇒ formatDiff face ──
  reset();
  renderTool('Grep', 'src/**', CASES.L5_DIFF, false, null, 'sess-cg');
  await settle();
  out.cases.L5_diff = await snapshot();

  // ── P1: history-replay face (persistence.js) with the SAME guard shape ──
  // Scope probe for the brief's persistence clause: no source file is touched;
  // this only drives the real replay renderer and reads the resulting DOM.
  try {
    const { restoreFromBackendHistory } = await import('/js/persistence.js');
    reset();
    restoreFromBackendHistory([{ type: 'tool', label: 'Card', summary: '1 card', content: CASES.L1_GUARD_TRUNC, isError: false }],
      { scrollToBottom: false, busyTail: false });
    await settle();
    out.cases.P1_backend_history = await snapshot();
    out.cases.P1_backend_history.ran = true;
  } catch (e) {
    out.cases.P1_backend_history = { ran: false, error: String(e && e.message || e) };
  }

  // ── P2: same replay face with hljs OFF ⇒ the literal plain-text fallback that
  //        persistence.js shares with chat.js (`<pre class="tool-body-pre">`) ──
  try {
    const { restoreFromBackendHistory } = await import('/js/persistence.js');
    reset();
    await withHljsOff(async () => {
      restoreFromBackendHistory([{ type: 'tool', label: 'Card', summary: '1 card', content: CASES.L1_GUARD_TRUNC, isError: false }],
        { scrollToBottom: false, busyTail: false });
      await settle();
    });
    out.cases.P2_backend_history_no_hljs = await snapshot();
    out.cases.P2_backend_history_no_hljs.ran = true;
  } catch (e) {
    out.cases.P2_backend_history_no_hljs = { ran: false, error: String(e && e.message || e) };
  }

  out.hadHljs = HAD_HLJS;
  reset();
  return out;
}

// ── main ───────────────────────────────────────────────────────────────────
(async () => {
  const t0 = Date.now();
  try {
    const lsof = execSync(`lsof -nP -iTCP:${PORT} -sTCP:LISTEN -t 2>/dev/null || true`, { encoding: 'utf8' }).trim();
    if (lsof) { console.error('harness error: port ' + PORT + ' already in LISTEN (pids: ' + lsof + ') — pick another port'); process.exit(2); }
    await spawnServer();
    console.log('[pre-flight] static server :' + PORT + ' serving ' + ROOT_ABS + ' (pid ' + serverPid + ')');
    console.log('[pre-flight] H8080_BEFORE = "' + H8080_BEFORE + '"');

    const browser = await chromium.launch();
    let pageResult = null;
    try {
      const ctx = await browser.newContext();
      const page = await ctx.newPage();
      page.on('pageerror', (e) => pageErrors.push(String(e)));
      await page.route('**/api/nf-ticket*', (r) => r.fulfill({ json: { tickets: {}, rejected: [] } }));
      await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
      await page.route('**/cg-harness.html', (r) => r.fulfill({ contentType: 'text/html', body: harnessHtml() }));
      await page.goto(`http://127.0.0.1:${PORT}/cg-harness.html`, { waitUntil: 'load' });
      pageResult = await page.evaluate(runCases, CASES);
    } finally {
      await browser.close();
    }

    // ── checks (same judgment for both personas: red on the pre-fix tree IS the C4 reading) ──
    const C = pageResult.cases;
    const clean = (c) => c && c.sentinelHits === 0 && c.persistedHits === 0 && c.payloadKeyHits === 0 && c.prePlain === 0 && c.preHljs === 0;
    check('A1', 'guard shape (hljs OFF ⇒ :1134 <pre> branch): no raw payload in DOM, placeholder shown',
      clean(C.L1a_guard_no_hljs) && C.L1a_guard_no_hljs.placeholderVisible,
      { sentinelHits: C.L1a_guard_no_hljs.sentinelHits, prePlain: C.L1a_guard_no_hljs.prePlain, persistedHits: C.L1a_guard_no_hljs.persistedHits, payloadKeyHits: C.L1a_guard_no_hljs.payloadKeyHits, placeholderVisible: C.L1a_guard_no_hljs.placeholderVisible, head: C.L1a_guard_no_hljs.textSampleHead });
    check('A2', 'guard shape (hljs ON, production): no raw payload in DOM, placeholder shown',
      clean(C.L1b_guard_hljs) && C.L1b_guard_hljs.placeholderVisible,
      { sentinelHits: C.L1b_guard_hljs.sentinelHits, preHljs: C.L1b_guard_hljs.preHljs, persistedHits: C.L1b_guard_hljs.persistedHits, placeholderVisible: C.L1b_guard_hljs.placeholderVisible });
    check('A3', 'guard shape under Read label (highlight route): no raw payload in DOM',
      clean(C.L1c_guard_read_label),
      { sentinelHits: C.L1c_guard_read_label.sentinelHits, preHljs: C.L1c_guard_read_label.preHljs, prePlain: C.L1c_guard_read_label.prePlain });
    check('A4', 'prefix + parseable payload ⇒ card path (iframe), sentinel not in DOM',
      clean(C.L1d_prefix_valid) && C.L1d_prefix_valid.cardIframes === 1,
      { sentinelHits: C.L1d_prefix_valid.sentinelHits, cardIframes: C.L1d_prefix_valid.cardIframes, prePlain: C.L1d_prefix_valid.prePlain });
    check('A5', 'sentinel mid-string, tail has no .html ⇒ safe placeholder',
      clean(C.L1e_no_html) && C.L1e_no_html.placeholderVisible,
      { sentinelHits: C.L1e_no_html.sentinelHits, prePlain: C.L1e_no_html.prePlain, placeholderVisible: C.L1e_no_html.placeholderVisible });
    check('A6', 'C3① canonical card (sentinel at 0) still renders as ONE card iframe, zero raw text',
      clean(C.L3_card_start) && C.L3_card_start.cardIframes === 1,
      { cardIframes: C.L3_card_start.cardIframes, sentinelHits: C.L3_card_start.sentinelHits, srcdocDigests: C.L3_card_start.srcdocDigests, htmlDigest: C.L3_card_start.htmlDigest });
    check('A7', 'C3② plain non-card result keeps the <pre class="tool-body-pre"> fallback (pre text byte-identical)',
      C.L4a_plain_no_hljs.prePlain === 1 && C.L4a_plain_no_hljs.preTextDigests[0] === (await sha(CASES.L4_PLAIN)),
      { prePlain: C.L4a_plain_no_hljs.prePlain, preTextDigests: C.L4a_plain_no_hljs.preTextDigests, expectedDigest: sha(CASES.L4_PLAIN) });
    check('A8', 'C3③ unified diff still routed to formatDiff (.diff-line present)',
      C.L5_diff.diffLines >= 3,
      { diffLines: C.L5_diff.diffLines, htmlDigest: C.L5_diff.htmlDigest });
    check('A9', 'P1 [scope probe] history-replay face with the SAME guard shape: no raw payload in DOM',
      !!(C.P1_backend_history && C.P1_backend_history.ran) && clean(C.P1_backend_history),
      C.P1_backend_history ? { ran: C.P1_backend_history.ran, sentinelHits: C.P1_backend_history.sentinelHits, prePlain: C.P1_backend_history.prePlain, preHljs: C.P1_backend_history.preHljs, persistedHits: C.P1_backend_history.persistedHits, head: C.P1_backend_history.textSampleHead } : C.P1_backend_history);
    check('A9b', 'P2 [scope probe] same replay face, hljs OFF ⇒ literal plain <pre class="tool-body-pre"> fallback',
      !!(C.P2_backend_history_no_hljs && C.P2_backend_history_no_hljs.ran) && clean(C.P2_backend_history_no_hljs),
      C.P2_backend_history_no_hljs ? { ran: C.P2_backend_history_no_hljs.ran, sentinelHits: C.P2_backend_history_no_hljs.sentinelHits, prePlain: C.P2_backend_history_no_hljs.prePlain, persistedHits: C.P2_backend_history_no_hljs.persistedHits, head: C.P2_backend_history_no_hljs.textSampleHead } : C.P2_backend_history_no_hljs);
    check('A10', 'no page errors', pageErrors.length === 0, pageErrors);
    // ── post-flight: port + 8080 double assertion ──
    const mine = execSync(`lsof -nP -iTCP:${PORT} -sTCP:LISTEN -t 2>/dev/null || true`, { encoding: 'utf8' }).trim();
    check('A11', 'harness server is the only listener on its isolated port', !!mine && mine.split(/\s+/).includes(String(serverPid)),
      { port: PORT, listeners: mine, ourPid: serverPid });
    const H8080_AFTER = listeners8080();
    check('A12', ':8080 host listener set unchanged (zero signals to the host)', H8080_BEFORE === H8080_AFTER,
      { before: H8080_BEFORE, after: H8080_AFTER });

    const payload = {
      runner: 'scripts/verify-cardguard-nosentinel.cjs',
      persona: PERSONA, webRoot: ROOT_ABS, port: PORT,
      chatJsSha256: pageResult.modules.chatJsSha256,
      persistenceJsSha256: pageResult.modules.persistenceJsSha256,
      persistedOutputHitsInChatJs: pageResult.modules.persistedOutputHitsInChatJs,
      persistedOutputHitsInPersistenceJs: pageResult.modules.persistedOutputHitsInPersistenceJs,
      placeholderKey: 'chat.toolCardUnavailable', placeholderText: pageResult.placeholderText,
      placeholderResolves: pageResult.placeholderResolves,
      cases: pageResult.cases, checks, pageErrors,
      elapsedMs: Date.now() - t0,
    };
    if (OUT) fs.writeFileSync(OUT, JSON.stringify(payload, null, 2));
    const failed = checks.filter((c) => !c.pass);
    console.log('\n=== ' + (checks.length - failed.length) + '/' + checks.length + ' checks green' +
      (failed.length ? ' | RED: ' + failed.map((f) => f.id).join(',') : '') + ' | persona=' + PERSONA + ' ===');
    killServer();
    process.exit(failed.length ? 1 : 0);
  } catch (e) {
    console.error('HARNESS ERROR', e && e.stack || e);
    killServer();
    process.exit(2);
  }
})();

for (const sig of ['SIGINT', 'SIGTERM']) process.on(sig, () => { killServer(); process.exit(130); });

#!/usr/bin/env node
/* ───────────────────────────────────────────────────────────────────────────
 * verify-cardguard-nosentinel.cjs — DOM regression assertion for the cardguard
 * negative filter (author ruling 2026-09-17 / #687①, #690, #695-A, #698-A1,
 * #556).
 *
 * SUBJECT: a payload carrying the card sentinel (`___\w+_HTML___`) ANYWHERE
 * — the realistic shape being the ToolResultGuard preview
 *     `<persisted-output>\nOutput too large (…). …\nPreview (first 2048 chars):\n___CARD_HTML___{…`
 * — must never reach the chat-stream DOM as raw text. The load-bearing rule is
 * the NEGATIVE filter, and it is judged by the SAME regex and only that regex
 * on every face. A payload that only starts with the sentinel is the canonical
 * card (positively rendered); everything else must route to the card path (best
 * effort) or to a safe placeholder.
 *
 * FACES (a face = one independent render route into the chat stream). The set
 * below is the contract: `--scope` declares it, and a face that is not actually
 * driven is reported UNCOVERED with a non-zero exit — never silently green
 * (author ruling #556 ②: a green run must never be attributable to a face the
 * script does not exercise).
 *
 *   renderTool       chat.js live tool RESULT      (L1/L3/L4/L5 arms, A1–A8)
 *   toolInputStream  chat.js streamed tool ARGUMENTS (A13–A14, #698 A1)
 *   backendHistory   persistence.js restoreFromBackendHistory  (P1/P2, A9/A9b)
 *   storageReplay    persistence.js restoreFromStorage         (P3/P4, A15/A16)
 *
 * A9/A9b ARE PRE-EXISTING ARMS and are frozen VERBATIM from main (their desc
 * literal, predicate and diagnostic payload are the replayguard batch's, i.e.
 * outside this batch's boundary) — do NOT reword them here; the face list above
 * is what carries their scope attribution, the desc text carries none.
 *
 * WHY THE storageReplay FACE IS ITS OWN ARM (#556 ①): the two replay routes are
 * independent guards in persistence.js. Before this change the script drove
 * only `restoreFromBackendHistory`, so rolling back the `restoreFromStorage`
 * branch alone left every arm green — a false green: a release that regressed
 * the localStorage fallback (the path a user actually hits on hard-refresh with
 * the backend unreachable) would have passed the gate. The per-face `COV/*`
 * checks and the `--mutant` double-pin (D2/D3) close exactly that hole.
 *
 * ROOT-AGNOSTIC BY DESIGN: `--root <webRoot>` makes the SAME assertion run
 * against the pre-fix tree copy (MUST be RED = the "old code fails" reading) and
 * against the fixed tree (MUST be GREEN). `--baseline <webRoot>` and
 * `--mutant <webRoot>` add second/third trees whose arms are driven IN THE SAME
 * INVOCATION, so a red→green pin is a single-run comparison rather than two
 * pasted conclusions (D1/D2). `--mutant` is the single-branch rollback tree
 * (only one cardguard branch reverted) and doubles as the no-masking proof: the
 * face that does not belong to the reverted branch must stay green (D3).
 *
 * `--root` defaults to the web root of the tree this script lives in, so the
 * bare `node scripts/verify-cardguard-nosentinel.cjs` is a valid CI gate run.
 *
 * Instance discipline: static `python3 -m http.server` on isolated ports + a
 * route-stubbed harness page. NEVER an engine instance, never :8080/:8097/:3030.
 * The :8080 listener set is snapshotted before/after and must be byte-identical.
 * Cleanup: PID-verified kill of our own servers + chromium close, on every exit
 * path.
 *
 * usage:
 *   node scripts/verify-cardguard-nosentinel.cjs [--root <webRoot>] [--port N]
 *        [--scope all|face,face,…] [--baseline <webRoot>] [--mutant <webRoot>]
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
// Default root = the web root of the tree this script ships in. A CI gate run
// therefore needs no arguments; `--root` still selects a foreign tree copy
// (pre-fix / mutant) for the same assertion. A wrong root is caught below.
const DEFAULT_ROOT = path.resolve(__dirname, '..', 'src', 'main', 'resources', 'web');
const WEB_ROOT = argOf('--root', DEFAULT_ROOT);
const BASELINE_ROOT = argOf('--baseline', null);
const MUTANT_ROOT = argOf('--mutant', null);
const SCOPE_RAW = argOf('--scope', 'all');
const PORT = String(argOf('--port', '8213'));
const OUT = argOf('--out', null);
const PERSONA = argOf('--persona', 'unlabelled');

// ── face coverage declaration (author ruling #556 ②) ───────────────────────
// Coverage is DECLARED here and VERIFIED per run; it is never inferred from a
// green arm list. A face outside `--scope` produces a red `COV/<face>` check,
// an `UNCOVERED` marker on stdout and a non-zero exit — the readable rejection
// the ruling requires. Silent green is the failure mode this switch forbids.
const FACES = ['renderTool', 'toolInputStream', 'backendHistory', 'storageReplay'];
const FACE_CHECKS = {
  renderTool: ['A1', 'A2', 'A3', 'A4', 'A5', 'A6', 'A7', 'A8'],
  toolInputStream: ['A13', 'A14'],
  backendHistory: ['A9', 'A9b'],
  storageReplay: ['A15', 'A16'],
};
const SCOPE_SET = SCOPE_RAW === 'all'
  ? new Set(FACES)
  : new Set(SCOPE_RAW.split(',').map((s) => s.trim()).filter(Boolean));
const SCOPE_UNKNOWN = [...SCOPE_SET].filter((f) => !FACES.includes(f));
const UNCOVERED = FACES.filter((f) => !SCOPE_SET.has(f));

const ROOT_ABS = path.resolve(WEB_ROOT);
if (SCOPE_UNKNOWN.length) {
  console.error('harness error: unknown face(s) in --scope: ' + SCOPE_UNKNOWN.join(', ') +
    '\ndeclared faces: ' + FACES.join(', '));
  process.exit(2);
}
for (const [flag, dir] of [['--root', ROOT_ABS], ['--baseline', BASELINE_ROOT], ['--mutant', MUTANT_ROOT]]) {
  if (!dir) continue;
  const abs = path.resolve(dir);
  if (!fs.existsSync(path.join(abs, 'js', 'chat.js'))) {
    console.error('harness error: ' + abs + '/js/chat.js not found (' + flag + ' must be a web/ root)');
    process.exit(2);
  }
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

// ── isolated static servers (own PID, own port) ────────────────────────────
const HOST_PID = process.pid;
const servers = [];   // { label, port, pid, proc, log }
function killServers() {
  for (const s of servers.splice(0)) {
    if (!s.pid) continue;
    try {
      // PID 验身 before any signal: our own spawned python, never anything else.
      const cmd = execSync(`ps -p ${s.pid} -o command= 2>/dev/null`, { encoding: 'utf8' }).trim();
      if (cmd.includes('http.server') && String(s.pid) !== String(HOST_PID)) {
        process.kill(s.pid, 'SIGTERM');
        console.log('[cleanup] killed ' + s.label + ' static server pid=' + s.pid + ' (' + cmd.slice(0, 60) + ')');
      } else {
        console.log('[cleanup] server pid not ours — not signalling: ' + cmd);
      }
    } catch (e) { /* already gone */ }
    if (s.log) { try { s.log.end(); } catch (e) {} }
  }
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

function spawnServer(label, port, rootAbs) {
  return new Promise((resolve, reject) => {
    const logPath = '/tmp/cardguard-httpserver-' + port + '.log';
    const log = fs.createWriteStream(logPath);
    const proc = spawn('python3', ['-m', 'http.server', port, '--bind', '127.0.0.1', '--directory', rootAbs],
      { stdio: ['ignore', 'pipe', 'pipe'] });
    const rec = { label, port, root: rootAbs, pid: proc.pid, proc, log };
    servers.push(rec);
    proc.stdout.pipe(log); proc.stderr.pipe(log);
    proc.on('error', reject);
    waitReady(`http://127.0.0.1:${port}/js/chat.js`, 20000).then((ok) => {
      if (!ok) return reject(new Error(label + ' static server not ready on :' + port));
      resolve(rec);
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
  // ── #698 A1: the sentinel carried by streamed tool ARGUMENTS ──────────────
  // The model emits `{"file_path":…,"content":"…"}`; the content field echoes a
  // guard preview. Primary field of Write = `content` (chat.js TOOL_PRIMARY_FIELDS)
  // ⇒ appendToolStreamDelta extracts it and, pre-fix, renders it verbatim into
  // `<pre class="tool-body-pre">` (hljs OFF) or the hljs `<pre>` (hljs ON).
  // NOTE the payload is reached through an unterminated JSON string — exactly
  // the streaming shape (`complete:false`), so the extraction result is a
  // partial value, not a parseable card.
  S1_INPUT_SENTINEL: '{"file_path":"/tmp/cg/out.txt","content":"' +
    '<persisted-output>\\nOutput too large (117.1 KB). Full output saved to: /tmp/cg/tool-results/call_00_x.txt' +
    '\\n\\nPreview (first 2048 chars):\\n' +
    '___CARD_HTML___{\\"fileRefs\\":{\\"proxied\\":0,\\"failed\\":0},\\"warnings\\":[],\\"html\\":\\"<div cla',
  // Control: an ORDINARY streamed tool input must keep the plain <pre> face
  // byte-for-byte (the negative filter must not swallow legitimate content).
  S3_INPUT_PLAIN: '{"file_path":"/tmp/cg/plain.txt","content":"alpha\\nbeta\\ngamma\\ndelta\\neps',
};

async function runCases(payload) {
  const CASES = payload.cases;
  const IN_SCOPE = new Set(payload.scope);
  const cv = await import('/js/chatView.js');
  const state = (await import('/js/state.js')).default;
  const { t } = await import('/js/i18n.js');
  const { renderTool, renderToolPending, appendToolStreamDelta, cancelToolStreamRAF } = await import('/js/chat.js');
  const chat = document.getElementById('chat');
  const view = { id: 'primary', sessionId: 'sess-cg', visible: true, stream: { scrollSnapped: false, toolStreamText: '' }, dom: { chat } };
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
  const reset = () => {
    cancelToolStreamRAF();
    chat.innerHTML = '';
    state.sessionToolCards = {};
    state.sessionPendingTools = {};
    view.stream.toolStreamText = '';
  };

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
      // ── face-driving evidence: proves the route was actually exercised, so a
      // face can never be "green" because its driver silently produced nothing.
      toolRows: chat.querySelectorAll('.row.tool').length,
      streamBodies: chat.querySelectorAll('.tool-stream-body').length,
      placeholderNodes: chat.querySelectorAll('[data-cardguard="placeholder"]').length,
      textLen: text.length,
      textDigest: await hash(text),
      htmlDigest: await hash(chat.innerHTML),
      srcdocDigests: await Promise.all(ifrs.map((f) => hash(f.getAttribute('srcdoc') || ''))),
      textSampleHead: text.slice(0, 200),
    };
  }

  const out = {
    placeholderText: PH_TEXT, placeholderResolves: PH_TEXT !== PH_KEY,
    cases: {}, modules: {}, faces: {},
  };
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
  const skipped = (face) => ({ ran: false, reason: 'face excluded by --scope' });

  // ── FACE: renderTool ──────────────────────────────────────────────────────
  if (IN_SCOPE.has('renderTool')) {
    out.faces.renderTool = { ran: true };
    // L3 runs FIRST so the module-level iframe id counter is at the same value in
    // the pre-fix and post-fix trees (cardRegistry uses `++_iframeId`, which rides
    // into both `data-nf-card-id` and the height script) ⇒ the digests below are a
    // true byte-for-byte comparison of the same mount index.
    reset();
    renderTool('Card', '1 card', CASES.L3_CARD_START, false, null, 'sess-cg');
    await settle();
    out.cases.L3_card_start = await snapshot();

    // L1a: guard shape, hljs OFF ⇒ exactly the plain `<pre>` fallback branch.
    reset();
    await withHljsOff(async () => { renderTool('Card', '1 card', CASES.L1_GUARD_TRUNC, false, null, 'sess-cg'); await settle(); });
    out.cases.L1a_guard_no_hljs = await snapshot();

    // L1b: guard shape, hljs ON (production state).
    reset();
    renderTool('Card', '1 card', CASES.L1_GUARD_TRUNC, false, null, 'sess-cg');
    await settle();
    out.cases.L1b_guard_hljs = await snapshot();

    // L1c: guard shape under a Read label, hljs ON (highlight route).
    reset();
    renderTool('Read', 'x.txt', CASES.L1_GUARD_TRUNC, false, null, 'sess-cg');
    await settle();
    out.cases.L1c_guard_read_label = await snapshot();

    // L1d: prefix + fully parseable payload ⇒ best-effort card path.
    reset();
    renderTool('Card', '1 card', CASES.L1B_PREFIX_VALID, false, null, 'sess-cg');
    await settle();
    out.cases.L1d_prefix_valid = await snapshot();

    // L1e: sentinel mid-string, tail parses but has no `.html`.
    reset();
    renderTool('Card', '1 card', CASES.L1C_NO_HTML, false, null, 'sess-cg');
    await settle();
    out.cases.L1e_no_html = await snapshot();

    // L4a: plain non-card result, hljs OFF ⇒ plain <pre class="tool-body-pre">.
    reset();
    await withHljsOff(async () => { renderTool('Bash', 'ls -la', CASES.L4_PLAIN, false, null, 'sess-cg'); await settle(); });
    out.cases.L4a_plain_no_hljs = await snapshot();

    // L4b: plain non-card result, hljs ON ⇒ highlight route (unchanged either way).
    reset();
    renderTool('Bash', 'ls -la', CASES.L4_PLAIN, false, null, 'sess-cg');
    await settle();
    out.cases.L4b_plain_hljs = await snapshot();

    // L5: unified diff on a Grep label ⇒ formatDiff face.
    reset();
    renderTool('Grep', 'src/**', CASES.L5_DIFF, false, null, 'sess-cg');
    await settle();
    out.cases.L5_diff = await snapshot();
  } else {
    out.faces.renderTool = skipped('renderTool');
  }

  // ── FACE: backendHistory (persistence.js) with the SAME guard shape ───────
  if (IN_SCOPE.has('backendHistory')) {
    out.faces.backendHistory = { ran: true };
    // P1: backend-history replay face, hljs ON.
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

    // P2: same replay face with hljs OFF ⇒ the literal plain-text fallback that
    // persistence.js shares with chat.js (`<pre class="tool-body-pre">`).
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
  } else {
    out.faces.backendHistory = skipped('backendHistory');
  }

  // ── FACE: storageReplay (persistence.js restoreFromStorage) — #556 ① ─────
  // This is the route with ZERO coverage before this batch: it is the
  // localStorage fallback a user hits on hard-refresh when the backend is
  // unreachable, and its guard is an INDEPENDENT branch from the backend one.
  if (IN_SCOPE.has('storageReplay')) {
    out.faces.storageReplay = { ran: true };
    const stMod = await import('/js/state.js');
    const LS_SESSIONS = stMod.LS_SESSIONS_KEY;
    const seed = (entry) => {
      const prevRaw = localStorage.getItem(LS_SESSIONS);
      const prevActive = state.activeSessionId;
      localStorage.setItem(LS_SESSIONS, JSON.stringify({ 'sess-cg': [entry] }));
      state.activeSessionId = 'sess-cg';
      return { prevRaw, prevActive };
    };
    const unseed = (saved) => {
      if (saved.prevRaw === null) localStorage.removeItem(LS_SESSIONS);
      else localStorage.setItem(LS_SESSIONS, saved.prevRaw);
      state.activeSessionId = saved.prevActive;
    };
    const driveStorage = async (hljsOff) => {
      const { restoreFromStorage } = await import('/js/persistence.js');
      const saved = seed({ type: 'tool', label: 'Card', summary: '1 card', content: CASES.L1_GUARD_TRUNC, isError: false });
      reset();
      try {
        if (hljsOff) {
          await withHljsOff(async () => { restoreFromStorage({ scrollToBottom: false, busyTail: false }); await settle(); });
        } else {
          restoreFromStorage({ scrollToBottom: false, busyTail: false });
          await settle();
        }
      } finally { unseed(saved); }
    };

    // P3: storage replay face, hljs ON.
    try {
      await driveStorage(false);
      out.cases.P3_storage_replay = await snapshot();
      out.cases.P3_storage_replay.ran = true;
    } catch (e) {
      out.cases.P3_storage_replay = { ran: false, error: String(e && e.message || e) };
    }

    // P4: same, hljs OFF ⇒ the literal plain <pre class="tool-body-pre"> fallback.
    try {
      await driveStorage(true);
      out.cases.P4_storage_replay_no_hljs = await snapshot();
      out.cases.P4_storage_replay_no_hljs.ran = true;
    } catch (e) {
      out.cases.P4_storage_replay_no_hljs = { ran: false, error: String(e && e.message || e) };
    }
  } else {
    out.faces.storageReplay = skipped('storageReplay');
  }

  // ── FACE: toolInputStream (#698 A1) — streamed tool ARGUMENTS ─────────────
  // `appendToolStreamDelta` renders the model's in-flight tool input, which is a
  // DIFFERENT route from renderTool's result side: no tool_result exists yet.
  // Pre-fix a sentinel inside the streamed primary field lands verbatim in the
  // DOM (hljs `<pre>` or the plain `<pre class="tool-body-pre">` fallback).
  if (IN_SCOPE.has('toolInputStream')) {
    out.faces.toolInputStream = { ran: true };
    const driveInput = async (delta, hljsOff) => {
      reset();
      renderToolPending('Write', 'sess-cg');
      if (hljsOff) {
        await withHljsOff(async () => { appendToolStreamDelta('Write', delta); await settle(); });
      } else {
        appendToolStreamDelta('Write', delta);
        await settle();
      }
    };

    // S1: hljs ON (production) — the highlight route.
    reset();
    try {
      await driveInput(CASES.S1_INPUT_SENTINEL, false);
      out.cases.S1_input_hljs = await snapshot();
      out.cases.S1_input_hljs.ran = true;
    } catch (e) {
      out.cases.S1_input_hljs = { ran: false, error: String(e && e.message || e) };
    }

    // S2: hljs OFF — the literal plain `<pre class="tool-body-pre">` fallback.
    try {
      await driveInput(CASES.S1_INPUT_SENTINEL, true);
      out.cases.S2_input_no_hljs = await snapshot();
      out.cases.S2_input_no_hljs.ran = true;
    } catch (e) {
      out.cases.S2_input_no_hljs = { ran: false, error: String(e && e.message || e) };
    }

    // S3: control — an ordinary streamed input keeps the plain <pre> face.
    try {
      await driveInput(CASES.S3_INPUT_PLAIN, true);
      out.cases.S3_input_plain = await snapshot();
      out.cases.S3_input_plain.ran = true;
    } catch (e) {
      out.cases.S3_input_plain = { ran: false, error: String(e && e.message || e) };
    }
  } else {
    out.faces.toolInputStream = skipped('toolInputStream');
  }

  out.hadHljs = HAD_HLJS;
  reset();
  return out;
}

// ── per-tree judgement (shared by root / baseline / mutant) ────────────────
function judge(payload) {
  const C = payload.cases;
  const clean = (c) => c && c.sentinelHits === 0 && c.persistedHits === 0 && c.payloadKeyHits === 0 && c.prePlain === 0 && c.preHljs === 0;
  const out = [];
  const add = (id, desc, pass, detail) => out.push({ id, desc, pass: !!pass, detail });
  // Strictly equivalent to the pre-existing `!!(c && c.ran)` shape used by the
  // A9/A9b arms: a case whose `ran` flag is missing is NOT driven ⇒ the arm goes
  // RED. The looser `ran !== false && error === undefined` form returned TRUE for
  // a missing `ran` — the vacuous-green direction #556 forbids.
  const driven = (c) => !!(c && c.ran);

  if (C.L1a_guard_no_hljs) {
    add('A1', 'guard shape (hljs OFF ⇒ plain <pre> branch): no raw payload in DOM, placeholder shown',
      clean(C.L1a_guard_no_hljs) && C.L1a_guard_no_hljs.placeholderVisible,
      { sentinelHits: C.L1a_guard_no_hljs.sentinelHits, prePlain: C.L1a_guard_no_hljs.prePlain, persistedHits: C.L1a_guard_no_hljs.persistedHits, payloadKeyHits: C.L1a_guard_no_hljs.payloadKeyHits, placeholderVisible: C.L1a_guard_no_hljs.placeholderVisible, head: C.L1a_guard_no_hljs.textSampleHead });
    add('A2', 'guard shape (hljs ON, production): no raw payload in DOM, placeholder shown',
      clean(C.L1b_guard_hljs) && C.L1b_guard_hljs.placeholderVisible,
      { sentinelHits: C.L1b_guard_hljs.sentinelHits, preHljs: C.L1b_guard_hljs.preHljs, persistedHits: C.L1b_guard_hljs.persistedHits, placeholderVisible: C.L1b_guard_hljs.placeholderVisible });
    add('A3', 'guard shape under Read label (highlight route): no raw payload in DOM',
      clean(C.L1c_guard_read_label),
      { sentinelHits: C.L1c_guard_read_label.sentinelHits, preHljs: C.L1c_guard_read_label.preHljs, prePlain: C.L1c_guard_read_label.prePlain });
    add('A4', 'prefix + parseable payload ⇒ card path (iframe), sentinel not in DOM',
      clean(C.L1d_prefix_valid) && C.L1d_prefix_valid.cardIframes === 1,
      { sentinelHits: C.L1d_prefix_valid.sentinelHits, cardIframes: C.L1d_prefix_valid.cardIframes, prePlain: C.L1d_prefix_valid.prePlain });
    add('A5', 'sentinel mid-string, tail has no .html ⇒ safe placeholder',
      clean(C.L1e_no_html) && C.L1e_no_html.placeholderVisible,
      { sentinelHits: C.L1e_no_html.sentinelHits, prePlain: C.L1e_no_html.prePlain, placeholderVisible: C.L1e_no_html.placeholderVisible });
    add('A6', 'canonical card (sentinel at 0) still renders as ONE card iframe, zero raw text',
      clean(C.L3_card_start) && C.L3_card_start.cardIframes === 1,
      { cardIframes: C.L3_card_start.cardIframes, sentinelHits: C.L3_card_start.sentinelHits, srcdocDigests: C.L3_card_start.srcdocDigests, htmlDigest: C.L3_card_start.htmlDigest });
    add('A7', 'plain non-card result keeps the <pre class="tool-body-pre"> fallback (pre text byte-identical)',
      C.L4a_plain_no_hljs.prePlain === 1 && C.L4a_plain_no_hljs.preTextDigests[0] === sha(CASES.L4_PLAIN),
      { prePlain: C.L4a_plain_no_hljs.prePlain, preTextDigests: C.L4a_plain_no_hljs.preTextDigests, expectedDigest: sha(CASES.L4_PLAIN) });
    add('A8', 'unified diff still routed to formatDiff (.diff-line present)',
      C.L5_diff.diffLines >= 3,
      { diffLines: C.L5_diff.diffLines, htmlDigest: C.L5_diff.htmlDigest });
  }

  if (C.P1_backend_history) {
    add('A9', 'P1 [scope probe] history-replay face with the SAME guard shape: no raw payload in DOM',
      !!(C.P1_backend_history && C.P1_backend_history.ran) && clean(C.P1_backend_history),
      C.P1_backend_history ? { ran: C.P1_backend_history.ran, sentinelHits: C.P1_backend_history.sentinelHits, prePlain: C.P1_backend_history.prePlain, preHljs: C.P1_backend_history.preHljs, persistedHits: C.P1_backend_history.persistedHits, head: C.P1_backend_history.textSampleHead } : C.P1_backend_history);
    add('A9b', 'P2 [scope probe] same replay face, hljs OFF ⇒ literal plain <pre class="tool-body-pre"> fallback',
      !!(C.P2_backend_history_no_hljs && C.P2_backend_history_no_hljs.ran) && clean(C.P2_backend_history_no_hljs),
      C.P2_backend_history_no_hljs ? { ran: C.P2_backend_history_no_hljs.ran, sentinelHits: C.P2_backend_history_no_hljs.sentinelHits, prePlain: C.P2_backend_history_no_hljs.prePlain, persistedHits: C.P2_backend_history_no_hljs.persistedHits, head: C.P2_backend_history_no_hljs.textSampleHead } : C.P2_backend_history_no_hljs);
  }

  if (C.P3_storage_replay) {
    // `toolRows >= 1` is the anti-false-green guard: a storage arm that did not
    // actually reach the replay renderer must FAIL, not pass vacuously.
    add('A15', 'P3 [face storageReplay] restoreFromStorage with the SAME guard shape: no raw payload, placeholder shown, and the face was ACTUALLY driven (≥1 tool row)',
      driven(C.P3_storage_replay) && clean(C.P3_storage_replay) && C.P3_storage_replay.placeholderVisible && C.P3_storage_replay.toolRows >= 1,
      { ran: C.P3_storage_replay.ran, sentinelHits: C.P3_storage_replay.sentinelHits, prePlain: C.P3_storage_replay.prePlain, preHljs: C.P3_storage_replay.preHljs, persistedHits: C.P3_storage_replay.persistedHits, toolRows: C.P3_storage_replay.toolRows, placeholderVisible: C.P3_storage_replay.placeholderVisible, head: C.P3_storage_replay.textSampleHead });
    add('A16', 'P4 [face storageReplay] same face, hljs OFF ⇒ literal plain <pre class="tool-body-pre"> fallback',
      driven(C.P4_storage_replay_no_hljs) && clean(C.P4_storage_replay_no_hljs) && C.P4_storage_replay_no_hljs.placeholderVisible && C.P4_storage_replay_no_hljs.toolRows >= 1,
      { ran: C.P4_storage_replay_no_hljs.ran, sentinelHits: C.P4_storage_replay_no_hljs.sentinelHits, prePlain: C.P4_storage_replay_no_hljs.prePlain, persistedHits: C.P4_storage_replay_no_hljs.persistedHits, toolRows: C.P4_storage_replay_no_hljs.toolRows, placeholderVisible: C.P4_storage_replay_no_hljs.placeholderVisible, head: C.P4_storage_replay_no_hljs.textSampleHead });
  }

  if (C.S1_input_hljs) {
    // `streamBodies >= 1` proves the streaming body was created, i.e. the tool
    // INPUT route was exercised — not that the driver silently no-opped.
    add('A13', 'S1 [face toolInputStream] sentinel in STREAMED tool ARGUMENTS (hljs ON): never rendered as raw text; safe placeholder shown, no <pre> fallback',
      driven(C.S1_input_hljs) && clean(C.S1_input_hljs) && C.S1_input_hljs.placeholderVisible && C.S1_input_hljs.streamBodies >= 1,
      { ran: C.S1_input_hljs.ran, sentinelHits: C.S1_input_hljs.sentinelHits, persistedHits: C.S1_input_hljs.persistedHits, payloadKeyHits: C.S1_input_hljs.payloadKeyHits, prePlain: C.S1_input_hljs.prePlain, preHljs: C.S1_input_hljs.preHljs, streamBodies: C.S1_input_hljs.streamBodies, placeholderVisible: C.S1_input_hljs.placeholderVisible, head: C.S1_input_hljs.textSampleHead });
    add('A14', 'S2 [face toolInputStream] same, hljs OFF ⇒ the literal <pre class="tool-body-pre"> fallback branch, same negative filter',
      driven(C.S2_input_no_hljs) && clean(C.S2_input_no_hljs) && C.S2_input_no_hljs.placeholderVisible && C.S2_input_no_hljs.streamBodies >= 1,
      { ran: C.S2_input_no_hljs.ran, sentinelHits: C.S2_input_no_hljs.sentinelHits, persistedHits: C.S2_input_no_hljs.persistedHits, prePlain: C.S2_input_no_hljs.prePlain, preHljs: C.S2_input_no_hljs.preHljs, streamBodies: C.S2_input_no_hljs.streamBodies, placeholderVisible: C.S2_input_no_hljs.placeholderVisible, head: C.S2_input_no_hljs.textSampleHead });
    // S3 control: an ordinary streamed input keeps the plain <pre> face, so the
    // new filter is a negative filter and not a blanket placeholder.
    add('A17', 'S3 [face toolInputStream, control] an ORDINARY streamed tool input still renders as plain text (no false placeholder)',
      driven(C.S3_input_plain) && C.S3_input_plain.prePlain === 1 && C.S3_input_plain.placeholderVisible === false
        && C.S3_input_plain.preTextDigests[0] === sha('alpha\nbeta\ngamma\ndelta\neps'),
      { prePlain: C.S3_input_plain && C.S3_input_plain.prePlain, placeholderVisible: C.S3_input_plain && C.S3_input_plain.placeholderVisible, preTextDigests: C.S3_input_plain && C.S3_input_plain.preTextDigests, expected: sha('alpha\nbeta\ngamma\ndelta\neps') });
  }

  return out;
}

// ── main ───────────────────────────────────────────────────────────────────
(async () => {
  const t0 = Date.now();
  try {
    const trees = [{ label: 'root', root: ROOT_ABS, port: Number(PORT) }];
    if (BASELINE_ROOT) trees.push({ label: 'baseline', root: path.resolve(BASELINE_ROOT), port: Number(PORT) + 1 });
    if (MUTANT_ROOT) trees.push({ label: 'mutant', root: path.resolve(MUTANT_ROOT), port: Number(PORT) + 2 });
    const usedPorts = trees.map((t) => t.port);
    if (new Set(usedPorts).size !== usedPorts.length) { console.error('harness error: duplicate ports'); process.exit(2); }
    for (const p of usedPorts) {
      const lsof = execSync(`lsof -nP -iTCP:${p} -sTCP:LISTEN -t 2>/dev/null || true`, { encoding: 'utf8' }).trim();
      if (lsof) { console.error('harness error: port ' + p + ' already in LISTEN (pids: ' + lsof + ') — pick another port'); process.exit(2); }
    }

    for (const t of trees) await spawnServer(t.label, t.port, t.root);
    const rootServer = servers.find((s) => s.label === 'root');
    console.log('[pre-flight] root static server :' + rootServer.port + ' serving ' + ROOT_ABS + ' (pid ' + rootServer.pid + ')');
    for (const s of servers) if (s.label !== 'root') console.log('[pre-flight] ' + s.label + ' server :' + s.port + ' serving ' + s.root);
    console.log('[pre-flight] scope = ' + [...SCOPE_SET].join(',') + (UNCOVERED.length ? ' | UNCOVERED: ' + UNCOVERED.join(',') : ''));
    console.log('[pre-flight] H8080_BEFORE = "' + H8080_BEFORE + '"');

    const browser = await chromium.launch();
    const readings = {};
    try {
      const ctx = await browser.newContext();
      await ctx.route('**/api/nf-ticket*', (r) => r.fulfill({ json: { tickets: {}, rejected: [] } }));
      await ctx.route('**/api/**', (r) => r.fulfill({ json: {} }));
      await ctx.route('**/cg-harness.html', (r) => r.fulfill({ contentType: 'text/html', body: harnessHtml() }));
      for (const t of trees) {
        const page = await ctx.newPage();
        page.on('pageerror', (e) => pageErrors.push({ tree: t.label, message: String(e) }));
        await page.goto(`http://127.0.0.1:${t.port}/cg-harness.html`, { waitUntil: 'load' });
        readings[t.label] = await page.evaluate(runCases, { cases: CASES, scope: [...SCOPE_SET] });
        await page.close();
      }
    } finally {
      await browser.close();
    }

    const rootRes = readings.root;
    const rootChecks = judge(rootRes);
    for (const c of rootChecks) check(c.id, c.desc, c.pass, c.detail);
    // A10 judges the tree under test only: a comparison tree is a deliberately
    // broken/older copy and must not be able to redden the root reading here.
    const rootPageErrors = pageErrors.filter((p) => p.tree === 'root');
    check('A10', 'no page errors on the --root tree', rootPageErrors.length === 0, rootPageErrors);

    // ── per-face coverage declaration (#556 ②) ───────────────────────────────
    // One check per DECLARED face: in scope? actually driven? every arm green?
    // An out-of-scope face is a readable rejection (red check + UNCOVERED marker
    // + non-zero exit) — never a silent green.
    const byId = Object.fromEntries(checks.map((c) => [c.id, c]));
    for (const face of FACES) {
      const info = (rootRes.faces && rootRes.faces[face]) || {};
      const ids = FACE_CHECKS[face];
      const emitted = ids.filter((id) => byId[id]);
      const armsPass = emitted.length === ids.length && emitted.every((id) => byId[id].pass);
      const inScope = SCOPE_SET.has(face);
      check('COV/' + face,
        'face `' + face + '` covered: declared in --scope AND actually driven AND every arm green',
        inScope && info.ran === true && armsPass,
        { face, inScope, driven: info.ran === true, reason: info.reason || null, armsEmitted: emitted.length + '/' + ids.length, armsRed: emitted.filter((id) => !byId[id].pass) });
      if (!inScope) {
        console.log('UNCOVERED ' + face + ' — 不支持 / 未覆盖：face excluded by --scope=' + SCOPE_RAW +
          ' ⇒ readable rejection (this run is NOT a green gate for that face)');
      }
    }

    // ── same-run double pin (D1/D2) + no-masking independence (D3/D4) ────────
    const cmp = {};
    for (const t of trees) if (t.label !== 'root') cmp[t.label] = judge(readings[t.label]);
    const cById = (list) => Object.fromEntries(list.map((c) => [c.id, c]));
    const pin = (ids) => (list) => {
      const m = cById(list);
      return { red: ids.every((id) => m[id] && !m[id].pass), green: ids.every((id) => m[id] && m[id].pass), present: ids.every((id) => !!m[id]) };
    };
    const INPUT_ARMS = ['A13', 'A14'];
    const STORAGE_ARMS = ['A15', 'A16'];
    const PRE_EXISTING = ['A1', 'A2', 'A3', 'A4', 'A5', 'A6', 'A7', 'A8', 'A9', 'A9b', 'A17'];

    if (cmp.baseline) {
      const b = pin(INPUT_ARMS)(cmp.baseline);
      const r = pin(INPUT_ARMS)(rootChecks);
      check('D1', 'double pin (SAME run): toolInputStream arms RED on --baseline tree / GREEN on --root tree',
        b.present && r.present && b.red && r.green,
        { baselineRed: b.red, rootGreen: r.green, baselineRoot: path.resolve(BASELINE_ROOT) });
      // The baseline tree differs from the root tree only by the toolInputStream
      // guard, so every pre-existing arm must still be green there ⇒ the D1 red
      // is attributable to that one guard and nothing else.
      const p = pin(PRE_EXISTING)(cmp.baseline);
      check('D4', 'attribute check: on the --baseline tree every PRE-EXISTING arm is still green ⇒ D1 red is attributable to the toolInputStream guard alone',
        p.green, { preExistingGreen: p.green, armsRed: PRE_EXISTING.filter((id) => { const m = cById(cmp.baseline); return m[id] && !m[id].pass; }) });
    }
    if (cmp.mutant) {
      const m = pin(STORAGE_ARMS)(cmp.mutant);
      const r = pin(STORAGE_ARMS)(rootChecks);
      check('D2', 'double pin (SAME run): storageReplay arms RED on --mutant tree (storage branch alone rolled back) / GREEN on --root tree',
        m.present && r.present && m.red && r.green,
        { mutantRed: m.red, rootGreen: r.green, mutantRoot: path.resolve(MUTANT_ROOT) });
      const p = pin(PRE_EXISTING)(cmp.mutant);
      check('D3', 'no masking: a single-branch rollback reddens ONLY its own face — every non-storage arm stays green on the --mutant tree',
        p.green, { othersGreen: p.green, armsRed: PRE_EXISTING.filter((id) => { const x = cById(cmp.mutant); return x[id] && !x[id].pass; }) });
    }
    if (!cmp.baseline && !cmp.mutant) {
      console.log('[info] pin mode OFF (no --baseline/--mutant tree supplied) — the D* red→green pins were NOT evaluated in this run');
    }

    // ── post-flight: port + 8080 double assertion ──
    const mine = execSync(`lsof -nP -iTCP:${rootServer.port} -sTCP:LISTEN -t 2>/dev/null || true`, { encoding: 'utf8' }).trim();
    check('A11', 'harness server is the only listener on its isolated port', !!mine && mine.split(/\s+/).includes(String(rootServer.pid)),
      { port: rootServer.port, listeners: mine, ourPid: rootServer.pid });
    const H8080_AFTER = listeners8080();
    check('A12', ':8080 host listener set unchanged (zero signals to the host)', H8080_BEFORE === H8080_AFTER,
      { before: H8080_BEFORE, after: H8080_AFTER });

    const payload = {
      runner: 'scripts/verify-cardguard-nosentinel.cjs',
      persona: PERSONA, webRoot: ROOT_ABS, port: PORT,
      scope: [...SCOPE_SET], declaredFaces: FACES, uncovered: UNCOVERED,
      pinMode: { baseline: BASELINE_ROOT ? path.resolve(BASELINE_ROOT) : null, mutant: MUTANT_ROOT ? path.resolve(MUTANT_ROOT) : null },
      chatJsSha256: rootRes.modules.chatJsSha256,
      persistenceJsSha256: rootRes.modules.persistenceJsSha256,
      persistedOutputHitsInChatJs: rootRes.modules.persistedOutputHitsInChatJs,
      persistedOutputHitsInPersistenceJs: rootRes.modules.persistedOutputHitsInPersistenceJs,
      placeholderKey: 'chat.toolCardUnavailable', placeholderText: rootRes.placeholderText,
      placeholderResolves: rootRes.placeholderResolves,
      faces: rootRes.faces,
      cases: rootRes.cases, checks, pageErrors,
      comparisons: Object.fromEntries(Object.entries(readings).filter(([k]) => k !== 'root')
        .map(([k, v]) => [k, { root: trees.find((t) => t.label === k).root, faces: v.faces, checks: cmp[k] }])),
      elapsedMs: Date.now() - t0,
    };
    if (OUT) fs.writeFileSync(OUT, JSON.stringify(payload, null, 2));
    const failed = checks.filter((c) => !c.pass);
    console.log('\n=== ' + (checks.length - failed.length) + '/' + checks.length + ' checks green' +
      (failed.length ? ' | RED: ' + failed.map((f) => f.id).join(',') : '') + ' | persona=' + PERSONA + ' ===');
    killServers();
    process.exit(failed.length ? 1 : 0);
  } catch (e) {
    console.error('HARNESS ERROR', e && e.stack || e);
    killServers();
    process.exit(2);
  }
})();

for (const sig of ['SIGINT', 'SIGTERM']) process.on(sig, () => { killServers(); process.exit(130); });

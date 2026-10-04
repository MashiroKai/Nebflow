#!/usr/bin/env node
// e2e-onboarding-v2-isolated.mjs — isolated cold-start end-to-end walk of the
// v2 onboarding (personal-agent batch #482, acceptance items ①②③④).
//
// ISOLATION-FIRST GATE (task book §5 D-3): this script NEVER touches the host
// instance. It requires
//   NEBFLOW_URL      = http://127.0.0.1:<port>   (a port in the 8686..8690 band)
//   NEBFLOW_HOME_DIR = the isolated home (a `mktemp -d` tree)
// and refuses outright if either is missing or the port is 8080. The caller
// boots the gateway itself (`sbt "run --home <dir> --port <port>"`); this script
// is only the client side, so it cannot start a second instance by accident.
//
// What it proves, frame by frame over the real WS surface:
//   A. Cold start: the gateway reports onboarding 'pending' with zero config.
//   B. Questionnaire walk: per-question upsert (setOnboardingAnswers) lands each
//      answer, then finishOnboarding writes Soul.md / User.md as peers at the
//      home root, carrying the free-form quotes verbatim.
//   C. All-skipped path: an 11/11 skip run reaches **done** — the terminal
//      transition is actually SENT and awaited (`setOnboardingState('done')` →
//      `onboardingStateSet`), and the marker on disk says done. Reaching done
//      also requires a recorded probe (the server hard gate), so this section
//      points the isolated config at a LOCAL stub that really answers and probes
//      it first; a harness that only wrote the artifacts could never prove this.
//   D. Model step same-source + two-way consistency: getOnboardingModels lists
//      exactly the providers/models of the config store the Settings panel
//      writes, and a context-window write-back is read back from that same
//      store (updateConfig -> config file -> the next getOnboardingModels read).
//   E. PRIMARY terminal path (pick a configured model, then finish): the real
//      sequence the UI runs — finishOnboarding, then probeLlm (which records
//      probeOkAt and only succeeds because the stub answers), then
//      setOnboardingState('done') → the gate accepts. This is the path the
//      verifier proved unmet; the stub makes the probe real without any network.
//   F. The gate actually refuses: setOnboardingState('done') on a fresh home
//      (no probe on record) answers {type:'error',code:'probe_required'} and the
//      marker does NOT move. Coverage must exercise the gate, not bypass it.
//
// Usage:
//   NEBFLOW_URL=http://127.0.0.1:8687 NEBFLOW_HOME_DIR=$(mktemp -d) \
//     node scripts/e2e-onboarding-v2-isolated.mjs
//
// The local stub server (sections C / E / F) binds an ephemeral loopback port of
// its OWN choosing — never the reserved 8686..8690 band, never :8080 — and is
// closed before exit.
//
// Exit code 0 = every check passed. Non-zero = at least one FAIL.

import { readFileSync, existsSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { createServer } from 'node:http';

const URL_BASE = process.env.NEBFLOW_URL || '';
const HOME = process.env.NEBFLOW_HOME_DIR || '';

function die(msg) {
  console.error(`REFUSE  ${msg}`);
  process.exit(2);
}
if (!HOME) die('NEBFLOW_HOME_DIR is required (the isolated home from mktemp -d)');
if (!URL_BASE) die('NEBFLOW_URL is required (http://127.0.0.1:<port>)');
{
  const port = Number(new URL(URL_BASE).port || 80);
  if (port === 8080) die('NEBFLOW_URL points at the host instance :8080 — forbidden');
  if (port < 8686 || port > 8690) die(`port ${port} is outside the reserved 8686..8690 band`);
}
const tokenPath = join(HOME, 'auth.json');
if (!existsSync(tokenPath)) die(`no auth.json under ${HOME} — is the isolated gateway running?`);
// auth.json holds the token as a BARE JSON STRING (Auth.loadOrCreateToken,
// gateway/auth.scala) — not an object with a `token` field.
const TOKEN = JSON.parse(readFileSync(tokenPath, 'utf8'));

let pass = 0;
let fail = 0;
function check(name, ok, extra = '') {
  if (ok) pass++;
  else fail++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? `  — ${extra}` : ''}`);
}

// ── WS plumbing (Node's global WebSocket, same shape the other smokes use) ──
function wsConnectOnce() {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${URL_BASE.replace(/^http/, 'ws')}/ws?token=${encodeURIComponent(TOKEN)}`);
    const inbox = [];
    const waiters = [];
    ws.addEventListener('message', (ev) => {
      let msg;
      try { msg = JSON.parse(ev.data); } catch { return; }
      inbox.push(msg);
      for (let i = waiters.length - 1; i >= 0; i--) {
        const w = waiters[i];
        if (w.after < inbox.length && w.pred(msg)) { waiters.splice(i, 1); w.resolve(msg); }
      }
    });
    ws.addEventListener('open', () => resolve({ ws, inbox, waiters }));
    ws.addEventListener('error', () => reject(new Error('WS connect failed')));
  });
}

/**
 * Handshake with retry. The runner already waits for the HTTP listener, but a
 * single attempt would still turn any residual startup race into a bare
 * "WS connect failed" — retry briefly so the check reflects the system under
 * test, not our timing.
 */
async function wsConnect(attempts = 15, gapMs = 1000) {
  let last;
  for (let i = 0; i < attempts; i++) {
    try {
      return await wsConnectOnce();
    } catch (e) {
      last = e;
      await new Promise((r) => setTimeout(r, gapMs));
    }
  }
  throw last;
}

function waitFor(conn, pred, timeoutMs, label, after = 0) {
  for (let i = after; i < conn.inbox.length; i++) {
    if (pred(conn.inbox[i])) return Promise.resolve(conn.inbox[i]);
  }
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(`timeout waiting for ${label}`)), timeoutMs);
    conn.waiters.push({ pred, after, resolve: (m) => { clearTimeout(t); resolve(m); } });
  });
}

/**
 * Send one frame and await its reply. `after` is the inbox length BEFORE the
 * send, so a repeated frame (getOnboardingModels / finishOnboarding are both sent
 * more than once below) can never be satisfied by the earlier reply — without
 * this bound the second read would silently return the first answer and every
 * "two-way consistency" check would pass vacuously.
 */
async function ask(conn, frame, replyType, timeoutMs = 8000) {
  const after = conn.inbox.length;
  conn.ws.send(JSON.stringify(frame));
  return waitFor(conn, (m) => m.type === replyType, timeoutMs, `${replyType} (after ${frame.type})`, after);
}

/**
 * Model-list read that also accepts the generic `{type:"error"}` shell and FAILS
 * LOUD on it. Without this a server-side refusal shows up as a bare 8s timeout of
 * `onboardingModels`, which reads like a harness hiccup instead of a defect — the
 * exact shape the virgin-home decode bug took (before the fix, a fresh home
 * answered this frame with "current config is unreadable").
 */
async function askModels(conn, timeoutMs = 8000) {
  const after = conn.inbox.length;
  conn.ws.send(JSON.stringify({ type: 'getOnboardingModels' }));
  const m = await waitFor(conn, (x) => x.type === 'onboardingModels' || x.type === 'error',
    timeoutMs, 'onboardingModels|error', after);
  if (m.type === 'error') {
    check('D model list: the frame must not be refused', false, `server replied error: ${m.message}`);
    return { source: null, models: [], providers: [] };
  }
  return m;
}

/**
 * Read the marker from the frame AND from disk. The frame is `onboarding:` of
 * `configData`; the file is `onboarding.json` (`{state, probeOkAt}`). The disk
 * leg matters: a stale/forged frame must not be able to fake a state change
 * (the same read-modify-write file the gate writes is the source of truth).
 */
async function readMarker(conn) {
  const cfg = await ask(conn, { type: 'getConfig' }, 'configData', 8000);
  let disk = null;
  const p = join(HOME, 'onboarding.json');
  if (existsSync(p)) {
    try { disk = JSON.parse(readFileSync(p, 'utf8')); } catch { disk = null; }
  }
  return { frame: cfg.onboarding ?? null, disk };
}

/**
 * A minimal, DETERMINISTIC, LOCAL OpenAI-protocol stub. The onboarding probe
 * (`probeLlm`) is a real network call through the global chain, so proving the
 * all-skipped / primary terminal paths reach `done` needs a provider that
 * actually answers. A stub on loopback keeps that real — no real provider, no
 * credentials, no third-party network.
 *
 * Binding: the OS picks an ephemeral port on 127.0.0.1 (0 in `listen`), which by
 * construction cannot collide with :8080 or the reserved 8686..8690 band.
 */
function startStubLlm() {
  const state = { hits: 0, lastBody: null };
  const server = createServer((req, res) => {
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      state.hits++;
      state.lastBody = body;
      if (!req.url.endsWith('/chat/completions')) {
        res.writeHead(404, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ error: { message: 'not found' } }));
        return;
      }
      // Shape the OpenAI adapter expects (choices[0].message.content non-empty).
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({
        id: 'stub-1', object: 'chat.completion', model: 'stub-model',
        choices: [{ index: 0, message: { role: 'assistant', content: 'ok' }, finish_reason: 'stop' }],
        usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 },
      }));
    });
  });
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address();
      resolve({ port, url: `http://127.0.0.1:${port}`, hits: () => state.hits, lastBody: () => state.lastBody,
        close: () => new Promise((r) => server.close(() => r())) });
    });
  });
}

/** The 11-question script, mirroring onboarding.js (id / label / scope / kind). */
const SCRIPT = [
  { id: 'name', label: '名字', scope: 'base', kind: 'free' },
  { id: 'call', label: '怎么称呼', scope: 'user', kind: 'free' },
  { id: 'model', label: '大脑配置', scope: 'base', kind: 'model' },
  { id: 'role', label: '角色定位', scope: 'soul', kind: 'choice' },
  { id: 'style', label: '语言风格', scope: 'soul', kind: 'choice' },
  { id: 'pro', label: '主动程度', scope: 'soul', kind: 'choice' },
  { id: 'boundaries', label: '相处约定', scope: 'soul', kind: 'multi' },
  { id: 'temper1', label: '语气基线', scope: 'soul', kind: 'choice' },
  { id: 'temper2', label: '做事方式', scope: 'soul', kind: 'choice' },
  { id: 'identity', label: '日常身份', scope: 'user', kind: 'choice' },
  { id: 'focus', label: '当前关注', scope: 'user', kind: 'free' },
];

function answerFrame(id, label, scope, kind, value, values) {
  return { id, label, scope, kind, ...(values ? { values } : { value }) };
}

const conn = await wsConnect();
const stub = await startStubLlm();
try {
  // ── A. Cold start reading ──────────────────────────────────────────────
  // `configured` is true when the home ships providers (a seeded run). The
  // onboarding marker must still be unset: that is what makes this a cold start.
  const seeded = process.env.E2E_SEEDED === '1';
  const cfg = await ask(conn, { type: 'getConfig' }, 'configData');
  // 🔴 契约（WsConfigHandlers.handleGetConfig + onboarding.js initOnboarding）：
  // `onboarding: null` = 还没有任何 marker（全新安装）= 引导该跑；'done'/'skipped'
  // 才是永不再跑。所以冷启动的正确读数就是 **null**（不是 'pending'）。
  check('A1 cold start: no onboarding marker yet (fresh install => flow must run)',
    cfg.onboarding === null, `onboarding=${JSON.stringify(cfg.onboarding)} configured=${cfg.configured}`);
  if (!seeded) {
    check('A2 cold start: reports the isolated home as unconfigured',
      cfg.configured === false, `configured=${cfg.configured}`);
  } else {
    check('A2 seeded run: providers present but the onboarding marker is still unset',
      cfg.configured === true, `configured=${cfg.configured}`);
  }

  // ── D1. Model list comes from the same config store as Settings ────────
  const models0 = await askModels(conn);
  if (!seeded) {
    check('D1 zero providers => empty candidate list (no second data source invented)',
      models0.source === 'configured' && Array.isArray(models0.models) && models0.models.length === 0,
      `source=${models0.source} models=${models0.models?.length}`);
  } else {
    check('D1 seeded: candidate list is non-empty and marked as the configured source',
      models0.source === 'configured' && Array.isArray(models0.models) && models0.models.length > 0,
      `source=${models0.source} models=${models0.models?.length} providers=${JSON.stringify(models0.providers)}`);
    const bad = (models0.models || []).filter((m) => !(m.effectiveContextWindow > 0) || m.effectiveContextWindow > m.contextWindow);
    check('D1 seeded: every row carries effective <= configured (clamp arithmetic)',
      bad.length === 0, `offending=${JSON.stringify(bad.slice(0, 2))}`);
  }

  // ── B. Questionnaire walk (per-question upsert) ────────────────────────
  const NAME = process.env.E2E_AGENT_NAME || '小满';
  const CALL = process.env.E2E_USER_CALL || '阿凯';
  const FOCUS = '把这批真系统改造落地，别在细节上走样。';
  const answers = {};
  for (const q of SCRIPT) {
    if (q.id === 'model') continue; // the model question is answered through the config store (D below)
    let a;
    if (q.id === 'name') a = answerFrame(q.id, q.label, q.scope, 'free', NAME);
    else if (q.id === 'call') a = answerFrame(q.id, q.label, q.scope, 'free', CALL);
    else if (q.id === 'focus') a = answerFrame(q.id, q.label, q.scope, 'free', FOCUS);
    else if (q.kind === 'multi') a = answerFrame(q.id, q.label, q.scope, 'multi', '', ['先问再做', '别替我拍板']);
    else a = answerFrame(q.id, q.label, q.scope, 'choice', `${q.label}的选项A`);
    answers[q.id] = a;
    const saved = await ask(conn, { type: 'setOnboardingAnswers', answers: { [q.id]: a } }, 'onboardingAnswersSaved');
    check(`B upsert ${q.id}: count=1`, saved.count === 1, `count=${saved.count}`);
  }

  const fin = await ask(conn, { type: 'finishOnboarding', answers }, 'onboardingArtifacts', 20000);
  check('B finish: artifacts frame carries both peer paths',
    typeof fin.soulPath === 'string' && typeof fin.userPath === 'string',
    `${fin.soulPath} | ${fin.userPath}`);
  check('B finish: Soul.md and User.md are peers at the home root',
    fin.soulPath === join(HOME, 'Soul.md') && fin.userPath === join(HOME, 'User.md'),
    `soul=${fin.soulPath} user=${fin.userPath}`);
  check('B finish: both files non-empty on disk',
    existsSync(fin.soulPath) && existsSync(fin.userPath) &&
    statSync(fin.soulPath).size === fin.soulBytes && statSync(fin.userPath).size === fin.userBytes,
    `soul=${fin.soulBytes}B user=${fin.userBytes}B`);
  const soul = readFileSync(fin.soulPath, 'utf8');
  const user = readFileSync(fin.userPath, 'utf8');
  check('B Soul.md carries the free-form name verbatim', soul.includes(NAME), NAME);
  check('B User.md carries the user-call answer', user.includes(CALL), CALL);
  check('B Soul.md carries the free-form quote under 你说过的原话',
    soul.includes('## 你说过的原话') && soul.includes(NAME), 'name quote');
  check('B User.md carries the free-form focus quote verbatim', user.includes(FOCUS), FOCUS);
  check('B name written to displayName (mechanism dir unchanged)',
    fin.agentName === NAME, `agentName=${fin.agentName}`);
  const agentJson = JSON.parse(readFileSync(join(HOME, 'agents', 'Nebula', 'agent.json'), 'utf8'));
  check('B mechanism key frozen: dir is Nebula/', existsSync(join(HOME, 'agents', 'Nebula', 'agent.json')), 'agents/Nebula/agent.json');
  check('B agent.json carries displayName but name stays the mechanism key',
    agentJson.displayName === NAME && agentJson.name === 'Nebula',
    `name=${agentJson.name} displayName=${agentJson.displayName}`);

  // ── F. The gate itself REFUSES (must be exercised, not bypassed) ───────
  // A fresh home with no probe on record: `setOnboardingState('done')` must be
  // answered by {type:'error',code:'probe_required'} — the very gate that the
  // original coverage walked around (both by never sending the frame and by
  // using the legacy `writeState` path in the spec).
  const refused = await ask(conn, { type: 'setOnboardingState', state: 'done' }, 'error', 8000)
    .catch((e) => ({ __err: String(e) }));
  check('F gate refuses done without a probe (code=probe_required)',
    refused.code === 'probe_required', `code=${refused.code} message=${refused.message}`);
  const markerAfterRefusal = await readMarker(conn);
  check('F refused transition leaves the marker unmoved (file + frame agree)',
    markerAfterRefusal.disk?.state !== 'done' && markerAfterRefusal.frame !== 'done',
    `disk=${markerAfterRefusal.disk?.state} frame=${markerAfterRefusal.frame}`);
  // …and the SAME home must still be recoverable: the refusal is not a dead end.
  check('F a refusal does not leave artifact files half-written behind the user’s back',
    existsSync(join(HOME, 'Soul.md')), 'Soul.md present from section B');

  // ── C. All-skipped path — reaches done for real ────────────────────────
  // Point the isolated config at the LOCAL stub (the same single write path the
  // Settings panel uses) so the probe below is a real network call that really
  // succeeds. Its baseUrl is loopback: nothing leaves this machine.
  const cfgForStub = await ask(conn, { type: 'getConfig' }, 'configData');
  const baseForStub = (() => {
    try { return JSON.parse(cfgForStub.config || '{}'); } catch { return {}; }
  })();
  const cfgWithStub = {
    ...baseForStub,
    llm: {
      ...(baseForStub.llm || {}),
      providers: {
        ...((baseForStub.llm || {}).providers || {}),
        e2estub: {
          baseUrl: `${stub.url}/`,
          apiKey: 'stub-key',
          protocol: 'openai',
          models: [{ id: 'stub-model', contextWindow: 128000 }],
        },
      },
    },
  };
  const updStub = await ask(conn, { type: 'updateConfig', config: JSON.stringify(cfgWithStub) }, 'configUpdated', 15000)
    .catch((e) => ({ __err: String(e) }));
  check('C stub provider wired into the isolated config store', !updStub.__err, updStub.__err || `type=${updStub.type}`);

  const skippedAnswers = {};
  for (const q of SCRIPT) skippedAnswers[q.id] = answerFrame(q.id, q.label, q.scope, 'skip', '');
  const fin2 = await ask(conn, { type: 'finishOnboarding', answers: skippedAnswers }, 'onboardingArtifacts', 20000);
  const soul2 = readFileSync(fin2.soulPath, 'utf8');
  const user2 = readFileSync(fin2.userPath, 'utf8');
  check('C all-skipped: still writes both files non-empty',
    fin2.soulBytes > 0 && fin2.userBytes > 0, `soul=${fin2.soulBytes}B user=${fin2.userBytes}B`);
  check('C all-skipped: Soul.md keeps the ## 备注 skeleton section',
    soul2.includes('## 备注'), '## 备注');
  for (const sec of ['## 自我认知', '## 相处之道', '## 你说过的原话', '## 成长约定']) {
    check(`C all-skipped: Soul.md keeps ${sec}`, soul2.includes(sec), sec);
  }
  for (const sec of ['## 称呼与身份', '## 当前关注', '## 你说过的原话', '## 记录规则']) {
    check(`C all-skipped: User.md keeps ${sec}`, user2.includes(sec), sec);
  }
  check('C all-skipped: per-field placeholders, no empty holes',
    soul2.includes('未设置（这一题你跳过了）') || soul2.includes('未设置（随时可以再来起一个）'),
    'placeholder rows');

  // 🔴 The transition that used to be MISSING from this harness. The frontend's
  // terminal sequence probes first, then writes the marker; do the same order.
  const hitsBefore = stub.hits();
  const probeSkip = await ask(conn, { type: 'probeLlm' }, 'probeResult', 25000);
  check('C all-skipped: a real probe through the chosen brain succeeds',
    probeSkip.ok === true, `ok=${probeSkip.ok} error=${probeSkip.error}`);
  check('C all-skipped: the probe really reached the configured provider (stub hit count grew)',
    stub.hits() > hitsBefore, `hits ${hitsBefore} -> ${stub.hits()}`);
  const stSkip = await ask(conn, { type: 'setOnboardingState', state: 'done' }, 'onboardingStateSet', 8000)
    .catch((e) => ({ __err: String(e) }));
  check('C all-skipped: setOnboardingState(done) is ACCEPTED after the probe',
    stSkip.state === 'done', stSkip.__err || `state=${stSkip.state}`);
  const markerSkip = await readMarker(conn);
  check('C all-skipped: the marker on disk says done (cold start is finishable)',
    markerSkip.disk?.state === 'done' && markerSkip.frame === 'done',
    `disk=${markerSkip.disk?.state} frame=${markerSkip.frame}`);
  check('C all-skipped: a successful probe is recorded (probeOkAt on disk)',
    typeof markerSkip.disk?.probeOkAt === 'number', `probeOkAt=${markerSkip.disk?.probeOkAt}`);

  // ── D. Model step: same source + two-way consistency ───────────────────
  // Write a provider into the SAME store the Settings panel uses (updateConfig),
  // then read the candidate list back through the onboarding frame and compare.
  const currentRaw = await ask(conn, { type: 'getConfig' }, 'configData');
  const baseCfg = (() => {
    try { return JSON.parse(currentRaw.config || '{}'); } catch { return {}; }
  })();
  const wired = {
    ...baseCfg,
    llm: {
      ...(baseCfg.llm || {}),
      providers: {
        ...((baseCfg.llm || {}).providers || {}),
        e2eprov: {
          baseUrl: 'https://example.invalid/v1/',
          apiKey: 'k',
          protocol: 'openai',
          models: [{ id: 'glm-4.6', contextWindow: 128000 }],
        },
      },
    },
  };
  const upd1 = await ask(conn, { type: 'updateConfig', config: JSON.stringify(wired) }, 'configUpdated', 15000)
    .catch((e) => ({ __err: String(e) }));
  check('D1 settings-store write accepted (updateConfig)', !upd1.__err, upd1.__err || `type=${upd1.type}`);

  const models1 = await askModels(conn);
  const refs = (models1.models || []).map((m) => m.ref);
  check('D2 candidate list reflects the config store (same source as Settings)',
    refs.includes('e2eprov/glm-4.6'), `refs=${JSON.stringify(refs)}`);
  check('D2 providers list matches the configured provider',
    (models1.providers || []).includes('e2eprov'), `providers=${JSON.stringify(models1.providers)}`);
  const row = (models1.models || []).find((m) => m.ref === 'e2eprov/glm-4.6');
  check('D2 effective window = min(configured, modelMaxContext)',
    row && row.effectiveContextWindow === 128000, `effective=${row?.effectiveContextWindow}`);

  // ── E. PRIMARY terminal path: pick a configured model, then finish ──────
  // This is the path the author's item ② foregrounds (choose a model from the
  // already-configured providers) AND the one the verifier proved could never
  // reach done. It is exercised here through the stub so the probe inside the
  // terminal sequence is a real call that really succeeds — deterministic, no
  // third-party network, no credentials.
  const finModel = await ask(conn, {
    type: 'finishOnboarding',
    answers: {
      name: answerFrame('name', '名字', 'base', 'free', NAME),
      model: answerFrame('model', '大脑配置', 'base', 'model', 'e2estub/stub-model'),
    },
    modelRef: 'e2estub/stub-model',
    contextWindow: 64000,
  }, 'onboardingArtifacts', 20000);
  check('E primary: artifacts write reports no error', !finModel.modelWriteError, `modelWriteError=${finModel.modelWriteError}`);

  const models2 = await askModels(conn);
  const row2 = (models2.models || []).find((m) => m.ref === 'e2estub/stub-model');
  check('E primary: two-way consistency — onboarding write read back via the Settings data source',
    row2 && row2.contextWindow === 64000 && row2.effectiveContextWindow === 64000,
    `contextWindow=${row2?.contextWindow} effective=${row2?.effectiveContextWindow}`);
  const onDisk = JSON.parse(readFileSync(join(HOME, 'nebflow.json'), 'utf8'));
  check('E primary: the SAME config file on disk carries the new window',
    onDisk.llm?.providers?.e2estub?.models?.[0]?.contextWindow === 64000,
    `disk=${onDisk.llm?.providers?.e2estub?.models?.[0]?.contextWindow}`);
  check('E primary: the write-back did not drop unrelated config keys',
    onDisk.llm?.providers?.e2estub?.baseUrl === `${stub.url}/`,
    'stub baseUrl preserved');

  // The terminal sequence the frontend now runs: finish → probe → marker. Replay
  // it in order against a home whose marker is already done (accepted either way)
  // so the ORDER itself is under test: the probe must come before the marker.
  const hitsBeforeE = stub.hits();
  const probeE = await ask(conn, { type: 'probeLlm' }, 'probeResult', 25000);
  check('E primary: the probe on the picked model succeeds', probeE.ok === true, `ok=${probeE.ok} error=${probeE.error}`);
  check('E primary: the probe reached the picked provider (stub hit count grew)',
    stub.hits() > hitsBeforeE, `hits ${hitsBeforeE} -> ${stub.hits()}`);
  const stE = await ask(conn, { type: 'setOnboardingState', state: 'done' }, 'onboardingStateSet', 8000)
    .catch((e) => ({ __err: String(e) }));
  check('E primary: setOnboardingState(done) accepted after the probe', stE.state === 'done', stE.__err || `state=${stE.state}`);
  const markerE = await readMarker(conn);
  check('E primary: marker on disk + frame both say done',
    markerE.disk?.state === 'done' && markerE.frame === 'done',
    `disk=${markerE.disk?.state} frame=${markerE.frame}`);

  // ── D4. The FRONT END actually sends the window ────────────────────────
  // The frame contract above proves the engine honours `contextWindow`; this
  // proves the model question is wired to send it (the author's item ② asks for
  // BOTH a detected model list and a settable context size — a UI that never
  // emits the field would pass every D1–D3 check while leaving the feature dead).
  const jsPath = process.env.E2E_ONBOARDING_JS || '';
  if (jsPath && existsSync(jsPath)) {
    const src = readFileSync(jsPath, 'utf8');
    check('D4 front end sets a context window on the model question',
      /payload\.contextWindow\s*=/.test(src) && /data-ctx-window/.test(src),
      'payload.contextWindow + data-ctx-window present');
    check('D4 front end lists the configured providers as the primary path',
      src.includes("type: 'getOnboardingModels'"), 'getOnboardingModels frame');
    check('D4 front end carries the skip control on every card kind',
      src.includes('data-skip'), 'data-skip');
    // 🔴 The terminal transition must be OBSERVED, not fired and forgotten: the
    // flow probes before it writes the marker, it AWAITS `setOnboardingState`, and
    // it only plays the success finale once the marker actually landed. A source
    // that still calls setOnboardingState without awaiting its verdict is exactly
    // the shape that made a refused cold start unfinishable.
    check('D4 front end probes before writing the terminal marker',
      /await probeLlm\(\)/.test(src) && /const st = await setOnboardingState/.test(src),
      'probeLlm awaited + setOnboardingState awaited');
    check('D4 front end does not fire the marker write without a verdict',
      !/^\s*setOnboardingState\('done'\);\s*$/m.test(src), 'no bare fire-and-forget done write');
    check('D4 the probe gate refusal has its own message bucket (不是「记忆写入失败」)',
      src.includes("'probe_required'") && src.includes('ob2.finish.probeRequired'),
      'probe_required bucket');
    check('D4 front end offers a retry for a refused terminal step',
      src.includes('data-retry-finish') && src.includes('ob2.finish.retry'),
      'retry control');
  } else {
    check('D4 front-end source check', false, `E2E_ONBOARDING_JS not set or missing: ${jsPath}`);
  }
} finally {
  conn.ws.close();
  await stub.close();
}

console.log(`\nE2E isolated onboarding: ${pass} passed, ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);

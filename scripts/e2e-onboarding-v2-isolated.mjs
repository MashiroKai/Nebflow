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
//   C. All-skipped path: an 11/11 skip run still reaches done and leaves a
//      structurally complete skeleton (## 备注 present in Soul.md).
//   D. Model step same-source + two-way consistency: getOnboardingModels lists
//      exactly the providers/models of the config store the Settings panel
//      writes, and a context-window write-back is read back from that same
//      store (updateConfig -> config file -> the next getOnboardingModels read).
//
// Usage:
//   NEBFLOW_URL=http://127.0.0.1:8687 NEBFLOW_HOME_DIR=$(mktemp -d) \
//     node scripts/e2e-onboarding-v2-isolated.mjs
//
// Exit code 0 = every check passed. Non-zero = at least one FAIL.

import { readFileSync, existsSync, statSync } from 'node:fs';
import { join } from 'node:path';

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
function wsConnect() {
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
try {
  // ── A. Cold start reading ──────────────────────────────────────────────
  // `configured` is true when the home ships providers (a seeded run). The
  // onboarding marker must still be unset: that is what makes this a cold start.
  const seeded = process.env.E2E_SEEDED === '1';
  const cfg = await ask(conn, { type: 'getConfig' }, 'configData');
  check('A1 cold start: isolated home reports onboarding=pending',
    cfg.onboarding === 'pending', `onboarding=${JSON.stringify(cfg.onboarding)} configured=${cfg.configured}`);
  if (!seeded) {
    check('A2 cold start: reports the isolated home as unconfigured',
      cfg.configured === false, `configured=${cfg.configured}`);
  } else {
    check('A2 seeded run: providers present but the onboarding marker is still unset',
      cfg.configured === true, `configured=${cfg.configured}`);
  }

  // ── D1. Model list comes from the same config store as Settings ────────
  const models0 = await ask(conn, { type: 'getOnboardingModels' }, 'onboardingModels');
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

  // ── C. All-skipped path ────────────────────────────────────────────────
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

  const models1 = await ask(conn, { type: 'getOnboardingModels' }, 'onboardingModels');
  const refs = (models1.models || []).map((m) => m.ref);
  check('D2 candidate list reflects the config store (same source as Settings)',
    refs.includes('e2eprov/glm-4.6'), `refs=${JSON.stringify(refs)}`);
  check('D2 providers list matches the configured provider',
    (models1.providers || []).includes('e2eprov'), `providers=${JSON.stringify(models1.providers)}`);
  const row = (models1.models || []).find((m) => m.ref === 'e2eprov/glm-4.6');
  check('D2 effective window = min(configured, modelMaxContext)',
    row && row.effectiveContextWindow === 128000, `effective=${row?.effectiveContextWindow}`);

  // Write-back through the onboarding frame, then read the store back directly.
  const finModel = await ask(conn, {
    type: 'finishOnboarding',
    answers: {
      name: answerFrame('name', '名字', 'base', 'free', NAME),
      model: answerFrame('model', '大脑配置', 'base', 'model', 'e2eprov/glm-4.6'),
    },
    modelRef: 'e2eprov/glm-4.6',
    contextWindow: 64000,
  }, 'onboardingArtifacts', 20000);
  check('D3 write-back reported no error', !finModel.modelWriteError, `modelWriteError=${finModel.modelWriteError}`);

  const models2 = await ask(conn, { type: 'getOnboardingModels' }, 'onboardingModels');
  const row2 = (models2.models || []).find((m) => m.ref === 'e2eprov/glm-4.6');
  check('D3 two-way consistency: onboarding write read back through Settings data source',
    row2 && row2.contextWindow === 64000 && row2.effectiveContextWindow === 64000,
    `contextWindow=${row2?.contextWindow} effective=${row2?.effectiveContextWindow}`);
  const onDisk = JSON.parse(readFileSync(join(HOME, 'nebflow.json'), 'utf8'));
  check('D3 the SAME config file on disk carries the new window',
    onDisk.llm?.providers?.e2eprov?.models?.[0]?.contextWindow === 64000,
    `disk=${onDisk.llm?.providers?.e2eprov?.models?.[0]?.contextWindow}`);
  check('D3 the write-back did not drop unrelated config keys',
    onDisk.llm?.providers?.e2eprov?.baseUrl === 'https://example.invalid/v1/',
    'baseUrl preserved');

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
  } else {
    check('D4 front-end source check', false, `E2E_ONBOARDING_JS not set or missing: ${jsPath}`);
  }
} finally {
  conn.ws.close();
}

console.log(`\nE2E isolated onboarding: ${pass} passed, ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);

// onboarding.js — chat-native first-run onboarding (v2 conversational questionnaire).
//
// v2 (personal-agent batch 2026-10-04) replaces the old modal wizard / v1 fixed
// greeting with the demo-established conversational flow:
//   demo/personal-agent-onboarding-v2.html  (79107 B / 1731 lines / sha256
//   b8d0bb0c1b7bcccb6388d565d06ab9f0736ce2e0a1f8afd98b2a69e7cc540205)
//   design note = .nebflow/onboarding/20261004_011652_v2-interaction-design__personal-agent-onboarding.md
//
// Shape (v2 demo SCRIPT, single source — 11 questions, one card each, order = run order):
//   1  name       free-first   Agent display name (→ Soul.md + agent.json displayName)
//   2  call       free-first   how to address the user (→ User.md)
//   3  model      choice+fields brain config (configured providers → same config store as Settings)
//   4  role       choice       Soul.md 角色定位
//   5  style      choice       Soul.md 语言风格
//   6  pro        choice       Soul.md 主动程度
//   7  boundaries multi        Soul.md 相处约定
//   8  temper1    choice       Soul.md 语气基线
//   9  temper2    choice       Soul.md 做事方式
//   10 identity   choice       User.md 日常身份
//   11 focus      free-first   User.md 当前关注
//
// Discipline (task book §2 B):
//   - every question carries 跳过这一题 (no exception — model / name / call too);
//   - choice questions carry a permanent 「都不是，我自己说」 free-input fallback;
//   - question text streams FIRST, then a 240ms beat, THEN the option card appears
//     (🔴 no instant card pop);
//   - the pseudo-streaming reuses the note's own render layer, which is the SAME
//     typewriter family the previous onboarding already used in this file (typeInto /
//     simBubble) — no second typewriter is introduced;
//   - answers persist per question (WS setOnboardingAnswers, per-question upsert) and
//     the artifacts (Soul.md / User.md / displayName) are written once at finish
//     (WS finishOnboarding).
//
// Backend contract (gateway/WsConfigHandlers.scala):
//   getConfig            → configData { configured, onboarding:'pending'|'done'|'skipped'|null, config }
//   setOnboardingState   → onboardingStateSet { state }
//   setOnboardingAnswers { answers:{id:{kind,value,values?,label,scope}} } → onboardingAnswersSaved { count }
//   getOnboardingModels  → onboardingModels { source, providers[], models[] }
//   probeLlm             → probeResult { ok, error? }        (15s backend timeout)
//   finishOnboarding     { answers, modelRef?, contextWindow? } → onboardingArtifacts { ... }
//   updateConfig         → configUpdated (same single write path the Settings panel uses)

import state from './state.js';
import { sendWs, onMessage } from './ws.js';
import { activeView } from './chatView.js';
import { t, getLocale } from './i18n.js';
import { smartScroll, escapeHtml } from './utils.js';
import { rootDisplayName as rootName } from './rootName.js';

const PROBE_TIMEOUT_MS = 20000; // backend times out at 15s; this is the last-resort guard
const MODEL_REQ_TIMEOUT_MS = 8000;

let started = false;           // trigger once per boot (configData re-fires on config save)
let busy = false;              // finish-in-flight lock (double-click guard)
let pendingProbe = null;       // { resolve, timer } while a probeLlm is in flight
let pendingModels = null;      // { resolve, timer } while a getOnboardingModels is in flight
let activeSim = null;          // current typewriter controller { finish } — for Esc / replay cleanup

// ── probeLlm hard gate ───────────────────────────────────────
onMessage('probeResult', (msg) => {
  if (!pendingProbe) return;
  clearTimeout(pendingProbe.timer);
  const { resolve } = pendingProbe;
  pendingProbe = null;
  resolve({ ok: !!msg.ok, error: msg.error || '' });
});

function probeLlm() {
  return new Promise((resolve) => {
    pendingProbe = { resolve, timer: setTimeout(() => {
      pendingProbe = null;
      resolve({ ok: false, error: t('onboarding.probeTimeout') });
    }, PROBE_TIMEOUT_MS) };
    sendWs({ type: 'probeLlm' });
  });
}

// ── configured model list (same source as the Settings panel) ─
onMessage('onboardingModels', (msg) => {
  if (!pendingModels) return;
  clearTimeout(pendingModels.timer);
  const { resolve } = pendingModels;
  pendingModels = null;
  resolve({ providers: msg.providers || [], models: msg.models || [] });
});

function getOnboardingModels() {
  return new Promise((resolve) => {
    pendingModels = { resolve, timer: setTimeout(() => {
      pendingModels = null;
      resolve({ providers: [], models: [] });
    }, MODEL_REQ_TIMEOUT_MS) };
    sendWs({ type: 'getOnboardingModels' });
  });
}

// ── State writes ─────────────────────────────────────────────
function setOnboardingState(next) {
  sendWs({ type: 'setOnboardingState', state: next });
}

/** Persist one question's answer immediately (per-question upsert; the terminal
 *  write of Soul.md / User.md happens once at finish). */
function persistAnswer(rec) {
  sendWs({ type: 'setOnboardingAnswers', answers: { [rec.id]: rec } });
}

// ── Typewriter engine (§4 of the design note) ────────────────
// Pure render-layer simulation: char-by-char into .ob-para divs with punctuation
// pauses, segment gaps, blinking cursor, skip (bubble click / Esc / skip button)
// → instant full text; reduced-motion → instant full text. Parameter set matches
// the v2 design note (30/130/220/320).
const TYPING_MS = 30;
const PUNCT_PAUSE_MS = 130;
const DASH_PAUSE_MS = 220;
const SEGMENT_GAP_MS = 320;
const CARD_BEAT_MS = 240;      // beat between "question finished streaming" and card reveal

function reducedMotion() {
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/** Type segments into the .ob-para children of `body`. Returns { finish } —
 *  finish() instantly completes all remaining text and fires onDone once. */
function typeInto(body, segments, { onDone = null } = {}) {
  const paras = [...body.querySelectorAll('.ob-para')];
  let cancelled = false;
  let seg = 0, pos = 0;
  let timer = null;
  let doneFired = false;

  const cursor = document.createElement('span');
  cursor.className = 'ob-cursor';
  cursor.setAttribute('aria-hidden', 'true');

  function fireDone() {
    if (doneFired) return;
    doneFired = true;
    if (onDone) onDone();
  }
  function clearTimer() { if (timer) { clearTimeout(timer); timer = null; } }
  function fillAll() {
    segments.forEach((s, i) => { if (paras[i] && paras[i].textContent.length < s.length) paras[i].textContent = s; });
  }
  function finish() {
    if (cancelled) return;
    cancelled = true;
    clearTimer();
    fillAll();
    cursor.remove();
    activeSim = null;
    fireDone();
  }
  function pump() {
    if (cancelled) return;
    const text = segments[seg] || '';
    if (pos < text.length) {
      paras[seg].textContent = text.slice(0, pos + 1);
      const ch = text[pos];
      let delay = TYPING_MS;
      if (ch === '—' && text[pos + 1] === '—') delay = DASH_PAUSE_MS;
      else if ('，。？；：！、,.?;:!'.includes(ch)) delay = PUNCT_PAUSE_MS;
      pos++;
      timer = setTimeout(pump, delay);
    } else if (seg + 1 < segments.length) {
      seg++; pos = 0;
      timer = setTimeout(pump, SEGMENT_GAP_MS);
    } else {
      cursor.remove();
      activeSim = null;
      fireDone();
    }
  }

  if (reducedMotion()) {
    fillAll();
    fireDone();
    return { finish: () => {} };
  }
  body.appendChild(cursor);
  activeSim = { finish };
  pump();
  return { finish };
}

/** Build a simulated AI bubble in the chat stream with the typewriter attached.
 *  `segments` = paragraphs of one bubble. Returns the row element. */
function simBubble(segments, { onDone = null, source = null, question = false } = {}) {
  const chat = activeView?.dom?.chat;
  if (!chat) return null;
  const row = document.createElement('div');
  row.className = 'row ai';
  const bubble = document.createElement('div');
  bubble.className = 'bubble ai ob-sim-bubble';
  const src = document.createElement('div');
  src.className = 'ask-user-source';
  src.textContent = source || sourceLabel();
  const body = document.createElement('div');
  body.className = 'ob-body';
  segments.forEach((_, i) => {
    const p = document.createElement('div');
    p.className = 'ob-para';
    if (question && i === segments.length - 1) p.classList.add('ob-question');
    body.appendChild(p);
  });
  bubble.append(src, body);
  row.appendChild(bubble);
  chat.appendChild(row);
  smartScroll();
  const ctrl = typeInto(body, segments, { onDone });
  if (!reducedMotion()) {
    const skip = document.createElement('button');
    skip.type = 'button';
    skip.className = 'ob-skip glass-control';
    skip.textContent = t('ob2.typing.skip');
    skip.setAttribute('data-skip-typing', '1');
    skip.addEventListener('click', ctrl.finish);
    bubble.appendChild(skip);
  }
  return row;
}

/** Stream one bubble and resolve once its text has fully arrived. */
function streamBubble(segments, opts = {}) {
  return new Promise((resolve) => {
    const row = simBubble(segments, { ...opts, onDone: () => resolve(row) });
    if (!row) resolve(null);   // no chat view ⇒ degrade gracefully
  });
}

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

// Esc skips the current typewriter (bound once at module scope).
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && activeSim) { activeSim.finish(); }
});

// ── Display name (the only place the root agent's name renders) ─
// The display-name read is owned by rootName.js (leaf module, single read
// point shared with micOrb / chat / modelPanel). `"Nebula"` is the mechanism key
// everywhere it is a judgement (task book §2 C whitelist) — only the DISPLAY face
// parameterizes.
function rootDisplayName() {
  return rootName();
}

function sourceLabel() {
  return rootDisplayName().toUpperCase();
}

/** Apply a newly chosen name everywhere the display surface reads it. */
function renameEverywhere(name) {
  state.rootDisplayName = name;
  const self = (state.agentsData || []).find((x) => x.name === 'Nebula');
  if (self) self.displayName = name;
}

// ── Provider presets (field-face fallback for the model question) ─
// 🔴 The PRIMARY path of the model question is the configured-provider list
// (getOnboardingModels / same source as Settings). These presets only seed the
// Base URL / model suggestions when the user picks "add a provider" — the same
// fallback face v1 had, per task book §2 B.
const PRESETS = {
  zhipu:   { label: '智谱 GLM',     name: 'zhipu',    baseUrl: 'https://open.bigmodel.cn/api/paas/v4/', models: ['glm-4.6', 'glm-4.5-air', 'glm-4.5-flash'] },
  deepseek:{ label: 'DeepSeek',     name: 'deepseek', baseUrl: 'https://api.deepseek.com/v1/',          models: ['deepseek-chat', 'deepseek-reasoner'] },
  kimi:    { label: 'Kimi',         name: 'kimi',     baseUrl: 'https://api.moonshot.cn/v1/',           models: ['kimi-k2-0905-preview', 'moonshot-v1-32k'] },
  compat:  { label: 'OpenAI 兼容',  name: 'custom',   baseUrl: 'https://api.openai.com/v1/',            models: ['gpt-4o', 'gpt-4o-mini'] },
  local:   { label: '本地 Ollama',  name: 'ollama',   baseUrl: 'http://localhost:11434/v1/',            models: ['qwen3:8b', 'llama3.1:8b'] },
};

// ── Question script (single source) ──────────────────────────
// Mirrors the v2 design note's SCRIPT table verbatim: id / act / lead / question /
// options / skip / free input / mapping target.
function SCRIPT() {
  return [
    {
      id: 'name', seg: t('onboarding.seg.meet'), scope: 'base', kind: 'free-first',
      intro: [t('ob2.intro.meet1'), t('ob2.intro.meet2')],
      lead: [t('ob2.lead.meet1')],
      q: t('ob2.q.name'),
      placeholder: t('ob2.ph.name'),
      chips: ['星尘', '洛书', '阿涅', 'Nova', '海豹'],
      maxLength: 16,
      label: t('ob2.label.name'),
    },
    {
      id: 'call', seg: t('onboarding.seg.meet'), scope: 'user', kind: 'free-first',
      lead: [t('ob2.lead.meet2')],
      q: t('ob2.q.call'),
      placeholder: t('ob2.ph.call'),
      chips: ['阿凯', '老板', '船长', '直接叫我名字'],
      maxLength: 12,
      label: t('ob2.label.call'),
    },
    {
      id: 'model', seg: t('onboarding.seg.brain'), scope: 'base', kind: 'model',
      lead: [t('ob2.lead.brain1'), t('ob2.lead.brain2')],
      q: t('ob2.q.model'),
      label: t('ob2.label.model'),
    },
    {
      id: 'role', seg: t('onboarding.seg.expect'), scope: 'soul', kind: 'choice',
      intro: [t('ob2.intro.expect1'), t('ob2.intro.expect2')],
      lead: [t('ob2.lead.role')],
      q: t('ob2.q.role'),
      options: [
        { label: t('ob2.opt.role.partner') }, { label: t('ob2.opt.role.keeper') },
        { label: t('ob2.opt.role.sparring') }, { label: t('ob2.opt.role.muse') },
        { label: t('ob2.opt.role.all') },
      ],
      freeHint: t('ob2.fh.role'),
      label: t('ob2.label.role'),
    },
    {
      id: 'style', seg: t('onboarding.seg.expect'), scope: 'soul', kind: 'choice',
      lead: [t('ob2.lead.style')],
      q: t('ob2.q.style'),
      options: [
        { label: t('ob2.opt.style.direct') }, { label: t('ob2.opt.style.gentle') },
        { label: t('ob2.opt.style.humor') }, { label: t('ob2.opt.style.rigorous') },
      ],
      freeHint: t('ob2.fh.style'),
      label: t('ob2.label.style'),
    },
    {
      id: 'pro', seg: t('onboarding.seg.expect'), scope: 'soul', kind: 'choice',
      lead: [t('ob2.lead.pro')],
      q: t('ob2.q.pro'),
      options: [
        { label: t('ob2.opt.pro.quiet'), desc: t('ob2.opt.pro.quietDesc') },
        { label: t('ob2.opt.pro.remind'), desc: t('ob2.opt.pro.remindDesc') },
        { label: t('ob2.opt.pro.active'), desc: t('ob2.opt.pro.activeDesc') },
      ],
      freeHint: t('ob2.fh.pro'),
      label: t('ob2.label.pro'),
    },
    {
      id: 'boundaries', seg: t('onboarding.seg.expect'), scope: 'soul', kind: 'multi',
      lead: [t('ob2.lead.boundaries')],
      q: t('ob2.q.boundaries'),
      options: [
        { label: t('ob2.opt.bd.argue'), desc: t('ob2.opt.bd.argueDesc') },
        { label: t('ob2.opt.bd.remember'), desc: t('ob2.opt.bd.rememberDesc') },
        { label: t('ob2.opt.bd.honest'), desc: t('ob2.opt.bd.honestDesc') },
        { label: t('ob2.opt.bd.hands'), desc: t('ob2.opt.bd.handsDesc') },
      ],
      freeHint: t('ob2.fh.boundaries'),
      label: t('ob2.label.boundaries'),
    },
    {
      id: 'temper1', seg: t('onboarding.seg.expect'), scope: 'soul', kind: 'choice',
      lead: [t('ob2.lead.temper1')],
      q: t('ob2.q.temper1'),
      options: [{ label: t('ob2.opt.t1.direct') }, { label: t('ob2.opt.t1.gentle') }],
      freeHint: t('ob2.fh.temper1'),
      label: t('ob2.label.temper1'),
    },
    {
      id: 'temper2', seg: t('onboarding.seg.expect'), scope: 'soul', kind: 'choice',
      lead: [t('ob2.lead.temper2')],
      q: t('ob2.q.temper2'),
      options: [{ label: t('ob2.opt.t2.plan') }, { label: t('ob2.opt.t2.casual') }],
      freeHint: t('ob2.fh.temper2'),
      label: t('ob2.label.temper2'),
    },
    {
      id: 'identity', seg: t('onboarding.seg.you'), scope: 'user', kind: 'choice',
      intro: [t('ob2.intro.you1'), t('ob2.intro.you2')],
      lead: [t('ob2.lead.identity')],
      q: t('ob2.q.identity'),
      options: [
        { label: t('ob2.opt.id.dev') }, { label: t('ob2.opt.id.student') },
        { label: t('ob2.opt.id.researcher') }, { label: t('ob2.opt.id.creator') },
        { label: t('ob2.opt.id.freelance') }, { label: t('ob2.opt.id.other') },
      ],
      freeHint: t('ob2.fh.identity'),
      label: t('ob2.label.identity'),
    },
    {
      id: 'focus', seg: t('onboarding.seg.you'), scope: 'user', kind: 'free-first',
      lead: [t('ob2.lead.focus')],
      q: t('ob2.q.focus'),
      placeholder: t('ob2.ph.focus'),
      maxLength: 140,
      label: t('ob2.label.focus'),
    },
  ];
}

// ── Answer collection (the JS-side answer table) ─────────────
const collected = new Map();   // id → { kind, value, values?, label, scope }

/**
 * The context window the user chose on the model question (author item ②), sent
 * as the top-level `contextWindow` of the finishOnboarding frame. `null` = the
 * user did not set one ⇒ the engine keeps the model's configured value. It is
 * deliberately NOT part of `collected`: the window is brain config, not a memory
 * answer, so it must not leak into Soul.md / User.md.
 */
let modelContextWindow = null;

function record(def, kind, value, values) {
  const rec = { kind, label: def.label || def.id, scope: def.scope };
  if (kind === 'multi') { rec.values = values || []; rec.value = (values || []).join('；'); }
  else if (kind === 'skip') { rec.value = ''; }
  else { rec.value = value || ''; }
  collected.set(def.id, rec);
  persistAnswer({ ...rec });
  if (def.id === 'name' && kind !== 'skip' && rec.value) renameEverywhere(rec.value);
}

// ── Segment meta (from the design note: "第三幕 · 第 N / 11 题") ─
function segmentMeta(def, total, idx) {
  return `${def.seg} · ${t('ob2.progress', { n: idx, total })}`;
}

// ── Question cards ───────────────────────────────────────────
/** One question card appended into `bubble`. Resolves once the user answers,
 *  skips, or (choice) picks an option / free text. */
function askCard(def, bubble, total, idx) {
  return new Promise((resolve) => {
    let settled = false;

    const card = document.createElement('div');
    card.className = 'ask-card';
    card.setAttribute('role', 'group');
    card.setAttribute('data-q', def.id);
    card.setAttribute('aria-label', def.q);
    const meta = document.createElement('div');
    meta.className = 'ask-meta';
    meta.textContent = segmentMeta(def, total, idx);
    card.appendChild(meta);

    const finish = (kind, value, values, echo) => {
      if (settled) return;
      settled = true;
      record(def, kind, value, values);
      card.querySelectorAll('button, input').forEach((n) => { n.disabled = true; n.style.pointerEvents = 'none'; });
      card.style.opacity = '0.62';
      if (echo) userSay(echo);
      resolve({ kind, value, values });
    };
    const skip = () => finish('skip', '', null, t('ob2.echo.skip'));

    // shared free-input row (used by all three kinds)
    const freeRow = document.createElement('div');
    freeRow.className = 'free-row';
    const freeInput = document.createElement('input');
    freeInput.className = 'option-input';
    const freeBtn = document.createElement('button');
    freeBtn.type = 'button';
    freeBtn.className = 'glass-control ob-primary';
    freeBtn.textContent = t('ob2.btn.useThis');
    freeBtn.disabled = true;
    freeInput.placeholder = def.placeholder || def.freeHint || t('ob2.ph.free');
    if (def.maxLength) freeInput.maxLength = def.maxLength;
    freeInput.addEventListener('input', () => { freeBtn.disabled = freeInput.value.trim().length === 0; });
    freeBtn.addEventListener('click', () => {
      const v = freeInput.value.trim();
      if (!v) return;
      finish('free', v, null, v);
    });
    freeInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.isComposing && !freeBtn.disabled) { e.preventDefault(); freeBtn.click(); }
    });
    freeRow.append(freeInput, freeBtn);

    // common foot: always a skip control
    const foot = document.createElement('div');
    foot.className = 'ob-actions';
    const skipBtn = document.createElement('button');
    skipBtn.type = 'button';
    skipBtn.className = 'glass-control ob-ghost';
    skipBtn.textContent = t('ob2.btn.skip');
    skipBtn.setAttribute('data-skip', def.id);
    skipBtn.addEventListener('click', skip);
    const spacer = document.createElement('span');
    spacer.className = 'ob-spacer';
    foot.append(spacer, skipBtn);

    if (def.kind === 'free-first') {
      // Input is present from the start; chips only seed it.
      card.appendChild(freeRow);
      if (def.chips) {
        const chipRow = document.createElement('div');
        chipRow.className = 'chip-row';
        def.chips.forEach((c) => {
          const b = document.createElement('button');
          b.type = 'button';
          b.className = 'option-btn';
          b.textContent = c;
          b.setAttribute('data-chip', def.id);
          b.addEventListener('click', () => { freeInput.value = c; freeBtn.disabled = false; freeInput.focus(); });
          chipRow.appendChild(b);
        });
        card.appendChild(chipRow);
      }
      card.appendChild(foot);
      bubble.appendChild(card);
      smartScroll();
      if (!reducedMotion()) { try { freeInput.focus(); } catch { /* ignore */ } }
      return;
    }

    if (def.kind === 'model') { askModelCard(def, card, foot, freeRow, finish, total, idx); bubble.appendChild(card); smartScroll(); return; }

    // choice / multi
    const opts = document.createElement('div');
    opts.className = 'opt-row' + ((def.options || []).some((o) => o.desc) ? ' wide' : '');
    const picked = new Set();
    let multiOk = null;
    (def.options || []).forEach((o, i) => {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'option-btn';
      b.setAttribute('data-opt', def.id + ':' + i);
      if (def.kind === 'multi') {
        const check = document.createElement('span');
        check.className = 'option-check';
        b.appendChild(check);
      }
      const txt = document.createElement('span');
      txt.className = 'option-text';
      txt.textContent = o.label;
      if (o.desc) {
        const small = document.createElement('small');
        small.textContent = o.desc;
        txt.appendChild(small);
      }
      b.appendChild(txt);
      b.addEventListener('click', () => {
        if (def.kind === 'multi') {
          if (picked.has(o.label)) { picked.delete(o.label); b.classList.remove('picked'); }
          else { picked.add(o.label); b.classList.add('picked'); }
          if (multiOk) multiOk.disabled = picked.size === 0;
        } else {
          opts.querySelectorAll('.option-btn').forEach((x) => x.classList.remove('picked'));
          b.classList.add('picked');
          setTimeout(() => finish('choice', o.label, null, o.label), reducedMotion() ? 0 : 160);
        }
      });
      opts.appendChild(b);
    });
    card.appendChild(opts);

    // permanent free-input fallback entry (every choice/multi question)
    const ghost = document.createElement('button');
    ghost.type = 'button';
    ghost.className = 'option-btn opt-ghost';
    ghost.textContent = t('ob2.btn.sayMyself');
    ghost.setAttribute('data-free', def.id);
    const ghostRow = document.createElement('div');
    ghostRow.className = 'opt-row';
    ghostRow.style.marginTop = '8px';
    ghostRow.appendChild(ghost);
    card.appendChild(ghostRow);
    freeRow.style.display = 'none';
    card.appendChild(freeRow);
    ghost.addEventListener('click', () => {
      freeRow.style.display = 'flex';
      ghostRow.style.display = 'none';
      smartScroll();
      try { freeInput.focus(); } catch { /* ignore */ }
    });

    if (def.kind === 'multi') {
      multiOk = document.createElement('button');
      multiOk.type = 'button';
      multiOk.className = 'glass-control ob-primary';
      multiOk.textContent = t('ob2.btn.picked');
      multiOk.setAttribute('data-multi-ok', def.id);
      multiOk.disabled = true;
      multiOk.addEventListener('click', () => finish('multi', [...picked].join('；'), [...picked], t('ob2.echo.boundaries', { v: [...picked].join('；') })));
      foot.appendChild(multiOk);
    }
    card.appendChild(foot);
    bubble.appendChild(card);
    smartScroll();
  });
}

/** Model question card: primary path = configured providers (same source as
 *  Settings); the provider field face stays as a fallback.
 *
 *  The context window is set HERE (author item ②: 「检测可用模型和设置上下文大小」).
 *  Picking a candidate reveals an inline window field prefilled with that model's
 *  configured value; the window travels as the top-level `contextWindow` of the
 *  finishOnboarding frame, which writes it into `llm.providers.<id>.models[].contextWindow`
 *  — the same store the Settings panel edits. From there the existing single chain
 *  (effectiveContextWindow → ModelCandidate.contextWindow → AgentState.contextWindow
 *  → MemoryBudget.injectionCapBytes) makes it drive the injection budget. */
function askModelCard(def, card, foot, freeRow, finish, total, idx) {
  const hint = document.createElement('div');
  hint.className = 'option-hint';
  hint.textContent = t('ob2.model.loading');
  card.appendChild(hint);

  const listWrap = document.createElement('div');
  listWrap.className = 'opt-row wide';
  card.appendChild(listWrap);

  // Context-window row: revealed once a candidate is picked. `ctxIn`'s value is
  // what actually leaves the card; the window is optional (blank / non-positive
  // ⇒ omitted, and the engine keeps the configured value).
  const ctxRow = document.createElement('div');
  ctxRow.className = 'ctx-row';
  ctxRow.style.display = 'none';
  const ctxLabel = document.createElement('span');
  ctxLabel.className = 'ctx-label';
  ctxLabel.textContent = t('ob2.model.ctxLabel');
  const ctxIn = document.createElement('input');
  ctxIn.className = 'option-input mono';
  ctxIn.type = 'number';
  ctxIn.min = '1';
  ctxIn.step = '1000';
  ctxIn.setAttribute('data-ctx-window', def.id);
  const ctxApply = document.createElement('button');
  ctxApply.type = 'button';
  ctxApply.className = 'glass-control ob-primary';
  ctxApply.textContent = t('ob2.model.ctxApply');
  ctxApply.disabled = true;
  ctxRow.append(ctxLabel, ctxIn, ctxApply);
  const ctxHint = document.createElement('div');
  ctxHint.className = 'option-hint';
  ctxHint.style.display = 'none';
  ctxHint.textContent = t('ob2.model.ctxHint');
  card.appendChild(ctxRow);
  card.appendChild(ctxHint);

  /** Positive integer or null (the engine's own non-positive semantics stay in charge). */
  const ctxValue = () => {
    const n = Number(ctxIn.value);
    return Number.isFinite(n) && n > 0 ? Math.trunc(n) : null;
  };
  // Committing = the candidate currently selected (null until a list row is
  // clicked; the fallback field face commits through `saveBtn`).
  let pickedRef = null;
  // True once the user edits the window themselves; a candidate pick prefills it,
  // and the fallback face must not carry a DIFFERENT model's prefill forward.
  let ctxTouched = false;
  const syncCtxApply = () => { ctxApply.disabled = pickedRef === null || ctxValue() === null; };
  ctxIn.addEventListener('input', () => { ctxTouched = true; syncCtxApply(); });

  ctxApply.addEventListener('click', () => {
    if (pickedRef === null || ctxValue() === null) return;
    modelContextWindow = ctxValue();
    finish('model', pickedRef, null, t('ob2.model.echoOk', { model: pickedRef }));
  });

  const fallbackBtn = document.createElement('button');
  fallbackBtn.type = 'button';
  fallbackBtn.className = 'option-btn opt-ghost';
  fallbackBtn.textContent = t('ob2.model.addProvider');
  fallbackBtn.style.marginTop = '8px';

  // field face (hidden until "add provider" is chosen)
  const fields = document.createElement('div');
  fields.style.display = 'none';
  const urlIn = document.createElement('input');
  urlIn.className = 'option-input mono';
  urlIn.placeholder = 'https://…';
  const keyIn = document.createElement('input');
  keyIn.className = 'option-input mono';
  keyIn.type = 'password';
  keyIn.placeholder = 'API Key';
  const modelIn = document.createElement('input');
  modelIn.className = 'option-input mono';
  modelIn.placeholder = 'Model ID';
  const saveBtn = document.createElement('button');
  saveBtn.type = 'button';
  saveBtn.className = 'glass-control ob-primary';
  saveBtn.textContent = t('onboarding.btn.testAndSave');
  saveBtn.disabled = true;
  fields.append(urlIn, keyIn, modelIn, saveBtn);
  card.appendChild(fields);
  card.appendChild(foot);

  function checkFields() {
    const ok = /^https?:\/\//.test(urlIn.value.trim()) && keyIn.value.trim().length > 0 && modelIn.value.trim().length > 0;
    saveBtn.disabled = !ok;
  }
  [urlIn, keyIn, modelIn].forEach((inp) => inp.addEventListener('input', checkFields));

  fallbackBtn.addEventListener('click', () => {
    listWrap.style.display = 'none';
    fallbackBtn.style.display = 'none';
    hint.textContent = '';
    fields.style.display = 'block';
    // The field face commits through `saveBtn`, so no candidate stays "picked"
    // (otherwise the window row could commit a ref the user navigated away from).
    pickedRef = null;
    syncCtxApply();
    // The window is settable on this face too (author item ②) — it commits with
    // `saveBtn`, so the standalone "use this window" button steps aside. A
    // prefill carried over from a discarded candidate is cleared unless the user
    // typed it themselves.
    if (!ctxTouched) ctxIn.value = '';
    ctxRow.style.display = 'flex';
    ctxHint.style.display = 'block';
    ctxApply.style.display = 'none';
    urlIn.value = PRESETS.zhipu.baseUrl;
    modelIn.value = PRESETS.zhipu.models[0];
    checkFields();
    smartScroll();
  });

  saveBtn.addEventListener('click', async () => {
    saveBtn.disabled = true;
    const probe = document.createElement('div');
    probe.className = 'ob-probing';
    probe.textContent = t('onboarding.probing');
    card.appendChild(probe);
    smartScroll();
    const data = {
      name: 'custom', baseUrl: urlIn.value.trim(), apiKey: keyIn.value.trim(), protocol: 'openai',
      models: [{ id: modelIn.value.trim() }],
    };
    if (!data.baseUrl.endsWith('/')) data.baseUrl += '/';
    // Same single write path the Settings panel uses: updateConfig → same store.
    sendWs({ type: 'updateConfig', config: withProviderPatch(data) });
    const res = await probeLlm();
    if (res.ok) {
      probe.classList.add('ok');
      probe.textContent = t('ob2.model.probeOk', { model: modelIn.value.trim() });
      // The fallback face carries its own window field value too (author item ②).
      modelContextWindow = ctxValue();
      finish('model', `${data.name}/${modelIn.value.trim()}`, null, t('ob2.model.echoOk', { model: modelIn.value.trim() }));
    } else {
      probe.textContent = t('onboarding.probeFailed', { error: res.error || '' });
      saveBtn.disabled = false;
    }
  });

  // load configured models (silently degrade to the fallback face when empty)
  getOnboardingModels().then((res) => {
    hint.textContent = '';
    if (!res.models.length) {
      hint.textContent = t('ob2.model.none');
      card.insertBefore(fallbackBtn, fields);
      return;
    }
    res.models.forEach((m) => {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'option-btn';
      b.setAttribute('data-model-ref', m.ref);
      const txt = document.createElement('span');
      txt.className = 'option-text';
      txt.textContent = m.displayLabel || m.ref;
      const small = document.createElement('small');
      small.textContent = t('ob2.model.ctx', { n: m.effectiveContextWindow });
      txt.appendChild(small);
      b.appendChild(txt);
      // First click selects + reveals the window row prefilled with the model's
      // configured value; "用这个窗口" commits. This keeps the window an explicit
      // user decision instead of a silent side effect of picking a model.
      b.addEventListener('click', () => {
        pickedRef = m.ref;
        listWrap.querySelectorAll('[data-model-ref]').forEach((n) => n.classList.remove('picked'));
        b.classList.add('picked');
        ctxRow.style.display = 'flex';
        ctxHint.style.display = 'block';
        // Picking a candidate IS choosing its window baseline: prefill the model's
        // configured value (switching candidates re-baselines, the user's next
        // edit makes it theirs).
        ctxIn.value = String(m.contextWindow || m.effectiveContextWindow || '');
        ctxTouched = false;
        syncCtxApply();
        smartScroll();
      });
      listWrap.appendChild(b);
    });
    card.insertBefore(fallbackBtn, fields);
    smartScroll();
  });
}

/** Build a config JSON carrying the fallback provider (same shape the Settings
 *  panel writes; merged over the live parsed config so other keys survive). */
function withProviderPatch(data) {
  const base = state.parsedConfig && typeof state.parsedConfig === 'object' ? state.parsedConfig : {};
  const providers = { ...(base.llm?.providers || {}) };
  providers[data.name] = {
    baseUrl: data.baseUrl, apiKey: data.apiKey, protocol: data.protocol, models: data.models,
  };
  return JSON.stringify({ ...base, llm: { ...(base.llm || {}), providers } });
}

// ── Flow orchestration ───────────────────────────────────────
/* ENABLEMENT (author ruling 2026-10-04): the onboarding flow is part of the real
   system now (Personal Agent batch). The flow runs on first start (state pending
   / no marker) and on explicit replay. It is NOT gated behind a localStorage flag
   any more. */
export function initOnboarding(msg) {
  if (started) return;
  const ob = msg.onboarding ?? null;
  if (ob === 'done' || ob === 'skipped') return;   // S0 — never again
  started = true;
  void runFlow();
}

/** /onboarding slash command + Settings "Re-run Onboarding" — replay the flow. */
export function replayOnboarding() {
  started = true;
  collected.clear();
  modelContextWindow = null;   // a replay must not inherit the previous run's window
  if (activeSim) activeSim.finish();
  void runFlow();
}

function userSay(text) {
  const chat = activeView?.dom?.chat;
  if (!chat) return;
  const row = document.createElement('div');
  row.className = 'row user';
  const bubble = document.createElement('div');
  bubble.className = 'bubble user';
  const tEl = document.createElement('div');
  tEl.textContent = text;
  bubble.appendChild(tEl);
  row.appendChild(bubble);
  chat.appendChild(row);
  smartScroll();
}

async function runFlow() {
  const total = SCRIPT().length;
  const script = SCRIPT();
  await streamBubble([t('ob2.greet1')], { source: t('ob2.source.unnamed') });
  await streamBubble([t('ob2.greet2'), t('ob2.greet3'), t('ob2.greet4')], { source: t('ob2.source.unnamed') });
  for (let i = 0; i < script.length; i++) {
    await askQuestion(script[i], total, i + 1);
  }
  await wait(300);
  await streamBubble([t('ob2.collected')]);
  await writingRitual();
  await finale();
}

async function askQuestion(def, total, idx) {
  if (def.intro) await streamBubble(def.intro);
  const { row } = await streamBubbleAsync(def.lead.concat([def.q]), { question: true });
  const bubble = row ? row.querySelector('.bubble') : null;
  if (!bubble) return;
  await wait(CARD_BEAT_MS);
  const ans = await askCard(def, bubble, total, idx);
  await wait(220);
  await streamBubble([followLine(def, ans)]);
}

/** streamBubble variant exposing the row. */
function streamBubbleAsync(segments, opts = {}) {
  return new Promise((resolve) => {
    const row = simBubble(segments, { ...opts, onDone: () => resolve({ row }) });
    if (!row) resolve({ row: null });
  });
}

/** The transition line after an answer (three sources, three voices). */
function followLine(def, ans) {
  if (!ans || ans.kind === 'skip') return t('ob2.follow.skip');
  if (ans.kind === 'free') return t('ob2.follow.free', { v: ans.value });
  if (def.id === 'model') return t('ob2.follow.model', { v: ans.value });
  if (def.kind === 'multi') return t('ob2.follow.multi');
  return t('ob2.follow.choice', { v: ans.value });
}

// ── Writing ritual + finale ──────────────────────────────────
async function writingRitual() {
  const row = await streamBubbleAsync([t('ob2.writing.title')]);
  const bubble = row.row ? row.row.querySelector('.bubble') : null;
  if (!bubble) return;
  const lines = document.createElement('div');
  lines.className = 'write-lines';
  const seq = [
    t('ob2.write.1'), t('ob2.write.2'), t('ob2.write.3'),
    t('ob2.write.4'), t('ob2.write.5'), t('ob2.write.6'),
  ];
  bubble.appendChild(lines);
  for (const text of seq) {
    const el = document.createElement('div');
    el.className = 'wl ok';
    el.textContent = text;
    lines.appendChild(el);
    smartScroll();
    await wait(220);
  }
  await wait(260);
  const answers = answersPayload();
  const modelRef = collected.get('model') && collected.get('model').kind !== 'skip' ? collected.get('model').value : null;
  finishOnboarding({ answers, modelRef });
}

let finishAcked = false;
onMessage('onboardingArtifacts', (msg) => {
  finishAcked = true;
  const row = activeView?.dom?.chat;
  if (row) {
    const note = document.createElement('div');
    note.className = 'row notice';
    const b = document.createElement('div');
    b.className = 'bubble';
    b.textContent = t('ob2.write.done', { soul: msg.soulPath, user: msg.userPath });
    if (msg.modelWriteError) b.textContent += ' ' + t('ob2.model.writeErr', { error: msg.modelWriteError });
    note.appendChild(b);
    row.appendChild(note);
    smartScroll();
  }
  setOnboardingState('done');
});

onMessage('error', (msg) => {
  if (!/onboarding/i.test(msg?.message || '')) return;
  const row = activeView?.dom?.chat;
  if (!row) return;
  const note = document.createElement('div');
  note.className = 'row notice';
  const b = document.createElement('div');
  b.className = 'bubble';
  b.textContent = t('ob2.write.failed', { error: msg.message });
  note.appendChild(b);
  row.appendChild(note);
  smartScroll();
});

function answersPayload() {
  const out = {};
  collected.forEach((v, k) => { out[k] = v; });
  return out;
}

function finishOnboarding({ answers, modelRef }) {
  const payload = { type: 'finishOnboarding', answers };
  if (modelRef) payload.modelRef = modelRef;
  // Explicit window (if the user set one) rides the SAME frame that carries the
  // model ref — one write path, one config store.
  if (modelRef && modelContextWindow) payload.contextWindow = modelContextWindow;
  sendWs(payload);
}

async function finale() {
  const n = collected.get('name');
  const name = n && n.kind !== 'skip' && n.value ? n.value : t('ob2.name.unnamed');
  const c = collected.get('call');
  const call = c && c.kind !== 'skip' && c.value ? c.value : t('ob2.you');
  const style = collected.get('style');
  const styleV = style && style.kind !== 'skip' ? style.value : '';
  const segs = finaleLines(name, call, styleV);
  await streamBubble(segs);
  const input = document.getElementById('input');
  if (input) {
    input.disabled = false;
    input.placeholder = t('ob2.input.ph', { name });
  }
}

/** Style-adaptive closing lines (from the v2 demo's finaleGreeting). */
function finaleLines(name, call, style) {
  const byStyle = {
    [t('ob2.opt.style.direct')]: [t('ob2.finale.direct1', { n: name }), t('ob2.finale.direct2')],
    [t('ob2.opt.style.gentle')]: [t('ob2.finale.gentle1', { n: name }), t('ob2.finale.gentle2')],
    [t('ob2.opt.style.humor')]: [t('ob2.finale.humor1', { n: name }), t('ob2.finale.humor2')],
    [t('ob2.opt.style.rigorous')]: [t('ob2.finale.rigorous1', { n: name }), t('ob2.finale.rigorous2')],
  };
  const segs = byStyle[style] || [t('ob2.finale.plain1', { n: name }), t('ob2.finale.plain2')];
  return [...segs, t('ob2.finale.hello', { u: call })];
}

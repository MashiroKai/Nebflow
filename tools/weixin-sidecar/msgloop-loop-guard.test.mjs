#!/usr/bin/env node
/**
 * 微信入站静默死亡批（2026-11-07）— the LIVE-LOOP GUARD leg.
 *
 * Incident shape (reproduced here, not narrated): the live inbound loop can
 * END and never come back while the shim process stays up and healthy from
 * launchd's point of view. `heartbeatMutex` swallowed every fs fault
 * (`catch { return false }`) with NO log, `liveLoop` turned that into a bare
 * `return`, and `startMsgLoop` answered it with a bare `return;` — permanent,
 * silent, per-process. Observed field state: lock file gone, cursor file stale
 * to the minute of the last kept message, zero msgloop lines afterwards, 5651
 * "inbound loop not active" sends in the meantime (a WARN that was hardcoded
 * false and therefore carried no information).
 *
 * Pinned here (all offline, zero iLink traffic, zero real network):
 *
 *   LG1 OWNERSHIP FAULT IS SURVIVABLE — a transient lock-file fault (the file
 *       disappears under a running loop) must NOT end inbound: polling resumes
 *       by itself. RED on the pre-batch code (polls freeze forever).
 *   LG2 THAT FAULT IS LOUD — the loss is named once (level + reason), so a log
 *       reader sees the event instead of a silence.
 *   LG3 THE ACTIVITY LEDGER IS REAL — `inboundLoopState()` reports active +
 *       monotone poll/exit counters, and is the ONE truth the send leg and the
 *       shim's /healthz read (the pre-batch shim hardcoded `() => false`).
 *   LG4 A CLEAN STOP IS NOT AN EXIT — aborting the loop leaves no re-arm churn
 *       (exit counter stays put), so production SIGTERM stays quiet.
 *
 * Run: node --test tools/weixin-sidecar/msgloop-loop-guard.test.mjs
 */
import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

// ── isolated state dir + account + token file (no host state is touched) ──
const STATE = fs.mkdtempSync(path.join(os.tmpdir(), "nb-loopguard-state-"));
const ACCOUNT = "loopguard-im-bot";
const ACCOUNTS = path.join(STATE, "openclaw-weixin", "accounts");
fs.mkdirSync(ACCOUNTS, { recursive: true });
fs.writeFileSync(path.join(STATE, "openclaw-weixin", "accounts.json"), JSON.stringify([ACCOUNT]));
fs.writeFileSync(path.join(ACCOUNTS, `${ACCOUNT}.json`), JSON.stringify({ token: "test-bot-token", baseUrl: "http://127.0.0.1:9" }));
const TOKEN_FILE = path.join(os.tmpdir(), `nb-loopguard-token-${process.pid}`);
fs.writeFileSync(TOKEN_FILE, "test-token-shape-only");
const LOCK = path.join(ACCOUNTS, `${ACCOUNT}.msgloop.lock`);

process.env.WEIXIN_MSGLOOP_STATE_DIR = STATE;
process.env.WEIXIN_MSGLOOP_INGRESS_URL = "http://127.0.0.1:9/ingress";
process.env.WEIXIN_SEND_TOKEN_FILE = TOKEN_FILE;

// ── log capture: the loop's loudness is part of the contract ──
const lines = [];
const realLog = console.log;
const realErr = console.error;
const grab = (stream) => (...args) => {
  lines.push({ level: stream, text: args.map((a) => String(a)).join(" ") });
};
console.log = grab("out");
console.error = grab("err");

// ── a getUpdates stub: instant, empty, counted (the poll is the cycle) ──
// The stub paces itself on a MACROTASK: a promise-only cycle never yields to
// the timer phase, so the poll loop would starve the spec's own sleeps (the
// real wire always yields — this keeps the stub honest about that).
let polls = 0;
const tick = () => new Promise((r) => setTimeout(r, 5));
globalThis.fetch = async (url) => {
  if (String(url).includes("/ilink/bot/getupdates")) {
    polls += 1;
    await tick();
    return { ok: true, status: 200, text: async () => JSON.stringify({ ret: 0, msgs: [] }) };
  }
  await tick();
  return { ok: true, status: 200, text: async () => "{}" };
};

const { startMsgLoop, inboundLoopState } = await import("./msgloop.mjs");

const sleepMs = (ms) => new Promise((r) => setTimeout(r, ms));
const waitFor = async (fn, budgetMs = 4000) => {
  const until = Date.now() + budgetMs;
  while (Date.now() < until) {
    if (fn()) return true;
    await sleepMs(20);
  }
  return false;
};
const mentioned = (re) => lines.some((l) => re.test(l.text));

const stop = startMsgLoop();
// The live loop owns the account and is polling: that is the healthy baseline.
const started = await waitFor(() => polls >= 2 && fs.existsSync(LOCK));
assert.ok(started, "baseline: the loop must own the lock and poll (polls=" + polls + ")");
const baselineState = inboundLoopState();
assert.equal(baselineState.active, true, "LG3 baseline: the ledger reports the loop ACTIVE");

test("LG3 the activity ledger is real (active + monotone counters)", () => {
  const s = inboundLoopState();
  assert.equal(s.active, true, "loop active while polling");
  assert.ok(s.polls >= 2, `poll counter advances with traffic, got ${s.polls}`);
  assert.ok(s.lastPollAt > 0, "last poll timestamp recorded");
  assert.equal(s.msgs, 0, "no messages yet");
  assert.equal(s.starts >= 1, true, "start counted");
});

test("LG1 LG2 a transient lock fault neither kills inbound nor stays silent", async () => {
  const before = polls;
  // The incident's opening move: the lock file vanishes under the running loop
  // (external cleanup / a half-written read / a swept state dir). Pre-batch
  // code: heartbeatMutex catch → silent false → loop gone for the process life.
  // The re-arm backoff is 5s, so the recovery budget must outlast it.
  fs.unlinkSync(LOCK);
  const recovered = await waitFor(() => polls >= before + 3, 12000);
  assert.ok(
    recovered,
    `polling must resume after the fault (before=${before} now=${polls}) — a frozen counter IS the incident`,
  );
  assert.ok(
    mentioned(/mutex: heartbeat failed|mutex: lock no longer ours|mutex: heartbeat with no ownership marker/),
    "the ownership fault is NAMED in the log (pre-batch: silent)",
  );
  assert.ok(mentioned(/loop: exited|re-arm/), "the exit/re-arm decision is logged with its reason");
  const s = inboundLoopState();
  assert.equal(s.active, true, "LG3 the ledger reports ACTIVE again after recovery");
  assert.ok(s.exits >= 1, "the exit is counted");
  assert.ok(String(s.lastExitReason ?? "").length > 0, "the exit carries a reason string");
});

test("LG4 a clean stop is not an exit (no re-arm churn)", async () => {
  const exitsBefore = inboundLoopState().exits;
  stop.abort();
  await sleepMs(300);
  const s = inboundLoopState();
  assert.equal(s.active, false, "a stopped loop reports inactive");
  assert.equal(s.exits, exitsBefore, "SIGTERM-style abort adds no exit/re-arm cycle");
  assert.equal(polls, inboundLoopState().polls, "no polling after the stop");
});

test.after(() => {
  console.log = realLog;
  console.error = realErr;
  const tail = lines.slice(-6).map((l) => `  [${l.level}] ${l.text}`).join("\n");
  realLog(`\nloop-guard: polls=${polls} ledger=${JSON.stringify(inboundLoopState())}\nrecent lines:\n${tail}`);
});

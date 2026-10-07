#!/usr/bin/env node
/**
 * Nebflow Weixin (iLink) side-car — INBOUND message loop (weixin-inbound batch,
 * 2026-10-07; contract = report `*_weixin-inbound__chain-n-6c92ac27.md` §1/§2,
 * shared verbatim with the outbound batch — do not fork).
 *
 * One job: consume the bot account's iLink update stream (getUpdates long-poll,
 * wire mirror of official plugin 2.4.9) and hand each message to the Nebflow
 * ingest seam: POST <ingress> {"senderId","text","messageId"} with the shim
 * token. Sender gate, session binding and injection are engine-side; this loop
 * invents no routing and reads no session state.
 *
 * Kill switches (any one ⇒ original login-only shim behaviour):
 *   1. remove this file                     → shim.mjs import fails → warn, go on
 *   2. WEIXIN_MSGLOOP_OFF=1                 → shim.mjs boot hook skips the import
 *   3. WEIXIN_MSGLOOP_INGRESS_URL unset     → loop refuses to start (idle log)
 *
 * Config (env only — shim.mjs argv parsing untouched):
 *   WEIXIN_MSGLOOP_INGRESS_URL  full ingress URL (required to start)
 *   WEIXIN_SEND_TOKEN_FILE      shim token path (default ~/.nebflow/secrets/
 *                               weixin-ilink-ingress-token; engine mints 0600).
 *                               weixin-autobind batch (2026-10-07): the env
 *                               name joined the WEIXIN_SEND_TOKEN[_FILE] family
 *                               — the outbound send leg's spelling (author
 *                               ruling ②: same bot ⇒ one token, one name). The
 *                               pre-rename WEIXIN_MSGLOOP_TOKEN_FILE spelling
 *                               is still read for one compatibility release,
 *                               then removable.
 *   WEIXIN_MSGLOOP_FIXTURE      absolute NDJSON path of iLink-shaped messages;
 *                               presence = FIXTURE mode (zero iLink traffic),
 *                               lines flow through the same deliver leg
 *   WEIXIN_MSGLOOP_STATE_DIR    lock/cursor state root (default: plugin mirror)
 *   WEIXIN_MSGLOOP_ACCOUNT_ID   bot account override (default: newest indexed)
 *
 * 🔴 Zero-secret discipline (from shim.mjs): no token value ever logged —
 * length/shape only. Mutex plane (§1.2): one consumer per bot account via
 * <accountId>.msgloop.lock (pid+heartbeat liveness, stale preemption,
 * owner-checked release). Cursor plane: the plugin's own <accountId>.sync.json
 * (byte-compatible; advanced only after a 2xx verdict — at-least-once).
 *
 * Shared helpers are EXPORTED for the outbound batch (contract §2④): import
 * from this file, never rewrite.
 */

import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

// ───────────────────────────── config ─────────────────────────────

const INGRESS_URL = (process.env.WEIXIN_MSGLOOP_INGRESS_URL ?? "").trim();

/**
 * Token-file env resolution (weixin-autobind batch, 2026-10-07): the env name
 * joined the WEIXIN_SEND_TOKEN[_FILE] family — the outbound send leg's
 * spelling (author ruling ②; same bot ⇒ one token, one name). Resolution
 * order: WEIXIN_SEND_TOKEN_FILE ＞ the deprecated WEIXIN_MSGLOOP_TOKEN_FILE
 * (kept for one compatibility release — existing plists/deployments keep
 * working; removable after that) ＞ the engine-minted default. Pure (env
 * injected) so the tokenfile spec can pin the order offline.
 * @param {Record<string, string | undefined>} env
 */
export function resolveTokenFile(env = process.env) {
  return (
    env.WEIXIN_SEND_TOKEN_FILE?.trim() ||
    env.WEIXIN_MSGLOOP_TOKEN_FILE?.trim() || // deprecated pre-rename spelling
    path.join(os.homedir(), ".nebflow", "secrets", "weixin-ilink-ingress-token")
  );
}
const TOKEN_FILE = resolveTokenFile();
const FIXTURE = process.env.WEIXIN_MSGLOOP_FIXTURE?.trim() || "";
const ACCOUNT_OVERRIDE = process.env.WEIXIN_MSGLOOP_ACCOUNT_ID?.trim() || "";

const ILINK_BASE = "https://ilinkai.weixin.qq.com";
const PLUGIN_VERSION = "2.4.9";
const ILINK_APP_CLIENT_VERSION = String((2 << 16) | (4 << 8) | 9); // buildClientVersion("2.4.9")
const LONG_POLL_TIMEOUT_MS = 35_000; // monitor.js DEFAULT_LONG_POLL_TIMEOUT_MS
const RETRY_DELAY_MS = 2_000; // monitor.js RETRY_DELAY_MS
const BACKOFF_DELAY_MS = 30_000; // monitor.js BACKOFF_DELAY_MS
const MAX_CONSECUTIVE_FAILURES = 3; // monitor.js MAX_CONSECUTIVE_FAILURES
const STALE_TOKEN_ERRCODE = -14; // session-guard.js STALE_TOKEN_ERRCODE
const STALE_TOKEN_PAUSE_MS = 60 * 60 * 1000; // session-guard.js SESSION_PAUSE_DURATION_MS
const MUTEX_STALE_MS = 120_000; // > 3× live cycle; heartbeat refreshes EVERY cycle
const MUTEX_RETRY_MS = 60_000;
const FIXTURE_POLL_GAP_MS = 1_000;

function log(level, msg) {
  const line = `${new Date().toISOString()} ${level} msgloop: ${msg}`;
  (level === "ERROR" ? console.error : console.log)(line);
}
const info = (m) => log("INFO", m);
const warn = (m) => log("WARN", m);
const error = (m) => log("ERROR", m);
const debug = (m) => { if (process.env.WEIXIN_SIDECAR_DEBUG === "1") log("DEBUG", m); };

/** redaction: type+length / first-8+length — never a value. */
export const shape = (s) => (typeof s === "string" ? `${s.length}B` : "absent");
export const peek = (s) => (typeof s === "string" ? `${s.slice(0, 8)}…(${s.length}B)` : "absent");

// ──────────── shared helpers (contract §2④ — outbound batch imports these) ────────────

/** state-dir.ts mirror: WEIXIN_MSGLOOP_STATE_DIR || OPENCLAW_STATE_DIR || ~/.openclaw */
export function stateDirOf() {
  return (
    process.env.WEIXIN_MSGLOOP_STATE_DIR?.trim() ||
    process.env.OPENCLAW_STATE_DIR?.trim() ||
    process.env.CLAWDBOT_STATE_DIR?.trim() ||
    path.join(os.homedir(), ".openclaw")
  );
}
export const accountsDirOf = () => path.join(stateDirOf(), "openclaw-weixin", "accounts");

/** accounts.ts listIndexedWeixinAccountIds */
export function listIndexedAccountIds() {
  try {
    const parsed = JSON.parse(fs.readFileSync(path.join(stateDirOf(), "openclaw-weixin", "accounts.json"), "utf-8"));
    return Array.isArray(parsed) ? parsed.filter((id) => typeof id === "string" && id.trim()) : [];
  } catch {
    return [];
  }
}

/** accounts.ts loadWeixinAccount */
export function loadAccount(id) {
  try {
    return JSON.parse(fs.readFileSync(path.join(accountsDirOf(), `${id}.json`), "utf-8"));
  } catch {
    return null;
  }
}

/** openclaw normalizeAccountId: "hex@im.bot" → "hex-im-bot" (fs-safe). */
export function normalizeAccountId(raw) {
  return String(raw).replace(/[^A-Za-z0-9_-]/g, "-");
}

/** api.ts classifyFetchError — vocabulary only. */
export function classifyFetchError(err) {
  if (err?.name === "TimeoutError" || err?.name === "AbortError") return "timeout";
  const s = `${err?.message ?? err} ${err?.cause?.code ?? ""}`;
  if (/ENOTFOUND|EAI_AGAIN|getaddrinfo/i.test(s)) return "dns";
  if (/ECONNREFUSED/i.test(s)) return "tcp_refused";
  if (/ETIMEDOUT|ENETUNREACH|EHOSTUNREACH|UND_ERR_CONNECT_TIMEOUT/i.test(s)) return "tcp_timeout";
  if (/SSL|TLS|CERT|UNABLE_TO_VERIFY/i.test(s)) return "tls";
  return "network";
}

/** api.ts buildCommonHeaders (no route tag shim-side). */
export function commonIlinkHeaders() {
  return { "iLink-App-Id": "bot", "iLink-App-ClientVersion": ILINK_APP_CLIENT_VERSION };
}

/** api.ts buildHeaders — POST face. */
export function postIlinkHeaders(token) {
  const uin = Buffer.from(String(crypto.randomBytes(4).readUInt32BE(0)), "utf-8").toString("base64");
  return {
    "Content-Type": "application/json",
    AuthorizationType: "ilink_bot_token",
    "X-WECHAT-UIN": uin,
    ...(token?.trim() ? { Authorization: `Bearer ${token.trim()}` } : {}),
    ...commonIlinkHeaders(),
  };
}

/** The shim token (§1.1): re-read from disk every call — rotation is instant. */
export function readIngressToken(tokenFile = TOKEN_FILE) {
  try {
    const t = fs.readFileSync(tokenFile, "utf-8").trim();
    return t || null;
  } catch {
    return null;
  }
}

// ──────────── iLink message → ingress body (mirrors messaging/inbound.js) ────────────

const MEDIA_LABEL = { 2: "[图片]", 3: "[语音]", 4: "[文件]", 5: "[视频]" }; // types.js MessageItemType

/** inbound.js bodyFromItemList: TEXT body; voice transcript; else media label. */
export function messageBody(msg) {
  for (const item of msg?.item_list ?? []) {
    if (item.type === 1 && item.text_item?.text != null) return String(item.text_item.text);
    if (item.type === 3 && item.voice_item?.text) return String(item.voice_item.text);
    if (MEDIA_LABEL[item.type]) return MEDIA_LABEL[item.type];
  }
  return "";
}

/** inbound.js getWeixinMessageId: top-level message_id, else first item msg_id. */
export function messageIdOf(msg) {
  if (typeof msg?.message_id === "string" && msg.message_id.trim()) return msg.message_id.trim();
  for (const item of msg?.item_list ?? []) {
    if (typeof item?.msg_id === "string" && item.msg_id.trim()) return item.msg_id.trim();
  }
  return null;
}

/** The ingress body (contract §2 wire shape). */
export function toInboundBody(msg) {
  const body = { senderId: String(msg?.from_user_id ?? "") };
  const text = messageBody(msg);
  if (text) body.text = text;
  const id = messageIdOf(msg);
  if (id) body.messageId = id;
  return body;
}

// ───────────────────────────── mutex (§1.2) ─────────────────────────────

let lockTakenAt = 0; // owner marker for heartbeat/release ownership checks

const lockPathOf = (accountId) => path.join(accountsDirOf(), `${accountId}.msgloop.lock`);

function pidAlive(pid) {
  try { process.kill(pid, 0); return true; } catch { return false; }
}

function writeLock(accountId) {
  const p = lockPathOf(accountId);
  fs.mkdirSync(path.dirname(p), { recursive: true }); // a fresh state dir has no accounts/ yet
  const rec = { holder: "nebflow-shim-msgloop", pid: process.pid, host: os.hostname(), takenAt: lockTakenAt, heartbeat: Date.now(), mode: FIXTURE ? "fixture" : "live" };
  fs.writeFileSync(p, JSON.stringify(rec), { flag: "wx" });
}

/** Try once: O_EXCL claim; on EEXIST read the holder — steal only if stale. */
function acquireMutexOnce(accountId) {
  lockTakenAt = Date.now();
  try {
    writeLock(accountId);
    return { held: true };
  } catch (e) {
    if (e?.code !== "EEXIST") throw e;
    lockTakenAt = 0;
    let cur = null;
    try { cur = JSON.parse(fs.readFileSync(lockPathOf(accountId), "utf-8")); } catch { cur = null; }
    if (cur && pidAlive(cur.pid) && Date.now() - (cur.heartbeat ?? 0) < MUTEX_STALE_MS) {
      return { held: false, holder: cur };
    }
    warn(cur ? `mutex: preempting stale holder pid=${cur.pid} (heartbeat ${Math.round((Date.now() - (cur.heartbeat ?? 0)) / 1000)}s old)` : "mutex: clearing unreadable lock file");
    try { fs.unlinkSync(lockPathOf(accountId)); } catch { /* raced away — next round re-evaluates */ }
    lockTakenAt = Date.now();
    try {
      writeLock(accountId);
      return { held: true };
    } catch (e2) {
      if (e2?.code !== "EEXIST") throw e2;
      lockTakenAt = 0;
      return { held: false }; // lost the steal race — re-evaluate next round
    }
  }
}

function acquireMutex(accountId, stop) {
  if (stop?.aborted) return false;
  const r = acquireMutexOnce(accountId);
  if (r.held) info(`mutex: held ${path.basename(lockPathOf(accountId))} (pid=${process.pid}, mode=${FIXTURE ? "fixture" : "live"})`);
  else if (r.holder) warn(`mutex: refused — account ${accountId} held by pid=${r.holder.pid} (taken ${new Date(r.holder.takenAt).toISOString()}), retrying every ${MUTEX_RETRY_MS / 1000}s`);
  return r.held;
}

/** Refresh the heartbeat — EVERY cycle (quiet accounts stay protected too). */
function heartbeatMutex(accountId) {
  if (!lockTakenAt) return false;
  try {
    const p = lockPathOf(accountId);
    const cur = JSON.parse(fs.readFileSync(p, "utf-8"));
    if (cur.pid !== process.pid || cur.takenAt !== lockTakenAt) {
      warn("mutex: lock no longer ours — stopping the loop (another consumer took the account)");
      lockTakenAt = 0;
      return false;
    }
    cur.heartbeat = Date.now();
    fs.writeFileSync(p, JSON.stringify(cur));
    return true;
  } catch {
    return false; // unreadable/gone: treated as lost ownership
  }
}

/** Owner-checked release (never unlink a lock we do not hold). */
export function releaseMutex(accountId) {
  try {
    const cur = JSON.parse(fs.readFileSync(lockPathOf(accountId), "utf-8"));
    if (cur.pid === process.pid && cur.takenAt === lockTakenAt) fs.unlinkSync(lockPathOf(accountId));
  } catch { /* already gone */ }
  lockTakenAt = 0;
}

// ─────────────── cursor plane (plugin-compatible: <accountId>.sync.json) ───────────────

const cursorPathOf = (accountId) => path.join(accountsDirOf(), `${accountId}.sync.json`);

function loadCursor(accountId) {
  try {
    const v = JSON.parse(fs.readFileSync(cursorPathOf(accountId), "utf-8")).get_updates_buf;
    if (typeof v === "string") return v;
  } catch { /* fresh start */ }
  return "";
}

function saveCursor(accountId, buf) {
  fs.mkdirSync(accountsDirOf(), { recursive: true });
  fs.writeFileSync(cursorPathOf(accountId), JSON.stringify({ get_updates_buf: buf }));
}

// ───────────────────────── deliver leg (§2 wire contract) ─────────────────────────

/**
 * One message → the Nebflow ingest seam. Returns {delivered, status, verdict}.
 * 403 re-reads the token file once (rotation pickup) before giving up; the
 * cursor NEVER advances on auth/network failure (at-least-once, §T6).
 */
export async function deliverToIngress(msg) {
  const body = JSON.stringify(toInboundBody(msg));
  for (let attempt = 1; attempt <= 2; attempt++) {
    const token = readIngressToken();
    if (!token) {
      warn(`ingress: no shim token at ${TOKEN_FILE} — engine seam-up mints it; holding`);
      return { delivered: false, status: 0, verdict: null };
    }
    let res;
    try {
      res = await fetch(INGRESS_URL, {
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
        body,
        signal: AbortSignal.timeout(10_000),
      });
    } catch (err) {
      warn(`ingress: ${classifyFetchError(err)} — ${err.message}`);
      return { delivered: false, status: 0, verdict: null };
    }
    const raw = await res.text();
    let verdict = null;
    try { verdict = JSON.parse(raw); } catch { /* non-JSON body: verdict stays unseen */ }
    if (res.ok) {
      debug(`ingress 200 verdict=${raw.slice(0, 120)}`);
      return { delivered: true, status: res.status, verdict };
    }
    if (res.status === 403 && attempt === 1) {
      warn("ingress: 403 — re-reading the token file once (rotation?)");
      continue;
    }
    warn(`ingress: HTTP ${res.status} — not delivered, cursor held`);
    return { delivered: false, status: res.status, verdict };
  }
  return { delivered: false, status: 403, verdict: null };
}

// ───────────────────── live leg (getUpdates long-poll mirror) ─────────────────────

/** api.ts getUpdates (2.4.9 mirror; client timeout → empty ret=0 like the plugin). */
async function getUpdates(baseUrl, token, cursor, timeoutMs) {
  try {
    const res = await fetch(`${baseUrl.replace(/\/+$/, "")}/ilink/bot/getupdates`, {
      method: "POST",
      headers: postIlinkHeaders(token),
      body: JSON.stringify({ get_updates_buf: cursor, base_info: { channel_version: PLUGIN_VERSION, bot_agent: "OpenClaw" } }),
      signal: AbortSignal.timeout(timeoutMs),
    });
    const raw = await res.text();
    if (!res.ok) throw new Error(`getUpdates ${res.status}: ${raw.slice(0, 200)}`);
    return JSON.parse(raw);
  } catch (err) {
    if (err?.name === "TimeoutError" || err?.name === "AbortError") return { ret: 0, msgs: [] };
    throw err;
  }
}

const sleep = (ms, stop) =>
  new Promise((resolve) => {
    const t = setTimeout(resolve, ms);
    stop?.addEventListener("abort", () => { clearTimeout(t); resolve(); }, { once: true });
  });

/** monitor.js mirror: long-poll → deliver each → cursor after 2xx. -14 = 1h pause. */
async function liveLoop(accountId, stop) {
  let cursor = loadCursor(accountId);
  info(`live: ${cursor ? `resuming cursor (${cursor.length}B buf)` : "fresh cursor"} — ${ILINK_BASE}/ilink/bot/getupdates`);
  let failures = 0;
  let pausedUntil = 0;
  let pollTimeoutMs = LONG_POLL_TIMEOUT_MS;
  while (!stop?.aborted) {
    if (Date.now() < pausedUntil) { await sleep(Math.min(pausedUntil - Date.now(), 60_000), stop); continue; }
    if (!heartbeatMutex(accountId)) return;
    const acct = loadAccount(accountId);
    const token = acct?.token?.trim();
    if (!token) {
      warn(`live: no bot token for ${accountId} in ${accountsDirOf()} — waiting ${MUTEX_RETRY_MS / 1000}s`);
      await sleep(MUTEX_RETRY_MS, stop);
      continue;
    }
    const baseUrl = typeof acct?.baseUrl === "string" && acct.baseUrl.trim() ? acct.baseUrl.trim() : ILINK_BASE;
    let resp;
    try {
      resp = await getUpdates(baseUrl, token, cursor, pollTimeoutMs);
    } catch (err) {
      failures += 1;
      const backoff = failures >= MAX_CONSECUTIVE_FAILURES;
      if (backoff) failures = 0;
      error(`live: getUpdates ${classifyFetchError(err)}: ${err.message} (backoff ${backoff ? BACKOFF_DELAY_MS / 1000 : RETRY_DELAY_MS / 1000}s)`);
      await sleep(backoff ? BACKOFF_DELAY_MS : RETRY_DELAY_MS, stop);
      continue;
    }
    if (typeof resp.longpolling_timeout_ms === "number" && resp.longpolling_timeout_ms > 0) {
      pollTimeoutMs = resp.longpolling_timeout_ms; // monitor.js server-driven pacing
    }
    if ((resp.errcode ?? resp.ret ?? 0) !== 0) {
      if (resp.errcode === STALE_TOKEN_ERRCODE || resp.ret === STALE_TOKEN_ERRCODE) {
        pausedUntil = Date.now() + STALE_TOKEN_PAUSE_MS;
        error("live: bot token stale (-14) — pausing this account for 1h (session-guard mirror)");
        continue;
      }
      failures += 1;
      const backoff = failures >= MAX_CONSECUTIVE_FAILURES;
      if (backoff) failures = 0;
      error(`live: getUpdates ret=${resp.ret} errcode=${resp.errcode} errmsg=${resp.errmsg ?? ""} (backoff ${backoff ? BACKOFF_DELAY_MS / 1000 : RETRY_DELAY_MS / 1000}s)`);
      await sleep(backoff ? BACKOFF_DELAY_MS : RETRY_DELAY_MS, stop);
      continue;
    }
    failures = 0;
    let allDelivered = true;
    for (const m of resp.msgs ?? []) {
      info(`live: inbound from=${String(m?.from_user_id ?? "") || "unresolved"} body=${shape(messageBody(m))}`);
      const r = await deliverToIngress(m);
      if (!r.delivered) { allDelivered = false; break; }
      info(`live: verdict ${JSON.stringify(r.verdict)}`);
    }
    const buf = typeof resp.get_updates_buf === "string" ? resp.get_updates_buf : "";
    if (allDelivered && buf && buf !== cursor) {
      saveCursor(accountId, buf);
      cursor = buf;
      debug(`cursor → ${buf.length}B`);
    }
  }
}

// ─────────────────── fixture leg (zero iLink traffic; test seam) ───────────────────

/**
 * Fixture mode (§T7): tail an NDJSON file of iLink-shaped messages; every line
 * flows through the SAME deliver leg as live traffic. The offset file advances
 * only up to the last line that got a 2xx verdict — a failed line is redelivered
 * on a later tick (same at-least-once semantics as the live cursor).
 */
async function fixtureLoop(accountId, stop) {
  const offsetFile = `${FIXTURE}.offset`;
  let offset = 0; // absolute file position: everything before it is DELIVERED
  try { offset = Number(fs.readFileSync(offsetFile, "utf-8").trim()) || 0; } catch { /* fresh */ }
  info(`fixture: tailing ${FIXTURE} from offset ${offset}`);
  let pending = ""; // bytes read from `offset` not yet delivered; invariant: pending === file[offset..size)
  for (;;) {
    if (!heartbeatMutex(accountId)) return;
    let size = 0;
    try { size = fs.statSync(FIXTURE).size; } catch { await sleep(FIXTURE_POLL_GAP_MS, stop); continue; }
    if (!pending && size > offset) { // read only consumed bytes — a held line must not be re-read
      const fd = fs.openSync(FIXTURE, "r");
      try {
        const buf = Buffer.alloc(size - offset);
        fs.readSync(fd, buf, 0, buf.length, offset);
        pending = buf.toString("utf-8");
      } finally { fs.closeSync(fd); }
    }
    const nl = pending.indexOf("\n");
    if (nl >= 0) {
      const rawLine = pending.slice(0, nl + 1); // the line INCLUDING its newline
      const line = rawLine.trim();
      let consume = true; // skippable (blank/malformed) lines advance the offset
      if (line) {
        let msg = null;
        try { msg = JSON.parse(line); } catch { warn(`fixture: skipping non-JSON line: ${line.slice(0, 80)}`); }
        if (msg) {
          info(`fixture: inbound from=${String(msg?.from_user_id ?? "")} body=${shape(messageBody(msg))}`);
          const r = await deliverToIngress(msg);
          if (r.delivered) info(`fixture: verdict ${JSON.stringify(r.verdict)}`);
          else {
            warn(`fixture: NOT delivered (HTTP ${r.status}) — line held, will redeliver`);
            consume = false; // at-least-once: this line retries on a later tick
          }
        }
      }
      if (consume) {
        pending = pending.slice(rawLine.length);
        offset += Buffer.byteLength(rawLine, "utf-8");
        fs.writeFileSync(offsetFile, String(offset));
      }
    }
    await sleep(FIXTURE_POLL_GAP_MS, stop);
  }
}

// ───────────────────────────── entry ─────────────────────────────

let loopAccount = "";

/** shim.mjs boot hook. No-ops (with a visible reason) unless fully configured. */
export function startMsgLoop() {
  if (!INGRESS_URL) {
    info("idle — WEIXIN_MSGLOOP_INGRESS_URL not set (kill switch 3); login-only shim");
    return;
  }
  if (FIXTURE && !path.isAbsolute(FIXTURE)) {
    warn(`fixture path must be absolute: ${FIXTURE}`);
    return;
  }
  const accountId = normalizeAccountId(ACCOUNT_OVERRIDE || listIndexedAccountIds().at(-1) || (FIXTURE ? "fixture-im-bot" : ""));
  if (!accountId) {
    error("no bot account resolvable (index empty; set WEIXIN_MSGLOOP_ACCOUNT_ID)");
    return;
  }
  loopAccount = accountId;
  const stop = new AbortController();
  for (const sig of ["SIGTERM", "SIGINT"]) {
    process.on(sig, () => {
      if (loopAccount) releaseMutex(loopAccount);
      stop.abort();
    });
  }
  info(`starting (${FIXTURE ? "fixture" : "live"} mode) → ${INGRESS_URL}`);
  info(`token file ${TOKEN_FILE} (${shape(readIngressToken())}), account ${accountId}, state ${stateDirOf()}`);
  (async () => {
    for (;;) {
      if (stop.signal.aborted) return;
      if (acquireMutex(accountId, stop.signal)) {
        try {
          await (FIXTURE ? fixtureLoop(accountId, stop.signal) : liveLoop(accountId, stop.signal));
          return; // loop exited: ownership lost or stop signalled
        } finally {
          releaseMutex(accountId);
        }
      }
      await sleep(MUTEX_RETRY_MS, stop.signal);
    }
  })().catch((e) => error(`loop crashed: ${e.message} (mutex released; shim continues login-only)`));
}

// Direct-run entry (`node msgloop.mjs`) — for ops and the fixture rig. The
// production path (shim.mjs's dynamic import) never matches this guard, so the
// loop still starts exactly once per shim process.
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  startMsgLoop();
}

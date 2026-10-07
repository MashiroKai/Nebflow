#!/usr/bin/env node
/**
 * Nebflow Weixin (iLink) side-car — localhost control endpoint on 127.0.0.1:8787.
 *
 * Author ruling 2026-10-05 (plan b): the official plugin release
 * (@tencent-weixin/openclaw-weixin 2.4.9) ships NO HTTP side-car — its login
 * path is a CLI terminal QR — so this shim implements the control contract the
 * Nebflow README section "Weixin (iLink) channel" pins, and the contract
 * src/main/scala/nebflow/social/WeixinIlinkScanBind.scala consumes:
 *
 *   GET /login/begin    → starts (or restarts) the login session; answers
 *                         {"state":"starting"} or, when the first QR is already
 *                         in, {"state":"qr_ready","qrUrl":…,"expireIn":…}
 *   GET /login/status   → {"state":"starting"|"qr_ready"|"polling"|"done"|"failed",
 *                          "qrUrl"?,"userCode"?,"expireIn"?,"error"?,"credentials"?}
 *                         polled until terminal; on `done` the body carries
 *                         credentials EXACTLY ONCE (the token never appears in
 *                         any other response). Both legs are GET only.
 *
 * QR source — programmatic reuse of the official login flow (2.4.9,
 * src/auth/login-qr.ts + src/api/api.ts), mirrored at the wire level:
 *   POST https://ilinkai.weixin.qq.com/ilink/bot/get_bot_qrcode?bot_type=3
 *        body {"local_token_list":[…]} → {"qrcode","qrcode_img_content"}
 *   GET  https://ilinkai.weixin.qq.com/ilink/bot/get_qrcode_status?qrcode=…
 *        (35 s long-poll) → {"status":"wait"|"scaned"|"confirmed"|"expired"|…}
 * `qrcode_img_content` is exactly the string the plugin feeds qrcode-terminal
 * (displayQRCode) — it goes out as `qrUrl`; the Nebflow card renders that
 * string as the QR (web/vendor/qrcode.js), so it is directly scannable.
 *
 * Why mirrored calls instead of importing the plugin's modules: login-qr.js →
 * accounts.js imports the openclaw host SDK (`openclaw/plugin-sdk/*`), which
 * exists only inside a host install — outside it the import fails at load
 * time. The two wire calls below reproduce the 2.4.9 requests byte-for-byte
 * (endpoint, query, headers, body, long-poll timeout, refresh-on-expired ×3,
 * IDC redirect, X-WECHAT-UIN / iLink-App-ClientVersion derivation).
 *
 * Zero npm dependencies. Node ≥ 22.13.0 (same floor as the plugin).
 * Listens on loopback ONLY (the contract seam has no auth; keep it local).
 *
 * 🔴 Zero-secret discipline: bot_token is logged NOWHERE (length only), served
 * exactly once on the first `done` status, and mirrored to the host account
 * file exactly as the official plugin does after a confirmed login
 * (saveWeixinAccount + registerWeixinAccountId, 0600, backup before overwrite;
 * disable with --no-account-sync).
 */

import http from "node:http";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import process from "node:process";
// weixin-outbound batch (2026-10-07): the OUTBOUND send leg lives in its own
// file (unified interface contract ④: send section separate from the loop
// section). shim.mjs only wires the route and hands over the shared helpers.
import { makeSendHandler } from "./send.mjs";

// ───────────────────────────── config ─────────────────────────────

const DEFAULT_PORT = 8787; // WeixinIlinkScanBind.SidecarDefaultBase
const FIXED_BASE_URL = "https://ilinkai.weixin.qq.com"; // login-qr.ts FIXED_BASE_URL
const BOT_TYPE = "3"; // login-qr.ts DEFAULT_ILINK_BOT_TYPE
const PLUGIN_VERSION = "2.4.9"; // README-pinned plugin version
const ILINK_APP_ID = "bot"; // plugin package.json `ilink_appid`
/** buildClientVersion("2.4.9") — api.ts: (major<<16)|(minor<<8)|patch. */
const ILINK_APP_CLIENT_VERSION = String((2 << 16) | (4 << 8) | 9); // 132105
const QR_LONG_POLL_TIMEOUT_MS = 35_000; // login-qr.ts QR_LONG_POLL_TIMEOUT_MS
const QR_FETCH_TIMEOUT_MS = 15_000; // plugin sends get_bot_qrcode unbounded; we bound it
const QR_TTL_MS = 5 * 60_000; // login-qr.ts ACTIVE_LOGIN_TTL_MS (server enforces too)
const MAX_QR_REFRESH = 3; // login-qr.ts MAX_QR_REFRESH_COUNT
const POLL_GAP_MS = 1_000; // login-qr.ts inter-poll sleep
const SESSION_BUDGET_MS = 15 * 60_000; // hard cap; Nebflow's own budget is 300 s
const BEGIN_QR_WAIT_MS = 4_000; // < driveLogin's 5 s HTTP timeout on begin

function parseArgs(argv) {
  const opts = {
    port: Number(process.env.WEIXIN_SIDECAR_PORT ?? DEFAULT_PORT),
    host: process.env.WEIXIN_SIDECAR_HOST ?? "127.0.0.1",
    accountSync: true,
    help: false,
  };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--port") opts.port = Number(argv[++i]);
    else if (a === "--host") opts.host = argv[++i];
    else if (a === "--no-account-sync") opts.accountSync = false;
    else if (a === "--help" || a === "-h") opts.help = true;
    else {
      console.error(`unknown argument: ${a} (try --help)`);
      process.exit(2);
    }
  }
  return opts;
}

const OPTS = parseArgs(process.argv.slice(2));
if (OPTS.help) {
  console.log(`nebflow weixin side-car — 127.0.0.1 control endpoint (README "Weixin (iLink) channel")

Usage: node shim.mjs [--port 8787] [--host 127.0.0.1] [--no-account-sync]

  --port <n>          listen port (default ${DEFAULT_PORT}; card default sidecar_url)
  --host <addr>       bind address (default 127.0.0.1 — loopback only by design)
  --no-account-sync   do NOT mirror the confirmed login into the host account
                      file (~/.openclaw/openclaw-weixin/accounts/); the official
                      plugin does this after its own confirmed logins. Default ON.
Env: WEIXIN_SIDECAR_PORT, WEIXIN_SIDECAR_HOST, OPENCLAW_STATE_DIR (host state
root, plugin state-dir.ts mirror), WEIXIN_SIDECAR_DEBUG=1 (redacted wire dumps).`);
  process.exit(0);
}

// ───────────────────────────── logging ─────────────────────────────
// ts + level; every secret-bearing value renders as type+length only.

function log(level, msg) {
  const line = `${new Date().toISOString()} ${level} ${msg}`;
  (level === "ERROR" ? console.error : console.log)(line);
}
const info = (m) => log("INFO", m);
const warn = (m) => log("WARN", m);
const error = (m) => log("ERROR", m);
const debug = (m) => { if (process.env.WEIXIN_SIDECAR_DEBUG === "1") log("DEBUG", m); };

/** redact(s) → first 8 chars + length, for non-secret wire shape debugging. */
const shape = (s) => (typeof s === "string" ? `${s.length}B` : "absent");
const peek = (s) => (typeof s === "string" ? `${s.slice(0, 8)}…(${s.length}B)` : "absent");

// ─────────────────────── host state (plugin mirror) ───────────────────────
// state-dir.ts: OPENCLAW_STATE_DIR || CLAWDBOT_STATE_DIR || ~/.openclaw

function stateDir() {
  return (
    process.env.OPENCLAW_STATE_DIR?.trim() ||
    process.env.CLAWDBOT_STATE_DIR?.trim() ||
    path.join(os.homedir(), ".openclaw")
  );
}
const weixinStateDir = () => path.join(stateDir(), "openclaw-weixin");
const accountIndexPath = () => path.join(weixinStateDir(), "accounts.json");
const accountsDir = () => path.join(weixinStateDir(), "accounts");

/** accounts.ts listIndexedWeixinAccountIds — ids from the persistent index. */
function listIndexedAccountIds() {
  try {
    const parsed = JSON.parse(fs.readFileSync(accountIndexPath(), "utf-8"));
    return Array.isArray(parsed) ? parsed.filter((id) => typeof id === "string" && id.trim()) : [];
  } catch {
    return [];
  }
}

/** accounts.ts loadWeixinAccount (normalized path only — legacy fallbacks omitted). */
function loadAccount(id) {
  try {
    return JSON.parse(fs.readFileSync(path.join(accountsDir(), `${id}.json`), "utf-8"));
  } catch {
    return null;
  }
}

/** accounts.ts getLocalBotTokenList — newest first, max 10. */
function localBotTokenList() {
  const ids = listIndexedAccountIds();
  const tokens = [];
  for (let i = ids.length - 1; i >= 0 && tokens.length < 10; i--) {
    const token = loadAccount(ids[i])?.token?.trim();
    if (token) tokens.push(token);
  }
  return tokens;
}

/** openclaw normalizeAccountId shape: "hex@im.bot" → "hex-im-bot" (fs-safe). */
function normalizeAccountId(raw) {
  return String(raw).replace(/[^A-Za-z0-9_-]/g, "-");
}

/** Backup-before-write helper (config/install discipline). */
function backupIfExists(file) {
  try {
    if (fs.existsSync(file)) {
      const bak = `${file}.bak-${new Date().toISOString().replace(/[-:T]/g, "").slice(0, 15)}`;
      fs.copyFileSync(file, bak);
      info(`account-sync: backed up existing ${path.basename(file)} → ${path.basename(bak)}`);
      return true;
    }
  } catch (e) {
    warn(`account-sync: backup of ${file} failed: ${e.message}`);
  }
  return false;
}

/**
 * Mirror of the plugin's post-confirmed persistence (channel.ts:447-452):
 * saveWeixinAccount(normalizedId, {token, baseUrl, userId}) +
 * registerWeixinAccountId(normalizedId). Skipped entirely with --no-account-sync.
 * Deviation from the plugin: clearStaleAccountsForUserId only LOGS here (the
 * plugin deletes same-userId stale accounts; deletion stays a human decision).
 */
function persistConfirmedAccount({ botToken, botId, baseUrl, userId }) {
  if (!OPTS.accountSync) {
    info("account-sync: disabled (--no-account-sync), host account file untouched");
    return;
  }
  const id = normalizeAccountId(botId);
  try {
    fs.mkdirSync(accountsDir(), { recursive: true });
    const file = path.join(accountsDir(), `${id}.json`);
    const existing = loadAccount(id) ?? {};
    backupIfExists(file);
    const data = {
      token: botToken,
      savedAt: new Date().toISOString(),
      ...(baseUrl?.trim() ? { baseUrl: baseUrl.trim() } : {}),
      ...(userId?.trim() ? { userId: userId.trim() } : { ...(existing.userId ? { userId: existing.userId } : {}) }),
    };
    fs.writeFileSync(file, JSON.stringify(data, null, 2), "utf-8");
    try { fs.chmodSync(file, 0o600); } catch { /* best-effort, mirror plugin */ }
    // registerWeixinAccountId
    const ids = listIndexedAccountIds();
    if (!ids.includes(id)) {
      fs.mkdirSync(weixinStateDir(), { recursive: true });
      backupIfExists(accountIndexPath());
      fs.writeFileSync(accountIndexPath(), JSON.stringify([...ids, id], null, 2), "utf-8");
    }
    // stale same-userId accounts (plugin deletes them; we only report)
    if (userId?.trim()) {
      for (const other of listIndexedAccountIds()) {
        if (other === id) continue;
        if (loadAccount(other)?.userId?.trim() === userId.trim()) {
          warn(`account-sync: stale account ${other} shares userId (plugin would remove it; left untouched)`);
        }
      }
    }
    info(`account-sync: host account file written for ${id} (token ${shape(botToken)}, 0600)`);
  } catch (e) {
    // Nebflow-side persistence is independent; a host-sync failure must not
    // poison the done status (Nebflow still gets its credentials once).
    error(`account-sync: failed to write host account file: ${e.message}`);
  }
}

// ─────────────────────── ilink wire client (2.4.9 mirror) ───────────────────────

/** api.ts randomWechatUin — random uint32 → decimal string → base64. */
function randomWechatUin() {
  const uint32 = crypto.randomBytes(4).readUInt32BE(0);
  return Buffer.from(String(uint32), "utf-8").toString("base64");
}

/** api.ts buildCommonHeaders — the GET face (iLink-App-*, optional SKRouteTag). */
function commonHeaders() {
  return {
    "iLink-App-Id": ILINK_APP_ID,
    "iLink-App-ClientVersion": ILINK_APP_CLIENT_VERSION,
  };
}

/** api.ts buildHeaders — the POST face (adds Content-Type/UIN/AuthorizationType). */
function postHeaders() {
  return {
    "Content-Type": "application/json",
    AuthorizationType: "ilink_bot_token",
    "X-WECHAT-UIN": randomWechatUin(),
    ...commonHeaders(),
  };
}

/** Classify a fetch error the way api.ts classifyFetchError does (vocabulary only). */
function classifyFetchError(err) {
  const name = err?.name ?? "";
  const causeCode = err?.cause?.code ?? "";
  const s = `${err?.message ?? err} ${causeCode}`;
  if (name === "TimeoutError" || name === "AbortError") return "timeout";
  if (/ENOTFOUND|EAI_AGAIN|getaddrinfo/i.test(s)) return "dns";
  if (/ECONNREFUSED/i.test(s)) return "tcp_refused";
  if (/ETIMEDOUT|ENETUNREACH|EHOSTUNREACH|UND_ERR_CONNECT_TIMEOUT/i.test(s)) return "tcp_timeout";
  if (/SSL|TLS|CERT|UNABLE_TO_VERIFY/i.test(s)) return "tls";
  return "network";
}

/**
 * login-qr.ts fetchQRCode — POST get_bot_qrcode, returns {qrcode, qrcode_img_content}.
 * Plugin sends this with no client timeout; we bound it (QR_FETCH_TIMEOUT_MS).
 */
async function fetchQrCode(signal) {
  const url = `${FIXED_BASE_URL}/ilink/bot/get_bot_qrcode?bot_type=${encodeURIComponent(BOT_TYPE)}`;
  const body = JSON.stringify({ local_token_list: localBotTokenList() });
  debug(`get_bot_qrcode POST local_token_list=${localBotTokenList().length}`);
  const res = await fetch(url, {
    method: "POST",
    headers: postHeaders(),
    body,
    signal: AbortSignal.any([AbortSignal.timeout(QR_FETCH_TIMEOUT_MS), ...(signal ? [signal] : [])]),
  });
  const raw = await res.text();
  debug(`get_bot_qrcode status=${res.status} rawLen=${raw.length}`);
  if (!res.ok) throw new Error(`get_bot_qrcode ${res.status}: ${raw.slice(0, 200)}`);
  const parsed = JSON.parse(raw);
  if (typeof parsed.qrcode !== "string" || typeof parsed.qrcode_img_content !== "string") {
    throw new Error(`get_bot_qrcode unexpected shape: keys=${Object.keys(parsed).join(",")}`);
  }
  return parsed;
}

/**
 * login-qr.ts pollQRStatus — long-poll get_qrcode_status (35 s client timeout;
 * timeout/network error → {status:"wait"} exactly like the plugin).
 */
async function pollQrStatus(baseUrl, qrcode, signal) {
  const url = `${baseUrl}/ilink/bot/get_qrcode_status?qrcode=${encodeURIComponent(qrcode)}`;
  try {
    const res = await fetch(url, {
      method: "GET",
      headers: commonHeaders(),
      signal: AbortSignal.any([AbortSignal.timeout(QR_LONG_POLL_TIMEOUT_MS), ...(signal ? [signal] : [])]),
    });
    const raw = await res.text();
    if (!res.ok) {
      debug(`get_qrcode_status ${res.status} len=${raw.length}`);
      return { status: "wait" };
    }
    return JSON.parse(raw);
  } catch (err) {
    if (signal?.aborted) throw err; // superseded — unwind, do not fake a wait
    debug(`get_qrcode_status ${classifyFetchError(err)} → wait`);
    return { status: "wait" };
  }
}

// ───────────────────────────── session driver ─────────────────────────────
// One active login at a time (the contract is stateless between Nebflow and
// the side-car: each begin restarts the session, mirroring the plugin's
// activeLogins single entry). Terminal `done` hands the credential triple out
// exactly once; later polls keep state=done with no credentials.

let session = null; // { gen, phase, error?, qrUrl, qrcode, qrAt, pollingBase, refreshes, creds?, doneServed, abort }

const sleep = (ms, signal) =>
  new Promise((resolve, reject) => {
    const t = setTimeout(resolve, ms);
    signal?.addEventListener("abort", () => { clearTimeout(t); reject(new Error("aborted")); }, { once: true });
  });

async function runDriver(gen, signal) {
  const s = session;
  const alive = () => session === s && !signal.aborted;
  try {
    // ① mint the QR (login-qr.ts startWeixinLoginWithQr)
    const qr = await fetchQrCode(signal);
    if (!alive()) return;
    s.qrcode = qr.qrcode;
    s.qrUrl = qr.qrcode_img_content;
    s.qrAt = Date.now();
    s.phase = "qr_ready";
    s.pollingBase = FIXED_BASE_URL;
    info(`session#${gen}: qr ready (qrUrl ${peek(s.qrUrl)}, qrcode ${shape(s.qrcode)})`);
  } catch (err) {
    if (signal.aborted || !alive()) return;
    s.phase = "failed";
    s.error = `qr_fetch_failed: ${classifyFetchError(err)}`;
    error(`session#${gen}: qr fetch failed (${s.error}: ${err.message})`);
    return;
  }

  // ② poll loop (login-qr.ts waitForWeixinLogin, sans the stdin verify-code leg)
  while (alive() && Date.now() - s.startedAt < SESSION_BUDGET_MS) {
    let st;
    try {
      st = await pollQrStatus(s.pollingBase, s.qrcode, signal);
    } catch {
      return; // superseded by a newer begin
    }
    if (!alive()) return;
    const status = st?.status;
    debug(`session#${gen}: status=${status} keys=${Object.keys(st ?? {}).join(",")}`);

    if (status === "wait") {
      s.phase = "qr_ready";
    } else if (status === "scaned") {
      if (s.phase !== "polling") info(`session#${gen}: scanned on phone, verifying`);
      s.phase = "polling";
    } else if (status === "scaned_but_redirect") {
      // IDC redirect: keep polling on the redirected host (login-qr.ts)
      if (typeof st.redirect_host === "string" && st.redirect_host) {
        s.pollingBase = `https://${st.redirect_host}`;
        info(`session#${gen}: idc redirect → polling host ${st.redirect_host}`);
      }
      s.phase = "polling";
    } else if (status === "need_verifycode") {
      // The official flow reads the number from CLI stdin; a card has no input
      // face. Surface a clean failure (phone shows the number — a retry with a
      // fresh QR usually clears it). Documented deviation, see report.
      s.phase = "failed";
      s.error = "need_verifycode: phone shows a verify number; restart the scan";
      warn(`session#${gen}: need_verifycode → failed (no input face on the card leg)`);
      return;
    } else if (status === "expired" || status === "verify_code_blocked") {
      s.refreshes += 1;
      if (s.refreshes > MAX_QR_REFRESH) {
        s.phase = "failed";
        s.error = status === "expired" ? "qr_expired: refreshed 3 times" : "verify_code_blocked";
        warn(`session#${gen}: ${status} ×${s.refreshes} → giving up`);
        return;
      }
      try {
        const qr = await fetchQrCode(signal); // login-qr.ts refreshQRCode
        if (!alive()) return;
        s.qrcode = qr.qrcode;
        s.qrUrl = qr.qrcode_img_content;
        s.qrAt = Date.now();
        s.phase = "qr_ready";
        info(`session#${gen}: qr refreshed (${s.refreshes}/${MAX_QR_REFRESH}, qrUrl ${peek(s.qrUrl)})`);
      } catch (err) {
        if (signal.aborted || !alive()) return;
        s.phase = "failed";
        s.error = `qr_refresh_failed: ${classifyFetchError(err)}`;
        error(`session#${gen}: qr refresh failed: ${err.message}`);
        return;
      }
    } else if (status === "binded_redirect") {
      // Plugin treats this as success-with-existing-credentials; no new token
      // is issued. The card leg cannot surface "already connected" without a
      // token, so fail with an explicit, honest reason (see report, 候示 item).
      s.phase = "failed";
      s.error = "bot_already_bound: this bot is already bound to this host; existing credentials remain valid";
      warn(`session#${gen}: binded_redirect → failed (no new credentials issued by server)`);
      return;
    } else if (status === "confirmed") {
      const botToken = typeof st.bot_token === "string" ? st.bot_token : "";
      const botId = typeof st.ilink_bot_id === "string" ? st.ilink_bot_id : "";
      const userId = typeof st.ilink_user_id === "string" ? st.ilink_user_id : "";
      const baseUrl = typeof st.baseurl === "string" ? st.baseurl : "";
      if (!botToken || !botId || !userId) {
        s.phase = "failed";
        s.error = "confirmed_but_incomplete: server did not return the full credential triple";
        error(`session#${gen}: confirmed but incomplete (token=${shape(botToken)} botId=${shape(botId)} userId=${shape(userId)})`);
        return;
      }
      s.phase = "done";
      s.creds = { bot_token: botToken, ilink_bot_id: botId, ilink_user_id: userId };
      s.doneServed = false;
      info(`session#${gen}: confirmed (bot_token ${shape(botToken)}, ilink_bot_id ${shape(botId)}, ilink_user_id ${shape(userId)}, baseurl ${baseUrl || "absent"})`);
      persistConfirmedAccount({ botToken, botId, baseUrl, userId });
      return;
    } else {
      s.phase = "failed";
      s.error = `login_failed: unexpected status ${String(status)}`;
      warn(`session#${gen}: unexpected status ${String(status)}`);
      return;
    }
    try { await sleep(POLL_GAP_MS, signal); } catch { return; }
  }
  if (alive()) {
    s.phase = "failed";
    s.error = "login_timeout: session budget exhausted";
    warn(`session#${gen}: budget exhausted`);
  }
}

/** login-qr.ts purgeExpiredLogins analog — nothing retained between sessions. */

/** begin: restart the session; resolve once the first QR is in or BEGIN_QR_WAIT_MS passed. */
function beginSession() {
  if (session) session.abort.abort();
  const abort = new AbortController();
  session = {
    gen: (session?.gen ?? 0) + 1,
    phase: "starting",
    error: undefined,
    qrUrl: undefined,
    qrcode: undefined,
    qrAt: 0,
    pollingBase: FIXED_BASE_URL,
    refreshes: 0,
    creds: undefined,
    doneServed: false,
    startedAt: Date.now(),
    abort,
  };
  const s = session;
  info(`session#${s.gen}: begin (account_sync=${OPTS.accountSync ? "on" : "off"})`);
  runDriver(s.gen, abort.signal).catch((e) => {
    if (!abort.signal.aborted) error(`session#${s.gen}: driver crashed: ${e.message}`);
  });
  return s;
}

/** The status projection (contract shape; token only on the first done poll). */
function statusPayload(s = session) {
  if (!s) return { state: "starting" };
  const remainSec = s.qrAt ? Math.max(0, Math.round((s.qrAt + QR_TTL_MS - Date.now()) / 1000)) : 0;
  switch (s.phase) {
    case "starting":
      return { state: "starting" };
    case "qr_ready":
      return { state: "qr_ready", qrUrl: s.qrUrl, expireIn: remainSec };
    case "polling":
      return { state: "polling", qrUrl: s.qrUrl, userCode: "", expireIn: remainSec };
    case "done":
      if (!s.doneServed && s.creds) {
        s.doneServed = true;
        return { state: "done", credentials: s.creds };
      }
      return { state: "done" }; // token never appears in any other response
    case "failed":
      return { state: "failed", error: s.error ?? "login_failed" };
    default:
      return { state: "starting" };
  }
}

// ───────────────────────────── http face ─────────────────────────────

function sendJson(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(body) });
  res.end(body);
}

// weixin-outbound batch: the send-leg handler, assembled once with this file's
// shared helpers (send.mjs stays importable standalone for unit tests).
// `inboundActive` reports the inbound loop state; this side-car ships none yet
// (the inbound batch owns that half) so it is constantly false for now — the
// send leg logs its observability WARN on that, per the send contract.
const handleSend = makeSendHandler({
  info,
  warn,
  error,
  debug,
  sendJson,
  loadAccount,
  listIndexedAccountIds,
  normalizeAccountId,
  PLUGIN_VERSION,
  ILINK_APP_ID,
  ILINK_APP_CLIENT_VERSION,
  ILINK_DEFAULT_BASE: FIXED_BASE_URL,
  inboundActive: () => false, // 候与入站批对账归一: the inbound batch flips this to its loop state
});

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host ?? "127.0.0.1"}`);
  const p = url.pathname.replace(/\/+$/, "") || "/";
  debug(`${req.method} ${p}`);

  if (p === "/login/begin" || p === "/login/status") {
    if (req.method !== "GET") {
      sendJson(res, 405, { error: "method_not_allowed: this seam serves GET only" });
      return;
    }
    if (p === "/login/begin") {
      const s = beginSession();
      // Contract: begin MAY already carry the first QR. Wait briefly (bounded
      // well under driveLogin's 5 s HTTP timeout) so the card gets the QR in
      // one round trip when the network is healthy; fall through to `starting`
      // otherwise — status picks it up within one poll.
      const started = Date.now();
      const waitQr = setInterval(() => {
        const ready = s.qrUrl || s.phase === "failed";
        if (ready || Date.now() - started >= BEGIN_QR_WAIT_MS) {
          clearInterval(waitQr);
          sendJson(res, 200, s.qrUrl
            ? { state: "qr_ready", qrUrl: s.qrUrl, expireIn: Math.round(QR_TTL_MS / 1000) }
            : statusPayload(s));
        }
      }, 50);
      return;
    }
    sendJson(res, 200, statusPayload());
    return;
  }

  if (p === "/healthz") {
    sendJson(res, 200, { ok: true, state: session?.phase ?? "idle", gen: session?.gen ?? 0 });
    return;
  }

  // weixin-outbound batch: the authenticated outbound send leg (send.mjs owns
  // the whole contract — auth, body, retry boundary, iLink wire mirror).
  if (p === "/send") {
    handleSend(req, res);
    return;
  }

  sendJson(res, 404, { error: "not_found" });
});

server.listen(OPTS.port, OPTS.host, () => {
  info(`weixin side-car listening on http://${OPTS.host}:${OPTS.port} (plugin ${PLUGIN_VERSION} wire mirror; account_sync=${OPTS.accountSync ? "on" : "off"})`);
  info(`contract: GET /login/begin, GET /login/status (README "Weixin (iLink) channel"); ilink base ${FIXED_BASE_URL}`);
});

for (const sig of ["SIGINT", "SIGTERM"]) {
  process.on(sig, () => {
    info(`${sig} received, closing`);
    session?.abort.abort();
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 1500).unref();
  });
}

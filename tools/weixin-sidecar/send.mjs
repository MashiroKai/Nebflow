// Nebflow Weixin (iLink) side-car — OUTBOUND send leg (weixin-outbound batch,
// 2026-10-07). Deliberately a SEPARATE file from shim.mjs (the login/control
// leg): the two message-loop halves are developed and reconciled independently
// (unified interface contract ④: "shim 文件级：出站 send 段独立文件开发").
//
// Contract served (security design section 0 of the batch report, which THIS
// file implements verbatim):
//
//   POST /send
//     Authorization: Bearer <shared send token>   — required; constant-time
//       compared. Token source order (names family-aligned with the inbound
//       batch's WEIXIN_MSGLOOP_TOKEN_FILE; 候与入站批对账归一):
//         1. env WEIXIN_SEND_TOKEN                      (explicit value)
//         2. env WEIXIN_SEND_TOKEN_FILE                 (explicit file path)
//         3. <nebflow home>/secrets/weixin-ilink-send-token — the per-leg mint
//            the engine keeps at <dataRoot>/secrets/ (sibling of the inbound
//            batch's weixin-ilink-ingress-token: ONE credential system, ONE
//            instance per leg; NEBFLOW_HOME overrides the home, default
//            ~/.nebflow)
//       The token is re-read PER REQUEST: replacing the file revokes the old
//       token without a shim restart (env-configured values need a restart —
//       documented).
//     Body: {"to": "<ilink_user_id>", "text": "...", "account"?, "client_id"?}
//
//   Responses:
//     200 {"ok":true,"delivered":true,"message_id"?,"client_id","attempts",
//          "context_threaded":false}      — iLink accepted (ret=0)
//     400 {"error":"invalid_body:…"}      — bad JSON / missing to / missing or
//                                           oversize text (sanity bound only —
//                                           the protocol itself has no cap)
//     401 {"error":"unauthorized"}        — missing/mismatching bearer token
//     405 {"error":"method_not_allowed: POST only"}
//     500 {"error":"…"}                   — host-account state refusal (empty /
//                                           ambiguous / unusable account index)
//     502 {"error":"delivery_failed:…"}   — iLink refused (ret!==0 / HTTP 4xx-5xx,
//                                           never retried) or network-class
//                                           failure after the retry budget
//     503 {"error":"shim_token_unconfigured"} — the send leg is CLOSED (fail-
//                                           closed; never degrades to open)
//
//   Retry boundary (design §0.4): ONLY network-class failures (the
//   classifyFetchError vocabulary: timeout/dns/tcp_refused/tcp_timeout/tls/
//   network) are retried, at most WEIXIN_SIDECAR_SEND_ATTEMPTS (default 2)
//   with WEIXIN_SIDECAR_SEND_BACKOFF_MS (default 1000 ms) between attempts and
//   WEIXIN_SIDECAR_SEND_TIMEOUT_MS (default 15000 ms — the official plugin's
//   DEFAULT_API_TIMEOUT_MS) per attempt. HTTP 4xx/5xx and iLink ret!==0 are
//   DETERMINISTIC rejections and are never retried.
//
//   Credential flow (design §0.2): the bot_token NEVER crosses the engine↔shim
//   wire. It is read here, per send, from the host account file this side-car
//   already mirrors (~/.openclaw/openclaw-weixin/accounts/<id>.json — the same
//   file the official plugin reads). Account selection: the optional `account`
//   field (normalized id or raw bot id), else THE single indexed account; with
//   several accounts and no hint the request is refused rather than guessed.
//
//   Mutual exclusion (design §0.4, contract ②): a send consumes no getUpdates
//   cursor and the server dedups by client_id, so the outbound leg does not
//   require the single-consumer mutex. When the side-car's inbound loop is not
//   running, a WARN line is logged for observability (ctx.inboundActive()).
//   候与入站批对账归一.
//
//   context_token: NOT threaded in v1 (the inbound loop owns the per-user
//   context cache once it lands; the official plugin itself sends without
//   context when the token is missing — warn, not refuse). The response says
//   "context_threaded":false so the reading is explicit. 候与入站批对账归一.
//
// Wire mirror (official plugin 2.4.9 dist/src/api/api.js + messaging/send.js):
//   POST {base}/ilink/bot/sendmessage
//     headers: Content-Type: application/json, AuthorizationType:
//     ilink_bot_token, X-WECHAT-UIN (random uint32 → decimal → base64),
//     iLink-App-Id, iLink-App-ClientVersion (132105), Authorization: Bearer
//     <bot_token>
//     body: {"msg":{"from_user_id":"","to_user_id","client_id",
//     "message_type":2(BOT),"message_state":2(FINISH),"item_list":[{"type":1
//     (TEXT),"text_item":{"text"}}]},"base_info":{"channel_version","bot_agent"}}
//   The response's uint64 message_id is quoted before JSON.parse (the plugin's
//   parseWeixinApiJson LOSSLESS_ID_FIELDS discipline) so it never loses
//   precision as a JS number.
//
// Zero npm dependencies. This module imports NOTHING from shim.mjs (so it can
// be unit-tested standalone); shim.mjs hands the shared helpers in once via
// makeSendHandler(ctx).

import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

// ───────────────────────────── config ─────────────────────────────

const SEND_TIMEOUT_MS = Number(process.env.WEIXIN_SIDECAR_SEND_TIMEOUT_MS ?? 15_000);
const SEND_ATTEMPTS = Math.max(1, Number(process.env.WEIXIN_SIDECAR_SEND_ATTEMPTS ?? 2));
const SEND_BACKOFF_MS = Number(process.env.WEIXIN_SIDECAR_SEND_BACKOFF_MS ?? 1_000);
/** Shim-side sanity bound only (NOT a protocol limit): refuse absurd bodies. */
const SEND_TEXT_MAX_CHARS = 60_000;
/** MessageItemType.TEXT / MessageType.BOT / MessageState.FINISH (api/types.js). */
const ITEM_TYPE_TEXT = 1;
const MESSAGE_TYPE_BOT = 2;
const MESSAGE_STATE_FINISH = 2;
/** parseWeixinApiJson LOSSLESS_ID_FIELDS mirror: uint64 ids quoted before parse. */
const LOSSLESS_ID_FIELDS = new Set(["message_id", "msg_id", "svr_id"]);

/** base_info.bot_agent — grammar-checked against sanitizeBotAgent: product form. */
const BOT_AGENT = "NebflowSidecar/1.0";

// ───────────────────────── shared token face ─────────────────────────

/** The nebflow home the default token file lives under (design §0.2). */
function nebflowHome() {
  return process.env.NEBFLOW_HOME?.trim() || path.join(os.homedir(), ".nebflow");
}

/** Token source order: env value → env-named file → the engine's per-leg mint. */
function readSharedToken() {
  const env = process.env.WEIXIN_SEND_TOKEN?.trim();
  if (env) return env;
  const file =
    process.env.WEIXIN_SEND_TOKEN_FILE?.trim() ||
    path.join(nebflowHome(), "secrets", "weixin-ilink-send-token");
  try {
    const v = fs.readFileSync(file, "utf-8").trim();
    return v || null;
  } catch {
    return null;
  }
}

/** Constant-time equality; unequal lengths never short-circuit on content. */
function tokensMatch(expected, provided) {
  if (!expected || !provided) return false;
  const a = Buffer.from(expected, "utf-8");
  const b = Buffer.from(provided, "utf-8");
  if (a.length !== b.length) {
    // Burn a comparison anyway so a wrong-length probe is not cheaper than a
    // wrong-value probe of the same length class.
    crypto.timingSafeEqual(a, a);
    return false;
  }
  return crypto.timingSafeEqual(a, b);
}

function bearerOf(req) {
  const h = req.headers["authorization"];
  if (typeof h !== "string") return null;
  const m = /^Bearer\s+(.+)$/.exec(h.trim());
  return m ? m[1].trim() : null;
}

// ───────────────── iLink wire (2.4.9 mirror; pure, ctx-injected) ─────────────────

/** api.ts randomWechatUin mirror — random uint32 → decimal string → base64. */
function randomWechatUin() {
  const uint32 = crypto.randomBytes(4).readUInt32BE(0);
  return Buffer.from(String(uint32), "utf-8").toString("base64");
}

/** generateId grammar mirror (util/random.js) with an honest sender prefix. */
function generateClientId() {
  return `nebflow-sidecar:${Date.now()}-${crypto.randomBytes(4).toString("hex")}`;
}

/** messaging/send.js buildTextMessageReq mirror — single TEXT item, FINISH. */
export function buildTextMessageReq({ to, text, clientId }) {
  return {
    msg: {
      from_user_id: "",
      to_user_id: to,
      client_id: clientId,
      message_type: MESSAGE_TYPE_BOT,
      message_state: MESSAGE_STATE_FINISH,
      item_list: text ? [{ type: ITEM_TYPE_TEXT, text_item: { text } }] : undefined,
    },
  };
}

/**
 * Quote uint64 id fields before JSON.parse (parseWeixinApiJson discipline,
 * compact form): rewrites ONLY actual object properties, never string content.
 */
export function parseLosslessIds(rawText) {
  let out = "";
  let i = 0;
  while (i < rawText.length) {
    if (rawText[i] !== '"') {
      out += rawText[i++];
      continue;
    }
    const start = i;
    i++;
    let esc = false;
    while (i < rawText.length) {
      const ch = rawText[i++];
      if (esc) esc = false;
      else if (ch === "\\") esc = true;
      else if (ch === '"') break;
    }
    const tok = rawText.slice(start, i);
    out += tok;
    let c = i;
    while (/\s/.test(rawText[c] ?? "")) c++;
    if (rawText[c] !== ":") continue;
    let key;
    try {
      key = JSON.parse(tok);
    } catch {
      continue;
    }
    if (typeof key !== "string" || !LOSSLESS_ID_FIELDS.has(key)) continue;
    out += rawText.slice(i, c + 1);
    c++;
    while (/\s/.test(rawText[c] ?? "")) out += rawText[c++];
    const nStart = c;
    if (rawText[c] === "-") c++;
    while (/\d/.test(rawText[c] ?? "")) c++;
    if (c > nStart && !(c === nStart + 1 && rawText[nStart] === "-")) {
      out += `"${rawText.slice(nStart, c)}"`;
      i = c;
    } else {
      i = nStart;
    }
  }
  return JSON.parse(out);
}

/** One sendmessage attempt. Resolves the parsed iLink response; throws on failure. */
async function ilinkSendMessage(cx, { base, botToken, to, text, clientId, signal }) {
  const url = `${base.replace(/\/+$/, "")}/ilink/bot/sendmessage`;
  const body = JSON.stringify({
    ...buildTextMessageReq({ to, text, clientId }),
    base_info: { channel_version: cx.PLUGIN_VERSION, bot_agent: BOT_AGENT },
  });
  const res = await fetch(url, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      AuthorizationType: "ilink_bot_token",
      "X-WECHAT-UIN": randomWechatUin(),
      Authorization: `Bearer ${botToken}`,
      "iLink-App-Id": cx.ILINK_APP_ID,
      "iLink-App-ClientVersion": cx.ILINK_APP_CLIENT_VERSION,
    },
    body,
    signal: AbortSignal.any([AbortSignal.timeout(SEND_TIMEOUT_MS), ...(signal ? [signal] : [])]),
  });
  const raw = await res.text();
  cx.debug(`sendmessage status=${res.status} rawLen=${raw.length}`);
  if (!res.ok) {
    const err = new Error(`sendmessage http_${res.status}: ${raw.slice(0, 200)}`);
    err.protocol = true; // deterministic — never retried
    throw err;
  }
  const parsed = parseLosslessIds(raw);
  if (parsed.ret && parsed.ret !== 0) {
    const err = new Error(`sendmessage ret=${parsed.ret} errmsg=${parsed.errmsg ?? "(none)"}`);
    err.protocol = true;
    throw err;
  }
  return parsed;
}

/** Classify a fetch error with the plugin's vocabulary (vocabulary only). */
export function classifyFetchError(err) {
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
 * Send with the design §0.4 retry boundary: network-class failures only,
 * at most SEND_ATTEMPTS attempts. Deterministic failures rethrow immediately.
 */
async function sendWithRetry(cx, params) {
  let lastErr = null;
  for (let attempt = 1; attempt <= SEND_ATTEMPTS; attempt++) {
    try {
      const parsed = await ilinkSendMessage(cx, params);
      return { parsed, attempts: attempt };
    } catch (err) {
      lastErr = err;
      if (err.protocol || params.signal?.aborted) throw err;
      const kind = classifyFetchError(err);
      cx.warn(`send: attempt ${attempt}/${SEND_ATTEMPTS} failed (${kind}: ${err.message})`);
      if (attempt < SEND_ATTEMPTS) await new Promise((r) => setTimeout(r, SEND_BACKOFF_MS));
    }
  }
  const err = new Error(`send failed after ${SEND_ATTEMPTS} attempts: ${lastErr?.message ?? lastErr}`);
  err.networkClass = classifyFetchError(lastErr);
  throw err;
}

// ───────────────────────── host account face ─────────────────────────

/**
 * Resolve the bot credentials for one send. `hint` is the optional `account`
 * request field (normalized account id or raw bot id). Returns
 * {id, token, baseUrl} or {refusal} — a multi-account index without a hint is
 * an explicit refusal, never a guess.
 */
export function resolveAccount(hint, cx) {
  const ids = cx.listIndexedAccountIds();
  if (ids.length === 0) {
    return { refusal: "no_host_account: the host account index is empty (no confirmed login mirrored)" };
  }
  let id = null;
  if (hint?.trim()) {
    const h = hint.trim();
    id = ids.find((i) => i === h) ?? ids.find((i) => i === cx.normalizeAccountId(h)) ?? null;
    if (!id) return { refusal: `unknown_account: '${hint}' is not in the host account index` };
  } else if (ids.length === 1) {
    id = ids[0];
  } else {
    return { refusal: `ambiguous_account: ${ids.length} host accounts indexed (${ids.join(", ")}); pass "account"` };
  }
  const acct = cx.loadAccount(id);
  const token = acct?.token?.trim();
  if (!token) return { refusal: `unusable_account: ${id} carries no token` };
  return { id, token, baseUrl: acct.baseUrl?.trim() || null };
}

// ───────────────────────────── route handler ─────────────────────────────

/**
 * The /send endpoint factory. `cx` carries the shared helpers from shim.mjs:
 * {info, warn, error, debug, sendJson, loadAccount, listIndexedAccountIds,
 * normalizeAccountId, PLUGIN_VERSION, ILINK_APP_ID, ILINK_APP_CLIENT_VERSION,
 * ILINK_DEFAULT_BASE, inboundActive()}.
 */
export function makeSendHandler(cx) {
  return async function handleSend(req, res) {
    if (req.method !== "POST") {
      cx.sendJson(res, 405, { error: "method_not_allowed: POST only" });
      return;
    }

    // ── auth FIRST (fail-closed, design §0.1) ──
    const expected = readSharedToken();
    if (!expected) {
      cx.sendJson(res, 503, { error: "shim_token_unconfigured" });
      return;
    }
    if (!tokensMatch(expected, bearerOf(req))) {
      cx.warn("send: unauthorized request (bearer missing or mismatching)");
      cx.sendJson(res, 401, { error: "unauthorized" });
      return;
    }

    // ── body (bounded read) ──
    let body;
    try {
      const raw = await new Promise((resolve, reject) => {
        const chunks = [];
        let size = 0;
        req.on("data", (c) => {
          size += c.length;
          if (size > 4 * SEND_TEXT_MAX_CHARS) {
            reject(new Error("body_too_large"));
            req.destroy();
            return;
          }
          chunks.push(c);
        });
        req.on("end", () => resolve(Buffer.concat(chunks).toString("utf-8")));
        req.on("error", reject);
      });
      body = JSON.parse(raw);
    } catch (e) {
      cx.sendJson(res, 400, { error: `invalid_body: ${e.message}` });
      return;
    }
    const to = typeof body?.to === "string" ? body.to.trim() : "";
    const text = typeof body?.text === "string" ? body.text : null;
    if (!to) {
      cx.sendJson(res, 400, { error: "invalid_body: 'to' (ilink_user_id) is required" });
      return;
    }
    if (text == null || !text.trim()) {
      cx.sendJson(res, 400, { error: "invalid_body: 'text' is required" });
      return;
    }
    if (text.length > SEND_TEXT_MAX_CHARS) {
      cx.sendJson(res, 400, { error: `invalid_body: text exceeds the ${SEND_TEXT_MAX_CHARS}-char sanity bound` });
      return;
    }

    if (!cx.inboundActive()) {
      cx.warn(
        "send: inbound loop not active — outbound requires no single-consumer mutex (sends consume no cursor); logged for observability",
      );
    }

    // ── credentials: bot_token from the host account file, never the wire ──
    const acct = resolveAccount(body.account, cx);
    if (acct.refusal) {
      cx.error(`send: account refusal — ${acct.refusal}`);
      cx.sendJson(res, 500, { error: acct.refusal });
      return;
    }
    const clientId =
      typeof body.client_id === "string" && body.client_id.trim() ? body.client_id.trim() : generateClientId();
    const base =
      process.env.WEIXIN_SIDECAR_ILINK_BASE?.trim() || // test/ops seam, highest
      acct.baseUrl ||
      cx.ILINK_DEFAULT_BASE;

    // Abort in-flight iLink attempts when the caller goes away mid-send.
    const ac = new AbortController();
    res.on("close", () => {
      if (!res.writableEnded) ac.abort();
    });

    cx.info(`send: to=${to} account=${acct.id} base=${base} clientId=${clientId} textLen=${text.length}`);
    try {
      const { parsed, attempts } = await sendWithRetry(cx, {
        base,
        botToken: acct.token,
        to,
        text,
        clientId,
        signal: ac.signal,
      });
      const messageId = typeof parsed.message_id === "string" ? parsed.message_id : undefined;
      cx.info(`send: delivered to=${to} (message_id ${messageId ? `${messageId.length}B` : "absent"}, attempts=${attempts})`);
      cx.sendJson(res, 200, {
        ok: true,
        delivered: true,
        ...(messageId ? { message_id: messageId } : {}),
        client_id: clientId,
        attempts,
        context_threaded: false,
      });
    } catch (err) {
      if (err.protocol) {
        cx.error(`send: iLink refused to=${to}: ${err.message}`);
        cx.sendJson(res, 502, { error: `delivery_failed: ${err.message}`, client_id: clientId });
      } else {
        cx.error(`send: network-class failure to=${to} (${err.networkClass ?? classifyFetchError(err)}): ${err.message}`);
        cx.sendJson(res, 502, {
          error: `delivery_failed: network ${err.networkClass ?? classifyFetchError(err)} after retries`,
          client_id: clientId,
          attempts: SEND_ATTEMPTS,
        });
      }
    }
  };
}

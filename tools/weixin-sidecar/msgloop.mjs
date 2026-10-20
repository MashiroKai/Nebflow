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
 *   WEIXIN_MSGLOOP_ACCOUNT_ID   bot account override (default: newest indexed;
 *                               startup pin only — self-heal may re-target)
 *
 * 🔴 Zero-secret discipline (from shim.mjs): no token value ever logged —
 * length/shape only. Mutex plane (§1.2): one consumer per bot account via
 * <accountId>.msgloop.lock (pid+heartbeat liveness, stale preemption,
 * owner-checked release). Cursor plane: the plugin's own <accountId>.sync.json
 * (byte-compatible; advanced only after a 2xx verdict — at-least-once).
 *
 * Self-heal (msgloop self-heal batch, 2026-10-08): a QR re-bind revokes the
 * previous bot token server-side (the -14 loop) and lands a NEW index entry,
 * so the loop re-reads the account index (once on -14; every pause wake ≤60s;
 * throttled to ≥5min while polling) and adopts the newest valid account
 * in-process — mutex released owner-checked, fresh per-account cursor, zero
 * restart. Kill-switch semantics above are unchanged.
 *
 * Inbound media (weixin-inbound-media batch, 2026-10-08): IMAGE (type 2) /
 * FILE (4) / VIDEO (5) items additionally land on local disk BEFORE the
 * deliver leg — download + AES-128-ECB decrypt from the item's CDN reference,
 * saved under <NEBFLOW_HOME>/media/weixin-inbound/<accountId>/<messageId>-<name>
 * (official 2.4.9 mirrors: media/media-download.ts, cdn/pic-decrypt.ts,
 * cdn/aes-ecb.ts — download direction; the upload twins live in send.mjs).
 * A landed item rides the ingress body as mediaPath/mediaKind/mediaName/
 * mediaMime with the placeholder label still in `text`; ANY media failure
 * (download / decrypt / timeout / >100MB) degrades to the existing
 * placeholder-label delivery — the cursor is NEVER held for a media leg
 * (at-least-once, §T6). Voice stays out of scope (its transcript already
 * rides as text). v1 keeps NO automatic cleanup: the media dir grows with
 * traffic — an ops concern, swept by hand.
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
const ACCOUNT_RECHECK_MS = 5 * 60_000; // self-heal: index re-read throttle while polling (pause wakes re-read on EVERY ≤60s wake)

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

/**
 * The ingress body (contract §2 wire shape). `media` — the deliver leg's media
 * step result (weixin-inbound-media batch) — rides as the four optional fields
 * ONLY when the item landed on disk; `text` keeps the placeholder label either
 * way (the engine rewrites its own richer line when the path is good).
 */
export function toInboundBody(msg, media = null) {
  const body = { senderId: String(msg?.from_user_id ?? "") };
  const text = messageBody(msg);
  if (text) body.text = text;
  const id = messageIdOf(msg);
  if (id) body.messageId = id;
  if (media) {
    body.mediaPath = media.mediaPath;
    body.mediaKind = media.mediaKind;
    body.mediaName = media.mediaName;
    body.mediaMime = media.mediaMime;
  }
  return body;
}

// ───── weixin-inbound-media batch: CDN download + decrypt + persist ─────
// Download-direction mirrors of the official 2.4.9 plugin (media/
// media-download.ts downloadMediaFromItem, cdn/pic-decrypt.ts
// downloadAndDecryptBuffer + parseAesKey, cdn/aes-ecb.ts decryptAesEcb,
// media/mime.ts getMimeFromFilename). The upload twins (encrypt side) live in
// send.mjs — same family, opposite direction, same CDN base constant.

const MEDIA_MAX_BYTES = 100 * 1024 * 1024; // media-download.ts WEIXIN_MEDIA_MAX_BYTES
// send.mjs §0.4 budget twins: network-class failures retry, deterministic
// refusals (protocol) abort at once. Env-tunable for ops; defaults aligned.
const MEDIA_ATTEMPTS = Math.max(1, Number(process.env.WEIXIN_SIDECAR_MEDIA_ATTEMPTS ?? 2));
const MEDIA_BACKOFF_MS = Number(process.env.WEIXIN_SIDECAR_MEDIA_BACKOFF_MS ?? 1_000);
const MEDIA_DOWNLOAD_TIMEOUT_MS = Number(process.env.WEIXIN_SIDECAR_MEDIA_TIMEOUT_MS ?? 30_000); // send.mjs CDN_TIMEOUT_MS twin
/** accounts.ts CDN_BASE_URL — the download fallback base (send.mjs's twin). */
const CDN_DEFAULT_BASE = "https://novac2c.cdn.weixin.qq.com/c2c";
/** MessageItemType → mediaKind (voice 3 stays out of scope: transcript rides as text). */
const MEDIA_KINDS = { 2: "image", 4: "file", 5: "video" };
/** sniffImageMime → the file-name extension for a landed image (no wire name). */
const IMAGE_EXT = { "image/png": ".png", "image/jpeg": ".jpg", "image/gif": ".gif", "image/webp": ".webp", "image/bmp": ".bmp" };

/** The media root: <NEBFLOW_HOME>/media/weixin-inbound (NEBFLOW_HOME twin of
 * send.mjs nebflowHome(); read per call so ops/tests can re-point it). */
export function mediaRootOf() {
  const home = process.env.NEBFLOW_HOME?.trim() || path.join(os.homedir(), ".nebflow");
  return path.join(home, "media", "weixin-inbound");
}

/**
 * pic-decrypt.ts parseAesKey mirror: CDNMedia.aes_key arrives in two wild
 * encodings — base64(16 raw bytes) (images' media.aes_key) or base64(32 hex
 * chars) (file/voice/video, and images' image_item.aeskey after the hex→base64
 * normalize). Anything else is a deterministic refusal (protocol: the CDN
 * retry budget must not spin on a bad key).
 */
export function parseAesKey(aesKeyBase64, label = "media") {
  const decoded = Buffer.from(String(aesKeyBase64 ?? ""), "base64");
  if (decoded.length === 16) return decoded;
  if (decoded.length === 32 && /^[0-9a-fA-F]{32}$/.test(decoded.toString("ascii"))) {
    return Buffer.from(decoded.toString("ascii"), "hex"); // base64 → hex string → raw bytes
  }
  const err = new Error(`${label}: aes_key must decode to 16 raw bytes or a 32-char hex string, got ${decoded.length}B`);
  err.protocol = true;
  throw err;
}

/** aes-ecb.ts decryptAesEcb mirror — AES-128-ECB, PKCS7 (the node default). */
export function decryptAesEcb(ciphertext, key) {
  const decipher = crypto.createDecipheriv("aes-128-ecb", key, null);
  return Buffer.concat([decipher.update(ciphertext), decipher.final()]);
}

/** mime.ts getMimeFromFilename mirror (the ported table; unknown → octet-stream). */
const FILE_EXT_MIME = {
  ".pdf": "application/pdf",
  ".doc": "application/msword",
  ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  ".xls": "application/vnd.ms-excel",
  ".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  ".ppt": "application/vnd.ms-powerpoint",
  ".pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
  ".txt": "text/plain",
  ".csv": "text/csv",
  ".zip": "application/zip",
  ".tar": "application/x-tar",
  ".gz": "application/gzip",
  ".mp3": "audio/mpeg",
  ".ogg": "audio/ogg",
  ".wav": "audio/wav",
  ".mp4": "video/mp4",
  ".mov": "video/quicktime",
  ".webm": "video/webm",
  ".mkv": "video/x-matroska",
  ".avi": "video/x-msvideo",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".gif": "image/gif",
  ".webp": "image/webp",
  ".bmp": "image/bmp",
};
export function mimeFromFilename(filename) {
  const ext = path.extname(String(filename ?? "")).toLowerCase();
  return FILE_EXT_MIME[ext] ?? "application/octet-stream";
}

/** Image containers by magic bytes; unrecognized → "image/*" (the official
 * inbound MediaType spelling for images — we never guess a wrong exact mime). */
export function sniffImageMime(buf) {
  const b = Buffer.from(buf ?? []);
  const latin1 = (from, to) => b.subarray(from, to).toString("latin1"); // byte-faithful (ascii strips the high bit)
  if (b.length >= 8 && latin1(0, 8) === "\x89PNG\r\n\x1a\n") return "image/png";
  if (b.length >= 3 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff) return "image/jpeg";
  if (b.length >= 6 && (latin1(0, 6) === "GIF87a" || latin1(0, 6) === "GIF89a")) return "image/gif";
  if (b.length >= 12 && latin1(0, 4) === "RIFF" && latin1(8, 12) === "WEBP") return "image/webp";
  if (b.length >= 2 && b[0] === 0x42 && b[1] === 0x4d) return "image/bmp";
  return "image/*";
}

/**
 * A peer-controlled file_name must never steer the save path: basename only,
 * separators/control chars flattened, leading dots pinned, UTF-8 byte-capped
 * (CJK names survive — this is the name the agent will see in the injection
 * line). `fallback` covers an absent/empty name.
 */
export function safeFileName(raw, fallback) {
  const cleaned = path
    .basename(String(raw ?? ""))
    .replace(/[\\/\x00-\x1f]/g, "_")
    .replace(/^\.+/, "_")
    .trim();
  if (!cleaned || cleaned === "_") return fallback;
  let out = cleaned;
  while (Buffer.byteLength(out, "utf-8") > 200) out = out.slice(0, -1);
  return out;
}

/** message ids are numeric wire strings — flatten defensively for the file name. */
function safeId(raw) {
  return String(raw ?? "").replace(/[^A-Za-z0-9_-]/g, "-").slice(0, 80);
}

/** pic-decrypt.ts fetchCdnBytes twin, plus the 100MB bound: a declared
 * content-length over the bound is refused up-front; a server that lies about
 * it is cut off mid-stream (bounded memory). Client-refusal statuses (4xx) are
 * deterministic (protocol); server trouble retries within the §0.4 budget. */
async function fetchCdnBytes(url, { label, fetchImpl }) {
  const res = await fetchImpl(url, { signal: AbortSignal.timeout(MEDIA_DOWNLOAD_TIMEOUT_MS) });
  if (!res.ok) {
    const body = await res.text().catch(() => "");
    const err = new Error(`CDN download ${res.status} ${res.statusText} body=${body.slice(0, 200)}`);
    err.protocol = res.status >= 400 && res.status < 500;
    throw err;
  }
  const declared = Number(res.headers?.get?.("content-length") ?? "");
  if (Number.isFinite(declared) && declared > MEDIA_MAX_BYTES) {
    try { res.body?.cancel(); } catch { /* already gone */ }
    const err = new Error(`CDN object exceeds the ${MEDIA_MAX_BYTES}-byte bound (content-length ${declared})`);
    err.protocol = true;
    throw err;
  }
  const chunks = [];
  let total = 0;
  for await (const chunk of res.body) {
    total += chunk.length;
    if (total > MEDIA_MAX_BYTES) {
      try { res.body?.cancel(); } catch { /* already gone */ }
      const err = new Error(`CDN object exceeds the ${MEDIA_MAX_BYTES}-byte bound mid-stream`);
      err.protocol = true;
      throw err;
    }
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

/**
 * pic-decrypt.ts downloadAndDecryptBuffer mirror (download direction): the
 * server's full_url wins; ${cdn}/download?encrypted_query_param=… is the
 * fallback spelling (cdn-url.ts buildCdnDownloadUrl). AES-128-ECB decrypt with
 * the parsed key. Raises on any stage — the caller owns the degrade.
 */
async function downloadAndDecryptBuffer({ encryptedQueryParam, aesKeyBase64, fullUrl, cdnBase, label, fetchImpl }) {
  const key = parseAesKey(aesKeyBase64, label);
  const trimmedFull = typeof fullUrl === "string" ? fullUrl.trim() : "";
  const trimmedParam = typeof encryptedQueryParam === "string" ? encryptedQueryParam.trim() : "";
  let url;
  if (trimmedFull) url = trimmedFull;
  else if (trimmedParam) {
    url = `${cdnBase.replace(/\/+$/, "")}/download?encrypted_query_param=${encodeURIComponent(trimmedParam)}`;
  } else {
    const err = new Error(`${label}: no CDN download URL (need full_url or encrypt_query_param)`);
    err.protocol = true;
    throw err;
  }
  debug(`media: ${label} GET ${url.replace(/([?&][^=]+=)[^&]+/g, "$1…")}`);
  const encrypted = await fetchCdnBytes(url, { label, fetchImpl });
  const decrypted = decryptAesEcb(encrypted, key);
  debug(`media: ${label} decrypted ${encrypted.length}B → ${decrypted.length}B`);
  return decrypted;
}

/** The §0.4-shaped retry wrapper: network-class failures retry within the
 * MEDIA_ATTEMPTS budget; a deterministic (protocol) refusal propagates at
 * once. Same boundary as send.mjs withNetworkRetry, download direction. */
async function withMediaRetry(label, fn) {
  let lastErr = null;
  for (let attempt = 1; attempt <= MEDIA_ATTEMPTS; attempt++) {
    try {
      return await fn();
    } catch (err) {
      lastErr = err;
      if (err?.protocol) throw err;
      warn(`media: ${label} attempt ${attempt}/${MEDIA_ATTEMPTS} failed (${classifyFetchError(err)}: ${err.message})`);
      if (attempt < MEDIA_ATTEMPTS) await new Promise((r) => setTimeout(r, MEDIA_BACKOFF_MS));
    }
  }
  throw lastErr;
}

/**
 * The deliver-leg media step (weixin-inbound-media batch): find the FIRST
 * image/file/video item, download + decrypt + persist it to
 * <mediaRoot>/<accountId>/<messageId>-<name>, and return the four wire fields
 * {mediaPath, mediaKind, mediaName, mediaMime}. v1 keeps NO automatic cleanup —
 * the dir grows with traffic (swept by hand; see the header note). null = the
 * message carries no in-scope item OR anything failed: the caller degrades to
 * the placeholder-label delivery and the cursor is NEVER held for a media leg
 * (this function is TOTAL — it does not throw). `deps` is the test/ops seam
 * (fetchImpl / mediaRoot / now / cdnBase); a live call passes just {accountId}.
 */
export async function processInboundMedia(msg, deps = {}) {
  try {
    const accountId = normalizeAccountId(deps.accountId ?? "");
    const item = (msg?.item_list ?? []).find((it) => MEDIA_KINDS[it.type]);
    if (!item || !accountId) return null;
    const kind = MEDIA_KINDS[item.type];
    const label = `${kind} msg=${messageIdOf(msg) ?? "?"}`;
    const media = item.image_item ?? item.file_item ?? item.video_item;
    const encryptedParam = typeof media?.media?.encrypt_query_param === "string" ? media.media.encrypt_query_param : "";
    const fullUrl = typeof media?.media?.full_url === "string" ? media.media.full_url : "";
    if (!encryptedParam.trim() && !fullUrl.trim()) {
      debug(`media: ${label} carries no CDN reference — placeholder only`);
      return null;
    }
    // The official inbound key preference: image_item.aeskey (the hex spelling)
    // wins over media.aes_key; every spelling funnels through parseAesKey.
    const itemKey = kind === "image" ? item.image_item?.aeskey : undefined;
    const aesKeyBase64 =
      typeof itemKey === "string" && itemKey.trim()
        ? Buffer.from(itemKey.trim(), "hex").toString("base64")
        : typeof media?.media?.aes_key === "string"
          ? media.media.aes_key
          : "";
    if (!aesKeyBase64.trim()) {
      debug(`media: ${label} carries no aes_key — placeholder only`);
      return null;
    }
    const buf = await withMediaRetry(label, () =>
      downloadAndDecryptBuffer({
        encryptedQueryParam: encryptedParam,
        aesKeyBase64,
        fullUrl,
        cdnBase: deps.cdnBase ?? CDN_DEFAULT_BASE,
        label,
        fetchImpl: deps.fetchImpl ?? fetch,
      }),
    );
    if (buf.length === 0) {
      const err = new Error(`${label}: decrypted buffer is empty`);
      err.protocol = true;
      throw err;
    }
    // media-download.ts naming/mime per kind: file → file_name guesses the
    // mime; video lands as a plain mp4; image → magic-byte sniff (the official
    // passes neither name nor mime for images and lets the store infer).
    let name;
    let mime;
    if (kind === "file") {
      name = safeFileName(item.file_item?.file_name, "file.bin");
      mime = mimeFromFilename(item.file_item?.file_name ?? "");
    } else if (kind === "video") {
      name = "video.mp4";
      mime = "video/mp4";
    } else {
      mime = sniffImageMime(buf);
      name = `image${IMAGE_EXT[mime] ?? ".bin"}`;
    }
    const dir = path.join(deps.mediaRoot ?? mediaRootOf(), accountId);
    fs.mkdirSync(dir, { recursive: true }); // v1: no automatic cleanup — grows with traffic
    const id = messageIdOf(msg) || `noid-${(deps.now ?? Date.now)()}`;
    const mediaPath = path.join(dir, `${safeId(id)}-${name}`);
    fs.writeFileSync(mediaPath, buf); // overwrite ⇒ an at-least-once redelivery is idempotent
    info(`media: ${label} landed ${buf.length}B → ${mediaPath} (${mime})`);
    return { mediaPath, mediaKind: kind, mediaName: name, mediaMime: mime };
  } catch (err) {
    warn(
      `media: ${kind4(msg)} download failed (${err?.protocol ? "protocol" : classifyFetchError(err)}: ${err?.message}) ` +
        "— degrading to the placeholder label, cursor NOT held",
    );
    return null;
  }
}

/** The item-kind label for the failure line (kind is lost by the outer catch). */
function kind4(msg) {
  const item = (msg?.item_list ?? []).find((it) => MEDIA_KINDS[it.type]);
  return item ? MEDIA_KINDS[item.type] : "media";
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

// ─────────── self-heal account plane (msgloop self-heal batch, 2026-10-08) ───────────

/**
 * Newest USABLE account in the index (self-heal selection): walk newest→oldest
 * and return the first id whose account file loads with a non-empty token —
 * null when none qualifies (so the caller keeps the status quo + a warning).
 * Pure: index + loader injected, zero fs/zero token values, so the offline
 * spec (msgloop-selfheal.test.mjs) can pin the discipline. `index` holds
 * ALREADY-NORMALIZED ids (the live caller maps listIndexedAccountIds() through
 * normalizeAccountId — startup selection normalizes the same way).
 * @param {string[]} index normalized account ids, oldest → newest
 * @param {(id: string) => {token?: unknown} | null} loader
 * @returns {string | null}
 */
export function latestValidAccountId(index, loader) {
  for (let i = index.length - 1; i >= 0; i--) {
    const id = String(index[i] ?? "").trim();
    if (!id) continue;
    const acct = loader(id);
    if (acct && typeof acct.token === "string" && acct.token.trim()) return id;
  }
  return null;
}

/**
 * The keep/switch verdict (self-heal core, pure): (current id, normalized
 * index, loader) ⇒ {action: "keep"|"switch", accountId, reason}. Switch iff
 * the newest VALID indexed account differs from the current one — a re-bind
 * lands a newer entry (adopt it); a missing/tokenless current file degrades to
 * the newest still-valid entry (fall back); an empty/unreadable index never
 * switches and never throws (keep + reason ⇒ the loop logs and holds).
 * @param {string} currentId
 * @param {string[]} index normalized account ids, oldest → newest
 * @param {(id: string) => {token?: unknown} | null} loader
 * @returns {{action: "keep" | "switch", accountId: string, reason: string}}
 */
export function decideAccountSwitch(currentId, index, loader) {
  const latest = latestValidAccountId(index, loader);
  if (!latest) {
    return {
      action: "keep",
      accountId: currentId,
      reason: index.length ? "no indexed account file carries a usable token" : "account index is empty",
    };
  }
  if (latest === currentId) {
    return { action: "keep", accountId: currentId, reason: "current account is the newest valid indexed one" };
  }
  const curAt = index.indexOf(currentId);
  const newAt = index.indexOf(latest);
  return {
    action: "switch",
    accountId: latest,
    reason:
      curAt === -1 ? "current account is not in the index"
      : newAt > curAt ? "newer valid indexed account"
      : "current account file has no usable token",
  };
}

// ───────────────────────── deliver leg (§2 wire contract) ─────────────────────────

/**
 * Dropped verdicts whose meaning is「the engine could not take the message」—
 * NOT a policy decision about the message itself. A 200 with such a verdict
 * used to advance the cursor (any 2xx counted as delivered), i.e. the message
 * was silently lost upstream exactly while a channel was disabled/miswired —
 * the dangling-secret incident window (2026-10). Two members:
 *   - adapter-not-started: the seam is not up (bridge unregistered — channel
 *     disabled, config invalid, or the loop points at the wrong instance);
 *   - inject-failed: the engine accepted the seam call but the injection threw
 *     (transient — the same class as a 5xx).
 * Both hold the cursor (at-least-once, §T6 extended: the cursor never advances
 * when the engine did not ACCEPT the message). Every other dropped reason is a
 * FINAL policy verdict (not-on-allowlist / no-session-bound / no-text-body /
 * ask-*) — holding those would wedge the loop on a poison message, so they
 * advance exactly as before (the wx_mallory precedent: dropped ⇒ no re-delivery).
 */
const CURSOR_HOLD_DROPPED = new Set(["adapter-not-started", "inject-failed"]);

/**
 * One message → the Nebflow ingest seam. Returns {delivered, status, verdict}.
 * 403 re-reads the token file once (rotation pickup) before giving up; the
 * cursor NEVER advances on auth/network failure (at-least-once, §T6) — nor on
 * a 200 whose verdict is a CURSOR_HOLD_DROPPED one (see above).
 * weixin-inbound-media batch: the media step runs BEFORE the body is built —
 * a landed item adds the four media fields, a failed one degrades to the
 * placeholder label (the step is contained; it never holds the cursor).
 */
export async function deliverToIngress(msg, accountId = loopAccount) {
  const media = await processInboundMedia(msg, { accountId });
  const body = JSON.stringify(toInboundBody(msg, media));
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
      // A 200 is a transport ack, not a delivery ack: when the verdict says the
      // engine could not accept the message, hold the cursor — the upstream
      // iLink queue redelivers on a later poll (at-least-once). Verdict-unseen
      // (non-JSON body) keeps the legacy advance: the engine spoke 2xx.
      if (verdict?.verdict === "dropped" && CURSOR_HOLD_DROPPED.has(verdict.reason)) {
        warn(`ingress: 200 dropped (${verdict.reason}) — engine did not accept, cursor held (redelivery on a later poll)`);
        return { delivered: false, status: res.status, verdict };
      }
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

/**
 * monitor.js mirror: long-poll → deliver each → cursor after 2xx. -14 = the
 * self-heal check first (newer valid indexed account ⇒ return a switch handoff
 * to the outer loop; otherwise the session-guard 1h pause), plus a periodic
 * index re-read (every pause wake, throttled to ACCOUNT_RECHECK_MS while
 * polling). Returns the {action: "switch", accountId, reason, trigger} handoff
 * on a self-heal switch, undefined on ownership loss / stop.
 */
async function liveLoop(accountId, stop) {
  let cursor = loadCursor(accountId);
  info(`live: ${cursor ? `resuming cursor (${cursor.length}B buf)` : "fresh cursor"} — ${ILINK_BASE}/ilink/bot/getupdates`);
  let failures = 0;
  let pausedUntil = 0;
  let pollTimeoutMs = LONG_POLL_TIMEOUT_MS;
  let lastAccountCheckAt = 0; // self-heal throttle (0 ⇒ first cycle re-reads at once)
  /** the one self-heal re-read (pure core; ids normalized like startup selection):
   *  a switch verdict carries the trigger and IS the handoff; a keep verdict
   *  comes back with its reason so the caller logs exactly why it held. */
  const healCheck = (trigger) => {
    const d = decideAccountSwitch(accountId, listIndexedAccountIds().map(normalizeAccountId), loadAccount);
    return d.action === "switch" ? { ...d, trigger } : d;
  };
  while (!stop?.aborted) {
    const paused = Date.now() < pausedUntil;
    if (paused || Date.now() - lastAccountCheckAt >= ACCOUNT_RECHECK_MS) {
      lastAccountCheckAt = Date.now();
      const heal = healCheck(paused ? "index re-read during -14 pause" : "periodic index re-read");
      if (heal.action === "switch") return heal;
    }
    if (paused) { await sleep(Math.min(pausedUntil - Date.now(), 60_000), stop); continue; }
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
        // Self-heal arm: a -14 here is a server-side revocation — the QR
        // re-bind has (or is about to) land a fresh index entry. Re-read the
        // index once now; switch only when a DIFFERENT valid account exists,
        // else keep the session-guard 1h pause (the pause wakes re-read the
        // index every ≤60s, so a later re-bind is still adopted in-process).
        const heal = healCheck("errcode -14");
        if (heal.action === "switch") return heal;
        pausedUntil = Date.now() + STALE_TOKEN_PAUSE_MS;
        error(`live: bot token stale (-14) — no newer valid account in the index (${heal.reason}); pausing this account for 1h (session-guard mirror)`);
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
      const r = await deliverToIngress(m, accountId);
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
          const r = await deliverToIngress(msg, accountId);
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
  let accountId = normalizeAccountId(ACCOUNT_OVERRIDE || listIndexedAccountIds().at(-1) || (FIXTURE ? "fixture-im-bot" : ""));
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
      const held = accountId; // the account THIS iteration's mutex/cursor belong to
      if (acquireMutex(held, stop.signal)) {
        let next = null;
        try {
          next = await (FIXTURE ? fixtureLoop(held, stop.signal) : liveLoop(held, stop.signal));
        } finally {
          releaseMutex(held); // owner-checked; always the account we held
        }
        // Self-heal switch: loopAccount flips AFTER the release (a signal in
        // between releases the old id — owner-checked, so a re-release is a
        // no-op) and the loop re-enters with the new account + its own cursor.
        if (!stop.signal.aborted && next?.action === "switch" && next.accountId) {
          accountId = normalizeAccountId(next.accountId);
          loopAccount = accountId;
          info(`self-heal: account ${held} → ${accountId} (${next.reason}; trigger: ${next.trigger}) — same process, fresh cursor`);
          continue;
        }
        return; // loop exited: ownership lost or stop signalled
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

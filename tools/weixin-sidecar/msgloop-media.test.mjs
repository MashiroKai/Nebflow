#!/usr/bin/env node
/**
 * msgloop inbound-media download leg (weixin-inbound-media batch, 2026-10-08).
 *
 * Pins the deliver-leg media step of msgloop.mjs against stubbed CDN fetches
 * (zero iLink traffic, zero real network): the dual aes_key encodings, the
 * decrypt round-trip against send.mjs's upload-side encrypt (one crypto
 * family), per-kind naming/mime (image hex-key sniff / file_name mime / plain
 * mp4), the 100MB bound (content-length AND mid-stream), the network-class
 * retry budget (≤2, protocol refusals never retried), the total-function
 * degrade (ANY failure → null ⇒ placeholder-label delivery, cursor NEVER
 * held), and voice staying out of scope.
 *
 * Run: node --test tools/weixin-sidecar/msgloop-media.test.mjs
 */
import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

// Env BEFORE the dynamic import pins the module consts (retry budget + the
// deliverToIngress seams). Each node --test file is its own process.
process.env.WEIXIN_SIDECAR_MEDIA_ATTEMPTS = "2"; // §0.4 budget (1 retry)
process.env.WEIXIN_SIDECAR_MEDIA_BACKOFF_MS = "10"; // fast tests
process.env.WEIXIN_MSGLOOP_INGRESS_URL = "http://127.0.0.1:9/ingress"; // never dialed un-stubbed
const TOKEN_FILE = path.join(os.tmpdir(), `nb-media-test-token-${process.pid}`);
fs.writeFileSync(TOKEN_FILE, "test-token-shape-only");
process.env.WEIXIN_SEND_TOKEN_FILE = TOKEN_FILE; // readIngressToken resolves the module const at import time

const { parseAesKey, decryptAesEcb, mimeFromFilename, sniffImageMime, safeFileName, mediaRootOf, processInboundMedia, deliverToIngress, messageIdOf } =
  await import("./msgloop.mjs");
const { encryptAesEcb } = await import("./send.mjs");

const KEY_HEX = "00112233445566778899aabbccddeeff";
const KEY = Buffer.from(KEY_HEX, "hex");
/** An encrypted payload the shim must decrypt back to `plaintext`. */
const encrypted = (plaintext, key = KEY) => encryptAesEcb(plaintext, key);
const PNG = Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), Buffer.from("fakepng")]);

/** Minimal fetch stub: `routes(url)` → a Response-like. body = array of chunk-like. */
function stubFetch(routes) {
  const calls = [];
  const fn = async (url) => {
    calls.push(String(url));
    const r = routes(String(url));
    if (!r) throw Object.assign(new Error("getaddrinfo ENOTFOUND cdn.stub"), { cause: { code: "ENOTFOUND" } });
    if (r.reject) throw r.reject;
    return {
      ok: r.status ? r.status >= 200 && r.status < 300 : true,
      status: r.status ?? 200,
      statusText: "OK",
      headers: { get: (n) => (n.toLowerCase() === "content-length" && r.contentLength != null ? String(r.contentLength) : null) },
      text: async () => r.text ?? "stub-error",
      body: (async function* () {
        for (const c of r.body ?? []) yield c;
      })(),
      [Symbol.asyncIterator]() {
        let i = 0;
        return { next: async () => (i < (r.body ?? []).length ? { value: r.body[i++], done: false } : { done: true }) };
      },
    };
  };
  fn.calls = calls;
  return fn;
}

const tmpRoot = () => fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), "nb-media-")));
const imageMsg = (extra = {}) => ({
  from_user_id: "wx_alice",
  message_id: "m1",
  item_list: [{ type: 2, image_item: { aeskey: KEY_HEX, media: { encrypt_query_param: "enc-param-1" }, ...extra } }],
});

// ───────────────────────── crypto + naming helpers ─────────────────────────

test("MD1 parseAesKey: both wild encodings (base64 of 16 raw bytes / of 32 hex chars) land on the same key", () => {
  const raw = parseAesKey(KEY.toString("base64")); // image media.aes_key spelling
  const hex = parseAesKey(Buffer.from(KEY_HEX).toString("base64")); // image_item.aeskey → base64(hex)
  assert.ok(raw.equals(KEY) && hex.equals(KEY), "both encodings must recover the 16 raw bytes");
  assert.throws(() => parseAesKey(Buffer.alloc(15).toString("base64")), /16 raw bytes or a 32-char hex/);
  assert.throws(() => parseAesKey(""), /16 raw bytes or a 32-char hex/);
});

test("MD2 decryptAesEcb inverts send.mjs encryptAesEcb (one crypto family across the two legs)", () => {
  const plain = Buffer.from("入站媒体 plaintext \u{1F438}");
  assert.equal(decryptAesEcb(encrypted(plain), KEY).toString("utf-8"), plain.toString("utf-8"));
});

test("MD3 mimeFromFilename mirrors the official table; unknown → octet-stream", () => {
  assert.equal(mimeFromFilename("报告.pdf"), "application/pdf");
  assert.equal(mimeFromFilename("a.XLSX"), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
  assert.equal(mimeFromFilename("clip.mp4"), "video/mp4");
  assert.equal(mimeFromFilename("noext"), "application/octet-stream");
  assert.equal(mimeFromFilename(undefined), "application/octet-stream");
});

test("MD4 sniffImageMime: magic bytes, else the official image/* spelling", () => {
  assert.equal(sniffImageMime(PNG), "image/png");
  assert.equal(sniffImageMime(Buffer.from([0xff, 0xd8, 0xff, 0xe0])), "image/jpeg");
  assert.equal(sniffImageMime(Buffer.from("GIF89a……")), "image/gif");
  assert.equal(sniffImageMime(Buffer.from("GIF87a")), "image/gif");
  const webp = Buffer.concat([Buffer.from("RIFF"), Buffer.alloc(4), Buffer.from("WEBP")]);
  assert.equal(sniffImageMime(webp), "image/webp");
  assert.equal(sniffImageMime(Buffer.from("BM……")), "image/bmp");
  assert.equal(sniffImageMime(Buffer.from("not an image")), "image/*");
});

test("MD5 safeFileName: a peer-controlled name never steers the path; CJK survives; byte-capped", () => {
  assert.equal(safeFileName("../../etc/passwd", "file.bin"), "passwd");
  assert.equal(safeFileName("a/b\\c.txt", "file.bin"), "b_c.txt", "POSIX basename + separator flattening: no separator survives");
  assert.equal(safeFileName("..hidden", "file.bin"), "_hidden");
  assert.equal(safeFileName("   ", "file.bin"), "file.bin");
  assert.equal(safeFileName(undefined, "file.bin"), "file.bin");
  assert.equal(safeFileName("季度报告 最终版.pdf", "file.bin"), "季度报告 最终版.pdf");
  const capped = safeFileName("长".repeat(300), "file.bin");
  assert.ok(Buffer.byteLength(capped) <= 200, `byte cap: ${Buffer.byteLength(capped)}`);
});

test("MD6 mediaRootOf: <NEBFLOW_HOME>/media/weixin-inbound", () => {
  process.env.NEBFLOW_HOME = "/tmp/nb-media-home-test";
  assert.equal(mediaRootOf(), path.join("/tmp/nb-media-home-test", "media", "weixin-inbound"));
  delete process.env.NEBFLOW_HOME;
});

// ───────────────────────── the full stubbed CDN chain ─────────────────────────

test("MD7 image full chain: hex image_item.aeskey → download?encrypted_query_param fallback → sniffed png", async () => {
  const root = tmpRoot();
  const fetchImpl = stubFetch((url) => {
    assert.ok(url.startsWith("https://cdn.stub/download?encrypted_query_param="), `fallback download spelling: ${url}`);
    assert.ok(url.includes(encodeURIComponent("enc-param-1")), `param rides encoded: ${url}`);
    return { body: [encrypted(PNG)] };
  });
  const r = await processInboundMedia(imageMsg(), { accountId: "Acct@im.bot", mediaRoot: root, cdnBase: "https://cdn.stub/", fetchImpl });
  assert.ok(r, "a healthy chain must land");
  assert.equal(r.mediaKind, "image");
  assert.equal(r.mediaMime, "image/png");
  assert.equal(r.mediaName, "image.png");
  assert.ok(r.mediaPath.includes(`${path.join(root, "Acct-im-bot")}`), `account dir: ${r.mediaPath}`);
  assert.ok(path.basename(r.mediaPath).startsWith("m1-"), `messageId prefix: ${r.mediaPath}`);
  assert.deepEqual(fs.readFileSync(r.mediaPath), PNG, "the landed bytes are the PLAINTEXT");
  // at-least-once redelivery is idempotent (same path, overwritten cleanly)
  const r2 = await processInboundMedia(imageMsg(), { accountId: "Acct@im.bot", mediaRoot: root, cdnBase: "https://cdn.stub/", fetchImpl });
  assert.equal(r2.mediaPath, r.mediaPath);
});

test("MD8 file: media.aes_key in the 32-hex encoding, full_url wins over the fallback, file_name guesses the mime", async () => {
  const root = tmpRoot();
  const plain = Buffer.from("file payload");
  const aesKey32 = Buffer.from(KEY_HEX).toString("base64"); // base64(hex string)
  const msg = {
    from_user_id: "wx_alice",
    message_id: "m2",
    item_list: [{ type: 4, file_item: { media: { aes_key: aesKey32, full_url: "https://cdn.stub/full/x" }, file_name: "季度报告.pdf" } }],
  };
  const fetchImpl = stubFetch((url) => {
    assert.equal(url, "https://cdn.stub/full/x", "the server's full_url must win");
    return { body: [encrypted(plain, Buffer.from(Buffer.from(aesKey32, "base64").toString("ascii"), "hex"))] };
  });
  const r = await processInboundMedia(msg, { accountId: "a-im-bot", mediaRoot: root, fetchImpl });
  assert.ok(r);
  assert.equal(r.mediaKind, "file");
  assert.equal(r.mediaName, "季度报告.pdf");
  assert.equal(r.mediaMime, "application/pdf");
  assert.deepEqual(fs.readFileSync(r.mediaPath), plain);
});

test("MD9 video: base64(raw16) key, no wire name → plain mp4", async () => {
  const root = tmpRoot();
  const plain = Buffer.from("0123456789abcdef mp4 boxes");
  const msg = {
    from_user_id: "wx_alice",
    message_id: "m3",
    item_list: [{ type: 5, video_item: { media: { encrypt_query_param: "vp", aes_key: KEY.toString("base64") } } }],
  };
  const fetchImpl = stubFetch(() => ({ body: [encrypted(plain)] }));
  const r = await processInboundMedia(msg, { accountId: "a-im-bot", mediaRoot: root, fetchImpl });
  assert.ok(r);
  assert.equal(r.mediaKind, "video");
  assert.equal(r.mediaName, "video.mp4");
  assert.equal(r.mediaMime, "video/mp4");
  assert.deepEqual(fs.readFileSync(r.mediaPath), plain);
});

test("MD10 image without the hex spelling falls back to media.aes_key", async () => {
  const root = tmpRoot();
  const msg = {
    from_user_id: "wx_alice",
    message_id: "m4",
    item_list: [{ type: 2, image_item: { media: { encrypt_query_param: "p", aes_key: KEY.toString("base64") } } }],
  };
  const fetchImpl = stubFetch(() => ({ body: [encrypted(PNG)] }));
  const r = await processInboundMedia(msg, { accountId: "a-im-bot", mediaRoot: root, fetchImpl });
  assert.ok(r && r.mediaMime === "image/png", `fallback key path: ${r?.mediaMime}`);
});

// ───────────────────────── degrade + budget + bound ─────────────────────────

test("MD11 network failure degrades to null after exactly the ≤2 budget (never throws, cursor not held)", async () => {
  const root = tmpRoot();
  const fetchImpl = stubFetch(() => null); // every dial: ENOTFOUND-style reject
  const r = await processInboundMedia(imageMsg(), { accountId: "a-im-bot", mediaRoot: root, fetchImpl });
  assert.equal(r, null, "degrade — the placeholder label will ride instead");
  assert.equal(fetchImpl.calls.length, 2, `network budget (env-pinned 2 attempts), got ${fetchImpl.calls.length}`);
});

test("MD12 a CDN client refusal (404) is deterministic: one dial, no retry", async () => {
  const root = tmpRoot();
  const fetchImpl = stubFetch(() => ({ status: 404, text: "gone" }));
  const r = await processInboundMedia(imageMsg(), { accountId: "a-im-bot", mediaRoot: root, fetchImpl });
  assert.equal(r, null);
  assert.equal(fetchImpl.calls.length, 1, "protocol refusals never retry");
});

test("MD13 oversize by content-length is refused up-front (the body is never read)", async () => {
  const root = tmpRoot();
  const fetchImpl = stubFetch(() => ({ contentLength: 100 * 1024 * 1024 + 1, body: [] }));
  const r = await processInboundMedia(imageMsg(), { accountId: "a-im-bot", mediaRoot: root, fetchImpl });
  assert.equal(r, null);
  assert.equal(fetchImpl.calls.length, 1);
});

test("MD14 oversize mid-stream (a lying server) is cut off", async () => {
  const root = tmpRoot();
  const fake = { length: 100 * 1024 * 1024 + 1 }; // chunk-like; never materialized
  const fetchImpl = stubFetch(() => ({ body: [fake] }));
  const r = await processInboundMedia(imageMsg(), { accountId: "a-im-bot", mediaRoot: root, fetchImpl });
  assert.equal(r, null);
});

test("MD15 voice stays out of scope; a message without in-scope media never dials", async () => {
  const root = tmpRoot();
  const fetchImpl = stubFetch(() => ({ body: [Buffer.from("x")] }));
  const voice = {
    from_user_id: "wx_a",
    item_list: [{ type: 3, voice_item: { media: { encrypt_query_param: "p", aes_key: KEY.toString("base64") } } }],
  };
  assert.equal(await processInboundMedia(voice, { accountId: "a", mediaRoot: root, fetchImpl }), null);
  assert.equal(await processInboundMedia({ from_user_id: "w", item_list: [] }, { accountId: "a", mediaRoot: root, fetchImpl }), null);
  assert.equal(await processInboundMedia(imageMsg(), { accountId: "", mediaRoot: root, fetchImpl }), null, "no account ⇒ no landing");
  assert.equal(fetchImpl.calls.length, 0);
});

test("MD16 the step is TOTAL: even a poisoned mediaRoot degrades to null", async () => {
  const fileAsRoot = path.join(tmpRoot(), "not-a-dir");
  fs.writeFileSync(fileAsRoot, "x");
  const fetchImpl = stubFetch(() => ({ body: [encrypted(PNG)] }));
  const r = await processInboundMedia(imageMsg(), { accountId: "a-im-bot", mediaRoot: fileAsRoot, fetchImpl });
  assert.equal(r, null, "mkdir/write failure must never escape (cursor discipline)");
});

// ───────────────── deliver leg wiring (placeholder + cursor discipline) ─────────────────

/** Patch globalThis.fetch to route CDN vs ingress; returns {restore, bodies}. */
function patchFetch(routes) {
  const bodies = [];
  const orig = globalThis.fetch;
  globalThis.fetch = async (url, opts = {}) => {
    const u = String(url);
    if (u.endsWith("/ingress")) {
      bodies.push(JSON.parse(opts.body));
      return { ok: true, status: 200, text: async () => '{"verdict":"injected"}' };
    }
    const r = routes(u);
    if (!r) throw Object.assign(new Error("getaddrinfo ENOTFOUND cdn.stub"), { cause: { code: "ENOTFOUND" } });
    return {
      ok: true,
      status: 200,
      headers: { get: () => null },
      text: async () => "",
      body: (async function* () {
        for (const c of r) yield c;
      })(),
    };
  };
  return { bodies, restore: () => (globalThis.fetch = orig) };
}

test("MD17 deliverToIngress: a LANDED image rides the four media fields with the label still in text", async () => {
  const p = patchFetch(() => [encrypted(PNG)]);
  try {
    const r = await deliverToIngress(imageMsg(), "a-im-bot");
    assert.equal(r.delivered, true, "2xx verdict ⇒ delivered (cursor advances)");
    const body = p.bodies[0];
    assert.equal(body.text, "[图片]", "the placeholder label stays in text either way");
    assert.equal(body.messageId, "m1");
    assert.ok(typeof body.mediaPath === "string" && path.isAbsolute(body.mediaPath), `mediaPath present+absolute: ${body.mediaPath}`);
    assert.ok(body.mediaPath.includes(path.join("media", "weixin-inbound", "a-im-bot")), `under the media root: ${body.mediaPath}`);
    assert.equal(body.mediaKind, "image");
    assert.equal(body.mediaName, "image.png");
    assert.equal(body.mediaMime, "image/png");
  } finally {
    p.restore();
  }
});

test("MD18 deliverToIngress: a FAILED media leg degrades to the bare placeholder — delivered:true, no media fields, cursor NOT held", async () => {
  const p = patchFetch(() => null); // CDN blackhole
  try {
    const r = await deliverToIngress(imageMsg(), "a-im-bot");
    assert.equal(r.delivered, true, "the degrade still delivers — the cursor is never held for a media leg");
    const body = p.bodies[0];
    assert.equal(body.text, "[图片]");
    assert.equal(body.mediaPath, undefined);
    assert.equal(body.mediaKind, undefined);
    assert.equal(body.mediaName, undefined);
    assert.equal(body.mediaMime, undefined);
  } finally {
    p.restore();
  }
});

test("MD19 messageIdOf fallback keeps the naming deterministic for id-less messages", () => {
  assert.equal(messageIdOf({ message_id: " m9 " }), "m9");
  assert.equal(messageIdOf({ item_list: [{ msg_id: "item-1" }] }), "item-1");
  assert.equal(messageIdOf({}), null);
});

test("MD20 export surface: the media batch adds to (never removes from) the module face", async () => {
  const msgloop = await import("./msgloop.mjs");
  for (const name of ["processInboundMedia", "mediaRootOf", "parseAesKey", "decryptAesEcb", "mimeFromFilename", "sniffImageMime", "safeFileName"]) {
    assert.equal(typeof msgloop[name], "function", `export ${name} must be a function`);
  }
});

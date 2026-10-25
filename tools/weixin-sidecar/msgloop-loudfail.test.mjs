#!/usr/bin/env node
/**
 * msgloop 入站图片必达批（2026-10-24）— the LOUD-failure audit leg.
 *
 * Pins the three incident-driven guarantees of the inbound media chain (zero
 * iLink traffic, zero real network — every dial is stubbed):
 *
 *   ① a media item that EXISTS but did NOT land reports its reason through
 *     the `onFailure` seam and rides the wire as `mediaError` (the engine
 *     WARNs + receipts the author — never again a side-car-log-only loss);
 *   ② a message carrying MORE than one media item counts the undelivered
 *     rest as `mediaExtra` (v1 delivers the first item only — the census
 *     makes the rest VISIBLE instead of silently dropping them);
 *   ③ a client-side long-poll timeout is MARKED (`silentTimeout`) so the
 *     live leg can WARN on a streak — a stalling inbound session can no
 *     longer present as a zero-trace blackout (cursor static, heartbeat
 *     fresh, no logs — the 2026-10-24 incident shape).
 *
 * Backward-compat pins: `toInboundBody` without a report is byte-identical
 * to the pre-batch shape; `processInboundMedia` return values are unchanged
 * (null ⇒ placeholder degrade, cursor never held); the onFailure seam is
 * optional (callers without it behave exactly as before).
 *
 * Run: node --test tools/weixin-sidecar/msgloop-loudfail.test.mjs
 */
import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

process.env.WEIXIN_SIDECAR_MEDIA_ATTEMPTS = "2";
process.env.WEIXIN_SIDECAR_MEDIA_BACKOFF_MS = "10";
process.env.WEIXIN_MSGLOOP_INGRESS_URL = "http://127.0.0.1:9/ingress"; // patched per test
const TOKEN_FILE = path.join(os.tmpdir(), `nb-loudfail-token-${process.pid}`);
fs.writeFileSync(TOKEN_FILE, "test-token-shape-only");
process.env.WEIXIN_SEND_TOKEN_FILE = TOKEN_FILE;

const { analyzeInboundMedia, processInboundMedia, toInboundBody, deliverToIngress, getUpdates } =
  await import("./msgloop.mjs");
const { encryptAesEcb } = await import("./send.mjs");

const KEY_HEX = "00112233445566778899aabbccddeeff";
const KEY = Buffer.from(KEY_HEX, "hex");
const PNG = Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), Buffer.from("fakepng")]);

const imageItem = (extra = {}) => ({
  type: 2,
  image_item: { aeskey: KEY_HEX, media: { encrypt_query_param: "enc-param-1" }, ...extra },
});
const imageMsg = (items = [imageItem()]) => ({ from_user_id: "wx_alice", message_id: "m1", item_list: items });

/** Minimal CDN fetch stub (same shape as msgloop-media.test.mjs). */
function stubFetch(routes) {
  const fn = async (url) => {
    const r = routes(String(url));
    if (!r) throw Object.assign(new Error("getaddrinfo ENOTFOUND cdn.stub"), { cause: { code: "ENOTFOUND" } });
    if (r.reject) throw r.reject;
    return {
      ok: (r.status ?? 200) >= 200 && (r.status ?? 200) < 300,
      status: r.status ?? 200,
      statusText: "OK",
      headers: { get: () => null },
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
  return fn;
}

const tmpRoot = () => fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), "nb-loudfail-")));

// ───────────────────────── ① the media-item census ─────────────────────────

test("LA1 analyzeInboundMedia counts in-scope items only (voice/text excluded)", () => {
  assert.deepEqual(analyzeInboundMedia({ item_list: [] }), { count: 0, kind: null });
  assert.deepEqual(analyzeInboundMedia({ item_list: [{ type: 1, text_item: { text: "hi" } }] }), { count: 0, kind: null });
  assert.deepEqual(
    analyzeInboundMedia({ item_list: [{ type: 3, voice_item: { text: "转写" } }] }),
    { count: 0, kind: null },
    "voice stays out of scope (its transcript rides as text)",
  );
  assert.deepEqual(analyzeInboundMedia(imageMsg()), { count: 1, kind: "image" });
  assert.deepEqual(
    analyzeInboundMedia(imageMsg([imageItem(), { type: 4, file_item: {} }, { type: 5, video_item: {} }])),
    { count: 3, kind: "image" },
    "multi-item census: count + the FIRST item's kind",
  );
});

// ─────────────── ② onFailure: every not-landed shape reports WHY ───────────────

test("LA2 a network failure fires onFailure with a readable reason (and still degrades to null)", async () => {
  const failures = [];
  const fetchImpl = stubFetch(() => null); // every dial: ENOTFOUND
  const r = await processInboundMedia(imageMsg(), {
    accountId: "a-im-bot",
    mediaRoot: tmpRoot(),
    fetchImpl,
    onFailure: (err) => failures.push(String(err?.message)),
  });
  assert.equal(r, null, "the degrade discipline is byte-identical (null ⇒ placeholder)");
  assert.equal(failures.length, 1, "the exhausted retry budget reports ONCE (withMediaRetry throws the last error once)");
  assert.ok(failures[0].includes("image msg=m1"), "the reason names the kind + message id");
});

test("LA3 missing CDN reference / missing aes_key / missing account each report their own reason", async () => {
  const failures = [];
  const onFailure = (err) => failures.push(String(err?.message));
  const noRef = imageMsg([imageItem({ media: {} })]); // aeskey present, no CDN ref
  const noKey = imageMsg([imageItem({ aeskey: "", media: { encrypt_query_param: "p" } })]);
  assert.equal(await processInboundMedia(noRef, { accountId: "a", mediaRoot: tmpRoot(), onFailure }), null);
  assert.equal(await processInboundMedia(noKey, { accountId: "a", mediaRoot: tmpRoot(), onFailure }), null);
  assert.equal(await processInboundMedia(imageMsg(), { accountId: "", mediaRoot: tmpRoot(), onFailure }), null, "no account");
  assert.deepEqual(
    failures.map((m) => m.split(":").slice(1).join(":").trim()),
    ["carries no CDN reference", "carries no aes_key", "no account resolved"],
    "each deterministic refusal reports its exact shape",
  );
});

test("LA4 a message with NO media item never fires onFailure (nothing to report)", async () => {
  let fired = 0;
  const r = await processInboundMedia(
    { from_user_id: "w", item_list: [{ type: 1, text_item: { text: "hi" } }] },
    { accountId: "a", mediaRoot: tmpRoot(), onFailure: () => { fired += 1; } },
  );
  assert.equal(r, null);
  assert.equal(fired, 0);
});

test("LA5 a HEALTHY landing lands (regression pin) and never fires onFailure", async () => {
  const failures = [];
  const fetchImpl = stubFetch(() => ({ body: [encryptAesEcb(PNG, KEY)] }));
  const r = await processInboundMedia(imageMsg(), {
    accountId: "a-im-bot",
    mediaRoot: tmpRoot(),
    fetchImpl,
    onFailure: (err) => failures.push(String(err?.message)),
  });
  assert.ok(r && r.mediaKind === "image" && r.mediaMime === "image/png", "the happy path still lands");
  assert.deepEqual(failures, [], "success is not a failure");
});

// ───────────────── ③ the wire: mediaError / mediaExtra ride the body ─────────────────

test("LA6 toInboundBody: absent report ⇒ byte-identical pre-batch shape; report ⇒ the two audit fields", () => {
  const media = { mediaPath: "/tmp/x.png", mediaKind: "image", mediaName: "image.png", mediaMime: "image/png" };
  const plain = toInboundBody(imageMsg(), media);
  assert.deepEqual(Object.keys(plain).sort(), ["mediaKind", "mediaMime", "mediaName", "mediaPath", "messageId", "senderId", "text"], "no audit keys leak without a report");
  const reported = toInboundBody(imageMsg(), media, { mediaExtra: 2 });
  assert.equal(reported.mediaExtra, 2);
  assert.equal(reported.mediaError, undefined, "mediaExtra alone does not invent a mediaError");
  const failed = toInboundBody(imageMsg(), null, { mediaError: "image msg=m1: timeout: boom" });
  assert.equal(failed.mediaError, "image msg=m1: timeout: boom");
  assert.equal(failed.mediaPath, undefined, "a failure never carries a path");
  assert.equal(failed.text, "[图片]", "the placeholder label still rides (the engine degrades visibly)");
});

test("LA7 deliverToIngress: a failed download puts mediaError on the wire; multi-item puts mediaExtra", async () => {
  const bodies = [];
  const patchFetch = async (url, opts) => {
    if (String(url).includes("/ingress")) {
      bodies.push(JSON.parse(String(opts.body)));
      return { ok: true, status: 200, text: async () => JSON.stringify({ verdict: "injected", sessionId: "s1" }) };
    }
    throw Object.assign(new Error("getaddrinfo ENOTFOUND cdn.stub"), { cause: { code: "ENOTFOUND" } });
  };
  const origFetch = globalThis.fetch;
  globalThis.fetch = patchFetch;
  try {
    const r1 = await deliverToIngress(imageMsg(), "a-im-bot");
    assert.equal(r1.delivered, true);
    assert.ok(bodies[0].mediaError?.includes("image msg=m1"), `mediaError rides the wire, got ${JSON.stringify(bodies[0])}`);
    assert.equal(bodies[0].mediaPath, undefined);

    const r2 = await deliverToIngress(
      imageMsg([imageItem(), imageItem(), imageItem()]),
      "a-im-bot",
    );
    assert.equal(r2.delivered, true);
    assert.equal(bodies[1].mediaExtra, 2, "two further items counted");
    assert.ok(bodies[1].mediaError?.includes("image msg=m1"), "first item failed too ⇒ mediaError AND mediaExtra");
  } finally {
    globalThis.fetch = origFetch;
  }
});

test("LA8 deliverToIngress: a healthy landing puts the four media fields and NO audit noise", async () => {
  const bodies = [];
  const patchFetch = async (url, opts) => {
    if (String(url).includes("/ingress")) {
      bodies.push(JSON.parse(String(opts.body)));
      return { ok: true, status: 200, text: async () => JSON.stringify({ verdict: "injected", sessionId: "s1" }) };
    }
    return {
      ok: true,
      status: 200,
      headers: { get: () => null },
      body: (async function* () {
        yield encryptAesEcb(PNG, KEY);
      })(),
      text: async () => "",
    };
  };
  const origFetch = globalThis.fetch;
  globalThis.fetch = patchFetch;
  try {
    const r = await deliverToIngress(imageMsg(), "a-im-bot");
    assert.equal(r.delivered, true);
    assert.ok(bodies[0].mediaPath?.includes("m1-"), "the path landed");
    assert.equal(bodies[0].mediaError, undefined);
    assert.equal(bodies[0].mediaExtra, undefined, "single-item message ⇒ no extra count");
  } finally {
    globalThis.fetch = origFetch;
  }
});

// ───────────── ④ the long-poll timeout is MARKED (zero-trace stall detector) ─────────────

test("LA9 getUpdates marks a client-side timeout (silentTimeout) instead of faking a plain empty poll", async () => {
  const origFetch = globalThis.fetch;
  globalThis.fetch = async () => {
    throw Object.assign(new Error("The operation timed out"), { name: "TimeoutError" });
  };
  try {
    const r = await getUpdates("https://ilink.stub", "token-shape-only", "", 50);
    assert.equal(r.ret, 0);
    assert.deepEqual(r.msgs, []);
    assert.equal(r.silentTimeout, true, "the timeout is distinguishable from the server's empty poll");
  } finally {
    globalThis.fetch = origFetch;
  }
});

#!/usr/bin/env node
/**
 * Ingress backoff (2026-10-25 tcp_refused-storm batch).
 *
 * Incident: the engine came up on the OLD default port 8080 (manual `sbt run`
 * while the side-car targets 127.0.0.1:6138) — every ingress POST died with
 * `tcp_refused — fetch failed` and the loop re-polled at full cadence. With
 * the cursor held, getUpdates returns instantly (messages still buffered
 * upstream) and the deliver leg fails instantly ⇒ multiple tcp_refused lines
 * PER SECOND for the whole engine-down window (the 13:29 log shape).
 *
 * Contract pinned here:
 *   - schedule: 1s → 2s → 4s → … capped at 60s; additive upward jitter only
 *     (realized wait ≥ core step, so the cap floor is never undercut)
 *   - evidence: during N consecutive transport-class failures the retry
 *     frequency stays ≤ 1/capMs (once the core step reaches the cap, every
 *     realized wait is ≥ cap)
 *   - classifier: transport class = fetch threw (status 0) or 5xx; a speaking
 *     engine (4xx, 200 + hold verdict) does NOT back off — data-driven
 *     recovery is immediate (#692 backoff philosophy, no death sentence)
 *   - the incident face end-to-end: ECONNREFUSED on the ingress POST ⇒
 *     status 0 ⇒ transport class ⇒ the loop's backoff path engages
 *
 * Pure loopback stubs: global.fetch replaced, token file in a throwaway dir —
 * zero iLink traffic, zero real secrets.
 *
 * Run: node --test tools/weixin-sidecar/msgloop-backoff.test.mjs
 */
import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const TMP = fs.mkdtempSync(path.join(os.tmpdir(), "msgloop-backoff-"));
process.env.WEIXIN_SEND_TOKEN_FILE = path.join(TMP, "ingress-token");
process.env.WEIXIN_MSGLOOP_INGRESS_URL = "http://127.0.0.1:6138/api/social/channels/weixin-ilink/ingress";
process.env.WEIXIN_MSGLOOP_STATE_DIR = path.join(TMP, "state");
fs.writeFileSync(process.env.WEIXIN_SEND_TOKEN_FILE, "test-ingress-token-value\n");

const { deliverToIngress, ingressBackoffMs, isTransportFailure, classifyFetchError } = await import("./msgloop.mjs");

const MSG = { from_user_id: "wx_test@im.wechat", item_list: [{ type: 1, text_item: { text: "hi" } }] };

const BASE = 1_000;
const CAP = 60_000;
const coreOf = (n) => Math.min(CAP, BASE * 2 ** (n - 1));

test("BO1 schedule shape: doubles from 1s and caps at 60s (no-jitter read)", () => {
  const f = (n) => ingressBackoffMs(n, { rand: () => 0 });
  assert.equal(f(1), 1_000);
  assert.equal(f(2), 2_000);
  assert.equal(f(3), 4_000);
  assert.equal(f(4), 8_000);
  assert.equal(f(6), 32_000);
  assert.equal(f(7), CAP, "1000·2⁶ = 64000 → capped at 60s");
  assert.equal(f(20), CAP, "deep outage stays at the cap");
  assert.equal(f(0), 1_000, "failures < 1 clamps to the first step");
});

test("BO2 jitter is additive upward only — realized wait never below the core step", () => {
  for (let n = 1; n <= 12; n++) {
    const lo = ingressBackoffMs(n, { rand: () => 0 });
    const mid = ingressBackoffMs(n, { rand: () => 0.5 });
    const hi = ingressBackoffMs(n, { rand: () => 0.999 });
    assert.ok(mid >= lo, `n=${n}: jitter must never shorten the wait (${mid} < ${lo})`);
    assert.ok(hi >= mid, `n=${n}: jitter must be monotone in rand`);
    assert.ok(hi <= coreOf(n) * (1 + 0.25) + 1, `n=${n}: jitter stays within +25% (${hi})`);
  }
  // cap floor preserved under jitter: a capped round never dips below 60s
  assert.ok(ingressBackoffMs(7, { rand: () => 0.999 }) >= CAP);
  assert.ok(ingressBackoffMs(50, { rand: () => 0.001 }) >= CAP);
});

test("BO3 evidence pin: N consecutive failures ⇒ max retry frequency ≤ 1/cap", () => {
  // Walk a sustained outage: every realized wait on the schedule must be ≥ its
  // core step, and once the core step reaches the cap EVERY wait (any rand) is
  // ≥ cap — so past step 7 the loop re-attempts at most once per 60s.
  for (let n = 1; n <= 40; n++) {
    for (let s = 0; s < 25; s++) {
      const wait = ingressBackoffMs(n, { rand: Math.random });
      assert.ok(wait >= coreOf(n), `n=${n} sample=${s}: ${wait} < core ${coreOf(n)}`);
      if (n >= 7) assert.ok(wait >= CAP, `n=${n} sample=${s}: ${wait} < cap ${CAP}`);
    }
  }
});

test("BO4 classifier: transport class = unreachable (0) or dying (5xx); speaking engine never backs off", () => {
  for (const s of [0, 500, 502, 503, 599]) assert.equal(isTransportFailure(s), true, `status ${s}`);
  for (const s of [200, 204, 301, 400, 403, 404, 409, 429]) assert.equal(isTransportFailure(s), false, `status ${s}`);
});

test("BO5 incident face end-to-end: ECONNREFUSED on ingress ⇒ status 0 ⇒ transport class", async () => {
  const refused = Object.assign(new Error("fetch failed"), { cause: { code: "ECONNREFUSED" } });
  const realFetch = globalThis.fetch;
  globalThis.fetch = async () => {
    throw refused;
  };
  try {
    assert.equal(classifyFetchError(refused), "tcp_refused", "the 13:29 log line's classification");
    const r = await deliverToIngress(MSG, "test-acct");
    assert.equal(r.delivered, false, "cursor held (at-least-once)");
    assert.equal(r.status, 0, "fetch-threw is status 0");
    assert.equal(isTransportFailure(r.status), true, "the loop's backoff path must engage for exactly this verdict");
  } finally {
    globalThis.fetch = realFetch;
  }
});

test("BO6 5xx shape: engine reached but failing ⇒ transport class (backoff engages)", async () => {
  const realFetch = globalThis.fetch;
  globalThis.fetch = async () => ({ ok: false, status: 503, text: async () => "overloaded" });
  try {
    const r = await deliverToIngress(MSG, "test-acct");
    assert.equal(r.delivered, false);
    assert.equal(r.status, 503);
    assert.equal(isTransportFailure(r.status), true);
  } finally {
    globalThis.fetch = realFetch;
  }
});

test("BO7 speaking engine: 200+hold verdict and 403 do NOT back off (poll cadence unchanged)", async () => {
  const realFetch = globalThis.fetch;
  const respondJson = (obj, status = 200) => async () => ({
    ok: status >= 200 && status < 300,
    status,
    text: async () => JSON.stringify(obj),
  });
  try {
    globalThis.fetch = respondJson({ verdict: "dropped", reason: "adapter-not-started" });
    let r = await deliverToIngress(MSG, "test-acct");
    assert.equal(r.delivered, false, "cursor held (VD2, unchanged)");
    assert.equal(isTransportFailure(r.status), false, "engine answered 200 — reachable ⇒ no backoff");

    globalThis.fetch = respondJson({ error: "unauthorized" }, 403);
    r = await deliverToIngress(MSG, "test-acct");
    assert.equal(r.status, 403);
    assert.equal(isTransportFailure(r.status), false, "403 has its own single rotation, not the transport ladder");
  } finally {
    globalThis.fetch = realFetch;
  }
});

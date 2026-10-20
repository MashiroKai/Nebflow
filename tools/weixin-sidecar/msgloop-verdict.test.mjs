#!/usr/bin/env node
/**
 * Ingress verdict → cursor discipline (dangling-secret incident batch, 2026-10-20).
 *
 * Pins the fix for the silent-loss face found in the incident window: a 200
 * whose verdict says the engine could NOT accept the message used to advance
 * the cursor (any 2xx = delivered) — the message was then lost upstream while
 * the target instance's weixin bridge was unregistered (disabled channel /
 * invalid config / loop pointed at the wrong instance).
 *
 * Contract pinned here:
 *   - 200 dropped(adapter-not-started)  ⇒ NOT delivered (cursor held)
 *   - 200 dropped(inject-failed)        ⇒ NOT delivered (cursor held)
 *   - 200 dropped(not-on-allowlist)     ⇒ delivered (final policy drop — the
 *     wx_mallory precedent: holding would wedge the loop on a poison message)
 *   - 200 dropped(no-session-bound)     ⇒ delivered (final policy drop)
 *   - 200 + injected                    ⇒ delivered (unchanged)
 *   - 200 non-JSON body                 ⇒ delivered (verdict unseen — legacy)
 *   - 403 twice (token unchanged)       ⇒ NOT delivered (unchanged)
 *
 * Pure loopback stubs: global.fetch is replaced, the token file lives in a
 * throwaway dir, the state dir is redirected — zero iLink traffic, zero real
 * secrets, zero writes outside the test's temp dir.
 *
 * Run: node --test tools/weixin-sidecar/msgloop-verdict.test.mjs
 */
import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const TMP = fs.mkdtempSync(path.join(os.tmpdir(), "msgloop-verdict-"));
process.env.WEIXIN_SEND_TOKEN_FILE = path.join(TMP, "ingress-token");
process.env.WEIXIN_MSGLOOP_INGRESS_URL = "http://127.0.0.1:9/ingress";
process.env.WEIXIN_MSGLOOP_STATE_DIR = path.join(TMP, "state");
fs.writeFileSync(process.env.WEIXIN_SEND_TOKEN_FILE, "test-ingress-token-value\n");

const { deliverToIngress } = await import("./msgloop.mjs");

const MSG = { from_user_id: "wx_test@im.wechat", item_list: [{ type: 1, text_item: { text: "hi" } }] };

/** Stub fetch for one call; returns what the deliver leg saw. */
function stubFetch(respond) {
  const calls = [];
  globalThis.fetch = async (url, opts) => {
    calls.push({ url, opts });
    return respond(calls.length);
  };
  return calls;
}

const respondJson = (obj, status = 200) => () => ({
  ok: status >= 200 && status < 300,
  status,
  text: async () => JSON.stringify(obj),
});
const respondRaw = (raw, status = 200) => () => ({ ok: status >= 200 && status < 300, status, text: async () => raw });

test("VD1 200 + injected ⇒ delivered (status quo kept)", async () => {
  stubFetch(respondJson({ verdict: "injected", sessionId: "s-1" }));
  const r = await deliverToIngress(MSG, "test-acct");
  assert.equal(r.delivered, true);
  assert.equal(r.status, 200);
});

test("VD2 200 + dropped(adapter-not-started) ⇒ NOT delivered — the incident loss face", async () => {
  const calls = stubFetch(respondJson({ verdict: "dropped", reason: "adapter-not-started" }));
  const r = await deliverToIngress(MSG, "test-acct");
  assert.equal(r.delivered, false, "a 200 dropped(adapter-not-started) must hold the cursor");
  assert.equal(r.verdict?.reason, "adapter-not-started");
  assert.equal(calls.length, 1, "no retry storm — one POST, hold, redeliver on a later poll");
});

test("VD3 200 + dropped(inject-failed) ⇒ NOT delivered (transient engine failure = 5xx class)", async () => {
  stubFetch(respondJson({ verdict: "dropped", reason: "inject-failed" }));
  const r = await deliverToIngress(MSG, "test-acct");
  assert.equal(r.delivered, false);
});

test("VD4 200 + dropped(not-on-allowlist) ⇒ delivered (final policy drop — no wedge)", async () => {
  stubFetch(respondJson({ verdict: "dropped", reason: "not-on-allowlist" }));
  const r = await deliverToIngress(MSG, "test-acct");
  assert.equal(r.delivered, true, "policy drops advance exactly as the wx_mallory precedent");
});

test("VD5 200 + dropped(no-session-bound) ⇒ delivered (final policy drop)", async () => {
  stubFetch(respondJson({ verdict: "dropped", reason: "no-session-bound" }));
  const r = await deliverToIngress(MSG, "test-acct");
  assert.equal(r.delivered, true);
});

test("VD6 200 non-JSON body ⇒ delivered (verdict unseen — legacy behaviour)", async () => {
  stubFetch(respondRaw("<html>gateway hiccup</html>"));
  const r = await deliverToIngress(MSG, "test-acct");
  assert.equal(r.delivered, true);
});

test("VD7 403 twice (token file unchanged) ⇒ NOT delivered (existing 403 ladder)", async () => {
  const calls = stubFetch(respondJson({ error: "unauthorized" }, 403));
  const r = await deliverToIngress(MSG, "test-acct");
  assert.equal(r.delivered, false);
  assert.equal(calls.length, 2, "403 re-reads the token once, then gives up");
});

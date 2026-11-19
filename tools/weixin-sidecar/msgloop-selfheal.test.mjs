#!/usr/bin/env node
/**
 * msgloop self-heal account selection/switch verdict (msgloop self-heal batch,
 * 2026-10-08).
 *
 * Pins the keep/switch discipline of msgloop.mjs: a QR re-bind revokes the old
 * bot token server-side (the -14 loop) and lands a NEW accounts.json index
 * entry, so the loop re-reads the index (once on -14; every pause wake ≤60s;
 * throttled to ≥5min while polling) and must adopt the newest VALID account
 * in-process — newest-first walk over index entries whose account file loads
 * with a non-empty token; switch iff that differs from the current account;
 * index/file trouble degrades to keep + reason (never throws, never crashes
 * the loop). Pure-function spec: the module is IMPORTED, never run — zero
 * iLink traffic, zero filesystem reads, zero token values (length/shape only).
 *
 * Run: node --test tools/weixin-sidecar/msgloop-selfheal.test.mjs
 */
import test from "node:test";
import assert from "node:assert/strict";
import * as msgloop from "./msgloop.mjs";
import { decideAccountSwitch, latestValidAccountId, normalizeAccountId } from "./msgloop.mjs";

// fake bot token: shape only (72B like a real one), never a value
const TOK = "t".repeat(72);
const ok = (extra = {}) => ({ token: TOK, ...extra });
const files = (map) => (id) => (id in map ? map[id] : null); // loadAccount stand-in

test("SH1 -14 arm: a newer valid indexed account exists ⇒ switch to it", () => {
  const d = decideAccountSwitch(
    "old-im-bot",
    ["old-im-bot", "new-im-bot"],
    files({ "old-im-bot": ok({ userId: "u1" }), "new-im-bot": ok({ userId: "u1" }) }),
  );
  assert.deepEqual(d, { action: "switch", accountId: "new-im-bot", reason: "newer valid indexed account" });
});

test("SH2 -14 arm: current IS the newest valid account ⇒ keep (loop holds its 1h pause)", () => {
  const d = decideAccountSwitch(
    "cur-im-bot",
    ["old-im-bot", "cur-im-bot"],
    files({ "old-im-bot": ok(), "cur-im-bot": ok() }),
  );
  assert.deepEqual(d, {
    action: "keep",
    accountId: "cur-im-bot",
    reason: "current account is the newest valid indexed one",
  });
});

test("SH3 periodic arm: the same core flips keep→switch as the index grows between wakes", () => {
  const loader = files({ "a-im-bot": ok(), "b-im-bot": ok() });
  const before = decideAccountSwitch("a-im-bot", ["a-im-bot"], loader);
  assert.equal(before.action, "keep");
  const after = decideAccountSwitch("a-im-bot", ["a-im-bot", "b-im-bot"], loader); // re-bind landed
  assert.deepEqual(after, { action: "switch", accountId: "b-im-bot", reason: "newer valid indexed account" });
});

test("SH4 empty index ⇒ keep the current account, no switch, no throw", () => {
  const d = decideAccountSwitch("cur-im-bot", [], files({}));
  assert.deepEqual(d, { action: "keep", accountId: "cur-im-bot", reason: "account index is empty" });
});

test("SH5 newest account file corrupt ⇒ fall back to the newest still-valid entry", () => {
  const loader = files({ "a-im-bot": ok(), "b-im-bot": null }); // b unreadable (loader ⇒ null)
  const fromOlder = decideAccountSwitch("a-im-bot", ["a-im-bot", "b-im-bot"], loader);
  assert.deepEqual(fromOlder, {
    action: "keep",
    accountId: "a-im-bot",
    reason: "current account is the newest valid indexed one",
  });
  const fromBroken = decideAccountSwitch("b-im-bot", ["a-im-bot", "b-im-bot"], loader);
  assert.deepEqual(fromBroken, {
    action: "switch",
    accountId: "a-im-bot",
    reason: "current account file has no usable token",
  });
});

test("SH6 every indexed file corrupt/tokenless ⇒ keep + warning reason, never crash", () => {
  const loader = files({ "a-im-bot": null, "b-im-bot": { userId: "u1" } }); // unreadable / no token
  const d = decideAccountSwitch("b-im-bot", ["a-im-bot", "b-im-bot"], loader);
  assert.deepEqual(d, {
    action: "keep",
    accountId: "b-im-bot",
    reason: "no indexed account file carries a usable token",
  });
});

test("SH7 a blank/whitespace token does not count as usable", () => {
  const loader = files({ "a-im-bot": { token: "   " }, "b-im-bot": ok() });
  assert.equal(latestValidAccountId(["a-im-bot", "b-im-bot"], loader), "b-im-bot");
  assert.deepEqual(decideAccountSwitch("a-im-bot", ["a-im-bot", "b-im-bot"], loader), {
    action: "switch",
    accountId: "b-im-bot",
    reason: "newer valid indexed account",
  });
});

test("SH8 latestValidAccountId walks newest→oldest, skips junk, null when none valid", () => {
  const loader = files({ "a-im-bot": ok(), "b-im-bot": null, "c-im-bot": ok() });
  assert.equal(latestValidAccountId(["a-im-bot", "b-im-bot", "c-im-bot"], loader), "c-im-bot");
  assert.equal(latestValidAccountId(["b-im-bot"], loader), null);
  // blank and non-string index entries are skipped, never dereferenced
  assert.equal(latestValidAccountId(["a-im-bot", "", "   ", 42, null], loader), "a-im-bot");
  assert.equal(latestValidAccountId([], loader), null);
});

test("SH9 current account no longer in the index ⇒ switch to the newest valid one", () => {
  const d = decideAccountSwitch("ghost-im-bot", ["a-im-bot", "b-im-bot"], files({ "a-im-bot": ok(), "b-im-bot": ok() }));
  assert.deepEqual(d, { action: "switch", accountId: "b-im-bot", reason: "current account is not in the index" });
});

test("SH10 live call shape: the raw index maps through normalizeAccountId before the verdict", () => {
  // shim.mjs persists NORMALIZED ids, but the pipeline normalizes defensively
  // exactly like startup selection does (.at(-1) → normalizeAccountId).
  const rawIndex = ["old@im.bot", "new@im.bot"]; // pre-rebind / post-rebind
  const mapped = rawIndex.map(normalizeAccountId);
  assert.deepEqual(mapped, ["old-im-bot", "new-im-bot"]);
  const loader = files({ "old-im-bot": ok(), "new-im-bot": ok() });
  assert.deepEqual(decideAccountSwitch("old-im-bot", mapped, loader), {
    action: "switch",
    accountId: "new-im-bot",
    reason: "newer valid indexed account",
  });
  // idempotent for an already-normalized index (the steady state shim writes)
  assert.deepEqual(decideAccountSwitch("new-im-bot", mapped, loader).action, "keep");
});

test("SH11 export surface: the pre-existing names are intact (additive batch only)", () => {
  const required = [
    "accountsDirOf",
    "classifyFetchError",
    "commonIlinkHeaders",
    "decideAccountSwitch", // new — self-heal verdict
    "deliverToIngress",
    "latestValidAccountId", // new — self-heal selection
    "listIndexedAccountIds",
    "loadAccount",
    "messageBody",
    "messageIdOf",
    "normalizeAccountId",
    "peek",
    "postIlinkHeaders",
    "readIngressToken",
    "releaseMutex",
    "resolveTokenFile",
    "shape",
    "startMsgLoop",
    "stateDirOf",
    "toInboundBody",
  ];
  const keys = Object.keys(msgloop).sort();
  for (const name of required) {
    assert.ok(keys.includes(name), `missing export: ${name}`);
    assert.equal(typeof msgloop[name], "function", `export ${name} must stay a function`);
  }
  assert.equal(
    msgloop.deliverToIngress.constructor.name,
    "AsyncFunction",
    "deliverToIngress stays async (checked statically — never invoked here)",
  );
});

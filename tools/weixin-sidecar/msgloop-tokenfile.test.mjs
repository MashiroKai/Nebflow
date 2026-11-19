#!/usr/bin/env node
/**
 * msgloop token-file env resolution (weixin-autobind batch, 2026-10-07).
 *
 * Pins the WEIXIN_SEND_TOKEN[_FILE] family normalization of msgloop.mjs (author
 * ruling ②: same bot ⇒ one token, one name; the outbound send leg's spelling
 * is the family standard): the family env wins, the deprecated
 * WEIXIN_MSGLOOP_TOKEN_FILE stays readable for one compatibility release (then
 * removable), and with no env the default is the engine-minted ingress token
 * path. Pure-function spec: the module is IMPORTED, never run — zero iLink
 * traffic, zero filesystem reads, zero token values.
 *
 * Run: node --test tools/weixin-sidecar/msgloop-tokenfile.test.mjs
 */
import test from "node:test";
import assert from "node:assert/strict";
import { resolveTokenFile } from "./msgloop.mjs";

test("TF1 the family env WEIXIN_SEND_TOKEN_FILE wins over the deprecated spelling", () => {
  const f = resolveTokenFile({
    WEIXIN_SEND_TOKEN_FILE: "/x/send-token-file",
    WEIXIN_MSGLOOP_TOKEN_FILE: "/y/pre-rename",
  });
  assert.equal(f, "/x/send-token-file");
});

test("TF2 the deprecated WEIXIN_MSGLOOP_TOKEN_FILE is still read (one compat release)", () => {
  const f = resolveTokenFile({ WEIXIN_MSGLOOP_TOKEN_FILE: "/y/pre-rename" });
  assert.equal(f, "/y/pre-rename");
});

test("TF3 a blank family value falls through to the deprecated one (trim semantics preserved)", () => {
  const f = resolveTokenFile({
    WEIXIN_SEND_TOKEN_FILE: "   ",
    WEIXIN_MSGLOOP_TOKEN_FILE: " /y/pre-rename ",
  });
  assert.equal(f, "/y/pre-rename");
});

test("TF4 no env ⇒ the engine-minted default path shape (~/.nebflow/secrets/weixin-ilink-ingress-token)", () => {
  const f = resolveTokenFile({});
  assert.equal(
    f.endsWith(".nebflow/secrets/weixin-ilink-ingress-token"),
    true,
    `unexpected default shape: ${f}`
  );
});

test("TF5 both envs blank ⇒ the default (a blank env is never a path)", () => {
  const f = resolveTokenFile({ WEIXIN_SEND_TOKEN_FILE: "", WEIXIN_MSGLOOP_TOKEN_FILE: "" });
  assert.equal(f.endsWith(".nebflow/secrets/weixin-ilink-ingress-token"), true, f);
});

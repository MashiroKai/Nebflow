// weixin-outbound batch — send.mjs unit tests (node:test, zero npm deps).
//
// Deliberately LOOPBACK-ONLY: the "iLink" here is a local stub server that
// records requests and answers the contract shapes; no external network is
// touched. What is pinned:
//   - auth: 405/401/503 fail-closed faces (design §0.1), constant-time path
//   - body validation: 400 faces
//   - wire fidelity: sendmessage endpoint, header group, msg body fields,
//     base_info (mirror of the official plugin 2.4.9 send face)
//   - delivery verdict: ret=0 → delivered + lossless message_id; ret!==0 →
//     502 (deterministic, NOT retried); network-class → retried then 502
//   - account resolution: single account auto-picked; several without a hint
//     auto-adopt the NEWEST USABLE one (pop-file-leg batch 2026-10-21 — the
//     msgloop's own rule; the old ambiguous_account refusal is retired);
//     all-unusable refused; unknown hint refused
// Run: node --test tools/weixin-sidecar/send.test.mjs

import test from "node:test";
import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import http from "node:http";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import {
  makeSendHandler,
  buildTextMessageReq,
  buildImageItem,
  buildFileItem,
  encryptAesEcb,
  aesEcbPaddedSize,
  parseLosslessIds,
  latestValidAccountId,
  resolveAccount,
} from "./send.mjs";

// ───────────────────────── harness ─────────────────────────

/** A stub "iLink" that records sendmessage requests. */
function stubIlink(handler) {
  const seen = [];
  const server = http.createServer((req, res) => {
    let body = "";
    req.on("data", (c) => (body += c));
    req.on("end", () => {
      seen.push({ url: req.url, headers: req.headers, body: JSON.parse(body || "{}") });
      handler(req, res, JSON.parse(body || "{}"));
    });
  });
  return {
    seen,
    listen: () =>
      new Promise((resolve) =>
        server.listen(0, "127.0.0.1", () => resolve(`http://127.0.0.1:${server.address().port}`)),
      ),
    close: () => new Promise((resolve) => server.close(resolve)),
  };
}

/** A fake host state dir with one (or several) mirrored account(s). */
function fakeState(tokens) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "nb-send-test-"));
  const wxDir = path.join(dir, "openclaw-weixin");
  fs.mkdirSync(path.join(wxDir, "accounts"), { recursive: true });
  const ids = tokens.map((t, i) => `acct${i}-im-bot`);
  for (let i = 0; i < tokens.length; i++) {
    fs.writeFileSync(
      path.join(wxDir, "accounts", `${ids[i]}.json`),
      JSON.stringify({ token: tokens[i], userId: `user${i}` }),
    );
  }
  fs.writeFileSync(path.join(wxDir, "accounts.json"), JSON.stringify(ids));
  return dir;
}

/** Shared helpers ctx mirroring what shim.mjs hands in. */
function makeCtx(stateDir, stubBase) {
  const lines = [];
  const log = (level) => (m) => lines.push(`${level} ${m}`);
  return {
    ctx: {
      info: log("INFO"),
      warn: log("WARN"),
      error: log("ERROR"),
      debug: log("DEBUG"),
      sendJson: (res, code, obj) => {
        res.writeHead(code, { "Content-Type": "application/json" });
        res.end(JSON.stringify(obj));
      },
      loadAccount: (id) => {
        try {
          return JSON.parse(fs.readFileSync(path.join(stateDir, "openclaw-weixin", "accounts", `${id}.json`), "utf-8"));
        } catch {
          return null;
        }
      },
      listIndexedAccountIds: () => {
        try {
          const v = JSON.parse(fs.readFileSync(path.join(stateDir, "openclaw-weixin", "accounts.json"), "utf-8"));
          return Array.isArray(v) ? v : [];
        } catch {
          return [];
        }
      },
      normalizeAccountId: (raw) => String(raw).replace(/[^A-Za-z0-9_-]/g, "-"),
      PLUGIN_VERSION: "2.4.9",
      ILINK_APP_ID: "bot",
      ILINK_APP_CLIENT_VERSION: "132105",
      ILINK_DEFAULT_BASE: "https://ilinkai.weixin.qq.com",
      WEIXIN_SIDECAR_ILINK_BASE_STUB: stubBase,
      inboundActive: () => false,
    },
    lines,
  };
}

/** POST /send against an in-process handler (no TCP hop for the shim itself). */
function callHandler(handle, { token, body, method = "POST" }) {
  return new Promise((resolve, reject) => {
    const req = new http.IncomingMessage(null);
    req.method = method;
    req.headers = token ? { authorization: `Bearer ${token}` } : {};
    // A bare EventEmitter fake: real response classes expose getters
    // (headersSent, writableEnded) the harness must not fight with.
    const chunks = [];
    const res = new EventEmitter();
    res.statusCode = 0;
    res.writableEnded = false;
    res.writeHead = (code) => {
      res.statusCode = code;
      return res;
    };
    res.write = (c) => {
      chunks.push(Buffer.from(c));
      return true;
    };
    res.end = (c) => {
      if (c) chunks.push(Buffer.from(c));
      res.writableEnded = true;
      resolve({ status: res.statusCode, body: JSON.parse(Buffer.concat(chunks).toString("utf-8") || "{}") });
      return res;
    };
    req.push(typeof body === "string" ? body : JSON.stringify(body ?? {}));
    req.push(null);
    Promise.resolve(handle(req, res)).catch(reject);
  });
}

async function withEnv(env, fn) {
  const saved = {};
  for (const [k, v] of Object.entries(env)) {
    saved[k] = process.env[k];
    if (v === undefined) delete process.env[k];
    else process.env[k] = v;
  }
  try {
    return await fn();
  } finally {
    for (const [k, v] of Object.entries(saved)) {
      if (v === undefined) delete process.env[k];
      else process.env[k] = v;
    }
  }
}

// ───────────────────────── pure helpers ─────────────────────────

test("buildTextMessageReq mirrors the official 2.4.9 send body", () => {
  const req = buildTextMessageReq({ to: "wx_alice", text: "hello", clientId: "cid:1-aa" });
  assert.deepEqual(req.msg, {
    from_user_id: "",
    to_user_id: "wx_alice",
    client_id: "cid:1-aa",
    message_type: 2, // BOT
    message_state: 2, // FINISH
    item_list: [{ type: 1, text_item: { text: "hello" } }], // TEXT
  });
});

test("parseLosslessIds quotes uint64 message_id fields (no precision loss) and nothing else", () => {
  const raw = '{"ret":0,"message_id":1234567890123456789,"other":1234567890123456789,"note":"message_id 42"}';
  const parsed = parseLosslessIds(raw);
  assert.equal(parsed.message_id, "1234567890123456789", "the uint64 id survives as a string");
  assert.equal(typeof parsed.other, "number", "non-id numeric fields stay numbers");
  assert.equal(parsed.note, "message_id 42", "string content is never rewritten");
});

// ───────────────────────── auth faces ─────────────────────────

test("send: no token configured ⇒ 503 fail-closed (the leg stays shut)", async () => {
  await withEnv(
    { WEIXIN_SEND_TOKEN: undefined, WEIXIN_SEND_TOKEN_FILE: undefined, NEBFLOW_HOME: "/nonexistent-nb-home" },
    async () => {
      const { ctx } = makeCtx(fakeState(["tok"]), "http://127.0.0.1:1");
      const handle = makeSendHandler(ctx);
      const res = await callHandler(handle, { token: "anything", body: { to: "wx_alice", text: "hi" } });
      assert.equal(res.status, 503);
      assert.equal(res.body.error, "shim_token_unconfigured");
    },
  );
});

test("send: wrong bearer ⇒ 401; right bearer passes the auth gate", async () => {
  const stub = stubIlink((req, res) => {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ ret: 0, message_id: 42 }));
  });
  const base = await stub.listen();
  await withEnv(
    { WEIXIN_SEND_TOKEN: "secret-token", NEBFLOW_HOME: "/nonexistent-nb-home", WEIXIN_SIDECAR_ILINK_BASE: base },
    async () => {
      try {
        const { ctx } = makeCtx(fakeState(["bot-token-1"]), base);
        const handle = makeSendHandler(ctx);
        const bad = await callHandler(handle, { token: "WRONG", body: { to: "wx_alice", text: "hi" } });
        assert.equal(bad.status, 401);
        assert.equal(bad.body.error, "unauthorized");
        const noHeader = await callHandler(handle, { token: null, body: { to: "wx_alice", text: "hi" } });
        assert.equal(noHeader.status, 401);
        const good = await callHandler(handle, { token: "secret-token", body: { to: "wx_alice", text: "hi" } });
        assert.equal(good.status, 200);
        assert.equal(good.body.delivered, true);
      } finally {
        await stub.close();
      }
    },
  );
});

test("send: non-POST ⇒ 405 (before any auth work)", async () => {
  await withEnv({ WEIXIN_SEND_TOKEN: "t", NEBFLOW_HOME: "/nonexistent-nb-home", WEIXIN_SIDECAR_ILINK_BASE: "http://127.0.0.1:1" }, async () => {
    const { ctx } = makeCtx(fakeState(["tok"]), "http://127.0.0.1:1");
    const handle = makeSendHandler(ctx);
    const res = await callHandler(handle, { method: "GET", token: "t", body: null });
    assert.equal(res.status, 405);
  });
});

// ───────────────────────── body validation ─────────────────────────

test("send: missing to / empty text / bad json ⇒ 400 invalid_body", async () => {
  await withEnv({ WEIXIN_SEND_TOKEN: "t", NEBFLOW_HOME: "/nonexistent-nb-home", WEIXIN_SIDECAR_ILINK_BASE: "http://127.0.0.1:1" }, async () => {
    const { ctx } = makeCtx(fakeState(["tok"]), "http://127.0.0.1:1");
    const handle = makeSendHandler(ctx);
    const noTo = await callHandler(handle, { token: "t", body: { text: "hi" } });
    assert.equal(noTo.status, 400);
    assert.match(noTo.body.error, /invalid_body/);
    const noText = await callHandler(handle, { token: "t", body: { to: "wx_alice" } });
    assert.equal(noText.status, 400);
    const badJson = await callHandler(handle, { token: "t", body: "{not json" });
    assert.equal(badJson.status, 400);
  });
});

// ───────────────────────── wire fidelity ─────────────────────────

test("send: the sendmessage request mirrors the official plugin wire shape", async () => {
  const stub = stubIlink((req, res) => {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end('{"ret":0,"message_id":1234567890123456789}'); // RAW: a JS number literal would already lose uint64 precision in the stub
  });
  const base = await stub.listen();
  // 🔴 egress discipline: EVERY test that can pass the auth+body gates pins the
  // iLink base to the local stub — a test must NEVER reach the real endpoint.
  await withEnv(
    { WEIXIN_SEND_TOKEN: "shim-tok", NEBFLOW_HOME: "/nonexistent-nb-home", WEIXIN_SIDECAR_ILINK_BASE: base },
    async () => {
      try {
        const { ctx } = makeCtx(fakeState(["bot-token-xyz"]), base);
        const handle = makeSendHandler(ctx);
        const res = await callHandler(handle, { token: "shim-tok", body: { to: "filehelper", text: "fixture reply" } });
        assert.equal(res.status, 200);
        assert.equal(res.body.delivered, true);
        assert.equal(res.body.message_id, "1234567890123456789", "the uint64 server id survives as a string");
        assert.equal(res.body.context_threaded, false, "v1 threads no context_token — the reading says so");

      const call = stub.seen[0];
      assert.equal(call.url, "/ilink/bot/sendmessage");
      assert.equal(call.headers["authorizationtype"], "ilink_bot_token");
      assert.equal(call.headers["authorization"], "Bearer bot-token-xyz", "the HOST account token rides the iLink call — never the shim token");
      assert.equal(call.headers["ilink-app-id"], "bot");
      assert.equal(call.headers["ilink-app-clientversion"], "132105");
      assert.ok(call.headers["x-wechat-uin"], "the UIN header is present");
      assert.deepEqual(
        call.body.msg,
        {
          from_user_id: "",
          to_user_id: "filehelper",
          client_id: call.body.msg.client_id,
          message_type: 2,
          message_state: 2,
          item_list: [{ type: 1, text_item: { text: "fixture reply" } }],
        },
      );
      assert.match(call.body.msg.client_id, /^nebflow-sidecar:\d+-[0-9a-f]{8}$/, "client_id grammar mirror, honest prefix");
      assert.deepEqual(call.body.base_info, { channel_version: "2.4.9", bot_agent: "NebflowSidecar/1.0" });
      assert.ok(res.body.client_id, "the response echoes a client_id");
      // observability: the inbound-loop WARN fired (the loop is not running in this harness)
    } finally {
      await stub.close();
    }
  });
});

// ───────────────────────── delivery verdicts ─────────────────────────

test("send: iLink ret!==0 ⇒ 502 delivery_failed, NOT retried (deterministic rejection)", async () => {
  const stub = stubIlink((req, res) => {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ ret: 1, errmsg: "rejected" }));
  });
  const base = await stub.listen();
  await withEnv(
    { WEIXIN_SEND_TOKEN: "t", NEBFLOW_HOME: "/nonexistent-nb-home", WEIXIN_SIDECAR_ILINK_BASE: base, WEIXIN_SIDECAR_SEND_ATTEMPTS: "2", WEIXIN_SIDECAR_SEND_BACKOFF_MS: "10" },
    async () => {
      try {
        const { ctx } = makeCtx(fakeState(["bot-token"]), base);
        const handle = makeSendHandler(ctx);
        const res = await callHandler(handle, { token: "t", body: { to: "wx_alice", text: "hi" } });
        assert.equal(res.status, 502);
        assert.match(res.body.error, /delivery_failed: sendmessage ret=1/);
        assert.equal(stub.seen.length, 1, "a protocol rejection is NEVER retried");
      } finally {
        await stub.close();
      }
    },
  );
});

test("send: network-class failure IS retried, then reported as 502 with the classified cause", async () => {
  // A definitely-free LOOPBACK port: bind port 0, take the assigned port, close.
  // (Low ports like 1 are in undici's bad-port list — the error there is
  // "bad port", not a connection refusal.)
  const freePort = await new Promise((resolve, reject) => {
    const s = http.createServer();
    s.listen(0, "127.0.0.1", () => {
      const p = s.address().port;
      s.close(() => (s.closeAllConnections?.(), resolve(p)));
    });
    s.on("error", reject);
  });
  await withEnv(
    // 🔴 egress discipline: pinned to a dead LOOPBACK port — nothing here can
    // reach any external host; the refusal is local by construction.
    { WEIXIN_SEND_TOKEN: "t", NEBFLOW_HOME: "/nonexistent-nb-home", WEIXIN_SIDECAR_ILINK_BASE: `http://127.0.0.1:${freePort}`, WEIXIN_SIDECAR_SEND_ATTEMPTS: "2", WEIXIN_SIDECAR_SEND_BACKOFF_MS: "10" },
    async () => {
      const { ctx } = makeCtx(fakeState(["bot-token"]), `http://127.0.0.1:${freePort}`);
      const handle = makeSendHandler(ctx);
      const res = await callHandler(handle, { token: "t", body: { to: "wx_alice", text: "hi" } });
      assert.equal(res.status, 502);
      assert.match(res.body.error, /delivery_failed: network tcp_refused after retries/);
      assert.equal(res.body.attempts, 2, "the retry budget was spent (2 attempts)");
    },
  );
});

// ───────────────────────── account resolution ─────────────────────────

test("send: single auto-picked; several without a hint ADOPT THE NEWEST USABLE (同腿同权); hint pins; unknown refused", async () => {
  const stub = stubIlink((req, res) => {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ ret: 0, message_id: 7 }));
  });
  const base = await stub.listen();
  await withEnv(
    { WEIXIN_SEND_TOKEN: "t", NEBFLOW_HOME: "/nonexistent-nb-home", WEIXIN_SIDECAR_ILINK_BASE: base },
    async () => {
      try {
        const one = makeCtx(fakeState(["single-token"]), base).ctx;
        const r1 = await callHandler(makeSendHandler(one), { token: "t", body: { to: "a", text: "x" } });
        assert.equal(r1.status, 200);
        assert.equal(stub.seen.at(-1).headers["authorization"], "Bearer single-token");

        const many = makeCtx(fakeState(["t0", "t1"]), base).ctx;
        const r2 = await callHandler(makeSendHandler(many), { token: "t", body: { to: "a", text: "x" } });
        assert.equal(r2.status, 200, "several accounts + no hint ⇒ the newest USABLE is adopted, never refused");
        assert.equal(stub.seen.at(-1).headers["authorization"], "Bearer t1", "the NEWEST indexed account wins");

        const r3 = await callHandler(makeSendHandler(many), { token: "t", body: { to: "a", text: "x", account: "acct1@im.bot" } });
        assert.equal(r3.status, 200, "an explicit hint still pins the account");
        assert.equal(stub.seen.at(-1).headers["authorization"], "Bearer t1");

        const r4 = await callHandler(makeSendHandler(many), { token: "t", body: { to: "a", text: "x", account: "nope" } });
        assert.equal(r4.status, 500);
        assert.match(r4.body.error, /unknown_account/);
      } finally {
        await stub.close();
      }
    },
  );
});

test("send: newest account tokenless ⇒ adopt the newest VALID; all unusable ⇒ 500 no_usable_account", async () => {
  // A definitely-free LOOPBACK port: bind port 0, take the assigned port, close.
  const freePort = await new Promise((resolve, reject) => {
    const s = http.createServer();
    s.listen(0, "127.0.0.1", () => {
      const p = s.address().port;
      s.close(() => (s.closeAllConnections?.(), resolve(p))); // release the handle — an open server would keep the test process alive
    });
    s.on("error", reject);
  });
  const base = `http://127.0.0.1:${freePort}`;
  await withEnv(
    // 🔴 egress discipline: the base is pinned to the dead LOOPBACK port — the
    // send fails fast and locally; the point under test is only WHICH account
    // was adopted, never any real wire.
    {
      WEIXIN_SEND_TOKEN: "t",
      NEBFLOW_HOME: "/nonexistent-nb-home",
      WEIXIN_SIDECAR_ILINK_BASE: base,
      WEIXIN_SIDECAR_SEND_ATTEMPTS: "1",
      WEIXIN_SIDECAR_SEND_TIMEOUT_MS: "50",
      WEIXIN_SIDECAR_SEND_BACKOFF_MS: "0",
    },
    async () => {
      // newest index entry (acct1) carries no usable token — the walk skips it
      const state = fakeState(["t0", null]);
      const { ctx } = makeCtx(state, base);
      const r = await callHandler(makeSendHandler(ctx), { token: "t", body: { to: "a", text: "x" } });
      assert.equal(r.status, 502, "no iLink stub wired — but the account face must NOT be the refusal");
      assert.doesNotMatch(r.body.error, /no_usable_account|ambiguous_account|unusable_account/, "acct0 (the newest VALID) was adopted");
      assert.match(r.body.error, /delivery_failed/, "the failure is the send itself, not the account");

      const none = makeCtx(fakeState([null, null]), base).ctx;
      const r2 = await callHandler(makeSendHandler(none), { token: "t", body: { to: "a", text: "x" } });
      assert.equal(r2.status, 500);
      assert.match(r2.body.error, /no_usable_account/);
    },
  );
});

test("latestValidAccountId: walks newest→oldest, skips blank ids and tokenless files (msgloop twin)", () => {
  const loader = (id) => (id === "b" ? null : { token: id === "a" ? "  " : `tok-${id}` });
  assert.equal(latestValidAccountId(["a", "b", "c"], loader), "c");
  assert.equal(latestValidAccountId(["a", "", "c"], loader), "c");
  assert.equal(latestValidAccountId(["a", "b"], loader), null, "none usable ⇒ null");
  assert.equal(latestValidAccountId([], loader), null);
});

test("resolveAccount: hint normalization still maps a raw bot id; blank hint = no hint (auto-adopt)", () => {
  const cx = {
    listIndexedAccountIds: () => ["acct0-im-bot", "acct1-im-bot"],
    loadAccount: (id) => ({ token: `tok-${id}` }),
    normalizeAccountId: (raw) => String(raw).replace(/[^A-Za-z0-9_-]/g, "-"),
  };
  assert.equal(resolveAccount("acct1@im.bot", cx).id, "acct1-im-bot", "raw bot id normalizes to the fs-safe id");
  assert.equal(resolveAccount("   ", cx).id, "acct1-im-bot", "a blank hint is no hint — the newest usable is adopted");
  assert.equal(resolveAccount(undefined, cx).id, "acct1-im-bot");
});

test("send: empty host account index ⇒ 500 no_host_account (never a silent fallback)", async () => {
  await withEnv({ WEIXIN_SEND_TOKEN: "t", NEBFLOW_HOME: "/nonexistent-nb-home", WEIXIN_SIDECAR_ILINK_BASE: "http://127.0.0.1:1" }, async () => {
    const { ctx } = makeCtx(fakeState([]), "http://127.0.0.1:1");
    const res = await callHandler(makeSendHandler(ctx), { token: "t", body: { to: "a", text: "x" } });
    assert.equal(res.status, 500);
    assert.match(res.body.error, /no_host_account/);
  });
});

// ───────────────────────── media leg (weixin-media batch) ─────────────────────────
// The full official pipeline against a LOCAL stub only (egress discipline): one
// stub server plays BOTH iLink (getuploadurl, sendmessage) and the CDN (the
// upload_full_url it issues points back at itself). 🔴 Every media test pins
// WEIXIN_SIDECAR_ILINK_BASE to the stub — nothing here can reach Tencent.

/** A raw wire stub: records {url, headers, body:Buffer}; handler answers. */
function stubWire(handler) {
  const seen = [];
  const server = http.createServer((req, res) => {
    const chunks = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => {
      const body = Buffer.concat(chunks);
      seen.push({ url: req.url, headers: req.headers, body });
      handler(req, res, body, seen[seen.length - 1]);
    });
  });
  return {
    seen,
    listen: () =>
      new Promise((resolve) =>
        server.listen(0, "127.0.0.1", () => resolve(`http://127.0.0.1:${server.address().port}`)),
      ),
    close: () => new Promise((resolve) => server.close(resolve)),
  };
}

/** A stub playing the WHOLE media pipeline. Options: cdnStatus (each CDN
 * answer's status), downloadParam (null ⇒ the header is OMITTED), uploadRet
 * (the getuploadurl ret). One server plays iLink AND the CDN: the
 * upload_full_url it issues points back at itself. */
function mediaPipeline(opts = {}) {
  const counts = { getuploadurl: 0, cdn: 0, sendmessage: 0 };
  let port = 0;
  const stub = stubWire((req, res) => {
    if (req.url === "/ilink/bot/getuploadurl") {
      counts.getuploadurl++;
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(
        JSON.stringify({
          ret: opts.uploadRet ?? 0,
          upload_full_url: `http://127.0.0.1:${port}/cdn-upload?ticket=t1`,
        }),
      );
    } else if (req.url.startsWith("/cdn-upload")) {
      counts.cdn++;
      const st = opts.cdnStatus ?? 200;
      if (st === 200) {
        const headers = opts.downloadParam === null ? {} : { "x-encrypted-param": opts.downloadParam ?? "dlp-abc" };
        res.writeHead(200, headers);
        res.end("ok");
      } else {
        res.writeHead(st, { "x-error-message": "cdn refuses" });
        res.end("no");
      }
    } else if (req.url === "/ilink/bot/sendmessage") {
      counts.sendmessage++;
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end('{"ret":0,"message_id":777}');
    } else {
      res.writeHead(404);
      res.end("{}");
    }
  });
  return {
    seen: stub.seen,
    counts,
    listen: async () => {
      const base = await stub.listen();
      port = Number(base.split(":").pop());
      return base;
    },
    close: stub.close,
  };
}

/** A small temp file with stable bytes (its own PNG-ish magic header). */
function tempMediaFile(name, size = 24) {
  const bytes = Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), crypto.randomBytes(size)]);
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "nb-media-test-"));
  const p = path.join(dir, name);
  fs.writeFileSync(p, bytes);
  return { path: p, bytes };
}

test("media: image pipeline mirrors the official wire — getuploadurl → AES-ECB CDN POST → image_item", async () => {
  const img = tempMediaFile("pop.png");
  const stub = mediaPipeline();
  const base = await stub.listen();
  await withEnv(
    { WEIXIN_SEND_TOKEN: "shim-tok", NEBFLOW_HOME: "/nonexistent-nb-home", WEIXIN_SIDECAR_ILINK_BASE: base },
    async () => {
      try {
        const { ctx } = makeCtx(fakeState(["bot-token-xyz"]), base);
        const handle = makeSendHandler(ctx);
        const res = await callHandler(handle, { token: "shim-tok", body: { to: "wx_alice", image_path: img.path } });
        assert.equal(res.status, 200);
        assert.equal(res.body.delivered, true);
        assert.deepEqual(res.body.media, { kind: "image", bytes: img.bytes.length, name: "pop.png" });
        assert.equal(res.body.message_id, "777");

        // ① getuploadurl body: the official field set, padded ciphertext size
        const up = stub.seen.find((e) => e.url === "/ilink/bot/getuploadurl");
        const upBody = JSON.parse(up.body.toString("utf-8"));
        assert.equal(upBody.media_type, 1, "UploadMediaType.IMAGE");
        assert.equal(upBody.to_user_id, "wx_alice");
        assert.equal(upBody.rawsize, img.bytes.length);
        assert.equal(upBody.rawfilemd5, crypto.createHash("md5").update(img.bytes).digest("hex"));
        assert.equal(upBody.filesize, aesEcbPaddedSize(img.bytes.length), "the ciphertext size rides the request");
        assert.equal(upBody.no_need_thumb, true);
        assert.match(upBody.filekey, /^[0-9a-f]{32}$/);
        assert.match(upBody.aeskey, /^[0-9a-f]{32}$/);
        assert.equal(up.headers["authorization"], "Bearer bot-token-xyz", "the HOST token rides iLink, never the shim token");
        assert.deepEqual(upBody.base_info, { channel_version: "2.4.9", bot_agent: "NebflowSidecar/1.0" });

        // ② CDN POST: octet-stream ciphertext that DECRYPTS to the plaintext
        const cdn = stub.seen.find((e) => e.url.startsWith("/cdn-upload"));
        assert.equal(cdn.headers["content-type"], "application/octet-stream");
        const decipher = crypto.createDecipheriv("aes-128-ecb", Buffer.from(upBody.aeskey, "hex"), null);
        const plain = Buffer.concat([decipher.update(cdn.body), decipher.final()]);
        assert.ok(plain.equals(img.bytes), "the CDN body decrypts (AES-128-ECB/PKCS7) to the original file");
        assert.equal(cdn.body.length, aesEcbPaddedSize(img.bytes.length));

        // ③ exactly ONE sendmessage carrying ONE image_item
        assert.equal(stub.counts.sendmessage, 1);
        const msg = JSON.parse(stub.seen.find((e) => e.url === "/ilink/bot/sendmessage").body.toString("utf-8")).msg;
        assert.equal(msg.to_user_id, "wx_alice");
        assert.equal(msg.item_list.length, 1);
        const item = msg.item_list[0];
        const expected = buildImageItem({ downloadParam: "dlp-abc", aeskeyHex: upBody.aeskey, fileSizeCiphertext: upBody.filesize });
        assert.deepEqual(item, expected);
        assert.equal(item.image_item.media.encrypt_query_param, "dlp-abc", "the CDN header becomes the download param");
        assert.equal(item.image_item.media.aes_key, Buffer.from(upBody.aeskey, "utf-8").toString("base64"));
        assert.equal(item.image_item.media.encrypt_type, 1);
        assert.equal(item.image_item.mid_size, upBody.filesize);
      } finally {
        await stub.close();
      }
    },
  );
});

test("media: a caption TEXT rides FIRST as its own sendmessage (official sendMediaItems)", async () => {
  const img = tempMediaFile("cap.png");
  const stub = mediaPipeline();
  const base = await stub.listen();
  await withEnv(
    { WEIXIN_SEND_TOKEN: "t", NEBFLOW_HOME: "/nonexistent-nb-home", WEIXIN_SIDECAR_ILINK_BASE: base },
    async () => {
      try {
        const { ctx } = makeCtx(fakeState(["bot-token"]), base);
        const handle = makeSendHandler(ctx);
        const res = await callHandler(handle, { token: "t", body: { to: "wx_alice", text: "看图", image_path: img.path } });
        assert.equal(res.status, 200);
        const sends = stub.seen.filter((e) => e.url === "/ilink/bot/sendmessage").map((e) => JSON.parse(e.body.toString("utf-8")).msg);
        assert.equal(sends.length, 2);
        assert.equal(sends[0].item_list[0].type, 1, "the caption goes first");
        assert.equal(sends[0].item_list[0].text_item.text, "看图");
        assert.equal(sends[1].item_list[0].type, 2);
        assert.notEqual(sends[0].client_id, sends[1].client_id, "each request carries its own client_id");
      } finally {
        await stub.close();
      }
    },
  );
});

test("media: file leg — media_type FILE, file_item{media,file_name,md5,len}", async () => {
  const doc = tempMediaFile("report.pdf", 48);
  const stub = mediaPipeline({ downloadParam: "dlp-file" });
  const base = await stub.listen();
  await withEnv(
    { WEIXIN_SEND_TOKEN: "t", NEBFLOW_HOME: "/nonexistent-nb-home", WEIXIN_SIDECAR_ILINK_BASE: base },
    async () => {
      try {
        const { ctx } = makeCtx(fakeState(["bot-token"]), base);
        const handle = makeSendHandler(ctx);
        const res = await callHandler(handle, { token: "t", body: { to: "wx_bob", file_path: doc.path } });
        assert.equal(res.status, 200);
        assert.deepEqual(res.body.media, { kind: "file", bytes: doc.bytes.length, name: "report.pdf" });
        const upBody = JSON.parse(stub.seen.find((e) => e.url === "/ilink/bot/getuploadurl").body.toString("utf-8"));
        assert.equal(upBody.media_type, 3, "UploadMediaType.FILE");
        const msg = JSON.parse(stub.seen.find((e) => e.url === "/ilink/bot/sendmessage").body.toString("utf-8")).msg;
        const expected = buildFileItem({
          downloadParam: "dlp-file",
          aeskeyHex: upBody.aeskey,
          fileName: "report.pdf",
          md5: crypto.createHash("md5").update(doc.bytes).digest("hex"),
          fileSize: doc.bytes.length,
        });
        assert.deepEqual(msg.item_list[0], expected);
        assert.equal(msg.item_list[0].file_item.len, String(doc.bytes.length), "len is the PLAINTEXT size, as a string");
      } finally {
        await stub.close();
      }
    },
  );
});

test("media: validation — no text and no path / both paths / relative path / missing file ⇒ 400, zero iLink calls", async () => {
  const img = tempMediaFile("ok.png");
  await withEnv(
    { WEIXIN_SEND_TOKEN: "t", NEBFLOW_HOME: "/nonexistent-nb-home", WEIXIN_SIDECAR_ILINK_BASE: "http://127.0.0.1:1" },
    async () => {
      const { ctx } = makeCtx(fakeState(["tok"]), "http://127.0.0.1:1");
      const handle = makeSendHandler(ctx);
      const neither = await callHandler(handle, { token: "t", body: { to: "a" } });
      assert.equal(neither.status, 400);
      assert.match(neither.body.error, /one of 'text', 'image_path', 'file_path'/);
      const both = await callHandler(handle, { token: "t", body: { to: "a", image_path: img.path, file_path: img.path } });
      assert.equal(both.status, 400);
      assert.match(both.body.error, /mutually exclusive/);
      const rel = await callHandler(handle, { token: "t", body: { to: "a", image_path: "relative/pop.png" } });
      assert.equal(rel.status, 400);
      assert.match(rel.body.error, /absolute/);
      const gone = await callHandler(handle, { token: "t", body: { to: "a", file_path: "/nonexistent-nb-media/x.pdf" } });
      assert.equal(gone.status, 400);
      assert.match(gone.body.error, /media_path_unreadable/);
    },
  );
});

test("media: CDN 5xx retries to the official budget then 502 — NO sendmessage ever fires", async () => {
  const img = tempMediaFile("f.png", 16);
  const stub = mediaPipeline({ cdnStatus: 500 });
  const base = await stub.listen();
  await withEnv(
    { WEIXIN_SEND_TOKEN: "t", NEBFLOW_HOME: "/nonexistent-nb-home", WEIXIN_SIDECAR_ILINK_BASE: base },
    async () => {
      try {
        const { ctx } = makeCtx(fakeState(["bot-token"]), base);
        const handle = makeSendHandler(ctx);
        const res = await callHandler(handle, { token: "t", body: { to: "a", image_path: img.path } });
        assert.equal(res.status, 502);
        assert.match(res.body.error, /CDN upload failed after 3 attempts/);
        assert.equal(stub.counts.cdn, 3, "the official UPLOAD_MAX_RETRIES budget");
        assert.equal(stub.counts.sendmessage, 0, "no media item is sent from a failed upload");
      } finally {
        await stub.close();
      }
    },
  );
});

test("media: CDN 4xx aborts immediately (deterministic, exactly one CDN attempt)", async () => {
  const img = tempMediaFile("f.png", 16);
  const stub = mediaPipeline({ cdnStatus: 403 });
  const base = await stub.listen();
  await withEnv(
    { WEIXIN_SEND_TOKEN: "t", NEBFLOW_HOME: "/nonexistent-nb-home", WEIXIN_SIDECAR_ILINK_BASE: base },
    async () => {
      try {
        const { ctx } = makeCtx(fakeState(["bot-token"]), base);
        const handle = makeSendHandler(ctx);
        const res = await callHandler(handle, { token: "t", body: { to: "a", image_path: img.path } });
        assert.equal(res.status, 502);
        assert.match(res.body.error, /CDN upload client error 403/);
        assert.equal(stub.counts.cdn, 1);
        assert.equal(stub.counts.sendmessage, 0);
      } finally {
        await stub.close();
      }
    },
  );
});

test("media: a CDN 200 without x-encrypted-param is retried then refused", async () => {
  const img = tempMediaFile("f.png", 16);
  const stub = mediaPipeline({ downloadParam: null });
  const base = await stub.listen();
  await withEnv(
    { WEIXIN_SEND_TOKEN: "t", NEBFLOW_HOME: "/nonexistent-nb-home", WEIXIN_SIDECAR_ILINK_BASE: base },
    async () => {
      try {
        const { ctx } = makeCtx(fakeState(["bot-token"]), base);
        const handle = makeSendHandler(ctx);
        const res = await callHandler(handle, { token: "t", body: { to: "a", image_path: img.path } });
        assert.equal(res.status, 502);
        assert.match(res.body.error, /missing x-encrypted-param/);
        assert.equal(stub.counts.cdn, 3);
        assert.equal(stub.counts.sendmessage, 0);
      } finally {
        await stub.close();
      }
    },
  );
});

test("media: getuploadurl ret!==0 ⇒ deterministic 502, no CDN attempt", async () => {
  const img = tempMediaFile("f.png", 16);
  const stub = mediaPipeline({ uploadRet: -1 });
  const base = await stub.listen();
  await withEnv(
    { WEIXIN_SEND_TOKEN: "t", NEBFLOW_HOME: "/nonexistent-nb-home", WEIXIN_SIDECAR_ILINK_BASE: base },
    async () => {
      try {
        const { ctx } = makeCtx(fakeState(["bot-token"]), base);
        const handle = makeSendHandler(ctx);
        const res = await callHandler(handle, { token: "t", body: { to: "a", image_path: img.path } });
        assert.equal(res.status, 502);
        assert.match(res.body.error, /getuploadurl ret=-1/);
        assert.equal(stub.counts.getuploadurl, 1, "a protocol rejection is never retried");
        assert.equal(stub.counts.cdn, 0);
      } finally {
        await stub.close();
      }
    },
  );
});

// ───────────── 媒体合并批 (2026-10-22, 台账 #756): the images[] group face ─────────────
// 作者令：①「微信上一起发图片好像不会组合一起发送 而是分开发送」②「如果没有文字
// 内容 要等一下文字再一起发 不要空发图片和文件」。组面 = N 图一次上传 + 一条
// sendmessage 携 [caption?] + N image_item（探针），任何拒绝自动回落官方逐条序。
// 全部走本地 stub（egress discipline 不破）。

/**
 * A group-face pipeline stub: the whole media pipeline PLUS a sendmessage
 * rets queue (`sendRets`: shifted per sendmessage call, default all 0) and
 * `destroyFirst` (the first N sendmessage calls get their socket destroyed —
 * the network-class failure face).
 */
function groupPipeline(opts = {}) {
  const counts = { getuploadurl: 0, cdn: 0, sendmessage: 0 };
  let port = 0;
  const rets = [...(opts.sendRets ?? [])];
  let sends = 0;
  const stub = stubWire((req, res) => {
    if (req.url === "/ilink/bot/getuploadurl") {
      counts.getuploadurl++;
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ret: 0, upload_full_url: `http://127.0.0.1:${port}/cdn-upload?ticket=t${counts.getuploadurl}` }));
    } else if (req.url.startsWith("/cdn-upload")) {
      counts.cdn++;
      res.writeHead(200, { "x-encrypted-param": `dlp-${counts.cdn}` });
      res.end("ok");
    } else if (req.url === "/ilink/bot/sendmessage") {
      counts.sendmessage++;
      sends++;
      if (opts.destroyFirst && sends <= opts.destroyFirst) {
        res.destroy();
        return;
      }
      const ret = rets.length ? rets.shift() : 0;
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ret, message_id: 900 + sends }));
    } else {
      res.writeHead(404);
      res.end("{}");
    }
  });
  return {
    seen: stub.seen,
    counts,
    sendmessages: () =>
      stub.seen
        .filter((e) => e.url === "/ilink/bot/sendmessage")
        .map((e) => JSON.parse(e.body.toString("utf-8")).msg),
    listen: async () => {
      const base = await stub.listen();
      port = Number(base.split(":").pop());
      return base;
    },
    close: stub.close,
  };
}

test("group: 3 images ⇒ ONE sendmessage carrying the 3 image_items in payload order (组合一起发送)", async () => {
  const a = tempMediaFile("a.png", 24);
  const b = tempMediaFile("b.png", 48);
  const c = tempMediaFile("c.png", 72);
  const stub = groupPipeline();
  const base = await stub.listen();
  await withEnv(
    { WEIXIN_SEND_TOKEN: "t", NEBFLOW_HOME: "/nonexistent-nb-home", WEIXIN_SIDECAR_ILINK_BASE: base },
    async () => {
      try {
        const { ctx } = makeCtx(fakeState(["bot-token"]), base);
        const handle = makeSendHandler(ctx);
        const res = await callHandler(handle, {
          token: "t",
          body: { to: "wx_alice", images: [a.path, b.path, c.path] },
        });
        assert.equal(res.status, 200);
        assert.equal(res.body.delivered, true);
        assert.equal(res.body.group, true, "the native group sendmessage is the delivery path");
        assert.deepEqual(res.body.media, {
          kind: "images",
          count: 3,
          bytes: [a.bytes.length, b.bytes.length, c.bytes.length],
          names: ["a.png", "b.png", "c.png"],
        });
        // exactly 3 uploads and exactly ONE sendmessage
        assert.equal(stub.counts.getuploadurl, 3);
        assert.equal(stub.counts.cdn, 3);
        assert.equal(stub.counts.sendmessage, 1);
        // payload order: the upload url bodies arrive in request order …
        const rawsizes = stub.seen
          .filter((e) => e.url === "/ilink/bot/getuploadurl")
          .map((e) => JSON.parse(e.body.toString("utf-8")).rawsize);
        assert.deepEqual(rawsizes, [a.bytes.length, b.bytes.length, c.bytes.length]);
        // … and the ONE message carries all 3 image items in the same order
        const msgs = stub.sendmessages();
        assert.equal(msgs.length, 1);
        assert.equal(msgs[0].item_list.length, 3);
        assert.deepEqual(
          msgs[0].item_list.map((it) => it.image_item.mid_size),
          [aesEcbPaddedSize(a.bytes.length), aesEcbPaddedSize(b.bytes.length), aesEcbPaddedSize(c.bytes.length)],
        );
        assert.equal(msgs[0].message_type, 2);
        assert.equal(msgs[0].message_state, 2);
      } finally {
        await stub.close();
      }
    },
  );
});

test("group: a caption rides FIRST INSIDE the group message (图文同发 — one message, text + images)", async () => {
  const a = tempMediaFile("a.png", 16);
  const b = tempMediaFile("b.png", 32);
  const stub = groupPipeline();
  const base = await stub.listen();
  await withEnv(
    { WEIXIN_SEND_TOKEN: "t", NEBFLOW_HOME: "/nonexistent-nb-home", WEIXIN_SIDECAR_ILINK_BASE: base },
    async () => {
      try {
        const { ctx } = makeCtx(fakeState(["bot-token"]), base);
        const handle = makeSendHandler(ctx);
        const res = await callHandler(handle, {
          token: "t",
          body: { to: "wx_alice", text: "这两张图如下", images: [a.path, b.path] },
        });
        assert.equal(res.status, 200);
        assert.equal(res.body.group, true);
        assert.equal(stub.counts.sendmessage, 1, "caption + images = ONE message (no separate caption send)");
        const msgs = stub.sendmessages();
        assert.equal(msgs[0].item_list.length, 3);
        assert.equal(msgs[0].item_list[0].type, 1, "the text item rides FIRST in the same item_list");
        assert.equal(msgs[0].item_list[0].text_item.text, "这两张图如下");
        assert.equal(msgs[0].item_list[1].type, 2);
        assert.equal(msgs[0].item_list[2].type, 2);
      } finally {
        await stub.close();
      }
    },
  );
});

test("group: the server refusing the group sendmessage (ret!==0) ⇒ automatic official per-item fallback (caption first, fresh pipelines)", async () => {
  const a = tempMediaFile("a.png", 16);
  const b = tempMediaFile("b.png", 32);
  // rets: group attempt rejected; fallback = caption sendmessage + 2 image sendmessages
  const stub = groupPipeline({ sendRets: [1, 0, 0, 0] });
  const base = await stub.listen();
  await withEnv(
    { WEIXIN_SEND_TOKEN: "t", NEBFLOW_HOME: "/nonexistent-nb-home", WEIXIN_SIDECAR_ILINK_BASE: base },
    async () => {
      try {
        const { ctx } = makeCtx(fakeState(["bot-token"]), base);
        const handle = makeSendHandler(ctx);
        const res = await callHandler(handle, {
          token: "t",
          body: { to: "wx_alice", text: "看图", images: [a.path, b.path] },
        });
        assert.equal(res.status, 200, "the fallback delivers — the caller sees NO failure");
        assert.equal(res.body.delivered, true);
        assert.equal(res.body.group, false, "the response names the actual delivery path");
        // 2 uploads in the group pipeline + 2 FRESH uploads in the fallback
        assert.equal(stub.counts.getuploadurl, 4);
        assert.equal(stub.counts.cdn, 4);
        assert.equal(stub.counts.sendmessage, 4);
        const msgs = stub.sendmessages();
        assert.equal(msgs[0].item_list.length, 3, "call 1 = the rejected group probe");
        assert.equal(msgs[1].item_list[0].type, 1, "fallback call 1 = the caption text, FIRST");
        assert.equal(msgs[1].item_list[0].text_item.text, "看图");
        assert.equal(msgs[2].item_list[0].type, 2, "fallback call 2 = image 1 alone");
        assert.equal(msgs[3].item_list[0].type, 2, "fallback call 3 = image 2 alone");
        assert.equal(msgs[2].client_id !== msgs[3].client_id, true, "each fallback request carries its own client_id");
      } finally {
        await stub.close();
      }
    },
  );
});

test("group: WEIXIN_SIDECAR_MEDIA_GROUP=off ⇒ NO group attempt (straight per-item, no wasted uploads)", async () => {
  const a = tempMediaFile("a.png", 16);
  const stub = groupPipeline();
  const base = await stub.listen();
  await withEnv(
    { WEIXIN_SEND_TOKEN: "t", NEBFLOW_HOME: "/nonexistent-nb-home", WEIXIN_SIDECAR_ILINK_BASE: base, WEIXIN_SIDECAR_MEDIA_GROUP: "off" },
    async () => {
      try {
        const { ctx } = makeCtx(fakeState(["bot-token"]), base);
        const handle = makeSendHandler(ctx);
        const res = await callHandler(handle, { token: "t", body: { to: "wx_alice", images: [a.path] } });
        assert.equal(res.status, 200);
        assert.equal(res.body.group, false);
        assert.equal(stub.counts.getuploadurl, 1, "exactly one upload — no probe upload");
        assert.equal(stub.counts.sendmessage, 1);
      } finally {
        await stub.close();
      }
    },
  );
});

test("group: a failing fallback item stops the batch ⇒ 502 with delivered_count (partial delivery is honest)", async () => {
  const a = tempMediaFile("a.png", 16);
  const b = tempMediaFile("b.png", 32);
  // rets: group rejected (1); fallback: img1 ok (0), img2 rejected (2)
  const stub = groupPipeline({ sendRets: [1, 0, 2] });
  const base = await stub.listen();
  await withEnv(
    { WEIXIN_SEND_TOKEN: "t", NEBFLOW_HOME: "/nonexistent-nb-home", WEIXIN_SIDECAR_ILINK_BASE: base },
    async () => {
      try {
        const { ctx } = makeCtx(fakeState(["bot-token"]), base);
        const handle = makeSendHandler(ctx);
        const res = await callHandler(handle, { token: "t", body: { to: "wx_alice", images: [a.path, b.path] } });
        assert.equal(res.status, 502);
        assert.equal(res.body.delivered_count, 1, "the body names the partial delivery");
        assert.match(res.body.error, /delivered 1/);
        assert.equal(stub.counts.sendmessage, 3, "the stop-at-first-failure boundary: no third fallback item");
      } finally {
        await stub.close();
      }
    },
  );
});

test("group: a network-class failure on the group attempt ⇒ the fallback still delivers", async () => {
  const a = tempMediaFile("a.png", 16);
  const b = tempMediaFile("b.png", 32);
  // the first 2 sendmessage calls (the group attempt's retry budget) get destroyed
  const stub = groupPipeline({ destroyFirst: 2, sendRets: [] });
  const base = await stub.listen();
  await withEnv(
    { WEIXIN_SEND_TOKEN: "t", NEBFLOW_HOME: "/nonexistent-nb-home", WEIXIN_SIDECAR_ILINK_BASE: base, WEIXIN_SIDECAR_SEND_BACKOFF_MS: "10" },
    async () => {
      try {
        const { ctx } = makeCtx(fakeState(["bot-token"]), base);
        const handle = makeSendHandler(ctx);
        const res = await callHandler(handle, { token: "t", body: { to: "wx_alice", images: [a.path, b.path] } });
        assert.equal(res.status, 200);
        assert.equal(res.body.delivered, true);
        assert.equal(res.body.group, false);
        // 2 group uploads + 2 fresh fallback uploads; sendmessage = 2 destroyed attempts + 2 delivered
        assert.equal(stub.counts.getuploadurl, 4);
        assert.equal(stub.counts.sendmessage, 4);
      } finally {
        await stub.close();
      }
    },
  );
});

test("group: validation — images + image_path / non-array / empty array / relative / missing ⇒ 400, zero iLink calls", async () => {
  const img = tempMediaFile("ok.png");
  await withEnv(
    { WEIXIN_SEND_TOKEN: "t", NEBFLOW_HOME: "/nonexistent-nb-home", WEIXIN_SIDECAR_ILINK_BASE: "http://127.0.0.1:1" },
    async () => {
      const { ctx } = makeCtx(fakeState(["tok"]), "http://127.0.0.1:1");
      const handle = makeSendHandler(ctx);
      const bothFaces = await callHandler(handle, { token: "t", body: { to: "a", images: [img.path], image_path: img.path } });
      assert.equal(bothFaces.status, 400);
      assert.match(bothFaces.body.error, /mutually exclusive/);
      const notArray = await callHandler(handle, { token: "t", body: { to: "a", images: "nope" } });
      assert.equal(notArray.status, 400);
      assert.match(notArray.body.error, /must be an array/);
      const empty = await callHandler(handle, { token: "t", body: { to: "a", images: [] } });
      assert.equal(empty.status, 400);
      assert.match(empty.body.error, /non-empty/);
      const rel = await callHandler(handle, { token: "t", body: { to: "a", images: ["relative/a.png"] } });
      assert.equal(rel.status, 400);
      assert.match(rel.body.error, /absolute/);
      const gone = await callHandler(handle, { token: "t", body: { to: "a", images: ["/nonexistent-nb-media/x.png"] } });
      assert.equal(gone.status, 400);
      assert.match(gone.body.error, /media_path_unreadable/);
      const entry = await callHandler(handle, { token: "t", body: { to: "a", images: [123] } });
      assert.equal(entry.status, 400);
      assert.match(entry.body.error, /non-empty array/);
    },
  );
});

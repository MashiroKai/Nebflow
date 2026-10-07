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
//   - account resolution: single account auto-picked; ambiguous refused;
//     unknown hint refused
// Run: node --test tools/weixin-sidecar/send.test.mjs

import test from "node:test";
import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import http from "node:http";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { makeSendHandler, buildTextMessageReq, parseLosslessIds } from "./send.mjs";

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

test("send: one indexed account is auto-picked; several without a hint is a refusal, never a guess", async () => {
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
        assert.equal(r2.status, 500);
        assert.match(r2.body.error, /ambiguous_account/);

        const r3 = await callHandler(makeSendHandler(many), { token: "t", body: { to: "a", text: "x", account: "acct1@im.bot" } });
        assert.equal(r3.status, 200, "an explicit hint (raw bot id) resolves the ambiguity");
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

test("send: empty host account index ⇒ 500 no_host_account (never a silent fallback)", async () => {
  await withEnv({ WEIXIN_SEND_TOKEN: "t", NEBFLOW_HOME: "/nonexistent-nb-home", WEIXIN_SIDECAR_ILINK_BASE: "http://127.0.0.1:1" }, async () => {
    const { ctx } = makeCtx(fakeState([]), "http://127.0.0.1:1");
    const res = await callHandler(makeSendHandler(ctx), { token: "t", body: { to: "a", text: "x" } });
    assert.equal(res.status, 500);
    assert.match(res.body.error, /no_host_account/);
  });
});

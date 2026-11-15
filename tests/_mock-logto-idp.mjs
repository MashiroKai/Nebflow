// _mock-logto-idp.mjs — 本地 mock Logto IdP（登录线本地真实链路批 2026-11-15）。
//
// Why：授权范围 = 隔离本地真实 IdP/HTTPS/portal/双浏览器（不碰真实付费账号）。
// 仓库此前没有本地 IdP——PKCE/密码的浏览器全流只能停在单元面或碰部署租户。
// 本文件把部署 Logto 的**协议形状**（neblink/core/Protocol.scala LogtoOidc +
// LogtoPasswordFlow.scala Experience 四步）在 127.0.0.1 上真实复刻：
//   · GET  /oidc/auth                          授权端点（PKCE S256 全参数校验）
//   · POST /mock/login                         表单提交腿（浏览器真实键入）
//   · GET  /oidc/auth/:state/continue          Experience 链收口 → 302 带 code
//   · POST /oidc/token                         RFC 7636 真校验（错 verifier ⇒ invalid_grant）
//   · PUT  /api/experience                     开 Experience 会话（Set-Cookie 交互签）
//   · POST /api/experience/verification/password  密码校验 → verificationId
//   · POST /api/experience/identification       绑定身份
//   · POST /api/experience/submit               → { redirectTo }
//   · POST /api/device/register                 NebLink 注册汇聚点 → {deviceToken}
//   · GET  /.well-known/openid-configuration    discovery 形状
// code 一次性、state 一次性、10 分钟 TTL；账号库 = 测试内建账号，零真实凭据。
//
// Run: node tests/_mock-logto-idp.mjs --port 8093 --account e2e-user:e2e-password
//      （由 tests/login-local-e2e.mjs 以子进程方式拉起与收杀，不手工跑。）

import { createServer } from 'node:http';
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { parse } from 'node:url';

// ── CLI ────────────────────────────────────────────────────────────────────
const args = process.argv.slice(2);
const argOf = (name, dflt) => {
  const i = args.indexOf(name);
  return i >= 0 && args[i + 1] ? args[i + 1] : dflt;
};
const PORT = Number(argOf('--port', '8093'));
const [USER, PASS] = (argOf('--account', 'e2e-user:e2e-password')).split(':');
const CLIENT_ID = argOf('--client-id', 'stand-e2e');

// ── 状态（内存，进程生命周期 = 一轮 E2E）──────────────────────────────────
/** state → { clientId, redirectUri, challenge, scope, expiresAt, code?, used } */
const authRequests = new Map();
/** interaction cookie 值 → { verified, verificationId, identifier } */
const interactions = new Map();
/** code → { state, clientId, redirectUri, expiresAt, used } */
const codes = new Map();
const TTL_MS = 10 * 60 * 1000;

const b64url = (buf) => Buffer.from(buf).toString('base64url');
const now = () => Date.now();
const sweep = () => {
  for (const [k, v] of authRequests) if (v.expiresAt < now()) authRequests.delete(k);
  for (const [k, v] of codes) if (v.expiresAt < now()) codes.delete(k);
  for (const [k, v] of interactions) if (v.expiresAt < now()) interactions.delete(k);
};

const log = (...a) => console.log(`[mock-idp ${new Date().toISOString()}]`, ...a);

// ── HTTP 骨架 ──────────────────────────────────────────────────────────────
const form = (req) => new Promise((resolve) => {
  let body = '';
  req.on('data', (c) => { body += c; });
  req.on('end', () => resolve(Object.fromEntries(new URLSearchParams(body))));
});
const json = (req) => new Promise((resolve) => {
  let body = '';
  req.on('data', (c) => { body += c; });
  req.on('end', () => { try { resolve(JSON.parse(body || '{}')); } catch { resolve({}); } });
});
const send = (res, status, headers, body) => { res.writeHead(status, headers); res.end(body); };
const jsonRes = (res, status, obj, headers = {}) =>
  send(res, status, { 'content-type': 'application/json', ...headers }, JSON.stringify(obj));
const cookieOf = (req, name) =>
  (req.headers.cookie || '').split(/;\s*/).find((c) => c.startsWith(`${name}=`))?.slice(name.length + 1);

/** 无签名 JWT（mock：decodeIdTokenClaims 只解 payload 段；签名面由部署租户真实链钉）。 */
const idTokenFor = (identifier) => {
  const header = b64url(JSON.stringify({ alg: 'none', typ: 'JWT' }));
  const payload = b64url(JSON.stringify({
    sub: `mock-${Buffer.from(identifier).toString('hex').slice(0, 16)}`,
    email: identifier.includes('@') ? identifier : `${identifier}@mock.local`,
    name: identifier,
    iss: `http://127.0.0.1:${PORT}`,
    aud: CLIENT_ID,
    iat: Math.floor(now() / 1000),
    exp: Math.floor(now() / 1000) + 3600,
  }));
  return `${header}.${payload}.mock`;
};

const loginPage = (state, error = '') => `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><title>mock Logto · 登录</title>
<style>body{font-family:system-ui;display:grid;place-items:center;height:100vh;margin:0;background:#10141d;color:#e8eaed}
form{background:#181c26;padding:32px;border-radius:12px;min-width:280px}
input{display:block;width:100%;box-sizing:border-box;margin:8px 0 16px;padding:10px;border-radius:8px;border:1px solid #3a3f4b;background:#0c0f16;color:inherit}
button{width:100%;padding:10px;border-radius:8px;border:0;background:#4c6fff;color:#fff;cursor:pointer}
.err{color:#ff7b7b;min-height:1.2em;font-size:13px}</style></head>
<body><form method="post" action="/mock/login">
<h2 style="margin:0 0 12px;font-size:16px">mock Logto · 本地隔离登录</h2>
<div class="err">${error}</div>
<label>账号<input name="username" autocomplete="username" required></label>
<label>密码<input name="password" type="password" autocomplete="current-password" required></label>
<input type="hidden" name="state" value="${state}">
<button type="submit">登录</button></form></body></html>`;

const server = createServer(async (req, res) => {
  sweep();
  const url = parse(req.url, true);
  const path = url.pathname;
  const cookies = [];
  try {
    // ── 授权端点（PKCE 参数全量真实校验）──────────────────────────────────
    if (req.method === 'GET' && path === '/oidc/auth') {
      const q = url.query;
      const bad = (why) => jsonRes(res, 400, { error: 'invalid_request', error_description: why });
      if (q.response_type !== 'code') return bad('response_type must be code');
      if (q.code_challenge_method !== 'S256') return bad('code_challenge_method must be S256');
      if (!q.code_challenge || !q.client_id || !q.redirect_uri || !q.state) return bad('missing required params');
      if (q.client_id !== CLIENT_ID) return bad('unknown client_id');
      if (!/^http:\/\/127\.0\.0\.1:\d+\/auth\/callback$/.test(String(q.redirect_uri))) {
        return bad('redirect_uri not in loopback allowlist');
      }
      authRequests.set(q.state, {
        clientId: q.client_id, redirectUri: q.redirect_uri, challenge: q.code_challenge,
        scope: q.scope || '', expiresAt: now() + TTL_MS, approved: false,
      });
      // 交互签与授权请求绑定（真 Logto 同形：authorize 建会话，Experience 链
      // 续用同一 cookie；headless 客户端 GET authorize 后再 PUT experience，
      // jar 里已有 cookie ⇒ 复用，不再新发）。
      let interaction = cookieOf(req, 'mock-interaction');
      if (!interaction || !interactions.has(interaction)) {
        interaction = randomUUID();
        interactions.set(interaction, { verified: false, expiresAt: now() + TTL_MS });
      }
      interactions.get(interaction).state = String(q.state);
      cookies.push(`mock-interaction=${interaction}; Path=/; HttpOnly; SameSite=Lax`);
      return send(res, 200, { 'content-type': 'text/html; charset=utf-8', 'set-cookie': cookies },
        loginPage(String(q.state)));
    }

    // ── 浏览器表单腿 ─────────────────────────────────────────────────────
    if (req.method === 'POST' && path === '/mock/login') {
      const f = await form(req);
      const authReq = authRequests.get(f.state);
      if (!authReq) return send(res, 400, { 'content-type': 'text/html; charset=utf-8' },
        '<p>state 已过期，请回到应用重新发起登录。</p>');
      if (f.username !== USER || f.password !== PASS) {
        return send(res, 200, { 'content-type': 'text/html; charset=utf-8' },
          loginPage(f.state, '账号或密码不对（本地 mock 账号见 runner 参数）。'));
      }
      authReq.approved = true;
      const code = randomBytes(24).toString('base64url');
      codes.set(code, { state: f.state, clientId: authReq.clientId, redirectUri: authReq.redirectUri,
        expiresAt: now() + TTL_MS, used: false });
      log('browser login ok →', authReq.redirectUri);
      return send(res, 302, { location: `${authReq.redirectUri}?code=${code}&state=${encodeURIComponent(f.state)}` }, '');
    }

    // ── Experience 链收口（两条腿共用：浏览器表单批 approved、headless 链
    //    interaction.verified；两者取其一即发码）─────────────────────────
    if (req.method === 'GET' && /^\/oidc\/auth\/[^/]+\/continue$/.test(path)) {
      const state = path.split('/')[3];
      const authReq = authRequests.get(state);
      const interaction = interactions.get(cookieOf(req, 'mock-interaction') || '');
      if (!authReq || !(authReq.approved || interaction?.verified)) {
        return jsonRes(res, 400, { error: 'interaction_not_approved' });
      }
      const code = randomBytes(24).toString('base64url');
      codes.set(code, { state, clientId: authReq.clientId, redirectUri: authReq.redirectUri,
        expiresAt: now() + TTL_MS, used: false });
      return send(res, 302, { location: `${authReq.redirectUri}?code=${code}&state=${encodeURIComponent(state)}` }, '');
    }

    // ── token 端点（RFC 7636 真校验）────────────────────────────────────
    if (req.method === 'POST' && path === '/oidc/token') {
      const f = await form(req);
      const grant = f.get ? f.get('grant_type') : f.grant_type;
      if (grant !== 'authorization_code') return jsonRes(res, 400, { error: 'unsupported_grant_type' });
      const rec = codes.get(f.get ? f.get('code') : f.code);
      if (!rec) return jsonRes(res, 400, { error: 'invalid_grant', error_description: 'unknown code' });
      if (rec.used) return jsonRes(res, 400, { error: 'invalid_grant', error_description: 'code replay' });
      const verifier = f.get ? f.get('code_verifier') : f.code_verifier;
      const expect = createHash('sha256').update(String(verifier)).digest('base64url');
      const authReq = authRequests.get(rec.state);
      if (!authReq || expect !== authReq.challenge) {
        return jsonRes(res, 400, { error: 'invalid_grant', error_description: 'PKCE verification failed' });
      }
      if ((f.get ? f.get('client_id') : f.client_id) !== rec.clientId ||
          (f.get ? f.get('redirect_uri') : f.redirect_uri) !== rec.redirectUri) {
        return jsonRes(res, 400, { error: 'invalid_grant', error_description: 'client/redirect mismatch' });
      }
      rec.used = true;
      authRequests.delete(rec.state);
      const identifier = USER.includes('@') ? USER : `${USER}@mock.local`;
      return jsonRes(res, 200, {
        access_token: `mock-at-${randomBytes(12).toString('hex')}`,
        token_type: 'Bearer',
        expires_in: 3600,
        scope: authReq.scope || 'openid email profile',
        refresh_token: `mock-rt-${randomBytes(12).toString('hex')}`,
        id_token: idTokenFor(identifier),
      });
    }

    // ── Experience API（headless 密码腿；交互 = cookie）─────────────────
    if (req.method === 'PUT' && path === '/api/experience') {
      const body = await json(req);
      if (body.interactionEvent !== 'SignIn') return jsonRes(res, 422, { error: 'unsupported interactionEvent' });
      // 会话已在 authorize 访问时建立（cookie 复用）；没有 cookie = 客户端没先走
      // authorize —— 与真 Logto 同判（401 interaction_required）。
      const interaction = cookieOf(req, 'mock-interaction');
      if (!interaction || !interactions.has(interaction)) {
        return jsonRes(res, 401, { error: 'interaction_required' });
      }
      return jsonRes(res, 204, {});
    }
    if (req.method === 'POST' && path === '/api/experience/verification/password') {
      const interaction = interactions.get(cookieOf(req, 'mock-interaction') || '');
      if (!interaction) return jsonRes(res, 401, { error: 'interaction_required' });
      const body = await json(req);
      const value = body.identifier?.value ?? '';
      // 与部署租户同形（LogtoPasswordFlow 方言钉，shape probes 2026-10-26）：
      // 凭据错 = **422 + code `session.invalid_credentials`**（反枚举：不漏账号
      // 存在性）。网关的 classifyFailure 只认 body 的 `code` 字段——首跑发 400
      // + {error} 被归为 Protocol ⇒ 502（实测：wrong-password 腿没走 InvalidCredentials 面）。
      if (value !== USER || body.password !== PASS) {
        log('experience password rejected for', value);
        return jsonRes(res, 422, { code: 'session.invalid_credentials', message: 'Invalid credentials' });
      }
      interaction.verificationId = `ver-${randomBytes(8).toString('hex')}`;
      interaction.identifier = value;
      return jsonRes(res, 200, { verificationId: interaction.verificationId });
    }
    if (req.method === 'POST' && path === '/api/experience/identification') {
      const interaction = interactions.get(cookieOf(req, 'mock-interaction') || '');
      const body = await json(req);
      if (!interaction || body.verificationId !== interaction.verificationId) {
        return jsonRes(res, 403, { error: 'verification_mismatch' });
      }
      interaction.verified = true;
      return jsonRes(res, 204, {});
    }
    if (req.method === 'POST' && path === '/api/experience/submit') {
      const interaction = interactions.get(cookieOf(req, 'mock-interaction') || '');
      if (!interaction?.verified) return jsonRes(res, 422, { error: 'identity_not_verified' });
      // redirectTo = 本交互对应授权请求的收口页（authorize 时绑定的 state）。
      const state = interaction.state;
      if (!state || !authRequests.has(state)) return jsonRes(res, 409, { error: 'no_pending_authorization' });
      return jsonRes(res, 200, { redirectTo: `http://127.0.0.1:${PORT}/oidc/auth/${state}/continue` });
    }

    if (req.method === 'GET' && path === '/.well-known/openid-configuration') {
      return jsonRes(res, 200, {
        issuer: `http://127.0.0.1:${PORT}`,
        authorization_endpoint: `http://127.0.0.1:${PORT}/oidc/auth`,
        token_endpoint: `http://127.0.0.1:${PORT}/oidc/token`,
        end_session_endpoint: `http://127.0.0.1:${PORT}/oidc/session/end`,
        response_types_supported: ['code'],
        code_challenge_methods_supported: ['S256'],
      });
    }

    if (req.method === 'GET' && path === '/mock/health') return jsonRes(res, 200, { ok: true, user: USER });

    // ── NebLink 设备注册汇聚点（LoginConvergence：密码腿/PKCE 腿换到票后都
    //    落 `POST /api/device/register`，shape = LogtoTransport.registerRequest
    //    {deviceId, deviceName, platform, machineFingerprint?} + Bearer 票 ⇒
    //    EnrollResponse {deviceToken, networkId}（NeblinkEnrollment.persist 消费
    //    面：deviceToken 必需、其余可省）。mock 只验 Bearer 非空（票的真伪由
    //    /oidc/token 的 RFC 7636 真校验背书），零真实凭据。 ──────────────────
    if (req.method === 'POST' && path === '/api/device/register') {
      const bearerToken = (req.headers.authorization || '').replace(/^Bearer\s+/i, '').trim();
      if (!bearerToken) return jsonRes(res, 401, { error: 'missing_bearer' });
      const body = await json(req);
      log('device register ok →', body.deviceId || '(no deviceId)');
      return jsonRes(res, 200, {
        deviceToken: `mock-device-token-${randomBytes(12).toString('base64url')}`,
        networkId: 'mock-network',
      });
    }

    jsonRes(res, 404, { error: 'not_found', path });
  } catch (e) {
    log('handler error', e?.stack || e);
    jsonRes(res, 500, { error: 'internal' });
  }
});

server.listen(PORT, '127.0.0.1', () => {
  log(`mock Logto IdP on http://127.0.0.1:${PORT} · account=${USER} · clientId=${CLIENT_ID}`);
});
const shutdown = () => { try { server.close(() => process.exit(0)); } catch { process.exit(0); } };
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);

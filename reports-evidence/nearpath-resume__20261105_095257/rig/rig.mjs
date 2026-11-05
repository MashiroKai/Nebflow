// rig.mjs — 本机近路（loopback nearpath）实测台 · 门户 ingress 面
//
// 拓扑（全部本机；生产 6138 / pid 41715 零接触）：
//
//   浏览器 ──https──▶ 本文件（"门户 ingress"，:7443，自签证书）
//                        │  · /api/portal/status         ← rig 自己答（= link-server 的门户面）
//                        │  · /api/portal/console-ticket ← rig 转 ConsoleTicketMint 帧给引擎
//                        │  · 其余 /api/**、/、/ws       ← 一律走 portal wire 隧道帧给引擎
//                        ▼
//                假中继（同进程，:18990，ws /api/device/relay-ws）
//                        ▲  引擎拨入（Bearer deviceToken）
//   隔离引擎（java，:6410，home=/tmp/nearpath-fire/rig-home，STAND_HOME 同值）
//                        ▲
//   环回探测代理（:6411）—— 浏览器闸③ 打 http://127.0.0.1:6411/api/health
//                        逐条记录（Origin / PNA 预检头）后转发给 :6410
//
// 为什么必须自建门户 ingress：ws.js 的四道闸只在**经中继加载的页面**上成立
// （闸① 读 /api/portal/status 的 boundHost.deviceId，只有门户面才有），而真实
// 门户是 https——混合内容 / Private-Network-Access 只有 https 页面能复现。
//
// 契约来源：src/main/scala/nebflow/neblink/PortalWireCodec.scala（v1.3 帧面）
//           src/test/resources/neblink/portal-wire-contract-v1.3.md
//
// 复用（后续节点做改前/改后 A/B）：RIG_WEB=<dir> 把静态资源（如 js/ws.js）换成
// 任意目录，命中即本地直出，未命中回落隧道（引擎 serve 的注入页）。
//
// 用法：
//   node rig.mjs                          # 独立起台（前台），Ctrl-C 收工
//   import { startRig } from './rig.mjs'  # gates.mjs 进程内起台

import { createServer as createHttpServer, request as httpRequest } from 'node:http';
import { createServer as createHttpsServer } from 'node:https';
import net from 'node:net';
import { WebSocketServer, WebSocket } from 'ws';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const ROOT = '/tmp/nearpath-fire';

export const DEFAULTS = {
  enginePort: 6410,
  relayPort: 18990,
  ingressPort: 7443,
  probePort: 6411,
  proxyPort: 7444,
  home: path.join(ROOT, 'rig-home'),
  engineSrc: path.join(ROOT, 'engine-src'),
  classpathFile: path.join(ROOT, 'classpath.txt'),
  certDir: path.join(ROOT, 'certs'),
  web: process.env.RIG_WEB || null,        // 静态资源覆盖目录（A/B 用）
  lanIp: process.env.RIG_LAN_IP || '',     // 非本机地址空间的 origin（PNA 轴）
  deviceId: 'dev-nearpath-fire-6410',
  tunnelPerIpCap: 200000,   // 台子放宽隧道预算（生产 1024/60s）
  tunnelTotalCap: 500000,   // 台子放宽隧道总额（生产 10240）
  verbose: true,
};

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.jpg': 'image/jpeg', '.ico': 'image/x-icon', '.woff2': 'font/woff2',
  '.woff': 'font/woff', '.ttf': 'font/ttf', '.map': 'application/json',
};

// 隧道帧里不放的 hop-by-hop 头（外加 accept-encoding：保持明文响应，便于取证）
const DROP_REQ_HEADERS = new Set([
  'connection', 'upgrade', 'proxy-connection', 'keep-alive', 'transfer-encoding',
  'te', 'trailer', 'expect', 'accept-encoding',
]);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export async function startRig(opts = {}) {
  const cfg = { ...DEFAULTS, ...opts };
  const log = (...a) => { if (cfg.verbose) console.log('[rig]', ...a); };

  const state = {
    enginePid: null,
    engineUp: false,
    relayConnected: false,
    modes: {
      portalStatus: 'ok',      // ok | http404 | no_bound_host
      loopbackInfo: 'engine',  // engine | http403 | port0 | probe_proxy | dead_port
      mint: 'ok',              // ok | http403 | no_url | no_ticket
      probe: 'pass',           // pass | pna_refuse | http500 | wrong_id   (仅 probe_proxy 时生效)
    },
    probeLog: [],
    // 隧道帧读数（resume 批新增·纯观测）：nf-file 走隧道腿时的分帧与 base64 膨胀，
    // 用来给「同一份文件：环回腿 vs 隧道腿」的 A/B 附上链路级证据（帧数/线上字节）。
    // 只累加计数，不改任何转发语义。
    frameStats: { bodyFramesDown: 0, bodyBytesB64: 0, httpRequests: 0, httpResponses: 0 },
    ingressLog: [],
    tunnelLog: [],
    engineStdout: '',
    mintedTickets: 0,
  };

  // ── 0. 隔离 home（只此一处）────────────────────────────────────────────────
  fs.rmSync(cfg.home, { recursive: true, force: true });
  fs.mkdirSync(path.join(cfg.home, 'neblink'), { recursive: true });
  fs.mkdirSync(path.join(cfg.home, 'logs'), { recursive: true });
  fs.writeFileSync(path.join(cfg.home, 'auth.json'), JSON.stringify('rig-file-token'));
  fs.writeFileSync(path.join(cfg.home, 'neblink', 'config.json'), JSON.stringify({
    enabled: true, syncIntervalSec: 45,
    neblinkServer: {
      url: `http://127.0.0.1:${cfg.relayPort}`,
      networkId: 'n-nearpath-rig', secret: 's-nearpath-rig',
      deviceToken: 'tok-nearpath-rig',
    },
  }, null, 2));
  const b64url = (s) => Buffer.from(s).toString('base64url');
  const forgedIdToken = (sub, name, email) =>
    `${b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }))}.` +
    `${b64url(JSON.stringify({ sub, name, email }))}.sig-not-verified-rig`;
  fs.writeFileSync(path.join(cfg.home, 'neblink', 'device.json'), JSON.stringify({
    serverUrl: `http://127.0.0.1:${cfg.relayPort}`,
    networkId: 'n-nearpath-rig',
    deviceId: cfg.deviceId,
    logto: { refreshToken: '', updatedAt: Date.now(), idToken: forgedIdToken('sub-rig-owner', 'RigOwner', 'rig@example.invalid') },
  }, null, 2));

  // ── 1. 假中继：HTTP（device/session 交换）+ WS（隧道）───────────────────────
  const pending = new Map();     // requestId -> handlers
  const mintWaiters = new Map(); // requestId -> resolve
  let engineSock = null;

  const relayHttp = createHttpServer((req, res) => {
    if (req.method === 'POST' && (req.url || '').startsWith('/api/device/session')) {
      req.on('data', () => {});
      req.on('end', () => {
        log('relay: device/session exchange');
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ token: 'sess-nearpath-rig', networkId: 'n-nearpath-rig', deviceId: cfg.deviceId, peers: [] }));
      });
      return;
    }
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end('{}');
  });
  const wss = new WebSocketServer({ noServer: true, handleProtocols: () => false });
  relayHttp.on('upgrade', (req, sock, head) => {
    const u = req.url || '';
    if (u.startsWith('/api/device/relay-ws')) {
      wss.handleUpgrade(req, sock, head, (ws) => wss.emit('connection', ws, req));
    } else { sock.destroy(); }
  });
  wss.on('connection', (ws, req) => {
    engineSock = ws;
    state.relayConnected = true;
    log('relay: engine tunnel connected', req.url);
    ws.on('close', () => { if (engineSock === ws) { engineSock = null; state.relayConnected = false; log('relay: engine tunnel closed'); } });
    ws.on('message', (raw) => {
      let m; try { m = JSON.parse(raw.toString()); } catch { return; }
      // 心跳：引擎每 10s 发 {"type":"ping"}，任意入向帧都算「对端活着」，但真中继
      // 会回 pong（NeblinkRelayTunnel 头注 :31）——不回 = 每 30s 一次
      // 「heartbeat timeout → reconnect」，会把正在跑的页面从隧道上掉下来。
      if (m.type === 'ping') { try { ws.send(JSON.stringify({ type: 'pong' })); } catch { /* */ } }
      if (m.type !== 'body_frame' && m.type !== 'ws_frame' && m.type !== 'ping' && m.type !== 'pong') {
        state.tunnelLog.push({ at: Date.now(), dir: 'up', type: m.type, requestId: m.requestId });
        if (state.tunnelLog.length > 500) state.tunnelLog.shift();
      }
      // 线上帧读数（纯观测）：入向 body_frame = 引擎→门户的响应体切片（base64）。
      if (m.type === 'body_frame') {
        state.frameStats.bodyFramesDown += 1;
        state.frameStats.bodyBytesB64 += (m.data ? m.data.length : 0);
      } else if (m.type === 'http_request') state.frameStats.httpRequests += 1;
      else if (m.type === 'http_response') state.frameStats.httpResponses += 1;
      if (m.type === 'ConsoleTicketResult' && mintWaiters.has(m.requestId)) {
        const r = mintWaiters.get(m.requestId); mintWaiters.delete(m.requestId); r(m);
      }
      const h = pending.get(m.requestId);
      if (!h) return;
      if (m.type === 'http_response') h.onHead?.(m);
      else if (m.type === 'body_frame') h.onBody?.(m);
      else if (m.type === 'ws_open_result') h.onOpenResult?.(m);
      else if (m.type === 'ws_frame') h.onWsFrame?.(m);
      else if (m.type === 'ws_close') h.onWsClose?.(m);
    });
  });
  relayHttp.listen(cfg.relayPort, '127.0.0.1');

  const sendFrame = (obj) => {
    if (!engineSock || engineSock.readyState !== WebSocket.OPEN) throw new Error('relay tunnel not connected');
    engineSock.send(JSON.stringify(obj));
  };

  /** 门户 → 引擎 的一次 HTTP 周期（http_request / http_response / body_frame）。 */
  function tunnelHttp({ method, path: p, headers, body }) {
    return new Promise((resolve, reject) => {
      const requestId = crypto.randomUUID();
      const acc = { head: null, chunks: [], bodyEnded: false };
      const timer = setTimeout(() => {
        if (pending.has(requestId)) { pending.delete(requestId); reject(new Error('tunnel http timeout')); }
      }, 30000);
      const finish = () => {
        if (!acc.head || !acc.bodyEnded) return;
        pending.delete(requestId);
        clearTimeout(timer);
        resolve({
          status: acc.head.status,
          headers: acc.head.headers || {},
          setCookie: acc.head.setCookie || [],
          body: Buffer.concat(acc.chunks),
        });
      };
      pending.set(requestId, {
        onHead: (m) => { acc.head = m; finish(); },
        onBody: (m) => {
          if (m.data) acc.chunks.push(Buffer.from(m.data, 'base64'));
          if (m.more === false) acc.bodyEnded = true;
          finish();
        },
      });
      try {
        sendFrame({ type: 'http_request', proto: 1, requestId, method, path: p, headers });
        if (body && body.length) {
          const CH = 64 * 1024;
          let seq = 0;
          for (let off = 0; off < body.length; off += CH) {
            const chunk = body.subarray(off, Math.min(off + CH, body.length));
            sendFrame({ type: 'body_frame', proto: 1, requestId, seq: seq++, more: off + CH < body.length, data: chunk.toString('base64') });
          }
        } else {
          // 契约义务：空体也要一帧终止帧（否则引擎侧首帧看门狗兜底）
          sendFrame({ type: 'body_frame', proto: 1, requestId, seq: 0, more: false, data: '' });
        }
      } catch (e) { pending.delete(requestId); clearTimeout(timer); reject(e); }
    });
  }

  /** 门户 → 引擎 的一次 WS 周期（ws_open / ws_frame / ws_close）。 */
  function tunnelWsOpen({ path: p, headers }) {
    return new Promise((resolve, reject) => {
      const requestId = crypto.randomUUID();
      const timer = setTimeout(() => {
        if (pending.has(requestId)) { pending.delete(requestId); reject(new Error('ws_open timeout')); }
      }, 15000);
      pending.set(requestId, {
        onOpenResult: (m) => {
          clearTimeout(timer);
          resolve({
            requestId,
            accepted: m.accepted,
            status: m.status,
            sendText: (s) => sendFrame({ type: 'ws_frame', proto: 1, requestId, kind: 'text', data: Buffer.from(s).toString('base64'), fin: true }),
            sendBinary: (b) => sendFrame({ type: 'ws_frame', proto: 1, requestId, kind: 'binary', data: Buffer.from(b).toString('base64'), fin: true }),
            close: (code = 1000, reason = '') => sendFrame({ type: 'ws_close', proto: 1, requestId, code, reason }),
            onFrame: null, onClose: null,
          });
        },
        onWsFrame: (m) => {
          const cb = pending.get(requestId)?.onFrame;
          if (cb) cb(m.kind, Buffer.from(m.data || '', 'base64'));
        },
        onWsClose: (m) => {
          const cb = pending.get(requestId)?.onClose;
          if (cb) cb(m.code, m.reason);
          pending.delete(requestId);
        },
      });
      try { sendFrame({ type: 'ws_open', proto: 1, requestId, path: p, headers }); }
      catch (e) { pending.delete(requestId); clearTimeout(timer); reject(e); }
    });
  }

  /** 门户铸票面（link-server `POST /api/portal/console-ticket` 的等价物）。 */
  function mintConsoleTicket() {
    return new Promise((resolve, reject) => {
      const requestId = crypto.randomUUID();
      const mintId = crypto.randomUUID();
      const timer = setTimeout(() => {
        if (mintWaiters.has(requestId)) { mintWaiters.delete(requestId); reject(new Error('mint timeout')); }
      }, 15000);
      mintWaiters.set(requestId, (m) => {
        clearTimeout(timer);
        if (m.error) reject(new Error(`mint error: ${m.error}`)); else resolve(m.ticket);
      });
      try { sendFrame({ type: 'ConsoleTicketMint', proto: 1, requestId, mintId, accountSub: 'sub-rig-owner', reqOrigin: 'portal' }); }
      catch (e) { mintWaiters.delete(requestId); clearTimeout(timer); reject(e); }
    });
  }

  // ── 2. 隔离引擎（直启 java：路径含空格 ⇒ 不能经 shell 拼 -cp）───────────────
  if (!fs.existsSync(cfg.classpathFile)) throw new Error(`classpath file missing: ${cfg.classpathFile}`);
  const cp = fs.readFileSync(cfg.classpathFile, 'utf8').trim();
  if (!cp || cp.split(':').length < 20) throw new Error(`classpath looks wrong (entries=${cp.split(':').length})`);
  const childEnv = { ...process.env, STAND_HOME: cfg.home };
  // CORS 白名单 = 「门户 origin」（HealthRoutes.allowedProbeOrigins = brand portalUrl
  // + Branding.env("PORTAL_URL") + serverUrl）。生产的门户 origin 是
  // https://mystand.space（brand.conf，默认已在名单里）；rig 再把自己这个门户
  // ingress 的 origin 加进去（STAND_PORTAL_URL，envPrefix=STAND 双前缀读）。
  // 语义与生产一一对应：放行的依然**只有那一个门户 origin**，不是 `*`。
  childEnv.STAND_PORTAL_URL = `https://${cfg.lanIp || '127.0.0.1'}:${cfg.ingressPort}`;
  delete childEnv.NEBFLOW_PORTAL_URL;
  // 隧道面每 IP 预算：生产默认 1024/60s（ConnGuardConfig），而控制台**一次冷加载**
  // 就有 ~800+ 个静态资源请求，台子短时间要连跑二十多轮 ⇒ 必然撞 tunnel-per-ip-cap
  // （引擎日志可见 429）。这里只放宽预算，不放宽任何判据（闸①-④ 全在浏览器侧与
  // 既有端点上，与本旋钮无关）。真门户的同一读数在 FINDINGS 的「局限」里如实登记。
  childEnv.STAND_GATEWAY_CONN_GUARD_TUNNEL_PER_IP_CAP = String(cfg.tunnelPerIpCap);
  childEnv.STAND_GATEWAY_CONN_GUARD_TUNNEL_TOTAL_CAP = String(cfg.tunnelTotalCap);
  const engine = spawn('java', [
    '-Dnebflow.start.foreground=true', '-cp', cp, 'nebflow.Main',
    '--home', cfg.home, '--port', String(cfg.enginePort), '--no-browser', 'start',
  ], { cwd: cfg.engineSrc, env: childEnv, stdio: ['ignore', 'pipe', 'pipe'], detached: true });
  state.enginePid = engine.pid;
  const grab = (d) => { state.engineStdout = (state.engineStdout + d.toString()).slice(-4_000_000); };
  engine.stdout.on('data', grab);
  engine.stderr.on('data', grab);
  log('engine spawned pid=', engine.pid, 'port=', cfg.enginePort, 'home=', cfg.home);

  const killEngine = () => {
    if (!state.enginePid) return;
    // 只按自己 spawn 的 pid（进程组首）精确收杀——绝不碰生产 41715 / 6138
    try { process.kill(-state.enginePid, 'SIGKILL'); } catch { try { process.kill(state.enginePid, 'SIGKILL'); } catch { /* already gone */ } }
    state.enginePid = null;
  };

  async function waitEngineHealthy(timeoutMs) {
    const t0 = Date.now();
    while (Date.now() - t0 < timeoutMs) {
      try {
        const r = await fetch(`http://127.0.0.1:${cfg.enginePort}/api/health`, { signal: AbortSignal.timeout(3000) });
        if (r.ok) { state.engineUp = true; return true; }
      } catch { /* not up yet */ }
      await sleep(1000);
    }
    return false;
  }
  const healthy = await waitEngineHealthy(opts.healthTimeoutMs || 300000);
  if (!healthy) { killEngine(); throw new Error('engine never became healthy'); }
  log('engine healthy on :' + cfg.enginePort);

  for (let i = 0; i < 60 && !state.relayConnected; i++) await sleep(500);
  log('relay connected =', state.relayConnected);

  // 门户面报的 boundHost.deviceId 必须 = 引擎自己认的身份：闸③ 拿环回 /api/health 的
  // deviceId 与它比对，任一不等即 device_mismatch。引擎身份是**机器派生**的
  // （不在 device.json 里，实测 device.json 写什么都被覆盖）⇒ 启动后读真实值。
  try {
    const h = await (await fetch(`http://127.0.0.1:${cfg.enginePort}/api/health`, { signal: AbortSignal.timeout(5000) })).json();
    if (h && typeof h.deviceId === 'string' && h.deviceId) { cfg.deviceId = h.deviceId; log('engine deviceId =', cfg.deviceId); }
  } catch { log('WARN: could not read engine deviceId'); }

  // 与引擎 probeCorsHeaders（HealthRoutes.scala:238-255）逐头一致的应答头。
  // allowed = rig 的门户 origin 白名单（= 生产白名单的神秘等价物）。
  const probeAllowedOrigins = new Set([`https://127.0.0.1:${cfg.ingressPort}`, 'https://mystand.space', 'https://link.mystand.space']);
  function probeCorsHeaders(origin) {
    const o = (origin || '').trim().replace(/\/$/, '');
    if (!o || !probeAllowedOrigins.has(o)) return {};
    return {
      'Access-Control-Allow-Origin': o,
      'Vary': 'Origin',
      'Access-Control-Allow-Methods': 'GET, OPTIONS',
      'Access-Control-Allow-Headers': 'content-type',
      'Access-Control-Allow-Private-Network': 'true',
      'Access-Control-Max-Age': '600',
    };
  }

  // ── 3. 环回探测代理（:6411）——闸③ 的观测点 ────────────────────────────────
  const probeServer = createHttpServer((req, res) => {
    const h = req.headers;
    state.probeLog.push({
      at: new Date().toISOString(),
      method: req.method, url: req.url,
      origin: h.origin || null,
      acrm: h['access-control-request-method'] || null,
      acrh: h['access-control-request-headers'] || null,
      acrpn: h['access-control-request-private-network'] || null,
      ua: (h['user-agent'] || '').slice(0, 50),
      mode: state.modes.probe,
    });
    const mode = state.modes.probe;
    if (mode === 'pna_refuse') {
      res.writeHead(req.method === 'OPTIONS' ? 204 : 403, { 'Access-Control-Allow-Origin': h.origin || '*' });
      return res.end();
    }
    if (mode === 'http500') {
      // 带 CORS 头的 500：让浏览器**读得到**状态码（否则会被 CORS 拦成 probe_unreachable）
      res.writeHead(500, { 'Content-Type': 'text/plain', ...probeCorsHeaders(h.origin) });
      return res.end('rig: forced 500');
    }
    if (mode === 'wrong_id') {
      const b = Buffer.from(JSON.stringify({ status: 'ok', deviceId: 'dev-OTHER-gateway' }));
      res.writeHead(200, {
        'Content-Type': 'application/json', 'Content-Length': b.length,
        'Access-Control-Allow-Origin': h.origin || '*',
        'Access-Control-Allow-Private-Network': 'true',
      });
      return res.end(b);
    }
    // pass：原样转发给引擎（保留全部头，回程头与体照抄），并**在这里补 CORS/PNA 头**。
    // 为什么补：生产上这一面是引擎直接应答浏览器（probeCorsHeaders，
    // HealthRoutes.scala:238-255：环回腿 ∧ origin 在白名单）。经 rig 转发后 socket
    // 仍来自 127.0.0.1（环回腿语义保住），但 origin 白名单按引擎进程的 Branding 算，
    // 而引擎看到的是 rig 的 origin ⇒ 这里复刻同一道闸：
    //   · origin 不在白名单 ⇒ 零 CORS 头（生产降级语义：浏览器读不到 = probe_unreachable）
    //   · 命中 ⇒ ACAO/Vary/Methods/Headers/ACAPN/Max-Age 六件套（与引擎逐头一致）
    if (req.method === 'OPTIONS') {
      res.writeHead(204, probeCorsHeaders(h.origin));
      return res.end();
    }
    const fwd = {};
    for (const [k, v] of Object.entries(h)) if (!DROP_REQ_HEADERS.has(k)) fwd[k] = Array.isArray(v) ? v.join(', ') : v;
    const cors = probeCorsHeaders(h.origin);
    const preq = httpRequest({ host: '127.0.0.1', port: cfg.enginePort, method: req.method, path: req.url, headers: fwd }, (pres) => {
      // 引擎侧若已带同名头（本例不会：origin 不在引擎白名单）则去重，防重复值
      const strip = new Set(Object.keys(cors).map((k) => k.toLowerCase()));
      const head = {};
      for (const [k, v] of Object.entries(pres.headers)) if (!strip.has(k.toLowerCase())) head[k] = v;
      res.writeHead(pres.statusCode || 502, { ...head, ...cors });
      pres.pipe(res);
    });
    preq.on('error', () => { try { res.writeHead(502); res.end('rig probe proxy: engine unreachable'); } catch { /* */ } });
    req.pipe(preq);
  });
  probeServer.listen(cfg.probePort); // 双栈：127.0.0.1 与 ::1（localhost）都收

  // ── 3b. 转发代理（:7444）——让浏览器看到**生产门户 origin** ─────────────────
  // 本机 100.64/10 那个地址上起不了监听（实测：bind 成功但外部读到空应答，退出码 52），
  // 而 Chromium 的 --host-resolver-rules 在本机 Playwright 里不生效（实测四条拼法全被
  // 真实 DNS 覆盖）。可用的替代 = 代理：mystand.space 的 CONNECT 一律落到本机
  // ingress（自签证书 SAN 已含 DNS:mystand.space），于是页面 origin 就是生产 origin
  // https://mystand.space；而 127.0.0.1/localhost 的 CONNECT 原样直连（闸③ 的探测
  // 因此仍是「浏览器 → 本机端口」的真链路，PNA 预检不被代理吞掉）。
  const proxyServer = createHttpServer((req, res) => {
    // 明文 http 的绝对 URI 形态。**本机目标必须原样直连**（闸③ 的环回探测就是
    // http://127.0.0.1:<port>/api/health：把它转到 ingress 会把探测面狸猫换太子，
    // 让 CORS 证据全部失真）。
    const abs = /^http:\/\/([^/]+)(\/.*)?$/.exec(req.url || '');
    let targetHost = '127.0.0.1', targetPort = cfg.ingressPort, p = req.url || '/';
    if (abs) {
      const hp = abs[1];
      p = abs[2] || '/';
      const [h, port] = hp.split(':');
      const isLocal = h === '127.0.0.1' || h === 'localhost' || h === '[::1]';
      if (isLocal) { targetHost = h.replace(/^\[|\]$/g, ''); targetPort = Number(port || 80); }
    }
    const fwd = { ...req.headers };
    const preq = httpRequest({ host: targetHost, port: targetPort, method: req.method, path: p, headers: fwd }, (pres) => {
      res.writeHead(pres.statusCode || 502, pres.headers);
      pres.pipe(res);
    });
    preq.on('error', () => { try { res.writeHead(502); res.end('rig proxy: upstream unreachable'); } catch { /* */ } });
    req.pipe(preq);
  });
  proxyServer.on('connect', (req, clientSock, head) => {
    const [hRaw, pRaw] = (req.url || '').split(':');
    const h = (hRaw || '').replace(/^\[|\]$/g, '');
    const local = h === '127.0.0.1' || h === 'localhost' || h === '::1';
    const dest = local ? { host: h, port: Number(pRaw || 443) } : { host: '127.0.0.1', port: cfg.ingressPort };
    const upstream = net.connect(dest, () => {
      clientSock.write('HTTP/1.1 200 Connection Established\r\n\r\n');
      if (head && head.length) upstream.write(head);
      upstream.pipe(clientSock);
      clientSock.pipe(upstream);
    });
    upstream.on('error', () => { try { clientSock.end('HTTP/1.1 502 Bad Gateway\r\n\r\n'); } catch { /* */ } });
    clientSock.on('error', () => { try { upstream.destroy(); } catch { /* */ } });
  });
  proxyServer.listen(cfg.proxyPort, '127.0.0.1');

  // ── 4. 门户 ingress（https）────────────────────────────────────────────────
  const tls = {
    key: fs.readFileSync(path.join(cfg.certDir, 'rig-key.pem')),
    cert: fs.readFileSync(path.join(cfg.certDir, 'rig-cert.pem')),
  };

  function json(res, status, obj) {
    const b = Buffer.from(JSON.stringify(obj));
    res.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': b.length, 'Cache-Control': 'no-store' });
    res.end(b);
  }

  /** 静态覆盖目录（RIG_WEB）命中即直出；否则回落隧道。 */
  function serveOverride(res, urlPath) {
    if (!cfg.web) return false;
    const rel = decodeURIComponent(urlPath === '/' ? '/index.html' : urlPath);
    const file = path.normalize(path.join(cfg.web, rel));
    if (!file.startsWith(path.resolve(cfg.web))) return false;
    try {
      if (!fs.statSync(file).isFile()) return false;
      const body = fs.readFileSync(file);
      res.writeHead(200, {
        'Content-Type': MIME[path.extname(file)] || 'application/octet-stream',
        'Content-Length': body.length, 'Cache-Control': 'no-store',
      });
      res.end(body);
      return true;
    } catch { return false; }
  }

  async function handleIngress(req, res) {
    const u = new URL(req.url, `https://${req.headers.host || 'rig.invalid'}`);
    const p = u.pathname;
    const raw = [];
    req.on('data', (c) => raw.push(c));
    await new Promise((r) => { req.on('end', r); req.on('close', r); });
    const body = Buffer.concat(raw);

    state.ingressLog.push({ at: Date.now(), method: req.method, path: p + (u.search || ''), cookie: !!req.headers.cookie });
    if (state.ingressLog.length > 500) state.ingressLog.shift();

    // ── rig 控制面（非门户契约）──
    if (p === '/rig/status') {
      return json(res, 200, {
        engineUp: state.engineUp, enginePid: state.enginePid,
        relayConnected: state.relayConnected, deviceId: cfg.deviceId,
        modes: state.modes, mintedTickets: state.mintedTickets,
        probeLog: state.probeLog, ingressLog: state.ingressLog.slice(-80),
        tunnelLog: state.tunnelLog.slice(-80),
        enginePort: cfg.enginePort, ingressPort: cfg.ingressPort, probePort: cfg.probePort,
      });
    }
    if (p === '/rig/ticket') {
      try { const t = await mintConsoleTicket(); state.mintedTickets++; return json(res, 200, { ticket: t }); }
      catch (e) { return json(res, 500, { error: String(e) }); }
    }
    if (p === '/rig/modes' && req.method === 'POST') {
      try {
        Object.assign(state.modes, JSON.parse(body.toString() || '{}'));
        log('modes →', JSON.stringify(state.modes));
        return json(res, 200, state.modes);
      } catch (e) { return json(res, 400, { error: String(e) }); }
    }
    if (p === '/rig/engine-log') {
      const tail = Number(u.searchParams.get('tail') || 400);
      const comb = readEngineLog(cfg, state);
      const txt = comb.split('\n').slice(-tail).join('\n');
      res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' });
      return res.end(txt);
    }
    if (p === '/rig/engine-health') {
      try {
        const r = await fetch(`http://127.0.0.1:${cfg.enginePort}/api/health`, { signal: AbortSignal.timeout(3000) });
        const t = await r.text();
        res.writeHead(200, { 'Content-Type': 'application/json' });
        return res.end(t);
      } catch (e) { return json(res, 502, { error: String(e) }); }
    }
    if (p === '/rig/reset-probe-log' && req.method === 'POST') {
      state.probeLog.length = 0; state.ingressLog.length = 0; state.tunnelLog.length = 0;
      return json(res, 200, { ok: true });
    }

    // ── 门户面（link-server 等价物；rig 自己答，不进隧道）──
    if (p === '/api/portal/status') {
      const m = state.modes.portalStatus;
      if (m === 'http404') return json(res, 404, {});
      if (m === 'no_bound_host') return json(res, 200, { boundHost: null });
      return json(res, 200, { boundHost: { deviceId: cfg.deviceId }, deviceId: cfg.deviceId });
    }
    if (p === '/api/portal/console-ticket' && req.method === 'POST') {
      const m = state.modes.mint;
      if (m === 'http403') return json(res, 403, { error: 'rig forced 403' });
      if (m === 'no_url') return json(res, 200, {});
      if (m === 'no_ticket') return json(res, 200, { consoleUrl: 'https://mystand.space/portal' });
      try {
        const t = await mintConsoleTicket();
        state.mintedTickets++;
        return json(res, 200, { consoleUrl: `https://mystand.space/portal?ticket=${encodeURIComponent(t)}`, expiresInSec: 120 });
      } catch (e) { return json(res, 502, { error: String(e) }); }
    }

    // ── 闸② 的人工拦截面（便于逐闸定分支，不必改引擎）──
    if (p === '/api/health/loopback-info' && state.modes.loopbackInfo !== 'engine') {
      const m = state.modes.loopbackInfo;
      if (m === 'http403') return json(res, 403, { error: 'rig forced 403' });
      if (m === 'port0') return json(res, 200, { port: 0 });
      if (m === 'dead_port') return json(res, 200, { port: cfg.probePort + 1000 });
      if (m === 'probe_proxy') return json(res, 200, { port: cfg.probePort });
    }

    // ── 静态覆盖（A/B），未命中回落隧道 ──
    if ((req.method === 'GET' || req.method === 'HEAD') && !p.startsWith('/api/') && !p.startsWith('/ws')) {
      if (serveOverride(res, p)) return;
    }

    // ── 其余一律走 portal wire 隧道（= 真实门户的转发行）──
    const fwdHeaders = {};
    for (const [k, v] of Object.entries(req.headers)) {
      if (DROP_REQ_HEADERS.has(k)) continue;
      fwdHeaders[k] = Array.isArray(v) ? v.join(', ') : v;
    }
    try {
      const out = await tunnelHttp({ method: req.method, path: p + (u.search || ''), headers: fwdHeaders, body });
      const head = { ...out.headers };
      if (out.setCookie && out.setCookie.length) head['set-cookie'] = out.setCookie;
      res.writeHead(out.status, head);
      res.end(out.body);
    } catch (e) {
      res.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('rig ingress: tunnel leg failed: ' + String(e));
    }
  }

  const ingress = createHttpsServer(tls, (req, res) => {
    handleIngress(req, res).catch((e) => {
      try { res.writeHead(500, { 'Content-Type': 'text/plain' }); res.end('rig error: ' + String(e)); } catch { /* closed */ }
    });
  });

  // 控制台 WS：浏览器 ↔ 隧道 ws_open / ws_frame
  const ingressWss = new WebSocketServer({ noServer: true });
  ingress.on('upgrade', (req, sock, head) => {
    if (!(req.url || '').startsWith('/ws')) { sock.destroy(); return; }
    ingressWss.handleUpgrade(req, sock, head, async (bws) => {
      const fwdHeaders = {};
      for (const [k, v] of Object.entries(req.headers)) {
        if (DROP_REQ_HEADERS.has(k)) continue;
        fwdHeaders[k] = Array.isArray(v) ? v.join(', ') : v;
      }
      let cycle;
      try { cycle = await tunnelWsOpen({ path: req.url, headers: fwdHeaders }); }
      catch (e) { log('ws_open failed:', String(e)); try { bws.close(1011, 'tunnel ws_open failed'); } catch { /* */ } return; }
      if (!cycle.accepted) {
        log('ws_open refused by engine, status=', cycle.status);
        try { bws.close(1008, 'engine refused ws'); } catch { /* */ }
        return;
      }
      cycle.onFrame = (kind, buf) => { try { bws.send(kind === 'text' ? buf.toString('utf8') : buf); } catch { /* */ } };
      cycle.onClose = (code, reason) => { try { bws.close(code === 1005 ? 1000 : code, reason); } catch { /* */ } };
      bws.on('message', (data, isBinary) => {
        const buf = Buffer.isBuffer(data) ? data : Buffer.from(data);
        try { if (isBinary) cycle.sendBinary(buf); else cycle.sendText(buf.toString('utf8')); } catch { /* */ }
      });
      bws.on('close', () => { try { cycle.close(1000, 'browser closed'); } catch { /* */ } });
    });
  });

  await new Promise((r) => ingress.listen(cfg.ingressPort, r));
  log('ingress https on :' + cfg.ingressPort, cfg.web ? `(web override: ${cfg.web})` : '(static: engine via tunnel)');

  const stop = async () => {
    killEngine();
    try { probeServer.close(); } catch { /* */ }
    try { proxyServer.close(); } catch { /* */ }
    try { ingress.close(); } catch { /* */ }
    try { relayHttp.close(); } catch { /* */ }
  };

  return {
    cfg, state, stop, killEngine,
    deviceId: cfg.deviceId,
    origins: {
      local: `https://127.0.0.1:${cfg.ingressPort}`,
      portal: 'https://mystand.space',  // 浏览器经 proxy:7444 → 本机 ingress（生产 origin 同源）
      lan: cfg.lanIp ? `https://${cfg.lanIp}:${cfg.ingressPort}` : null,
      proxy: `http://127.0.0.1:${cfg.proxyPort}`,
    },
  };
}

/** 引擎日志（home/logs/stand.log）+ stdout 合并读取。 */
export function readEngineLog(cfg, state, bytes = 400000) {
  let file = '';
  try {
    const p = path.join(cfg.home, 'logs', 'stand.log');
    const st = fs.statSync(p);
    const fd = fs.openSync(p, 'r');
    const start = Math.max(0, st.size - bytes);
    const buf = Buffer.alloc(st.size - start);
    fs.readSync(fd, buf, 0, buf.length, start);
    fs.closeSync(fd);
    file = buf.toString('utf8');
  } catch { /* no log yet */ }
  return file + '\n---- stdout ----\n' + (state.engineStdout || '').slice(-bytes);
}

// 独立起台（前台）
if (import.meta.url === `file://${process.argv[1]}`) {
  const rig = await startRig();
  console.log('[rig] ready. origins:', rig.origins);
  console.log('[rig] control: GET /rig/status  GET /rig/ticket  POST /rig/modes');
  const bye = async () => { console.log('[rig] shutting down (own engine pid only)'); await rig.stop(); process.exit(0); };
  process.on('SIGINT', bye); process.on('SIGTERM', bye);
}

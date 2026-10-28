// portal-identity-e2e-isolated.mjs — 门户身份透传批·隔离引擎真浏览器 e2e
// （方案 A owner 判定 + 方案 B 三小刀；在产引擎 pid 70481/网关 6138 零接触）。
//
// 拓扑：
//   隔离引擎  http://localhost:8687  home=/tmp/nb-portal-identity/home
//     · neblink/config.json enabled=true + neblinkServer.url= fake relay
//     · neblink/device.json 凭据（锻造 id_token：sub=sub-real-owner, name=Mashiro）
//   假中继（本进程内 WS 服务）ws://127.0.0.1:18999/api/device/relay-ws
//     · 收引擎拨入（Bearer tok-e2e），下发 ConsoleTicketMint 帧（门户铸票伪造面，
//       PortalWireCodec JSON 帧形，proto=1）→ 收 ConsoleTicketResult 取票
//   真浏览器（chromium，Safari UA）经 `?ticket=` 兑换 → relay-mint 会话
//     （accountSub=锻造 sub）→ 页面真打用户消息 → 引擎侧 Device provenance 日志判词。
//
// 断言（全部引擎侧日志/存储读数，截图进 .nebflow/evidence/）：
//   A-owner   owner sub 会话 ⇒ provenance 判词 'Mashiro · Safari（经官网）' (enrolled=false, authenticated=true)
//   A-other   非 owner sub 会话 ⇒ authenticated=false + 默认名兜底 '未登记-Mac Safari'
//   B①②      直连腿页面近路 miss 诊断键（portal_status_http_404）可见
//   B③        指纹不匹配 ⇒ 顶栏「刷新以启用新版」提示条出现
import { chromium } from '@playwright/test';
import { WebSocketServer, WebSocket } from 'ws';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const HOME = '/tmp/nb-portal-identity/home';
const ENGINE_PORT = 8687;
const RELAY_PORT = 18999;
const BASE = `http://localhost:${ENGINE_PORT}`;
const LOG = path.join(HOME, 'logs', 'stand.log');
const SHOT = path.join(ROOT, '.nebflow', 'evidence', '20261028_portal-identity-passthrough');
fs.mkdirSync(SHOT, { recursive: true });

const OWNER_SUB = 'sub-real-owner-e2e';
const OTHER_SUB = 'sub-someone-else-e2e';
const ENGINE_DEVICE_TOKEN = 'tok-e2e-relay';

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const b64url = (s) => Buffer.from(s).toString('base64url');
const forgedIdToken = (sub, name, email) =>
  `${b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }))}.` +
  `${b64url(JSON.stringify({ sub, name, email }))}.sig-not-verified-e2e`;

// ── 1. 隔离 home ────────────────────────────────────────────────────────────
fs.rmSync(HOME, { recursive: true, force: true });
fs.mkdirSync(path.join(HOME, 'neblink'), { recursive: true });
fs.mkdirSync(path.join(HOME, 'logs'), { recursive: true });
fs.writeFileSync(path.join(HOME, 'auth.json'), JSON.stringify('e2e-file-token'));
fs.writeFileSync(
  path.join(HOME, 'neblink', 'config.json'),
  JSON.stringify({ enabled: true, syncIntervalSec: 45, neblinkServer: { url: `http://127.0.0.1:${RELAY_PORT}`, networkId: 'n-e2e', secret: 's-e2e', deviceToken: ENGINE_DEVICE_TOKEN } }, null, 2)
);
fs.writeFileSync(
  path.join(HOME, 'neblink', 'device.json'),
  JSON.stringify({ serverUrl: `http://127.0.0.1:${RELAY_PORT}`, networkId: 'n-e2e', deviceId: 'dev-e2e-portal-id', logto: { refreshToken: '', updatedAt: Date.now(), idToken: forgedIdToken(OWNER_SUB, 'Mashiro', 'kaimashiro@qq.com') } }, null, 2)
);
console.log('[setup] isolated home ready at', HOME);

// ── 2. 假中继（ConsoleTicketMint 下发 + ConsoleTicketResult 收票）───────────
// HTTP 面：POST /api/device/session（deviceToken → session token 交换——隧道
// Bearer 的来源，缺失 ⇒ 引擎走「无 token 安静梯」永不拨号）。WS 面：升级
// /api/device/relay-ws ⇒ 引擎隧道接入 ⇒ 下发 mint 帧。
import { createServer } from 'node:http';
const relayHttp = createServer((req, res) => {
  if (req.method === 'POST' && req.url === '/api/device/session') {
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      console.log('[fake-relay] device/session exchange <-', body.slice(0, 120));
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ token: 'sess-e2e-1', networkId: 'n-e2e', deviceId: 'dev-e2e-portal-id', peers: [] }));
    });
    return;
  }
  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.end('{}');
});
const wss = new WebSocketServer({ noServer: true });
relayHttp.on('upgrade', (req, sock, head) => {
  const url = req.url || '';
  if (url.startsWith('/api/device/relay-ws')) {
    wss.handleUpgrade(req, sock, head, (ws) => wss.emit('connection', ws, req));
  } else {
    sock.destroy();
  }
});
let engineSock = null;
const pendingMints = new Map(); // requestId -> resolve
wss.on('connection', (ws) => {
  engineSock = ws;
  console.log('[fake-relay] engine tunnel connected');
  ws.on('message', (raw) => {
    let m; try { m = JSON.parse(raw.toString()); } catch { return; }
    if (m.type === 'ConsoleTicketResult' && pendingMints.has(m.requestId)) {
      pendingMints.get(m.requestId)(m);
      pendingMints.delete(m.requestId);
    }
    // 其余帧（心跳/注册类）静默忽略——本假中继只服务铸票一帧。
  });
});
relayHttp.listen(RELAY_PORT, '127.0.0.1');

/** 门户铸票伪造面：下发 mint 帧 → 等引擎回票（设计稿跳②的引擎侧对端）。 */
function mintTicket(accountSub) {
  return new Promise((resolve, reject) => {
    const trySend = (attempt = 0) => {
      if (engineSock && engineSock.readyState === WebSocket.OPEN) {
        const requestId = crypto.randomUUID();
        const mintId = crypto.randomUUID();
        pendingMints.set(requestId, (res) =>
          res.error ? reject(new Error(`mint error: ${res.error}`)) : resolve(res.ticket)
        );
        engineSock.send(JSON.stringify({ type: 'ConsoleTicketMint', proto: 1, requestId, mintId, accountSub, reqOrigin: 'portal' }));
        setTimeout(() => { if (pendingMints.has(requestId)) { pendingMints.delete(requestId); reject(new Error('mint timeout')); } }, 15000);
      } else if (attempt < 40) setTimeout(() => trySend(attempt + 1), 500);
      else reject(new Error('engine tunnel never connected'));
    };
    trySend();
  });
}

// ── 3. 隔离引擎（两段式：export classpath → 直启 java；进程组整体收杀）──────
// 为什么不经 `sbt runMain`：本仓路径含空格（"Claude code"），fork JVM 的 -cp
// 参数经 shell 拼接时空格截断 ⇒ ClassNotFoundException。export 的 classpath 走
// spawn argv 数组直传 java，零引号环节。
function exec(cmd, args) {
  return new Promise((resolve, reject) => {
    const p = spawn(cmd, args, { cwd: ROOT });
    let out = '';
    p.stdout.on('data', (d) => { out += d.toString(); });
    p.stderr.on('data', (d) => { out += d.toString(); });
    p.on('close', (code) => (code === 0 ? resolve(out) : reject(new Error(`${cmd} exit ${code}: ${out.slice(-400)}`))));
  });
}

let engine = null;
try {
  const cpOut = await exec('sbt', ['-batch', 'export Compile/fullClasspath']);
  // 锚定应用侧 classpath：本项目应用编译产物 = target/scala-3.5.2/classes（build
  // 定义自身是 scala-2.12——它的 classpath 行也会出现在某些 reload 输出里，必须
  // 排除）。真行 ~10k 字符/76 项；仍以项数卫哨兜底。
  const candidates = cpOut
    .split('\n')
    .filter((l) => l.includes('/target/scala-3') && l.includes('/classes:') && l.length > 1000);
  const cp = candidates.sort((a, b) => b.length - a.length)[0];
  if (!cp || cp.split(':').length < 20) {
    fs.writeFileSync('/tmp/nb-portal-identity-cpout-dump.txt', cpOut);
    throw new Error(`classpath parse failed (entries=${cp ? cp.split(':').length : 0}) — full output dumped to /tmp/nb-portal-identity-cpout-dump.txt`);
  }
  console.log('[setup] classpath exported (entries=', cp.split(':').length, ')');
  engine = spawn(
    'java',
    ['-Dnebflow.start.foreground=true', '-cp', cp, 'nebflow.Main', '--home', HOME, '--port', String(ENGINE_PORT), '--no-browser', 'start'],
    { cwd: ROOT, stdio: ['ignore', 'pipe', 'pipe'], detached: true }
  );
} catch (e) {
  console.error('[setup] engine launch prep failed:', String(e).slice(0, 300));
  process.exit(1);
}
engine.stdout.on('data', (d) => process.stdout.write(`[engine] ${d}`));
engine.stderr.on('data', (d) => process.stderr.write(`[engine!] ${d}`));

async function waitEngineHealthy(timeoutMs = 300000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    try {
      const r = await fetch(`${BASE}/api/health`);
      if (r.ok) return true;
    } catch { /* not up yet */ }
    await sleep(2000);
  }
  return false;
}

// ── 4. 日志判词读取（日志文件 + 引擎 stdout 双源——logback 路径漂移的保险）──
let engineStdout = '';
engine.stdout.on('data', (d) => {
  const s = d.toString();
  if (engineStdout.length > 4000000) engineStdout = engineStdout.slice(-2000000);
  engineStdout += s;
});
function logTail(bytes = 200000) {
  try {
    const st = fs.statSync(LOG);
    const fd = fs.openSync(LOG, 'r');
    const start = Math.max(0, st.size - bytes);
    const buf = Buffer.alloc(st.size - start);
    fs.readSync(fd, buf, 0, buf.length, start);
    fs.closeSync(fd);
    return buf.toString('utf8');
  } catch { return ''; }
}
const combinedLog = (bytes = 200000) => logTail(bytes) + '\n' + engineStdout.slice(-bytes);
const waitLog = async (needle, timeoutMs = 20000) => {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    if (combinedLog().includes(needle)) return true;
    await sleep(500);
  }
  return false;
};

// ── 5. 主流程 ───────────────────────────────────────────────────────────────
let engineHealthy = false;
try {
  engineHealthy = await waitEngineHealthy();
  ok('隔离引擎 :8687 启动可达（在产 6138 零接触）', engineHealthy);
  if (!engineHealthy) throw new Error('engine never became healthy');

  // 隧道腿自证：引擎应已拨上假中继（ConsoleTicketMint 铸票链的前置）。
  const ownerTicket = await mintTicket(OWNER_SUB);
  ok('伪造门户铸票（ConsoleTicketMint→Result）：owner 票到手', typeof ownerTicket === 'string' && ownerTicket.length > 20, `len=${ownerTicket?.length}`);
  const otherTicket = await mintTicket(OTHER_SUB);
  ok('伪造门户铸票：非 owner 票到手', typeof otherTicket === 'string' && otherTicket.length > 20, `len=${otherTicket?.length}`);

  const SAFARI_UA =
    'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Safari/605.1.15';
  const browser = await chromium.launch();

  // ── A-owner：owner sub 会话 ⇒ 已认证标记 ──
  {
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 960 }, userAgent: SAFARI_UA });
    // 远端门户设备姿态：屏蔽花名册读（真门户手机经隧道无此直连面）⇒ 页面
    // 不自动绑定 self ⇒ stamp 不带 boundDeviceId ⇒ 三级判定落到 tier2/tier3
    // （账号轨）。不屏蔽时同机浏览器会正确自绑机器名（tier1）——run9 实证。
    await ctx.route('**/api/neblink/status', (r) => r.fulfill({ status: 404, contentType: 'application/json', body: '{}' }));
    const page = await ctx.newPage();
    await page.goto(`${BASE}/?ticket=${encodeURIComponent(ownerTicket)}`); // 303 → / + 会话 cookie
    await page.waitForSelector('#input', { state: 'visible', timeout: 30000 });
    // 真人路径：输入框打字 + 发送（buildFrame 随帧带 device stamp）。
    await page.fill('#input', 'e2e owner 身份透传探针');
    await page.keyboard.press('Enter');
    await sleep(2500);
    const needle = `Device provenance: 'Mashiro · Safari（经官网）' (enrolled=false, authenticated=true)`;
    const hit = await waitLog(needle, 20000);
    ok('A-owner provenance 判词：Mashiro · Safari（经官网）（已认证）', hit, hit ? '' : `\n---- provenance lines ----\n${combinedLog().split('\n').filter((l) => l.includes('Device provenance')).join('\n')}`);
    await page.screenshot({ path: `${SHOT}/A1-owner-authenticated-console.png` });
    await ctx.close();
  }

  // ── A-other：非 owner sub 会话 ⇒ 不升已认证，默认名兜底 ──
  {
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 960 }, userAgent: SAFARI_UA });
    // 远端门户设备姿态（同 A-owner 腿：屏蔽花名册读 ⇒ 不自绑 ⇒ tier3 兜底可判）。
    await ctx.route('**/api/neblink/status', (r) => r.fulfill({ status: 404, contentType: 'application/json', body: '{}' }));
    const page = await ctx.newPage();
    await page.goto(`${BASE}/?ticket=${encodeURIComponent(otherTicket)}`);
    await page.waitForSelector('#input', { state: 'visible', timeout: 30000 });
    await page.fill('#input', 'e2e 非 owner 探针');
    await page.keyboard.press('Enter');
    await sleep(2500);
    const hit = await waitLog(`Device provenance: '未登记-Mac Safari' (enrolled=false, authenticated=false)`, 20000);
    ok('A-other provenance 判词：非 owner 不升已认证，默认名兜底', hit, hit ? '' : combinedLog().split('\n').filter((l) => l.includes('Device provenance')).join('\n'));
    await page.screenshot({ path: `${SHOT}/A2-other-sub-default-console.png` });
    await ctx.close();
  }

  // ── B①②近路 miss 诊断 + B③ 指纹提示 ──
  {
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 960 }, userAgent: SAFARI_UA });
    // B③「陈旧标签页」前提伪造面：document 起始就把 window.__WEB_FINGERPRINT__
    // 锁成旧值（getter 劫持，页面注入脚本的赋值被静默吞掉）——等价于「本标签页
    // 由旧版构建 serve」。此后应用真 socket 首连的 serverConfig（引擎当前指纹）
    // 与旧值比对不等 ⇒ main.js 处理器真链路触发提示条。裸 new WebSocket 绕过
    // main.js 分发管道（帧到了无人处理），不得用。
    await ctx.addInitScript(() => {
      const stale = 'stale-fp-e2e';
      Object.defineProperty(window, '__WEB_FINGERPRINT__', { configurable: true, get: () => stale, set: () => {} });
    });
    const page = await ctx.newPage();
    await page.goto(`${BASE}/?ticket=${encodeURIComponent(await mintTicket(OWNER_SUB))}`);
    await page.waitForSelector('#input', { state: 'visible', timeout: 30000 });
    await sleep(1500);
    // B①②：直连腿 /api/portal/status 404（无 link-server 面）⇒ miss 原因可读。
    const miss = await page.evaluate(() => {
      try { return JSON.parse(sessionStorage.getItem('stand_ws_nearpath_loopback_miss') || 'null'); } catch { return null; }
    });
    ok('B①②近路 miss 诊断键可见（直连腿 portal_status_http_404）', miss && miss.reason === 'portal_status_http_404', miss);
    // B③：陈旧指纹 vs 引擎当前指纹 ⇒ 顶栏提示（真 socket 帧 + 真处理器链）。
    const fpOnPage = await page.evaluate(() => window.__WEB_FINGERPRINT__);
    const hint = await page.waitForSelector('#stale-tab-hint', { timeout: 15000 }).catch(() => null);
    ok('B③指纹不匹配 ⇒ 顶栏刷新提示条出现', !!hint && fpOnPage === 'stale-fp-e2e', `pageFp=${fpOnPage}`);
    if (hint) {
      const text = (await hint.textContent()) || '';
      ok('B③提示文案自述旧版标签页', text.includes('新版本') && text.includes('刷新'), text.slice(0, 60));
    }
    await page.screenshot({ path: `${SHOT}/B3-stale-tab-hint.png` });
    await ctx.close();
  }

  await browser.close();

  // 隧道闸/会话零破坏旁证：兑换日志形态（relay-mint 三条 + accountSub 对账）。
  const log = combinedLog(400000);
  const minted = (log.match(/console ticket redeemed/g) || []).length;
  ok('引擎侧兑换日志三条（owner/other/诊断页各一）', minted >= 3, `redeemed=${minted}`);
  ok('兑换日志携 accountSub（owner sub 对账）', log.includes(`accountSub=${OWNER_SUB}`) && log.includes(`accountSub=${OTHER_SUB}`));
} catch (e) {
  ok('e2e 主流程无异常', false, String(e).slice(0, 300));
} finally {
  // 取证：引擎 stdout 全量落盘（判词/兑换日志对账源）。
  try { fs.writeFileSync('/tmp/nb-portal-e2e-stdout.txt', engineStdout); } catch { /* best effort */ }
  // 进程组整体收杀（引擎 JVM 为组首，同组直杀）。
  if (engine?.pid) {
    try { process.kill(-engine.pid, 'SIGKILL'); } catch { try { engine.kill('SIGKILL'); } catch { /* exited */ } }
  }
  wss.close();
}

console.log(failures === 0 ? '\nALL PASS' : `\n${failures} FAILURE(S)`);
process.exit(failures === 0 ? 0 : 1);

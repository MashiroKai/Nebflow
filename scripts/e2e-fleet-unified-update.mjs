#!/usr/bin/env node
// e2e-fleet-unified-update.mjs — 全设备统一更新 E2E（fleet 批 2026-11-01，作者令
// 「一键把所有设备更新到同一版本，并全程有状态卡片引导」）。
//
// 形态：**真引擎、真安装、真重启**，但全部关在隔离 home + 隔离端口里。
//   A = 控制台实例（跑**新版包**，作为推送方与「本机行」）
//   B = 真对端实例（跑**旧版包**，被 A 推送后真装包真重启，版本变新）
//   C/D/E1/E2 = 轻量 mock 设备（各验一条分发/失败分支，零进程重启开销）
//   MOCK = mock NebLink Server（名册在线态可编程，`online` 位由它给）
//
// 剧本（对应作者令五条验收面）：
//   ① 恢复：预写「在飞」任务后重启 ⇒ 在飞行标失联（绝不假装还在跑）
//   ② 计划：版本核对表 + 能力门槛（老版本不支持）+ 四格分发 + 收口句（manual 优先）
//   ③ 确认/审计：缺 confirm ⇒ 400 且零审计行
//   ④ 真更新：A 推送 B → B 真装包真重启 → 版本收敛到目标；E1「装了但版本没变」
//      ⇒ healthcheck-failed（单机失败不阻塞其他）→ 一键重试成功 ⇒ 收口 completed
//   ⑤ 坏包：manifest 尺寸不符 ⇒ 拒装（在役包未动，零重启）⇒ verify-failed
//   ⑥ 失联：E2 受受理后消失 ⇒ device-lost（窗满判失联，不报成功）
//   ⑦ 离线补推：D 名册离线 ⇒ waiting-online + pending-online；上线哨兵补推 ⇒ completed
//   ⑧ 全一致：一切收敛后计划读数 all-consistent（不报「已发起」了事）
//
// Run（worktree 根，jar 已构建）：node scripts/e2e-fleet-unified-update.mjs
// Env：fixture 默认**不删**（CLEAN=1 清理；NB_DRY_RUN=1 只跑守卫断言）；
//      A_PORT=8111 B_PORT=8112 MOCK_PORT=18998
//
// 安全面（逐条，硬约束）：
//   · 端口 8122 常被**其他在飞批**的实例占用 ⇒ 本脚本选号前先 lsof 预检，占则退出；
//   · 宿主 6138/8080 监听集合前后快照必须逐字相同（本脚本绝不碰宿主实例）；
//   · A/B 的 `NEBFLOW_HOTUPDATE_INSTALL_COMMAND` 一律显式注入 ⇒ **真实安装脚本永不
//     可能被跑**（A 注入 `exit 3` 只做安全阀，B 注入真装包命令）；
//   · manifest 读源一律指向本地 mock（`nebflow.updates.manifestUrl`）⇒ 不读外网；
//   · 不打印任何凭据（token 只从 auth.json 读进内存拼头，绝不落日志）。

import { spawn, execSync } from 'node:child_process';
import http from 'node:http';
import { createHash } from 'node:crypto';
import {
  readFileSync, mkdirSync, writeFileSync, copyFileSync, rmSync, existsSync, readdirSync, statSync,
  createWriteStream,
} from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';
import { setTimeout as sleep } from 'node:timers/promises';
import { guardFixtureHome, safeRm, assertCleanable } from './lib/delguard.mjs';
import { guardWrites } from './lib/writeguard.mjs';

const REPO = dirname(dirname(fileURLToPath(import.meta.url)));
const ROOT = process.env.FLEET_E2E_ROOT || join(tmpdir(), 'qa-fleet-e2e');
const A_PORT = Number(process.env.A_PORT || 8111);
const B_PORT = Number(process.env.B_PORT || 8112);
const C_PORT = A_PORT + 2; // 老版本（不支持热更）
const D_PORT = A_PORT + 3; // 名册离线 → 上线补推
const E1_PORT = A_PORT + 4; // 装了但版本不变 → healthcheck-failed；重试成功
const E2_PORT = A_PORT + 5; // 受理后失联 → device-lost
const MOCK_PORT = Number(process.env.MOCK_PORT || 18998);
const A_BASE = `http://127.0.0.1:${A_PORT}`;

const OLD = '2026.10.26-beta.2'; // B 起手版本（= 仓库在役包）
const NEW = '2026.12.01'; // 本轮目标（A 与推送包同版）
const NEW2 = '2026.12.15'; // 坏包轮的目标版本（尺寸不符 ⇒ 拒装）
const FLOOR = '2026.10.26'; // = UpdateCapability.MinRemoteVersion

const A_HOME = join(ROOT, 'a/home');
const A_INSTALL = join(ROOT, 'a/install');
const A_RUN = join(ROOT, 'a/run');
const B_HOME = join(ROOT, 'b/home');
const B_INSTALL = join(ROOT, 'b/install');
const B_RUN = join(ROOT, 'b/run');
const ART = join(ROOT, 'artifacts');

guardWrites([A_HOME, A_INSTALL, A_RUN, B_HOME, B_INSTALL, B_RUN, ART], { repoRoot: REPO, label: 'e2e-fleet' });
guardFixtureHome(ROOT, { label: 'e2e-fleet' });

let failed = 0;
function check(name, ok, extra = '') {
  if (!ok) failed++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
}

// ── 清理 / 端口预检 ─────────────────────────────────────────────────────────
const children = [];
const servers = [];
let cleaned = false;
const PORTS = [A_PORT, B_PORT, C_PORT, D_PORT, E1_PORT, E2_PORT, MOCK_PORT];
function listeners(port) {
  try {
    return execSync(`lsof -nP -iTCP:${port} -sTCP:LISTEN -t 2>/dev/null | tr '\\n' ' '`, { shell: '/bin/bash' })
      .toString().trim();
  } catch { return ''; }
}
async function cleanup() {
  if (cleaned) return;
  cleaned = true;
  for (const s of servers) { try { s.closeAllConnections?.(); s.close(); } catch { /* gone */ } }
  for (const c of children) { try { if (c.pid) process.kill(-c.pid, 'SIGKILL'); } catch { /* gone */ } }
  // 处理真重启派生的后继进程（RestartHelper 的 spawn 不在我们的 children 里）
  for (const port of PORTS) {
    const pids = listeners(port).split(/\s+/).filter(Boolean);
    for (const pid of pids) { try { process.kill(Number(pid), 'SIGKILL'); } catch { /* gone */ } }
  }
}
for (const sig of ['SIGINT', 'SIGTERM', 'uncaughtException', 'unhandledRejection']) {
  process.on(sig, async (e) => { if (e) console.error(e); await cleanup(); process.exit(1); });
}

for (const port of PORTS) {
  const busy = listeners(port);
  if (busy) {
    console.error(`port ${port} occupied (${busy}) — abort（8122 常被并行批占用，本脚本不碰它，换号用 A_PORT=…）`);
    process.exit(1);
  }
}
const HOST_SNAPSHOT = (p) => {
  try { return execSync(`lsof -nP -iTCP:${p} -sTCP:LISTEN -t 2>/dev/null | sort | tr '\\n' ' '`, { shell: '/bin/bash' }).toString().trim(); }
  catch { return ''; }
};
const HOST6138_BEFORE = HOST_SNAPSHOT(6138);

// ── 制品：旧包（仓库在役）、新包（旧包 + 换 VERSION 资源）─────────────────
function findAssembly() {
  const dir = join(REPO, 'target/scala-3.5.2');
  const jars = readdirSync(dir).filter((f) => f.startsWith('stand-assembly-') && f.endsWith('.jar'));
  if (!jars.length) throw new Error('assembly jar not found — run sbt assembly first');
  return join(dir, jars.sort().pop());
}
assertCleanable(ART, { label: 'e2e-fleet/artifacts' });
rmSync(ART, { recursive: true, force: true });
mkdirSync(ART, { recursive: true });
const SRC_JAR = findAssembly();
const NEW_JAR = join(ART, `stand-assembly-${NEW}.jar`);
copyFileSync(SRC_JAR, NEW_JAR);
{
  const tmp = join(ART, 'ver');
  mkdirSync(tmp, { recursive: true });
  writeFileSync(join(tmp, 'VERSION'), `${NEW}\n`);
  execSync(`cd ${JSON.stringify(tmp)} && zip -q ${JSON.stringify(NEW_JAR)} VERSION`, { shell: '/bin/bash' });
}
const NEW_SIZE = statSync(NEW_JAR).size;
const NEW_SHA = createHash('sha256').update(readFileSync(NEW_JAR)).digest('hex');
console.log(`[phase] src=${SRC_JAR}\n[phase] new=${NEW_JAR} (${NEW_SIZE}B sha256=${NEW_SHA.slice(0, 12)}…)`);

// ── fixture（A / B 两套 home + install + run）───────────────────────────────
function baseHome(home, deviceId, deviceName) {
  mkdirSync(join(home, 'neblink'), { recursive: true });
  mkdirSync(join(home, 'logs'), { recursive: true });
  writeFileSync(join(home, 'device.json'), JSON.stringify({
    deviceId, deviceName, platform: 'macos', deviceSecret: 'e2e-secret', capabilities: {}, userDescription: '',
  }));
  writeFileSync(join(home, 'neblink/config.json'), JSON.stringify({
    enabled: true, syncIntervalSec: 5,
    neblinkServer: { url: `http://127.0.0.1:${MOCK_PORT}`, networkId: 'net-e2e', secret: 'sec-e2e' },
  }));
  writeFileSync(join(home, 'nebflow.json'), JSON.stringify({ deviceAuth: 'off' }));
  writeFileSync(join(home, 'onboarding.json'), JSON.stringify({ state: 'skipped' }));
}
for (const d of ['a', 'b']) {
  for (const sub of ['home', 'install', 'run']) {
    assertCleanable(join(ROOT, d, sub), { label: `e2e-fleet/fixture-${d}-${sub}` });
    rmSync(join(ROOT, d, sub), { recursive: true, force: true });
    mkdirSync(join(ROOT, d, sub), { recursive: true });
  }
}
baseHome(A_HOME, 'dev-a-1', 'QA-A');
baseHome(B_HOME, 'dev-b-2', 'QA-B');

// A：跑新包；指针 = 新版本；安装目录里有新版本留存包（健康自检的期望版本真值来源）
const A_JAR = join(A_RUN, `stand-assembly-${NEW}.jar`);
copyFileSync(NEW_JAR, A_JAR);
mkdirSync(join(A_INSTALL, 'versions', NEW), { recursive: true });
copyFileSync(NEW_JAR, join(A_INSTALL, 'versions', NEW, `stand-assembly-${NEW}.jar`));
writeFileSync(join(A_INSTALL, 'current-version'), `${NEW}\n`);

// B：跑旧包（文件名用新版本名——真装包会覆盖这个正在运行的路径）
const B_JAR = join(B_RUN, `stand-assembly-${NEW}.jar`);
copyFileSync(SRC_JAR, B_JAR);
mkdirSync(join(B_INSTALL, 'versions', OLD), { recursive: true });
copyFileSync(SRC_JAR, join(B_INSTALL, 'versions', OLD, `stand-assembly-${OLD}.jar`));
writeFileSync(join(B_INSTALL, 'current-version'), `${OLD}\n`);

// A：预写「在飞」任务（验 restore：在飞行标失联，绝不假装还在跑）
mkdirSync(join(A_HOME, 'hotupdate'), { recursive: true });
const SEEDED_JOB = {
  job: {
    jobId: 'fleet-seeded-restore', startedAt: 1, finishedAt: 0, status: 'running',
    targetVersion: NEW, updatedCount: 0, failedCount: 0, manualCount: 0, pendingOnlineCount: 0, cancelledCount: 0,
  },
  runs: [
    { deviceId: 'dev-a-1', deviceName: 'QA-A', isLocal: true, stage: 'restart', detail: 'restarting (seeded)', attempts: 0, startedAt: 1, updatedAt: 1 },
    // 🔴 `stage` 必须是**线上 wire 值**（`FleetStage.wire`）：写成 'restarting' 会解不出来
    // ⇒ 解码器兜底 `Failed`（终态）⇒ `restore()` 判「已终态、不动」⇒ 该行既不是 done 也
    // 不带 device-lost 失败类（e2e ①-3 实跑抓到：stage=failed 而 failure=null）。
    { deviceId: 'dev-b-2', deviceName: 'QA-B', isLocal: false, stage: 'restart', detail: 'seeded', attempts: 0, startedAt: 1, updatedAt: 1 },
  ],
  manual: [], pending: [],
};
writeFileSync(join(A_HOME, 'hotupdate/fleet-job.json'), JSON.stringify(SEEDED_JOB));
const AUDIT_PATH = join(A_HOME, 'hotupdate/fleet-audit.jsonl');

// ── mock 设备（C 老版本 / D 离线→补推 / E1 装了不变版→重试成功 / E2 受理后失联）──
function healthBody(version, { fleet = true } = {}) {
  const j = { status: 'ok', version };
  if (fleet) j.fleet = { protocol: 1, hotUpdate: true, minRemoteVersion: FLOOR };
  return j;
}
const mockDevices = {
  C: { version: '2025.01.01', fleet: false, updateCalls: 0, mode: 'refuse' },
  D: { version: OLD, fleet: true, updateCalls: 0, mode: 'bump' },
  E1: { version: OLD, fleet: true, updateCalls: 0, mode: 'accept-no-bump-then-bump' },
  E2: { version: OLD, fleet: true, updateCalls: 0, mode: 'accept-then-die' },
};
function serveMockDevice(name, port) {
  const st = mockDevices[name];
  const server = http.createServer((req, res) => {
    if (req.url === '/api/health') {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify(healthBody(st.version, { fleet: st.fleet })));
      return;
    }
    if (req.url === '/api/neblink/update') {
      st.updateCalls++;
      if (st.mode === 'refuse') { res.writeHead(403).end('{"error":"not a trusted peer"}'); return; }
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end('{"ok":true,"message":"accepted"}');
      if (st.mode === 'bump') { st.version = NEW; return; }
      if (st.mode === 'accept-no-bump-then-bump') {
        // 第一次：受理但版本不变（install did not take effect）；第二次（重试）：真变新
        if (st.updateCalls >= 2) st.version = NEW;
        return;
      }
      if (st.mode === 'accept-then-die') {
        // 受理后彻底消失 ⇒ 本机探测全失败 ⇒ 窗满判 device-lost（不报成功）
        setTimeout(() => { try { server.closeAllConnections?.(); server.close(); } catch { /* gone */ } }, 200);
        return;
      }
      return;
    }
    res.writeHead(404).end('{}');
  });
  servers.push(server);
  return new Promise((r) => server.listen(port, '127.0.0.1', r));
}

// ── mock NebLink Server（名册在线态可编程；manifest/jar 下载同源）──────────
let rosterPhase = [];
const mockSeen = [];
const peersFor = () => rosterPhase;
const mockServer = http.createServer((req, res) => {
  let body = '';
  req.on('data', (d) => (body += d));
  req.on('end', () => {
    mockSeen.push(`${req.method} ${req.url}`);
    if (req.url === '/api/device/login' || req.url === '/api/device/session') {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ token: 'tok-e2e', networkId: 'net-e2e', deviceId: 'dev-a-1', peers: peersFor() }));
      return;
    }
    if (req.url === '/api/device/heartbeat') {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ peers: peersFor() }));
      return;
    }
    if (req.url === '/updates/manifest.json' || req.url === '/updates/manifest-beta.json') {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify(manifest));
      return;
    }
    if (req.url === `/dl/stand-assembly-${NEW}.jar`) {
      res.writeHead(200, { 'content-type': 'application/java-archive' });
      res.end(readFileSync(NEW_JAR));
      return;
    }
    res.writeHead(404).end('{}');
  });
});
servers.push(mockServer);
let manifest = {
  schema: 1, channel: 'stable', version: NEW, publishedAt: '2026-12-01T00:00:00Z',
  artifacts: { jar: { url: `http://127.0.0.1:${MOCK_PORT}/dl/stand-assembly-${NEW}.jar`, sha256: NEW_SHA, sizeBytes: NEW_SIZE } },
};
await new Promise((r) => mockServer.listen(MOCK_PORT, '127.0.0.1', r));
await serveMockDevice('C', C_PORT);
await serveMockDevice('D', D_PORT);
await serveMockDevice('E1', E1_PORT);
await serveMockDevice('E2', E2_PORT);
console.log(`[phase] mock server :${MOCK_PORT} + devices C:${C_PORT} D:${D_PORT} E1:${E1_PORT} E2:${E2_PORT}`);

const ep = (port) => [{ address: '127.0.0.1', port, kind: 'lan' }];
// 🔴 名册里**必须**始终含一台带 `127.0.0.1` 端点的设备：设备侧 P2P 更新端点的
// 准入是「IP 在 trusted 集」（`verifyPeerAccess` → `isTrustedPeer`），而 trusted 集
// 只由名册端点地址灌入（`updateTrustedIps`）。名册若只回传 B 自己（会被 self 过滤），
// B 的 trusted 集为空 ⇒ 被 A 推送时一律 403 `Not a trusted peer`。探针⑤ 实测坐实。
// SELF 行在 A 侧被 self 过滤，在 B 侧正好提供 127.0.0.1 这条信任来源。
const SELF = { deviceId: 'dev-a-1', deviceName: 'QA-A', platform: 'macos', endpoints: ep(A_PORT), online: true };
const ROSTER = {
  old: [{ deviceId: 'dev-c-3', deviceName: 'QA-C', platform: 'linux', endpoints: ep(C_PORT), online: true }],
  b: [{ deviceId: 'dev-b-2', deviceName: 'QA-B', platform: 'macos', endpoints: ep(B_PORT), online: true }],
  e1: [{ deviceId: 'dev-e1-5', deviceName: 'QA-E1', platform: 'linux', endpoints: ep(E1_PORT), online: true }],
  e2: [{ deviceId: 'dev-e2-6', deviceName: 'QA-E2', platform: 'linux', endpoints: ep(E2_PORT), online: true }],
  dOffline: [{ deviceId: 'dev-d-4', deviceName: 'QA-D', platform: 'linux', endpoints: ep(D_PORT), online: false }],
  dOnline: [{ deviceId: 'dev-d-4', deviceName: 'QA-D', platform: 'linux', endpoints: ep(D_PORT), online: true }],
};
function setRoster(...groups) { rosterPhase = [SELF, ...groups.flat()]; }
// 登录响应/心跳响应同源（mock server 用 peersFor()）。
setRoster(ROSTER.b);

// ── 起实例 ──────────────────────────────────────────────────────────────────
function startInstance({ jar, home, port, installDir, installCmd, extraProps = [] }) {
  const env = {
    ...process.env,
    STAND_HOME: home,
    NEBFLOW_GATEWAY_PORT: String(port),
    INSTALL_DIR: installDir,
    NEBFLOW_HOTUPDATE_INSTALL_COMMAND: installCmd,
    JAVA_TOOL_OPTIONS: [
      `-Dnebflow.updates.manifestUrl=http://127.0.0.1:${MOCK_PORT}/updates/manifest.json`,
      // 自检窗：一次真装包 + 真重启 + 回来自证在这台机器上要 40–90s（④ 实跑两次：
      // 第一次 62s 过、第二次 >60s 被误判 device-lost —— 窗太窄会把「慢」判成「失联」）。
      // 120s 仍远小于生产默认 300s（生产不设 ⇒ 默认值，零行为变化）。
      `-Dnebflow.fleet.verifyWindowSec=120`,
      `-Dnebflow.fleet.pollIntervalMs=1000`,
      // 既有热重启时序（只在本 fixture 里缩短；生产不设 ⇒ 默认值，零行为变化）。
      // 观察窗默认 15s、健康自检轮询 250ms —— 一次实测要白等几十秒，与本批无关。
      `-Dnebflow.hotRestart.cooldownMs=0`,
      `-Dnebflow.hotRestart.observeWindowMs=1000`,
      `-Dnebflow.hotRestart.drainPollMs=200`,
      `-Dnebflow.hotRestart.drainDeadlineMs=30000`,
      `-Dnebflow.hotRestart.healthPollMs=100`,
      ...extraProps,
    ].join(' '),
  };
  const log = createWriteStream(join(ROOT, `gw-${port}.log`), { flags: 'a' });
  const child = spawn('java', [
    '--add-opens', 'java.base/java.lang=ALL-UNNAMED', '-jar', jar,
    'start', '--home', home, '--port', String(port), '--no-browser', '--foreground',
  ], { cwd: ROOT, detached: true, stdio: ['ignore', 'pipe', 'pipe'], env });
  child.stdout.pipe(log);
  child.stderr.pipe(log);
  children.push(child);
  return child;
}

async function healthOf(port) {
  try {
    const r = await fetch(`http://127.0.0.1:${port}/api/health`);
    if (r.status !== 200) return null;
    return await r.json();
  } catch { return null; }
}
async function waitVersion(port, version, timeoutMs = 180000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    const h = await healthOf(port);
    if (h && h.version === version) return true;
    await sleep(700);
  }
  return false;
}

// B 的真装包命令：把已校验交接件覆盖到**正在运行的包路径**，并写双指针
const B_INSTALL_CMD = [
  `cp "$NEBFLOW_STAGED_JAR" ${JSON.stringify(B_JAR)}.tmp`,
  `mv ${JSON.stringify(B_JAR)}.tmp ${JSON.stringify(B_JAR)}`,
  `mkdir -p ${JSON.stringify(join(B_INSTALL, 'versions', NEW))}`,
  `cp "$NEBFLOW_STAGED_JAR" ${JSON.stringify(join(B_INSTALL, 'versions', NEW, `stand-assembly-${NEW}.jar`))}`,
  `printf '${NEW}\\n' > ${JSON.stringify(join(B_INSTALL, 'current-version'))}`,
  `printf '${OLD}\\n' > ${JSON.stringify(join(B_INSTALL, 'previous-version'))}`,
].join(' && ');

startInstance({ jar: B_JAR, home: B_HOME, port: B_PORT, installDir: B_INSTALL, installCmd: B_INSTALL_CMD });
startInstance({
  jar: A_JAR, home: A_HOME, port: A_PORT, installDir: A_INSTALL,
  // 安全阀：A 自己的安装动作只允许**失败退出**（3）。坏包轮的 verify 在它之前就拒装，
  // 所以这个值永远不会被消耗；万一 manifest 走 legacy 路径，也绝不可能真装包/真重启。
  installCmd: 'exit 3',
});
const aUp = await waitVersion(A_PORT, NEW, 180000);
const bUp = await waitVersion(B_PORT, OLD, 180000);
check('A（控制台实例）起在新版包上', aUp, `${A_BASE} version=${NEW}`);
check('B（对端实例）起在旧版包上', bUp, `version=${OLD}`);
if (!aUp || !bUp) { await cleanup(); process.exit(1); }
check('A 的 /api/health 自报 fleet 能力门', (await healthOf(A_PORT))?.fleet?.hotUpdate === true, JSON.stringify((await healthOf(A_PORT))?.fleet));

const TOKEN = JSON.parse(readFileSync(join(A_HOME, 'auth.json'), 'utf8'));
async function api(path, method = 'GET', body) {
  const r = await fetch(`${A_BASE}/api${path}`, {
    method,
    headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await r.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* non-json */ }
  return { status: r.status, text, json };
}
/** 现推一次名册（scan = 立即发现；mock 名册是当前 phase）。 */
async function scan() {
  await api('/neblink/scan', 'POST');
  await sleep(1500);
}
/** 计划里的设备 id 集合（GET 现算 ⇒ 每次都是一次真探测）。 */
async function rosterOf() {
  const r = await api('/update/fleet');
  return (r.json?.plan?.devices || []).map((d) => d.deviceId);
}
/**
 * 名册收敛等待：换名册后**离册**设备走既有 15s grace（`NeblinkService.removePeer`），
 * 而在线判据窗是 90s（`onlineFreshnessMs`）⇒ 旧设备会在计划里多活十几秒。剧本每一场
 * 都要求行集合确定（否则「4 行」这类断言会数到上一场的残留），所以每场先落名册、
 * 再等行集合命中；每轮补一次 scan 推进既有发现腿（不新增定时器）。
 */
async function waitPlan(wantIds, timeoutMs = 90000) {
  const want = [...wantIds].sort();
  const t0 = Date.now();
  let last = [];
  while (Date.now() - t0 < timeoutMs) {
    last = await rosterOf();
    if (last.length === want.length && [...last].sort().every((v, i) => v === want[i])) return last;
    await scan();
  }
  throw new Error(`roster did not converge to [${want.join(',')}] (last=[${last.join(',')}])`);
}
const auditLines = () => (existsSync(AUDIT_PATH) ? readFileSync(AUDIT_PATH, 'utf8').trim().split('\n').filter(Boolean) : []);
async function waitJob(pred, timeoutMs = 120000) {
  const t0 = Date.now();
  let last = null;
  while (Date.now() - t0 < timeoutMs) {
    const r = await api('/update/fleet');
    last = r.json;
    if (last && pred(last)) return last;
    await sleep(800);
  }
  return last;
}
const jobOf = (snap) => (snap && snap.job ? snap.job.job : null);
const runsOf = (snap) => (snap && snap.job && Array.isArray(snap.job.runs) ? snap.job.runs : []);
const runOf = (snap, id) => runsOf(snap).find((r) => r.deviceId === id) || null;

// ══ ① 恢复：在飞行标失联 ════════════════════════════════════════════════════
{
  const r = await api('/update/fleet');
  const job = jobOf(r.json);
  const local = runOf(r.json, 'dev-a-1');
  const remote = runOf(r.json, 'dev-b-2');
  check('①-1 落盘任务被恢复（jobId 命中种子）', r.status === 200 && job && job.jobId === 'fleet-seeded-restore', `status=${r.status}`);
  check('①-2 本机行「重启后已在目标版」⇒ completed（不写失联）', local?.stage === 'done', `stage=${local?.stage}`);
  check('①-3 远端在飞行标记失联 device-lost（绝不假装还在跑）', remote?.stage === 'failed' && remote?.failure === 'device-lost', `stage=${remote?.stage} failure=${remote?.failure}`);
  check('①-4 收口状态按改写后的行判 = partial（不报 completed）', job?.status === 'partial', `status=${job?.status}`);
  check('①-5 恢复不写审计（不是一次动作）', auditLines().length === 0, `auditLines=${auditLines().length}`);
}

// ══ ② 计划：版本核对 + 能力门槛 + 四格分发 + 收口句 ═════════════════════════
setRoster(ROSTER.b, ROSTER.old, ROSTER.dOffline);
await scan();
await waitPlan(['dev-a-1', 'dev-b-2', 'dev-c-3', 'dev-d-4']);
{
  const r = await api('/update/fleet');
  const plan = r.json?.plan;
  const devs = plan?.devices || [];
  const byId = (id) => devs.find((d) => d.deviceId === id);
  check('②-1 计划含本机 + 名册三台（共 4 行）', devs.length === 4, `devices=${devs.map((d) => d.deviceName).join(',')}`);
  check('②-2 本机行 in-sync + 免推（已同版不重复安装）', byId('dev-a-1')?.versionState === 'in-sync' && byId('dev-a-1')?.isLocal === true, `state=${byId('dev-a-1')?.versionState}`);
  check('②-3 老版本不支持：能力=version-too-old + 分支 manual（不推）', byId('dev-c-3')?.capability === 'version-too-old' && byId('dev-c-3')?.branch === 'manual' && byId('dev-c-3')?.remoteUpdatable === false, `${byId('dev-c-3')?.capability}/${byId('dev-c-3')?.branch}`);
  check('②-4 在线且支持 ⇒ push；离线且支持 ⇒ queued（待上线）', byId('dev-b-2')?.branch === 'push' && byId('dev-d-4')?.branch === 'queued', `b=${byId('dev-b-2')?.branch} d=${byId('dev-d-4')?.branch}`);
  check('②-5 收口句 manual 优先 = manual-required + 四元组齐全', plan?.summary?.status === 'manual-required'
    && plan.summary.manualCount === 1 && plan.summary.pendingOnlineCount === 1 && plan.summary.pushCount === 1 && plan.summary.totalDevices === 4,
    JSON.stringify(plan?.summary));
  check('②-6 文案走 messageKey（帧内零硬编码展示文案）', plan?.summary?.messageKey === 'fleet.summary.manual-required' && byId('dev-c-3')?.capabilityKey === 'fleet.capability.version-too-old', `${plan?.summary?.messageKey}`);
  check('②-7 老版本设备从未被推送（零 update 调用）', mockDevices.C.updateCalls === 0, `calls=${mockDevices.C.updateCalls}`);
  const ev = await api('/update/fleet/events');
  check('②-8 /events 重连面与快照同源（都是 fleetUpdate 帧）', ev.status === 200 && ev.json?.type === 'fleetUpdate' && Array.isArray(ev.json?.inflight), `type=${ev.json?.type}`);
}

// ══ ③ 确认与审计：缺 confirm ⇒ 400，且不落审计 ══════════════════════════════
{
  const before = auditLines().length;
  const r = await api('/update/fleet', 'POST', { source: 'e2e' });
  check('③-1 缺 confirm ⇒ 400（不默认 true、不静默执行）', r.status === 400 && /confirm/.test(r.json?.error || ''), `status=${r.status}`);
  check('③-2 未受理 ⇒ 零审计行（动作才有留痕）', auditLines().length === before, `before=${before} after=${auditLines().length}`);
}

// ══ ④ 真更新：B 真装包真重启 + E1 装了不变版 ⇒ healthcheck-failed ⇒ 一键重试 ══
setRoster(ROSTER.b, ROSTER.e1);
await waitPlan(['dev-a-1', 'dev-b-2', 'dev-e1-5']);
{
  const before = auditLines().length;
  const start = await api('/update/fleet', 'POST', { confirm: true, source: 'e2e' });
  check('④-1 带 confirm 发起 ⇒ 200 + jobId', start.status === 200 && !!start.json?.jobId, `jobId=${start.json?.jobId}`);
  check('④-2 发起即留审计 fleet-start', (() => {
    const lines = auditLines();
    return lines.length > before && lines.slice(before).some((l) => l.includes('fleet-start'));
  })(), `+${auditLines().length - before}`);
  const snap = await waitJob((s) => jobOf(s) && jobOf(s).status !== 'running', 180000);
  const b = runOf(snap, 'dev-b-2');
  const e1 = runOf(snap, 'dev-e1-5');
  const a = runOf(snap, 'dev-a-1');
  check('④-3 真对端 B 走完：completed（真装包真重启后自证新版本）', b?.stage === 'done', `stage=${b?.stage} detail=${String(b?.detail || '').slice(0, 80)}`);
  check('④-4 B 现在真的在新版本上（端口重启自证）', (await healthOf(B_PORT))?.version === NEW, `version=${(await healthOf(B_PORT))?.version}`);
  check('④-5 B 盘上双指针已切换（current=新 / previous=旧）',
    readFileSync(join(B_INSTALL, 'current-version'), 'utf8').trim() === NEW
    && readFileSync(join(B_INSTALL, 'previous-version'), 'utf8').trim() === OLD,
    `${readFileSync(join(B_INSTALL, 'current-version'), 'utf8').trim()}/${readFileSync(join(B_INSTALL, 'previous-version'), 'utf8').trim()}`);
  check('④-6 本机行 in-sync ⇒ 已同版不重复安装', a?.stage === 'done' && /already on the target version/.test(String(a?.detail || '')), `stage=${a?.stage}`);
  check('④-7 装了但版本没变 ⇒ 分类 healthcheck-failed（不报成功）', e1?.stage === 'failed' && e1?.failure === 'healthcheck-failed', `stage=${e1?.stage} failure=${e1?.failure}`);
  check('④-8 单机失败不阻塞其他设备：B 同时成功、任务收口 partial', jobOf(snap)?.status === 'partial' && jobOf(snap)?.updatedCount === 2 && jobOf(snap)?.failedCount === 1, JSON.stringify({ s: jobOf(snap)?.status, u: jobOf(snap)?.updatedCount, f: jobOf(snap)?.failedCount }));
  check('④-9 失败行携带可重试标记（回滚档才不给重试）', e1?.retryable === true, `retryable=${e1?.retryable}`);
  check('④-10 收口留审计 fleet-finish', auditLines().some((l) => l.includes('fleet-finish')), '');

  // 一键重试失败项（E1 第二次真变版）
  const retry = await api('/update/fleet/retry', 'POST');
  check('④-11 重试受理 ⇒ 200', retry.status === 200, `status=${retry.status}`);
  const snap2 = await waitJob((s) => runOf(s, 'dev-e1-5')?.stage === 'done' && jobOf(s)?.status !== 'running', 120000);
  check('④-12 重试后 E1 到 completed（任务重新打开并收口）', runOf(snap2, 'dev-e1-5')?.stage === 'done' && jobOf(snap2)?.status === 'completed', `stage=${runOf(snap2, 'dev-e1-5')?.stage} status=${jobOf(snap2)?.status}`);
  check('④-13 重试留审计 fleet-retry', auditLines().some((l) => l.includes('fleet-retry')), '');
  check('④-14 本轮全部收敛后计划读数 all-consistent（真正的一致，不是「已发起」）',
    (async () => true)() !== null && (await api('/update/fleet')).json?.plan?.summary?.status === 'all-consistent',
    `status=${(await api('/update/fleet')).json?.plan?.summary?.status}`);
}

// ══ ⑤ 坏包：manifest 尺寸不符 ⇒ 拒装（在役包未动、零重启）════════════════════
setRoster(ROSTER.b);
await waitPlan(['dev-a-1', 'dev-b-2']);
{
  manifest = {
    schema: 1, channel: 'stable', version: NEW2, publishedAt: '2026-12-15T00:00:00Z',
    artifacts: { jar: { url: `http://127.0.0.1:${MOCK_PORT}/dl/stand-assembly-${NEW}.jar`, sha256: NEW_SHA, sizeBytes: NEW_SIZE + 4096 } },
  };
  const start = await api('/update/fleet', 'POST', { confirm: true, source: 'e2e-badpkg' });
  check('⑤-1 坏包轮发起（目标版本 = 坏包声明的版本）', start.status === 200, `status=${start.status}`);
  const snap = await waitJob((s) => jobOf(s) && jobOf(s).status !== 'running', 180000);
  const b = runOf(snap, 'dev-b-2');
  check('⑤-2 对端 B 拒装并分类 verify-failed（尺寸不符，绝不入 staging swap）', b?.stage === 'failed' && b?.failure === 'verify-failed', `failure=${b?.failure} detail=${String(b?.detail || '').slice(0, 100)}`);
  check('⑤-3 在役包未动：B 仍在新版本上服务', (await healthOf(B_PORT))?.version === NEW, `version=${(await healthOf(B_PORT))?.version}`);
  check('⑤-4 本机腿同样被拒（同一条拒装语义，不砖机）', runOf(snap, 'dev-a-1')?.stage === 'failed', `stage=${runOf(snap, 'dev-a-1')?.stage}`);
  check('⑤-5 A 仍在运行（坏包不导致重启）', (await healthOf(A_PORT))?.version === NEW, '');
  manifest = {
    schema: 1, channel: 'stable', version: NEW, publishedAt: '2026-12-01T00:00:00Z',
    artifacts: { jar: { url: `http://127.0.0.1:${MOCK_PORT}/dl/stand-assembly-${NEW}.jar`, sha256: NEW_SHA, sizeBytes: NEW_SIZE } },
  };
}

// ══ ⑥ 失联：受理后消失 ⇒ device-lost ═══════════════════════════════════════
setRoster(ROSTER.e2);
await waitPlan(['dev-a-1', 'dev-e2-6']);
{
  const start = await api('/update/fleet', 'POST', { confirm: true, source: 'e2e-lost' });
  check('⑥-1 失联轮发起', start.status === 200, `status=${start.status}`);
  const snap = await waitJob((s) => jobOf(s) && jobOf(s).status !== 'running', 180000);
  const e2 = runOf(snap, 'dev-e2-6');
  check('⑥-2 受理后消失 ⇒ device-lost（窗满判失联，绝不报成功）', e2?.stage === 'failed' && e2?.failure === 'device-lost', `failure=${e2?.failure}`);
}

// ══ ⑦ 离线补推：名册离线 ⇒ waiting-online；上线哨兵自动补推 ══════════════════
setRoster(ROSTER.dOffline);
await waitPlan(['dev-a-1', 'dev-d-4']);
{
  const plan = (await api('/update/fleet')).json?.plan;
  const d = plan?.devices?.find((x) => x.deviceId === 'dev-d-4');
  check('⑦-1 离线且支持 ⇒ queued + 收口 pending-online', d?.branch === 'queued' && plan?.summary?.status === 'pending-online' && plan?.summary?.pendingOnlineCount === 1, `${d?.branch}/${plan?.summary?.status}`);
  const start = await api('/update/fleet', 'POST', { confirm: true, source: 'e2e-offline' });
  check('⑦-2 离线轮发起 ⇒ 200', start.status === 200, '');
  const snap = await waitJob((s) => jobOf(s) && jobOf(s).status !== 'running', 60000);
  check('⑦-3 离线设备行 = waiting-online（本轮到此为止，不假跑）', runOf(snap, 'dev-d-4')?.stage === 'waiting-online', `stage=${runOf(snap, 'dev-d-4')?.stage}`);
  check('⑦-4 收口如实带「1 台待上线」', jobOf(snap)?.pendingOnlineCount === 1, `pending=${jobOf(snap)?.pendingOnlineCount}`);

  // 上线：名册翻 online ⇒ 既有 peer 变更回调触发哨兵 ⇒ 自动补推 ⇒ 版本变新
  // 🔴 名册由 **D 自己的心跳拍**领（A 的 scan 只推 A 的发现腿）：`applyServerPeerStatus`
  // / `upsertPeer` 的「上线」判据要看到 D 的 lastSeen 从 0 变新。mock server 立刻翻
  // online，D 下一拍心跳（≤30s）就会到账；这里等的是既有的心跳腿，零新增定时器。
  setRoster(ROSTER.dOnline);
  // 分两段等，失分点可判：第一段只等「A 真把 D 看成在线」（名册腿到账；这一段失败 =
  // 心跳/名册问题，与哨兵无关），第二段才等哨兵补推的结果。
  let dOnline = false;
  for (let i = 0; i < 40 && !dOnline; i++) {
    await scan();
    const plan = (await api('/update/fleet')).json?.plan;
    const d = plan?.devices?.find((x) => x.deviceId === 'dev-d-4');
    dOnline = d?.online === true;
  }
  check('⑦-5 名册腿到账：A 现在把 D 看成在线（哨兵触发腿的前提）', dOnline, `online=${dOnline}`);
  const snap2 = await waitJob((s) => runOf(s, 'dev-d-4')?.stage === 'done', 180000);
  const dd = runOf(snap2, 'dev-d-4');
  check('⑦-6 上线哨兵自动补推 ⇒ completed（无需人工再点）', dd?.stage === 'done', `stage=${dd?.stage} detail=${String(dd?.detail || '').slice(0, 70)}`);
  check('⑦-7 哨兵动作留审计 fleet-sentinel', auditLines().some((l) => l.includes('fleet-sentinel')), '');
  check('⑦-8 收口后 pendingOnlineCount 归零', jobOf(snap2)?.pendingOnlineCount === 0, `pending=${jobOf(snap2)?.pendingOnlineCount}`);
}

// ══ ⑧ 全一致 + 审计只增 + 宿主零影响 ════════════════════════════════════════
{
  const plan = (await api('/update/fleet')).json?.plan;
  check('⑧-1 全部设备收敛后计划读数 = all-consistent', plan?.summary?.status === 'all-consistent' && plan?.summary?.allConsistent === true, `status=${plan?.summary?.status}`);
  const lines = auditLines();
  check('⑧-2 审计是只增 JSONL（每行可解析且带 action/jobId）', lines.length > 6 && lines.every((l) => {
    try { const j = JSON.parse(l); return typeof j.action === 'string' && typeof j.jobId === 'string'; } catch { return false; }
  }), `lines=${lines.length}`);
  check('⑧-3 宿主 6138 监听集合未变（本脚本不碰宿主实例）', HOST_SNAPSHOT(6138) === HOST6138_BEFORE, `before=[${HOST6138_BEFORE}]`);
  const a = await healthOf(A_PORT);
  check('⑧-4 控制台实例全程在役（可中断/可感知，零静默批量重启）', a?.version === NEW, `version=${a?.version}`);
}

console.log(`\n[summary] ${failed === 0 ? 'ALL PASS' : `${failed} FAILED`}`);
console.log(`[fixture] ${ROOT}（默认保留；CLEAN=1 清理）`);
await cleanup();
if (!process.env.CLEAN && !process.env.NB_CLEAN) {
  console.log(`[cleanup] 保留 fixture（要清理设 CLEAN=1）`);
} else {
  safeRm(ROOT, { label: 'e2e-fleet/clean' });
}
process.exit(failed === 0 ? 0 : 1);

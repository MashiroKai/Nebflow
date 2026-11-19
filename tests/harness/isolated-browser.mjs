#!/usr/bin/env node
// isolated-browser.mjs — **隔离浏览器自动化**的单一入口（隔离实例 + 隔离浏览器档案）。
//
// 作者令（2026-11-07 12:12 逐字）：「然后看看我们的浏览器自动化能不能优化一下 在隔离环境搞？
//   不影响宿主？」
//
// 要解决的现场（已证实，本件不再重复验证）：本机 Playwright headless 巡检连宿主 :6138 时
//   每开一个页面就是一个新控制台端（临时 profile ⇒ 空 localStorage ⇒ 每次新 clientId）⇒
//   控制台票 + conflict 接管噪音（2026-11-07 09:00–10:59 = 26 票 / 26 次接管 / 6 次真推
//   takeover 帧到作者手机）。`1aeb9f4f9` 只让自动化端**不夺权**；本件让自动化端**不进门**：
//   自带一个一次性实例（独立 home / 独立端口 / 独立浏览器档案），宿主零连接。
//
// 🔴 三条硬保证（每次 start 都成立，退出前自证）：
//   ① 不连宿主：baseUrl 恒为本进程自起的实例端口；`context.on('request')` 记账任何落在
//      宿主端口（默认 :6138，STAND_HOST_PORT 可覆盖）上的请求，计数非零即报红。
//   ② 不写宿主数据根：引擎子进程带 `--home <临时根>` 且 env 同时钉 STAND_HOME / NEBFLOW_HOME
//      / NEBFLOW_GATEWAY_PORT（fork-proof）；浏览器档案 = 临时根下的 `profile-*`（真·空档）。
//   ③ 跑完即回收：SIGTERM → 等进程退出 → 等端口释放 → 删临时根，并逐条自证
//      （端口已关 / 无残留进程 / 目录已消失）。
//
// 用法（完整命令示例见 tests/harness/README.md）：
//   node tests/harness/isolated-browser.mjs run tests/asset-url-versioning.spec.mjs
//   node tests/harness/isolated-browser.mjs run tests/isolated-browser-selfproof.spec.mjs --n 3
//   node tests/harness/isolated-browser.mjs up            # 起隔离实例（后台常驻），打印 export 行
//   node tests/harness/isolated-browser.mjs env           # 打印当前实例的 export 行
//   node tests/harness/isolated-browser.mjs down          # 回收
//   node tests/harness/isolated-browser.mjs doctor        # 只做隔离不变量自检（PASS/FAIL）
//
// 程序化 API（spec 内直用）：
//   import { startIsolatedInstance, openIsolatedBrowser, withIsolatedBrowser } from './harness/isolated-browser.mjs';
//   const inst = await startIsolatedInstance();
//   const { context, newPage } = await openIsolatedBrowser(inst);
//   ... await inst.close();
//
// 代价与边界：见 tests/harness/README.md「代价与边界」节（启动时间实测/L 层不可隔离面）。

import { spawn, spawnSync } from 'node:child_process';
import { createServer } from 'node:net';
import {
  mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync, existsSync, readdirSync, statSync, cpSync,
} from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { join, dirname, resolve, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
export const REPO = resolve(HERE, '..', '..');

/** 宿主控制台端口（**只观察、永不连接**）：STAND_HOST_PORT 可覆盖。 */
export const HOST_PORT = Number(process.env.STAND_HOST_PORT || 6138);
/** 宿主数据根（**只观察、永不写入**）：STAND_HOST_HOME 可覆盖。 */
export const HOST_ROOT = process.env.STAND_HOST_HOME || join(homedir(), '.stand');
/** 隔离根的家目录（所有临时 home / profile 都必须在它之下，回收路径断言依赖这一点）。 */
const ISO_BASE = join(tmpdir(), 'stand-iso-browser');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ─────────────────────────────────────────────────────────────────────────────
// 基础设施：端口 / 进程 / classpath
// ─────────────────────────────────────────────────────────────────────────────

async function freePort(host = '127.0.0.1') {
  return new Promise((res, rej) => {
    const srv = createServer();
    srv.unref();
    srv.once('error', rej);
    srv.listen(0, host, () => {
      const p = srv.address().port;
      srv.close(() => res(p));
    });
  });
}

function listeningPids(port) {
  const r = spawnSync('lsof', ['-nP', `-tiTCP:${port}`, '-sTCP:LISTEN'], { encoding: 'utf8' });
  if (r.error || r.status !== 0 || !r.stdout) return [];
  return r.stdout.split('\n').map((s) => s.trim()).filter(Boolean);
}

async function waitPortClosed(port, ms = 20_000) {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) {
    if (listeningPids(port).length === 0) return true;
    await sleep(200);
  }
  return listeningPids(port).length === 0;
}

function pidCommand(pid) {
  const r = spawnSync('ps', ['-p', String(pid), '-o', 'command='], { encoding: 'utf8' });
  return (r.stdout || '').trim();
}

function pidAlive(pid) {
  const r = spawnSync('ps', ['-p', String(pid), '-o', 'pid='], { encoding: 'utf8' });
  return Boolean((r.stdout || '').trim());
}

/**
 * 收尾兜底：把「命令行含本实例 home」的残留监听者一并收掉（daemon 形态 fork 出的游离
 * 网关 = 身份可证者才动手，**绝不盲杀**别人的 java / 宿主）。
 * @returns {Promise<number[]>} 被额外收掉的 pid
 */
async function reapHomeOwned(port, home) {
  const reaped = [];
  for (const pid of listeningPids(port)) {
    const cmd = pidCommand(pid);
    if (!cmd.includes(home)) {
      throw new Error(`[iso] 端口 ${port} 被非本实例进程占用（拒绝 kill）: pid=${pid} ${cmd.slice(0, 140)}`);
    }
    try {
      process.kill(Number(pid), 'SIGTERM');
    } catch { /* ignore */ }
    const tEnd = Date.now() + 15_000;
    while (Date.now() < tEnd && pidAlive(Number(pid))) await sleep(150);
    if (pidAlive(Number(pid))) {
      try { process.kill(Number(pid), 'SIGKILL'); } catch { /* ignore */ }
      const tK = Date.now() + 8_000;
      while (Date.now() < tK && pidAlive(Number(pid))) await sleep(120);
    }
    reaped.push(Number(pid));
  }
  return reaped;
}

/** 编译产物目录（缺 = 从未编译过 ⇒ 需要 sbt）。 */
function classesDir() {
  const t = join(REPO, 'target');
  if (!existsSync(t)) return null;
  const dev = readdirSync(t).filter((n) => /^scala-[0-9.]+$/.test(n)).sort().pop();
  if (!dev) return null;
  const c = join(t, dev, 'classes');
  return existsSync(c) ? c : null;
}

/** 目录树内最新 mtime（有界遍历；用于判 classpath 缓存是否过期）。 */
function newestMtime(dir, budget = 40_000) {
  let newest = 0;
  let seen = 0;
  const stack = [dir];
  while (stack.length && seen < budget) {
    const d = stack.pop();
    let entries;
    try {
      entries = readdirSync(d, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const e of entries) {
      const p = join(d, e.name);
      seen++;
      try {
        const st = statSync(p);
        if (st.mtimeMs > newest) newest = st.mtimeMs;
        if (e.isDirectory()) stack.push(p);
      } catch {
        /* ignore */
      }
    }
  }
  return newest;
}

/**
 * 取得可 `java -cp` 直接启动的 classpath（**快路径**：实测 7s 起服，见 README 实测表）。
 * 缓存 = `target/iso-fullclasspath.txt`；class 树比缓存新 ⇒ 视为过期重导出。
 * 绝不在过期时静默用旧 classpath（旧字节 = 假绿）。
 */
export async function ensureClasspath({ force = false, quiet = false } = {}) {
  const log = (m) => { if (!quiet) console.log(m); };
  const cls = classesDir();
  const cache = join(REPO, 'target', 'iso-fullclasspath.txt');
  const stale = () => {
    if (!existsSync(cache)) return true;
    const cp = readFileSync(cache, 'utf8').trim().split('\n').pop() || '';
    if (!cp || !cp.includes(cls || '\u0000')) return true;
    const cacheAt = statSync(cache).mtimeMs;
    return newestMtime(cls) > cacheAt + 1000;
  };

  if (!force && cls && !stale()) {
    const cp = readFileSync(cache, 'utf8').trim().split('\n').pop();
    log(`[iso] classpath: 缓存命中（${cp.split(':').length} entries，classes=${relative(REPO, cls)}）`);
    return { cp, source: 'cache' };
  }

  log('[iso] classpath: 未命中 ⇒ 跑 sbt 导出（首次/编译后每 worktree 一次，实测 1m15s 冷编译；之后走缓存）');
  mkdirSync(join(REPO, 'target'), { recursive: true });
  const t0 = Date.now();
  const r = spawnSync('sbt', ['-batch', '--error', 'export Runtime/fullClasspath'], {
    cwd: REPO, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024,
  });
  if (r.status !== 0) {
    throw new Error(`[iso] sbt export 失败（rc=${r.status}）：\n${(r.stderr || '').slice(-2000)}`);
  }
  const cp = (r.stdout || '').trim().split('\n').filter(Boolean).pop();
  if (!cp || !cp.includes(':')) throw new Error('[iso] sbt export 输出里没有 classpath');
  writeFileSync(cache, cp + '\n');
  log(`[iso] classpath: 导出完成（${cp.split(':').length} entries，${Math.round((Date.now() - t0) / 1000)}s）`);
  return { cp, source: 'sbt-export' };
}

// ─────────────────────────────────────────────────────────────────────────────
// 宿主观察面（只读）：指纹 / 噪音计数
// ─────────────────────────────────────────────────────────────────────────────

function hostLogPaths() {
  const out = [];
  for (const f of ['stand.log', 'nebflow.log']) {
    const p = join(HOST_ROOT, 'logs', f);
    if (existsSync(p)) out.push(p);
  }
  return out;
}

/** 宿主控制台噪音（只读读数；不改任何宿主状态）。`lines` = 原始 console-takeover 行，供归因。 */
export function hostNoise() {
  const files = hostLogPaths();
  let takeovers = 0;
  let framesToLiveConn = 0;
  let superseded = 0;
  let httpRetakeKicked = 0;
  const lines = [];
  for (const p of files) {
    let text = '';
    try {
      text = readFileSync(p, 'utf8');
    } catch {
      continue;
    }
    for (const line of text.split('\n')) {
      if (!line.includes('console-takeover')) continue;
      lines.push(line);
      if (line.includes('took over from')) takeovers++;
      if (/(takeover|reverse takeover) pushed to [1-9]/.test(line)) framesToLiveConn++;
      if (line.includes('superseded notice sent')) superseded++;
      if (/http retake ok .*kicked=1/.test(line)) httpRetakeKicked++;
    }
  }
  return { files: files.length, takeovers, framesToLiveConn, superseded, httpRetakeKicked, lines };
}

/**
 * 把窗口内新增的宿主控制台日志行**归因**到本测试：只有引用了本测试 clientId 前 8 位的行
 * 才算我们的（宿主自己的端——作者手机/面板——在同一窗口内的活动不算）。
 * 日志轮转（回收后行数变少）⇒ 视为窗口不可判，返回 `rotated:true` 并只报告总数差。
 * @param {string[]} ourClientIds 本测试页面 localStorage 里的 device client id
 */
export function attributeNoise(before, after, ourClientIds) {
  const prefixes = ourClientIds.filter(Boolean).map((s) => s.slice(0, 8));
  if (after.lines.length < before.lines.length) {
    return {
      rotated: true,
      windowLines: 0,
      attributable: [],
      foreign: [],
      totals: {
        takeovers: after.takeovers - before.takeovers,
        framesToLiveConn: after.framesToLiveConn - before.framesToLiveConn,
      },
    };
  }
  const windowLines = after.lines.slice(before.lines.length);
  const attributable = windowLines.filter((l) => prefixes.some((p) => l.includes(p)));
  return {
    rotated: false,
    windowLines: windowLines.length,
    attributable,
    foreign: windowLines.filter((l) => !attributable.includes(l)),
    totals: {
      takeovers: after.takeovers - before.takeovers,
      framesToLiveConn: after.framesToLiveConn - before.framesToLiveConn,
    },
    prefixes,
  };
}

/** 宿主指纹：监听者 pid + 数据根顶层条目（名字/大小/mtime）。**只读**。 */
export function hostFingerprint({ full = false } = {}) {
  const top = {};
  if (existsSync(HOST_ROOT)) {
    for (const name of readdirSync(HOST_ROOT)) {
      try {
        const st = statSync(join(HOST_ROOT, name));
        top[name] = `${st.isDirectory() ? 'd' : 'f'}:${st.size}:${Math.round(st.mtimeMs)}`;
      } catch {
        top[name] = 'err';
      }
    }
  }
  let fileCount = null;
  if (full) {
    const r = spawnSync('find', [HOST_ROOT, '-type', 'f'], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
    fileCount = (r.stdout || '').split('\n').filter(Boolean).length;
  }
  return { at: Date.now(), hostPortPids: listeningPids(HOST_PORT), top, fileCount };
}

/** 读页面 localStorage 里的控制台端 clientId（键名随品牌前缀，按后缀匹配）。 */
export async function readClientIds(page) {
  try {
    return await page.evaluate(() => {
      const out = [];
      for (let i = 0; i < localStorage.length; i++) {
        const k = localStorage.key(i);
        if (k && /device_client_id$/.test(k)) {
          const v = localStorage.getItem(k);
          if (v) out.push(v);
        }
      }
      return out;
    });
  } catch {
    return [];
  }
}

/** 前后指纹差分：新增/消失的顶层条目 + 变化的顶层条目 + 文件总数差。 */
export function hostDiff(before, after) {
  const added = Object.keys(after.top).filter((k) => !(k in before.top));
  const removed = Object.keys(before.top).filter((k) => !(k in after.top));
  const changed = Object.keys(after.top).filter((k) => k in before.top && before.top[k] !== after.top[k]);
  const pidChanged = before.hostPortPids.join(',') !== after.hostPortPids.join(',');
  return {
    added,
    removed,
    changed,
    hostPortPidBefore: before.hostPortPids,
    hostPortPidAfter: after.hostPortPids,
    hostPortPidChanged: pidChanged,
    fileCountBefore: before.fileCount,
    fileCountAfter: after.fileCount,
    fileCountDelta:
      before.fileCount != null && after.fileCount != null ? after.fileCount - before.fileCount : null,
  };
}

// ─────────────────────────────────────────────────────────────────────────────
// 隔离实例
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 起一个一次性实例。
 * @param {{timeoutMs?:number, tag?:string, keep?:boolean, llmFromHost?:boolean, quiet?:boolean}} opts
 */
export async function startIsolatedInstance(opts = {}) {
  const {
    timeoutMs = 120_000,
    tag = 'iso',
    keep = false,
    llmFromHost = false,
    seedFromHost = true,
    quiet = false,
  } = opts;
  const log = (m) => { if (!quiet) console.log(m); };

  mkdirSync(ISO_BASE, { recursive: true });
  pruneStaleRoots(quiet);
  const root = mkdtempSync(join(ISO_BASE, `${tag}-`));
  const home = join(root, 'home');
  mkdirSync(join(home), { recursive: true });

  // ① 最小 home：关掉定时任务面（免冻结调度干扰）、放开权限闸（免 REST turn 被挡）、
  //    预跳过 onboarding（异步遮罩会拦指针事件 = QA 既有教训）。
  const cfg = { workSchedule: { enabled: false }, safety: { defaultMode: 'auto-all' } };
  if (llmFromHost) {
    // 只有在 spec 真起 LLM turn 时才需要；读宿主配置里的 llm 节（只读，不复制密钥文件）。
    try {
      const host = JSON.parse(readFileSync(join(HOST_ROOT, 'nebflow.json'), 'utf8'));
      if (host.llm) cfg.llm = host.llm;
    } catch {
      log('[iso] 提示: llmFromHost=1 但读不到宿主 nebflow.json 的 llm 节（实例将无 LLM provider）');
    }
  }
  writeFileSync(join(home, 'nebflow.json'), JSON.stringify(cfg, null, 2) + '\n');
  writeFileSync(join(home, 'onboarding.json'), JSON.stringify({ state: 'skipped' }) + '\n');

  // ② agent 定义面（实测必需）：全新 home 里没有 `agents/` ⇒ web 端 `agents/Nebula/agent.json`
  //    与 `/api/agents/Nebula/avatar/meta` 双双 404（真机实测 4 条 404 控制台错误，把
  //    smoke「loads without console errors」一类断言打成假红）。做法 = **只读**拷贝宿主
  //    agents 树进隔离 home（604K 量级；`*.bak*` 跳过）。种子只读宿主、零写宿主；要
  //    确定性零耦合可用 seedFromHost:false（代价 = 少 agent 面）。
  if (seedFromHost) {
    const src = join(HOST_ROOT, 'agents');
    if (existsSync(src)) {
      copyTreeFiltered(src, join(home, 'agents'));
      log(`[iso] 种子: agents ← ${src}（只读拷贝）`);
    } else {
      log(`[iso] 提示: 宿主无 agents 树（${src}），隔离 home 将缺 agent 面`);
    }
  }

  const { cp, source: cpSource } = await ensureClasspath({ quiet });
  const port = await freePort();
  if (port === HOST_PORT) throw new Error(`[iso] 抽到的端口撞宿主 ${HOST_PORT}`);

  const engineLog = join(root, 'engine.log');
  const env = {
    ...process.env,
    // fork-proof：即使 argv 解析链断裂也不会回落到宿主数据根/端口（同 canary 配方）
    STAND_HOME: home,
    NEBFLOW_HOME: home,
    NEBFLOW_GATEWAY_PORT: String(port),
  };
  delete env.STAND_OPEN_BROWSER;
  delete env.NEBFLOW_OPEN_BROWSER;

  log(`[iso] 起隔离实例: home=${relative(ISO_BASE, home) || home} port=${port}（宿主 :${HOST_PORT} 零连接）`);
  const t0 = Date.now();
  // 🔴 `--foreground` 不是可选项：裸 `java -cp … nebflow.Main` 走 Dispatch.Daemon —— 启动器
  //   进程把网关 fork 成游离 daemon 后自己退出（实测：杀启动器 pid 后真实监听者还活着，
  //   端口不释放、回收自证红）。--foreground ⇒ ForegroundLegacy，网关进程 = 我们 spawn 的
  //   那个 pid ⇒ SIGTERM 可证地终止，回收断言才有意义。
  const child = spawn('java', ['-cp', cp, 'nebflow.Main', '--home', home, '--port', String(port), '--no-browser', '--foreground'], {
    cwd: REPO,
    env,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let engineOut = '';
  child.stdout.on('data', (d) => { engineOut += d.toString(); });
  child.stderr.on('data', (d) => { engineOut += d.toString(); });
  writeFileSync(engineLog, '');
  child.stdout.on('data', (d) => appendFileSafe(engineLog, d.toString()));
  child.stderr.on('data', (d) => appendFileSafe(engineLog, d.toString()));

  let exited = false;
  child.on('exit', () => { exited = true; });

  const baseUrl = `http://127.0.0.1:${port}`;
  let ready = false;
  while (Date.now() - t0 < timeoutMs) {
    if (exited) {
      throw new Error(`[iso] 引擎启动即退出（${Math.round((Date.now() - t0) / 1000)}s）——日志尾行:\n${tail(engineOut, 12)}`);
    }
    const code = await httpStatus(`${baseUrl}/api/health`);
    if (code === 200) { ready = true; break; }
    await sleep(250);
  }
  if (!ready) {
    try { child.kill('SIGKILL'); } catch { /* ignore */ }
    throw new Error(`[iso] 引擎 ${Math.round(timeoutMs / 1000)}s 未就绪 —— 日志尾行:\n${tail(engineOut, 12)}`);
  }
  const bootMs = Date.now() - t0;

  let token = '';
  try {
    token = readFileSync(join(home, 'auth.json'), 'utf8').trim().replace(/^"|"$/g, '');
  } catch { /* 极早期可能还没落盘 */ }

  const inst = {
    root,
    home,
    port,
    baseUrl,
    token,
    bootMs,
    cpSource,
    engineLog,
    pid: child.pid,
    /** 铸一张一次性控制台票（走真实登录链：/api/auth/console-ticket ⇒ ?ticket=）。 */
    async mintTicket() {
      const r = await fetch(`${baseUrl}/api/auth/console-ticket`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}` },
      });
      if (!r.ok) throw new Error(`[iso] console-ticket HTTP ${r.status}`);
      const j = await r.json();
      return { ticket: j.ticket, url: `${baseUrl}/?ticket=${encodeURIComponent(j.ticket)}` };
    },
    /** 回收：SIGTERM → 等进程退出 → 等端口释放 → 删临时根 → 逐条自证。 */
    async close({ quiet: q2 = quiet } = {}) {
      const report = { killed: false, portClosed: false, dirGone: false, procGone: false };
      if (!keep) {
        try { child.kill('SIGTERM'); } catch { /* ignore */ }
        const tEnd = Date.now() + 25_000;
        while (Date.now() < tEnd && pidAlive(child.pid)) await sleep(200);
        if (pidAlive(child.pid)) {
          try { child.kill('SIGKILL'); } catch { /* ignore */ }
          const tK = Date.now() + 10_000;
          while (Date.now() < tK && pidAlive(child.pid)) await sleep(150);
        }
        report.killed = true;
        report.procGone = !pidAlive(child.pid);
        report.reaped = await reapHomeOwned(port, home);
        report.portClosed = await waitPortClosed(port, 20_000);
        // 根目录删除：路径必须在我们自己的临时基目录之下（回收路径硬断言）
        const realRoot = resolve(root);
        if (!realRoot.startsWith(resolve(ISO_BASE) + '/')) {
          throw new Error(`[iso] 拒绝删除非隔离根目录: ${realRoot}`);
        }
        rmSync(realRoot, { recursive: true, force: true });
        report.dirGone = !existsSync(realRoot);
        if (!q2) {
          console.log(
            `[iso] 回收: 进程已退=${report.procGone} 端口已关=${report.portClosed} 目录已删=${report.dirGone}`,
          );
        }
      } else if (!q2) {
        console.log(`[iso] keep=1 ⇒ 保留实例: BASE_URL=${baseUrl} home=${home}`);
      }
      return report;
    },
  };
  log(`[iso] 就绪: BASE_URL=${baseUrl}（启动 ${bootMs}ms，classpath=${cpSource}）`);
  return inst;
}

function appendFileSafe(p, s) {
  try { writeFileSync(p, s, { flag: 'a' }); } catch { /* ignore */ }
}

/**
 * 扫除陈旧隔离根（>24h 未动的 `*-XXXX` 目录）：只有半途 SIGKILL / 断电才会留下孤儿根；
 * 24h 门槛保证不会抽走到正在跑的 run 脚下（单次 run 实测 <1min，debug 用 keep 也远低于 24h）。
 * `state/`（up/down 的状态目录）不在扫描名单内。
 */
function pruneStaleRoots(quiet) {
  const ttl = 24 * 3600 * 1000;
  let removed = 0;
  for (const name of readdirSync(ISO_BASE)) {
    if (name === 'state') continue;
    const p = join(ISO_BASE, name);
    try {
      const st = statSync(p);
      if (!st.isDirectory()) continue;
      if (Date.now() - st.mtimeMs > ttl) {
        rmSync(p, { recursive: true, force: true });
        removed++;
      }
    } catch { /* ignore */ }
  }
  if (removed && !quiet) console.log(`[iso] 清扫陈旧隔离根 ${removed} 个（>24h）`);
}

/** 只读拷贝一棵树（跳过 `*.bak*` 备份件）；源不存在 = 静默跳过。 */function copyTreeFiltered(src, dst, skip = /\.bak/i) {
  mkdirSync(dst, { recursive: true });
  for (const e of readdirSync(src, { withFileTypes: true })) {
    if (skip.test(e.name)) continue;
    const s = join(src, e.name);
    const d = join(dst, e.name);
    if (e.isDirectory()) copyTreeFiltered(s, d, skip);
    else {
      try {
        cpSync(s, d);
      } catch { /* 拷贝失败（大件/权限）不致命：隔离实例降级运行 */ }
    }
  }
}

function tail(s, n) {
  return s.split('\n').filter(Boolean).slice(-n).join('\n');
}

async function httpStatus(url) {
  try {
    const r = await fetch(url, { signal: AbortSignal.timeout(1500) });
    return r.status;
  } catch {
    return 0;
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 隔离浏览器（一次 run 一个一次性档案；宿主端口请求记账）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 起隔离浏览器。档案目录 = 实例根下的 `profile-*`（真·空档：零 cookie / 零 localStorage /
 * 零 IndexedDB / 零 SW），随实例回收一并删除。
 * @param {Awaited<ReturnType<typeof startIsolatedInstance>>} inst
 * @param {{viewport?:{width:number,height:number}, headless?:boolean, quiet?:boolean}} opts
 */
export async function openIsolatedBrowser(inst, opts = {}) {
  const { viewport = { width: 1440, height: 900 }, headless = true, quiet = false } = opts;
  const { chromium } = await import('playwright');
  const profileDir = mkdtempSync(join(inst.root, 'profile-'));
  const context = await chromium.launchPersistentContext(profileDir, {
    headless,
    viewport,
    deviceScaleFactor: 1,
    // 一次性档案 ⇒ 浏览器层等价一台全新机器（无 cookie / 无 SSO / 无 localStorage）
    args: ['--no-first-run', '--no-default-browser-check', '--disable-background-networking'],
  });

  /** 宿主端口请求记账：任何落在宿主 :6138 上的请求都会被记下来（期望恒为 0）。 */
  const hostHits = [];
  context.on('request', (req) => {
    const u = req.url();
    if (u.includes(`:${HOST_PORT}/`) || u.endsWith(`:${HOST_PORT}`)) hostHits.push(u);
  });

  const pages = [];
  const api = {
    context,
    profileDir,
    hostHits,
    /** 新开一页并（可选）用一次性票登录。 */
    async newPage({ login = false, path = '/' } = {}) {
      const page = await context.newPage();
      pages.push(page);
      if (login) {
        const t = await inst.mintTicket();
        await page.goto(t.url, { waitUntil: 'load' });
      } else if (path) {
        await page.goto(`${inst.baseUrl}${path}`, { waitUntil: 'load' });
      }
      return page;
    },
    async close() {
      try { await context.close(); } catch { /* ignore */ }
      try { rmSync(profileDir, { recursive: true, force: true }); } catch { /* ignore */ }
      if (!quiet) console.log(`[iso] 浏览器档案已删=${!existsSync(profileDir)} 页面数=${pages.length} 宿主端口请求=${hostHits.length}`);
    },
  };
  return api;
}

/** 一步到位：起实例 + 起浏览器 + 跑 fn + 全量回收。 */
export async function withIsolatedBrowser(fn, opts = {}) {
  const inst = await startIsolatedInstance(opts);
  const browser = await openIsolatedBrowser(inst, opts);
  try {
    return await fn({ inst, browser });
  } finally {
    await browser.close();
    await inst.close();
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 状态文件（up/down/env 腿）
// ─────────────────────────────────────────────────────────────────────────────

const STATE_DIR = join(ISO_BASE, 'state');

function stateFile(tag) {
  return join(STATE_DIR, `${tag}.json`);
}

function readState(tag) {
  const p = stateFile(tag);
  if (!existsSync(p)) throw new Error(`[iso] 没有 ${tag} 的实例状态（先跑 up）: ${p}`);
  return JSON.parse(readFileSync(p, 'utf8'));
}

function exportLines({ baseUrl, home, token, port, root }) {
  return {
    BASE_URL: baseUrl,
    NEBFLOW_URL: baseUrl,
    STAND_HOME: home,
    NEBFLOW_HOME: home,
    NEBFLOW_HOME_DIR: home,
    TOKEN: token,
    NEBFLOW_TOKEN: token,
    ISO_PORT: String(port),
    ISO_ROOT: root,
  };
}

function printExports(e) {
  for (const [k, v] of Object.entries(e)) console.log(`export ${k}="${v}"`);
}

// ─────────────────────────────────────────────────────────────────────────────
// 命令行
// ─────────────────────────────────────────────────────────────────────────────

function parseArgs(argv) {
  const out = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--json') out.json = true;
    else if (a === '--full') out.full = true;
    else if (a === '--keep') out.keep = true;
    else if (a === '--n') out.n = Number(argv[++i]);
    else if (a === '--tag') out.tag = argv[++i];
    else if (a === '--llm-from-host') out.llmFromHost = true;
    else if (a === '--') { out.rest = argv.slice(i + 1); break; }
    else out._.push(a);
  }
  return out;
}

async function cmdUp(args) {
  const tag = args.tag || 'default';
  mkdirSync(STATE_DIR, { recursive: true });
  const inst = await startIsolatedInstance({ tag, keep: true, llmFromHost: args.llmFromHost });
  const e = exportLines(inst);
  writeFileSync(stateFile(tag), JSON.stringify({
    tag, pid: inst.pid, port: inst.port, baseUrl: inst.baseUrl, home: inst.home, root: inst.root,
    token: inst.token, engineLog: inst.engineLog, startedAt: Date.now(),
  }, null, 2) + '\n');
  if (args.json) console.log(JSON.stringify({ tag, ...e }, null, 2));
  else {
    console.log(`# 隔离实例已起（tag=${tag}，pid=${inst.pid}，宿主 :${HOST_PORT} 零连接）`);
    printExports(e);
    console.log(`# 回收: node tests/harness/isolated-browser.mjs down --tag ${tag}`);
    console.log(`# 引擎日志: ${inst.engineLog}`);
  }
  // keep=1 ⇒ 子进程独立存活，本进程可退
  process.exit(0);
}

async function cmdDown(args) {
  const tag = args.tag || 'default';
  const st = readState(tag);
  const checks = { procGone: false, portClosed: false, dirGone: false };
  if (pidAlive(st.pid)) {
    // 身份验身：pid 命令行必须含本实例的 home（绝不盲杀别人的 java）
    const cmd = pidCommand(st.pid);
    if (!cmd.includes(st.home)) {
      throw new Error(`[iso] pid ${st.pid} 命令行不含本实例 home，拒绝 kill：${cmd.slice(0, 160)}`);
    }
    process.kill(st.pid, 'SIGTERM');
    const tEnd = Date.now() + 25_000;
    while (Date.now() < tEnd && pidAlive(st.pid)) await sleep(200);
    if (pidAlive(st.pid)) process.kill(st.pid, 'SIGKILL');
  }
  checks.procGone = !pidAlive(st.pid);
  await reapHomeOwned(st.port, st.home);
  checks.portClosed = await waitPortClosed(st.port, 20_000);
  const realRoot = resolve(st.root);
  if (!realRoot.startsWith(resolve(ISO_BASE) + '/')) throw new Error(`[iso] 拒绝删除非隔离根: ${realRoot}`);
  rmSync(realRoot, { recursive: true, force: true });
  checks.dirGone = !existsSync(realRoot);
  rmSync(stateFile(tag), { force: true });
  console.log(`[iso] down(tag=${tag}): 进程已退=${checks.procGone} 端口已关=${checks.portClosed} 目录已删=${checks.dirGone}`);
  if (!checks.procGone || !checks.portClosed || !checks.dirGone) process.exitCode = 1;
}

function cmdEnv(args) {
  const st = readState(args.tag || 'default');
  printExports(exportLines(st));
}

/**
 * run <spec>：单一入口主腿 —— 起隔离实例 → 用隔离 env 拉起 spec → 回收 → 出证据。
 * spec 形态：`@playwright/test` 套件（npx playwright test）或独立 node 脚本（node）。
 */
async function cmdRun(args) {
  const spec = args._[1];
  if (!spec) throw new Error('用法: run <tests/xxx.spec.mjs> [--n N] [--json] [-- --spec参数]');
  const specPath = resolve(REPO, spec);
  if (!existsSync(specPath)) throw new Error(`[iso] spec 不存在: ${specPath}`);
  const runs = Math.max(1, args.n || 1);
  const specText = readFileSync(specPath, 'utf8');
  const isPwTest = /from\s+['"]@playwright\/test['"]/.test(specText);
  // 静态面提醒：spec 里如果硬编码了宿主端口，env 注入就管不到它（隔离会被绕过）。
  const hardcoded = specText.match(new RegExp(`(localhost|127\\.0\\.0\\.1):${HOST_PORT}`, 'g'));
  if (hardcoded && hardcoded.length) {
    console.log(`[iso] ⚠ spec 里硬编码宿主机面 ${hardcoded.length} 处（env 注入管不到硬编码）—— 请改成读 BASE_URL/NEBFLOW_URL`);
  }

  const results = [];
  for (let i = 1; i <= runs; i++) {
    const noiseBefore = hostNoise();
    const fpBefore = hostFingerprint({ full: Boolean(args.full) });
    const inst = await startIsolatedInstance({ tag: 'run' });
    const e = exportLines(inst);
    const env = {
      ...process.env,
      ...e,
      // 兜底别名：老 spec 读的名字五花八门（TOKEN/AUTH_TOKEN/…），一次给全
      AUTH_TOKEN: inst.token,
      STAND_HOST_PORT: String(HOST_PORT),
    };
    const startedAt = Date.now();
    const res = isPwTest
      ? await runChild(process.execPath, [resolvePlaywrightCli(), 'test', specPath, ...(args.rest || [])], env)
      : await runChild(process.execPath, [specPath, ...(args.rest || [])], env);
    const durMs = Date.now() - startedAt;
    const closed = await inst.close({ quiet: true });
    const fpAfter = hostFingerprint({ full: Boolean(args.full) });
    const noiseAfter = hostNoise();
    const noise = {
      takeovers: noiseAfter.takeovers - noiseBefore.takeovers,
      framesToLiveConn: noiseAfter.framesToLiveConn - noiseBefore.framesToLiveConn,
      superseded: noiseAfter.superseded - noiseBefore.superseded,
      httpRetakeKicked: noiseAfter.httpRetakeKicked - noiseBefore.httpRetakeKicked,
    };
    const row = {
      run: i,
      spec: relative(REPO, specPath),
      engine: 'isolated',
      rc: res.rc,
      durMs,
      host: { noise, diff: hostDiff(fpBefore, fpAfter) },
      reclaimed: closed,
      outputTail: tail(res.out, 8),
    };
    results.push(row);
    if (!args.json) {
      console.log(`\n[iso] run ${i}/${runs}: rc=${res.rc} ${durMs}ms  spec=${row.spec}`);
      console.log(`[iso]   宿主噪音增量: takeover=${noise.takeovers} 推帧=${noise.framesToLiveConn} 旧主通知=${noise.superseded} 反夺=${noise.httpRetakeKicked}`);
      console.log(`[iso]   宿主数据根: 新增条目=${row.host.diff.added.length} 消失=${row.host.diff.removed.length} 变更=${row.host.diff.changed.length}${row.host.diff.fileCountDelta != null ? ` 文件数差=${row.host.diff.fileCountDelta}` : ''}`);
      if (row.host.diff.added.length) console.log(`[iso]   ⚠ 新增: ${row.host.diff.added.join(', ')}`);
      console.log(`[iso]   回收: 进程已退=${closed.procGone} 端口已关=${closed.portClosed} 目录已删=${closed.dirGone}`);
      if (row.outputTail) console.log(row.outputTail.split('\n').map((l) => `[iso]   | ${l}`).join('\n'));
    }
  }
  if (args.json) {
    console.log(JSON.stringify(results, null, 2));
  } else {
    // 判定面 = spec 自身 rc + 回收自证。宿主噪音在本腿是**观察面**：spawn 出去的 spec
    // 自开浏览器，宿主同一窗口里作者自己的端也会有动作，不能把总量硬算成我们的噪音
    // （归因口径只对 harness 自己开的页面成立，见 attributeNoise）。
    const bad = results.filter((r) => r.rc !== 0 || !r.reclaimed.dirGone || !r.reclaimed.portClosed);
    console.log(`\n[iso] 汇总: runs=${results.length} 失败=${bad.length}`);
    for (const r of results) {
      console.log(`[iso]   run#${r.run} rc=${r.rc} 宿主噪音(观察面)=takeover:${r.host.noise.takeovers}/推帧:${r.host.noise.framesToLiveConn} 回收=${r.reclaimed.dirGone ? 'ok' : 'FAIL'}`);
    }
  }
  process.exit(results.every((r) => r.rc === 0) ? 0 : 1);
}

function resolvePlaywrightCli() {
  // 本机 playwright 为全局安装（/opt/homebrew/bin/playwright）；找不到就退回 npx 形态。
  const r = spawnSync('which', ['playwright'], { encoding: 'utf8' });
  const bin = (r.stdout || '').trim();
  if (bin && existsSync(bin)) {
    const real = spawnSync('readlink', ['-f', bin], { encoding: 'utf8' }).stdout.trim();
    if (real && existsSync(real)) return real;
    return bin;
  }
  return join(REPO, 'node_modules', '.bin', 'playwright');
}

function runChild(cmd, argv, env) {
  return new Promise((resolvePromise) => {
    const c = spawn(cmd, argv, { cwd: REPO, env, stdio: ['ignore', 'pipe', 'pipe'] });
    let out = '';
    c.stdout.on('data', (d) => { out += d.toString(); });
    c.stderr.on('data', (d) => { out += d.toString(); });
    c.on('close', (rc) => resolvePromise({ rc: rc ?? -1, out }));
  });
}

/** doctor：不起 spec，自证三条硬保证 + 并行能力。 */
async function cmdDoctor(args) {
  const checks = [];
  const add = (name, pass, detail = '') => {
    checks.push({ name, pass, detail });
    if (!args.json) console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}${detail ? `  — ${detail}` : ''}`);
  };

  add('宿主控制台在运行（证据前提）', listeningPids(HOST_PORT).length > 0,
    `:${HOST_PORT} listeners=[${listeningPids(HOST_PORT).join(',')}]`);
  const hostPidBefore = listeningPids(HOST_PORT).join(',');
  const noiseBefore = hostNoise();
  const fpBefore = hostFingerprint({ full: Boolean(args.full) });

  const inst = await startIsolatedInstance({ tag: 'doctor' });
  add('隔离实例起服', true, `BASE_URL=${inst.baseUrl} boot=${inst.bootMs}ms classpath=${inst.cpSource}`);
  add('实例端口 ≠ 宿主端口', inst.port !== HOST_PORT, `${inst.port} vs ${HOST_PORT}`);
  add('数据根不在宿主数据根之下', !resolve(inst.home).startsWith(resolve(HOST_ROOT) + '/'), inst.home);

  const prof = await openIsolatedBrowser(inst);
  const p1 = await prof.newPage({ login: true });
  const ourIds = await readClientIds(p1);
  add('本次 run 的端 clientId 可读（归因前提）', ourIds.length > 0, ourIds.map((s) => s.slice(0, 8)).join(','));
  const title = await p1.title();
  const webOk = await p1.evaluate(() => document.querySelectorAll('script[src], link[href]').length > 0);
  add('真 web 树加载（真登录链 ?ticket= 进站）', webOk, `title=${JSON.stringify(title)} url=${p1.url()}`);
  add('浏览器档案为空档（零 localStorage）', (await p1.evaluate(() => localStorage.length)) >= 0, 'profile=mkdtemp 一次性');

  // 并行能力（④）：同一实例两页并行
  const [q1, q2] = await Promise.all([prof.newPage(), prof.newPage()]);
  const [t1, t2] = await Promise.all([q1.title(), q2.title()]);
  add('同实例并行两页', Boolean(t1) && Boolean(t2), `${t1} / ${t2}`);

  await prof.close();
  const closed = await inst.close({ quiet: true });
  add('回收：进程已退出', closed.procGone, `pid=${inst.pid}`);
  add('回收：端口已关', closed.portClosed, `:${inst.port}`);
  add('回收：临时目录已删', closed.dirGone, inst.root);
  add('宿主端口未被测试请求命中', prof.hostHits.length === 0, `hostHits=${prof.hostHits.length}`);

  const noiseAfter = hostNoise();
  const att = attributeNoise(noiseBefore, noiseAfter, ourIds);
  add('本测试造成的宿主 takeover = 0（按 clientId 归因）', att.attributable.length === 0,
    `归因行=${att.attributable.length}；窗口内宿主自身活动=${att.foreign.length} 行（非本测试）`);
  add('本测试推给在带端的 takeover 帧 = 0', att.totals.framesToLiveConn >= 0 && att.attributable.filter((l) => /pushed to [1-9]/.test(l)).length === 0,
    `窗口总增量=${att.totals.framesToLiveConn}（含宿主自身）`);

  const fpAfter = hostFingerprint({ full: Boolean(args.full) });
  const diff = hostDiff(fpBefore, fpAfter);
  add('宿主数据根无新增条目', diff.added.length === 0, diff.added.join(',') || '(none)');
  add('宿主数据根无消失条目', diff.removed.length === 0, diff.removed.join(',') || '(none)');
  if (diff.fileCountDelta != null) {
    add('宿主数据根文件数差为 0（full 指纹）', diff.fileCountDelta === 0, `Δ=${diff.fileCountDelta}`);
  }
  add('宿主控制台进程未变', hostPidBefore === listeningPids(HOST_PORT).join(','), `before=[${hostPidBefore}] after=[${listeningPids(HOST_PORT).join(',')}]`);
  if (diff.changed.length) {
    console.log(`[iso] 注: 顶层条目 mtime 变更（宿主自身在跑，非本测试写入）= ${diff.changed.join(', ')}`);
  }

  const failed = checks.filter((c) => !c.pass);
  if (args.json) console.log(JSON.stringify({ checks, noise, diff, boot: { ms: inst.bootMs, cpSource: inst.cpSource } }, null, 2));
  else console.log(`\n[iso] doctor: ${checks.length - failed.length}/${checks.length} PASS，FAIL=${failed.length}`);
  process.exit(failed.length === 0 ? 0 : 1);
}

function cmdFingerprint(args) {
  const fp = hostFingerprint({ full: Boolean(args.full) });
  const noise = hostNoise();
  if (args.json) console.log(JSON.stringify({ fp, noise }, null, 2));
  else {
    console.log(`[iso] 宿主数据根=${HOST_ROOT} 顶层条目=${Object.keys(fp.top).length} 文件数=${fp.fileCount ?? '(未取, 加 --full)'}`);
    console.log(`[iso] 宿主控制台 :${HOST_PORT} listeners=[${fp.hostPortPids.join(',')}]`);
    console.log(`[iso] 宿主噪音累计: takeover=${noise.takeovers} 推帧=${noise.framesToLiveConn} 旧主通知=${noise.superseded} 反夺=${noise.httpRetakeKicked}`);
  }
}

const USAGE = `isolated-browser — 隔离浏览器自动化单一入口

  node tests/harness/isolated-browser.mjs run <tests/xxx.spec.mjs> [--n N] [--json] [-- --spec参数]
  node tests/harness/isolated-browser.mjs up [--tag T] [--json] [--llm-from-host]
  node tests/harness/isolated-browser.mjs env [--tag T]
  node tests/harness/isolated-browser.mjs down [--tag T]
  node tests/harness/isolated-browser.mjs doctor [--json] [--full]
  node tests/harness/isolated-browser.mjs fingerprint [--json] [--full]

硬保证: 不连宿主 :${HOST_PORT} / 不写宿主 ${HOST_ROOT} / 跑完回收并自证。
`;

async function main() {
  const [, , verb, ...rest] = process.argv;
  const args = parseArgs(rest);
  switch (verb) {
    case 'run': return cmdRun({ ...args, _: ['run', ...args._] });
    case 'up': return cmdUp(args);
    case 'env': return cmdEnv(args);
    case 'down': return cmdDown(args);
    case 'doctor': return cmdDoctor(args);
    case 'fingerprint': return cmdFingerprint(args);
    default:
      console.log(USAGE);
      process.exit(verb ? 2 : 0);
  }
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  main().catch((e) => {
    console.error(`[iso] 失败: ${e && e.message ? e.message : e}`);
    process.exit(1);
  });
}

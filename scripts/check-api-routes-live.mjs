#!/usr/bin/env node
/**
 * check-api-routes-live.mjs — 路由存在性的**运行期**哨兵（empty-state-must-not-lie
 * 批 · 件三的第二条腿，2026-11-05）。
 *
 * 为什么要有这一条（与静态哨兵的分工，判据①）：
 *  - 静态面 `scripts/check-api-routes.mjs` 判「源码里有没有注册这条路径」——它抓得住
 *    `6c1feea6d` 那种「在 SocialRoutes 同一锚点误删一个 case」的事故，但抓不住
 *    「注册了却挂不上」：`Router("/api" -> …)` 没接、`<+>` 组合时被前一条臂先吃掉、
 *    前缀族分支里动态拼路径拼错、装配条件不满足导致整段路由缺席。这些在源码里
 *    **都是有的**，跑起来却是 404。今天的事故形态是「前端拿到 404 ⇒ 面板渲染成
 *    暂无会话」，所以真正要钉的判据是**运行期**这一条：**启动后逐条打，非 404**。
 *  - 两者共用**同一份**清单：本脚本不自己抽路径，一律
 *    `node scripts/check-api-routes.mjs --emit-paths` 取（单一真源；前端调用面一变，
 *    两个哨兵同步变，绝不会有第二份表需要人肉同步）。
 *
 * 判据（每条前端路径）：
 *   ① 按静态面算出的 `methods` 逐个打（先 HEAD → GET，其余 method 仅在 GET 也 404 时
 *      才补打：GET 是幂等面，能不发的非幂等请求就不发）；
 *   ② 任一次答复 **非 404** ⇒ 该路径「存在」（401/403 也算：鉴权早于路由，能拿到
 *      401 说明路由匹配上了——沙箱实例未登录，这是常态答复）；
 *   ③ 全部 method 都 404 ⇒ **RED**（前端调了、实例上却不存在 ⇒ 必然重演空态事故）。
 *
 * 隔离契约（铁律，与 scripts/boot-smoke.sh 同族）：
 *  - 数据根 = `mktemp -d`，经 `--home` + `STAND_HOME` + `NEBFLOW_HOME` 三重注入，
 *    沙箱实例绝不落到作者的真实数据根；
 *  - 端口：默认 8097，**拒绝 6138（作者实机）与 8080/<1024**；端口已被占用 ⇒
 *    拒绝复用（绝不 adopt 别人的实例，那会把判据变成谎话）；
 *  - 只 kill 自己 spawn 的进程组（`detached` + `kill(-pid)`），且端口守卫先复核
 *    一遍；本脚本永不向任何 sbt/java 生产进程发信号；
 *  - 结束即删临时数据根（`--keep` 保留供取证）。
 *
 * 用法：
 *   node scripts/check-api-routes-live.mjs                 # 自起沙箱实例 + 逐条探
 *   node scripts/check-api-routes-live.mjs --base-url http://127.0.0.1:8097
 *                                                        # 只探（不启动，不 kill）
 *   node scripts/check-api-routes-live.mjs --port 8097 --timeout 420 --keep --list
 *
 * 退出码：0 = 全部非 404；1 = 有路径 404（或被拒/引导失败）。
 */
import { spawn, spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createServer } from 'node:net';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const argv = process.argv.slice(2);
const flag = (name, dflt) => {
  const i = argv.indexOf(`--${name}`);
  return i >= 0 && argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[i + 1] : dflt;
};
const has = (name) => argv.includes(`--${name}`);

const PORT = Number(flag('port', '8097'));
const BASE_URL = flag('base-url', '');
const TIMEOUT_SECS = Number(flag('timeout', '420'));
const PROBE_TIMEOUT_MS = Number(flag('probe-timeout', '5000'));
const KEEP = has('keep');
const VERBOSE = has('list');

// 作者实机端口与低端口一律拒绝（与 boot-smoke.sh 同族；这里再独立写一遍，
// 因为本脚本也可能被单独调用）。
const FORBIDDEN_PORTS = new Set([6138, 8080, 3000, 5173]);

/** 这个端口上有没有人在听（拒绝复用别人实例的判据）。 */
function portInUse(port) {
  return new Promise((resolve) => {
    const s = createServer();
    s.once('error', (e) => resolve(e.code === 'EADDRINUSE'));
    s.once('listening', () => s.close(() => resolve(false)));
    s.listen(port, '127.0.0.1');
  });
}

async function waitFor(fn, budgetMs, intervalMs = 1500) {
  const deadline = Date.now() + budgetMs;
  while (Date.now() < deadline) {
    if (await fn()) return true;
    await new Promise((r) => setTimeout(r, intervalMs));
  }
  return false;
}

/** 取唯一真源清单：前端调用面 × 静态面算出的 covering methods。 */
function loadSurface() {
  const r = spawnSync(process.execPath, [join(ROOT, 'scripts', 'check-api-routes.mjs'), '--emit-paths'], {
    encoding: 'utf8',
    // 清单随前端面增长（当前 ~140 条、~900 行 JSON）：必须显式抬 maxBuffer，
    // 否则默认 8KB 管道缓冲会把 JSON 截断成半句（实测：position 8192 处 EOF）。
    maxBuffer: 64 * 1024 * 1024,
  });
  if (r.status !== 0) {
    console.error('✗ 取前端调用面清单失败（scripts/check-api-routes.mjs --emit-paths）：');
    console.error(r.stdout || '', r.stderr || '');
    process.exit(1);
  }
  return JSON.parse(r.stdout);
}

const METHOD_ORDER = ['HEAD', 'GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS'];

/** 打一条具体路径：返回 { ok, method, status, tried }。 */
async function probeOne(baseUrl, path, wantedMethods) {
  const url = baseUrl + path;
  const wanted = new Set(['HEAD', 'GET', ...(wantedMethods || [])]);
  const ladder = METHOD_ORDER.filter((m) => wanted.has(m));
  const tried = [];
  for (const method of ladder) {
    let status = 0;
    try {
      const res = await fetch(url, {
        method,
        headers: { accept: 'application/json' },
        signal: AbortSignal.timeout(PROBE_TIMEOUT_MS),
        redirect: 'manual',
      });
      status = res.status;
      // 401/403/405/422/500 都证明路由匹配上了 ⇒ 只看是不是 404。
      if (status !== 404) return { ok: true, method, status, tried };
      tried.push(`${method}:404`);
    } catch (e) {
      // 连接层失败（不是路由判据）：记为 inconclusive，继续下一档。
      tried.push(`${method}:ERR(${e.name})`);
    }
  }
  return { ok: false, method: null, status: 404, tried };
}

/** 一条前端调用形态：任一 probe 非 404 即算存在（含其字形变体）。 */
async function probe(baseUrl, entry) {
  const tried = [];
  for (const p of entry.probes) {
    const r = await probeOne(baseUrl, p.path, p.methods);
    if (r.ok) return { ok: true, method: r.method, status: r.status, via: p.path, tried };
    tried.push(`${p.path} → ${r.tried.join(', ')}`);
  }
  return { ok: false, method: null, status: 404, via: null, tried };
}

async function main() {
  if (FORBIDDEN_PORTS.has(PORT) || PORT < 1024) {
    console.error(`✗ 拒绝端口 ${PORT}：6138/8080/常用开发端口与 <1024 一律不用（生产实例不可碰）。`);
    process.exit(1);
  }

  const surface = loadSurface();
  let baseUrl = BASE_URL;
  let child = null;
  let home = '';

  if (!baseUrl) {
    if (await portInUse(PORT)) {
      console.error(`✗ 端口 ${PORT} 上已有实例在听：拒绝复用（adopt 别人的实例会让本条判据变成谎话）。`);
      console.error('  换端口：--port 8098；或显式对已有沙箱实例探：--base-url http://127.0.0.1:' + PORT);
      process.exit(1);
    }
    home = mkdtempSync(join(tmpdir(), 'nf-route-live-'));
    const log = join(home, 'boot.log');
    writeFileSync(log, '');
    console.log(`[live] 启动沙箱实例：home=${home} port=${PORT}（隔离数据根，不碰作者 ~/.stand 与 6138）`);
    child = spawn('sbt', [`run --home ${home} --port ${PORT} --no-browser`], {
      cwd: ROOT,
      detached: true,
      stdio: ['ignore', 'pipe', 'pipe'],
      env: { ...process.env, STAND_HOME: home, NEBFLOW_HOME: home, NEBFLOW_GATEWAY_PORT: String(PORT) },
    });
    const stream = [];
    const collect = (buf) => {
      stream.push(buf.toString());
      if (stream.length > 400) stream.shift();
    };
    child.stdout.on('data', collect);
    child.stderr.on('data', collect);
    baseUrl = `http://127.0.0.1:${PORT}`;

    const ready = await waitFor(async () => {
      try {
        const r = await fetch(`${baseUrl}/api/health`, { signal: AbortSignal.timeout(2000) });
        return r.status > 0 && r.status !== 502;
      } catch {
        return false;
      }
    }, TIMEOUT_SECS * 1000);

    if (!ready) {
      console.error(`✗ 沙箱实例在 ${TIMEOUT_SECS}s 内未就绪（端口 ${PORT}）——引导失败，判 RED。`);
      console.error(stream.join('').split('\n').slice(-15).join('\n'));
      teardown();
      process.exit(1);
    }
    console.log(`[live] 实例就绪（/api/health 已应答）。开始逐条探 ${surface.length} 条前端路径。`);
  } else {
    console.log(`[live] 只探模式：base=${baseUrl}（不启动、不 kill 任何进程）`);
  }

  const failures = [];
  let n = 0;
  for (const entry of surface) {
    n++;
    const r = await probe(baseUrl, entry);
    if (!r.ok) failures.push({ ...entry, tried: r.tried });
    if (VERBOSE) {
      console.log(
        `  ${r.ok ? 'ok  ' : '404 '} ${entry.path}  (${entry.probes.length} 个字形)` +
          (r.ok ? ` → ${r.method} ${r.status}  via ${r.via}` : ` → 全部 404`)
      );
    }
  }

  teardown();

  if (failures.length) {
    console.error(`✗ API route existence (live) — RED：${failures.length}/${n} 条前端路径在运行实例上 404`);
    for (const f of failures) {
      console.error(`  · ${f.path}  （${f.probes.length} 个字形全部 404）`);
      for (const t of f.tried) console.error(`      ${t}`);
    }
    console.error('  ⇒ 前端调了但实例上不存在：正是「404 ⇒ 面板渲染成空态」的事故形态，必须拦住。');
    process.exit(1);
  }

  console.log(`✓ API route existence (live) — GREEN（运行实例上逐条探 ${n} 条前端路径，全部非 404）`);
  process.exit(0);

  function teardown() {
    if (child && child.pid) {
      try {
        process.kill(-child.pid, 'SIGTERM'); // 只杀自己 spawn 的进程组
      } catch {
        /* 已退出 */
      }
      // 进程组/端口复核：确认端口已释放（只查不杀，绝不碰别人的实例）
      const deadline = Date.now() + 15000;
      while (Date.now() < deadline) {
        const busy = spawnSync('lsof', ['-tiTCP:' + String(PORT), '-sTCP:LISTEN'], { encoding: 'utf8' });
        if (!busy.stdout || !busy.stdout.trim()) break;
        spawnSync('sleep', ['0.5']);
      }
    }
    if (home && !KEEP) {
      try {
        rmSync(home, { recursive: true, force: true });
      } catch {
        /* 删不掉不算失败 */
      }
    } else if (home) {
      console.log(`[live] 保留沙箱数据根：${home}（boot.log 与实例写入的现场都在里面）`);
    }
  }
}

await main();

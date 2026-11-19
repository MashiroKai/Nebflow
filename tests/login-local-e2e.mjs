#!/usr/bin/env node
// login-local-e2e.mjs — 登录线本地真实链路 E2E（隔离本地 mock IdP + 真实网关 +
// 双浏览器；2026-11-15 授权范围：隔离本地真实服务，不碰真实付费账号）。
//
// 场景（编号接 PkceLoginSessionSpec / LogtoPasswordFlowSpec / session-rail 家族）：
//   LGN-SCN-001  headless 密码腿：网关 /api/neblink/auth/password 经 Experience 链
//                （mock IdP 四步）换票成功；错密码 = 拒（不漏账号存在性）。
//   LGN-SCN-002  PKCE 浏览器全流（chromium）：start → mock /oidc/auth 表单 →
//                302 回环 /auth/callback?code&state → 换票 → nf-authcheck 204。
//   LGN-SCN-003  会话恢复：同页 reload 后仍 204（session cookie 持久）。
//   LGN-SCN-004  WebKit 第二浏览器跑同流（双浏览器矩阵证据；Secure-cookie-over-http
//                的引擎差由 ConsoleTicketRoutesSpec #522 单元钉，本腿是矩阵实证）。
//   LGN-SCN-005  重启恢复：网关重启（同 home）后，同 cookie jar 再访 = 204。
//   （票务轮换/吊销腿 = tests/session-rail.spec.mjs，由外部编排最后跑——破坏性。）
//
// 隔离纪律：实例/IdP 全部 127.0.0.1 临时口（默认 8089/8093，启动前验空闲；拒绝
// 6138/8080/8097 与 <1024）；home = mktemp 隔离根；收杀只针对本脚本 spawn、且
// cmdline 含本轮 home 路径的进程。
//
// Run: node tests/login-local-e2e.mjs [--gateway-jar <path>] [--keep]
// 前置：target/scala-3.5.2/stand-assembly-*.jar（整合树重建后的 jar）。

import { spawn, execSync } from 'node:child_process';
import { createServer } from 'node:net';
import { readFileSync, writeFileSync, mkdirSync, rmSync, existsSync, mkdtempSync, readdirSync, appendFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium, webkit } from 'playwright';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..');
const args = process.argv.slice(2);
const argOf = (name) => { const i = args.indexOf(name); return i >= 0 && args[i + 1] ? args[i + 1] : undefined; };
const KEEP = args.includes('--keep');

// ── 结果账本（统一协议：PASS/FAIL/BLOCKED/SKIP + 证据行）─────────────────
const results = [];
const record = (id, name, status, detail = '') => {
  results.push({ id, name, status, detail });
  console.log(`${status === 'PASS' ? '✓' : status === 'FAIL' ? '✗' : '·'} ${id} ${name} — ${status}${detail ? ` (${detail})` : ''}`);
};

const USER = 'e2e-user';
const PASS = 'e2e-password';
const IDP_PORT = 8093;
const GW_PORT = 8089;
const PROTECTED = [6138, 8080, 8097];

const freePort = (port) => new Promise((resolve) => {
  const probe = createServer();
  probe.once('error', () => resolve(false));
  probe.once('listening', () => probe.close(() => resolve(true)));
  probe.listen(port, '127.0.0.1');
});

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const log = (...a) => console.log(`[login-e2e ${new Date().toISOString()}]`, ...a);

const spawned = [];
const cleanup = async () => {
  for (const p of spawned.reverse()) {
    try {
      // 身份核验（macOS 无 /proc）：ps 读 cmdline，必须仍含本轮标记（home 路径 /
      // mock-idp 脚本名）才动手 —— 不仅凭 PID，不 broad-kill。
      const cmd = execSync(`ps -p ${p.pid} -o command= 2>/dev/null || true`).toString();
      if (!cmd.includes(p.tag)) { log(`skip kill ${p.pid}（身份不符: ${cmd.slice(0, 60)}）`); continue; }
      try { process.kill(-p.pid, 'SIGTERM'); } catch { process.kill(p.pid, 'SIGTERM'); }
    } catch { /* 已退 */ }
  }
};

const waitHealthy = async (url, deadlineMs, label) => {
  const deadline = Date.now() + deadlineMs;
  while (Date.now() < deadline) {
    try {
      const r = await fetch(url);
      if (r.ok) return true;
    } catch { /* 未起 */ }
    await sleep(700);
  }
  throw new Error(`${label} 未在 ${deadlineMs}ms 内就绪`);
};

// ── 主流程 ─────────────────────────────────────────────────────────────────
const jar = argOf('--gateway-jar') ||
  (() => {
    const dir = join(ROOT, 'target', 'scala-3.5.2');
    if (!existsSync(dir)) return undefined;
    const jars = readdirSync(dir).filter((f) => f.startsWith('stand-assembly-') && f.endsWith('.jar'));
    return jars.length ? join(dir, jars.at(-1)) : undefined;
  })();

const main = async () => {
  // 0. 前置
  for (const p of [IDP_PORT, GW_PORT]) {
    if (PROTECTED.includes(p) || p < 1024) throw new Error(`端口 ${p} 在保护清单`);
    if (!(await freePort(p))) throw new Error(`端口 ${p} 不空闲（换口或清占者后重跑）`);
  }
  if (!jar || !existsSync(jar)) {
    record('LGN-ENV', 'assembly jar', 'BLOCKED', `缺 ${jar ?? 'target/scala-3.5.2/stand-assembly-*.jar'}；先在整合树 sbt assembly`);
    return finish();
  }
  const home = mkdtempSync('/tmp/login-e2e-home-');
  mkdirSync(join(home, 'neblink'), { recursive: true });
  writeFileSync(join(home, 'neblink', 'config.json'), JSON.stringify({
    logto: { endpoint: `http://127.0.0.1:${IDP_PORT}`, clientId: 'stand-e2e', pkceClientId: 'stand-e2e' },
    // 注册汇聚点目标（EnrollGuard 隔离闸：隔离根上没有显式 server URL ⇒ 拒生产
    // 默认回落，首跑实测 400「refused: … does not fall back to the production
    // NebLink server default」）。显式指到 mock IdP ⇒ 密码腿/浏览器腿的
    // register 汇聚腿落在本地（mock 同端口服务 /api/device/register）。
    neblinkServer: { url: `http://127.0.0.1:${IDP_PORT}`, networkId: 'mock-network', secret: 'mock-secret' },
  }, null, 2));
  log('isolated home', home);

  // 1. mock IdP
  const idp = spawn('node', [join(HERE, '_mock-logto-idp.mjs'), '--port', String(IDP_PORT),
    '--account', `${USER}:${PASS}`], { stdio: ['ignore', 'pipe', 'pipe'], detached: true });
  spawned.push({ pid: idp.pid, tag: '_mock-logto-idp.mjs' });
  idp.stdout.on('data', (d) => process.stdout.write(String(d)));
  idp.stderr.on('data', (d) => process.stderr.write(String(d)));
  await waitHealthy(`http://127.0.0.1:${IDP_PORT}/mock/health`, 15000, 'mock IdP');
  record('LGN-ENV', 'mock IdP 就绪', 'PASS', `port ${IDP_PORT}`);

  // 2. 网关（隔离 home；--foreground 让 spawn 的 java 就是网关本体 —— 缺省的
  //    daemon 化会 fork --daemon-child 后父进程退出，runner 的 PID 跟踪/收杀
  //    就会漏掉真身（首跑实证：父 PID 死 ⇒ 身份不符跳过 ⇒ 僵尸占 8089））。
  //    日志落 home 外旁侧。
  const gwLog = join(home, '..', 'gateway.log');
  const gw = spawn('java', ['--add-opens', 'java.base/java.lang=ALL-UNNAMED', '-jar', jar,
    'start', '--home', home, '--port', String(GW_PORT), '--no-browser', '--foreground'],
    { stdio: ['ignore', 'pipe', 'pipe'], detached: true });
  spawned.push({ pid: gw.pid, tag: home });
  gw.stdout.on('data', (d) => { try { appendFileSync(gwLog, d); } catch {} });
  gw.stderr.on('data', (d) => { try { appendFileSync(gwLog, d); } catch {} });
  await waitHealthy(`http://127.0.0.1:${GW_PORT}/api/health`, 120000, 'gateway');
  const fileToken = readFileSync(join(home, 'auth.json'), 'utf8').trim();
  record('LGN-ENV', '隔离网关就绪', 'PASS', `port ${GW_PORT} · auth.json 已自生成`);

  const gwUrl = `http://127.0.0.1:${GW_PORT}`;
  const bearer = (t) => ({ authorization: `Bearer ${t}` });

  // 3. LGN-SCN-001 headless 密码腿
  {
    const bad = await fetch(`${gwUrl}/api/neblink/auth/password`, {
      method: 'POST', headers: { 'content-type': 'application/json', ...bearer(fileToken) },
      body: JSON.stringify({ account: USER, password: 'wrong-password' }),
    });
    // 网关把 422 session.invalid_credentials 归为 InvalidCredentials ⇒ 401
    // （performHeadlessLogin 的 Unauthorized 面；mock 按 2026-10-26 钉死的租户
    // 方言发 422 + code 字段）。
    const rejectOk = bad.status === 401;
    const good = await fetch(`${gwUrl}/api/neblink/auth/password`, {
      method: 'POST', headers: { 'content-type': 'application/json', ...bearer(fileToken) },
      body: JSON.stringify({ account: USER, password: PASS }),
    });
    const bodyText = await good.text();
    const okShape = good.ok && !bodyText.includes('"error"');
    record('LGN-SCN-001', 'headless 密码腿（Experience 四步）',
      rejectOk && okShape ? 'PASS' : 'FAIL',
      `错密码=${bad.status} 对密码=${good.status} ${bodyText.slice(0, 120)}`);
  }

  // 4. PKCE 浏览器全流（chromium → webkit 双浏览器）
  const browserFlow = async (engineLabel, engine) => {
    const browser = await engine.launch({ headless: true });
    try {
      const context = await browser.newContext();
      const page = await context.newPage();
      await page.goto(`${gwUrl}/`);
      const started = await page.evaluate(async (token) => {
        const r = await fetch('/api/neblink/auth/start', {
          method: 'POST', headers: { 'content-type': 'application/json', authorization: `Bearer ${token}` },
          body: '{}',
        });
        return { status: r.status, body: await r.json() };
      }, fileToken);
      const authorizeUrl = started.body?.authorizeUrl;
      if (!authorizeUrl) {
        record(`LGN-SCN-002/${engineLabel}`, 'PKCE 浏览器全流', 'FAIL', `start=${started.status} ${JSON.stringify(started.body).slice(0, 120)}`);
        return { context };
      }
      await page.goto(authorizeUrl);
      await page.waitForSelector('form[action="/mock/login"]', { timeout: 15000 });
      await page.fill('input[name="username"]', USER);
      await page.fill('input[name="password"]', PASS);
      await Promise.all([
        page.waitForURL(/\/auth\/callback\?code=/, { timeout: 20000 }),
        page.click('button[type="submit"]'),
      ]);
      // 回环回调落点 + 换票结果页
      await page.waitForLoadState('domcontentloaded');
      const callbackUrl = page.url();
      const callbackOk = await page.evaluate(() => document.body.textContent.includes('登录成功'));
      // 设备登录面验收（不是控制台会话 cookie！）：PKCE callback 只落 NebLink
      // 设备凭据（device.json），不发控制台会话 —— /api/nf-authcheck 走
      // session/bearer/PAT 三轨，匿名 cookie 探针 403 是正确行为（首跑/二跑
      // 误把它当验收面）。真验收 = Bearer 文件票打 /api/neblink/status ⇒
      // loggedIn=true（凭据在场 + NebLink enabled）。
      const enrolled = await fetch(`${gwUrl}/api/neblink/status`, { headers: bearer(fileToken) })
        .then((r) => r.json()).catch(() => ({}));
      record(`LGN-SCN-002/${engineLabel}`, 'PKCE 浏览器全流',
        callbackUrl.includes('/auth/callback') && callbackOk && enrolled.loggedIn === true ? 'PASS' : 'FAIL',
        `callback=${callbackUrl.slice(0, 72)} card=${callbackOk} loggedIn=${enrolled.loggedIn ?? JSON.stringify(enrolled).slice(0, 60)}`);
      // LGN-SCN-003 会话恢复（reload 后设备凭据仍在 ⇒ 仍 loggedIn）
      await page.goto(`${gwUrl}/`);
      await page.reload({ waitUntil: 'domcontentloaded' });
      const enrolledAfterReload = await fetch(`${gwUrl}/api/neblink/status`, { headers: bearer(fileToken) })
        .then((r) => r.json()).catch(() => ({}));
      record(`LGN-SCN-003/${engineLabel}`, '会话恢复（reload 后凭据仍在）',
        enrolledAfterReload.loggedIn === true ? 'PASS' : 'FAIL', `loggedIn=${enrolledAfterReload.loggedIn}`);
      // 会话 cookie 必须在浏览器存活期内取走：finally 会 close 浏览器，
      // 之后 context.cookies() 直接抛「Target page, context or browser has
      // been closed」（首跑实测 fatal，SCN-005 被连带跳过）。
      const cookies = await context.cookies(`${gwUrl}/`);
      return { cookies };
    } finally {
      await browser.close().catch(() => {});
    }
  };

  const chromiumResult = await browserFlow('chromium', chromium);
  await browserFlow('webkit', webkit);

  // 5. LGN-SCN-005 重启恢复（SIGTERM → 同 home 重启 → 旧 cookie jar + 凭据再访）
  {
    const cookies = chromiumResult?.cookies ?? [];
    process.kill(gw.pid, 'SIGTERM');
    spawned.splice(spawned.findIndex((p) => p.pid === gw.pid), 1);
    // 等端口**真正释放**再拉新实例：SIGTERM 是优雅停机（CoordinatedShutdown），
    // 固定 sleep 时 waitHealthy 会打到垂死旧进程假 PASS（run4/run5 实测）。
    // freePort=true（可绑定）仅是必要条件 —— 真正的「新进程证明」在下面的
    // 先拒连后健康两段式 waitFreshHealthy。
    for (let i = 0; i < 60 && !(await freePort(GW_PORT)); i++) await sleep(500);
    const gw2 = spawn('java', ['--add-opens', 'java.base/java.lang=ALL-UNNAMED', '-jar', jar,
      'start', '--home', home, '--port', String(GW_PORT), '--no-browser', '--foreground'],
      { stdio: ['ignore', 'pipe', 'pipe'], detached: true });
    let gw2Error = '';
    let gw2Exit = '';
    gw2.on('error', (e) => { gw2Error = String(e?.message || e); });
    gw2.on('exit', (c, s) => { gw2Exit = `code=${c} signal=${s}`; });
    spawned.push({ pid: gw2.pid, tag: home });
    // 两段式：先等到**至少一次拒连**（旧进程确死），再等健康（新进程确活）。
    // 单段 waitHealthy 会被「未起=吞异常」语义骗过 —— 任何时刻唯一的 OK 应答
    // 都可能是垂死旧进程的最后一口气（run5 实测：gw2 零日志、health 假绿）。
    let sawRefusal = false;
    {
      const deadline = Date.now() + 60000;
      while (Date.now() < deadline && !sawRefusal) {
        try { await fetch(`${gwUrl}/api/health`); } catch { sawRefusal = true; break; }
        if (gw2Exit) break; // gw2 已死就不等了，直接失败面
        await sleep(300);
      }
    }
    await waitHealthy(`${gwUrl}/api/health`, 120000, 'gateway(重启)');
    if (!gw2.pid || gw2Exit || gw2Error) {
      record('LGN-SCN-005', '重启恢复', 'FAIL', `gw2 未存活 spawnError=${gw2Error || '-'} exit=${gw2Exit || '-'}`);
      return finish();
    }
    const browser = await chromium.launch({ headless: true });
    const context = await browser.newContext();
    if (cookies.length) await context.addCookies(cookies);
    const page = await context.newPage();
    await page.goto(`${gwUrl}/`);
    // 重启恢复 = 设备凭据耐久面（device.json 落盘在隔离 home；同 home 重启后
    // 仍 loggedIn —— 控制台会话 cookie 与此无关，见 SCN-002 的轨面注释）。
    const enrolledAfterRestart = await fetch(`${gwUrl}/api/neblink/status`, { headers: bearer(fileToken) })
      .then((r) => r.json()).catch(() => ({}));
    record('LGN-SCN-005', '重启恢复（同 home 重启后旧凭据仍 loggedIn）',
      enrolledAfterRestart.loggedIn === true ? 'PASS' : 'FAIL', `loggedIn=${enrolledAfterRestart.loggedIn}`);
    await browser.close().catch(() => {});
  }

  return finish();
};

const finish = () => {
  const counts = results.reduce((m, r) => (m[r.status] = (m[r.status] || 0) + 1, m), {});
  console.log('\n═══ 登录线本地真实链路 E2E 结果 ═══');
  for (const r of results) console.log(`${r.status.padEnd(7)} ${r.id}  ${r.name}${r.detail ? ` · ${r.detail}` : ''}`);
  console.log(`\n合计: PASS=${counts.PASS || 0} FAIL=${counts.FAIL || 0} BLOCKED=${counts.BLOCKED || 0}`);
  process.exitCode = (counts.FAIL || 0) > 0 ? 1 : 0;
};


main().catch(async (e) => {
  console.error('[login-e2e] fatal:', e?.stack || e);
  process.exitCode = 2;
}).finally(cleanup);

// 防误触：KEEP 时保留 home（打印路径），否则清理。
process.on('exit', () => {
  if (!KEEP) {
    try {
      for (const d of execSync('ls -d /tmp/login-e2e-home-* 2>/dev/null || true').toString().split('\n').filter(Boolean)) {
        rmSync(d, { recursive: true, force: true });
      }
    } catch { /* 清理失败不掩盖结果 */ }
  }
});

// fcc-scn-011-shots.mjs — FCC-SCN-011 作者目验材料采集（frontend-chat-composer-repairs）
//
// 台账：openspec/changes/frontend-chat-composer-repairs/tasks.md #17 —— 「真桌面
// 亮/暗截图 + 真隔离实例手机回归，作者目验；目验前桌面 host 不合并」。
// 本脚本 = 在真隔离实例（隔离 home + 非保护端口）上采集：桌面亮/暗 × 主对话窗
// （composer/header 主视觉）+ 手机层一屏，PNG 落证据目录供作者目验。
//
// 纪律：home = mktemp 隔离根；端口 preflight（拒绝 6138/8080/8097 与 <1024）；
// 收杀只针对本脚本 spawn 且 ps 身份匹配的进程；不裸 sbt run、不直启 GatewayMain
// 之外的生产入口（这里经 assembly jar start --foreground，与 login-local-e2e 同门）。
//
// Run: node tests/fcc-scn-011-shots.mjs --out <dir>

import { spawn, execSync } from 'node:child_process';
import { createServer } from 'node:net';
import { existsSync, mkdtempSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..');
const args = process.argv.slice(2);
const argOf = (n) => { const i = args.indexOf(n); return i >= 0 && args[i + 1] ? args[i + 1] : undefined; };
const OUT = argOf('--out') || join(ROOT, '..', 'fcc-scn-011-shots');
const GW_PORT = 8095;
const PROTECTED = [6138, 8080, 8097];
if (PROTECTED.includes(GW_PORT) || GW_PORT < 1024) throw new Error(`refusing protected port ${GW_PORT}`);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const freePort = (port) => new Promise((resolve) => {
  const probe = createServer();
  probe.once('error', () => resolve(false));
  probe.once('listening', () => probe.close(() => resolve(true)));
  probe.listen(port, '127.0.0.1');
});

const jarDir = join(ROOT, 'target', 'scala-3.5.2');
const jar = existsSync(jarDir)
  ? join(jarDir, readdirSync(jarDir).filter((f) => f.startsWith('stand-assembly-') && f.endsWith('.jar')).sort().pop())
  : undefined;
if (!jar) throw new Error('缺 stand-assembly jar：先在整合树 sbt assembly');

const spawned = [];
const cleanup = async () => {
  for (const p of spawned.reverse()) {
    try {
      const cmd = execSync(`ps -p ${p.pid} -o command=`, { encoding: 'utf8' }).trim();
      if (cmd.includes(p.tag)) { process.kill(p.pid, 'SIGTERM'); console.log('[cleanup] killed', p.pid, p.tag); }
      else console.log('[cleanup] skip', p.pid, '(身份不符)');
    } catch { /* 已死 */ }
  }
};
process.on('exit', () => { for (const p of spawned) { try { process.kill(p.pid, 'SIGKILL'); } catch {} } });
process.on('SIGINT', async () => { await cleanup(); process.exit(1); });

const waitHealthy = async (url, deadlineMs, label) => {
  const deadline = Date.now() + deadlineMs;
  while (Date.now() < deadline) {
    try { const r = await fetch(url); if (r.ok) return true; } catch { /* 未起 */ }
    await sleep(700);
  }
  throw new Error(`${label} 未就绪`);
};

const main = async () => {
  if (!(await freePort(GW_PORT))) throw new Error(`端口 ${GW_PORT} 不空闲`);
  mkdirSync(OUT, { recursive: true });
  const home = mkdtempSync('/tmp/fcc-shot-home-');
  const gwLog = join(home, '..', 'fcc-gateway.log');

  const gw = spawn('java', ['--add-opens', 'java.base/java.lang=ALL-UNNAMED', '-jar', jar,
    'start', '--home', home, '--port', String(GW_PORT), '--no-browser', '--foreground'],
    { stdio: ['ignore', 'pipe', 'pipe'], detached: true });
  spawned.push({ pid: gw.pid, tag: home });
  gw.stdout.on('data', (d) => { try { writeFileSync(gwLog, String(d), { flag: 'a' }); } catch {} });
  gw.stderr.on('data', (d) => { try { writeFileSync(gwLog, String(d), { flag: 'a' }); } catch {} });
  const gwUrl = `http://127.0.0.1:${GW_PORT}`;
  await waitHealthy(`${gwUrl}/api/health`, 120000, 'gateway');
  const fileToken = readToken(home);
  if (!fileToken) throw new Error('网关未生成 auth.json 文件票，无法铸会话');
  console.log('[shot] gateway ready', gwUrl);

  // Stage D 认证链（与 tests/session-rail.spec.mjs §7.2 / settings-e2e-isolated.mjs
  // 同门）：浏览器唯一入门 = console-ticket —— Bearer 文件票铸一次性票（120s、
  // 铸票预算 10/60s），GET /?ticket= 即 303 + HttpOnly stand_session cookie。
  // 旧 localStorage `nebflow_token` 轨已退役（GatewayAuth.scala stage D 注记：
  // 浏览器侧发射器已删），写它只会拍到登录墙 —— 首跑实证。
  const mintTicket = async () => {
    const r = await fetch(`${gwUrl}/api/auth/console-ticket`, {
      method: 'POST', headers: { authorization: `Bearer ${fileToken}` },
    });
    if (!r.ok) throw new Error(`console-ticket mint HTTP ${r.status}`);
    const ticket = (await r.json()).ticket;
    if (!ticket) throw new Error('console-ticket 返回空票');
    return ticket;
  };
  const openWithSession = async (ctx) => {
    const ticket = await mintTicket();
    const page = await ctx.newPage();
    await page.addInitScript(() => localStorage.setItem('nebflow_locale', 'zh-CN'));
    await page.goto(`${gwUrl}/?ticket=${encodeURIComponent(ticket)}`, { waitUntil: 'domcontentloaded' });
    return page;
  };

  const browser = await chromium.launch({ headless: true });

  // 桌面亮/暗：主对话窗（composer/header）
  for (const scheme of ['light', 'dark']) {
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: scheme });
    const page = await openWithSession(ctx);
    await page.waitForTimeout(3500);
    await page.screenshot({ path: join(OUT, `desktop-${scheme}.png`), fullPage: false });
    console.log('[shot] desktop-' + scheme + '.png');
    await ctx.close();
  }

  // 手机层：375×812
  {
    const ctx = await browser.newContext({ viewport: { width: 375, height: 812 }, colorScheme: 'light', isMobile: true, hasTouch: true });
    const page = await openWithSession(ctx);
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 20000 });
    await page.waitForTimeout(2500);
    await page.screenshot({ path: join(OUT, 'mobile-light.png'), fullPage: false });
    console.log('[shot] mobile-light.png');
    await ctx.close();
  }

  await browser.close();
  await cleanup();
  try { rmSync(home, { recursive: true, force: true }); }
  catch (e) { console.log('[shot] warn: 隔离 home 清理不彻底（截图已落盘）:', e?.message || e); }
  console.log('[shot] DONE →', OUT);
};

function readToken(home) {
  try { return readFileSync(join(home, 'auth.json'), 'utf8').trim().replace(/^"|"$/g, ''); } catch { return ''; }
}

main().catch(async (e) => { console.error('[shot] fatal:', e?.message || e); await cleanup(); process.exit(1); });

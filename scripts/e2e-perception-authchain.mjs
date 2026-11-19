#!/usr/bin/env node
// e2e-perception-authchain.mjs — 感知「连接邮箱」鉴权链真链路 E2E（Missing token 报障批 2026-10-26）。
//
// 报障：relay 源控制台「连接邮箱」提交得红字「Missing token」，请求没到引擎
// （引擎日志零痕）。定因：该字串 = link-server ingress 门 require_user 的 403 体
// （隧道之前拒）；本机直连链路引擎侧的正确形状是 403「Unauthorized」+ reason 头。
//
// 本脚本在**真实引擎 + 真浏览器 + 伪 IMAP**上把写穿链钉成事实：
//   ① 鉴权形状探针：无凭据 POST /api/perception/mail/connect 与 GET entries ⇒
//      403 {"error":"Unauthorized"} + X-Nf-Reason: credential-required，且体内
//      **永不出「Missing token」**（ingress 门独占形状，可分形判据）；
//   ② 写穿 E2E：console-ticket 兑换 → 真控制台 → 感知面板 → 连接邮箱表单
//      （假凭据 + 高级服务器指向本机伪 IMAP）→ 提交 ⇒ 伪 IMAP 伪件收到引擎的
//      真连接与 LOGIN（= 请求确已 UI→引擎→IMAP 全链写穿），卡片显示引擎侧
//      业务失败原文（非鉴权死底、非 Missing token）。
//   ③ 前端伞回归（同页）：面板 js 用仓内工作树版（route 拦截注入），401/403
//      伞行为由 tests/mail-perception.spec.mjs 鉴权拒绝脸钉（本脚本不重复）。
//
// 隔离纪律（同 e2e-archive-restart-visibility 先例）：java 直启 + 独立 --home +
// 端口≠8080/6138；杀前验身（cmdline 含 home）；CLOSE=1 才删 fixture home。
//
// Run：node scripts/e2e-perception-authchain.mjs
// Env：JAR=<assembly> PORT=8091 KEEP=1（保 home 取证）PW_CHANNEL=chrome（用本机
// Chrome 而非 playwright chromium）。

import { spawn } from 'node:child_process';
import http from 'node:http';
import net from 'node:net';
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';
import { setTimeout as sleep } from 'node:timers/promises';

const REPO = dirname(dirname(fileURLToPath(import.meta.url)));
const EVIDENCE = join(REPO, '.nebflow', 'evidence', '20261026_missing-token-authchain');
const PORT = Number(process.env.PORT || 8091);
const BASE = `http://127.0.0.1:${PORT}`;
const HOME = process.env.NEBFLOW_HOME || join(tmpdir(), `nb-perc-auth-${Date.now()}`);
const FIXED_JS = join(REPO, 'src', 'main', 'resources', 'web', 'js', 'perception.js');
const CLOSE = process.env.CLOSE === '1';

let failed = 0;
const check = (name, ok, extra = '') => {
  if (!ok) failed++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + String(extra).slice(0, 400) : ''}`);
};

mkdirSync(EVIDENCE, { recursive: true });
mkdirSync(HOME, { recursive: true });

// ── 伪 IMAP：收引擎真连接；LOGIN 一律 NO AUTHENTICATIONFAILED（干净快败）──
// 注：JavaMail 走隐式 SSL（真链路 imap.qq.com:993 形态）——裸 TCP 伪件只能收
// 到引擎的 TLS ClientHello（这本身就是「引擎真的来连了」的字节级写穿证据，
// ②e 钉它）；要再见 LOGIN 明文需 JVM 信任链（keytool + trustStore 启动注入），
// 属可选加固，见 REPORT §5。
const imapLog = [];
let imapConnections = 0;
let imapTlsClientHello = false;
const imapServer = net.createServer((sock) => {
  imapConnections++;
  imapLog.push(`CONN ${sock.remoteAddress}:${sock.remotePort} ts=${Date.now()}`);
  sock.write('* OK E2E-FAKE IMAP4rev1 ready\r\n');
  let buf = '';
  sock.on('data', (d) => {
    if (d.length >= 2 && d[0] === 0x16 && d[1] === 0x03) imapTlsClientHello = true;
    buf += d.toString('utf8');
    imapLog.push(`DATA ${JSON.stringify(d.toString('utf8'))}`);
    for (const line of buf.split('\r\n')) {
      const m = line.match(/^(\S+)\s+(LOGIN|CAPABILITY|SELECT|LOGOUT)\b/i);
      if (!m) continue;
      const tag = m[1];
      if (/login/i.test(m[2])) {
        imapLog.push(`LOGIN-ATTEMPT ${line}`);
        sock.write(`${tag} NO [AUTHENTICATIONFAILED] e2e-fake authentication failed\r\n`);
      } else if (/capability/i.test(m[2])) {
        sock.write(`* CAPABILITY IMAP4rev1\r\n${tag} OK CAPABILITY completed\r\n`);
      } else if (/logout/i.test(m[2])) {
        sock.write(`* BYE e2e-fake\r\n${tag} OK LOGOUT completed\r\n`);
        sock.end();
      } else {
        sock.write(`${tag} NO e2e-fake refused\r\n`);
      }
    }
    buf = buf.endsWith('\r\n') ? '' : buf.slice(buf.lastIndexOf('\r\n') + 2);
  });
  sock.on('error', () => {});
});
const IMAP_PORT = await new Promise((res) => imapServer.listen(0, '127.0.0.1', () => res(imapServer.address().port)));

// ── 引擎启动（java 直启、独立 home、随机高位端口栅格）──
const JAR = process.env.JAR;
if (!JAR || !existsSync(JAR)) {
  console.error(`FATAL: JAR env must point at a stand-assembly jar (got: ${JAR})`);
  process.exit(1);
}
const bootLog = join(HOME, 'e2e-engine.log');
// 端口在位检查（修 run-1 泄漏教训：端口被上一轮残留引擎占用时，health 200 会
// 认错实例、本实例 auth.json 缺席）——占用即拒，绝不adopt外来实例。
{
  const { execSync } = await import('node:child_process');
  let occupied = '';
  try {
    occupied = execSync(`netstat -ano -p tcp | findstr ":${PORT}"`, { shell: 'cmd.exe' }).toString()
      .split('\n').filter((l) => l.includes('LISTENING')).join('\n');
  } catch { /* 空 = 空闲 */ }
  if (occupied.trim()) {
    console.error(`FATAL: port ${PORT} already occupied — refusing to adopt a foreign instance:\n${occupied}`);
    imapServer.close();
    process.exit(1);
  }
}
const boot = spawn('java', ['--add-opens', 'java.base/java.lang=ALL-UNNAMED', '-jar', JAR, 'start', '--port', String(PORT), '--home', HOME, '--no-browser'], { cwd: HOME, stdio: ['ignore', 'ignore', 'ignore'] });
boot.stdout?.on('data', () => {});
const logStream = (await import('node:fs')).createWriteStream(bootLog);
// 引擎自己写 <home> 下日志；此处只留 boot 进程级兜底
process.on('exit', () => { try { logStream.end(); } catch {} });

const T0 = Date.now();
let up = false;
while (Date.now() - T0 < 120000) {
  try {
    const r = await fetch(`${BASE}/api/health`);
    if (r.ok) { up = true; break; }
  } catch { /* not up yet */ }
  await sleep(1500);
}
if (!up) {
  console.error(`FATAL: engine not ready in 120s (log: ${bootLog})`);
  await cleanup();
  process.exit(1);
}
// auth.json 就绪等待（boot-smoke D3 同源：票铸链读它）
let authReady = false;
for (let i = 0; i < 40 && !authReady; i++) {
  authReady = existsSync(join(HOME, 'auth.json'));
  if (!authReady) await sleep(500);
}
if (!authReady) {
  console.error(`FATAL: engine up but ${join(HOME, 'auth.json')} never appeared (it answered health? port collision guard passed?)`);
  await cleanup();
  process.exit(1);
}
console.log(`[boot] engine ready on :${PORT} in ${((Date.now() - T0) / 1000).toFixed(1)}s (home=${HOME})`);

// ── 清理（杀前验身：监听 pid 的 cmdline 必须含本 home —— netstat + Get-CimInstance 双证）──
let cleaned = false;
async function cleanup() {
  if (cleaned) return;
  cleaned = true;
  imapServer.close();
  try { boot.kill(); } catch { /* launcher already gone */ }
  try {
    const { execSync } = await import('node:child_process');
    const lines = execSync(`netstat -ano -p tcp | findstr ":${PORT}"`, { shell: 'cmd.exe' }).toString();
    const pids = new Set([...lines.matchAll(/\s(\d+)\s*$/gm)].map((m) => m[1]));
    for (const pid of pids) {
      const ps = process.env.WS_PSH || 'C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe';
      const cmd = execSync(
        `${ps} -NoProfile -Command "(Get-CimInstance Win32_Process -Filter 'ProcessId=${pid}').CommandLine"`,
        { shell: 'cmd.exe' },
      ).toString();
      if (cmd.includes(HOME) || cmd.includes(JAR)) {
        try { process.kill(Number(pid)); console.log(`[cleanup] killed engine pid=${pid}`); } catch {}
      } else {
        console.log(`[cleanup] GUARD: pid ${pid} on :${PORT} cmdline lacks home/jar — NOT killed`);
      }
    }
  } catch { /* 端口已空：正常收敛 */ }
  if (CLOSE) { try { (await import('node:fs')).rmSync(HOME, { recursive: true, force: true }); console.log('[cleanup] home removed'); } catch {} }
  else console.log(`[cleanup] home KEPT: ${HOME}`);
}
process.on('exit', () => { if (!cleaned) { try { boot.kill(); } catch {} } });
process.on('SIGINT', async () => { await cleanup(); process.exit(130); });

// ── ① 鉴权形状探针（无凭据 ⇒ 引擎 403 形状；「Missing token」必须缺席）──
async function probeNoAuth(method, path) {
  const r = await fetch(BASE + path, { method });
  let body = null;
  try { body = await r.json(); } catch {}
  return { status: r.status, reason: r.headers.get('x-nf-reason'), body };
}
const p1 = await probeNoAuth('POST', '/api/perception/mail/connect');
check('①a connect 无凭据 ⇒ 403（引擎形状）', p1.status === 403, JSON.stringify(p1.body));
check('①b 403 带 X-Nf-Reason: credential-required', p1.reason === 'credential-required', `reason=${p1.reason}`);
check('①c 引擎体 error=Unauthorized，绝无「Missing token」', p1.body?.error === 'Unauthorized' && !JSON.stringify(p1.body).includes('Missing token'), JSON.stringify(p1.body));
const p2 = await probeNoAuth('GET', '/api/perception/entries');
check('①d entries 无凭据 ⇒ 同形 403', p2.status === 403 && p2.body?.error === 'Unauthorized' && p2.reason === 'credential-required', JSON.stringify(p2.body));

// ── 铸票（与 LocalConsoleUrl.issue 同端点同形）──
const token = readFileSync(join(HOME, 'auth.json'), 'utf8').trim().replace(/^"|"$/g, '');
const mintResp = await fetch(`${BASE}/api/auth/console-ticket`, {
  method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body: '{}',
});
const mint = await mintResp.json().catch(() => ({}));
check('②a console-ticket 铸票 200', mintResp.ok && !!mint.ticket, `status=${mintResp.status}`);
const consoleUrl = `${BASE}/?ticket=${encodeURIComponent(mint.ticket)}`;

// ── ② 浏览器写穿 E2E ──
const { chromium } = await import('@playwright/test');
let browser = null;
try {
  browser = await (process.env.PW_CHANNEL
    ? chromium.launch({ channel: 'chrome', headless: true })
    : chromium.launch({ headless: true }));
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await ctx.newPage();
  // 前端面板注入仓内工作树版（引擎 jar 内 js 为构建时版本；route 拦截 = 零重打包验证修后面）
  await page.route(/\/js\/perception\.js(\?.*)?$/, async (route) => {
    await route.fulfill({ body: readFileSync(FIXED_JS, 'utf8'), contentType: 'text/javascript' });
  });
  await page.goto(consoleUrl, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#perception-btn', { timeout: 45000 });
  await page.screenshot({ path: join(EVIDENCE, '1-console.png') });

  // 面板 → 条目 → 连接 hero → 配置卡
  await page.click('#perception-btn');
  await page.waitForSelector('#perception-panel', { timeout: 10000 });
  await page.click('.pp-entry .pp-entry-head');
  await page.waitForSelector('.pp-setup-hero .pp-btn', { timeout: 10000 }).catch(async () => {
    // 已配置态回落：条目 detail 的「邮箱设置」按钮
    await page.click('[data-act="config"]');
  });
  await page.screenshot({ path: join(EVIDENCE, '2-panel.png') });
  await page.click('.pp-setup-hero .pp-btn');
  await page.waitForSelector('#pp-cfg-overlay', { timeout: 10000 });
  await page.fill('#pp-cfg-addr', 'fake-e2e@qq.com');
  await page.fill('#pp-cfg-code', 'fakecode123');
  await page.click('details.pp-adv summary');
  await page.fill('#pp-cfg-imap', '127.0.0.1');
  await page.fill('#pp-cfg-imapport', String(IMAP_PORT));
  await page.fill('#pp-cfg-smtp', '127.0.0.1');
  await page.fill('#pp-cfg-smtpport', String(IMAP_PORT));
  await page.screenshot({ path: join(EVIDENCE, '3-config-filled.png') });
  await page.click('#pp-cfg-connect');
  await page.waitForFunction(() => {
    const el = document.querySelector('#pp-cfg-result');
    return el && el.textContent && el.textContent.trim().length > 0 && !/正在连接|Connecting/.test(el.textContent);
  }, null, { timeout: 30000 });
  const resultText = await page.evaluate(() => document.querySelector('#pp-cfg-result')?.textContent || '');
  await page.screenshot({ path: join(EVIDENCE, '4-connect-result.png') });

  check('②b 提交后卡片无「Missing token」（本机链路本就到不了 ingress 门）', !resultText.includes('Missing token'), resultText);
  check('②c 卡片显示引擎侧业务失败原文（写穿证据面）', resultText.length > 0 && !/正在连接/.test(resultText), resultText);
  await sleep(500);
  const imapEvidence = imapLog.join('\n');
  writeFileSync(join(EVIDENCE, 'imap-fake.log'), imapEvidence || '(empty)');
  check('②d 伪 IMAP 收到引擎真连接（UI→引擎→IMAP 写穿）', imapConnections >= 1, `conns=${imapConnections}`);
  check('②e 引擎来连字节为 TLS ClientHello（隐式 SSL 形态 = 引擎连箱流程真实进入）', imapTlsClientHello, imapLog.slice(0, 3).join(' | ').slice(0, 200));
  check('②f 鉴权伞未误拉（400 是业务失败非会话死亡，卡片不得出会话引导）', !resultText.includes('重新进入控制台'), resultText);
  ctx.close();
} finally {
  if (browser) { try { await browser.close(); } catch { /* never launched */ } }
  await cleanup();
}

console.log(`\n[evidence] ${EVIDENCE}`);
await cleanup();
if (failed > 0) { console.log(`E2E-PERC-AUTHCHAIN: FAIL failed=${failed}`); process.exit(1); }
console.log('E2E-PERC-AUTHCHAIN: PASS');

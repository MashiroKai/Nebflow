#!/usr/bin/env node
// e2e-md-image-in-doc.mjs — 文档里放图批（2026-11-05）**隔离实例**验收。
//
// 作者报障两句（逐字）：
//   14:18「可以放文档 但问题是 文档里只有路径 图片没渲染出来」
//   15:15「是现在渲染引擎的问题吗 截图在里面 但是不渲染出来」
//
// 本脚本回答的正是这两句：**真引擎 + 真 vault + 真 /api/nf-file**（隔离
// NEBFLOW_HOME + 独立端口 8199，绝不碰 6138/8080），逐条记录
//   POST /api/nf-ticket（铸票）与 GET /api/nf-file?path=…&ticket=…（取字节）的
//   实际 URL 与返回状态码，再用 DOM 读数证明「图真的画出来了」。
//
// 复现集（三份，两份是作者的真实文档，原样拷进隔离 vault，一字未改）：
//   A. `canvas-note-button__20261105_122526.md`  —— 作者样本一
//   B. `config-single-source__20261105_124500.md`—— 作者样本二（`![[裸名]]`，
//      图在 docs/attachments/ 子目录 ⇒ 路径基准决定 404 还是 200）
//   C. `doc-images.md`                           —— 三写法 × 四基准夹具
//
// 运行（worktree 根，先 `npm run build`；jar 用主仓装配件）：
//   node scripts/e2e-md-image-in-doc.mjs
// Env：GATEWAY_PORT=8199、JAR=<assembly jar>、CLEAN=1 收尾删隔离 home。

import { spawn, execSync } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync, copyFileSync, existsSync, readdirSync, rmSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir, homedir } from 'node:os';
import { setTimeout as sleep } from 'node:timers/promises';
import { chromium } from 'playwright';

const REPO = dirname(dirname(fileURLToPath(import.meta.url)));
const MAIN_REPO = execSync('git rev-parse --path-format=absolute --git-common-dir', { cwd: REPO }).toString().trim().replace(/\/\.git$/, '');
const GATEWAY_PORT = Number(process.env.GATEWAY_PORT || 8199);
const BASE = `http://127.0.0.1:${GATEWAY_PORT}`;
// 每次跑用**独立 home**：隔离 home 会把 canvas 页签持久化到服务端，复用同一
// home 时第二跑是「恢复出来的页签」，取字节请求发生在我打点之前（读数会漂）。
const HOME = process.env.HOME_DIR || join(tmpdir(), `nb-mdimg-inst-${process.pid}`);
const PROJECT = 'qa-mdimg';
const VAULT = join(HOME, 'projects', PROJECT, '.stand', 'docs');
const OUT = process.env.OUT || join(REPO, '.nebflow', 'evidence', '20261105_md-image-in-doc', 'instance');
const REAL_DOCS = join(homedir(), '.stand', 'projects', 'stand', '.stand', 'docs');

function findJar() {
  if (process.env.JAR) return process.env.JAR;
  const fromMain = join(MAIN_REPO, 'target/scala-3.5.2');
  const fromHere = join(REPO, 'target/scala-3.5.2');
  for (const dir of [fromHere, fromMain]) {
    if (!existsSync(dir)) continue;
    const jars = readdirSync(dir).filter((f) => f.startsWith('stand-assembly-') && f.endsWith('.jar'));
    if (jars.length) return join(dir, jars.sort().pop());
  }
  throw new Error('assembly jar not found (set JAR=<path>)');
}

// E2E_MODE=before ⇒ **读数模式**：同一台隔离实例跑改前树（前端取装配件里打包的
// `web/`，即 `-Dstand.webdist=dev`），只记读数、判红不计分 —— 那份读数是
// 「是渲染引擎的问题吗」的对照面。
const MODE = process.env.E2E_MODE === 'before' ? 'before' : 'after';
let failed = 0;
function check(name, ok, extra = '') {
  const tag = ok ? 'PASS' : (MODE === 'before' ? 'READ' : 'FAIL');
  console.log(`${tag}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!ok && MODE === 'after') failed++;
}

mkdirSync(OUT, { recursive: true });
const runLog = [];
const log = (line) => { console.log(line); runLog.push(line); };

/* ── pre-flight：端口必须空；6138/8080 宿主面快照 ── */
{
  const busy = execSync(`lsof -nP -iTCP:${GATEWAY_PORT} -sTCP:LISTEN 2>/dev/null || true`, { shell: '/bin/bash' }).toString().trim();
  if (busy) { console.error(`port ${GATEWAY_PORT} occupied — abort\n${busy}`); process.exit(2); }
}
const hostSnapshot = (port) => execSync(`lsof -nP -iTCP:${port} -sTCP:LISTEN -t 2>/dev/null | sort | tr "\\n" " "`, { shell: '/bin/bash' }).toString().trim();
const P6138_BEFORE = hostSnapshot(6138);
const P8080_BEFORE = hostSnapshot(8080);
log(`[pre] 6138=[${P6138_BEFORE}] 8080=[${P8080_BEFORE}] 8199=free`);

/* ── fixture home（隔离） ── */
rmSync(join(HOME, 'projects', PROJECT), { recursive: true, force: true });
mkdirSync(join(HOME, 'projects', PROJECT, '.stand', 'docs', 'assets'), { recursive: true });
mkdirSync(join(HOME, 'projects', PROJECT, '.stand', 'docs', 'attachments'), { recursive: true });
mkdirSync(join(HOME, `ws-${PROJECT}`), { recursive: true });
writeFileSync(join(HOME, 'projects', PROJECT, 'project.json'), JSON.stringify({
  device: 'self', name: PROJECT,
  description: 'md 文档内图片渲染隔离验收（image-in-doc 批）',
  workspace: join(HOME, `ws-${PROJECT}`),
  createdAt: Date.now(),
}, null, 2));

// A/B 两份作者真文档原样拷入（含 B 的 attachments 图）
const DOC_A = 'canvas-note-button__20261105_122526.md';
const DOC_B = 'config-single-source__20261105_124500.md';
for (const f of [DOC_A, DOC_B]) copyFileSync(join(REAL_DOCS, f), join(VAULT, f));
copyFileSync(join(REAL_DOCS, 'attachments', 'config-single-source-provider-modal__20261105.png'),
  join(VAULT, 'attachments', 'config-single-source-provider-modal__20261105.png'));

// C. 三写法 × 四基准夹具（真图 = 作者证据树里的截图字节）
const PNG = readFileSync(join(homedir(), '.stand', 'evidence', 'canvas-note-button', 'note-open.png'));
writeFileSync(join(VAULT, 'same-dir.png'), PNG);
writeFileSync(join(VAULT, 'assets', 'via-assets.png'), PNG);
writeFileSync(join(VAULT, 'attachments', 'vault-attach.png'), PNG);
writeFileSync(join(HOME, 'abs.png'), PNG);
writeFileSync(join(HOME, 'abs-md.png'), PNG);
const FIXTURE_MD = 'doc-images.md';
writeFileSync(join(VAULT, FIXTURE_MD), [
  '# 三写法 × 四基准',
  '',
  '![标准·文档同目录](same-dir.png)',
  '',
  '![标准·子目录](assets/via-assets.png)',
  '',
  `![标准·绝对路径](${join(HOME, 'abs-md.png')})`,
  '',
  '![[assets/via-assets.png]]',
  '',
  '![[vault-attach.png]]',
  '',
  '<img src="same-dir.png" alt="HTML·文档同目录">',
  '',
  `<img src="${join(HOME, 'abs.png')}" alt="HTML·绝对路径">`,
  '',
  '![断链](does-not-exist.png)',
  '',
].join('\n'));
log(`[fixture] vault=${VAULT}`);

/* ── 起隔离实例（自家 cwd = worktree ⇒ StaticRoutes 走 DiskDist = 本次 build） ── */
const children = [];
let cleaned = false;
async function cleanup() {
  if (cleaned) return;
  cleaned = true;
  for (const c of children) {
    try { if (c.pid) process.kill(-c.pid, 'SIGKILL'); } catch { /* gone */ }
  }
  const pids = execSync(`lsof -tiTCP:${GATEWAY_PORT} -sTCP:LISTEN 2>/dev/null || true`, { shell: '/bin/bash' }).toString().trim();
  for (const pid of pids.split('\n').filter(Boolean)) {
    try { process.kill(Number(pid), 'SIGKILL'); } catch { /* gone */ }
  }
}
for (const sig of ['SIGINT', 'SIGTERM', 'uncaughtException', 'unhandledRejection']) {
  process.on(sig, async (e) => { if (e) console.error(e); await cleanup(); process.exit(1); });
}

const jar = findJar();
log(`[boot] jar=${jar}`);
const child = spawn('java', [
  // JVM 系统属性必须排在 -cp / 主类**之前**（改前树对照腿靠它切 DevTree）
  ...(MODE === 'before' ? ['-Dstand.webdist=dev'] : []),
  '--add-opens', 'java.base/java.lang=ALL-UNNAMED',
  '-cp', jar, 'nebflow.Main',
  '--home', HOME, '--port', String(GATEWAY_PORT), '--no-browser', '--foreground', 'start',
], {
  cwd: REPO, detached: true, stdio: ['ignore', 'pipe', 'pipe'],
  env: { ...process.env, NEBFLOW_GATEWAY_PORT: String(GATEWAY_PORT) },
});
children.push(child);
const bootLog = [];
const onOut = (d) => {
  const s = String(d).trim();
  if (!s) return;
  bootLog.push(s);
  if (/static tree|web-dist|listen|Listen|port|ERROR|Exception/i.test(s)) log(`[gw] ${s.slice(0, 220)}`);
};
child.stdout.on('data', onOut);
child.stderr.on('data', onOut);
child.on('error', (e) => console.error('[spawn-error]', e && e.message));
child.on('exit', (code, sig) => { console.error(`[gw-exit] code=${code} sig=${sig}`); if (process.env.NB_E2E_DEBUG === '1') console.error(bootLog.slice(-15).join('\n')); });

let up = false;
for (let i = 0; i < 120; i++) {
  try {
    const r = await fetch(BASE);
    if (r.status < 500) { up = true; break; }
  } catch { /* not yet */ }
  await sleep(1000);
}
check('隔离实例启动（独立端口 8199）', up, BASE);
if (!up) { await cleanup(); process.exit(1); }
// static tree 读数（必须 = disk web-dist，即本次修复的前端）
const treeLine = bootLog.find((l) => /static tree/i.test(l)) || '(未打印)';
log(`[gw] static tree ⇒ ${treeLine}`);
check(MODE === 'before'
  ? '前端取装配件打包的 web/（改前树 = DevTree）'
  : '前端由本次 build 的 web-dist 供给（DiskDist）',
  MODE === 'before' ? /dev web\/ sources/.test(treeLine) : /disk web-dist/.test(treeLine), treeLine);

await sleep(1500);
// auth.json 的形态两种都见过：裸 JSON 字符串（本版本）或 {token: …}。取兼容口径。
const AUTH_RAW = JSON.parse(readFileSync(join(HOME, 'auth.json'), 'utf8'));
const TOKEN = typeof AUTH_RAW === 'string' ? AUTH_RAW : (AUTH_RAW && AUTH_RAW.token) || '';
if (!TOKEN) { console.error('auth.json carried no token — abort'); await cleanup(); process.exit(2); }
log(`[auth] token from isolated home (len=${String(TOKEN).length})`);

/* ── 浏览器：真引擎 + 真 vault；全程记录 nf-ticket / nf-file 请求与状态 ── */
const netLog = [];
const browser = await chromium.launch();
const context = await browser.newContext({ baseURL: BASE, viewport: { width: 1440, height: 900 } });
await context.addInitScript((tok) => {
  // 品牌命名空间（branding.key('<x>') = `<lowerName>_<x>`；本项目 lowerName=stand），
  // 旧前缀一并写：两套读法都覆盖，前端迁移/兼容层怎么取都拿得到。
  localStorage.setItem('stand_token', tok);
  localStorage.setItem('nebflow_token', tok);
  localStorage.setItem('neblink_token', tok);
  localStorage.setItem('stand_locale', 'zh-CN');
  localStorage.setItem('nebflow_locale', 'zh-CN');
  // WS 帧嗅探：#303 打开腿走 pop.readFile → fileContent，帧不落 DOM，必须抓。
  window.__nfWs = [];
  const Orig = window.WebSocket;
  window.WebSocket = class extends Orig {
    constructor(...a) {
      super(...a);
      this.addEventListener('message', (ev) => {
        try {
          const m = JSON.parse(ev.data);
          if (m && (/readFile|fileContent|openFile/i.test(String(m.type || '')))) window.__nfWs.push(m);
        } catch { /* non-JSON */ }
      });
    }
    send(d) {
      try { const m = JSON.parse(d); if (/readFile/i.test(String(m.type || ''))) window.__nfWs.push({ sent: m }); } catch { /* raw */ }
      return super.send(d);
    }
  };
}, TOKEN);
const page = await context.newPage();
const pageErrors = [];
page.on('pageerror', (e) => pageErrors.push(String(e.message || e).slice(0, 200)));
const consoleLog = [];
page.on('console', (m) => consoleLog.push(`${m.type()}: ${String(m.text()).slice(0, 240)}`));
page.on('response', (r) => {
  const u = r.url();
  if (/\/(api|ws)\//.test(u) && r.status() >= 400) consoleLog.push(`HTTP ${r.status()} ${u.slice(0, 160)}`);
});
page.on('request', (r) => {
  const u = r.url();
  if (/\/api\/(nf-file|nf-ticket)/.test(u)) {
    const sp = new URL(u, BASE).searchParams;
    netLog.push({ phase: 'request', method: r.method(), path: sp.get('path') || '', ticket: !!sp.get('ticket'), url: u.slice(0, 260), at: Date.now() });
  }
});
const mintRounds = [];
page.on('response', (r) => {
  const u = r.url();
  if (!/\/api\/(nf-file|nf-ticket)/.test(u)) return;
  const sp = new URL(u, BASE).searchParams;
  netLog.push({ phase: 'response', status: r.status(), path: sp.get('path') || '', url: u.slice(0, 200), at: Date.now() });
  // 铸票应答体（决定性证据：哪条候选被端点拒了、reason 是什么）
  if (/\/api\/nf-ticket/.test(u) && r.status() === 200) {
    r.json().then((body) => {
      mintRounds.push({
        tickets: Object.keys((body && body.tickets) || {}),
        rejected: (body && body.rejected) || [],
        at: Date.now(),
      });
    }).catch(() => {});
  }
});
page.on('requestfailed', (r) => { if (/\/api\/(nf-file|nf-ticket)/.test(r.url())) netLog.push({ phase: 'failed', url: r.url().slice(0, 200), err: r.failure()?.errorText || '' }); });

// 真登录腿（不是塞 localStorage）：POST /api/auth/console-ticket（Bearer 档）
// 铸一次性 console ticket ⇒ 浏览器访问 `/?ticket=…` 兑换 ⇒ 服务端下发
// `stand_session` HttpOnly 会话 cookie。REST 与 WS 握手都吃这枚 cookie。
{
  const r = await fetch(`${BASE}/api/auth/console-ticket`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${TOKEN}` },
  });
  check('隔离实例铸到 console ticket（Bearer 档 200）', r.status === 200, `HTTP ${r.status}`);
  const body = await r.json().catch(() => null);
  if (!body || !body.ticket) { console.error('console ticket mint failed — abort'); await cleanup(); process.exit(2); }
  await page.goto(`${BASE}/?ticket=${encodeURIComponent(body.ticket)}`, { waitUntil: 'domcontentloaded' });
}
// 会话 rail 自证：authcheck 必须 204（不是 401/403）
const authz = await page.evaluate(async () => {
  try { const r = await fetch('/api/nf-authcheck', { credentials: 'same-origin' }); return r.status; } catch (e) { return -1; }
});
check('会话 cookie 生效（/api/nf-authcheck = 204）', authz === 204, `HTTP ${authz}`);
await page.waitForSelector('#activity-bar', { timeout: 60000, state: 'attached' });
const ob = await page.waitForSelector('.onboarding-overlay', { state: 'attached', timeout: 5000 }).then(() => true).catch(() => false);
if (ob) {
  const skip = await page.$('#ob-skip');
  if (skip) await skip.click().catch(() => {});
  await page.waitForSelector('.onboarding-overlay', { state: 'detached', timeout: 5000 }).catch(() => {});
}
await sleep(1200);

/** 用应用自己的打开腿（workspace-open-item 空载荷 ⇒ 真 pop.readFile → fileContent）。 */
async function openDoc(absPath, title) {
  const mark = Date.now();
  await page.evaluate(({ absPath, title }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType: '', title, content: '', absPath, pinned: true },
    }));
  }, { absPath, title });
  await page.waitForSelector('.canvas-tab-pane.active .canvas-md-scroll', { timeout: 20000, state: 'attached' }).catch(async () => {
    await page.screenshot({ path: join(OUT, 'debug-open-failed.png') });
    const dbg = await page.evaluate(() => ({
      panes: document.querySelectorAll('.canvas-tab-pane').length,
      mdScroll: document.querySelectorAll('.canvas-md-scroll').length,
      canvasOpen: document.body.classList.contains('canvas-open'),
      tabs: Array.from(document.querySelectorAll('.canvas-tab')).map((t) => (t.textContent || '').trim().slice(0, 60)),
      wsReady: (window.state && window.state.ws) ? 'yes' : 'n/a',
      activeId: (window.__nfWsFrames || []).length,
      toast: (document.querySelector('.toast, .nf-toast') || {}).textContent || '',
      wsFrames: (window.__nfWs || []).slice(-6),
    })).catch((e) => ({ err: String(e).slice(0, 200) }));
    log('[dbg-fail] ' + JSON.stringify(dbg));
    writeFileSync(join(OUT, 'debug-console.log'), consoleLog.join('\n'));
    log('[dbg-fail] console tail:\n' + consoleLog.slice(-25).join('\n'));
    throw new Error('doc did not open');
  });
  await sleep(3500);   // 铸票 + 取字节 + 级联
  if (process.env.NB_E2E_DEBUG === '1') {
    const dbg = await page.evaluate(() => ({
      panes: document.querySelectorAll('.canvas-tab-pane').length,
      activePane: document.querySelectorAll('.canvas-tab-pane.active').length,
      mdScroll: document.querySelectorAll('.canvas-md-scroll').length,
      canvasOpen: document.body.classList.contains('canvas-open'),
      tabs: Array.from(document.querySelectorAll('.canvas-tab')).map((t) => (t.textContent || '').trim().slice(0, 40)),
      bodyCls: document.body.className,
    }));
    log('[dbg] ' + JSON.stringify(dbg));
  }
  const read = await page.evaluate(() => {
    const root = document.querySelector('.canvas-tab-pane.active .canvas-md-viewer');
    if (!root) return null;
    const imgs = Array.from(root.querySelectorAll('img')).map((im) => {
      const r = im.getBoundingClientRect();
      return {
        alt: im.getAttribute('alt') || '',
        nw: im.naturalWidth, nh: im.naturalHeight,
        w: Math.round(r.width), h: Math.round(r.height),
        visible: r.width > 0 && r.height > 0 && im.naturalWidth > 0,
        path: im.dataset.nfMdPath || '',
        srcKind: String(im.currentSrc || im.src || '').startsWith('blob:') ? 'blob' : 'nf-file',
      };
    });
    const fails = Array.from(root.querySelectorAll('[data-nf-md-img-fail]')).map((el) => ({
      text: (el.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 160),
      path: (el.querySelector('.nf-md-img-fail-path') || {}).textContent || '',
      reason: (el.querySelector('.nf-md-img-fail-reason') || {}).textContent || '',
    }));
    const scroller = root.closest('.canvas-md-scroll');
    return { imgs, fails, contentWidth: Math.round(root.getBoundingClientRect().width), scrollHeight: scroller ? scroller.scrollHeight : 0 };
  });
  return { read, net: netLog.filter((e) => e.at >= mark) };
}

/** 把「证据元素」滚到视野中并截图（报告内嵌图自证用）。
 *
 *  改前/改后必须拍**同一位置**才有对照价值：目标可以是 img（改后），也可以是
 *  只剩正文文字 / 裸破图（改前）。所以在文档内做一次「先 img、再文本、再 alt」
 *  的三段匹配，命中后展开其 `<details>` 祖先（作者样本二把图放在折叠块里），
 *  再直接写 `.canvas-md-scroll.scrollTop`（不用 scrollIntoView —— 它会把滚动
 *  写进所有可滚祖先，见 viewers/markdown.js:scrollHeadingToTop 注释）。
 *  @param {string} name 输出文件基名
 *  @param {string} match 目标文案（路径片段 / alt / 可见文字） */
async function shotAt(name, match) {
  const info = await page.evaluate(({ match }) => {
    const root = document.querySelector('.canvas-tab-pane.active .canvas-md-viewer');
    if (!root) return { err: 'no viewer' };
    const scroller = root.closest('.canvas-md-scroll');
    const imgs = Array.from(root.querySelectorAll('img'));
    let el = imgs.find((im) => `${im.dataset.nfMdPath || ''} ${im.alt || ''} ${im.src || ''}`.includes(match));
    let kind = el ? 'img' : '';
    if (!el) {
      el = Array.from(root.querySelectorAll('a, code, p, li, span, div'))
        .filter((n) => (n.textContent || '').includes(match) && !n.querySelector('img'))
        .sort((a, b) => (a.textContent || '').length - (b.textContent || '').length)[0];
      kind = el ? 'text' : '';
    }
    if (!el) return { err: 'target-not-found' };
    const det = el.closest('details');
    if (det && !det.open) det.open = true;
    const r = el.getBoundingClientRect();
    const sr = scroller.getBoundingClientRect();
    scroller.scrollTop = Math.max(0, scroller.scrollTop + (r.top - sr.top) - 140);
    return { kind, h: Math.round(r.height), w: Math.round(r.width), text: (el.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 80) };
  }, { match });
  await sleep(600);
  const pane = page.locator('.canvas-tab-pane.active').first();
  await pane.screenshot({ path: join(OUT, name) }).catch(() => {});
  log(`[shot] ${name} ⇒ ${JSON.stringify(info)}`);
  return info;
}

/* A. 作者样本一 */
{
  const { read, net } = await openDoc(join(VAULT, DOC_A), DOC_A);
  log(`[A] ${DOC_A} imgs=${read.imgs.length} fails=${read.fails.length}`);
  check('A 作者样本一打开成功（真 vault 文件）', !!read);
  // 该文档的图片引用是**行内代码里的证据路径**（`~/.stand/evidence/…`），不是
  // 图片语法 ⇒ 按其自身写法不该出图（见报告 §1 读数）。
  check('A 样本一文档里 0 条图片语法（读数：它的图引用是行内代码路径）', read.imgs.length === 0, JSON.stringify(read.imgs));
  await page.screenshot({ path: join(OUT, `${MODE === 'before' ? 'BEFORE-' : ''}A-canvas-note-button.png`) });
  writeFileSync(join(OUT, 'A-net.json'), JSON.stringify(net, null, 2));
}

/* B. 作者样本二（`![[裸名]]`，图在 docs/attachments/） */
{
  const { read, net } = await openDoc(join(VAULT, DOC_B), DOC_B);
  log(`[B] ${DOC_B} imgs=${read.imgs.length} fails=${read.fails.length}`);
  const img = read.imgs.find((i) => /config-single-source-provider-modal/.test(i.alt || '') || /config-single-source-provider-modal/.test(i.path || ''));
  check('B 作者样本二：![[裸名]] 真出图（nw>0 且可见）', !!img && img.visible, JSON.stringify(img));
  check('B 生效路径落在 vault 的 attachments/ 腿', !!img && /\/attachments\/config-single-source-provider-modal__20261105\.png$/.test(img.path), img && img.path);
  const reqs = net.filter((e) => e.phase === 'request');
  const resp = net.filter((e) => e.phase === 'response');
  log(`[B] 网络：请求 ${reqs.length} 条 / 响应 ${resp.length} 条`);
  for (const r of resp) log(`[B]   ${r.status}  ${r.path ? r.path.split('/').slice(-2).join('/') : '(ticket)'}`);
  // 决定性证据：铸票应答的 rejected 表 —— 基准①② 的候选被**端点判决**拒掉
  // （reason=not-found），所以一次 404/401 请求都不会发出去。
  const rejectedFlat = mintRounds.flatMap((r) => r.rejected);
  check('B 基准①② 候选被铸票面判 not-found（不发请求）',
    rejectedFlat.some((x) => /docs\/config-single-source-provider-modal/.test(x.path || '') && String(x.reason) === 'not-found'),
    JSON.stringify(rejectedFlat).slice(0, 300));
  check('B attachments 腿取字节恰 1 次、200',
    resp.filter((r) => /\/attachments\/config-single-source-provider-modal/.test(r.path || '') && r.status === 200).length === 1,
    JSON.stringify(resp.map((r) => [r.status, (r.path || '').split('/').slice(-2).join('/')])));
  check('B 零 401 / 零 404（没有白发一次的请求）',
    !net.some((e) => e.status === 401 || e.status === 404),
    JSON.stringify(resp.map((r) => [r.status, (r.path || '').split('/').slice(-2).join('/')])));
  for (const r of mintRounds) log(`[B] mint: tickets=${r.tickets.length} rejected=${JSON.stringify(r.rejected).slice(0, 200)}`);
  await page.screenshot({ path: join(OUT, `${MODE === 'before' ? 'BEFORE-' : ''}B-config-single-source.png`) });
  // 同位置对照图：改后是**图**，改前是**一行蓝字文件名**（决定性画面差异）
  await shotAt(`${MODE === 'before' ? 'BEFORE-' : ''}B-visible.png`, 'config-single-source-provider-modal');
  writeFileSync(join(OUT, MODE === 'before' ? 'BEFORE-B-net.json' : 'B-net.json'), JSON.stringify(net, null, 2));
  writeFileSync(join(OUT, MODE === 'before' ? 'BEFORE-B-readings.json' : 'B-readings.json'), JSON.stringify(read, null, 2));
  writeFileSync(join(OUT, MODE === 'before' ? 'BEFORE-mint.json' : 'mint.json'), JSON.stringify(mintRounds, null, 2));
}

/* C. 三写法 × 四基准夹具 */
{
  const { read, net } = await openDoc(join(VAULT, FIXTURE_MD), FIXTURE_MD);
  log(`[C] ${FIXTURE_MD} imgs=${read.imgs.length} fails=${read.fails.length}`);
  const want = ['标准·文档同目录', '标准·子目录', '标准·绝对路径', 'assets/via-assets.png', 'vault-attach.png', 'HTML·文档同目录', 'HTML·绝对路径'];
  const missing = want.filter((a) => !read.imgs.some((i) => i.alt === a && i.visible));
  check('C 三写法 × 四基准 7 枚全出图', missing.length === 0, missing.join(', '));
  check('C 断链出现明确失败占位', read.fails.length >= 1 && /does-not-exist\.png/.test(read.fails[0].text || ''), JSON.stringify(read.fails));
  check('C 图片不超过内容列宽', read.imgs.every((i) => !i.visible || i.w <= read.contentWidth), `列宽=${read.contentWidth} 宽=${JSON.stringify(read.imgs.map((i) => i.w))}`);
  await page.screenshot({ path: join(OUT, `${MODE === 'before' ? 'BEFORE-' : ''}C-doc-images.png`) });
  // 「![[…]] 三基准」对照面：改后出图 / 改前只剩蓝字；同一位置同一裁切
  await shotAt(`${MODE === 'before' ? 'BEFORE-' : ''}C-wikilink-visible.png`, 'vault-attach.png');
  // 三写法并排区（真图 × 标准 / 子目录 / 绝对路径）
  await shotAt(`${MODE === 'before' ? 'BEFORE-' : ''}C-grid-visible.png`, '标准·子目录');
  // 断链用例：改后是带原因的占位块，改前是 44px 裸破图
  await shotAt(`${MODE === 'before' ? 'BEFORE-' : ''}C-failface.png`, 'does-not-exist.png');
  // 断链占位单独特写（元素级裁切，读得清原因与路径）
  const failLoc = page.locator('.canvas-tab-pane.active .canvas-md-viewer [data-nf-md-img-fail]').first();
  if (await failLoc.count()) await failLoc.screenshot({ path: join(OUT, `${MODE === 'before' ? 'BEFORE-' : ''}C-failface-zoom.png`) }).catch(() => {});
  writeFileSync(join(OUT, 'C-net.json'), JSON.stringify(net, null, 2));
  writeFileSync(join(OUT, MODE === 'before' ? 'BEFORE-C-readings.json' : 'C-readings.json'), JSON.stringify(read, null, 2));
}

check('全程零 pageerror', pageErrors.length === 0, pageErrors.join(' | '));
await browser.close();

/* ── post-flight：宿主面一字未动 ── */
const P6138_AFTER = hostSnapshot(6138);
const P8080_AFTER = hostSnapshot(8080);
check('6138 监听面未变（未重启/未 kill 宿主）', P6138_AFTER === P6138_BEFORE, `before=[${P6138_BEFORE}] after=[${P6138_AFTER}]`);
check('8080 监听面未变', P8080_AFTER === P8080_BEFORE, `before=[${P8080_BEFORE}] after=[${P8080_AFTER}]`);

await cleanup();
writeFileSync(join(OUT, 'run.log'), runLog.join('\n') + '\n');
writeFileSync(join(OUT, 'net-all.json'), JSON.stringify(netLog, null, 2));
if (process.env.CLEAN === '1') { try { rmSync(HOME, { recursive: true, force: true }); } catch { /* best effort */ } }

console.log(failed === 0 ? '\nALL PASS' : `\n${failed} FAIL`);
process.exit(failed === 0 ? 0 : 1);

// selection-ask-side-matrix.mjs — 锚点侧实测矩阵（r2 第 1 点补测）。
// 隔离网关 + 本 worktree 真实前端；不 mock 小模型（只测锚点几何，不发问）。
// 矩阵：①user→user ②ai→ai ③ai 起选 ai→user ④user 起选 user→ai，三视口 1200/390/375。
// 每组记录：起选气泡侧（实测 rect 中心 vs 会话列中心）、锚点 data-ask-side、
// 锚点与起选气泡外侧的实测间距（anchor.left − br.right 或 br.left − anchor.right）。
// 运行：STAND_JAR=<assembly jar> node tests/selection-ask-side-matrix.mjs
import { spawn } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import { establishSession } from './helpers/session-rail.mjs';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const root = `/private/tmp/qa-sidemx-live-${process.pid}`;
const privateHome = `/private/tmp/qa-sidemx-user-${process.pid}`;
const project = repo.includes('/.stand/worktrees/') ? repo.split('/.stand/worktrees/')[0] : repo;
const artifacts = process.env.SIDEMX_EVIDENCE || path.join(project, '.stand/tmp', path.basename(repo), 'evidence', `sidemx-${process.pid}`);
const jar = process.env.STAND_JAR;
const JAVA = process.env.STAND_JAVA || '/Library/Java/JavaVirtualMachines/temurin-23.jdk/Contents/Home/bin/java';
if (!jar) throw Error('STAND_JAR required');
if (root === '/Users/kaiyu/.stand' || !root.startsWith('/private/tmp/')) throw Error('unsafe root');
mkdirSync(privateHome, { recursive: true }); mkdirSync(artifacts, { recursive: true });
const freePort = () => new Promise((resolve, reject) => {
  const server = net.createServer(); server.on('error', reject);
  server.listen(0, '127.0.0.1', () => { const port = server.address().port; server.close(() => resolve(port)); });
});

/** 页内：按 DOM 顺序取 user/ai 正文行，找第一对相邻 (A,B) 角色组合。 */
const PAIR = (wantA, wantB) => {
  const rows = Array.from(document.querySelectorAll('#chat .row')).filter(r => r.classList.contains('user') || r.classList.contains('ai'));
  const role = r => (r.classList.contains('user') ? 'user' : 'ai');
  for (let i = 0; i + 1 < rows.length; i++) {
    if (role(rows[i]) === wantA && role(rows[i + 1]) === wantB) return { a: rows[i], b: rows[i + 1], roles: rows.map(role) };
  }
  return { a: null, b: null, roles: rows.map(role) };
};

/** 页内：从 a 行首文本到 b 行末文本建 Range，写入 selection，返回读数。 */
const SELECT_PAIR = (wantA, wantB) => {
  const textNode = (row, first) => {
    const bubble = row && row.querySelector('.bubble');
    if (!bubble) return null;
    const w = document.createTreeWalker(bubble, NodeFilter.SHOW_TEXT);
    let last = null, n;
    while ((n = w.nextNode())) { if (n.textContent.trim()) { if (first) return n; last = n; } }
    return last;
  };
  const rows = Array.from(document.querySelectorAll('#chat .row')).filter(r => r.classList.contains('user') || r.classList.contains('ai'));
  const role = r => (r.classList.contains('user') ? 'user' : 'ai');
  let a = null, b = null;
  for (let i = 0; i + 1 < rows.length; i++) {
    if (role(rows[i]) === wantA && role(rows[i + 1]) === wantB) { a = rows[i]; b = rows[i + 1]; break; }
  }
  if (!a) return { ok: false, roles: rows.map(role) };
  const na = textNode(a, true), nb = textNode(b, false);
  if (!na || !nb) return { ok: false, why: 'no text node' };
  const r = document.createRange(); r.setStart(na, 0); r.setEnd(nb, nb.textContent.length);
  const s = getSelection(); s.removeAllRanges(); s.addRange(r);
  return { ok: true, text: r.toString().slice(0, 60) };
};

/** 页内实测：起选气泡（startRow）实际 rect、锚点实际 rect、会话列；不读类名做判据。 */
const MEASURE_START = (wantA) => {
  const rows = Array.from(document.querySelectorAll('#chat .row')).filter(r => r.classList.contains('user') || r.classList.contains('ai'));
  const startRow = rows.find(r => (wantA === 'user' ? r.classList.contains('user') : r.classList.contains('ai')) && getSelection().containsNode(r, true)) || null;
  const bubble = startRow ? startRow.querySelector('.bubble') : null;
  const br = bubble ? bubble.getBoundingClientRect() : null;
  const chat = document.getElementById('chat')?.getBoundingClientRect() || null;
  const btn = document.querySelector('.selection-ask-btn');
  const visible = !!btn && !btn.hidden;
  const ar = visible ? btn.getBoundingClientRect() : null;
  const measuredSide = br && chat ? ((br.left + br.width / 2) >= (chat.left + chat.width / 2) ? 'right' : 'left') : null;
  return {
    visible,
    startBubble: br ? { left: Math.round(br.left), right: Math.round(br.right) } : null,
    chat: chat ? { left: Math.round(chat.left), right: Math.round(chat.right) } : null,
    anchor: ar ? { left: Math.round(ar.left), right: Math.round(ar.right) } : null,
    anchorSideAttr: btn ? btn.dataset.askSide || null : null,
    measuredSide,
    // 间距 = 锚点贴外侧的实测距离（负/大 = 不贴）。right：anchor.left − bubble.right；left：bubble.left − anchor.right。
    gapPx: br && ar ? (btn.dataset.askSide === 'right' ? Math.round(ar.left - br.right) : Math.round(br.left - ar.right)) : null,
    // 判据：锚点与起选气泡矩形**不相交**（水平+垂直任一方向分离即可）。
    overlap: br && ar ? !(ar.right <= br.left || ar.left >= br.right || ar.bottom <= br.top || ar.top >= br.bottom) : null,
    askPos: btn ? btn.dataset.askPos || null : null,
  };
};

const CASES = [
  { id: 'C1_user_user', a: 'user', b: 'user' },
  { id: 'C2_ai_ai', a: 'ai', b: 'ai' },
  { id: 'C3_ai_then_user', a: 'ai', b: 'user' },
  { id: 'C4_user_then_ai', a: 'user', b: 'ai' },
];
const VIEWPORTS = [
  { label: 'desktop', mobile: false, width: 1200, height: 900 },
  { label: 'mobile', mobile: true, width: 390, height: 844 },
  { label: 'mobile375', mobile: true, width: 375, height: 812 },
];

let gateway, browser;
try {
  const port = await freePort();
  if ([6138, 8080, 8097].includes(port)) throw Error('protected port');
  const env = { PATH: '/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin', HOME: privateHome, NEBFLOW_HOME_DIR: root };
  mkdirSync(root, { recursive: true });
  writeFileSync(`${root}/stand.json`, JSON.stringify({ workSchedule: { enabled: false }, safety: { defaultMode: 'auto-all' }, llm: { providers: { mock: { baseUrl: 'http://127.0.0.1:9/v1/', apiKey: 'fixture-only-placeholder', protocol: 'openai', models: [{ id: 'mock-1', contextWindow: 128000 }] } }, fallback: { preferred: 'mock/mock-1' } } }));
  mkdirSync(`${root}/agents/Nebula`, { recursive: true });
  writeFileSync(`${root}/agents/Nebula/agent.json`, JSON.stringify({ name: 'Nebula', model: { preferred: 'mock/mock-1' } }));
  writeFileSync(`${root}/onboarding.json`, JSON.stringify({ state: 'skipped' }));
  gateway = spawn(JAVA, ['--add-opens', 'java.base/java.lang=ALL-UNNAMED', '-Dnebflow.webdist=dev', '-cp', `${repo}/src/main/resources:${jar}`, 'nebflow.Main', '--home', root, '--port', String(port), '--no-browser', '--foreground', 'start'], { cwd: repo, env: { ...env, NEBFLOW_GATEWAY_PORT: String(port) }, stdio: ['pipe', 'pipe', 'pipe'] });
  console.log(`gatewayPid=${gateway.pid} port=${port}`);
  let log = '';
  gateway.stdout.on('data', d => { log += d; }); gateway.stderr.on('data', d => { log += d; });
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(Error(`readiness timeout: ${log.slice(-800)}`)), 90000);
    const check = d => { if (String(d).includes('gateway listening on')) { clearTimeout(timer); resolve(); } };
    gateway.stdout.on('data', check); gateway.stderr.on('data', check);
    gateway.once('exit', code => { clearTimeout(timer); reject(Error(`gateway exited ${code}`)); });
  });
  const token = JSON.parse(readFileSync(`${root}/auth.json`, 'utf8'));
  const base = `http://127.0.0.1:${port}`;
  const created = await fetch(`${base}/api/sessions`, { method: 'POST', headers: { 'content-type': 'application/json', authorization: `Bearer ${token}` }, body: JSON.stringify({ name: 'sidemx fixture' }) });
  if (!created.ok) throw Error(`session create ${created.status}`);
  browser = await chromium.launch({ headless: true });
  const report = [];
  for (const vp of VIEWPORTS) {
    const context = await browser.newContext({ viewport: { width: vp.width, height: vp.height }, isMobile: vp.mobile, hasTouch: vp.mobile });
    const page = await context.newPage();
    page.on('pageerror', e => console.log('pageerror', String(e)));
    await page.addInitScript(() => {
      const Native = window.WebSocket;
      window.__frames = [];
      window.WebSocket = class extends Native { constructor(...a) { super(...a); window.__socket = this; this.addEventListener('message', e => { try { window.__frames.push(JSON.parse(e.data)); } catch {} }); } };
    });
    await establishSession(base, token, page, { waitUntil: 'domcontentloaded' });
    await page.waitForFunction(() => window.__socket?.readyState === 1, null, { timeout: 30000 });
    await page.waitForFunction(() => window.__frames.some(f => f.type === 'sessionList' && f.activeId), null, { timeout: 30000 });
    await page.waitForFunction(() => window.__frames.some(f => f.type === 'historyPage'), null, { timeout: 30000 });
    // 夹具：u,u,a,a,u,a —— 相邻 (user,user)(user,ai)(ai,ai)(ai,user)(user,ai) 全覆盖。
    await page.evaluate(async () => {
      const chat = await import('/js/chat.js');
      const persist = await import('/js/persistence.js');
      chat.renderUserBubble('matrix user one', [], Date.now(), { msgId: 'mx-u1' });
      chat.renderUserBubble('matrix user two', [], Date.now(), { msgId: 'mx-u2' });
      persist.restoreFromBackendHistory([{ type: 'ai', text: 'matrix ai one' }], { scrollToBottom: false, busyTail: false });
      persist.restoreFromBackendHistory([{ type: 'ai', text: 'matrix ai two' }], { scrollToBottom: false, busyTail: false });
      chat.renderUserBubble('matrix user three', [], Date.now(), { msgId: 'mx-u3' });
      persist.restoreFromBackendHistory([{ type: 'ai', text: 'matrix ai three' }], { scrollToBottom: false, busyTail: false });
    });
    await page.waitForTimeout(400);
    const roles = await page.evaluate(`(${PAIR.toString()})('user','ai').roles`);
    console.log(`[${vp.label}] DOM roles=${JSON.stringify(roles)}`);
    for (const c of CASES) {
      await page.evaluate(() => { getSelection().removeAllRanges(); document.getElementById('chat')?.scrollTo?.(0, 0); });
      await page.waitForTimeout(120);
      const sel = await page.evaluate(([a, b, fn]) => new Function(`return (${fn})`)()(a, b), [c.a, c.b, SELECT_PAIR.toString()]);
      if (!sel.ok) { report.push({ viewport: vp.label, case: c.id, skipped: true, sel }); console.log(`[${vp.label}] ${c.id} SKIP ${JSON.stringify(sel)}`); continue; }
      try { await page.waitForFunction(() => { const b = document.querySelector('.selection-ask-btn'); return !!b && !b.hidden; }, null, { timeout: 4000 }); } catch {}
      const m = await page.evaluate(([fn, wantA]) => new Function(`return (${fn})`)()(wantA), [MEASURE_START.toString(), c.a]);
      await page.screenshot({ path: `${artifacts}/${vp.label}-${c.id}.png` });
      const line = { viewport: vp.label, case: c.id, startRole: c.a, endRole: c.b, selText: sel.text, ...m };
      report.push(line);
      console.log(`[${vp.label}] ${c.id} ${JSON.stringify(m)}`);
    }
    await context.close();
  }
  writeFileSync(`${artifacts}/side-matrix.json`, JSON.stringify({ repo, report }, null, 2));
  console.log(`MATRIX_DONE ${artifacts}/side-matrix.json`);
} finally {
  if (browser) await browser.close();
  if (gateway && gateway.exitCode === null) {
    const exit = new Promise(resolve => gateway.once('exit', resolve)); gateway.stdin.write('quit\n');
    const r = await Promise.race([exit.then(() => 'exited'), new Promise(resolve => setTimeout(() => resolve('NOT_EXITED'), 30000))]);
    console.log(`gatewayShutdown=${r} pid=${gateway.pid}`);
  }
}

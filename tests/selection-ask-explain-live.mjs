// selection-ask-explain-live.mjs — 选区解释（r2 返工）真机现场：隔离网关 + loopback mock 小模型
// + 本 worktree 真实前端资源。骨架沿用 selection-ask-multi-live.mjs（同一 harness）。
//
// 作者四点（2026-11-23 验收返工）：
//   P1 锚点贴**被选文本所在气泡的那一侧**（实测 rect，不按类名假设；手机端可能反转）；
//   P2 解释走**专门小模型**（WS selectionExplain → LlmKernel SmallLlmModel），非 /ask 会话；
//   P3 对话框样式 = canvas ask 同族（ask-dock / ask-bub / 输入条）；
//   P4 解释输出里**选中原文 = 词条 chip**，点击 = 展开解释内容。
//
// 判据（每视口）：
//   A_userRectsMeasured  两条 user 气泡的真实 rect（left/right）已实测；
//   A_anchorSideMatches  锚点 data-ask-side 与被选气泡实测外侧一致（锚点中心在气泡外侧同侧，
//                        偏移 ≤ 锚点宽+8px）；
//   B_dialogOpen         点锚 ⇒ `.ask-dock.selection-dock` 在场；
//   C_explainFromSmallLlm 投递腿 = selectionExplain 帧（非 ask 帧），回答落地；
//   D_chipsRendered      解释面板内选中原文渲染为 `.nf-term` 词条 chip；
//   E_chipClickExpands   点词条 ⇒ 展开解释内容（`.sel-explain-detail` 可见）。
//
// 运行（worktree 根）：STAND_JAR=<assembly jar> node tests/selection-ask-explain-live.mjs
import { spawn } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import http from 'node:http';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import { establishSession } from './helpers/session-rail.mjs';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const root = `/private/tmp/qa-selexplain-live-${process.pid}`;
const privateHome = `/private/tmp/qa-selexplain-user-${process.pid}`;
const project = repo.includes('/.stand/worktrees/') ? repo.split('/.stand/worktrees/')[0] : repo;
const artifacts = process.env.SELEXPLAIN_EVIDENCE || path.join(project, '.stand/tmp', path.basename(repo), 'evidence', String(process.pid));
const jar = process.env.STAND_JAR;
const JAVA = process.env.STAND_JAVA || '/Library/Java/JavaVirtualMachines/temurin-23.jdk/Contents/Home/bin/java';
if (!jar) throw Error('STAND_JAR required (isolated assembly jar; never the live engine jar)');
if (root === '/Users/kaiyu/.stand' || !root.startsWith('/private/tmp/')) throw Error('unsafe root');
mkdirSync(privateHome, { recursive: true }); mkdirSync(artifacts, { recursive: true });
const freePort = () => new Promise((resolve, reject) => {
  const server = net.createServer(); server.on('error', reject);
  server.listen(0, '127.0.0.1', () => { const port = server.address().port; server.close(() => resolve(port)); });
});
const listen = server => new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
let hits = 0, gateway, browser;
const EXPLAIN = '这是一段确定的模拟解释。';
// mock 小模型：OpenAI 兼容流式回文（一次调用一段解释）。
const mock = http.createServer((req, res) => {
  if (req.method !== 'POST') return res.writeHead(404).end();
  let body = ''; req.on('data', d => { body += d; });
  req.on('end', () => {
    hits++;
    const text = JSON.stringify({ id: 'mock', object: 'chat.completion.chunk', model: 'mock-1', choices: [{ index: 0, delta: { content: EXPLAIN }, finish_reason: null }] });
    const done = JSON.stringify({ id: 'mock', object: 'chat.completion.chunk', model: 'mock-1', choices: [{ index: 0, delta: {}, finish_reason: 'stop' }] });
    res.writeHead(200, { 'content-type': 'text/event-stream' }); res.end(`data: ${text}\n\ndata: ${done}\n\ndata: [DONE]\n\n`);
  });
});

/** 选中两条 user 气泡的正文（u1 首字 → u2 末字）。 */
const SELECT_USERS = (a, b) => {
  const textNode = (id) => {
    const row = document.querySelector(`#chat .row[data-msg-id="${id}"]`);
    const bubble = row && row.querySelector('.bubble');
    if (!bubble) return null;
    const w = document.createTreeWalker(bubble, NodeFilter.SHOW_TEXT);
    let n = w.nextNode(); while (n && !n.textContent.trim()) n = w.nextNode();
    return n;
  };
  const na = textNode(a), nb = textNode(b);
  if (!na || !nb) return { ok: false };
  const r = document.createRange();
  r.setStart(na, 0); r.setEnd(nb, nb.textContent.length);
  const s = getSelection(); s.removeAllRanges(); s.addRange(r);
  return { ok: true, text: r.toString() };
};

/** 实测读数：两条 user 气泡的真实屏幕边 + 锚点实测 + 会话列；不读类名。 */
const MEASURE = (ids) => {
  const rects = ids.map(id => {
    const row = document.querySelector(`#chat .row[data-msg-id="${id}"]`);
    const b = row && row.querySelector('.bubble');
    if (!b) return null;
    const r = b.getBoundingClientRect();
    return { id, left: Math.round(r.left), right: Math.round(r.right), top: Math.round(r.top), bottom: Math.round(r.bottom), rowClass: row.className };
  });
  const btn = document.querySelector('.selection-ask-btn');
  const anchor = btn && !btn.hidden ? btn.getBoundingClientRect() : null;
  const chat = document.getElementById('chat')?.getBoundingClientRect();
  const vw = window.innerWidth;
  return {
    vw,
    chat: chat ? { left: Math.round(chat.left), right: Math.round(chat.right) } : null,
    bubbles: rects,
    anchor: anchor ? { left: Math.round(anchor.left), right: Math.round(anchor.right), w: Math.round(anchor.width), cx: Math.round(anchor.left + anchor.width / 2) } : null,
    anchorSideAttr: btn ? btn.dataset.askSide || null : null,
  };
};

try {
  await listen(mock);
  const mockPort = mock.address().port, port = await freePort();
  if ([6138, 8080, 8097].includes(port) || [6138, 8080, 8097].includes(mockPort)) throw Error('protected port');
  const env = { PATH: '/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin', HOME: privateHome, NEBFLOW_HOME_DIR: root, MOCK_PORT: String(mockPort) };
  mkdirSync(root, { recursive: true });
  writeFileSync(`${root}/stand.json`, JSON.stringify({ workSchedule: { enabled: false }, safety: { defaultMode: 'auto-all' }, llm: { providers: { mock: { baseUrl: `http://127.0.0.1:${mockPort}/v1/`, apiKey: 'fixture-only-placeholder', protocol: 'openai', models: [{ id: 'mock-1', contextWindow: 128000 }] } }, fallback: { preferred: 'mock/mock-1' } } }));
  mkdirSync(`${root}/agents/Nebula`, { recursive: true });
  writeFileSync(`${root}/agents/Nebula/agent.json`, JSON.stringify({ name: 'Nebula', model: { preferred: 'mock/mock-1' } }));
  writeFileSync(`${root}/onboarding.json`, JSON.stringify({ state: 'skipped' }));
  console.log(`repo=${repo}`);
  console.log(`root=${root} mockPort=${mockPort} artifacts=${artifacts}`);
  gateway = spawn(JAVA, ['--add-opens', 'java.base/java.lang=ALL-UNNAMED', '-Dnebflow.webdist=dev', '-cp', `${repo}/src/main/resources:${jar}`, 'nebflow.Main', '--home', root, '--port', String(port), '--no-browser', '--foreground', 'start'], { cwd: repo, env: { ...env, NEBFLOW_GATEWAY_PORT: String(port) }, stdio: ['pipe', 'pipe', 'pipe'] });
  console.log(`gatewayPid=${gateway.pid}`);
  let log = '';
  gateway.stdout.on('data', d => { log += d; }); gateway.stderr.on('data', d => { log += d; });
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(Error(`gateway readiness timeout: ${log.slice(-1600)}`)), 90000);
    const check = d => { if (String(d).includes('gateway listening on')) { clearTimeout(timer); resolve(); } };
    gateway.stdout.on('data', check); gateway.stderr.on('data', check);
    gateway.once('exit', code => { clearTimeout(timer); reject(Error(`gateway exited ${code}: ${log.slice(-1600)}`)); });
  });
  const token = JSON.parse(readFileSync(`${root}/auth.json`, 'utf8'));
  const base = `http://127.0.0.1:${port}`;
  const created = await fetch(`${base}/api/sessions`, { method: 'POST', headers: { 'content-type': 'application/json', authorization: `Bearer ${token}` }, body: JSON.stringify({ name: 'explain fixture' }) });
  if (!created.ok) throw Error(`session create ${created.status}`);
  browser = await chromium.launch({ headless: true });
  const report = [];
  const VIEWPORTS = [
    { label: 'desktop', mobile: false, width: 1200, height: 900 },
    { label: 'mobile', mobile: true, width: 390, height: 844 },
    { label: 'mobile375', mobile: true, width: 375, height: 812 },
  ];
  for (const vp of VIEWPORTS) {
    const { label, mobile } = vp;
    const context = await browser.newContext({ viewport: { width: vp.width, height: vp.height }, isMobile: mobile, hasTouch: mobile });
    const page = await context.newPage();
    page.on('pageerror', error => console.log('pageerror', String(error)));
    await page.addInitScript(() => {
      const Native = window.WebSocket;
      window.__frames = []; window.__sent = [];
      const nativeSend = Native.prototype.send;
      Native.prototype.send = function (data) { try { window.__sent.push(JSON.parse(data)); } catch {} return nativeSend.call(this, data); };
      window.WebSocket = class extends Native { constructor(...a) { super(...a); window.__socket = this; this.addEventListener('message', e => { try { window.__frames.push(JSON.parse(e.data)); } catch {} }); } };
    });
    await establishSession(base, token, page, { waitUntil: 'domcontentloaded' });
    await page.waitForFunction(() => window.__socket?.readyState === 1, null, { timeout: 30000 });
    await page.waitForFunction(() => window.__frames.some(f => f.type === 'sessionList' && f.activeId), null, { timeout: 30000 });
    const activeId = await page.evaluate(() => window.__frames.find(f => f.type === 'sessionList' && f.activeId).activeId);
    // fixture：两条 user 气泡（r2 修正：不选 warm-up ai 行）。等历史帧落定再注入（同旧探针次序）。
    await page.waitForFunction(() => window.__frames.some(f => f.type === 'historyPage'), null, { timeout: 30000 });
    await page.evaluate(async () => {
      const chat = await import('/js/chat.js');
      chat.renderUserBubble('first user line for explain', [], Date.now(), { msgId: 'x-user-1' });
      chat.renderUserBubble('second user line for explain', [], Date.now(), { msgId: 'x-user-2' });
    });
    await page.waitForTimeout(400);
    await page.evaluate(() => document.querySelector('#chat .row[data-msg-id="x-user-1"]').scrollIntoView({ block: 'center' }));
    await page.waitForTimeout(150);
    const sel = await page.evaluate(([a, b, fn]) => new Function(`return (${fn})`)()(a, b), ['x-user-1', 'x-user-2', SELECT_USERS.toString()]);
    try { await page.waitForFunction(() => { const b = document.querySelector('.selection-ask-btn'); return !!b && !b.hidden; }, null, { timeout: 4000 }); } catch {}
    const measured = await page.evaluate(`(${MEASURE.toString()})(['x-user-1','x-user-2'])`);
    await page.screenshot({ path: `${artifacts}/${label}-A-select-users.png` });
    console.log(`[${label}] sel=${JSON.stringify(sel)} measured=${JSON.stringify(measured)}`);
    report.push({ viewport: label, activeId, sel: sel.ok, measured });
    await context.close();
  }
  writeFileSync(`${artifacts}/selection-ask-explain-measure.json`, JSON.stringify({ repo, report, mockHits: hits }, null, 2));
  console.log(`MEASURE_DONE report=${artifacts}/selection-ask-explain-measure.json`);
} finally {
  if (browser) await browser.close();
  if (gateway && gateway.exitCode === null) {
    const exit = new Promise(resolve => gateway.once('exit', resolve)); gateway.stdin.write('quit\n');
    const result = await Promise.race([exit.then(() => 'exited'), new Promise(resolve => setTimeout(() => resolve('NOT_EXITED'), 30000))]);
    console.log(`gatewayShutdown=${result} pid=${gateway.pid}`);
    if (result !== 'exited') process.exitCode = 1;
  }
  await new Promise(resolve => mock.close(resolve));
  console.log(`mockClosed=true hits=${hits} root=${root}`);
}

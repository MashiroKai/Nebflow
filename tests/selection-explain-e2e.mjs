// selection-explain-e2e.mjs — 作者四点全链路（隔离网关 + loopback mock 小模型 + 本 worktree 前端）。
// ① 选两条 user 气泡 → 锚点贴 user 侧（实测不相交）；② 点锚 → 选区解释面板开（canvas 外壳）；
// ③ 小模型返回解释（selectionExplain 帧，非 /ask）；④ 原文片段渲染为词条 chip，点击 → 展开解释。
// 三视口 1200 / 390 / 375，逐档截图。运行：STAND_JAR=<assembly jar> node tests/selection-explain-e2e.mjs
import { spawn } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import http from 'node:http';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import { establishSession } from './helpers/session-rail.mjs';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const root = `/private/tmp/qa-selexe2e-${process.pid}`;
const privateHome = `/private/tmp/qa-selexe2e-user-${process.pid}`;
const project = repo.includes('/.stand/worktrees/') ? repo.split('/.stand/worktrees/')[0] : repo;
const artifacts = process.env.SELEXE2E_EVIDENCE || path.join(project, '.stand/tmp', path.basename(repo), 'evidence', `e2e-${process.pid}`);
const jar = process.env.STAND_JAR;
const JAVA = process.env.STAND_JAVA || '/Library/Java/JavaVirtualMachines/temurin-23.jdk/Contents/Home/bin/java';
if (!jar) throw Error('STAND_JAR required');
if (root === '/Users/kaiyu/.stand' || !root.startsWith('/private/tmp/')) throw Error('unsafe root');
mkdirSync(privateHome, { recursive: true }); mkdirSync(artifacts, { recursive: true });
const EXPLAIN = '这是一段确定的模拟解释（小模型）。';
let hits = 0, gateway, browser;
const mock = http.createServer((req, res) => {
  if (req.method !== 'POST') return res.writeHead(404).end();
  let body = ''; req.on('data', d => { body += d; });
  req.on('end', () => {
    hits++;
    let streaming = false; try { streaming = JSON.parse(body).stream === true; } catch {}
    if (!streaming) {
      res.writeHead(200, { 'content-type': 'application/json' });
      return res.end(JSON.stringify({ id: 'mock', object: 'chat.completion', model: 'mock-1', choices: [{ index: 0, message: { role: 'assistant', content: EXPLAIN }, finish_reason: 'stop' }], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } }));
    }
    const t = JSON.stringify({ id: 'mock', object: 'chat.completion.chunk', model: 'mock-1', choices: [{ index: 0, delta: { content: EXPLAIN }, finish_reason: null }] });
    const d = JSON.stringify({ id: 'mock', object: 'chat.completion.chunk', model: 'mock-1', choices: [{ index: 0, delta: {}, finish_reason: 'stop' }] });
    res.writeHead(200, { 'content-type': 'text/event-stream' }); res.end(`data: ${t}\n\ndata: ${d}\n\ndata: [DONE]\n\n`);
  });
});
const listen = s => new Promise((res, rej) => { s.once('error', rej); s.listen(0, '127.0.0.1', res); });
const freePort = () => new Promise((res, rej) => { const s = net.createServer(); s.on('error', rej); s.listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => res(p)); }); });

/** 选中 x-user-1 首字 → x-user-2 末字（两条 user 气泡）。 */
const SELECT_USERS = () => {
  const tn = (id, first) => {
    const b = document.querySelector(`#chat .row[data-msg-id="${id}"] .bubble`);
    if (!b) return null;
    const w = document.createTreeWalker(b, NodeFilter.SHOW_TEXT);
    let last = null, n; while ((n = w.nextNode())) { if (n.textContent.trim()) { if (first) return n; last = n; } }
    return last;
  };
  const a = tn('x-user-1', true), b = tn('x-user-2', false);
  if (!a || !b) return { ok: false };
  const r = document.createRange(); r.setStart(a, 0); r.setEnd(b, b.textContent.length);
  const s = getSelection(); s.removeAllRanges(); s.addRange(r);
  return { ok: true };
};

/** 实测：锚点与两条 user 气泡不相交；面板是否 canvas 外壳；chip 数；解释区可见性。 */
const READ = () => {
  const btn = document.querySelector('.selection-ask-btn');
  const ar = btn && !btn.hidden ? btn.getBoundingClientRect() : null;
  const bubbles = ['x-user-1', 'x-user-2'].map(id => document.querySelector(`#chat .row[data-msg-id="${id}"] .bubble`)?.getBoundingClientRect()).filter(Boolean);
  const overlap = !!ar && bubbles.some(br => !(ar.right <= br.left || ar.left >= br.right || ar.bottom <= br.top || ar.top >= br.bottom));
  const dock = document.querySelector('.ask-dock.selection-dock');
  // point 3：词条 chip 在**原始气泡**里（不在面板里）；绿色 + 下划线取计算样式。
  const chips = Array.from(document.querySelectorAll('.bubble a.nf-term.selection-explain-chip'));
  const inPanel = Array.from(document.querySelectorAll('.selection-dock a.selection-explain-chip')).length;
  const cs = chips[0] ? getComputedStyle(chips[0]) : null;
  const tip = document.querySelector('.nf-term-tip');
  const tipShown = !!tip && tip.style.display !== 'none' && tip.getBoundingClientRect().width > 0;
  const ans = document.querySelector('.selection-explain-answer');
  const sr = btn ? btn.getBoundingClientRect() : null;
  // 居中参照 = 选中文字所在的第一条气泡文本块（选区在 step1 时已存在；chip 在 step2 才出）。
  const selRect = window.getSelection && window.getSelection().rangeCount
    ? window.getSelection().getRangeAt(0).getBoundingClientRect() : null;
  const spanR = chips.length ? chips[0].getBoundingClientRect() : (selRect && selRect.height ? selRect : null);
  return {
    anchorVisible: !!ar, anchorPos: btn ? btn.dataset.askPos || null : null, anchorOverlapsBubble: overlap,
    anchorIconIsChat: !!btn && !!btn.querySelector('svg.ask-fab-ic-chat'),
    anchorCenterDy: sr && spanR ? Math.round((sr.top + sr.height / 2) - (spanR.top + spanR.height / 2)) : null,
    dialogOpen: !!dock && !dock.classList.contains('closed'),
    dialogInputHidden: !!dock && getComputedStyle(dock.querySelector('.ask-input') || document.body).display === 'none',
    chipCount: chips.length, chipInPanelCount: inPanel, chipTexts: chips.map(c => c.textContent.slice(0, 20)),
    chipIsTerm: chips.length > 0 && chips.every(c => c.classList.contains('nf-term')),
    chipColor: cs ? cs.color : null,
    chipUnderline: cs ? cs.textDecorationLine : null,
    tipShown, tipText: tip ? tip.querySelector('.ts')?.textContent.slice(0, 40) : null,
    answerHiddenBeforeClick: ans ? ans.hidden : null,
    answerVisibleAfterClick: ans ? !ans.hidden : null,
    answerText: ans ? ans.textContent.slice(0, 40) : null,
    askFrameSent: null,
  };
};

try {
  await listen(mock);
  const mockPort = mock.address().port, port = await freePort();
  if ([6138, 8080, 8097].includes(port) || [6138, 8080, 8097].includes(mockPort)) throw Error('protected port');
  mkdirSync(root, { recursive: true });
  writeFileSync(`${root}/stand.json`, JSON.stringify({ workSchedule: { enabled: false }, safety: { defaultMode: 'auto-all' }, llm: { providers: { mock: { baseUrl: `http://127.0.0.1:${mockPort}/v1/`, apiKey: 'fixture-only-placeholder', protocol: 'openai', models: [{ id: 'mock-1', contextWindow: 128000 }] } }, fallback: { preferred: 'mock/mock-1' } } }));
  mkdirSync(`${root}/agents/Nebula`, { recursive: true });
  writeFileSync(`${root}/agents/Nebula/agent.json`, JSON.stringify({ name: 'Nebula', model: { preferred: 'mock/mock-1' } }));
  writeFileSync(`${root}/onboarding.json`, JSON.stringify({ state: 'skipped' }));
  gateway = spawn(JAVA, ['--add-opens', 'java.base/java.lang=ALL-UNNAMED', '-Dnebflow.webdist=dev', '-cp', `${repo}/src/main/resources:${jar}`, 'nebflow.Main', '--home', root, '--port', String(port), '--no-browser', '--foreground', 'start'], { cwd: repo, env: { PATH: '/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin', HOME: privateHome, NEBFLOW_HOME_DIR: root, NEBFLOW_GATEWAY_PORT: String(port) }, stdio: ['pipe', 'pipe', 'pipe'] });
  let log = '';
  gateway.stdout.on('data', d => { log += d; }); gateway.stderr.on('data', d => { log += d; });
  await new Promise((res, rej) => {
    const t = setTimeout(() => rej(Error(`readiness: ${log.slice(-800)}`)), 90000);
    const c = d => { if (String(d).includes('gateway listening on')) { clearTimeout(t); res(); } };
    gateway.stdout.on('data', c); gateway.stderr.on('data', c);
    gateway.once('exit', code => { clearTimeout(t); rej(Error(`gateway exited ${code}`)); });
  });
  const token = JSON.parse(readFileSync(`${root}/auth.json`, 'utf8'));
  const base = `http://127.0.0.1:${port}`;
  const created = await fetch(`${base}/api/sessions`, { method: 'POST', headers: { 'content-type': 'application/json', authorization: `Bearer ${token}` }, body: JSON.stringify({ name: 'explain e2e' }) });
  if (!created.ok) throw Error(`session create ${created.status}`);
  browser = await chromium.launch({ headless: true });
  const VIEWPORTS = [
    { label: 'desktop', mobile: false, width: 1200, height: 900 },
    { label: 'mobile', mobile: true, width: 390, height: 844 },
    { label: 'mobile375', mobile: true, width: 375, height: 812 },
  ];
  const report = [];
  for (const vp of VIEWPORTS) {
    const context = await browser.newContext({ viewport: { width: vp.width, height: vp.height }, isMobile: vp.mobile, hasTouch: vp.mobile });
    const page = await context.newPage();
    page.on('pageerror', e => console.log('pageerror', String(e)));
    await page.addInitScript(() => {
      const Native = window.WebSocket;
      window.__frames = []; window.__sent = [];
      const ns = Native.prototype.send;
      Native.prototype.send = function (data) { try { window.__sent.push(JSON.parse(data)); } catch {} return ns.call(this, data); };
      window.WebSocket = class extends Native { constructor(...a) { super(...a); window.__socket = this; this.addEventListener('message', e => { try { window.__frames.push(JSON.parse(e.data)); } catch {} }); } };
    });
    await establishSession(base, token, page, { waitUntil: 'domcontentloaded' });
    await page.waitForFunction(() => window.__socket?.readyState === 1, null, { timeout: 30000 });
    await page.waitForFunction(() => window.__frames.some(f => f.type === 'sessionList' && f.activeId), null, { timeout: 30000 });
    await page.waitForFunction(() => window.__frames.some(f => f.type === 'historyPage'), null, { timeout: 30000 });
    await page.evaluate(async () => {
      const chat = await import('/js/chat.js');
      chat.renderUserBubble('first user line for explain', [], Date.now(), { msgId: 'x-user-1' });
      chat.renderUserBubble('second user line for explain', [], Date.now(), { msgId: 'x-user-2' });
    });
    await page.waitForTimeout(400);
    await page.evaluate(() => document.querySelector('#chat .row[data-msg-id="x-user-1"]').scrollIntoView({ block: 'center' }));
    await page.waitForTimeout(150);
    // ① 选两条 user → 锚点
    await page.evaluate(`(${SELECT_USERS.toString()})()`);
    try { await page.waitForFunction(() => { const b = document.querySelector('.selection-ask-btn'); return !!b && !b.hidden; }, null, { timeout: 4000 }); } catch {}
    const step1 = await page.evaluate(`(${READ.toString()})()`);
    await page.screenshot({ path: `${artifacts}/${vp.label}-1-anchor.png` });
    // ② 点锚 → 面板
    await page.locator('.selection-ask-btn').click({ timeout: 4000 }).catch(() => {});
    try { await page.locator('.ask-dock.selection-dock').waitFor({ timeout: 4000 }); } catch {}
    try { await page.waitForFunction(() => !!document.querySelector('.bubble a.nf-term.selection-explain-chip'), null, { timeout: 4000 }); } catch {}
    const step2 = await page.evaluate(`(${READ.toString()})()`);
    await page.screenshot({ path: `${artifacts}/${vp.label}-2-dialog.png` });
    // ③ 解释帧来自 selectionExplain（非 ask）
    try { await page.waitForFunction(() => window.__frames.some(f => f.type === 'selectionExplain'), null, { timeout: 15000 }); } catch {}
    const sent = await page.evaluate(() => window.__sent.map(f => f.type));
    const frameType = await page.evaluate(() => (window.__frames.find(f => f.type === 'selectionExplain') || {}).type || null);
    // ④ 点词条 → 展开解释
    await page.locator('.bubble a.nf-term.selection-explain-chip').first().click({ timeout: 4000 }).catch(() => {});
    try { await page.waitForFunction(() => { const t = document.querySelector('.nf-term-tip'); return !!t && t.style.display !== 'none' && t.textContent.includes('模拟解释'); }, null, { timeout: 6000 }); } catch {}
    const step4 = await page.evaluate(`(${READ.toString()})()`);
    await page.screenshot({ path: `${artifacts}/${vp.label}-4-chip-expanded.png` });
    await page.keyboard.press('Escape');
    const cleanup = await page.evaluate(() => ({ chips: document.querySelectorAll('#chat .selection-explain-chip').length, tipVisible: !!document.querySelector('.nf-term-tip') && getComputedStyle(document.querySelector('.nf-term-tip')).display !== 'none' }));
    const line = { viewport: vp.label, sel: step1, dialog: step2, sentTypes: sent, selectionExplainFrame: frameType, afterChipClick: step4, cleanup, G_closeCleanup: cleanup.chips === 0 && !cleanup.tipVisible,
      askFrameSent: sent.includes('ask'), A_anchorNoOverlap: !step1.anchorOverlapsBubble && step1.anchorVisible,
      B_dialogOpen: step2.dialogOpen, C_smallModelFrame: frameType === 'selectionExplain' && !sent.includes('ask'),
      // D（point 3）：原始气泡里的选中文字 = 绿色 + 下划线 词条；面板里没有 chip。
      D_chipsAreTerms: step2.chipCount >= 1 && step2.chipIsTerm && step2.chipInPanelCount === 0 && step2.chipUnderline === 'underline',
      // E（point 3）：点词条 → canvas 词条同族弹窗 .nf-term-tip 展示解释。
      E_chipExpands: step4.tipShown === true && (step4.tipText || '').length > 0,
      // F（point 1/2）：锚点与选中首段垂直居中（±6px）；图标 = canvas Ask 同款 chat。
      F_anchorCentredIconChat: step2.anchorIconIsChat === true && step1.anchorCenterDy !== null && Math.abs(step1.anchorCenterDy) <= 6 };
    report.push(line);
    console.log(`[${vp.label}] ${JSON.stringify({ A: line.A_anchorNoOverlap, B: line.B_dialogOpen, C: line.C_smallModelFrame, D: line.D_chipsAreTerms, E: line.E_chipExpands, F: line.F_anchorCentredIconChat, color: step2.chipColor, pos: step1.anchorPos, chips: step2.chipCount, sent: sent })}`);
    await context.close();
  }
  writeFileSync(`${artifacts}/selection-explain-e2e.json`, JSON.stringify({ repo, report, mockHits: hits }, null, 2));
  const ok = report.every(l => l.A_anchorNoOverlap && l.B_dialogOpen && l.C_smallModelFrame && l.D_chipsAreTerms && l.E_chipExpands && l.F_anchorCentredIconChat && l.G_closeCleanup && !l.askFrameSent);
  console.log(`VERDICT=${ok ? 'PASS' : 'FAIL'} report=${artifacts}/selection-explain-e2e.json mockHits=${hits}`);
  if (!ok) process.exitCode = 1;
} finally {
  if (browser) await browser.close();
  if (gateway && gateway.exitCode === null) {
    const exit = new Promise(res => gateway.once('exit', res)); gateway.stdin.write('quit\n');
    const r = await Promise.race([exit.then(() => 'exited'), new Promise(res => setTimeout(() => res('NOT_EXITED'), 30000))]);
    console.log(`gatewayShutdown=${r} pid=${gateway.pid}`);
    if (r !== 'exited') process.exitCode = 1;
  }
  await new Promise(res => mock.close(res));
  console.log(`mockClosed=true hits=${hits}`);
}

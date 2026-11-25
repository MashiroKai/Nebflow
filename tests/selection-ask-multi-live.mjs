// selection-ask-multi-live.mjs — 主会话**多选合并 Ask** 真机现场（隔离网关 + loopback mock
// LLM + 本 worktree 的真实前端资源，零 stub）。骨架沿用 selection-ask-live.mjs。
//
// 设计正本 `model-rule-engine-and-panel-design__20261121_172934.md` §5（G1–G6）+ 作者令
// 2026-11-21 17:24 逐字（「图标和对话框就完全使用canvas的ask。回答完要生成可以点击的词条。
// 图标的位置……气泡框的旁边居中」）+ 2026-11-18 08:58「多选 进行ask 然后生成解释文字」。
//
// 判据：
//   M1 跨两条气泡连续选区 ⇒ `.selection-ask-btn` 出场（G1）；
//   M2 按钮纵向居中于覆盖段（|btnCenter − spanMid| ≤ 6px，G6）；
//   M3 点击 ⇒ 共用件 Ask 对话框 `.ask-dock.selection-dock` 在场；
//   M4 投递的 ask 帧 question 含 `<selection count="N">` 与 ≥2 条 `<quote n="i" from=…>`（G3 合并分段）；
//   M5 无输入框上方合并预览条（`.ask-merge-strip` 数 = 0；作者原话未要求）；
//   M6 回答落地 + 词条钩子已执行（ai 答复气泡带 `data-nf-term-linkified="1"`，askTermLinks 接线）；
//   M7 选区菜单零出场（复制/引用退役延续）。
//
// 运行（worktree 根）：node tests/selection-ask-multi-live.mjs
import { spawn } from 'node:child_process';
import { mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import http from 'node:http';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import { establishSession } from './helpers/session-rail.mjs';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const root = `/private/tmp/qa-selmulti-live-${process.pid}`;
const privateHome = `/private/tmp/qa-selmulti-user-${process.pid}`;
// 证据落项目 .stand/tmp/<wt>/evidence（工件规则）：worktree 下取其项目根，主检出即项目根。
const project = repo.includes('/.stand/worktrees/') ? repo.split('/.stand/worktrees/')[0] : repo;
const artifacts = process.env.SELMULTI_EVIDENCE || path.join(project, '.stand/tmp', path.basename(repo), 'evidence', String(process.pid));
// jar 缺省 = 本 worktree 自己构建的最新 assembly（mtime 最新）；旧的硬编码 11-08 jar 不含 selectionExplain 路由。
const ownJar = (() => {
  try {
    const d = path.join(repo, 'target/scala-3.5.2');
    return readdirSync(d).filter(f => /^stand-assembly-.*\.jar$/.test(f))
      .map(f => ({ p: path.join(d, f), t: statSync(path.join(d, f)).mtimeMs }))
      .sort((a, b) => b.t - a.t)[0]?.p || null;
  } catch { return null; }
})();
const jar = process.env.STAND_JAR || ownJar || '/Users/kaiyu/Claude code/Stand/target/scala-3.5.2/stand-assembly-2026.11.8-beta.1.jar';
const JAVA = process.env.STAND_JAVA || '/Library/Java/JavaVirtualMachines/temurin-23.jdk/Contents/Home/bin/java';
if (root === '/Users/kaiyu/.stand' || !root.startsWith('/private/tmp/')) throw Error('unsafe root');
mkdirSync(privateHome, { recursive: true }); mkdirSync(artifacts, { recursive: true });
const freePort = () => new Promise((resolve, reject) => {
  const server = net.createServer(); server.on('error', reject);
  server.listen(0, '127.0.0.1', () => { const port = server.address().port; server.close(() => resolve(port)); });
});
const listen = server => new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
let hits = 0, gateway, browser;
const ANSWER = '这是一段确定的模拟解释。';
const mock = http.createServer((req, res) => {
  if (req.method !== 'POST') return res.writeHead(404).end();
  let body = ''; req.on('data', d => { body += d; });
  req.on('end', () => {
    hits++;
    // 选区解释腿（selectionExplain）是非流式单次调用 ⇒ 回 JSON；流式请求回 SSE。
    let streaming = false; try { streaming = JSON.parse(body).stream === true; } catch {}
    if (!streaming) {
      res.writeHead(200, { 'content-type': 'application/json' });
      return res.end(JSON.stringify({ id: 'mock', object: 'chat.completion', model: 'mock-1', choices: [{ index: 0, message: { role: 'assistant', content: ANSWER }, finish_reason: 'stop' }], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } }));
    }
    const text = JSON.stringify({ id: 'mock', object: 'chat.completion.chunk', model: 'mock-1', choices: [{ index: 0, delta: { content: ANSWER }, finish_reason: null }] });
    const done = JSON.stringify({ id: 'mock', object: 'chat.completion.chunk', model: 'mock-1', choices: [{ index: 0, delta: {}, finish_reason: 'stop' }] });
    res.writeHead(200, { 'content-type': 'text/event-stream' }); res.end(`data: ${text}\n\ndata: ${done}\n\ndata: [DONE]\n\n`);
  });
});

/** 造跨两气泡的真 Selection：起点 = 首行文本偏移 0，终点 = 末行文本偏移 len。 */
const SELECT_SPAN = (firstId, lastId, len) => {
  const textOf = (id) => {
    const row = document.querySelector(`#chat .row[data-msg-id="${id}"]`);
    const bubble = row && row.querySelector('.bubble');
    if (!bubble) return null;
    const w = document.createTreeWalker(bubble, NodeFilter.SHOW_TEXT);
    let n = w.nextNode();
    while (n && !n.textContent.trim()) n = w.nextNode();
    return n;
  };
  let a = textOf(firstId), b = textOf(lastId);
  if (!a || !b) return { ok: false, why: 'missing text node' };
  // 按文档顺序排两端（起点必须在终点之前，否则 Range 倒置 ⇒ 空选区）。
  if (a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_PRECEDING) [a, b] = [b, a];
  const r = document.createRange();
  r.setStart(a, 0);
  r.setEnd(b, Math.min(len, b.textContent.length));
  const s = getSelection(); s.removeAllRanges(); s.addRange(r);
  return { ok: true, text: r.toString() };
};

/** 读数：按钮可见性、侧别、纵向居中偏差、菜单零出场。 */
const PROBE = () => {
  const btn = document.querySelector('.selection-ask-btn');
  const visible = !!btn && !btn.hidden;
  const r = visible ? btn.getBoundingClientRect() : null;
  const sel = getSelection();
  const rng = sel && sel.rangeCount ? sel.getRangeAt(0) : null;
  let spanMid = null;
  if (rng) {
    const rr = rng.getBoundingClientRect();
    spanMid = rr.top + rr.height / 2;
  }
  // 覆盖段（首末气泡）纵向中点 —— 与 placeAnchor 的 G6 口径一致的读数面。
  const rows = Array.from(document.querySelectorAll('#chat .row')).filter(x => rng && rng.intersectsNode(x));
  let segMid = null;
  if (rows.length) {
    const tops = rows.map(x => x.querySelector('.bubble')?.getBoundingClientRect()).filter(Boolean);
    const t = Math.min(...tops.map(x => x.top)), bm = Math.max(...tops.map(x => x.bottom));
    segMid = (t + bm) / 2;
  }
  // M2'（新契约）：锚点矩形与覆盖段每条气泡矩形不相交（手机端外侧放不下时贴上方，不压字）。
  const bubbleRects = rows.map(x => x.querySelector('.bubble')?.getBoundingClientRect()).filter(Boolean);
  const overlap = !!r && bubbleRects.some(br => !(r.right <= br.left || r.left >= br.right || r.bottom <= br.top || r.top >= br.bottom));
  return {
    overlap,
    askPos: btn ? btn.dataset.askPos || null : null,
    visible,
    side: btn ? btn.dataset.askSide || null : null,
    spanCount: btn ? btn.dataset.spanCount || null : null,
    centerDelta: r && segMid != null ? Math.round((r.top + r.height / 2) - segMid) : null,
    // 夹紧判据：覆盖段中点若在会话列之外（小视口下多段选区常见），按钮必须被夹在列内（不许飞出）。
    segMidInCol: (() => { const cr = document.getElementById('chat')?.getBoundingClientRect(); return cr && segMid != null ? segMid >= cr.top && segMid <= cr.bottom : null; })(),
    btnInCol: (() => { const cr = document.getElementById('chat')?.getBoundingClientRect(); return cr && r ? r.top >= cr.top && r.bottom <= cr.bottom : null; })(),
    menuCount: document.querySelectorAll('.fm-context-menu').length,
    mergeStrips: document.querySelectorAll('.ask-merge-strip').length,
    selected: sel ? sel.toString() : '',
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
  const created = await fetch(`${base}/api/sessions`, { method: 'POST', headers: { 'content-type': 'application/json', authorization: `Bearer ${token}` }, body: JSON.stringify({ name: 'multi fixture' }) });
  if (!created.ok) throw Error(`session create ${created.status}`);
  browser = await chromium.launch({ headless: true });
  const report = [];
  // 视口：桌面 1200 / 手机 390 / 手机 375（新 UI 不得在 375 档新增红）。
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
      window.__frames = [];
      window.__sent = [];
      const nativeSend = Native.prototype.send;
      Native.prototype.send = function (data) {
        try { window.__sent.push(JSON.parse(data)); } catch {}
        return nativeSend.call(this, data);
      };
      window.WebSocket = class extends Native { constructor(...a) { super(...a); window.__socket = this; this.addEventListener('message', e => { try { window.__frames.push(JSON.parse(e.data)); } catch {} }); } };
    });
    await establishSession(base, token, page, { waitUntil: 'domcontentloaded' });
    await page.waitForFunction(() => window.__socket?.readyState === 1, null, { timeout: 30000 });
    await page.waitForFunction(() => window.__frames.some(f => f.type === 'sessionList' && f.activeId), null, { timeout: 30000 });
    const activeId = await page.evaluate(() => window.__frames.find(f => f.type === 'sessionList' && f.activeId).activeId);
    const warm = await fetch(`${base}/api/sessions/${activeId}/turn`, { method: 'POST', headers: { 'content-type': 'application/json', authorization: `Bearer ${token}` }, body: JSON.stringify({ content: 'fixture warmup', timeoutSec: 30 }) });
    console.log(`[${label}] warmup status=${warm.status} hits=${hits}`);
    if (!warm.ok || hits === 0) throw Error('warmup did not reach loopback model');
    await page.waitForFunction(() => window.__frames.some(f => f.type === 'historyPage'), null, { timeout: 30000 });
    // 两条真实行：我方 user（右气泡）→ 对方 ai（左气泡），连续相邻 ⇒ 跨气泡连续选区。
    // Warmup is persisted in the shared session across viewports; select the fixture AI,
    // never the first historical AI (which may be hidden or covered by the mobile panel).
    await page.evaluate(async () => {
      const chat = await import('/js/chat.js');
      chat.renderUserBubble('first user line for multi select', [], Date.now(), { msgId: 'multi-fixture-user' });
      const persist = await import('/js/persistence.js');
      persist.restoreFromBackendHistory([{ type: 'ai', text: 'second ai line for multi select' }], { scrollToBottom: false, busyTail: false });
    });
    await page.waitForTimeout(400);
    const aiId = await page.evaluate(() => [...document.querySelectorAll('#chat .row.ai')].find(row => row.textContent.includes('second ai line for multi select'))?.dataset.msgId || null);
    if (!aiId) throw Error('no ai row');
    // 选区：从 user 行首到 ai 行第 6 字（跨两条气泡）。
    await page.evaluate(() => document.querySelector('#chat .row[data-msg-id="multi-fixture-user"]').scrollIntoView({ block: 'center' }));
    await page.waitForTimeout(150);
    const sel = await page.evaluate(([a, b, n, fn]) => new Function(`return (${fn})`)()(a, b, n), ['multi-fixture-user', aiId, 6, SELECT_SPAN.toString()]);
    console.log(`[${label}] select=${JSON.stringify(sel)}`);
    try { await page.waitForFunction(() => { const b = document.querySelector('.selection-ask-btn'); return !!b && !b.hidden; }, null, { timeout: 4000 }); } catch {}
    const pre = await page.evaluate(`(${PROBE.toString()})()`);
    await page.screenshot({ path: `${artifacts}/${label}-multi-onSelect.png` });
    await page.locator('.selection-ask-btn').click({ timeout: 4000 }).catch(() => {});
    try { await page.locator('.ask-dock.selection-dock').waitFor({ timeout: 4000 }); } catch {}
    // M6'（point 3 契约）：原始气泡里的选中文字 = 绿色下划线词条 chip（不在面板里）；
    // 点击 chip ⇒ canvas 词条同族弹窗 .selection-explain-tip 展示解释（小模型返回的 ANSWER）。
    let answerText = null, chipCount = 0, expanded = false;
    try { await page.waitForFunction(() => document.querySelectorAll('.bubble a.nf-term.selection-explain-chip').length > 0, null, { timeout: 6000 }); } catch {}
    chipCount = await page.locator('.bubble a.nf-term.selection-explain-chip').count();
    // 原始气泡 chip 可能仍在 nf-pop 入场动效（opacity 0）：等可见再点。
    try { await page.waitForFunction(() => { const c = document.querySelector('.bubble a.nf-term.selection-explain-chip'); if (!c) return false; const r = c.getBoundingClientRect(); return r.width > 0 && getComputedStyle(c).opacity !== '0'; }, null, { timeout: 6000 }); } catch {}
    if (chipCount) console.log(`[${label}] chipGeom ${JSON.stringify(await page.evaluate(() => { const c = document.querySelector('.bubble a.nf-term.selection-explain-chip'); if (!c) return null; c.scrollIntoView({ block: 'center' }); const r = c.getBoundingClientRect(); let a = c, hid = []; while (a && a.nodeType === 1) { const cs = getComputedStyle(a); if (cs.display === 'none' || cs.visibility === 'hidden') hid.push(a.className.toString().slice(0, 40) || a.tagName); a = a.parentElement; } const top = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2); return { r: [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)], top: top ? (top.className || top.tagName).toString().slice(0, 80) : null, inChat: !!top && !!top.closest('#chat'), hidden: hid.slice(0, 4), connected: c.isConnected, text: c.textContent.slice(0, 20) }; }))}`);
    // 只点**可见**的原文 chip（同一 DOM 里可能残留不可见的旧 chip）。
    // 限定：当前会话 #chat 内、可见、未被 hidden/动效遮罩的 chip；记录盖住它的元素（层叠诊断）。
    const visIdx = await page.evaluate(() => { const all = [...document.querySelectorAll('#chat .bubble a.nf-term.selection-explain-chip')]; all.forEach(c => c.removeAttribute('data-probe-chip')); const v = all.find(c => { const r = c.getBoundingClientRect(); const vis = r.width > 0 && r.height > 0 && getComputedStyle(c).opacity !== '0' && !c.closest('[hidden]'); return vis; }); if (v) { v.setAttribute('data-probe-chip', '1'); const r = v.getBoundingClientRect(); const top = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2); window.__chipCover = top ? (top.className || top.tagName).toString().slice(0, 80) : null; } return !!v; });
    if (!visIdx) console.log(`[${label}] no visible in-transcript chip; cover=${await page.evaluate(() => window.__chipCover || null)}`);
    else console.log(`[${label}] chip cover=${await page.evaluate(() => window.__chipCover)}`);
    const clickErr = chipCount && visIdx ? await page.locator('#chat .bubble a.nf-term.selection-explain-chip[data-probe-chip="1"]').first().click({ timeout: 4000 }).then(() => null, (e) => String(e).slice(0, 200)) : 'no-chip';
    if (clickErr) console.log(`[${label}] chip click: ${clickErr}`);
    try { await page.waitForFunction((a) => { const x = document.querySelector('.document-term-card'); return !!x && x.style.display !== 'none' && x.textContent.includes(a); }, ANSWER, { timeout: 20000 }); expanded = true; } catch (e) { console.log(`[${label}] explain wait failed: ${String(e).slice(0, 160)}`); }
    answerText = await page.locator('.document-term-card .document-term-summary').allTextContents();
    const tipState = await page.evaluate(() => { const t = document.querySelector('.document-term-card'); return { display: t ? t.style.display : 'absent', td: t ? t.querySelector('.document-term-summary')?.textContent.slice(0, 60) : null, aiEl: document.querySelector('.selection-explain-answer')?.textContent.slice(0, 60) || null, chips: document.querySelectorAll('.bubble a.nf-term.selection-explain-chip').length }; });
    console.log(`[${label}] tipState ${JSON.stringify(tipState)}`);
    // M4'（新契约）：发送的是 selectionExplain 帧（小模型路由），不得有 ask 帧。
    const frames = await page.evaluate(() => window.__sent.filter(f => f.type === 'selectionExplain').map(f => String(f.question || '')));
    const q = frames[0] || '';
    const dlgOpen = await page.evaluate(() => { const p = document.querySelector('.ask-dock.selection-dock'); return !!p && !p.classList.contains('closed'); });
    await page.screenshot({ path: `${artifacts}/${label}-multi-dialog.png` });
    const line = {
      viewport: label, sel: sel.ok, pre,
      M1_btnVisibleAcrossBubbles: !!pre.visible,
      // M2 = 居中（中点在列内 ⇒ |偏差| ≤ 6px）或 夹紧（中点在列外 ⇒ 按钮仍在列内）。
      M2_centered: pre.overlap === false && pre.btnInCol === true,
      M2_mode: 'nonoverlap',
      M2_askPos: pre.askPos,
      M2_centerDelta: pre.centerDelta,
      M3_dialogOpen: dlgOpen,
      // 顺序 = DOM 顺序（本 fixture 的 ai 行在前），断言两条来源都在、编号连续、计数为 2。
      // 段数随视口的预热行布局变化（手机端选区跨更多行）⇒ 判「≥2 段合并 + 两侧来源都在」，不钉死 2。
      M4_questionIsMerged: /<selection count="([2-9]\d*)">/.test(q) && /<quote n="1" from="(user|ai)">/.test(q) && /<quote n="2" from="(user|ai)">/.test(q) && /from="user"/.test(q) && /from="ai"/.test(q),
      M5_noMergeStrip: pre.mergeStrips === 0,
      M6_termHookRan: chipCount > 0,
      M6_answerShown: expanded && (answerText || []).some(t => t.includes(ANSWER)),
      M7_menuZero: pre.menuCount === 0,
      question: q.slice(0, 400),
      answerText: (answerText || []).join(' | ').slice(0, 120),
    };
    report.push(line);
    console.log(`[${label}] ${JSON.stringify(line)}`);
    await page.keyboard.press('Escape');
    if (mobile) {
      // M8 真触摸长按：CDP 合成 touch 源的长按（duration 700ms，非 DOM 造 Range）→ 原生长按选词
      // →（touchend / selectionchange）→ 选区按钮出场。手机端既有的选区入口 = 原生长按选择；
      // 长按菜单已在 2026-10-31 退役（msgQuote.js:526-531 / mobileLayer.css:2373），不另造入口。
      await page.evaluate(() => { getSelection().removeAllRanges(); document.querySelector('#chat .row[data-msg-id="multi-fixture-user"]')?.scrollIntoView({ block: 'center' }); });
      await page.waitForTimeout(200);
      const pt = await page.evaluate(() => {
        const row = document.querySelector('#chat .row[data-msg-id="multi-fixture-user"]');
        const bubble = row && row.querySelector('.bubble');
        if (!bubble) return null;
        const w = document.createTreeWalker(bubble, NodeFilter.SHOW_TEXT);
        let n = w.nextNode(); while (n && !n.textContent.trim()) n = w.nextNode();
        if (!n) return null;
        const r = document.createRange(); r.setStart(n, 0); r.setEnd(n, Math.min(5, n.textContent.length));
        const b = r.getBoundingClientRect();
        return { x: Math.round(b.left + b.width / 2), y: Math.round(b.top + b.height / 2) };
      });
      const cdp = await context.newCDPSession(page);
      let touchSel = '', touchBtn = false;
      if (pt) {
        await cdp.send('Input.synthesizeTapGesture', { x: pt.x, y: pt.y, duration: 700, tapCount: 1, gestureSourceType: 'touch' });
        try { await page.waitForFunction(() => { const b = document.querySelector('.selection-ask-btn'); return !!b && !b.hidden; }, null, { timeout: 4000 }); } catch {}
        touchSel = await page.evaluate(() => String(getSelection()));
        touchBtn = await page.evaluate(() => { const b = document.querySelector('.selection-ask-btn'); return !!b && !b.hidden; });
        await page.screenshot({ path: `${artifacts}/${label}-touch-longpress.png` });
      }
      line.M8_touchLongPressSelects = touchSel.length > 0;
      line.M8_touchLongPressBtn = touchBtn;
      line.M8_selectedText = touchSel.slice(0, 40);
      console.log(`[${label}] M8 ${JSON.stringify({ sel: line.M8_touchLongPressSelects, btn: touchBtn, text: line.M8_selectedText })}`);
    }
    await page.keyboard.press('Escape');
    await context.close();
  }
  writeFileSync(`${artifacts}/selection-ask-multi-live-report.json`, JSON.stringify({ repo, report, mockHits: hits }, null, 2));
  const ok = report.every(l => l.M1_btnVisibleAcrossBubbles && l.M2_centered && l.M3_dialogOpen && l.M4_questionIsMerged && l.M5_noMergeStrip && l.M6_termHookRan && l.M6_answerShown && l.M7_menuZero
    && (!l.viewport.startsWith('mobile') || (l.M8_touchLongPressSelects && l.M8_touchLongPressBtn)));
  console.log(`VERDICT=${ok ? 'PASS' : 'FAIL'} report=${artifacts}/selection-ask-multi-live-report.json`);
  if (!ok) process.exitCode = 1;
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

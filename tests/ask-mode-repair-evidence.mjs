#!/usr/bin/env node
// ask-mode-repair-evidence.mjs — ask mode 修复批（作者令 10-31 五条 + 10-28 减法令
// 补验）证据脚本：**真实应用全壳**（index.html 真实导航/底栏/canvas 会话上下文）+
// 产品自带数据缝（routeWebSocket 帧 + 事件总线 `workspace-open-item`），零台架效果图、
// 零后端、零 sbt。
//
// 与 20261031_ask-fix-batch/shoot-ask-fix.mjs 的区别：那份只抓图，本份**带判据读数**
// （每项逐条落 readings.json），且 WEB_DIR 可注入 —— 同一份数据缝夹具分别驱动
// 「改前树」（减法令前 = c402239c7^ 的 src/main/resources/web）与「改后树」（本分支）
// 产出前后对比图，两侧同壳同夹具，只有站点资源不同。
//
// 跑法：
//   WEB_DIR=<web 树> OUT=<证据目录> LABEL=<before|after> node tests/ask-mode-repair-evidence.mjs
// 退出码：0 = 全部判据通过；1 = 有判据红（读数仍落盘，截图照抓）。
import { chromium } from 'playwright';
import { createHash } from 'node:crypto';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock, ticketGuard } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(process.env.WEB_DIR || join(HERE, '../src/main/resources/web'));
const OUT = resolve(process.env.OUT || join(HERE, '../.stand/tmp/ask-mode-repair-20261122/evidence'));
const LABEL = process.env.LABEL || 'after';
const PORT = Number(process.env.PORT || 8231);
const BASE = `http://127.0.0.1:${PORT}`;
const W = Number(process.env.VW || 1600);
const H = Number(process.env.VH || 1000);
mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.woff2': 'font/woff2', '.ico': 'image/x-icon' };
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(body);
  } catch { res.writeHead(404); res.end('not found'); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

const sidOf = (absPath) => 'ask-' + createHash('sha256').update(absPath, 'utf8').digest('hex').slice(0, 8);
const readings = { label: LABEL, webDir: WEB_DIR, checks: [], pageErrors: [] };
const check = (name, pass, detail) => {
  readings.checks.push({ name, pass: !!pass, detail: detail === undefined ? null : detail });
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}  — ${typeof detail === 'string' ? detail : JSON.stringify(detail)}`);
};

let wsRef = null;
const wsSend = (o) => { if (wsRef) wsRef.send(JSON.stringify(o)); };

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: W, height: H }, colorScheme: 'dark' });
await ctx.addInitScript(() => {
  localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
  localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
  localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
});
const page = await ctx.newPage();
page.on('pageerror', (e) => readings.pageErrors.push(String(e.message || e).slice(0, 300)));
await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
await page.route(/\/api\/nf-file\?/, async (route) => {
  if (await ticketGuard(route)) return;
  const p = new URL(route.request().url()).searchParams.get('path') || '';
  return route.fulfill({ status: 200, contentType: 'text/markdown', body: `# ${p}\n\nmock body\n` });
});
await installTicketMock(page);
await page.routeWebSocket(/\/ws/, (ws) => {
  wsRef = ws;
  ws.onMessage((raw) => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    if (m.type === 'askOpen') {
      const sid = m.sessionId || sidOf((m.file && m.file.absPath) || '');
      ws.send(JSON.stringify({
        type: 'askState', sessionId: sid, sessionKey: 'ask:' + sid.slice(4, 8),
        file: (m.file && m.file.absPath) || '', fileName: (m.file && m.file.name) || 'file', messages: [],
      }));
    }
  });
  ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
});
await page.goto(BASE + '/index.html');
await page.waitForSelector('#activity-bar', { timeout: 20000 });
await sleep(700);

// ── 真实会话上下文：开一个 canvas 文件卡（产品自带事件缝），再开 ask dock ──
const absPath = '/tmp/ask-mode-repair/液体玻璃 v2 笔记.md';
await page.evaluate(({ absPath }) => {
  window.dispatchEvent(new CustomEvent('workspace-open-item', {
    detail: { id: 'file:' + absPath, itemType: 'markdown', title: '液体玻璃 v2 笔记.md', content: '# 笔记\n', absPath, pinned: true },
  }));
}, { absPath });
await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${absPath}"]`, { timeout: 15000 });
await sleep(400);
await page.click('.ask-fab');
await sleep(500);

const INPUT_LOC = '.ask-input textarea, .ask-dock textarea, .ask-input input';
const inputEl = () => page.locator(INPUT_LOC).first();
const sid = sidOf(absPath);
const shell = await page.evaluate(() => ({
  activityBar: !!document.getElementById('activity-bar'),
  canvasPane: !!document.querySelector('.canvas-tab-pane'),
  dock: !!document.querySelector('.ask-dock:not(.closed)'),
}));
check('E0 真实全壳（导航栏 + canvas 会话上下文 + ask dock 在场）', shell.activityBar && shell.canvasPane && shell.dock, shell);

// ── ① 答复 markdown 渲染（复用主会话渲染腿） ──
const MD = [
  '## v1 → v2 改了什么\n\n',
  '- **顶缘渐隐带**：液体玻璃 v2 材质\n',
  '- 发送键：墨玻璃药丸\n\n',
  '| 面 | v1 | v2 |\n|---|---|---|\n| 顶栏 | 实体栏 | 渐隐带 |\n\n',
  '```css\n#g2-edge-fade { backdrop-filter: blur(18px); }\n```',
].join('');
await inputEl().fill('这份文件的 v2 改了什么？');
await inputEl().press('Enter');

// ── ④ 等待期：typing 单件、零空 ai 泡（双泡判据） ──
await sleep(350);
const waiting = await page.evaluate(() => {
  const col = document.querySelector('.ask-col');
  return {
    ai: document.querySelectorAll('.ask-dock .ask-bub.ai').length,
    typing: document.querySelectorAll('.ask-dock .ask-stream-line, .ask-dock .ask-bub.typing').length,
    user: document.querySelectorAll('.ask-dock .ask-bub.user').length,
    bubbles: document.querySelectorAll('.ask-dock .ask-bub').length,
  };
});
check('④ 等待期零空 ai 泡（气泡面只有用户问；等待指示为状态行单件）',
  waiting.ai === 0 && waiting.typing === 1 && waiting.user === 1, waiting);
await page.locator('.ask-colwrap').screenshot({ path: join(OUT, `${LABEL}-04-waiting-single-bubble.png`) });

// 回放合并守卫（双泡同源缺陷②）：早于落账的 askState 快照不得抹掉本地新问
wsSend({ type: 'askState', sessionId: sid, sessionKey: 'ask:' + sid.slice(4, 8), file: absPath, fileName: '液体玻璃 v2 笔记.md', messages: [] });
await sleep(300);
const afterReplay = await page.evaluate(() => ({
  user: document.querySelectorAll('.ask-dock .ask-bub.user').length,
  text: [...document.querySelectorAll('.ask-dock .ask-bub.user')].map((e) => e.textContent).join('|'),
}));
check('④-b 陈旧 askState 回放不抹掉本地新问（回放合并守卫）',
  afterReplay.user === 1 && afterReplay.text.includes('v2 改了什么'), afterReplay);

// 流式答复：逐段真帧（产品自带数据缝）
for (let i = 0; i < MD.length; i += 24) {
  wsSend({ type: 'textDelta', sessionId: sid, delta: MD.slice(i, i + 24) });
  await sleep(35);
}
wsSend({ type: 'done', sessionId: sid });
await sleep(500);
const md = await page.evaluate(() => {
  const b = [...document.querySelectorAll('.ask-dock .ask-bub.ai')].pop();
  if (!b) return { missing: true };
  const cs = getComputedStyle(b.querySelector('li') || b);
  return {
    h2: !!b.querySelector('h2'), li: !!b.querySelector('ul li'), table: !!b.querySelector('table td'),
    pre: !!b.querySelector('pre code'), rawPipe: b.textContent.includes('|---|'),
    rawHash: b.textContent.includes('## '), rawAsterisk: b.textContent.includes('**'),
    listDisplay: cs.display,
  };
});
check('① 答复走主会话渲染腿：标题/列表/表格/代码块成 DOM，零裸 markdown 字面量',
  md.h2 && md.li && md.table && md.pre && !md.rawPipe && !md.rawHash && !md.rawAsterisk, md);
await page.locator('.ask-dock').screenshot({ path: join(OUT, `${LABEL}-01-md-rendered.png`) });

// ── ⑤ 渐隐/间距：首条消息不得被遮挡（顶带净空 + 零覆盖元素） ──
// 静态档先测（单问单答、列不可滚）：首条 = 列首元素，顶净空与命中测试都得真值。
const staticFirst = await page.evaluate(() => {
  const col = document.querySelector('.ask-col');
  col.scrollTop = 0; // 顶态：首条应落在顶净空之下（列未滚动）
  const cs = getComputedStyle(col);
  const first = col.firstElementChild;
  const fr = first ? first.getBoundingClientRect() : null;
  const cr = col.getBoundingClientRect();
  const cx = fr ? fr.left + Math.min(24, fr.width / 2) : 0;
  const cy = fr ? fr.top + Math.min(18, fr.height / 2) : 0;
  const hit = fr ? document.elementFromPoint(cx, cy) : null;
  return {
    padTop: cs.paddingTop, firstTop: fr ? +fr.top.toFixed(1) : null, colTop: +cr.top.toFixed(1),
    gapFirst: fr ? +(fr.top - cr.top).toFixed(1) : null,
    hit: hit ? (hit.className || hit.tagName) : null, hitInFirst: !!(first && hit && first.contains(hit)),
    scrollable: col.scrollHeight > col.clientHeight,
  };
});
check('⑤-a 首条消息在列内留出顶净空（scrollTop=0 时 gap ≥ 6px，首条不被列顶裁切）',
  staticFirst.gapFirst !== null && staticFirst.gapFirst >= 6, staticFirst);
check('⑤-b 首条消息未被任何浮层遮挡（首条内命中，元素可达）', staticFirst.hitInFirst === true, staticFirst);
for (let i = 0; i < 3; i++) {
  await inputEl().fill(`追问 ${i + 1}：折射强度参数是多少？`);
  await inputEl().press('Enter');
  await sleep(120);
  wsSend({ type: 'textDelta', sessionId: sid, delta: `回答 ${i + 1}：feTurbulence 低频噪声 + 克制 scale。` });
  wsSend({ type: 'done', sessionId: sid });
  await sleep(220);
}
const scrolled = await page.evaluate(() => {
  const col = document.querySelector('.ask-col');
  const cs = getComputedStyle(col);
  col.scrollTop = col.scrollHeight; // 拉动到底（作者令 2026-11-06 09:57 ②）
  const bubbles = [...col.querySelectorAll('.ask-bub')];
  const last = bubbles[bubbles.length - 1];
  const inputRow = document.querySelector('.ask-input');
  const lr = last ? last.getBoundingClientRect() : null;
  const ir = inputRow ? inputRow.getBoundingClientRect() : null;
  const veil = document.querySelectorAll('.ask-dock .ask-veil, .ask-dock .ds-edge-fade').length;
  return {
    padBottom: cs.paddingBottom, veilEls: veil,
    colMaskImage: cs.maskImage || cs.webkitMaskImage,
    scrollable: col.scrollHeight > col.clientHeight,
    gapToInput: (lr && ir) ? +(ir.top - lr.bottom).toFixed(1) : null,
    overlap: (lr && ir) ? +(Math.min(lr.bottom, ir.bottom) - Math.max(lr.top, ir.top)).toFixed(1) : null,
  };
});
check('⑤-c 面板内零独立遮罩条元素 + 列零 mask（渐隐非色带；作者令 2026-11-04 09:22 / 11-06 09:57）',
  scrolled.veilEls === 0 && scrolled.colMaskImage === 'none', scrolled);
check('⑤-d 拉动到底：最新气泡完全在输入条之上（不遮挡正文，间距 > 0）',
  scrolled.gapToInput !== null && scrolled.gapToInput > 0, scrolled);
await page.locator('.ask-colwrap').screenshot({ path: join(OUT, `${LABEL}-05-top-clearance.png`) });
await page.locator('.ask-dock').screenshot({ path: join(OUT, `${LABEL}-06-dock-full.png`) });

// ── 减法令（作者令 10-28 22:42 逐字）：单发送钮 + 精简消息面 ──
const recession = await page.evaluate(() => {
  const row = document.querySelector('.ask-input');
  const vis = (el) => { const c = getComputedStyle(el); return c.display !== 'none' && c.visibility !== 'hidden' && el.getBoundingClientRect().width > 0; };
  const btns = row ? [...row.querySelectorAll('button')] : [];
  const sels = ['.ask-ghost', '.ask-sess', '.ask-bub.sys'];
  const retired = {};
  for (const s of sels) retired[s] = document.querySelectorAll('.ask-dock ' + s).length;
  return {
    buttonsTotal: btns.length,
    buttonsVisible: btns.filter(vis).map((b) => b.className),
    handoffButton: [...document.querySelectorAll('.ask-dock button')].filter((b) => /handoff/i.test(b.className)).length,
    handoffStatusLine: document.querySelectorAll('.ask-dock [data-ask-handoff]').length,
    families: [...new Set([...document.querySelectorAll('.ask-dock .ask-bub')].flatMap((b) => ['user', 'ai', 'sys', 'peer', 'injected'].filter((k) => b.classList.contains(k))))],
    retired,
    dockH: Math.round(document.querySelector('.ask-dock').getBoundingClientRect().height),
  };
});
check('减法① 输入条右侧唯一可见发送钮（零第二箭头 / 零转交钮）',
  recession.buttonsVisible.length === 1 && /ask-send/.test(recession.buttonsVisible[0]) && recession.handoffButton === 0, recession);
check('减法② 问答面只出 user/ai 两族（ghost/徽标/sys 气泡零残留）',
  recession.families.every((f) => f === 'user' || f === 'ai') && recession.retired['.ask-ghost'] === 0 && recession.retired['.ask-sess'] === 0 && recession.retired['.ask-bub.sys'] === 0, recession);

// ── ② voice 块封存（默认档：<voice> 剥签渲染 + 零 TTS 请求） ──
const voice = await page.evaluate(async () => {
  const ttsCalls = [];
  const of = window.fetch;
  window.fetch = (...a) => { if (String(a[0]).includes('/api/tts')) ttsCalls.push(String(a[0])); return of(...a); };
  const utils = await import('/js/utils.js');
  const out = utils.renderMarkdownWithMath
    ? utils.renderMarkdownWithMath('语音测试 <voice>这句话不该进语音块</voice> 结束')
    : '';
  return { hasVoiceBlock: /voice-block|<voice/.test(out), text: out.replace(/<[^>]+>/g, '').trim().slice(0, 60), ttsCalls: ttsCalls.length };
});
check('② voice 块默认封存（<voice> 剥签为普通文本，内容无损）',
  !voice.hasVoiceBlock && voice.text.includes('这句话不该进语音块'), voice);

readings.shell = shell;
await browser.close();
server.close();
writeFileSync(join(OUT, 'readings.json'), JSON.stringify(readings, null, 2));
const failed = readings.checks.filter((c) => !c.pass);
console.log(`\n== ${LABEL}: ${readings.checks.length - failed.length} pass, ${failed.length} fail ==`);
console.log('OUT →', OUT);
process.exit(failed.length ? 1 : 0);

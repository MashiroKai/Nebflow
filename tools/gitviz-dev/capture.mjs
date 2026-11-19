// capture.mjs — gitviz MVP 真机证据采集（dev-only；Playwright + 本机 Chromium）。
// 跑法（playwright 由主检出的 node_modules 提供）：
//   NODE_PATH="/Users/kaiyu/Claude code/Stand/node_modules" node tools/gitviz-dev/capture.mjs
// 产出：.stand/tmp/gitviz-mvp-20261119/evidence/*.png|*.gif|*.json

import { chromium } from 'playwright';
import { mkdir, writeFile, readdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const WORKTREE = path.resolve(HERE, '../..');
// 证据落点 = 主检出的 .stand/tmp/<batch>/evidence（brief 指定；该路径在 .gitignore 内）
const OUT = process.env.GITVIZ_EVIDENCE_DIR || '/Users/kaiyu/Claude code/Stand/.stand/tmp/gitviz-mvp-20261119/evidence';
const BASE = process.env.GITVIZ_BASE || 'http://127.0.0.1:8791';

const CURSOR = `(() => {
  const d = document.createElement('div');
  d.style.cssText = 'position:fixed;z-index:2147483647;width:13px;height:13px;border-radius:50%;' +
    'background:rgba(91,127,191,.92);box-shadow:0 0 0 2px rgba(255,255,255,.9);pointer-events:none;' +
    'transform:translate(-50%,-50%);left:-40px;top:-40px;transition:none';
  document.body.appendChild(d);
  window.__devCursor = (x, y) => { d.style.left = x + 'px'; d.style.top = y + 'px'; };
})()`;

async function shot(page, name, frames, frames2) {
  const buf = await page.screenshot();
  await writeFile(path.join(OUT, name), buf);
  if (frames) frames.push(buf);
  if (frames2) frames2.push(buf);
  return buf;
}

async function center(page, sel, idx = 0) {
  // 先滚进视口（列表 53 张卡，stand 卡在视口外 ⇒ 直接取 box 会点到视口外的坐标）
  await page.evaluate(({ s, i }) => {
    const el = document.querySelectorAll(s)[i];
    if (el) el.scrollIntoView({ block: 'center', behavior: 'instant' });
  }, { s: sel, i: idx });
  await page.waitForTimeout(60);
  return page.evaluate(({ s, i }) => {
    const el = document.querySelectorAll(s)[i];
    if (!el) return null;
    const r = el.getBoundingClientRect();
    return { x: Math.round(r.left + Math.min(r.width / 2, 90)), y: Math.round(r.top + r.height / 2) };
  }, { s: sel, i: idx });
}

async function moveClick(page, sel, idx = 0) {
  const p = await center(page, sel, idx);
  if (!p) throw new Error(`no element for ${sel}[${idx}]`);
  await page.evaluate(({ x, y }) => window.__devCursor?.(x, y), p);
  await page.mouse.move(p.x, p.y);
  await page.mouse.click(p.x, p.y);
  return p;
}

async function framesDuring(page, ms, sink) {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) {
    sink.push(await page.screenshot());
  }
}

const report = { base: BASE, at: new Date().toISOString(), steps: [] };
// 只读审计：全量记录本页发出的每个请求（method + url），配 marks 切窗口给 analyze.mjs 判读
const reqs = [];
const mark = (n) => { report.marks = report.marks || {}; report.marks[n] = reqs.length; };

await mkdir(OUT, { recursive: true });
const browser = await chromium.launch();

// ══ 桌面 1440×900 暗档 ══════════════════════════════════════════════════════
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'dark', deviceScaleFactor: 2 });
const page = await ctx.newPage();
const consoleErrors = [];
page.on('pageerror', (e) => consoleErrors.push(String(e)));
page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
page.on('request', (r) => reqs.push({ m: r.method(), u: r.url(), t: Date.now() }));

await page.goto(BASE, { waitUntil: 'load' });
await page.waitForSelector('.project-card', { timeout: 15000 });
await page.evaluate(CURSOR);

// 真数据读数（与前端同源端点；载荷规模与口径一并留档）
// 与 UI 默认口径一致（12 个月 / 8 泳道 / 1200 提交上限），读数与截屏同源
const GRAPH_Q = 'heatDays=371&lanes=8&limit=1200';
mark('boot');
const graph = await page.evaluate(async (q) => (await fetch(`/api/projects/stand/git-graph?${q}`)).json(), GRAPH_Q);
report.graph = {
  state: graph.state, root: graph.root, lanes: graph.lanes, refsTotal: graph.refsTotal, refsShown: graph.refsShown,
  commitsTotal: graph.commitsTotal, commitsLimit: graph.commitsLimit, edgeCount: graph.edges.length,
  heatDays: graph.heatmap.days.length, heatTotal: graph.heatmap.total, heatMax: graph.heatmap.max,
  heatFrom: graph.heatmap.from, heatTo: graph.heatmap.to,
  payloadBytes: JSON.stringify(graph).length, serverMs: graph.serverMs, digest: graph.digest,
  head: graph.head,
  refs: graph.refs.map((r) => `${r.name} lane=${r.lane} ahead=${r.ahead} behind=${r.behind}`),
  firstCommit: graph.commits[0] && { short: graph.commits[0].short, date: graph.commits[0].date, subject: graph.commits[0].subject },
};
await page.evaluate((d) => { document.getElementById('dev-digest').textContent = `digest ${d}`; }, graph.digest);

// ① 列表态
await page.waitForTimeout(220);
const flipFrames = [];
await shot(page, '01-desktop-list-dark.png', flipFrames);
report.steps.push('01 列表态（真实 53 个项目行，取真 project.json）');

// ② 翻转（GIF 帧）：点 stand 卡卡头 → 次级面板
const cardIdx = await page.evaluate(() => [...document.querySelectorAll('.project-card')]
  .findIndex((c) => c.dataset.project === 'stand'));
if (cardIdx < 0) throw new Error('stand card not found');
const cardHeader = await center(page, '.project-card .team-card-title', cardIdx);
await page.evaluate(({ x, y }) => window.__devCursor(x, y), cardHeader);
await page.mouse.move(cardHeader.x, cardHeader.y);
flipFrames.push(await page.screenshot());
const clickPromise = page.mouse.click(cardHeader.x, cardHeader.y);
await framesDuring(page, 900, flipFrames);
await clickPromise;
await page.waitForSelector('.gitviz[data-state="ready"]', { timeout: 15000 });
await page.waitForTimeout(500);
await shot(page, '02-desktop-secondary-panel.png', flipFrames);
report.steps.push('02 翻转 → 次级面板（热力图 + 左侧树 + 右侧操控面板）');
mark('panelReady');

// ③ 选中项高亮（点第 4 行提交）
const flipGifFrames = flipFrames.slice();
await moveClick(page, '.gitviz-row', 3);
await page.waitForTimeout(240);
await shot(page, '03-desktop-select-commit.png');
report.steps.push('03 选中提交 → 行/节点/边高亮 + 右侧详情');

// ④ 多选 + 备注（勾 2 条分支 + 写两条备注；每次勾选后列表会重渲，故重新取元素）
await page.evaluate(() => document.querySelectorAll('.gitviz-ref-row input[type=checkbox]')[0]?.click());
await page.waitForTimeout(220);
await page.evaluate(() => document.querySelectorAll('.gitviz-ref-row input[type=checkbox]')[2]?.click());
await page.waitForTimeout(260);
await page.evaluate(() => {
  const tas = [...document.querySelectorAll('.gitviz-note')];
  if (tas[0]) { tas[0].value = '疑似漏合：先与 feat/base 预演 merge-tree，再决定落地方式'; tas[0].dispatchEvent(new Event('input', { bubbles: true })); }
  if (tas[1]) { tas[1].value = '分支膨胀候选：等价判定后建议清理'; tas[1].dispatchEvent(new Event('input', { bubbles: true })); }
});
await page.waitForTimeout(200);
await moveClick(page, '.gitviz-note', 0);
await page.waitForTimeout(200);
await shot(page, '04-desktop-multiselect-notes.png');
report.steps.push('04 多选两分支 + 逐项备注（纯前端状态，不发请求）');

// ⑤ 转发（只收集）
mark('beforeForward');
await moveClick(page, '.gitviz-btn', 0);
await page.waitForTimeout(400);
await shot(page, '05-desktop-forward.png');
const forwardLine = await page.evaluate(() => document.querySelector('.gitviz-note-io')?.textContent || '');
mark('afterForward');
report.steps.push(`05 转发给主会话（只收集）：${forwardLine}`);

// ── GIF ①：翻转 ──────────────────────────────────────────────────────────
await page.evaluate(() => window.__gitvizHarness?.back?.());
await page.waitForTimeout(320);
const replay = [];
replay.push(await page.screenshot());
const cardHeader2 = await center(page, '.project-card .team-card-title', cardIdx);
await page.evaluate(({ x, y }) => window.__devCursor(x, y), cardHeader2);
await page.mouse.move(cardHeader2.x, cardHeader2.y);
replay.push(await page.screenshot());
await page.mouse.click(cardHeader2.x, cardHeader2.y);
await framesDuring(page, 700, replay);
await page.waitForSelector('.gitviz[data-state="ready"]', { timeout: 15000 });
await page.waitForTimeout(450);
replay.push(await page.screenshot());
report.gif1Frames = replay.length;

// ── GIF ②：多选交互 ──────────────────────────────────────────────────────
const gif2 = [];
gif2.push(await page.screenshot());
await moveClick(page, '.gitviz-row', 1);
await page.waitForTimeout(400);
gif2.push(await page.screenshot());
await moveClick(page, '.gitviz-row', 5);
await page.waitForTimeout(400);
gif2.push(await page.screenshot());
await moveClick(page, '.gitviz-ref-row input[type=checkbox]', 1);
await page.waitForTimeout(400);
gif2.push(await page.screenshot());
await moveClick(page, '.gitviz-ref-row input[type=checkbox]', 4);
await page.waitForTimeout(400);
gif2.push(await page.screenshot());
await page.evaluate(() => {
  const tas = [...document.querySelectorAll('.gitviz-note')];
  if (tas[0]) { tas[0].value = '预演后落地'; tas[0].dispatchEvent(new Event('input', { bubbles: true })); }
});
await page.waitForTimeout(300);
gif2.push(await page.screenshot());
await moveClick(page, '.gitviz-btn', 0);
await page.waitForTimeout(500);
gif2.push(await page.screenshot());
report.gif2Frames = gif2.length;

report.requests = reqs;
report.consoleErrors = consoleErrors;
writeFile(path.join(OUT, '_gif-frames.json'), JSON.stringify({ n: replay.length + gif2.length }));

// 暂存帧（GIF 由 assemble-gif.mjs 用 PIL 组装）
for (let i = 0; i < replay.length; i++) await writeFile(path.join(OUT, `.frames-flip-${String(i).padStart(3, '0')}.png`), replay[i]);
for (let i = 0; i < gif2.length; i++) await writeFile(path.join(OUT, `.frames-multi-${String(i).padStart(3, '0')}.png`), gif2[i]);

await ctx.close();

// ══ 手机 375×812 暗档（全屏页）══════════════════════════════════════════════
const mctx = await browser.newContext({
  viewport: { width: 375, height: 812 }, colorScheme: 'dark', deviceScaleFactor: 3,
  isMobile: true, hasTouch: true, userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1',
});
const mpage = await mctx.newPage();
mpage.on('pageerror', (e) => consoleErrors.push(`mobile: ${e}`));
await mpage.goto(`${BASE}/?mobile=1&project=stand`, { waitUntil: 'load' });
await mpage.waitForSelector('.gitviz[data-state="ready"]', { timeout: 20000 });
await mpage.waitForTimeout(600);
await writeFile(path.join(OUT, '06-mobile-fullscreen-top.png'), await mpage.screenshot());
await mpage.evaluate(() => { const m = document.querySelector('.gitviz-main'); if (m) m.scrollTop = m.scrollHeight; });
await mpage.waitForTimeout(300);
await writeFile(path.join(OUT, '07-mobile-fullscreen-controls.png'), await mpage.screenshot());
// 手机端列表态（全屏）
await mpage.goto(`${BASE}/?mobile=1`, { waitUntil: 'load' });
await mpage.waitForSelector('.project-card', { timeout: 15000 });
await mpage.waitForTimeout(250);
await writeFile(path.join(OUT, '08-mobile-list-fullscreen.png'), await mpage.screenshot());
report.steps.push('06/07/08 手机 375×812 全屏暗档（面板上/下 + 列表）');
report.mobileViewport = '375x812 @3x, isMobile';
report.requests = reqs;
report.consoleErrors = consoleErrors;
await mctx.close();
await browser.close();

await writeFile(path.join(OUT, 'capture-report.json'), JSON.stringify(report, null, 2));
const files = (await readdir(OUT)).filter((f) => !f.startsWith('.'));
console.log(JSON.stringify({ ok: true, files, report: { graph: report.graph, consoleErrors } }, null, 2));

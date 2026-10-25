// capsule-hover-chain.spec.mjs — 胶囊 hover 滚动连续性批（作者报障 2026-10-25 10:4x）spec 钉
//
// 作者报障逐字：「胶囊的交互也有问题，就是hover的效果应该是只要鼠标在胶囊上，
// 就应该一直能上下滚动，现在是如果滚动，切换了其他卡片，那么就无法再次滚动，
// 需要重新hover触发。」
//
// 根因（canvas.js initCapsuleRing）：canvas-tab-switched → closeRing() 无条件
// 收环，滚轮监听随环拆走；而指针未离头时 pointerenter 不会再触发 ⇒ 换签后
// 滚动链死断，必须移出再 hover。修复：指针仍在 hover 区（openPt ∪ 标题头 ∪
// 非弧外胶囊联合盒）的换签 = 环保持开 + 游标跳新激活页签；离区换签收环腿保留。
//
// 验收标准（工单逐字）：光标不离开胶囊边界的前提下，连续滚动 N 次切换 N 张
// 卡片，零失效。本 spec 钉 N=5 链 + 缘位点击腿 + deck 让位腿。
//
// 运行：node tests/capsule-hover-chain.spec.mjs
// （boot 同 canvas-capsule-ring.spec.mjs：隔离静态服务器 + workspace-open-item
//   直灌文件卡 + __canvasDeck.state() 探针；永不触碰宿主 8080）

import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8189);
const BASE = `http://127.0.0.1:${PORT}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let pass = 0, fail = 0;
function ok(name, cond, detail = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  if (cond) pass++; else fail++;
}

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2', '.woff': 'font/woff',
};
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try { const b = readFileSync(file); res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' }); res.end(b); }
  catch { if (!res.headersSent) { res.writeHead(404); res.end(); } else res.end(); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));
const browser = await chromium.launch();

const FILES = ['alpha', 'bravo', 'charlie', 'delta', 'echo'];
const FILE_CONTENTS = new Map(FILES.map((f) => [`/tmp/ch-fixture/${f}.md`, { content: `# ${f}\n\n${f} body\n`, itemType: 'markdown' }]));

async function boot() {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'dark' });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  await ctx.route('**/api/**', (r) => r.fulfill({ status: 404, json: {} }));
  await installTicketMock(ctx);
  await ctx.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      else if (m.type === 'pop.readFile') {
        const rec = FILE_CONTENTS.get(m.path);
        ws.send(JSON.stringify({
          type: 'fileContent', path: m.path, absPath: m.path,
          content: rec ? rec.content : '', itemType: rec ? rec.itemType : 'markdown',
          fileName: (m.path || '').split('/').pop(), size: rec ? rec.content.length : 0,
        }));
      }
    });
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('body.mobile-layer, #activity-bar', { timeout: 15000 });
  await sleep(500);
  return { ctx, page, errors };
}

async function openFile(page, absPath) {
  const rec = FILE_CONTENTS.get(absPath);
  const title = absPath.split('/').pop();
  await page.evaluate(({ absPath, title, content, itemType }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType, title, content, absPath, pinned: true },
    }));
  }, { absPath, title, content: rec.content, itemType: rec.itemType });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${absPath}"]`, { timeout: 10000, state: 'attached' });
}

const stateOf = (page) => page.evaluate(() => window.__canvasDeck.state());

/** 胶囊（标题头）中心点。 */
async function capsulePoint(page) {
  return page.evaluate(() => {
    const hdr = document.querySelector('#canvas-panel .canvas-header');
    const r = hdr.getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  });
}
async function hoverCapsule(page) {
  const p = await capsulePoint(page);
  await page.mouse.move(p.x, p.y);
  await sleep(520);                                 // pointerenter 开环 + 展开过渡走完
}
const ringSnap = (page) => page.evaluate(() => ({
  open: document.querySelector('#canvas-panel .canvas-header').classList.contains('ring-open'),
  n: document.querySelectorAll('#canvas-capsule-ring .cr-capsule').length,
  center: document.querySelector('#canvas-capsule-ring .cr-capsule.is-center')?.dataset.tabId || null,
}));

/* ════════ AC1：滚动+切换 5 连链，滚轮零失效（工单验收标准）════════ */
{
  const { ctx, page, errors } = await boot();
  for (const f of FILES.slice(0, 4)) await openFile(page, `/tmp/ch-fixture/${f}.md`);
  await page.evaluate(() => document.querySelector('.canvas-tab[data-tab-id="plugins"] .canvas-tab-close')?.click());
  await sleep(400);
  const s0 = await stateOf(page);
  ok('AC0 前置：四文件页签开（echo 不开；plugins 关）',
    s0.tabCount === 4 && s0.activeId === 'file:/tmp/ch-fixture/delta.md', `n=${s0.tabCount} active=${s0.activeId}`);

  await hoverCapsule(page);
  let r = await ringSnap(page);
  ok('AC0b hover 开环', r.open && r.n === 4, `open=${r.open} n=${r.n}`);

  // 光标全程不离开胶囊点：滚动（换选）→ 点击中心（切换）为一轮，重复 5 轮。
  // 旧实现第 1 轮点击后环收 + pointerenter 不再触发 ⇒ 第 2 轮滚动即死（作者报障）。
  const cp = await capsulePoint(page);
  const switched = [];
  let chainAlive = true;
  for (let i = 1; i <= 5; i++) {
    await page.mouse.wheel(0, 100);                 // 纵滚一格 = 游标换选
    await sleep(240);
    const w = await ringSnap(page);
    if (!w.open || w.n !== 4 || !w.center) { chainAlive = false; ok(`AC${i}-a 第${i}轮滚轮`, false, `open=${w.open} n=${w.n}`); break; }
    await page.mouse.click(cp.x, cp.y);             // 中心位点击 = 切换（落穿标题头手势链）
    await sleep(320);
    const s = await stateOf(page);
    const after = await ringSnap(page);
    switched.push(s.activeId);
    const roundOk = s.activeId === w.center && after.open && after.n === 4 && after.center === s.activeId;
    if (!roundOk) {
      chainAlive = false;
      ok(`AC${i}-b 第${i}轮切换+环连续`, false, `active=${s.activeId} wheelCenter=${w.center} open=${after.open} center=${after.center}`);
      break;
    }
  }
  ok('AC1 连续 5 轮「滚动→切换」全链零失效（环全程开、游标/激活逐轮易主）',
    chainAlive && switched.length === 5, `switched=${switched.length} [${switched.map((x) => x.split('/').pop()).join(' → ')}]`);

  // AC2 收尾钉：链走完后滚轮 STILL 活着（旧实现此处就是「无法再次滚动」现场）
  await page.mouse.wheel(0, -100);
  await sleep(240);
  const tail = await ringSnap(page);
  ok('AC2 链尾滚轮仍换选（=报障现场回归钉）', tail.open && !!tail.center, `open=${tail.open} center=${tail.center}`);

  ok('AC-R0 主文档零 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ AC3：缘位点击腿 + AC4 deck 让位腿 ════════ */
{
  const { ctx, page, errors } = await boot();
  for (const f of FILES.slice(0, 4)) await openFile(page, `/tmp/ch-fixture/${f}.md`);
  await page.evaluate(() => document.querySelector('.canvas-tab[data-tab-id="plugins"] .canvas-tab-close')?.click());
  await sleep(400);

  await hoverCapsule(page);
  // 缘位胶囊（面板内完整 rect 的一条）点击 → 切换 + 环保持开（指针在胶囊上）
  const victim = await page.evaluate(() => {
    const caps = [...document.querySelectorAll('#canvas-capsule-ring .cr-capsule')];
    const active = window.__canvasDeck.state().activeId;
    const p = document.getElementById('canvas-panel').getBoundingClientRect();
    const inside = (c) => { const r = c.getBoundingClientRect(); return r.top >= p.top + 1 && r.bottom <= p.bottom - 1; };
    const other = caps.find((c) => c.dataset.tabId !== active && inside(c)) || caps.find((c) => c.dataset.tabId !== active);
    if (!other) return null;
    const r = other.getBoundingClientRect();
    return { id: other.dataset.tabId, x: r.left + r.width / 2, y: r.top + r.height / 2 };
  });
  if (victim) {
    await page.mouse.click(victim.x, victim.y);
    await sleep(350);
    const sV = await stateOf(page);
    const rV = await ringSnap(page);
    ok('AC3 缘位点击 = 切换 + 环保持开 + 游标跳新激活（滚动链不掉）',
      sV.activeId === victim.id && rV.open && rV.n === 4 && rV.center === victim.id,
      `active=${sV.activeId} open=${rV.open} center=${rV.center}`);
    await page.mouse.wheel(0, 100);
    await sleep(240);
    const w3 = await ringSnap(page);
    ok('AC3b 缘位点击后滚轮仍换选', w3.open && w3.center !== victim.id, `open=${w3.open} center=${w3.center}`);
  } else {
    ok('AC3 缘位点击腿', false, 'no victim capsule');
  }

  // AC4 deck 让位：环开态右键 → 多开（deck 开）→ 环滚轮让位收环（deck 态不开环）
  const cp4 = await capsulePoint(page);
  await page.mouse.click(cp4.x, cp4.y, { button: 'right' });
  await sleep(250);
  await page.evaluate(() => {
    const b = [...document.querySelectorAll('.canvas-tab-context-menu button')].find((x) => x.textContent === '多开');
    if (b) b.click();
  });
  await sleep(800);
  const sDeck = await stateOf(page);
  await page.mouse.wheel(0, 100);
  await sleep(300);
  const rDeck = await ringSnap(page);
  ok('AC4 deck 开启后环滚轮让位（deck 态不开环；滚轮归叠卡擦洗）',
    sDeck.open === true && rDeck.open === false,
    `deckOpen=${sDeck.open} ringOpen=${rDeck.open}`);
  // 收场：收起叠卡，还原面板
  await page.evaluate(() => {
    const b = document.getElementById('deck-fab');
    if (b) b.click();
  });
  await sleep(700);

  ok('AC-R0b 主文档零 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

await browser.close();
server.close();
console.log(`\ncapsule-hover-chain: ${pass} PASS / ${fail} FAIL`);
process.exit(fail ? 1 : 0);

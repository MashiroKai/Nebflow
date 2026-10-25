// capsule-size-uniform.spec.mjs — 胶囊尺寸跨面板一致 + 显示不全修复批 spec 钉
// （作者报障 2026-10-25 10:4x：「而且所有面板的胶囊大小应该保持一致，现在是我们
//   内部的面板明显小一圈，而且还有无法完全显示的bug」）
//
// 根因两处（split.css）：
//   C-① 高度随子件浮沉：body:not(.mobile-layer) .canvas-header { height:auto }
//      —— 文件页签有 canvas-pop-btn（26px）撑到 36，功能性面板页签（图谱/插件/
//      任务列表/设置，pop 钮 hidden）塌到 27 = 面板间「明显小一圈」。
//      修复：height 固定 36px（单一来源 = 该规则；全局 border-box 含 padding/
//      border）；卡窗配套 padding-top 8→4（旧 40px 形态遗产）。
//   C-② 循环百分比钳制截断一切标题：.canvas-title { max-width:62% } 是全宽
//      标题条时代（b5d507737）的遗产 —— UI 批⑨ 胶囊化后父级 fit-content，
//      子级百分比 max-width 成循环约束，Chrome 收敛在「自然宽 ×~0.62」定点
//      ⇒ 充裕空间下也截（实测「插件」6.9/24、「文档图谱」21.8/48、
//      short.md 44.3/52 全截）。修复：max-width:none（deck-narrow 46% 同改），
//      显示上限交还父级真实几何 cap（min(76%, max(220px, 100%-368px))，
//      对 #canvas-slide 非循环）；.t-name ellipsis 只在真到缘时兜底。
//
// 锁面：
//   C1 跨面板同高：文件卡 / 插件面板 / 图谱面板 活跃态胶囊高度全等（36px）；
//   C2 短标题零截断：插件 / 文档图谱 / short.md 三类标题全显示（ellipsis 未触发）；
//   C3 长文件名按设计省略号截断且不溢出：胶囊宽 ≤ 面板 cap、右缘不出面板；
//   C4 pop 钮有/无不影响高度（高度与子件解耦 = 单一来源语义钉）；
//   C5 卡窗同高 36 + 标题全显示（所有面板口径含独立卡窗）；
//   C6 环开态胶囊高度不变（hover 态与常态同尺寸 —— 承 CR2-7 恒定钉的面板页签腿）。
//
// 运行：node tests/capsule-size-uniform.spec.mjs
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
const PORT = Number(process.env.PORT || 8199);
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

const LONG_NAME = 'annual-performance-review-2026-final.md';
const FILE_CONTENTS = new Map([
  ['/tmp/cu-fixture/long.md', { content: '# x\n\nbody\n', itemType: 'markdown', name: LONG_NAME }],
  ['/tmp/cu-fixture/short.md', { content: '# x\n\nbody\n', itemType: 'markdown', name: 'short.md' }],
]);

function makeServerWsMocks(ctx) {
  return ctx.routeWebSocket(/\/ws/, (ws) => {
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
          fileName: rec && rec.name ? rec.name : (m.path || '').split('/').pop(), size: 10,
        }));
      }
    });
  });
}

async function boot({ cardWindow = false, path = '' } = {}) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'dark' });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  await ctx.route('**/api/**', (r) => r.fulfill({ status: 404, json: {} }));
  await installTicketMock(ctx);
  makeServerWsMocks(ctx);
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  await page.goto(BASE + '/index.html' + (cardWindow ? '?card-window=1' + (path ? '&path=' + encodeURIComponent(path) : '') : ''));
  if (cardWindow) {
    await page.waitForSelector('body.card-window', { timeout: 15000 });
    await page.waitForSelector('#canvas-panel .canvas-header', { timeout: 15000 }).catch(() => {});
  } else {
    await page.waitForSelector('body.mobile-layer, #activity-bar', { timeout: 15000 });
  }
  await sleep(700);
  return { ctx, page, errors };
}

async function openFile(page, absPath, title) {
  await page.evaluate(({ absPath, title }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType: 'markdown', title, content: '# x\n\nbody\n', absPath, pinned: true },
    }));
  }, { absPath, title });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${absPath}"]`, { timeout: 10000, state: 'attached' });
  await sleep(350);
}

const measure = (page) => page.evaluate(() => {
  const hdr = document.querySelector('#canvas-panel .canvas-header');
  const name = document.getElementById('canvas-title-name');
  const panel = document.getElementById('canvas-panel');
  const r = hdr.getBoundingClientRect();
  const pr = panel.getBoundingClientRect();
  const pop = document.getElementById('canvas-pop-btn');
  return {
    h: +r.height.toFixed(1),
    w: +r.width.toFixed(1),
    text: name.textContent,
    truncated: name.clientWidth < name.scrollWidth,
    popHidden: !pop || pop.hidden,
    cap: Math.min(pr.width * 0.76, Math.max(220, pr.width - 368)),
    panelRight: pr.right,
    hdrRight: r.right,
  };
});
const activate = async (page, tabId) => { await page.evaluate((id) => document.querySelector(`.canvas-tab[data-tab-id="${id}"]`)?.click(), tabId); await sleep(350); };

/* ════════ C1/C2/C3/C4/C6：主窗跨面板 ════════ */
{
  const { ctx, page, errors } = await boot();
  await openFile(page, '/tmp/cu-fixture/long.md', LONG_NAME);
  await openFile(page, '/tmp/cu-fixture/short.md', 'short.md');
  const mFile = await measure(page);       // short.md（pop 钮在场：文件卡）— 先量，图谱打开即换签
  // 前置两文件页签 + 两个功能面板（plugins 常驻页签 + 图谱）
  await page.click('#wiki-btn');
  await sleep(1200);
  await activate(page, 'plugins');
  const mPlugins = await measure(page);    // 插件（pop 钮 hidden：功能面板）
  await activate(page, 'wiki-graph');
  const mGraph = await measure(page);      // 文档图谱（pop 钮 hidden：功能面板）

  ok('C0 前置：三面标题在位', mFile.text === 'short.md' && mPlugins.text === '插件' && mGraph.text === '文档图谱',
    `${mFile.text}/${mPlugins.text}/${mGraph.text}`);

  ok('C1a 跨面板同高（文件卡 vs 插件面板）', Math.abs(mFile.h - mPlugins.h) <= 0.5, `file=${mFile.h} plugins=${mPlugins.h}`);
  ok('C1b 跨面板同高（文件卡 vs 图谱面板）', Math.abs(mFile.h - mGraph.h) <= 0.5, `file=${mFile.h} graph=${mGraph.h}`);
  ok('C1c 面板页签不再小一圈：高度回到文件卡档 36px',
    mPlugins.h === 36 && mGraph.h === 36 && mFile.h === 36, `plugins=${mPlugins.h} graph=${mGraph.h} file=${mFile.h}`);

  ok('C2a 短标题零截断：short.md 全显示', mFile.truncated === false, `${mFile.text} w=${mFile.w}`);
  ok('C2b 短标题零截断：插件 全显示（修前 6.9px/24px 自然宽）', mPlugins.truncated === false, `w=${mPlugins.w}`);
  ok('C2c 短标题零截断：文档图谱 全显示（修前 21.8px/48px 自然宽）', mGraph.truncated === false, `w=${mGraph.w}`);

  ok('C4 高度与 pop 钮解耦：文件卡 pop 在场、面板 pop hidden，高度仍全等',
    mFile.popHidden === false && mPlugins.popHidden === true && mGraph.popHidden === true
    && mFile.h === mPlugins.h && mFile.h === mGraph.h,
    `popHidden file=${mFile.popHidden} plugins=${mPlugins.popHidden} graph=${mGraph.popHidden}`);

  // C3 长文件名：按设计省略号截断且不溢出（面板 cap 内、右缘不出面板）
  await activate(page, 'file:/tmp/cu-fixture/long.md');
  const mLong = await measure(page);
  ok('C3a 长文件名截断受面板 cap 约束（省略号 = 设计兜底）', mLong.truncated === true && mLong.w <= mLong.cap + 1,
    `w=${mLong.w} cap=${mLong.cap.toFixed(1)}`);
  ok('C3b 长文件名胶囊不溢出面板（右缘 ≤ 面板右缘）', mLong.hdrRight <= mLong.panelRight + 0.5,
    `hdrRight=${mLong.hdrRight.toFixed(1)} panelRight=${mLong.panelRight.toFixed(1)}`);
  ok('C3c 长名可见宽高于修前（62% 循环钳退役）：≥140px（修前 102.4px）', mLong.w >= 180,
    `w=${mLong.w}`);

  // C6 环开态：胶囊高度不变（面板页签腿的尺寸恒定钉）
  await activate(page, 'wiki-graph');
  const cp = await page.evaluate(() => {
    const r = document.querySelector('#canvas-panel .canvas-header').getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  });
  await page.mouse.move(cp.x, cp.y);
  await sleep(560);
  const mRingOpen = await measure(page);
  const ringOpenPinned = await page.evaluate(() => document.querySelector('#canvas-panel .canvas-header').classList.contains('ring-open'));
  ok('C6 环开态高度不变（面板页签胶囊 hover 态 = 常态 36px）',
    ringOpenPinned && mRingOpen.h === 36, `open=${ringOpenPinned} h=${mRingOpen.h}`);
  await page.mouse.move(cp.x, cp.y + 320);
  await sleep(400);

  ok('C-R0 主文档零 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ C5：独立卡窗同口径 ════════ */
{
  const { ctx, page, errors } = await boot({ cardWindow: true, path: '/tmp/cu-fixture/short.md' });
  await sleep(1200);
  const mWin = await measure(page);
  ok('C5a 卡窗胶囊同高 36px（修前 40px 形态）', mWin.h === 36, `h=${mWin.h}`);
  ok('C5b 卡窗标题全显示', mWin.truncated === false, `text=${mWin.text}`);
  ok('C-R0b 卡窗零 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

await browser.close();
server.close();
console.log(`\ncapsule-size-uniform: ${pass} PASS / ${fail} FAIL`);
process.exit(fail ? 1 : 0);

// capsule-size-uniform.spec.mjs — 胶囊尺寸跨面板一致 + 显示不全修复批 spec 钉
// （作者报障 2026-10-25 10:4x：「而且所有面板的胶囊大小应该保持一致，现在是我们
//   内部的面板明显小一圈，而且还有无法完全显示的bug」）
// ＋ 固定尺寸批扩展（作者令 2026-10-25 13:18 逐字：「现在每个胶囊的大小还是
//   不是固定的呀，还是会根据内容自适应，我要保持一个固定的大小。」）
//
// 根因两处（split.css）：
//   C-① 高度随子件浮沉：body:not(.mobile-layer) .canvas-header { height:auto }
//      —— 文件页签有 canvas-pop-btn（26px）撑到 36，功能性面板页签（图谱/插件/
//      任务列表/设置，pop 钮 hidden）塌到 27 = 面板间「明显小一圈」。
//      修复：height 固定 36px；固定尺寸批升格单一来源 = sapphire.css
//      --capsule-h token（36px 值不变）。
//   C-② 循环百分比钳制截断一切标题：.canvas-title { max-width:62% } 是全宽
//      标题条时代（b5d507737）的遗产 —— UI 批⑨ 胶囊化后父级 fit-content，
//      子级百分比 max-width 成循环约束，Chrome 收敛在「自然宽 ×~0.62」定点
//      ⇒ 充裕空间下也截（实测「插件」6.9/24、「文档图谱」21.8/48、
//      short.md 44.3/52 全截）。修复：max-width:none（deck-narrow 46% 同改），
//      显示上限交还父级真实几何 cap。
//   C-③（固定尺寸批）宽度随内容自适应：width:fit-content（UI 批⑨ 胶囊化遗产）
//      —— 作者令固定几何。修复：width 固定 = sapphire.css --capsule-w 180px
//      token（标题头 .canvas-header 与 hover 环 .cr-capsule 两脸同一 token）；
//      超长内容 .t-name / .cr-name ellipsis 截断兜底（绝不溢出、绝不撑开几何）；
//      窄面板极值下既有 max-width cap 护栏保留（标准面板永不触发）。
//
// 锁面：
//   C1 跨面板同高：文件卡 / 插件面板 / 图谱面板 活跃态胶囊高度全等（36px）；
//   C2 短标题零截断：插件 / 文档图谱 / short.md 三类标题全显示（ellipsis 未触发）；
//   C3 长文件名按设计省略号截断且不溢出：胶囊宽 ≤ 面板 cap、右缘不出面板；
//   C4 pop 钮有/无不影响高度（高度与子件解耦 = 单一来源语义钉）；
//   C5 卡窗同高 36 + 标题全显示（所有面板口径含独立卡窗）；
//   C6 环开态胶囊高度不变（hover 态与常态同尺寸 —— 承 CR2-7 恒定钉的面板页签腿）；
//   F1 跨面板同宽：三面胶囊宽度全等 = --capsule-w 180px（同高 36 已由 C1 钉）；
//   F2 几何不随内容变：短名 / 长名两态胶囊盒逐像素同宽同高（固定 = 常量）；
//   F3 超长注入标题（200+ 字符）不变形：盒恒 180×36 + ellipsis 生效 + 右缘不出面板；
//   F4 环胶囊固定几何：hover 环全胶囊（中心+缘位）180×36 与主胶囊同 token 档；
//   F5 卡窗固定几何 180×36；
//   F6 token 单源静态钉：--capsule-h/--capsule-w 声明唯一（sapphire.css），
//      两胶囊脸消费 var()（split.css），无第二字面量来源。
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
// 固定尺寸批 F3：超长注入标题（200+ 字符，远超 180px 盒承载）—— 盒恒定 + ellipsis
const XL_NAME = '超长标题注入回归-' + 'x'.repeat(200) + '-fixed-size.md';
const FILE_CONTENTS = new Map([
  ['/tmp/cu-fixture/long.md', { content: '# x\n\nbody\n', itemType: 'markdown', name: LONG_NAME }],
  ['/tmp/cu-fixture/short.md', { content: '# x\n\nbody\n', itemType: 'markdown', name: 'short.md' }],
  ['/tmp/cu-fixture/xl.md', { content: '# x\n\nbody\n', itemType: 'markdown', name: XL_NAME }],
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

async function boot({ cardWindow = false, path = '', colorScheme = 'dark' } = {}) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme });
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
  ok('C3c 长名可见宽高于修前（62% 循环钳退役）：固定盒 180px（修前 102.4px）', mLong.w >= 180,
    `w=${mLong.w}`);

  /* ── F 固定尺寸批：胶囊固定几何（作者令 2026-10-25 13:18）── */
  // F1 跨面板同宽：三面胶囊宽度全等 = token 档 180px（同高已由 C1 钉）
  const tokW = 180, tokH = 36;
  ok('F1 跨面板同宽（文件卡 vs 插件 vs 图谱）= --capsule-w 180px',
    Math.abs(mFile.w - mPlugins.w) <= 0.5 && Math.abs(mFile.w - mGraph.w) <= 0.5
    && mFile.w === tokW && mPlugins.w === tokW && mGraph.w === tokW,
    `file=${mFile.w} plugins=${mPlugins.w} graph=${mGraph.w}`);

  // F2 几何不随内容变：短名（short.md）与长名两态盒逐像素同宽同高
  ok('F2 胶囊盒不随内容变（short 52px 文本 vs 长名 250px 文本同盒）',
    Math.abs(mFile.w - mLong.w) <= 0.5 && Math.abs(mFile.h - mLong.h) <= 0.5
    && mLong.w === tokW && mLong.h === tokH,
    `short=${mFile.w.toFixed(1)}x${mFile.h.toFixed(1)} long=${mLong.w.toFixed(1)}x${mLong.h.toFixed(1)}`);

  // F3 超长注入标题（200+ 字符）：盒恒 180×36 + ellipsis 生效 + 右缘不出面板
  await openFile(page, '/tmp/cu-fixture/xl.md', XL_NAME);
  const mXL = await measure(page);
  ok('F3a 超长标题（' + XL_NAME.length + ' 字符）盒恒定 180×36 不变形',
    mXL.w === tokW && mXL.h === tokH, `w=${mXL.w} h=${mXL.h}`);
  ok('F3b 超长标题 ellipsis 生效（clientWidth < scrollWidth）', mXL.truncated === true,
    `client=${mXL.w}`);
  ok('F3c 超长标题右缘不出面板（绝不溢出）', mXL.hdrRight <= mXL.panelRight + 0.5,
    `hdrRight=${mXL.hdrRight.toFixed(1)} panelRight=${mXL.panelRight.toFixed(1)}`);
  ok('F3d 超长标题全文保留在 DOM（截断是视觉层，非数据丢失）',
    mXL.text === XL_NAME, `len=${mXL.text.length}`);
  await page.screenshot({
    path: join(HERE, '../evidence/20261025_capsule-fixed-size/shots/1-xl-title-fixed-dark.png'),
    clip: await page.evaluate(() => {
      const p = document.getElementById('canvas-panel').getBoundingClientRect();
      return { x: Math.max(0, p.left), y: Math.max(0, p.top), width: p.width, height: 120 };
    }),
  });

  // C6 环开态：胶囊高度不变（面板页签腿的尺寸恒定钉）＋ F4 环胶囊固定几何
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

  // F4 环胶囊固定几何：hover 环全胶囊（中心+缘位）180×36，与主胶囊同 token 档
  // （offsetWidth/Height = 布局盒，对 rotateX/translateZ/scale 变换免疫 ——
  //   缘位 cover-flow 的投影盒不是几何本体，canvas-stack.spec 同口径）
  const ring = await page.evaluate(() => {
    const hdr = document.querySelector('#canvas-panel .canvas-header');
    const caps = [...document.querySelectorAll('#canvas-capsule-ring .cr-capsule')]
      .map((c) => ({ w: c.offsetWidth, h: c.offsetHeight, cls: c.className }));
    return { hdrW: hdr.offsetWidth, hdrH: hdr.offsetHeight, caps, n: caps.length };
  });
  ok('F4a 环开且胶囊挂载（前置）', ring.n >= 3, `n=${ring.n}`);
  ok('F4b 环全胶囊固定 180×36（中心+缘位同 token 档）',
    ring.caps.every((c) => c.w === tokW && c.h === tokH),
    ring.caps.map((c) => `${c.w}x${c.h}`).join(' '));
  ok('F4c 中心环胶囊与标题头胶囊同盒（同位同尺寸无缝 morph 承诺）',
    Math.abs(ring.hdrW - tokW) <= 0.5 && Math.abs(ring.hdrH - tokH) <= 0.5,
    `hdr=${ring.hdrW}x${ring.hdrH}`);
  await page.screenshot({
    path: join(HERE, '../evidence/20261025_capsule-fixed-size/shots/2-ring-open-36-dark.png'),
    clip: await page.evaluate(() => {
      const p = document.getElementById('canvas-panel').getBoundingClientRect();
      return { x: Math.max(0, p.left), y: Math.max(0, p.top - 56), width: p.width, height: 260 };
    }),
  });
  await page.mouse.move(cp.x, cp.y + 320);
  await sleep(400);

  ok('C-R0 主文档零 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ C5/F5：独立卡窗同口径 ════════ */
{
  const { ctx, page, errors } = await boot({ cardWindow: true, path: '/tmp/cu-fixture/short.md' });
  await sleep(1200);
  const mWin = await measure(page);
  ok('C5a 卡窗胶囊同高 36px（修前 40px 形态）', mWin.h === 36, `h=${mWin.h}`);
  ok('C5b 卡窗标题全显示', mWin.truncated === false, `text=${mWin.text}`);
  ok('F5 卡窗固定几何 180×36（与主窗同 token 档）', mWin.w === 180 && mWin.h === 36,
    `${mWin.w}x${mWin.h}`);
  ok('C-R0b 卡窗零 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ F3 亮色腿：超长标题固定几何跨主题一致 + 证据截图 ════════ */
{
  const { ctx, page, errors } = await boot({ colorScheme: 'light' });
  await openFile(page, '/tmp/cu-fixture/xl.md', XL_NAME);
  const mXLL = await measure(page);
  ok('F3-亮 超长标题亮色主题同盒 180×36（几何跨主题恒定）',
    mXLL.w === 180 && mXLL.h === 36 && mXLL.truncated === true, `${mXLL.w}x${mXLL.h}`);
  await page.screenshot({
    path: join(HERE, '../evidence/20261025_capsule-fixed-size/shots/3-xl-title-fixed-light.png'),
    clip: await page.evaluate(() => {
      const p = document.getElementById('canvas-panel').getBoundingClientRect();
      return { x: Math.max(0, p.left), y: Math.max(0, p.top), width: p.width, height: 120 };
    }),
  });
  ok('R0c 亮色腿零 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ F6：token 单源静态钉（文件级，零浏览器）════════ */
{
  const sap = readFileSync(join(HERE, '../src/main/resources/web/css/sapphire.css'), 'utf8');
  const split = readFileSync(join(HERE, '../src/main/resources/web/css/split.css'), 'utf8');
  ok('F6a --capsule-h/--capsule-w 声明唯一（sapphire.css 各恰 1 处）',
    (sap.match(/--capsule-h:\s*36px/g) || []).length === 1
    && (sap.match(/--capsule-w:\s*180px/g) || []).length === 1,
    `h=${(sap.match(/--capsule-h:\s*36px/g) || []).length} w=${(sap.match(/--capsule-w:\s*180px/g) || []).length}`);
  ok('F6b 两胶囊脸消费 token（split.css：标题头 width + .cr-capsule width/max-width 护栏共 3 处 var() 引用，无第二字面量档）',
    (split.match(/var\(--capsule-w/g) || []).length === 3
    && (split.match(/var\(--capsule-h/g) || []).length === 2
    && !/\.cr-capsule\s*\{[^}]*width:\s*\d+px/.test(split),
    `w-ref=${(split.match(/var\(--capsule-w/g) || []).length} h-ref=${(split.match(/var\(--capsule-h/g) || []).length}`);
  ok('F6c 标题头固定宽不再有 fit-content 桌面档（body:not(.mobile-layer) 规则内）',
    !/body:not\(\.mobile-layer\) \.canvas-header\s*\{[^}]*fit-content/.test(split),
    'desktop rule clean');
}

await browser.close();
server.close();
console.log(`\ncapsule-size-uniform: ${pass} PASS / ${fail} FAIL`);
process.exit(fail ? 1 : 0);

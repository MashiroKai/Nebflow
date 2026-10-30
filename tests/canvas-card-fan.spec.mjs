// canvas-card-fan.spec.mjs — canvas 顶部文件扇（扇卡批 2026-10-30 作者令 20:09
// 「canvas 顶部多文件 tab 视图改为卡片式左右翻动操作（卡片扇翻）」）新契约钉。
//
// 作者令（设计锚 = 2026-10-29 叠卡令 + Pop 扇形叠卡批 d5613e0ea）：参考微信叠卡
// —— 强制放缩所有卡片到一个大小（复用 Pop 扇卡同套卡面规格 220×280，11/14）
// + 扑克式轻微旋转展开（游标卡完整置顶，邻卡以卡顶中心为轴轻微旋开收于其下
// —— 不是直直重叠）；左右翻动换选。文件名显示带中文界面文案（卡头中文签 +
// 扇顶提示条，卡片名不裸英文）。手机端已是独立界面 —— 主窗扇不于移动层重复
// 打开（去重腿）。
//
// 本 spec 钉**扇卡批新增契约**（环状态机沿承契约 —— 滚轮设备分档/冷却/连续性、
// hover 链、右键菜单、deck 互斥、单页签不展 —— 在 canvas-capsule-ring.spec 回归）：
//   FAN-1  扇开：ring-open + 全页签挂载 + 全卡 220×280（强制放缩到一个大小）
//   FAN-2  扇形语言：游标 z5/x0/r0 完整置顶；|rel|=1 ±112px/±7°、|rel|=2
//          ±206px/±13° 左右镜像；|rel|≥3 弧外零透明不可点（6 卡腿）
//   FAN-3  中文界面文案：卡头「文件」签 + 页签名（不裸英文）+ 扇顶提示条
//   FAN-4  滚轮左右翻动：deltaX 主导单事件一格（扇批后翻动主手势即横轴）
//   FAN-5  卡面横拖翻页（指针腿）：跟手 --fan-dx + 过阈翻一张 + 快甩两张
//   FAN-6  小拖不翻 + 回弹（--fan-dx 归零）
//   FAN-7  翻页 ≠ 切换：游标离激活后激活不变，点击游标卡才切换（点击零动作/
//          点击切换两态）
//   FAN-8  扇悬标题头下方：卡顶在标题头底缘之下、标题常驻可见（结构承 #926）
//   FAN-9  移动层去重：mobile-layer 下 hover 不开扇（手机文件界面独立成态）
//
// 运行：node tests/canvas-card-fan.spec.mjs
// （boot 同 canvas-capsule-ring.spec：隔离静态服务器 + workspace-open-item
//   直灌文件卡 + __canvasDeck.state() 探针；永不触碰宿主 8080）

import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { mkdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const SHOTS = join(HERE, '../evidence/20261030_canvas-card-fan/shots');
mkdirSync(SHOTS, { recursive: true });
const PORT = Number(process.env.PORT || 8168);
const BASE = `http://127.0.0.1:${PORT}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let pass = 0, fail = 0;
function ok(name, cond, detail = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  if (cond) pass++; else fail++;
}
const approx = (a, b, tol) => Math.abs(a - b) <= tol;

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

const FILES = ['alpha', 'bravo', 'charlie', 'delta', 'echo', 'foxtrot'];

async function boot({ colorScheme = 'dark', viewport = { width: 1440, height: 900 } } = {}) {
  const ctx = await browser.newContext({ viewport, colorScheme });
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
        ws.send(JSON.stringify({
          type: 'fileContent', path: m.path, absPath: m.path,
          content: '# x\n\nbody\n', itemType: 'markdown',
          fileName: (m.path || '').split('/').pop(), size: 10,
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

async function openFile(page, name) {
  const absPath = `/tmp/cfan-fixture/${name}.md`;
  await page.evaluate(({ absPath, title }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType: 'markdown', title, content: '# x\n', absPath, pinned: true },
    }));
  }, { absPath, title: `${name}.md` });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${absPath}"]`, { timeout: 10000, state: 'attached' });
}

const stateOf = (page) => page.evaluate(() => window.__canvasDeck.state());

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
  await sleep(560);
}
const fanSnap = (page) => page.evaluate(() => {
  const cards = [...document.querySelectorAll('#canvas-capsule-ring .cr-capsule')];
  const byRel = (cls) => cards.filter((c) => c.classList.contains(cls));
  const poses = cards.map((c) => ({
    id: c.dataset.tabId,
    cls: c.className,
    x: parseFloat(c.style.getPropertyValue('--cr-x') || '0'),
    r: parseFloat(c.style.getPropertyValue('--cr-r') || '0'),
    z: Number(c.style.zIndex || 0),
    w: c.offsetWidth, h: c.offsetHeight,
    tag: c.querySelector('.cr-tag')?.textContent || null,
    name: c.querySelector('.cr-name')?.textContent || null,
  }));
  const cursor = document.querySelector('#canvas-capsule-ring .cr-capsule.is-center');
  const hdr = document.querySelector('#canvas-panel .canvas-header');
  const p = document.getElementById('canvas-panel');
  return {
    open: hdr.classList.contains('ring-open'),
    n: cards.length,
    poses,
    centerId: cursor?.dataset.tabId || null,
    edges: byRel('is-edge').length,
    farCount: byRel('is-far').length,
    farPe: byRel('is-far')[0] ? getComputedStyle(byRel('is-far')[0]).pointerEvents : null,
    farOpacity: byRel('is-far')[0] ? getComputedStyle(byRel('is-far')[0]).opacity : null,
    hint: document.querySelector('#canvas-capsule-ring .cr-hint')?.textContent || null,
    hintOpacity: (() => { const h = document.querySelector('#canvas-capsule-ring .cr-hint'); return h ? getComputedStyle(h).opacity : null; })(),
    titleOpacity: getComputedStyle(document.querySelector('.canvas-title .t-inner')).opacity,
    hdrBottom: hdr.getBoundingClientRect().bottom,
    minCardTop: Math.min(...cards.map((c) => c.getBoundingClientRect().top)),
    panelTop: p.getBoundingClientRect().top,
    fanDx: document.getElementById('canvas-capsule-ring').style.getPropertyValue('--fan-dx') || '0px',
  };
});

/* ════════ 桌面腿：扇契约（暗色，6 页签）════════ */
{
  const { ctx, page, errors } = await boot({ colorScheme: 'dark' });
  for (const f of FILES) await openFile(page, f);          // 6 页签（foxtrot 活动）
  await page.evaluate(() => document.querySelector('.canvas-tab[data-tab-id="plugins"] .canvas-tab-close')?.click());
  await sleep(400);
  const s0 = await stateOf(page);
  ok('FAN-0 前置：六文件页签开（foxtrot 活动）', s0.tabCount === 6 && s0.activeId === 'file:/tmp/cfan-fixture/foxtrot.md',
    `n=${s0.tabCount} active=${s0.activeId}`);

  await hoverCapsule(page);
  const hov = await fanSnap(page);
  /* FAN-1 扇开 + 全卡同尺寸（Pop 扇卡规格 220×280） */
  ok('FAN-1a hover 开扇：ring-open + 6 卡全挂载 + 中文提示条现形',
    hov.open && hov.n === 6 && hov.hint === '左右滑动或滚动翻页 · 点击卡片切换文件' && hov.hintOpacity === '1',
    `open=${hov.open} n=${hov.n} hint=${hov.hint}`);
  ok('FAN-1b 全卡强制放缩到一个大小 220×280（offset 盒对 transform 免疫，游标/邻位/弧外同面）',
    hov.poses.every((c) => c.w === 220 && c.h === 280),
    hov.poses.map((c) => `${c.w}x${c.h}`).join(' '));

  /* FAN-2 扇形语言：游标完整置顶；±1 ±112/±7°；±2 ±206/±13°；|rel|≥3 弧外 */
  const cur = hov.poses.find((c) => c.id === hov.centerId);
  const rel1 = hov.poses.filter((c) => c.cls.includes('is-edge') && Math.abs(c.x) < 200);
  const rel2 = hov.poses.filter((c) => c.cls.includes('is-edge') && Math.abs(c.x) >= 200);
  const side1 = rel1.map((c) => Math.sign(c.x)).sort().join(',');
  const side2 = rel2.map((c) => Math.sign(c.x)).sort().join(',');
  ok('FAN-2a 游标卡完整置顶：z5 + 零平移零旋转（x=0 r=0）',
    !!cur && cur.z === 5 && cur.x === 0 && cur.r === 0, `z=${cur?.z} x=${cur?.x} r=${cur?.r}`);
  ok('FAN-2b |rel|=1 邻卡 ±112px/±7° 左右镜像（扑克式轻微旋转 —— 角度非零 ≤8°、|x| 等幅）',
    rel1.length === 2 && side1 === '-1,1'
    && rel1.every((c) => approx(Math.abs(c.x), 112, 0.5) && approx(Math.abs(c.r), 7, 0.5) && Math.sign(c.r) === Math.sign(c.x) && c.z === 4),
    rel1.map((c) => `${c.x}/${c.r}°/z${c.z}`).join(' '));
  ok('FAN-2c |rel|=2 邻卡 ±206px/±13° 左右镜像（递增档，同号旋转+平移）',
    rel2.length === 2 && side2 === '-1,1'
    && rel2.every((c) => approx(Math.abs(c.x), 206, 0.5) && approx(Math.abs(c.r), 13, 0.5) && Math.sign(c.r) === Math.sign(c.x) && c.z === 3),
    rel2.map((c) => `${c.x}/${c.r}°/z${c.z}`).join(' '));
  ok('FAN-2d |rel|≥3 弧外（6 卡 ⇒ 恰 1 弧外）：零透明 + 不可点（位姿保留）',
    hov.farCount === 1 && hov.farOpacity === '0' && hov.farPe === 'none',
    `far=${hov.farCount} op=${hov.farOpacity} pe=${hov.farPe}`);

  /* FAN-3 中文界面文案：卡头「文件」签 + 页签名（卡片名不裸英文） */
  ok('FAN-3 卡头 = 中文「文件」签 + 页签名 + 大类型字形面（签/名齐备，零裸英文卡名）',
    hov.poses.every((c) => c.tag === '文件' && /\.md$/.test(c.name || '')),
    hov.poses.map((c) => `${c.tag}:${c.name}`).join(' '));

  /* FAN-8 结构承 #926：扇悬标题头下方 + 标题常驻 */
  ok('FAN-8 扇悬标题头下方：全卡顶缘 > 标题头底缘 + 标题 opacity 恒 1（扇不顶替标题文字）',
    hov.minCardTop > hov.hdrBottom && hov.titleOpacity === '1',
    `minTop=${hov.minCardTop.toFixed(1)} hdrBottom=${hov.hdrBottom.toFixed(1)} title=${hov.titleOpacity}`);
  ok('FAN-8b 零越顶缘：扇全在面板顶缘之下', hov.minCardTop > hov.panelTop, `minTop=${hov.minCardTop.toFixed(1)} panelTop=${hov.panelTop}`);

  await page.screenshot({
    path: join(SHOTS, '5-fan-six-cards-dark.png'),
    clip: await page.evaluate(() => {
      const p = document.getElementById('canvas-panel').getBoundingClientRect();
      return { x: Math.max(0, p.left), y: Math.max(0, p.top), width: p.width, height: 420 };
    }),
  });

  /* FAN-4 滚轮左右翻动：deltaX 主导单事件一格 */
  const c4 = await fanSnap(page);
  const i4 = c4.poses.findIndex((c) => c.id === c4.centerId);
  await page.mouse.wheel(120, 0);                          // 横滚一格（鼠标档）
  await sleep(300);
  const w4 = await fanSnap(page);
  const i4b = w4.poses.findIndex((c) => c.id === w4.centerId);
  const expect4 = c4.poses[(i4 + 1) % c4.poses.length].id; // +1 环绕
  ok('FAN-4 滚轮左右翻动：deltaX +120 单事件 → 游标恰进一步（环绕一格，激活不变）',
    w4.open && w4.centerId === expect4 && (await stateOf(page)).activeId === 'file:/tmp/cfan-fixture/foxtrot.md',
    `center=${w4.centerId}（expected ${expect4}）`);

  /* FAN-5 卡面横拖翻页：跟手 --fan-dx + 过阈翻一张（慢拖确定性一
     * 格 —— 步间 40ms 保速度 < 0.5px/ms 快甩阈） */
  const p5 = await capsulePoint(page);
  const dragY = await page.evaluate(() => {
    const c = document.querySelector('#canvas-capsule-ring .cr-capsule.is-center');
    const r = c.getBoundingClientRect();
    return r.top + r.height / 2;
  });
  const c5 = await fanSnap(page);
  const i5 = c5.poses.findIndex((c) => c.id === c5.centerId);
  await page.mouse.move(p5.x, dragY);
  await page.mouse.down();
  for (let d = 0; d <= 160; d += 20) { await page.mouse.move(p5.x + d, dragY); await sleep(40); }
  const midDrag = await page.evaluate(() => document.getElementById('canvas-capsule-ring').style.getPropertyValue('--fan-dx'));
  await page.mouse.up();
  await sleep(300);
  const d5 = await fanSnap(page);
  const i5b = d5.poses.findIndex((c) => c.id === d5.centerId);
  ok('FAN-5a 横拖跟手：拖动中 --fan-dx ≈ 位移（1:1 跟手）', parseFloat(midDrag) >= 150, `fan-dx=${midDrag}`);
  ok('FAN-5b 松手过阈翻一张：|dx|=160 ≥ 64 → 游标恰进一步（慢拖速度阈下 = 恰一格）',
    d5.centerId === c5.poses[(i5 + 1) % c5.poses.length].id && d5.centerId !== c5.poses[i5].id,
    `center ${c5.poses[i5].id.split('/').pop()} → ${d5.poses[i5b].id.split('/').pop()}`);
  ok('FAN-5c 翻页后 --fan-dx 归零（回弹）', parseFloat(d5.fanDx) === 0, `fan-dx=${d5.fanDx}`);

  /* FAN-6 小拖不翻 + 回弹 */
  const c6 = await fanSnap(page);
  await page.mouse.move(p5.x, dragY);
  await page.mouse.down();
  for (let d = 0; d <= 30; d += 10) { await page.mouse.move(p5.x + d, dragY); await sleep(30); }
  await page.mouse.up();
  await sleep(300);
  const d6 = await fanSnap(page);
  ok('FAN-6 小拖不翻：|dx|=30 < 64 → 游标不动 + 回弹归零',
    d6.centerId === c6.centerId && parseFloat(d6.fanDx) === 0,
    `center=${d6.centerId} fan-dx=${d6.fanDx}`);
  await sleep(450);   // 越过横拖 click 压制窗（350ms）——FAN-7b 的点击必须是干净 press

  /* FAN-7 翻页 ≠ 切换 + 点击游标卡切换（翻页后游标 ≠ 激活；点击游标卡才切换） */
  const activeBefore7 = (await stateOf(page)).activeId;
  ok('FAN-7a 翻页不改激活：游标已离激活、激活页签不变',
    d6.centerId !== activeBefore7 && activeBefore7 === 'file:/tmp/cfan-fixture/foxtrot.md',
    `cursor=${d6.centerId} active=${activeBefore7}`);
  // 点击游标卡中心（游标卡完整置顶、中心自收 pointer）→ 切换生效
  const curPt = await page.evaluate(() => {
    const c = document.querySelector('#canvas-capsule-ring .cr-capsule.is-center');
    const r = c.getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  });
  await page.mouse.click(curPt.x, curPt.y);
  await sleep(350);
  const s7 = await stateOf(page);
  const r7 = await fanSnap(page);
  ok('FAN-7b 点击游标卡 = 切换（游标≠激活时）：激活易主 + 扇保持开 + 游标跳新激活',
    s7.activeId === d6.centerId && r7.open && r7.centerId === s7.activeId,
    `active=${s7.activeId} center=${r7.centerId}`);
  // 游标 = 激活时再点 → 零动作（激活不变）
  const curPt2 = await page.evaluate(() => {
    const c = document.querySelector('#canvas-capsule-ring .cr-capsule.is-center');
    const r = c.getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  });
  await page.mouse.click(curPt2.x, curPt2.y);
  await sleep(350);
  ok('FAN-7c 游标=激活时点击零动作（CC1 沿承）：激活不变 + 扇保持开',
    (await stateOf(page)).activeId === s7.activeId && (await fanSnap(page)).open,
    `active=${(await stateOf(page)).activeId}`);

  ok('FAN-R0 桌面腿零 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ 移动腿：去重（手机端独立界面，主窗扇不重复打开）════════ */
{
  const { ctx, page, errors } = await boot({ colorScheme: 'dark', viewport: { width: 390, height: 844 } });
  await page.evaluate(() => document.querySelector('.canvas-tab[data-tab-id="plugins"] .canvas-tab-close')?.click()).catch(() => {});
  for (const f of FILES.slice(0, 3)) await openFile(page, f);
  await sleep(400);
  const isMobile = await page.evaluate(() => document.body.classList.contains('mobile-layer'));
  ok('FAN-9a 前置：移动层形态 + 三文件页签开', isMobile && (await stateOf(page)).tabCount === 3,
    `mobile=${isMobile} n=${(await stateOf(page)).tabCount}`);
  // hover 标题头（移动层卡头形态）→ 主窗扇不得开（openRing isMobileLayer 让位）
  const hdrInfo = await page.evaluate(() => {
    const hdr = document.querySelector('#canvas-panel .canvas-header');
    if (!hdr) return { exists: false };
    const r = hdr.getBoundingClientRect();
    return { exists: true, x: r.left + r.width / 2, y: r.top + r.height / 2, visible: r.width > 0 };
  });
  if (hdrInfo.exists && hdrInfo.visible) {
    await page.mouse.move(hdrInfo.x, hdrInfo.y);
    await sleep(560);
  }
  const m9 = await page.evaluate(() => ({
    ringOpen: document.querySelector('#canvas-panel .canvas-header')?.classList.contains('ring-open') || false,
    cards: document.querySelectorAll('#canvas-capsule-ring .cr-capsule').length,
    ringHidden: document.getElementById('canvas-capsule-ring')?.hidden ?? null,
  }));
  ok('FAN-9b 移动层不开扇（去重）：hover 卡头零环卡挂载 + 环层恒隐',
    m9.ringOpen === false && m9.cards === 0 && m9.ringHidden === true,
    `open=${m9.ringOpen} cards=${m9.cards} hidden=${m9.ringHidden}`);
  ok('FAN-R0b 移动腿零 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

await browser.close();
server.close();
console.log(`\ncanvas-card-fan: ${pass} PASS / ${fail} FAIL`);
process.exit(fail ? 1 : 0);

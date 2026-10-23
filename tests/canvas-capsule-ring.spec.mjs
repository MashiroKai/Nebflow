// canvas-capsule-ring.spec.mjs — canvas 顶部胶囊交互重做（作者令 2026-10-22）+ 对称修复批（作者令 2026-10-23）spec 钉
//
// 作者令五条（2026-10-22）：
//   ① 平时态 = 单胶囊（尺寸调小，与现状一致同位）
//   ② hover 态 = 圆环排布（capsule ring）：各页签胶囊沿弧排布、最多同时可见 3 个；
//      滚轮换选（hover 交互改向令 2026-10-22：纵滚 deltaY 主导——鼠标可操作；
//      触控板横滑 deltaX 保留副手势）；点击即切换；
//      缘位胶囊沿曲率 rotateY 透视 + 模糊扭曲（cover-flow），材质 = 全站统一
//      frosted token（--glass-bg/--glass-blur saturate(1.15)/--glass-border/
//      --frost-shadow，与 chat header 同源逐项相等）
//   ③ 按住 = 交互按钮语义：user-select:none（严禁文字多选/双击选中）+ dragstart
//      preventDefault（防原生拖拽）；按压触感照旧（CC1 承接）
//   ④ 右键菜单 = 多开（铺开叠卡）· 关闭 · 在独立窗口打开（联动 #717 卡窗链
//      popoutCardWindow；独立中 = 合并回主窗）——引用项保留（CC4 契约）
//   ⑤ 环上横滑优先消费（preventDefault + stopPropagation），画布区归 #716
//      捏合缩放/平移（不冲突钉在 wiki spec §G 双侧验证）
//
// 修复批三令（作者 2026-10-23 逐字「这个胶囊的hover要在上下显示，这样才有滚动
// 的感觉，现在全在下面。另外，胶囊的大小要保持固定，而且相对与canvas居中显示」）：
//   A 上下真对称 —— 缘位一上一下 |y| 严格相等（−edgeY / +edgeY），rotateX 反号
//     等幅；双缘全下垂（peek 0.58×/hang 1.62×）避裁妥协退役
//   B 尺寸恒定 —— 胶囊本体（常态/hover 展开/环滚动中）宽高相等，零缩放
//   C canvas 居中 —— 常态横轴 = #canvas-slide 中线（桌面主窗腿）
//
// 顶部修正批（作者 2026-10-23 11:55 逐字「canvas的hover要保持在顶部居中，
// 而不是整个canvas居中，顶部有些超出是正常且允许的。但是不要太多。」）：
//   D 环锚常驻顶带 —— 对称批的「环开沉降 canvas 垂直中点」（.ring-open
//     --ring-dy 双轴居中）撤销；横轴中线居中保持，环开态纵轴 = 常态顶带位
//     （零沉降，绝不落到 canvas 中部）
//   E 顶部越出克制 —— 上缘位允许略微越出面板顶缘（负向超出正常且允许），
//     量 = 胶囊自身高度的一小部分（视觉 ~1/3 胶囊高，canvas.js RING_TOP_OUT
//     布局系数 0.8 折算）；A 令对称（|y| 相等 + rotateX 反号等幅）保持
//
// 六项批（作者 2026-10-23 15:10 附截图，六项②「顶部越出去掉截断感」逐字
// 「让胶囊真能渲染超出 canvas 顶缘…消灭硬裁切线」）：
//   F 真越出 —— 环层挂 body（#canvas-capsule-ring position:fixed z-246），
//     面板 overflow:hidden 裁切盒失效：上缘位探出段真实渲染在 canvas 顶缘外；
//     E 令越出量克制档保持不变。配套：缘位可点性走稳态门（展开 0.36s 过渡后
//     .steady 才接 pointer —— 展开中胶囊从指针位掠过会抢标题头按压/拖划），
//     中心位恒 pointer-events:none 落穿标题头手势链（CR2-9/CR2-9b 钉）。
//
// 运行：node tests/canvas-capsule-ring.spec.mjs
// （boot 同 canvas-cardhead-capsule.spec.mjs：隔离静态服务器 + workspace-open-item
//   直灌文件卡 + __canvasDeck.state() 探针；永不触碰宿主 8080）

import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8167);
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

const FILE_CONTENTS = new Map([
  ['/tmp/cr-fixture/alpha.md', { content: '# alpha\n\nalpha body\n', itemType: 'markdown' }],
  ['/tmp/cr-fixture/bravo.md', { content: '# bravo\n\nbravo body\n', itemType: 'markdown' }],
  ['/tmp/cr-fixture/charlie.md', { content: '# charlie\n\ncharlie body\n', itemType: 'markdown' }],
]);

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
    return { x: r.left + r.width / 2, y: r.top + r.height / 2, w: r.width, h: r.height };
  });
}
async function hoverCapsule(page) {
  const p = await capsulePoint(page);
  await page.mouse.move(p.x, p.y);
  await sleep(520);                                 // pointerenter 开环 + 环展开过渡（0.36s）走完
}
async function unhover(page) {
  const p = await capsulePoint(page);
  await page.mouse.move(p.x, p.y + 320);            // 离开 hover 区（环开点 ∪ 环胶囊，顶带联合盒）
  await sleep(450);                                 // 140ms 宽限 + 收环过渡
}
const ringInfo = (page) => page.evaluate(() => {
  const hdr = document.querySelector('#canvas-panel .canvas-header');
  const ring = document.getElementById('canvas-capsule-ring');
  const caps = [...document.querySelectorAll('#canvas-capsule-ring .cr-capsule')];
  const cs = (el) => {
    const c = getComputedStyle(el);
    return { bg: c.backgroundColor, blur: c.backdropFilter || c.webkitBackdropFilter, border: c.borderColor, shadow: c.boxShadow, filter: c.filter, opacity: +(+c.opacity).toFixed(2) };
  };
  return {
    open: hdr.classList.contains('ring-open'),
    n: caps.length,
    center: caps.find((c) => c.classList.contains('is-center')) || null,
    edges: caps.filter((c) => c.classList.contains('is-edge')),
    fars: caps.filter((c) => c.classList.contains('is-far')),
    centerMat: caps[0] ? cs(caps[0]) : null,
    centerId: (caps.find((c) => c.classList.contains('is-center')) || {}).dataset?.tabId || null,
    centerRx: caps.find((c) => c.classList.contains('is-center'))?.style.getPropertyValue('--cr-rx') || null,
    edgeRx: caps.find((c) => c.classList.contains('is-edge'))?.style.getPropertyValue('--cr-rx') || null,
    edgeRxs: caps.filter((c) => c.classList.contains('is-edge')).map((c) => c.style.getPropertyValue('--cr-rx')),
    edgeYs: caps.filter((c) => c.classList.contains('is-edge')).map((c) => c.style.getPropertyValue('--cr-y')),
    edgeBlur: caps.find((c) => c.classList.contains('is-edge')) ? cs(caps.find((c) => c.classList.contains('is-edge'))).filter : null,
    farCount: caps.filter((c) => c.classList.contains('is-far')).length,
    titleOpacity: getComputedStyle(document.querySelector('.canvas-title .t-inner')).opacity,
    hdrUserSelect: getComputedStyle(hdr).userSelect || getComputedStyle(hdr).webkitUserSelect,
    ringHidden: ring ? ring.hidden : null,
  };
});

/* ════════ 桌面腿 ════════ */
{
  const { ctx, page, errors } = await boot({ colorScheme: 'dark' });
  await openFile(page, '/tmp/cr-fixture/alpha.md');
  await openFile(page, '/tmp/cr-fixture/bravo.md');
  await openFile(page, '/tmp/cr-fixture/charlie.md');   // charlie 活动页签
  await page.evaluate(() => document.querySelector('.canvas-tab[data-tab-id="plugins"] .canvas-tab-close')?.click());
  await sleep(400);
  const s0 = await stateOf(page);
  ok('CR0 前置：三文件页签开（charlie 活动）',
    s0.tabCount === 3 && s0.activeId === 'file:/tmp/cr-fixture/charlie.md', `n=${s0.tabCount} active=${s0.activeId}`);

  /* ① 平时态：单胶囊 + 尺寸调小（t-name 12px / padding 4px 档）+ 零环 */
  const rest = await ringInfo(page);
  const restStyle = await page.evaluate(() => {
    const name = document.querySelector('.canvas-title .t-name');
    const hdr = document.querySelector('#canvas-panel .canvas-header');
    const slide = document.getElementById('canvas-slide').getBoundingClientRect();
    const hr = hdr.getBoundingClientRect();
    return { font: getComputedStyle(name).fontSize, pad: getComputedStyle(hdr).paddingTop,
             select: getComputedStyle(hdr).userSelect || getComputedStyle(hdr).webkitUserSelect,
             w: hr.width, h: hr.height, cx: hr.left + hr.width / 2, cy: hr.top + hr.height / 2,
             slideCx: slide.left + slide.width / 2, slideCy: slide.top + slide.height / 2 };
  });
  ok('CR1-0 平时态：环闭 + 标题可见', !rest.open && rest.n === 0 && rest.titleOpacity === '1' && rest.ringHidden === true,
    `open=${rest.open} n=${rest.n} title=${rest.titleOpacity}`);
  ok('CR1-1 尺寸调小：t-name 12px + 头 padding 4px 档', restStyle.font === '12px' && restStyle.pad === '4px',
    `font=${restStyle.font} pad=${restStyle.pad}`);

  /* ② hover 展开：ring-open + 3 胶囊（1 中心 + 2 缘位）+ cover-flow 位姿 */
  await hoverCapsule(page);
  const hov = await ringInfo(page);
  ok('CR2-1 hover 展开：ring-open + 3 胶囊挂载（0 弧外 —— 最多同时可见 3 个）',
    hov.open && hov.n === 3 && hov.farCount === 0, `open=${hov.open} n=${hov.n} far=${hov.farCount}`);
  ok('CR2-2 中心 = 激活页签（charlie）+ 零旋转',
    hov.centerId === 'file:/tmp/cr-fixture/charlie.md' && hov.centerRx === '0deg', `center=${hov.centerId} rx=${hov.centerRx}`);
  const eysSorted = hov.edgeYs.map(parseFloat).sort((a, b) => a - b);   // [上(负), 下(正)]
  const rxsSorted = hov.edgeRxs.map(parseFloat).sort((a, b) => a - b);  // [下(负), 上(正)]
  ok('CR2-3 缘位上下真对称（作者令 2026-10-23）：一上一下、|y| 严格相等、rotateX 反号等幅（±34°）+ 模糊扭曲',
    hov.edges.length === 2 && eysSorted.length === 2
    && eysSorted[0] < 0 && eysSorted[1] > 0
    && approx(-eysSorted[0], eysSorted[1], 0.5)
    && approx(Math.abs(rxsSorted[0]), rxsSorted[1], 0.5) && Math.abs(rxsSorted[1]) === 34
    && /blur/.test(hov.edgeBlur || ''),
    `ys=${hov.edgeYs.join('/')} rxs=${hov.edgeRxs.join('/')} filter=${hov.edgeBlur}`);

  /* ② E 令·顶部越出克制（修正批）：环锚常驻顶带 —— 上缘位允许略微越出面板
     顶缘（负向超出正常且允许），量 = 胶囊自身高度的一小部分（0 < 越出 ≤
     0.5×胶囊投影高约束）；下缘位仍全在面板内（真越出批后探出段真实渲染，
     数值断言同一几何） */
  const clip = await page.evaluate(() => {
    const p = document.getElementById('canvas-panel').getBoundingClientRect();
    const rs = [...document.querySelectorAll('#canvas-capsule-ring .cr-capsule.is-edge')]
      .map((c) => c.getBoundingClientRect());
    const cap = document.querySelector('#canvas-capsule-ring .cr-capsule.is-center');
    return { pTop: p.top, pBottom: p.bottom, minTop: Math.min(...rs.map((r) => r.top)),
             maxBottom: Math.max(...rs.map((r) => r.bottom)), n: rs.length,
             capH: cap ? cap.offsetHeight : 28 };
  });
  const topOut = clip.pTop - clip.minTop;
  ok('CR2-6 顶部越出克制（修正批）：上缘位越出顶缘 0 < out ≤ 0.5×胶囊高 + 下缘位不越底缘',
    clip.n === 2 && topOut > 0 && topOut <= clip.capH * 0.5 + 2 && clip.maxBottom <= clip.pBottom - 1,
    `out=${topOut.toFixed(1)}px (cap ${clip.capH}px) / maxBottom=${clip.maxBottom.toFixed(1)} vs ${clip.pBottom.toFixed(1)}`);

  /* ② E 令·真越出（六项批 2026-10-23「顶部越出去掉截断感」）：环层挂 body ——
     面板 overflow:hidden 裁不到，上缘位探出段真实渲染在 canvas 顶缘外（修前
     硬裁切线退役）；中心位恒 pe:none 落穿标题头手势链，缘位可点性走稳态门。 */
  const real = await page.evaluate(() => {
    const panel = document.getElementById('canvas-panel');
    const ring = document.getElementById('canvas-capsule-ring');
    const pTop = panel.getBoundingClientRect().top;
    const edge = [...document.querySelectorAll('#canvas-capsule-ring .cr-capsule.is-edge')]
      .sort((a, b) => a.getBoundingClientRect().top - b.getBoundingClientRect().top)[0]; // 上缘位（min top），非 DOM 序首 元
    const er = edge ? edge.getBoundingClientRect() : null;
    const center = document.querySelector('#canvas-capsule-ring .cr-capsule.is-center');
    return {
      ringParent: ring ? ring.parentElement.id || ring.parentElement.tagName : null,
      panelContainsRing: panel.contains(ring),
      edgeAbovePanel: er ? er.top < pTop - 0.5 : null,
      centerPe: center ? getComputedStyle(center).pointerEvents : null,
    };
  });
  ok('CR2-9 真越出（六项②）：环层在面板外（body 层 —— overflow:hidden 裁切盒失效）+ 上缘位 rect 真实高于面板顶缘',
    real.ringParent === 'BODY' && real.panelContainsRing === false && real.edgeAbovePanel === true,
    `parent=${real.ringParent} contains=${real.panelContainsRing} edgeAbove=${real.edgeAbovePanel}`);
  ok('CR2-9b 中心位落穿（六项②配套）：is-center pointer-events none（标题头手势链不受环层遮蔽）',
    real.centerPe === 'none', `centerPe=${real.centerPe}`);

  /* ② B 令·尺寸恒定：常态 → hover 展开 → 环滚动中，胶囊本体宽高全等 + 零缩放 */
  const openRect = await page.evaluate(() => {
    const hdr = document.querySelector('#canvas-panel .canvas-header');
    const m = new DOMMatrixReadOnly(getComputedStyle(hdr).transform === 'none' ? undefined : getComputedStyle(hdr).transform);
    const r = hdr.getBoundingClientRect();
    return { w: r.width, h: r.height, scale: Math.hypot(m.a, m.b) };
  });
  ok('CR2-7 尺寸恒定（hover 展开）：胶囊宽高 = 常态（±1px）+ transform 零缩放',
    approx(openRect.w, restStyle.w, 1) && approx(openRect.h, restStyle.h, 1) && approx(openRect.scale, 1, 0.001),
    `rest=${restStyle.w.toFixed(1)}x${restStyle.h.toFixed(1)} open=${openRect.w.toFixed(1)}x${openRect.h.toFixed(1)} scale=${openRect.scale.toFixed(3)}`);

  /* ② C 令·canvas 居中（修正批修订）：居中 = 横轴中线常驻；环开态不沉降 ——
     D 令：纵轴仍在顶带（对对称批「环开双轴居中」的撤销） */
  ok('CR2-8a canvas 居中（常态·横轴）：胶囊中心 x = 面板中线（±2px）+ 纵轴在顶带',
    Math.abs(restStyle.cx - restStyle.slideCx) <= 2 && restStyle.cy < restStyle.slideCy,
    `dx=${(restStyle.cx - restStyle.slideCx).toFixed(1)} cy=${restStyle.cy.toFixed(1)} vs slideCy=${restStyle.slideCy.toFixed(1)}`);
  const centered = await page.evaluate(() => {
    const hdr = document.querySelector('#canvas-panel .canvas-header').getBoundingClientRect();
    const slide = document.getElementById('canvas-slide').getBoundingClientRect();
    return { dx: (hdr.left + hdr.width / 2) - (slide.left + slide.width / 2),
             dy: (hdr.top + hdr.height / 2) - (slide.top + slide.height / 2) };
  });
  ok('CR2-8b 环开顶部保持（修正批 D 令）：横轴中线不变（±2px）+ 纵轴零沉降（环开位 = 常态顶带位 ±1.5px，绝不落 canvas 中部）',
    Math.abs(centered.dx) <= 2 && Math.abs((restStyle.cy - restStyle.slideCy) - centered.dy) <= 1.5,
    `dx=${centered.dx.toFixed(1)} dyOpen=${centered.dy.toFixed(1)} dyRest=${(restStyle.cy - restStyle.slideCy).toFixed(1)}`);
  ok('CR2-4 中心位接班：常态标题让位（t-inner opacity 0，同位 morph）',
    hov.titleOpacity === '0', `title=${hov.titleOpacity}`);

  /* ② frosted 材质 = chat header 同 token（逐项相等）—— 取缘位胶囊（完整
   * frosted 药丸；中心位是内容-only 覆层，底 = 标题头胶囊本体） */
  const mat = await page.evaluate(() => {
    const pick = (el) => {
      const c = getComputedStyle(el);
      return { bg: c.backgroundColor, blur: c.backdropFilter || c.webkitBackdropFilter, border: c.borderColor, shadow: c.boxShadow };
    };
    const cap = document.querySelector('#canvas-capsule-ring .cr-capsule.is-edge');
    return { header: pick(document.querySelector('#header')), capsule: cap ? pick(cap) : null };
  });
  ok('CR2-5 材质 = 全站统一 frosted（bg/blur/border/shadow 与 chat header 逐项相等）',
    !!mat.capsule && JSON.stringify(mat.header) === JSON.stringify(mat.capsule),
    JSON.stringify({ header: mat.header, capsule: mat.capsule }).slice(0, 220));

  /* 证据截图：环开态（环锚顶带 —— 上缘位真实越出顶缘渲染（真越出批，无裁切
   *  线）+ 下缘位下垂；clip 取面板顶外扩，含顶缘外探出段全貌） */
  const shotBox = await page.evaluate(() => {
    const p = document.getElementById('canvas-panel').getBoundingClientRect();
    return { x: Math.max(0, p.left), y: Math.max(0, p.top - 56), width: p.width, height: 260 };
  });
  await page.screenshot({
    path: join(HERE, '../evidence/20261023_capsule-six/shots/1-ring-open-dark.png'),
    clip: shotBox,
  });

  /* ③ 滚动选择（hover 交互改向令）：**纵滚 deltaY 主导**（鼠标滚轮即换选，
     一格 ≈ 100 → 归一 0.56 × 100 = 56 = 恰好一格）；触控板横滑 deltaX 保留
     为副手势（-140 = 退两位归一量不变）。 */
  const before = await stateOf(page);
  await page.mouse.wheel(0, -200);                 // 纵滚上滚 2 格 → alpha 居中
  await sleep(300);
  const rolled = await ringInfo(page);
  ok('CR3-1 纵滚换选（鼠标可操作）：deltaY -200 → 游标退两位（alpha 居中），激活不变（滚动≠切换）',
    rolled.centerId === 'file:/tmp/cr-fixture/alpha.md' && (await stateOf(page)).activeId === before.activeId,
    `center=${rolled.centerId} active=${(await stateOf(page)).activeId}`);
  await page.mouse.wheel(-112, 0);                 // 横滑副手势：-112 = 退两位（0 环绕 → bravo）
  await sleep(300);
  const rolledX = await ringInfo(page);
  ok('CR3-1b 横滑保留（触控板副手势）：deltaX -112 → bravo 居中（余量清零随动）',
    rolledX.centerId === 'file:/tmp/cr-fixture/bravo.md', `center=${rolledX.centerId}`);
  await page.mouse.wheel(0, 100);                  // 纵滚下滚 1 格（一格滚轮 = 一换）→ charlie 居中
  await sleep(300);
  const rolled2 = await ringInfo(page);
  ok('CR3-2 反向一格：deltaY +100 → charlie 居中（环绕步进 100px/格，累积器余量随动）',
    rolled2.centerId === 'file:/tmp/cr-fixture/charlie.md', `center=${rolled2.centerId}`);

  /* ② B 令·尺寸恒定（环滚动中）：换选发生后的胶囊本体仍与常态全等 */
  const scrollRect = await page.evaluate(() => {
    const hdr = document.querySelector('#canvas-panel .canvas-header');
    const m = new DOMMatrixReadOnly(getComputedStyle(hdr).transform === 'none' ? undefined : getComputedStyle(hdr).transform);
    const r = hdr.getBoundingClientRect();
    return { w: r.width, h: r.height, scale: Math.hypot(m.a, m.b) };
  });
  ok('CR2-7b 尺寸恒定（环滚动中）：换选进行中胶囊宽高仍 = 常态 + 零缩放',
    approx(scrollRect.w, restStyle.w, 1) && approx(scrollRect.h, restStyle.h, 1) && approx(scrollRect.scale, 1, 0.001),
    `scroll=${scrollRect.w.toFixed(1)}x${scrollRect.h.toFixed(1)} scale=${scrollRect.scale.toFixed(3)}`);

  /* ③ 环上滚轮零外溢：消费于环（游标随动——纵滚已是主手势），pane 滚动纹丝不动 */
  const scrollTopBefore = await page.evaluate(() => {
    const pane = document.querySelector('.canvas-tab-pane.active');
    const sc = [...pane.querySelectorAll('*')].filter((el) => el.scrollHeight > el.clientHeight + 4);
    return sc.length ? sc[0].scrollTop : -1;
  });
  await page.mouse.wheel(0, 200);                    // 环上纵滚（主手势：游标随动）→ pane 不外溢
  await sleep(250);
  const scrollTopAfter = await page.evaluate(() => {
    const pane = document.querySelector('.canvas-tab-pane.active');
    const sc = [...pane.querySelectorAll('*')].filter((el) => el.scrollHeight > el.clientHeight + 4);
    return sc.length ? sc[0].scrollTop : -1;
  });
  ok('CR3-3 环优先消费：环上滚轮后 pane scrollTop 不变（不外溢内容区）',
    scrollTopBefore === scrollTopAfter, `scroll ${scrollTopBefore} → ${scrollTopAfter}`);

  /* ② 点击即切换：缘位胶囊（charlie 现居右侧缘）点击 → 激活易主 + 环收 */
  const edgeId = await page.evaluate(() => {
    const e = [...document.querySelectorAll('#canvas-capsule-ring .cr-capsule.is-edge')];
    return e.map((x) => x.dataset.tabId);
  });
  const target = edgeId.find((id) => id === 'file:/tmp/cr-fixture/charlie.md') || edgeId[0];
  await page.click(`#canvas-capsule-ring .cr-capsule[data-tab-id="${target}"]`);
  await sleep(350);
  const afterClick = await ringInfo(page);
  const sClick = await stateOf(page);
  ok('CR4 点击缘位胶囊 = 切换（激活易主 + 环收 + 标题回位）',
    sClick.activeId === target && !afterClick.open && afterClick.n === 0 && afterClick.titleOpacity === '1',
    `active=${sClick.activeId} open=${afterClick.open}`);

  /* ② hover 收起：离环 140ms 宽限后收（标题回位） */
  await hoverCapsule(page);
  const hov2 = await ringInfo(page);
  await unhover(page);
  const closed = await ringInfo(page);
  ok('CR5 hover 收起：离环后 ring-open 清 + 胶囊清 + 标题回位',
    hov2.open && !closed.open && closed.n === 0 && closed.titleOpacity === '1',
    `open ${hov2.open} → ${closed.open} n=${closed.n}`);

  /* 证据截图：平时态（收起）——胶囊回顶带横轴居中、零环（顶带 clip） */
  const restBox = await page.evaluate(() => {
    const p = document.getElementById('canvas-panel').getBoundingClientRect();
    return { x: Math.max(0, p.left), y: Math.max(0, p.top), width: p.width, height: 120 };
  });
  await page.screenshot({
    path: join(HERE, '../evidence/20261023_capsule-six/shots/2-rest-dark.png'),
    clip: restBox,
  });

  /* ④ 右键菜单：多开 / 关闭 / 在独立窗口打开（+ 引用项保留） */
  await hoverCapsule(page);
  const mp = await capsulePoint(page);
  await page.mouse.click(mp.x, mp.y, { button: 'right' });
  await sleep(250);
  const menu = await page.evaluate(() => {
    const m = document.querySelector('.canvas-tab-context-menu');
    return m ? [...m.querySelectorAll('button')].map((b) => b.textContent) : null;
  });
  const menuText = menu ? menu.join('/') : '';
  ok('CR6-1 胶囊右键菜单 = 引用当前页 + 多开 + 关闭 + 在独立窗口打开',
    !!menu && menuText.includes('引用当前页') && menuText.includes('多开') && menu.includes('关闭') && menu.includes('在独立窗口打开'),
    menuText);

  // 在独立窗口打开 → popoutCardWindow（#717 卡窗链；window.open 桩 → is-independent 标记）
  await page.evaluate(() => {
    window.__rawOpen = window.open;
    window.open = () => ({ closed: false, postMessage() {}, close() {}, focus() {}, addEventListener() {}, document: {} });
  });
  await page.evaluate(() => {
    const b = [...document.querySelectorAll('.canvas-tab-context-menu button')].find((x) => x.textContent === '在独立窗口打开');
    if (b) b.click();
  });
  await sleep(350);
  const indep = await page.evaluate(() => {
    const id = window.__canvasDeck.state().activeId;
    return {
      tab: document.querySelector(`.canvas-tab[data-tab-id="${id}"]`)?.classList.contains('is-independent'),
      pane: document.querySelector(`.canvas-tab-pane[data-tab-id="${id}"]`)?.getAttribute('data-independent'),
    };
  });
  ok('CR6-2 在独立窗口打开 → popout 链（#717）：页签 is-independent + 卡面 data-independent',
    indep.tab === true && indep.pane === '1', JSON.stringify(indep));

  // 独立中再开菜单 = 合并回主窗（同位换形）；合并复原
  await hoverCapsule(page);
  const mp2 = await capsulePoint(page);
  await page.mouse.click(mp2.x, mp2.y, { button: 'right' });
  await sleep(250);
  const menu2 = await page.evaluate(() => {
    const m = document.querySelector('.canvas-tab-context-menu');
    return m ? [...m.querySelectorAll('button')].map((b) => b.textContent) : null;
  });
  ok('CR6-3 独立中菜单换形：在独立窗口打开 → 合并回主窗',
    !!menu2 && menu2.includes('合并回主窗'), JSON.stringify(menu2));
  await page.evaluate(() => {
    const b = [...document.querySelectorAll('.canvas-tab-context-menu button')].find((x) => x.textContent === '合并回主窗');
    if (b) b.click();
  });
  await sleep(350);
  const merged = await page.evaluate(() => {
    const id = window.__canvasDeck.state().activeId;
    return !document.querySelector(`.canvas-tab[data-tab-id="${id}"]`)?.classList.contains('is-independent');
  });
  ok('CR6-4 合并回主窗 → is-independent 清', merged === true, `merged=${merged}`);

  // 多开 = 铺开叠卡（deck 开）；再开 = 收起叠卡（文案随态）
  await hoverCapsule(page);
  const mp3 = await capsulePoint(page);
  await page.mouse.click(mp3.x, mp3.y, { button: 'right' });
  await sleep(250);
  await page.evaluate(() => {
    const b = [...document.querySelectorAll('.canvas-tab-context-menu button')].find((x) => x.textContent === '多开');
    if (b) b.click();
  });
  await sleep(800);
  const deckOpen = await stateOf(page);
  ok('CR6-5 多开 = 铺开叠卡环（deck.open）', deckOpen.open === true, `open=${deckOpen.open}`);
  await hoverCapsule(page);
  const mp4 = await capsulePoint(page);
  await page.mouse.click(mp4.x, mp4.y, { button: 'right' });
  await sleep(250);
  const menu3 = await page.evaluate(() => {
    const m = document.querySelector('.canvas-tab-context-menu');
    return m ? [...m.querySelectorAll('button')].map((b) => b.textContent) : null;
  });
  await page.evaluate(() => {
    const b = [...document.querySelectorAll('.canvas-tab-context-menu button')].find((x) => x.textContent === '收起叠卡');
    if (b) b.click();
  });
  await sleep(700);
  const deckClosed = await stateOf(page);
  ok('CR6-6 deck 开着时菜单换形「收起叠卡」→ 点击收环',
    !!menu3 && menu3.includes('收起叠卡') && deckClosed.open === false,
    `menu=${menu3 ? menu3.join('/') : 'none'} open=${deckClosed.open}`);

  // 关闭：右键缘位胶囊（非激活卡）→ 关闭该项 → tabCount −1
  // （顶部修正批：上缘位探出段被面板 overflow:hidden 裁掉，rect 大部在面板顶
  // 上方 —— victim 取 rect 主体在面板内的胶囊，Playwright 点裁切段会逃出面板）
  await hoverCapsule(page);
  const victim = await page.evaluate(() => {
    const caps = [...document.querySelectorAll('#canvas-capsule-ring .cr-capsule')];
    const active = window.__canvasDeck.state().activeId;
    const p = document.getElementById('canvas-panel').getBoundingClientRect();
    const inside = (c) => { const r = c.getBoundingClientRect(); return r.top >= p.top - 1 && r.bottom <= p.bottom + 1; };
    const other = caps.find((c) => c.dataset.tabId !== active && inside(c))
      || caps.find((c) => c.dataset.tabId !== active);
    return other ? other.dataset.tabId : null;
  });
  if (victim) {
    await page.click(`#canvas-capsule-ring .cr-capsule[data-tab-id="${victim}"]`, { button: 'right' });
    await sleep(250);
    await page.evaluate(() => {
      const b = [...document.querySelectorAll('.canvas-tab-context-menu button')].find((x) => x.textContent === '关闭');
      if (b) b.click();
    });
    await sleep(450);
    const sClose = await stateOf(page);
    ok('CR6-7 菜单「关闭」= 关对应页签（tabCount −1，对项消失）',
      sClose.tabCount === 2 && victim !== sClose.activeId, `n=${sClose.tabCount} victim=${victim}`);
  } else {
    ok('CR6-7 菜单「关闭」= 关对应页签', false, 'no victim capsule');
  }

  /* ③ 按住 = 按钮语义：双击零文本选中 + dragstart 被拦 */
  await unhover(page);
  const cp = await capsulePoint(page);
  await page.mouse.dblclick(cp.x, cp.y);
  const sel = await page.evaluate(() => String(window.getSelection()));
  const dragBlocked = await page.evaluate(() => {
    const hdr = document.querySelector('#canvas-panel .canvas-header');
    const ev = new MouseEvent('dragstart', { bubbles: true, cancelable: true });
    hdr.dispatchEvent(ev);
    return ev.defaultPrevented;
  });
  ok('CR7-1 按住=按钮语义：双击胶囊零文本选中（user-select:none）', sel === '', `sel="${sel}"`);
  ok('CR7-2 防默认拖拽：胶囊 dragstart 被 preventDefault', dragBlocked === true, `prevented=${dragBlocked}`);

  /* ① 单胶囊不展开 */
  await unhover(page);
  await page.evaluate(() => {
    const active = window.__canvasDeck.state().activeId;
    for (const el of [...document.querySelectorAll('#canvas-tab-bar .canvas-tab')]) {
      if (el.dataset.tabId !== active) el.querySelector('.canvas-tab-close')?.click();
    }
  });
  await sleep(400);
  const singleState = await stateOf(page);
  await hoverCapsule(page);
  const single = await ringInfo(page);
  ok('CR8 单胶囊（平时态即全部）：hover 不展开环',
    singleState.tabCount === 1 && !single.open && single.n === 0,
    `n=${singleState.tabCount} open=${single.open} caps=${single.n}`);
  await unhover(page);

  ok('R0 主文档零 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | '));
  await page.evaluate(() => { if (window.__rawOpen) window.open = window.__rawOpen; });
  await ctx.close();
}

/* ════════ 亮色腿：顶带环展开/收起证据（亮主题 × 2 面）════════ */
{
  const { ctx, page, errors } = await boot({ colorScheme: 'light' });
  await openFile(page, '/tmp/cr-fixture/alpha.md');
  await openFile(page, '/tmp/cr-fixture/bravo.md');
  await openFile(page, '/tmp/cr-fixture/charlie.md');
  await page.evaluate(() => document.querySelector('.canvas-tab[data-tab-id="plugins"] .canvas-tab-close')?.click());
  await sleep(400);
  const shotBoxL = await page.evaluate(() => {
    const p = document.getElementById('canvas-panel').getBoundingClientRect();
    return { x: Math.max(0, p.left), y: Math.max(0, p.top - 56), width: p.width, height: 260 };
  });
  await hoverCapsule(page);
  const hovL = await ringInfo(page);
  const eys = hovL.edgeYs.map(parseFloat).sort((a, b) => a - b);
  const clipL = await page.evaluate(() => {
    const p = document.getElementById('canvas-panel').getBoundingClientRect();
    const rs = [...document.querySelectorAll('#canvas-capsule-ring .cr-capsule.is-edge')]
      .map((c) => c.getBoundingClientRect());
    const cap = document.querySelector('#canvas-capsule-ring .cr-capsule.is-center');
    return { pTop: p.top, minTop: Math.min(...rs.map((r) => r.top)), capH: cap ? cap.offsetHeight : 28 };
  });
  const topOutL = clipL.pTop - clipL.minTop;
  ok('L1 亮色：环展开上下对称（|y| 相等一上一下）+ 顶部越出克制 + 环锚顶带零沉降',
    hovL.open && hovL.n === 3 && eys[0] < 0 && eys[1] > 0 && approx(-eys[0], eys[1], 0.5)
    && topOutL > 0 && topOutL <= clipL.capH * 0.5 + 2
    && await page.evaluate(() => {
      const hdr = document.querySelector('#canvas-panel .canvas-header').getBoundingClientRect();
      const slide = document.getElementById('canvas-slide').getBoundingClientRect();
      return Math.abs((hdr.left + hdr.width / 2) - (slide.left + slide.width / 2)) <= 2
        && (hdr.top + hdr.height / 2) < (slide.top + slide.height / 2);
    }),
    `ys=${hovL.edgeYs.join('/')} out=${topOutL.toFixed(1)}px (cap ${clipL.capH}px)`);
  await page.screenshot({
    path: join(HERE, '../evidence/20261023_capsule-six/shots/3-ring-open-light.png'),
    clip: shotBoxL,
  });
  await unhover(page);
  const closedL = await ringInfo(page);
  ok('L2 亮色：收起回单胶囊（环清 + 标题回位 + 回顶带）', !closedL.open && closedL.n === 0 && closedL.titleOpacity === '1',
    `open=${closedL.open} n=${closedL.n}`);
  await page.screenshot({
    path: join(HERE, '../evidence/20261023_capsule-six/shots/4-rest-light.png'),
    clip: await page.evaluate(() => {
      const p = document.getElementById('canvas-panel').getBoundingClientRect();
      return { x: Math.max(0, p.left), y: Math.max(0, p.top), width: p.width, height: 120 };
    }),
  });
  ok('R0b 亮色腿零 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

await browser.close();
server.close();
console.log(`\ncanvas-capsule-ring: ${pass} PASS / ${fail} FAIL`);
process.exit(fail ? 1 : 0);

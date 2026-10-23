// canvas-capsule-six.spec.mjs — 修正批 · canvas 胶囊/按钮族六项（作者令 2026-10-23 15:10）spec 钉
//
// 作者令六项（附截图实证）与断言映射：
//   S1 ①名称双显去重 —— 阅读面首块级 H1 与文件名同名 ⇒ 隐藏（只留胶囊内一处）；
//      非同名标题不动；源码态不动。
//   S3 ③底右钮组贴齐面板右缘 —— source-toggle 右锚 16（gap ≤1px）；html 簇槽
//      回原位（ref-select 64 · find 112 · zoom 160）；窄面板容器查询腿齐落 16。
//   S4 ④底右钮毛玻璃 = chat 对话面板同源 —— bg/blur/border/shadow 与 #header
//      逐项相等（frosted 标准单源；--glass-control-* 自绘退役）。
//   S5 ⑤胶囊尺寸跨场景统一 —— deck 叠卡态（面板整区 0.85）前卡名称条反量回乘，
//      视觉尺寸 = 常态主胶囊基准（高 36 档 ±1.5px、宽 ±3px；computed scale
//      = 1/0.85 ≈ 1.1765）。
//   S6 ⑥a 滑删动画 —— 胶囊横划跟手 1:1（净空内 --head-dx 直读拖距）+ 微倾
//      （--head-rot ≠ 0）；释放过阈（>64）= 甩出动画（.is-swipe-out + 淡出）
//      再关页签；不足阈回弹归零。⑥b 顺滑 —— 横划跟手段非拖卡位姿零重写
//      （MutationObserver 计数）+ 静态面板 deck 盒零逐帧改写。
//   ②（顶部真越出）由 canvas-capsule-ring.spec.mjs CR2-6/CR2-9 承载，本文件不重复。
//
// 断言通道 = window.__canvasDeck.state() + DOM 计算样式/几何 + MutationObserver
// + 真实 mouse pointer 全链（与 canvas-cardhead-capsule.spec.mjs 同构）。
//
// 运行：node tests/canvas-capsule-six.spec.mjs

import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8169);
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

/* 可变文件表：samedoc = 首 H1 与文件名同名（S1 去重腿）；otherdoc = 标题不同名
 * （S1 不动腿）。bravo 长文供滚动面。 */
const TITLE_SAME = 'fig07_He破裂_机制与修复';
const TALL = `# ${TITLE_SAME}\n\n` + Array.from({ length: 80 }, (_, i) => `行 ${i + 1}：滚动穿底样本`).join('\n') + '\n';
const FILE_CONTENTS = new Map([
  ['/tmp/six-fixture/fig07_He破裂_机制与修复.md', { content: TALL, itemType: 'markdown' }],
  ['/tmp/six-fixture/otherdoc.md', { content: '# 完全不同的标题\n\nbody\n', itemType: 'markdown' }],
  ['/tmp/six-fixture/bravo.md', { content: '# bravo\n\n' + Array.from({ length: 80 }, (_, i) => `行 ${i + 1}`).join('\n') + '\n', itemType: 'markdown' }],
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
async function pollUntil(page, cond, ms = 5000) {
  const t0 = Date.now();
  let s = await stateOf(page);
  while (Date.now() - t0 < ms) {
    s = await stateOf(page);
    if (cond(s)) return s;
    await sleep(100);
  }
  return s;
}
/** 胶囊标题区中心（避开右缘 pop 钮：x=40%）。 */
async function capsulePoint(page) {
  return page.evaluate(() => {
    const hdr = document.querySelector('#canvas-panel .canvas-header');
    const r = hdr.getBoundingClientRect();
    return { x: r.left + r.width * 0.4, y: r.top + r.height / 2, rect: { l: r.left, t: r.top, w: r.width, h: r.height } };
  });
}
/** 真实 mouse 拖拽胶囊（pointer 全链），返回松手前最后一步的 --head-dx/--head-rot。 */
async function dragCapsuleTrack(page, ddx, ddy, steps = 12) {
  const p = await capsulePoint(page);
  await page.mouse.move(p.x, p.y);
  await page.mouse.down();
  let last = null;
  for (let i = 1; i <= steps; i++) {
    await page.mouse.move(p.x + (ddx * i) / steps, p.y + (ddy * i) / steps);
    last = await page.evaluate(() => {
      const hdr = document.querySelector('#canvas-panel .canvas-header');
      return { dx: hdr.style.getPropertyValue('--head-dx'), rot: hdr.style.getPropertyValue('--head-rot') };
    });
  }
  await page.mouse.up();
  return last;
}

/* ════════ 桌面腿（dark）：S1 去重 / S3 对齐 / S4 材质 ════════ */
{
  const { ctx, page, errors } = await boot({ colorScheme: 'dark' });
  await openFile(page, '/tmp/six-fixture/fig07_He破裂_机制与修复.md');
  await openFile(page, '/tmp/six-fixture/bravo.md');     // bravo 活动
  await openFile(page, '/tmp/six-fixture/otherdoc.md');
  await page.evaluate(() => document.querySelector('.canvas-tab[data-tab-id="plugins"] .canvas-tab-close')?.click());
  await sleep(500);

  /* ── S1 名称双显去重（otherdoc 活动：非同名标题不动）── */
  const otherVis = await page.evaluate(() => {
    const h = document.querySelector('.canvas-tab-pane[data-tab-id="file:/tmp/six-fixture/otherdoc.md"] .canvas-md-viewer > h1');
    return h ? getComputedStyle(h).display : 'missing';
  });
  ok('S1-1 非同名首标题不动（otherdoc H1 可见）', otherVis === 'block' || otherVis === 'list-item', `display=${otherVis}`);
  const sameHidden = await page.evaluate(() => {
    const h = document.querySelector('.canvas-tab-pane[data-tab-id="file:/tmp/six-fixture/fig07_He破裂_机制与修复.md"] .canvas-md-viewer > h1');
    return h ? getComputedStyle(h).display : 'missing';
  });
  ok('S1-2 同名首标题去重（fig07 阅读面 H1 display:none —— 只留胶囊一处）', sameHidden === 'none', `display=${sameHidden}`);
  // 切到 fig07 活动态：胶囊仍在（名称只此一处）+ 视口首块级元素非 H1
  await page.evaluate(() => {
    const tab = document.querySelector('.canvas-tab[data-tab-id="file:/tmp/six-fixture/fig07_He破裂_机制与修复.md"]');
    tab?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  });
  await sleep(400);
  const s1 = await stateOf(page);
  const firstVisible = await page.evaluate(() => {
    const viewer = document.querySelector('.canvas-tab-pane.active .canvas-md-viewer');
    if (!viewer) return null;
    for (const el of viewer.children) {
      const cs = getComputedStyle(el);
      if (cs.display !== 'none') return el.tagName;
    }
    return null;
  });
  const capName = await page.evaluate(() => (document.getElementById('canvas-title-name') || {}).textContent || '');
  ok('S1-3 活动态：胶囊名称在挂 + 阅读面首可见块非 H1（同名已去重）',
    s1.activeId === 'file:/tmp/six-fixture/fig07_He破裂_机制与修复.md' && capName.includes(TITLE_SAME) && firstVisible !== 'H1',
    `cap=${capName.slice(0, 24)} first=${firstVisible}`);
  // 源码态不动：sourceMode 下 H1 是文本（monaco），渲染面克隆才走 display:none
  const sourceHas = await page.evaluate(() => {
    const pane = document.querySelector('.canvas-tab-pane.active');
    return (pane?.dataset.sourceMode || '0') === '0' && !pane?.querySelector('.canvas-md-viewer > h1[style*="display:none"]');
  });
  ok('S1-4 去重只作用于阅读渲染面（DOM/源码未删，style 内联单点）', sourceHas === true, `sourceMode intact`);

  /* ── S3 底右钮组贴齐（md pane：source-toggle 右锚 16）── */
  const align = await page.evaluate(() => {
    const pr = document.getElementById('canvas-panel').getBoundingClientRect();
    const pick = (sel) => {
      const el = document.querySelector(sel);
      if (!el) return null;
      const r = el.getBoundingClientRect();
      const cs = getComputedStyle(el);
      return { gap: +(pr.right - r.right).toFixed(1), right: cs.right, bottom: cs.bottom };
    };
    return { src: pick('.canvas-source-toggle'), find: pick('.nf-findbar') };
  });
  ok('S3-1 source-toggle 贴齐面板右缘（gap ≤1px，右锚 16 原槽）',
    !!align.src && approx(align.src.gap, 16, 1), `gap=${align.src && align.src.gap} right=${align.src && align.src.right}`);

  /* ── S4 frosted 材质 = chat 对话面板同源（逐项相等，带既有豁免）──
     #header 豁免（前批裁定，不算差异）：border-bottom 透明（Header 底缘零边界
     批）+ box-shadow 仅顶缘 inset（--frost-shadow 豁免裁定）。故比对口径 =
     bg/blur/border-top 与 #header 相等 + shadow 与 :root 的 --frost-shadow
     解析值相等（按钮用完整 token，#header 只有顶缘 inset 是豁免形态）。 */
  const mat = await page.evaluate(() => {
    const pick = (el) => {
      const c = getComputedStyle(el);
      return { bg: c.backgroundColor, blur: c.backdropFilter || c.webkitBackdropFilter, btop: c.borderTopColor, shadow: c.boxShadow };
    };
    return {
      header: pick(document.getElementById('header')),
      src: pick(document.querySelector('.canvas-source-toggle')),
      frost: (() => { // token 经探针元素解析成 computed 形态（顺序/单位归一后再比对）
        const d = document.createElement('div');
        d.style.boxShadow = 'var(--frost-shadow)';
        document.body.appendChild(d);
        const v = getComputedStyle(d).boxShadow;
        d.remove();
        return v;
      })(),
    };
  });
  const norm = (s) => String(s).replace(/\s+/g, ' ').trim();
  const frostEq = mat.src.shadow === mat.header.shadow || norm(mat.src.shadow) === norm(mat.frost);
  ok('S4-1 source-toggle 材质 = #header frosted 单源（bg/blur/border-top 与 #header 相等 + shadow = --frost-shadow token；#header 底缘透明/顶缘 inset 为既有豁免）',
    mat.header.bg === mat.src.bg && mat.header.blur === mat.src.blur && mat.header.btop === mat.src.btop && frostEq,
    JSON.stringify({ header: mat.header, src: mat.src, frost: mat.frost }).slice(0, 220));
  ok('S4-2 减淡 opacity 退役（base opacity = 1 —— 材质到位不靠减淡）',
    await page.evaluate(() => getComputedStyle(document.querySelector('.canvas-source-toggle')).opacity) === '1');

  /* ── S6 ⑥a 滑删动画（fig07 活动态）── */
  // 跟手段：拖 −60px（< 64 阈）→ --head-dx ≈ −60 + 微倾非零
  const tracked = await dragCapsuleTrack(page, -60, 0);
  const dxNum = parseFloat((tracked.dx || '0').replace('px', '')) || 0;
  ok('S6-1 横划跟手 1:1（--head-dx 直读拖距，非旧 ±18 钳制）+ 微倾（--head-rot ≠ 0）',
    approx(dxNum, -60, 6) && parseFloat(tracked.rot) !== 0 && tracked.rot !== '',
    `dx=${tracked.dx} rot=${tracked.rot}`);
  await sleep(300);
  const bounced = await page.evaluate(() => {
    const hdr = document.querySelector('#canvas-panel .canvas-header');
    return { dx: hdr.style.getPropertyValue('--head-dx'), rot: hdr.style.getPropertyValue('--head-rot'), op: hdr.style.opacity };
  });
  ok('S6-2 不足阈松手回弹归零（--head-dx/--head-rot 清零，零副作用）',
    approx(parseFloat(bounced.dx), 0, 0.5) && (bounced.rot === '' || approx(parseFloat(bounced.rot), 0, 0.5)),
    `dx=${bounced.dx} rot=${bounced.rot}`);
  // 过阈甩出：拖 −90 释放 → .is-swipe-out 在挂 + 淡出 → 关页签（tabCount −1）
  const nBefore = (await stateOf(page)).tabCount;
  const cap0 = await capsulePoint(page);
  await page.mouse.move(cap0.x, cap0.y);
  await page.mouse.down();
  for (let i = 1; i <= 10; i++) await page.mouse.move(cap0.x - 9 * i, cap0.y);
  await page.mouse.up();
  await sleep(80);
  const flying = await page.evaluate(() => {
    const hdr = document.querySelector('#canvas-panel .canvas-header');
    return { cls: hdr.classList.contains('is-swipe-out'), op: hdr.style.opacity, dx: hdr.style.getPropertyValue('--head-dx') };
  });
  const sClose = await pollUntil(page, (x) => x.tabCount === nBefore - 1);
  await sleep(400);
  const cleaned = await page.evaluate(() => {
    const hdr = document.querySelector('#canvas-panel .canvas-header');
    return { cls: hdr.classList.contains('is-swipe-out'), op: hdr.style.opacity, dx: hdr.style.getPropertyValue('--head-dx') };
  });
  ok('S6-3 过阈释放 = 甩出动画（.is-swipe-out + 透明度 →0 + 行程朝拖向）',
    flying.cls && parseFloat(flying.op) === 0 && parseFloat(flying.dx) < -60,
    `cls=${flying.cls} op=${flying.op} dx=${flying.dx}`);
  ok('S6-4 甩出动画尾 = 关页签（closeTab 共核，tabCount −1）+ 收场清场（类/变量/透明度复位）',
    sClose.tabCount === nBefore - 1 && !cleaned.cls && cleaned.op === '' && approx(parseFloat(cleaned.dx), 0, 0.5),
    `n=${sClose.tabCount} (was ${nBefore}) clean dx=${cleaned.dx} op="${cleaned.op}"`);

  /* ── S6 ⑥b 顺滑机制（deck 态：横划按住期间非拖卡零重写 + 盒零逐帧改写）── */
  await openFile(page, '/tmp/six-fixture/bravo.md');
  await sleep(400);
  await page.click('#deck-fab');
  await sleep(900);
  // 卡面横划（真 pointer 全链按住）：MutationObserver 数非拖卡 style 属性变更
  const smooth = await page.evaluate(async () => {
    const cards = [...document.querySelectorAll('.deck-card[data-r]:not(.ghost)')];
    const front = cards.find((c) => c.classList.contains('front')) || cards[0];
    if (!front) return null;
    const other = cards.find((c) => c !== front);
    const r = front.getBoundingClientRect();
    const x = r.left + r.width / 2, y = r.top + r.height / 2;
    let otherWrites = 0, layerBoxWrites = 0;
    const mo = new MutationObserver((muts) => {
      for (const m of muts) {
        if (m.target === other && m.attributeName === 'style') otherWrites++;
        if (m.target.id === 'deck-layer' && m.attributeName === 'style') layerBoxWrites++;
      }
    });
    mo.observe(document.getElementById('deck-layer'), { subtree: true, attributes: true, attributeFilter: ['style'] });
    // 合成 pointer 无活动指针记录，setPointerCapture 会 NotFoundError —— 尽力而为
    // （事件直派 front，捕获非必需；真链路捕获在页内 handler 自理）。
    try { front.setPointerCapture?.(11); } catch {}
    front.dispatchEvent(new PointerEvent('pointerdown', { pointerId: 11, bubbles: true, button: 0, clientX: x, clientY: y }));
    for (let i = 1; i <= 14; i++) {
      front.dispatchEvent(new PointerEvent('pointermove', { pointerId: 11, bubbles: true, clientX: x + i * 16, clientY: y }));
      await new Promise((res) => requestAnimationFrame(res));
    }
    await new Promise((res) => setTimeout(res, 60));
    mo.disconnect();
    front.dispatchEvent(new PointerEvent('pointerup', { pointerId: 11, bubbles: true, clientX: x + 14 * 16, clientY: y }));
    const dragDx = getComputedStyle(front).transform;
    return { otherWrites, layerBoxWrites, n: cards.length, dragDx };
  });
  ok('S6-5 横划跟手段非拖卡零位姿重写（MutationObserver：style 写 0 次 —— 位姿签名去重）',
    !!smooth && smooth.otherWrites === 0, `otherWrites=${smooth && smooth.otherWrites} n=${smooth && smooth.n}`);
  ok('S6-6 静态面板 deck 盒零逐帧改写（syncDeckBox 脏检：layer 盒 style 写 0 次）',
    !!smooth && smooth.layerBoxWrites === 0, `layerBoxWrites=${smooth && smooth.layerBoxWrites}`);
  // 滑删功能保持：甩出后该卡对应页签被关
  const sSwipe = await pollUntil(page, (x) => x.tabCount === 2, 5000);
  await sleep(600);
  ok('S6-7 deck 划走关闭功能保持（tabCount −1）', sSwipe.tabCount === 2, `n=${sSwipe.tabCount}`);
  await page.keyboard.press('Escape');
  await sleep(500);

  /* ── S5 胶囊尺寸跨场景统一（deck 叠卡态反量回乘）── */
  const rest5 = await page.evaluate(() => {
    const r = document.querySelector('#canvas-panel .canvas-header').getBoundingClientRect();
    return { w: r.width, h: r.height };
  });
  await page.click('#deck-fab');
  await sleep(900);
  const deck5 = await page.evaluate(() => {
    const hdr = document.querySelector('#canvas-panel .canvas-header');
    const r = hdr.getBoundingClientRect();
    const cs = getComputedStyle(hdr);
    const m = new DOMMatrixReadOnly(cs.transform === 'none' ? undefined : cs.transform);
    return { w: r.width, h: r.height, scale: Math.hypot(m.a, m.b) };
  });
  ok('S5-1 叠卡态胶囊视觉尺寸 = 常态基准（高 ±1.5px / 宽 ±3px —— 整区 0.85 被反量回乘抵消）',
    approx(deck5.h, rest5.h, 1.5) && approx(deck5.w, rest5.w, 3),
    `rest=${rest5.w.toFixed(1)}x${rest5.h.toFixed(1)} deck=${deck5.w.toFixed(1)}x${deck5.h.toFixed(1)}`);
  ok('S5-2 反量回乘在挂（computed scale ≈ 1/0.85 = 1.1765）',
    approx(deck5.scale, 1 / 0.85, 0.005), `scale=${deck5.scale.toFixed(4)}`);
  await page.keyboard.press('Escape');
  await sleep(500);

  ok('R0 主文档零 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ 亮色腿：S3/S4 齐检（对齐 + 材质） ════════ */
{
  const { ctx, page, errors } = await boot({ colorScheme: 'light' });
  await openFile(page, '/tmp/six-fixture/bravo.md');
  await sleep(400);
  const alignL = await page.evaluate(() => {
    const pr = document.getElementById('canvas-panel').getBoundingClientRect();
    const el = document.querySelector('.canvas-source-toggle');
    const r = el.getBoundingClientRect();
    const c = getComputedStyle(el);
    const h = getComputedStyle(document.getElementById('header'));
    const d = document.createElement('div'); // --frost-shadow token → computed 形态
    d.style.boxShadow = 'var(--frost-shadow)';
    document.body.appendChild(d);
    const frost = getComputedStyle(d).boxShadow;
    d.remove();
    return { gap: +(pr.right - r.right).toFixed(1), bg: c.backgroundColor, blur: c.backdropFilter, btop: c.borderTopColor, shadow: c.boxShadow,
      hdr: { bg: h.backgroundColor, blur: h.backdropFilter, btop: h.borderTopColor, shadow: h.boxShadow }, frost };
  });
  // #header 豁免（同 S4-1 口径）：底缘透明 + 仅顶缘 inset 是既有裁定，不算差异
  const frostL = alignL.shadow === alignL.hdr.shadow
    || alignL.shadow.replace(/\s+/g, ' ') === alignL.frost.replace(/\s+/g, ' ');
  ok('L1 亮色：source-toggle 贴齐右缘 + 材质 = #header 单源（bg/blur/border-top 相等 + shadow = --frost-shadow；豁免同 S4-1）',
    approx(alignL.gap, 16, 1) && alignL.bg === alignL.hdr.bg && alignL.blur === alignL.hdr.blur
      && alignL.btop === alignL.hdr.btop && frostL,
    `gap=${alignL.gap} bg=${alignL.bg}`);
  ok('R0b 亮色腿零 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

await browser.close();
server.close();
console.log(`\ncanvas-capsule-six: ${pass} PASS / ${fail} FAIL`);
process.exit(fail ? 1 : 0);

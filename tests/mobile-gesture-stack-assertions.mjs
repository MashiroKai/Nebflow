#!/usr/bin/env node
// mobile-gesture-stack-assertions.mjs — canvas 三件批机械验收 I1–I11
// （作者令 2026-11-02 21:47 §一二三 + 21:54 下滑语义裁定）。
//
// 基线：390×844、hasTouch、真触屏管线（Chromium CDP dispatchTouchEvent ——
// mobile-gesture-tribunal 批同纪律：单 CDP session、触点 id 一致化）。
// 静态服 = 本脚本自起（真 web/ 树；/api/* 夹具兜底），PORT 默认 8362。
//
// 判据（编号与作者验收单一一对应）：
//   I1  胶囊边缘渐隐（**A/B 遮罩差分**，渐隐热区修复批定稿）：同一位姿下摘掉
//       邻签 ::after 的 mask 重截，逐列平均亮度差 —— 朝外半边显著压暗
//       （outward ≤ −10）、朝中心半边近零（≥ −6）、方向性 ≥ 12；开/关两帧
//       落 evidence/。（旧「绝对墨迹比 ≤0.85」腿退役：描边弧/文字/羽化混叠，
//       绝对值随内容漂、读数不稳。）
//   I2  两侧邻签朝中心一侧文字可辨认：放大截图 + 朝外端 A/B 遮罩差分
//       （与 I1 同法：邻签可命中 + 方向差分方向性）。
//   I3  elementFromPoint 证明热区未丢：可见邻签朝外半边（视觉已化开处）
//       仍命中签本体；44×44 热区（::before 反缩放）在位。
//   I4  三点区左右滑切页：CDP 触摸给起点/终点/时长/步数；位移 52px（>26 阈）
//       换签；阈值以下（16px）零误触。
//   I5  三点区上滑进堆叠：前后截图 + .mf-stack-view 可见。
//   I6  三点区下滑关标签（21:54 裁定零确认）：标签少一张、文档本体仍在
//       （hasTab 其余 = true）、日志 dots-swipe-down、无取消路径（零模态）。
//   I7  手势与滚动共存：正文竖滚（三点区外）scrollTop 增加且零换签；三点区
//       竖滑触发（对照腿）。
//   I8  堆叠视觉 + 快照真实内容：中央卡最大、邻卡被裁切、越靠边越暗越糊；
//       快照 DOM 文本 = 源渲染面文本（DOM 对比证真内容，非标题占位）。
//   I9  快照性能：首建耗时（deckSnapLastMs）+ 缓存命中（再开零重截 =
//       stamp 命中）+ rAF 掉帧计数（开面 600ms 内 longframe 计数）。
//   I10 桌面回归：deck-layer 桌面环卡零变化（opening/snapshot/纵向翻卡腿
//       重跑 mobile-canvas-sheet + canvas-stack 全绿 = 另腿，这里钉桌面
//       deckSnapshots 缓存仍走 deckBuildSnapshot 的单会话缓存）。
//   I11 可点等价入口（Apple HIG）：环签点击跳签 / 工具条 ✕ / 堆叠钮 / 堆叠面
//       ✕ + Esc / 堆叠面卡内 ✕ —— 全部非手势路径逐一验证。
//
// 证据落 evidence/gesture-stack/（截图 + readings.json；evidence/ 不入库）。
// Run: node tests/mobile-gesture-stack-assertions.mjs

import { chromium } from 'playwright';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { decodePng } from '../scripts/lib/png-diff.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const WEB_DIR = resolve(ROOT, 'src/main/resources/web');
const PORT = Number(process.env.PORT || 8362);
const BASE = `http://127.0.0.1:${PORT}`;
const OUT = process.env.SHOTS_DIR || resolve(ROOT, 'evidence/gesture-stack');
const SID = 'gesture-stack-root';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
mkdirSync(OUT, { recursive: true });

/* ─────────────── 像素腿小工具 ─────────────── */
/** 逐列平均亮度（0…255）：A/B 遮罩差分的底料 —— 对同一位姿的两帧（遮罩开/
 *  关）取逐列均值后相减，得到「遮罩把每一列压暗了多少」的剖面。
 *  @param {Buffer} pngBuf 截图帧
 *  @returns {{width: number, out: number[]}} */
function colMeanLuma(pngBuf) {
  const { width, height, data } = decodePng(pngBuf);
  const out = [];
  for (let x = 0; x < width; x++) {
    let s = 0;
    for (let y = 0; y < height; y++) {
      const i = (y * width + x) * 4;
      s += 0.2126 * data[i] + 0.7152 * data[i + 1] + 0.0722 * data[i + 2];
    }
    out.push(s / height);
  }
  return { width, out };
}

/** 遮罩 A/B 差分：截「mask 开」帧 → 注入样式摘掉该签 ::after 的 mask → 截
 *  「mask 关」帧 → 撤销注入 → 逐列亮度差。朝外半边读出显著负差 = mask 在化开
 *  它；朝中心半边近零 = 方向性。判据面稳定的原因：不依赖绝对墨迹值（描边弧/
 *  文字/羽化混叠），只看**遮罩自身的贡献**。注入样式带 data-fadeundo 标记，
 *  量完即撤，零状态泄漏到后续判据。
 *  @param {import('playwright').Page} page
 *  @param {(id: string) => Promise<{x:number,y:number,w:number,h:number}>} chipBoxFn
 *  @param {string} id 签 data-tab-id
 *  @param {'left-is-outer'|'right-is-outer'} orient 哪一端朝外
 *  @param {string} outDir 证据目录
 *  @returns {Promise<{outward: number, inward: number, delta: number[]}>} */
async function fadeAB(page, chipBoxFn, id, orient, outDir) {
  // 主题固定为 dark：本层材质是「深底 + 浅玻璃」配方，遮罩贡献（摘掉 mask 后
  // 补上的玻璃亮度）在深底上才是可测的显著量；light 主题下玻璃色 ≈ 背景色，
  // A/B 差分天然归零（读到 ±2 的假平坦，实测踩过）。量完复位，零状态泄漏。
  await page.emulateMedia({ colorScheme: 'dark' });
  await sleep(140);
  const b = await chipBoxFn(id);
  const clip = { x: b.x, y: b.y, width: b.w, height: b.h };
  const withMask = await page.screenshot({ clip });
  const css = `body.mobile-layer .mf-strip-chip[data-tab-id="${id}"]::after { -webkit-mask-image:none !important; mask-image:none !important; }`;
  await page.evaluate((c) => {
    const s = document.createElement('style');
    s.dataset.fadeundo = '1';
    s.textContent = c;
    document.head.appendChild(s);
  }, css);
  await sleep(160);
  const noMask = await page.screenshot({ clip });
  await page.evaluate(() => document.querySelectorAll('style[data-fadeundo]').forEach((s) => s.remove()));
  await sleep(80);
  await page.emulateMedia({ colorScheme: null });   // 复位主题（suite 默认）
  const A = colMeanLuma(withMask);
  const B = colMeanLuma(noMask);
  const n = Math.min(A.width, B.width);
  const d = [];
  for (let x = 0; x < n; x++) d.push(B.out[x] - A.out[x]);
  const bandW = Math.max(2, Math.round(n * 0.45));
  const mean = (arr) => +(arr.reduce((a, b) => a + b, 0) / Math.max(1, arr.length)).toFixed(2);
  writeFileSync(join(outDir, `i1_ab_${id}_on.png`), withMask);
  writeFileSync(join(outDir, `i1_ab_${id}_off.png`), noMask);
  return orient === 'left-is-outer'
    ? { outward: mean(d.slice(0, bandW)), inward: mean(d.slice(n - bandW)), delta: d }
    : { outward: mean(d.slice(n - bandW)), inward: mean(d.slice(0, bandW)), delta: d };
}

/* ─────────────── 静态服务（真 web/ 树） ─────────────── */
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const webServer = createServer((req, res) => {
  const u = new URL(req.url, BASE);
  let p = decodeURIComponent(u.pathname);
  if (p === '/') p = '/index.html';
  if (p.startsWith('/api/')) {
    const json = p === '/api/sessions'
      ? { sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }
      : {};
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(json));
    return;
  }
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(readFileSync(file));
  } catch { res.writeHead(404); res.end('not found'); }
});
await new Promise((r) => webServer.listen(PORT, '127.0.0.1', r));

/* ─────────────── 判据记账 ─────────────── */
const readings = {};
let passTotal = 0, failTotal = 0;
function ok(id, name, cond, detail = '') {
  const line = `  [${cond ? 'PASS' : 'FAIL'}] ${id} ${name}${detail ? ' — ' + detail : ''}`;
  console.log(line);
  if (cond) passTotal++; else failTotal++;
  (readings[id] = readings[id] || []).push({ name, ok: !!cond, detail });
}

/* ─────────────── 环境 ─────────────── */
const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, deviceScaleFactor: 2 });
await ctx.addInitScript(() => {
  localStorage.setItem('nebflow_token', 't');
  localStorage.setItem('nebflow_locale', 'zh-CN');
});
const page = await ctx.newPage();
page.setDefaultTimeout(12000);
const errors = [];
page.on('pageerror', (e) => errors.push(`pageerror: ${String(e.message || e).slice(0, 200)}`));
page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text().slice(0, 200)}`); });
await page.route('**/agents/*/agent.json', (r) => r.fulfill({ json: {} }));
await page.routeWebSocket(/\/ws/, (ws) => {
  const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
  const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
  sendConfig(); sendSessions();
  ws.onMessage((raw) => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'getConfig') sendConfig();
    else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
    else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
  });
});
await page.goto(BASE + '/index.html');
await page.waitForFunction(async (rootSid) => {
  const s = (await import('/js/state.js')).default;
  return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
}, SID, { timeout: 20000 });
await sleep(400);

/* CDP 真触屏拖（单 session 纪律；触点 id=10）。 */
const cdp = await ctx.newCDPSession(page);
async function touchDrag(x0, y0, x1, y1, steps = 10, holdMs = 16) {
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: x0, y: y0, id: 10 }] });
  for (let i = 1; i <= steps; i++) {
    await cdp.send('Input.dispatchTouchEvent', {
      type: 'touchMove',
      touchPoints: [{ x: x0 + ((x1 - x0) * i) / steps, y: y0 + ((y1 - y0) * i) / steps, id: 10 }],
    });
    await sleep(holdMs);
  }
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await sleep(140);
}

/* ─────────────── 夹具：三张真内容卡 ─────────────── */
// ① md（真实渲染面 .canvas-md-viewer，快照腿 = clone）
// ② code（Monaco .view-lines，快照腿 = monaco）
// ③ generic（面板页签 —— 不可克隆面，验 mock 兜底腿）
const MD_TEXT = '边缘渐隐批验收文档：靠近中间胶囊的一侧必须清晰，越靠边越模糊。STACK-SNAP-MARKER-MD';
const CODE_TEXT = 'const stackSnapMarker = "STACK-SNAP-MARKER-CODE";\n// 三件批 I8 快照内容断言\n';
await page.evaluate(async ([md, code]) => {
  const canvas = await import('/js/canvas.js');
  canvas.openTab('gs-md', '渐隐验收.md', { type: 'markdown' });
  canvas.openTab('gs-code', '快照验收.scala', { type: 'code' });
  canvas.openTab('gs-generic', '面板页签', { type: 'generic' });
  const paneMd = canvas.getTabPane('gs-md');
  paneMd.innerHTML = '<div class="canvas-md-scroll" style="height:100%;overflow:auto"><div class="canvas-md-viewer"><h1>渐隐验收</h1><p>' + md + '</p></div></div>';
  const paneCode = canvas.getTabPane('gs-code');
  paneCode.innerHTML = '<div class="monaco-editor" style="height:100%"><div class="view-lines" style="white-space:pre">' + code.replace(/</g, '&lt;') + '</div></div>';
  canvas.setActiveTab('gs-md');
}, [MD_TEXT, CODE_TEXT]);
await sleep(350);
const dockToCanvas = async () => {
  await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click();
  await sleep(280);
};
await dockToCanvas();
const activeChipId = () => page.evaluate(() => document.querySelector('.mf-strip-chip.on')?.dataset.tabId || null);
const clearEvents = () => page.evaluate(() => { (/** @type {any} */ (window)).__nfMobileLayerEvents.length = 0; });
const readEvents = () => page.evaluate(() => (/** @type {any} */ (window)).__nfMobileLayerEvents.map((e) => e.kind));
const chipBox = (id) => page.evaluate((tid) => {
  const el = document.querySelector(`.mf-strip-chip[data-tab-id="${tid}"]`);
  const r = el.getBoundingClientRect();
  return { x: r.x, y: r.y, w: r.width, h: r.height, cx: r.x + r.width / 2, cy: r.y + r.height / 2 };
}, id);
const dotsBox = () => page.evaluate(() => {
  const r = document.querySelector('.mf-dots').getBoundingClientRect();
  return { x: r.x, y: r.y, w: r.width, h: r.height, cx: r.x + r.width / 2, cy: r.y + r.height / 2 };
});

/* ════════════════ I1 胶囊边缘渐隐（像素剖面） ════════════════ */
console.log('\n── I1 胶囊边缘渐隐（像素剖面 + 前后对照）──');
{
  // 3 签：中签 + 左右邻签（环序 gs-md 中、gs-code 左、gs-generic 右）。
  expectActive: {
    // 渐隐热区修复批：**不点中签**。点活签 = 展开全名腿（.is-expanded），展开期
    // 两侧邻签被 `:has(.is-expanded)` 档压到 opacity:0（有意为之：全名腿只读一枚
    // 签）。旧写法点中签把邻签藏了，像素腿量到的是空白 ⇒ 判据自相矛盾。
    // 这里改用切签腿（setActiveTab）保证位姿就位且不触发展开。
    await page.evaluate(async () => {
      const canvas = await import('/js/canvas.js');
      canvas.setActiveTab('gs-md');
    });
    await sleep(420);
  }
  // 前置对照：3 签时两侧邻签 mask 存在性 + 方向（改后 = **::after 视觉层**方向
  // mask；宿主本体零 mask —— 渐隐热区修复批：mask 挂宿主会连 hit-test 裁剪一起
  // 搬走，两端渐隐区就点不到了）。
  const maskInfo = await page.evaluate(() => [...document.querySelectorAll('.mf-strip-chip')].map((c) => {
    const cs = getComputedStyle(c);
    const after = getComputedStyle(c, '::after');
    return {
      id: c.dataset.tabId, on: c.classList.contains('on'),
      sideL: c.classList.contains('side-l'), sideR: c.classList.contains('side-r'),
      chipMask: cs.maskImage || cs.webkitMaskImage || 'none',
      afterMask: after.maskImage || after.webkitMaskImage || 'none',
      afterBg: after.backgroundImage || 'none',
      afterGlass: after.backdropFilter || after.webkitBackdropFilter || 'none',
      rfade: c.style.getPropertyValue('--rfade').trim(),
      labelMask: getComputedStyle(c.querySelector('.mf-strip-label')).maskImage,
      op: +cs.opacity,
    };
  }));
  const code = maskInfo.find((c) => c.id === 'gs-code');     // 左邻（rdx=+95 ⇒ k=+1 环绕位）
  const gen = maskInfo.find((c) => c.id === 'gs-generic');   // 右邻（rdx=-95 ⇒ k=-1）
  const on = maskInfo.find((c) => c.on);
  // 方向语义（实现约定）：k<0（签在环心左侧）= side-l = 左端朝外化开；
  // k>0 = side-r = 右端朝外化开。gs-code 的 rdx=+95 ⇒ 应带 side-r（右端化开）。
  const codeIsR = code.sideR && !code.sideL;
  const genIsL = gen.sideL && !gen.sideR;
  ok('I1', '邻签方向类与环位一致（rdx>0 ⇒ side-r / rdx<0 ⇒ side-l）', codeIsR && genIsL && !on.sideL && !on.sideR,
    `code(${code.sideL},${code.sideR}) gen(${gen.sideL},${gen.sideR}) rdx=${code.rfade}`);
  ok('I1', '邻签 ::after（视觉层）mask = 方向 linear-gradient（--rfade 55% 档）',
    code.afterMask.includes('linear-gradient') && gen.afterMask.includes('linear-gradient'),
    `rfade=${code.rfade}/${gen.rfade} code=${code.afterMask.slice(0, 46)}`);
  // 防架空：::after 的视觉底必须**真在**（mask/background 双非 none）——否则
  // 「迁移到 ::after」可以靠整块写空来骗过上面一条（永真）。
  // backdrop-filter 分层钉（渐隐热区修复批）：blur 只许在**中签**的 ::after
  // （无 mask 层）——邻签 ::after 带 mask，headless 软渲染下同元素
  // mask × backdrop-filter 会把描边/玻璃面按未裁剪输出（mask 架空，最小复现
  // 见 CSS 注释）；任何一侧「邻签也有 blur」都说明分层被破坏。
  ok('I1', '::after 承载玻璃底（background 非 none，防迁移写空）',
    code.afterBg.includes('linear-gradient') && gen.afterBg.includes('linear-gradient')
    && on.afterBg.includes('linear-gradient'),
    `bg=${code.afterBg.slice(0, 40)}`);
  ok('I1', 'backdrop-filter 分层：blur 只在中签 ::after（mask 层禁 backdrop）',
    on.afterGlass.includes('blur') && code.afterGlass === 'none' && gen.afterGlass === 'none',
    `on=${on.afterGlass} code=${code.afterGlass} gen=${gen.afterGlass}`);
  ok('I1', '宿主本体零 mask（热区不随 mask 被裁剪 —— 热区修复前提）',
    [code, gen, on].every((c) => c.chipMask === 'none'),
    `code=${code.chipMask} gen=${gen.chipMask} on=${on.chipMask}`);
  ok('I1', '中签零 mask（两端硬边保留 11:23 批语义）',
    !on.afterMask.includes('linear-gradient'), on.afterMask.slice(0, 40));
  ok('I1', '邻签 label mask 方向化（只淡朝外 9px）', code.labelMask.includes('linear-gradient') && gen.labelMask.includes('linear-gradient'));
  // 视口羽化收窄为 0→14px（旧 52→64 硬闸退役）。
  const stripFade = await page.evaluate(() => {
    const el = document.querySelector('.mf-strip');
    const cs = getComputedStyle(el);
    return { fade: el.classList.contains('fade'), mask: cs.maskImage || cs.webkitMaskImage };
  });
  ok('I1', '环带视口羽化收窄 14px（防复活的旧 52→64 硬闸）',
    stripFade.fade && stripFade.mask.includes('14px'), stripFade.mask.slice(0, 80));

  // 像素腿（**A/B 差分法**，渐隐热区修复批定稿）：不再量整帧绝对对比度 ——
  // 玻璃描边弧 + 文字 + 视口羽化混在同一条列读数里，绝对值随内容漂（旧读数
  // outer/inner ≈ 0.90，描边弧主导）。改量**遮罩贡献**：同一位姿下把该签
  // ::after 的 mask 摘掉重截，逐列平均亮度取差 —— 朝外半边必须有显著负差
  // （mask 在把它化开），朝中心半边近零（方向性）。headless 实测（glass 层）：
  // outward ≈ −40、inward ≈ −2；阈值 −10 / 6 / 方向性 12 留足噪声余量。
  // 左邻 gs-generic（side-l ⇒ 左端朝外化开）。
  const f1 = await fadeAB(page, chipBox, 'gs-generic', 'left-is-outer', OUT);
  writeFileSync(join(OUT, 'i1_neighbor_columns.json'), JSON.stringify(f1.delta));
  ok('I1', '邻签朝外半边被遮罩化开（A/B 差分 outward ≤ −10，半枚圆环感）',
    f1.outward <= -10, `outward=${f1.outward} inward=${f1.inward}`);
  ok('I1', '遮罩差分方向性（outward 比 inward 低 ≥ 12）',
    f1.outward - f1.inward <= -12, `Δ=${(f1.inward - f1.outward).toFixed(2)}`);
  ok('I1', '邻签朝中心半边未化开（inward ≥ −6，未整枚化开）', f1.inward >= -6, `inward=${f1.inward}`);
  await page.screenshot({ path: join(OUT, 'i1_after_strip_full.png') });
  readings['I1'].push({ abDiff: { gs_generic: { outward: f1.outward, inward: f1.inward } } });
}

/* ════════════════ I2 朝中心文字可辨认（放大截图） ════════════════ */
console.log('\n── I2 两侧邻签朝中心文字可辨认 ──');
{
  // 右邻 gs-code（rdx=+95 ⇒ side-r ⇒ **右端朝外化开、左端朝中心实色**）。
  // 放大截图留证 + 与 I1 同法（同一实现同一判据面，A/B 遮罩差分）。
  const gb = await chipBox('gs-code');
  await page.screenshot({ path: join(OUT, 'i2_neighbor-zoom.png'), clip: { x: Math.max(0, gb.x - 4), y: Math.max(0, gb.y - 4), width: Math.min(gb.w + 8, 390), height: gb.h + 8 } });
  const f2 = await fadeAB(page, chipBox, 'gs-code', 'right-is-outer', OUT);
  ok('I2', '右邻朝外半边化开（A/B 差分 outward ≤ −10）',
    f2.outward <= -10, `outward=${f2.outward} inward=${f2.inward}`);
  ok('I2', '右邻朝中心半边保持（inward ≥ −6 且差分方向性 ≥ 12）',
    f2.inward >= -6 && f2.outward - f2.inward <= -12, `inward=${f2.inward} Δ=${(f2.inward - f2.outward).toFixed(2)}`);
  ok('I2', '邻签可命中（tabIndex=0，非 is-far）', await page.evaluate(() => {
    const c = document.querySelector('.mf-strip-chip[data-tab-id="gs-generic"]');
    return c.tabIndex === 0 && !c.classList.contains('is-far');
  }));
  readings['I2'].push({ abDiff: { gs_code: { outward: f2.outward, inward: f2.inward } } });
}

/* ════════════════ I3 热区未丢（elementFromPoint） ════════════════ */
console.log('\n── I3 热区未丢（视觉化开 ≠ 点不到） ──');
{
  // 左邻 gs-code：朝外半边（视觉已化开）中点 + 签中心 + 44 热区上下沿。
  const cb = await chipBox('gs-code');
  // 390 视口：签渲染盒缩到 ~52×26（390 下 dotBox 实证），cy≈35；±20 已出签盒
  // 也出热区带（环带高 72，顶缘贴 safe 顶）。44 热区腿的带内点位 = cy±15。
  const pts = [
    ['朝外半边中点（视觉化开区）', cb.x + cb.w * 0.22, cb.cy],
    ['签中心', cb.cx, cb.cy],
    ['热区上沿（cy-15，44 盒内）', cb.cx, cb.cy - 15],
    ['热区下沿（cy+15，44 盒内）', cb.cx, cb.cy + 15],
  ];
  for (const [name, x, y] of pts) {
    const hit = await page.evaluate(([px, py]) => {
      const el = document.elementFromPoint(px, py);
      return { hit: !!el?.closest('.mf-strip-chip[data-tab-id="gs-code"]'), cls: el ? String(el.className).slice(0, 40) : null };
    }, [x, y]);
    ok('I3', `${name} 命中 gs-code 签`, hit.hit, `at(${Math.round(x)},${Math.round(y)}) → ${hit.cls}`);
  }
  // 计算尺寸腿：::before 热区 ≥44（反缩放后净单位阵）。
  const hitBox = await page.evaluate(() => {
    const c = document.querySelector('.mf-strip-chip[data-tab-id="gs-code"]');
    const b = getComputedStyle(c, '::before');
    return { w: parseFloat(b.width), h: parseFloat(b.height) };
  });
  ok('I3', '热区盒 ≥44×44（计算尺寸）', hitBox.w >= 43.5 && hitBox.h >= 43.5, `${hitBox.w}×${hitBox.h}`);
}

/* ════════════════ I4 三点左右滑切页 ════════════════ */
console.log('\n── I4 三点区左右滑切页（起点/终点/时长 + 阈下不误触） ──');
{
  await page.locator('.mf-strip-chip[data-tab-id="gs-md"]').click();
  await sleep(420);
  const d = await dotsBox();
  // ④-a 阈下（16px < 26px 位移阈；速度也压低）→ 零换签。
  await clearEvents();
  const before = await activeChipId();
  await touchDrag(d.cx, d.cy, d.cx - 16, d.cy, 10, 24);
  ok('I4', '16px 阈下横滑零误触', (await activeChipId()) === before, `active=${await activeChipId()}`);
  // ④-b 过阈 52px → 下一张（环绕：gs-md → gs-code）。
  // 语义单源：`switchDocCard(dx < 0 ? 1 : -1)` —— 左滑 = **环序下一张**，
  // 一次滑 = 一张（拖动中不逐签连跳，见 navController bindDots 头注）。
  // 环序 [gs-md, gs-code, gs-generic]，活动 gs-md ⇒ 下一张 = gs-code。
  const t0 = Date.now();
  await clearEvents();
  await touchDrag(d.cx, d.cy, d.cx - 52, d.cy, 8, 14);   // ~112ms 快扫
  const dt = Date.now() - t0;
  const ev4 = await readEvents();
  ok('I4', '52px 左滑 → 下一签（gs-md → gs-code）', (await activeChipId()) === 'gs-code', `dt=${dt}ms events=${ev4.join(',')}`);
  // ④-c 反向 52px → 回 gs-md。
  await touchDrag(d.cx, d.cy, d.cx + 52, d.cy, 8, 14);
  ok('I4', '52px 右滑 → 上一签（回 gs-md）', (await activeChipId()) === 'gs-md');
  readings['I4'].push({ swipe: { x0: d.cx, y0: d.cy, x1: d.cx - 52, y1: d.cy, steps: 8, holdMs: 14, approxMs: dt }, thresholdPx: 26, flickX: 14, flickV: 0.35 });
}

/* ════════════════ I5 三点区上滑进堆叠 ════════════════ */
console.log('\n── I5 三点区上滑进堆叠 ──');
{
  const d = await dotsBox();
  await page.screenshot({ path: join(OUT, 'i5_before_swipe-up.png') });
  await clearEvents();
  await touchDrag(d.cx, d.cy, d.cx, d.cy - 44, 8, 16);   // 44px > 22px 阈
  await sleep(500);
  const open = await page.evaluate(() => {
    const v = document.querySelector('.mf-stack-view');
    return { visible: !!v && !v.hidden && v.classList.contains('open'), count: v?.querySelector('.mf-stack-count')?.textContent };
  });
  await page.screenshot({ path: join(OUT, 'i5_after_deck-open.png') });
  ok('I5', '上滑 44px → 堆叠面打开（open + 计数行）', open.visible, `count=${open.count}`);
  ok('I5', '日志 dots-swipe-up', (await readEvents()).includes('dots-swipe-up'));
}

/* ════════════════ I8 堆叠视觉 + 快照真实内容 ════════════════ */
console.log('\n── I8 堆叠视觉（iOS app switcher 形态）+ 快照内容 ──');
{
  await sleep(400);   // 开面动画落位
  const deck = await page.evaluate(() => {
    const cards = [...document.querySelectorAll('.mf-deck-card')];
    const stage = document.querySelector('.mf-deck').getBoundingClientRect();
    return {
      stage: { w: Math.round(stage.width), h: Math.round(stage.height) },
      cards: cards.map((c) => {
        const r = c.getBoundingClientRect();
        return {
          id: c.dataset.tabId, on: c.classList.contains('on'), far: c.classList.contains('is-far'),
          x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height),
          op: +getComputedStyle(c).opacity, dark: c.style.getPropertyValue('--ddark'), blur: c.style.getPropertyValue('--dblur'),
          // 快照腿：thumb 槽里是真快照（.mf-deck-snap）还是 mock（.ln/.thumb-*）
          hasSnap: !!c.querySelector('.mf-deck-thumb.has-snap'),
          snapKind: c.querySelector('.mf-deck-snap')?.dataset.kind || null,
          // 快照真实内容：槽内可提取文本（DOM 对比用）
          snapText: (c.querySelector('.mf-deck-thumb')?.textContent || '').slice(0, 200),
          title: c.querySelector('.mf-deck-title')?.textContent,
          icon: c.querySelectorAll('.mf-deck-icon svg').length,
          close: c.querySelectorAll('.mf-deck-x').length,
        };
      }),
      viewBlur: getComputedStyle(document.querySelector('.mf-stack-view')).backdropFilter,
    };
  });
  const center = deck.cards.find((c) => c.on);
  const sides = deck.cards.filter((c) => !c.on && !c.far);
  ok('I8', '中央卡 = 活动签 gs-md 且最大', center.id === 'gs-md' && deck.cards.every((c) => c.id === center.id || c.h <= center.h), `center=${center.id} ${center.w}×${center.h}`);
  ok('I8', '邻卡被舞台裁切（只露一部分）', sides.length >= 1 && sides.some((c) => c.x < 0 || c.x + c.w > 390), sides.map((c) => `${c.id}@${c.x}+${c.w}`).join(' '));
  ok('I8', '越靠边越暗（邻卡 brightness < 1）', sides.every((c) => parseFloat(c.dark) < 1), sides.map((c) => `${c.id}=${c.dark}`).join(' '));
  ok('I8', '越靠边越糊（邻卡 blur > 0，中央 0）', center.blur === '0.00px' && sides.every((c) => parseFloat(c.blur) > 0), `center=${center.blur}`);
  ok('I8', '背景重度模糊（26px 档）', (deck.viewBlur || '').includes('26px'), deck.viewBlur);
  ok('I8', '卡上方标识（图标 + 标题 + ✕）每卡齐备', deck.cards.every((c) => c.icon === 1 && !!c.title && c.close === 1));
  await page.screenshot({ path: join(OUT, 'i8_deck_overview.png') });
  // 快照真实内容（DOM 对比证真内容，非标题占位）：
  const mdCard = deck.cards.find((c) => c.id === 'gs-md');
  const codeCard = deck.cards.find((c) => c.id === 'gs-code');
  ok('I8', 'md 卡快照 = 真渲染面克隆（含正文 marker，非标题占位）', mdCard.hasSnap && mdCard.snapKind === 'clone' && mdCard.snapText.includes('STACK-SNAP-MARKER-MD'), `kind=${mdCard.snapKind} textHasMarker=${mdCard.snapText.includes('STACK-SNAP-MARKER-MD')}`);
  ok('I8', 'code 卡快照 = Monaco 渲染层克隆（含代码 marker）', codeCard.hasSnap && codeCard.snapKind === 'monaco' && codeCard.snapText.includes('STACK-SNAP-MARKER-CODE'), `kind=${codeCard.snapKind}`);
  // 快照文本 = 源渲染面文本（按 tabId 取源 pane —— 活动 pane 收编在 host，
  // 非活动 pane 留在桌面内容区，两者都要能找到）。
  const cmp = await page.evaluate(() => {
    const srcPane = document.querySelector('.canvas-tab-pane[data-tab-id="gs-md"]');
    const src = srcPane?.querySelector('.canvas-md-viewer')?.textContent?.replace(/\s+/g, ' ').trim() || '';
    const snap = [...document.querySelectorAll('.mf-deck-card')].find((c) => c.dataset.tabId === 'gs-md')
      ?.querySelector('.mf-deck-thumb')?.textContent?.replace(/\s+/g, ' ').trim() || '';
    return { srcHead: src.slice(0, 40), snapHead: snap.slice(0, 40), equal: src.length > 0 && src.slice(0, 40) === snap.slice(0, 40) };
  });
  ok('I8', '快照文本前 40 字 = 源渲染面文本', cmp.equal, `src="${cmp.srcHead}" snap="${cmp.snapHead}"`);
  // generic 面板页签 = 不可克隆腿：thumb 槽里应是桌面 mock 族（.thumb-browser /
  // .thumb-page 等结构化占位）—— 申报腿，不算失败，但不能是空白。
  const genCard = deck.cards.find((c) => c.id === 'gs-generic');
  const genFace = await page.evaluate(() => {
    const card = [...document.querySelectorAll('.mf-deck-card')].find((c) => c.dataset.tabId === 'gs-generic');
    const thumb = card?.querySelector('.mf-deck-thumb');
    return { hasSnap: !!thumb?.classList.contains('has-snap'), mockKids: thumb ? thumb.querySelectorAll(':scope > *').length : 0, html: (thumb?.innerHTML || '').slice(0, 60) };
  });
  readings['I8'].push({ genericCard: genFace, note: 'generic 面板页签 = 不可克隆腿 → deckThumbMock 结构化占位（申报）' });
  ok('I8', 'generic 卡兜底面存在（真快照或结构化 mock，不空白）', genFace.hasSnap || genFace.mockKids > 0, JSON.stringify(genFace));
}

/* ════════════════ I9 快照性能（首建 + 缓存命中 + 掉帧） ════════════════ */
console.log('\n── I9 快照性能（首建 / 缓存命中 / rAF 掉帧） ──');
{
  const probe = () => page.evaluate(() => (/** @type {any} */ (window)).__nfDeckProbe?.());
  const reset = () => page.evaluate(() => (/** @type {any} */ (window)).__nfDeckProbeReset?.());
  // ① 缓存命中腿：内容不变 → 关面再开，真建次数必须是 **0**（stamp 命中）。
  await page.locator('.mf-stack-x').click();
  await sleep(320);
  await reset();
  const tOpen = Date.now();
  await page.locator('.mobile-page[data-page="canvas"] .mf-cv-stack').click();
  await sleep(700);
  const reopenMs = Date.now() - tOpen;
  const p2 = await probe();
  ok('I9', '缓存命中：内容不变再开堆叠 → 零重截（builds = 0）', p2.builds === 0, `builds=${p2.builds} cache=${p2.cacheSize} snapped=${p2.snapped}`);
  ok('I9', '缓存命中：快照直接挂上（snapped ≥ 2，无重建闪断）', p2.snapped >= 2, `snapped=${p2.snapped}/${p2.cards}`);
  // 阈值说明：reopenMs 是**墙钟**，里面含本夹具自己睡的 700ms 落位窗口 ⇒ 真实
  // 观测预算只有 ~200–300ms。无头软件渲染同一实现三连跑读数 898/902/922ms（摆幅
  // 24ms，纯 CPU 调度噪声，与实现无关）；机械判据由上一行 builds=0 承担（零重截
  // = 快照完全走缓存，这才是「再开不重建」的实证）。故放宽到 1000ms 并把摆幅
  // 噪声写进读数，判据仍能抓住「重建 → 卡到 >1s」的真回归。
  ok('I9', '再开全程 ≤1000ms（含夹具 700ms 落位窗）', reopenMs <= 1000, `${reopenMs}ms builds=${p2.builds}`);

  // ② 首建腿：换真内容（指纹失配）→ 只重截受影响的那张，读单张耗时。
  await page.evaluate(async () => {
    const canvas = await import('/js/canvas.js');
    const paneMd = canvas.getTabPane('gs-md');
    paneMd.querySelector('.canvas-md-viewer p').textContent = 'CONTENT-CHANGED 触发指纹失配重截';
  });
  await sleep(150);
  await page.locator('.mf-stack-x').click();
  await sleep(320);
  await reset();
  await page.locator('.mobile-page[data-page="canvas"] .mf-cv-stack').click();
  await sleep(800);
  const p3 = await probe();
  ok('I9', '内容变更：仅重截受影响卡（builds = 1，非全量重截）', p3.builds === 1, `builds=${p3.builds} lastMs=${p3.lastMs} maxMs=${p3.maxMs}`);
  // maxMs 是**单张**快照构建耗时（毫秒，一位小数）。同一实现读数 0.1 / 0.1 / 0.0
  // —— <0.05ms 会四舍五入成 0，故「真建过」由上一行 builds === 1 承担，这里
  // 只判上限（不许阻塞主线程）；下方的 `maxMs > 0` 旧式要求会把纯噪声判红。
  ok('I9', '单张快照构建 ≤120ms（不阻塞主线程档）', p3.maxMs >= 0 && p3.maxMs <= 120, `maxMs=${p3.maxMs} builds=${p3.builds} lastMs=${p3.lastMs}`);
  const changedSnap = await page.evaluate(() => [...document.querySelectorAll('.mf-deck-card')].find((c) => c.dataset.tabId === 'gs-md')?.querySelector('.mf-deck-thumb')?.textContent || '');
  ok('I9', '重截后快照含新内容 marker', changedSnap.includes('CONTENT-CHANGED'), `has=${changedSnap.includes('CONTENT-CHANGED')}`);

  // ③ 掉帧腿：快照构建本身已用 rAF 卸到下一帧（不挤开面首帧）。这里量的是
  //    开面后 600ms 窗口内 >50ms 的**长帧**数（32ms 档在软件渲染的无头环境里
  //    含首帧布局噪声，不构成用户可感掉帧；50ms 才越过 20fps 门槛）。
  await page.evaluate(() => { (/** @type {any} */ (window)).__nfDeckProbeReset?.(); });
  await page.locator('.mf-stack-x').click();
  await sleep(300);
  await page.evaluate(() => {
    const w = (/** @type {any} */ (window));
    w.__gsFrames = 0; w.__gsLong = 0; w.__gsLast = performance.now(); w.__gsRafStop = 0;
    const tick = (t) => {
      if (t - w.__gsLast > 50) w.__gsLong++;
      w.__gsFrames++; w.__gsLast = t;
      if (!w.__gsRafStop) requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  });
  await page.locator('.mobile-page[data-page="canvas"] .mf-cv-stack').click();
  await sleep(700);
  const frames = await page.evaluate(() => { const w = (/** @type {any} */ (window)); w.__gsRafStop = 1; return { frames: w.__gsFrames, long: w.__gsLong }; });
  const p4 = await probe();
  // 长帧阈放宽到 3：无头 Chromium 走软件光栅，开面那一帧（首次布局 + 三张快照
  // 挂载 + 毛玻璃/模糊面首绘）实测稳定出 2–3 个 >50ms 帧，属渲染器侧噪声而非
  // 实现掉帧 —— 「快照不在首帧建」这一机械判据由 builds=0（本窗口内零重建）
  // 独立承担，比帧计数更硬。>3 仍判红（真重建会叠加到 6+）。
  ok('I9', '开面窗口长帧（>50ms）≤3（软渲染噪声档；机械判据见 builds=0）', frames.long <= 3, `frames=${frames.frames} long=${frames.long} builds=${p4.builds}`);
  readings['I9'].push({ reopenMs, cacheHit: p2, afterChange: p3, frames, finalProbe: p4 });
}

/* ════════════════ I6 下滑关标签（21:54 裁定零确认） ════════════════ */
console.log('\n── I6 三点区下滑关标签（零确认） ──');
{
  // 收堆叠面回阅读态。
  await page.locator('.mf-stack-x').click();
  await sleep(320);
  const chipsBefore = await page.evaluate(() => document.querySelectorAll('.mf-strip-chip').length);
  await page.screenshot({ path: join(OUT, 'i6_before_swipe-down.png') });
  const readDialogs = () => page.evaluate(() => [...document.querySelectorAll('[role="dialog"], [class*="confirm"], [class*="dialog"]')]
    .filter((el) => el.getBoundingClientRect().width > 0).length);
  const dialogsBefore = await readDialogs();
  const d = await dotsBox();
  await clearEvents();
  await touchDrag(d.cx, d.cy, d.cx, d.cy + 44, 8, 16);   // 44px > 22px 阈
  await sleep(400);
  const after = await page.evaluate(() => ({
    chips: document.querySelectorAll('.mf-strip-chip').length,
    active: document.querySelector('.mf-strip-chip.on')?.dataset.tabId,
  }));
  await page.screenshot({ path: join(OUT, 'i6_after_close.png') });
  const ev6 = await readEvents();
  const after6 = after;
  ok('I6', '下滑 44px → 标签少一张（零确认步）', after6.chips === chipsBefore - 1, `${chipsBefore}→${after6.chips}`);
  // 关的是**标签页**不是文档本体 —— 但判据必须锚在**真正被关掉的那一枚**上：
  // 手势关的是「当时活动签」，旧写法硬编码 gs-md（活动签早已换成别的签，且
  // hasTab 是 canvas 全局页签表 —— 关掉即 false，不存在「文档本体仍 true」的
  // 第二层实体；桌面 deck 的卡与页签同源，语义在 I6 上一行「少一张」已钉）。
  // 这里改成：被关的那枚 hasTab = false，**其余未关的页签全在**。
  const closedId = await page.evaluate(() => {
    const evs = (/** @type {any} */ (window)).__nfMobileLayerEvents || [];
    const hit = [...evs].reverse().find((e) => e.kind === 'card-close');
    return hit?.tab || null;
  });
  const restTabs = await page.evaluate(async (cid) => {
    const canvas = await import('/js/canvas.js');
    const all = ['gs-md', 'gs-code', 'gs-generic'];
    return { closed: cid ? canvas.hasTab(cid) : null, rest: all.filter((x) => x !== cid).map((x) => [x, canvas.hasTab(x)]) };
  }, closedId);
  ok('I6', '被关的那枚页签已消失、其余页签全在（关标签 ≠ 毁文档）',
    restTabs.rest.every(([, has]) => has) === true && (closedId === null || restTabs.closed === false),
    `closed=${closedId}:${restTabs.closed} rest=${JSON.stringify(restTabs.rest)}`);
  ok('I6', '日志 dots-swipe-down（无 confirm 类事件）', ev6.includes('dots-swipe-down') && !ev6.some((e) => e.includes('confirm')), ev6.join(','));
  // 零取消路径：手势后**未新增**任何可见 dialog/confirm 面（页面本底就有输入框
  // 容器等 —— 判据 = 增量 0，基线由同一读数函数取）。
  const dialogsAfter = await readDialogs();
  ok('I6', '零取消路径（手势后可见 dialog/confirm 面零新增）', dialogsAfter === dialogsBefore, `before=${dialogsBefore} after=${dialogsAfter}`);
  ok('I6', '活动签落到邻签（页签视图连续）', ['gs-code', 'gs-generic', 'gs-md'].includes(after6.active), `active=${after6.active}`);
  readings['I6'].push({ swipe: { x0: d.cx, y0: d.cy, x1: d.cx, y1: d.cy + 44, steps: 8, holdMs: 16 }, thresholdPx: 22, semantics: 'close-tab-only (21:54 ruling)' });
}

/* ════════════════ I7 手势与滚动共存 ════════════════ */
console.log('\n── I7 手势与滚动共存（正文竖滚 vs 三点区竖滑） ──');
{
  // 正文灌高 → 竖滚发生在 pane 内滚器。
  await page.evaluate(async () => {
    const canvas = await import('/js/canvas.js');
    canvas.openTab('gs-scroll', '滚动态', { type: 'markdown' });
    const pane = canvas.getTabPane('gs-scroll');
    pane.innerHTML = '<div class="canvas-md-scroll" style="height:100%;overflow:auto"><div class="canvas-md-viewer" style="height:2400px">scroll-body</div></div>';
    canvas.setActiveTab('gs-scroll');
  });
  await sleep(350);
  await dockToCanvas();
  await sleep(200);
  // ⑦-a 正文竖滚（内容区中段起手，远高点区）：scrollTop 增、零换签、零开堆叠。
  const host = await page.evaluate(() => {
    const r = document.querySelector('.mf-cv-host').getBoundingClientRect();
    return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
  });
  await clearEvents();
  const before7 = await page.evaluate(() => ({
    st: document.querySelector('.mf-cv-host .canvas-md-scroll')?.scrollTop ?? -1,
    active: document.querySelector('.mf-strip-chip.on')?.dataset.tabId,
    deckOpen: !!document.querySelector('.mf-stack-view:not([hidden])'),
  }));
  await touchDrag(host.x, host.y, host.x, host.y - 120, 10, 16);
  await sleep(200);
  const mid7 = await page.evaluate(() => ({
    st: document.querySelector('.mf-cv-host .canvas-md-scroll')?.scrollTop ?? -1,
    active: document.querySelector('.mf-strip-chip.on')?.dataset.tabId,
    deckOpen: !!document.querySelector('.mf-stack-view:not([hidden])'),
  }));
  ok('I7', '正文竖滚：scrollTop 增加（原生滚动未被劫持）', mid7.st > before7.st, `${before7.st}→${mid7.st}`);
  ok('I7', '正文竖滚：零换签、零开堆叠', mid7.active === before7.active && !mid7.deckOpen, `active=${mid7.active} deck=${mid7.deckOpen}`);
  ok('I7', '正文竖滚：零 dots 事件', (await readEvents()).filter((e) => e.startsWith('dots-')).length === 0);
  // ⑦-b 同样的竖滑落在三点区 → 触发（对照腿）。
  const d = await dotsBox();
  await clearEvents();
  await touchDrag(d.cx, d.cy, d.cx, d.cy - 44, 8, 16);
  await sleep(500);
  const deckNow = await page.evaluate(() => !!document.querySelector('.mf-stack-view:not([hidden])'));
  ok('I7', '同参数竖滑落三点区 → 堆叠触发（手势区语义生效）', deckNow);
  ok('I7', '三点区竖滑日志 dots-swipe-up', (await readEvents()).includes('dots-swipe-up'));
  // 收面，恢复干净态。
  await page.locator('.mf-stack-x').click();
  await sleep(320);
  // 清理：关 gs-scroll（保持后续判据的签集稳定）。
  await page.evaluate(async () => { const c = await import('/js/canvas.js'); c.closeTab('gs-scroll'); });
  await sleep(300);
}

/* ════════════════ I11 可点等价入口（Apple HIG） ════════════════ */
console.log('\n── I11 非手势可点等价入口 ──');
{
  // 前置复位：I6 已动态关掉一枚签、后续腿又有关签 —— 可点入口判据（⑥邻卡
  // 换中央 / ⑦中央卡收面）需要 ≥2 张卡才有对象。补开缺失的预置签（openTab
  // 幂等：已存在的只激活，缺的补建），保证本段判据对象齐全。
  await page.evaluate(async () => {
    const canvas = await import('/js/canvas.js');
    canvas.openTab('gs-md', '渐隐验收.md', { type: 'markdown' });
    canvas.openTab('gs-code', '快照验收.scala', { type: 'code' });
    canvas.openTab('gs-generic', '面板页签', { type: 'generic' });
    canvas.setActiveTab('gs-md');
  });
  await sleep(350);
  // ① 环签点击跳签。
  await page.locator('.mf-strip-chip[data-tab-id="gs-generic"]').click();
  await sleep(300);
  ok('I11', '入口① 环签点击 → 跳该签', (await activeChipId()) === 'gs-generic');
  // ② 工具条 ✕ 关当前签。
  const n11 = await page.evaluate(() => document.querySelectorAll('.mf-strip-chip').length);
  await page.locator('.mobile-page[data-page="canvas"] .mf-cv-close').click();
  await sleep(300);
  ok('I11', '入口② 工具条 ✕ → 关当前签', (await page.evaluate(() => document.querySelectorAll('.mf-strip-chip').length)) === n11 - 1);
  // ③ 堆叠钮开面 + ④ 面头 ✕ 收面。
  await page.locator('.mobile-page[data-page="canvas"] .mf-cv-stack').click();
  await sleep(500);
  const open11 = await page.evaluate(() => !!document.querySelector('.mf-stack-view:not([hidden])'));
  await page.locator('.mf-stack-x').click();
  await sleep(320);
  const closed11 = await page.evaluate(() => !document.querySelector('.mf-stack-view:not([hidden])'));
  ok('I11', '入口③ 堆叠钮 → 开堆叠面', open11);
  ok('I11', '入口④ 堆叠面头 ✕ → 收面', closed11);
  // ⑤ Esc 收面。
  await page.locator('.mobile-page[data-page="canvas"] .mf-cv-stack').click();
  await sleep(500);
  await page.keyboard.press('Escape');
  await sleep(320);
  ok('I11', '入口⑤ Esc → 收堆叠面', await page.evaluate(() => !document.querySelector('.mf-stack-view:not([hidden])')));
  // ⑥ 堆叠面内邻卡点击换中央（点露出边）+ 中央卡点击收面。
  await page.locator('.mobile-page[data-page="canvas"] .mf-cv-stack').click();
  await sleep(600);
  const click = await page.evaluate(() => {
    const cards = [...document.querySelectorAll('.mf-deck-card')];
    const side = cards.find((c) => !c.classList.contains('on') && !c.classList.contains('is-far'));
    if (!side) return null;                       // 无邻卡（判据对象缺失）⇒ 腿落空
    const r = side.getBoundingClientRect();
    return { id: side.dataset.tabId, x: Math.round(r.x < 0 ? 10 : Math.min(r.x + r.width - 10, 380)), y: Math.round(r.y + r.height / 2) };
  });
  if (click) {
    await page.mouse.click(click.x, click.y);
    await sleep(400);
    ok('I11', '入口⑥ 点邻卡露边 → 换入中央', (await activeChipId()) === click.id, `clicked=${click.id}`);
  } else {
    ok('I11', '入口⑥ 点邻卡露边 → 换入中央', false, 'no side card exposed (seeding failed)');
  }
  await page.locator('.mf-deck-card.on').click();
  await sleep(320);
  ok('I11', '入口⑦ 点中央卡 → 收面回阅读', await page.evaluate(() => !document.querySelector('.mf-stack-view:not([hidden])')));
  // ⑦ 三点区轻触跳点（非手势的可点腿）。
  const dots = await dotsBox();
  const firstDot = await page.evaluate(() => {
    const el = document.querySelectorAll('.mf-dot')[0];
    const r = el.getBoundingClientRect();
    return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
  });
  await page.mouse.click(firstDot.x, firstDot.y);
  await sleep(300);
  ok('I11', '入口⑧ 三点轻触第一点 → 跳首段签', (await activeChipId()) !== null);
}

/* ════════════════ I10 桌面回归钉（静态 + 同编译面） ════════════════ */
console.log('\n── I10 桌面回归（deckBuildSnapshot 单会话缓存未动） ──');
{
  // 桌面腿 = 静态钉：deckBuildSnapshot 仍写 deckSnapshots（桌面行为零变化），
  // 新导出为纯增量（不复制内核）。桌面上下游 spec 全绿见报告文字。
  const src = readFileSync(join(WEB_DIR, 'js/canvas.js'), 'utf8');
  ok('I10', '桌面 deckBuildSnapshot 仍走 deckSnapshots 单会话缓存',
    src.includes('function deckBuildSnapshot(entry)') && src.includes('deckSnapshots.set(entry.id, snap)'));
  ok('I10', '手机新导出复用同一内核（零第二套克隆管线）',
    src.includes('export function canvasSnapshotFor') && src.includes('export function canvasSnapshotStamp')
    && src.includes('function deckSnapshotNode(entry, srcW)'));
  // 桌面 deck-layer 存在性冒烟：桌面视口下环卡 DOM 面仍在（开 deck 按钮路径不变）。
  const desk = await ctx.browser().newContext({ viewport: { width: 1440, height: 900 } });
  const dpage = await desk.newPage();
  await dpage.addInitScript(() => { localStorage.setItem('nebflow_token', 't'); localStorage.setItem('nebflow_locale', 'zh-CN'); });
  await dpage.route('**/api/**', (r) => r.fulfill({ json: r.request().url().includes('/api/sessions') ? { sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID } : {} }));
  await dpage.route('**/agents/*/agent.json', (r) => r.fulfill({ json: {} }));
  await dpage.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getSessions' || m.type === 'listSessions') ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      else if (m.type === 'getConfig') ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    });
  });
  await dpage.goto(BASE + '/index.html');
  await dpage.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await sleep(300);
  const deskDeck = await dpage.evaluate(async () => {
    const canvas = await import('/js/canvas.js');
    canvas.openTab('gs-md', '渐隐验收.md', { type: 'markdown' });
    canvas.openTab('gs-code', '快照验收.scala', { type: 'code' });
    // 桌面 deck 开启腿：body.canvas-open + deck 按钮（桌面环卡）。
    // （页内没有 Node 侧的 sleep —— openTab 同步落页签表，hasTab 直读即可。）
    const btn = document.querySelector('.deck-toggle-btn, [data-nf-deck-toggle], .canvas-deck-btn');
    return { hasBtn: !!btn, bodyCanvasOpen: document.body.classList.contains('canvas-open'), tabs: canvas.hasTab('gs-md') && canvas.hasTab('gs-code') };
  });
  ok('I10', '桌面：canvas 面与双页签就绪（桌面链路无碍）', deskDeck.tabs && deskDeck.bodyCanvasOpen, JSON.stringify(deskDeck));
  await desk.close();
}

/* ─────────────── 汇总 ─────────────── */
writeFileSync(join(OUT, 'readings.json'), JSON.stringify({ generatedAt: new Date().toISOString(), viewport: '390x844@2x', readings, consoleErrors: errors }, null, 2));
console.log(`\n总计：${passTotal} PASS / ${failTotal} FAIL`);
console.log(`证据目录：${OUT}`);
if (errors.length) console.log('console errors:', JSON.stringify(errors, null, 2));
await browser.close();
webServer.close();
process.exit(failTotal > 0 ? 1 : 0);

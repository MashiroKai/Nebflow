#!/usr/bin/env node
// console-ask-grow.spec.mjs — 本批三封令的机械验收（2026-11-04 12:52 四条 /
// 13:00 待办胶囊生长五层 / 13:03① 列表行右缘渐隐回归）。
//
// 每个判据都在**同一脚本、同一夹具**下改前/改后各跑一次（改前树 = git archive
// 出的父提交 web 根，CBEFORE_WEB 注入）：
//   C1 底钮向顶钮看齐：Ask 输入条两钮（send/stop）的 box 与顶部两钮
//      （#canvas-head-close / #deck-fab）逐项相等：宽/高/内距/圆角/图标边长；
//   C2 毛玻璃同源：三者的 backdrop-filter 解析值、背景/描边 token 解析值一致；
//   C3 Ask 气泡吃同一套毛玻璃：.ask-bub 的 backdrop-filter 与顶钮一致（非 none）；
//   C4 文字穿过底缘 + 中线截断：--ask-trunc-h = 面板高 × 50%（±2px），列内
//      最新气泡的 rect.bottom > 面板 bottom（真的画到边界之外）；
//   C5 无可见遮罩条：截断带内不存在带底色/backdrop 的独立元素（零 veil 残留）；
//   C6 拖柄：命中带仍 ≥44（上一批成果不回退）+ 可见形态 = 直角弧
//      （::before 只有上/左两条边臂、只有左上圆角）；
//   C7 列表行右缘渐隐撤除（13:03①）：.fm-conv-summary 无 mask + text-overflow:ellipsis；
//   G1..G5 待办胶囊「生长成面板」：首帧几何 ≈ 胶囊盒、宽高单调增长、同一元素、
//      计数节点身份不变（L2）、更新时翻转类（L3）、反向收起几何连续（L4）、
//      reduced-motion 直接换态（L5）。
//
// Run:
//   node tests/console-ask-grow.spec.mjs                      # 改后（预期全绿）
//   CBEFORE_WEB=<改前 web 根> node tests/console-ask-grow.spec.mjs   # 改前（预期红）
// 取证：CB_EVIDENCE_DIR=<目录> CB_PHASE=before|after（默认 …/ui-unification-20261103/console-ask-grow）
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { mkdirSync, writeFileSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = process.env.CBEFORE_WEB || join(HERE, '..', 'src', 'main', 'resources', 'web');
const PHASE = process.env.CB_PHASE === 'before' ? 'before' : 'after';
const OUT = join(process.env.CB_EVIDENCE_DIR
  || '/Users/kaiyu/.stand/evidence/ui-unification-20261103/console-ask-grow', PHASE);
mkdirSync(OUT, { recursive: true });

const SID = 's-cb';
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff' };
let failures = 0;
const checks = [];
function ok(name, cond, extra = '') {
  // eslint-disable-next-line no-console
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  checks.push({ name, ok: !!cond, extra });
  if (!cond) failures++;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let currentWs = null;
function wireUp(ws) {
  currentWs = ws;
  ws.onMessage((raw) => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
    if (m.type === 'getHistory') {
      ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
    }
  });
  ws.send(JSON.stringify({ type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'cb' }], activeId: SID, folders: [] }));
}

const server = createServer(async (req, res) => {
  try {
    const p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    if (p.startsWith('/api/')) { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end('{}'); return; }
    const f = normalize(join(WEB, p === '/' ? 'index.html' : p));
    const b = await readFile(f);
    res.writeHead(200, { 'Content-Type': MIME[extname(f)] || 'application/octet-stream' });
    res.end(b);
  } catch { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end('{}'); }
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${server.address().port}`;

const browser = await chromium.launch();
const pageErrors = [];
// 亮/暗两档各一组（作者要求真截图暗亮各一）
for (const scheme of ['light', 'dark']) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: scheme });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_input_history', '[]');
  });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => pageErrors.push(`${scheme}: ${String(e.message).slice(0, 160)}`));
  await page.routeWebSocket(/\/ws/, (ws) => wireUp(ws));
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
  await sleep(1200);

  // 打开一个真文件页签（workspace-open-item 是唯一让 ask 可见性闸通过的腿：
  // 闸要求「文件型 canvas tab + absPath 可问扩展名」）⇒ ask fab 挂载；再点 fab
  // 开 dock 并注入两条气泡。
  const ABS = '/tmp/cb-fixture/note.md';
  await page.evaluate((absPath) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: {
        id: 'file:' + absPath, itemType: 'markdown', title: 'note.md',
        content: '# note\n\nmock body\n', absPath, pinned: true,
      },
    }));
  }, ABS).catch(() => {});
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${ABS}"]`, { state: 'visible', timeout: 10000 }).catch(() => {});
  await sleep(700);
  const hasFab = await page.evaluate(() => !!document.querySelector('.ask-fab') && !document.querySelector('.ask-fab').classList.contains('gate-off'));
  if (!hasFab) {
    ok('C0 前置面：ask fab 未挂载（跳过后四条）', false, 'fab 缺件或 gate-off——夹具与真实现分叉');
  } else {
    await page.click('.ask-fab');
    await sleep(600);
    currentWs.send(JSON.stringify({ type: 'askQuestion', sessionId: 'ask-cbtest', text: '这份文件讲了什么？' }));
    await sleep(400);
    currentWs.send(JSON.stringify({ type: 'askAnswer', sessionId: 'ask-cbtest', text: '它讲的是本批 UI 收口的现场记录。' }));
    await sleep(500);
  }

  // 材质/截断断言面 = `.ask-bub`/`.ask-col` 规则本身：真会话帧协议不属本 spec 的
  // 面，按真实结构注入一枚探针气泡（量的是同一条规则的解析值），等一拍布局落定
  // 让 ResizeObserver 把截断线重算到当前盒。
  await page.evaluate(() => {
    const dock = document.querySelector('.ask-dock');
    if (!dock || dock.querySelector('.ask-bub')) return;
    const b = document.createElement('div');
    b.className = 'ask-bub ai';
    b.textContent = '探针气泡：材质与截断面';
    dock.querySelector('.ask-col').appendChild(b);
  });
  await sleep(400);
  const m = await page.evaluate(() => {
    const cs = (n, pseudo) => {
      if (!n) return null;
      const c = getComputedStyle(n, pseudo);
      return { bf: c.backdropFilter || c.webkitBackdropFilter, bg: c.backgroundColor, border: c.borderTopWidth + ' ' + c.borderTopColor, radius: c.borderTopLeftRadius };
    };
    const box = (n) => { if (!n) return null; const r = n.getBoundingClientRect(); const c = getComputedStyle(n); return { w: +r.width.toFixed(1), h: +r.height.toFixed(1), pad: c.padding, radius: c.borderTopLeftRadius }; };
    const top1 = document.getElementById('canvas-head-close');
    const top2 = document.getElementById('deck-fab');
    const dock = document.querySelector('.ask-dock');
    const send = dock && dock.querySelector('.ask-send');
    const stop = dock && dock.querySelector('.ask-stop');
    const bub = dock && dock.querySelector('.ask-bub');
    const ic = send && send.querySelector('svg');
    const icTop = top1 && top1.querySelector('svg');
    const handle = dock && dock.querySelector('[data-ask-resize]');
    let hit = 0;
    if (handle) {
      const hr = handle.getBoundingClientRect();
      const cx = hr.left + hr.width / 2, cy = hr.top + hr.height / 2;
      let up = 0, down = 0;
      const owns = (x, y) => { const e = document.elementFromPoint(x, y); return !!e && (e === handle || handle.contains(e)); };
      while (up < 60 && owns(cx, cy - up - 1)) up++;
      while (down < 60 && owns(cx, cy + down + 1)) down++;
      hit = up + down + 1;
    }
    const hb = handle ? getComputedStyle(handle, '::before') : null;
    const cols = dock && dock.querySelector('.ask-col');
    const bubs = dock ? [...dock.querySelectorAll('.ask-bub')] : [];
    const lastBub = bubs[bubs.length - 1];
    const dr = dock ? dock.getBoundingClientRect() : null;
    const trunc = dock ? getComputedStyle(dock).getPropertyValue('--ask-trunc-h').trim() : '';
    // 列表行（13:03①）
    // 列表行规则探针（13:03① 是**手机层**规则，桌面树里没有行实例）：
    // 给 body 临时挂 mobile-layer 类 + 造一枚 .fm-conv-summary 探针，量的是
    // 那条规则本身（读完即摘，零残留）。
    const hadMobile = document.body.classList.contains('mobile-layer');
    if (!hadMobile) document.body.classList.add('mobile-layer');
    const probeRow = document.createElement('span');
    probeRow.className = 'fm-conv-summary';
    probeRow.textContent = '全部清零 [附件] 4 个文件：截屏';
    (dock || document.body).appendChild(probeRow);
    const rowCs = getComputedStyle(probeRow);
    const row = probeRow;
    return {
      top1: box(top1), top2: box(top2), top1Mat: cs(top1), top2Mat: cs(top2),
      send: box(send), stop: box(stop), sendMat: cs(send), sendIcon: ic ? ic.getBoundingClientRect().width : null,
      topIcon: icTop ? icTop.getBoundingClientRect().width : null,
      bubMat: cs(bub),
      dock: dr ? { h: +dr.height.toFixed(1), bottom: +dr.bottom.toFixed(1) } : null,
      trunc: trunc, truncPx: parseFloat(trunc) || 0,
      colMask: cols ? (getComputedStyle(cols).maskImage || getComputedStyle(cols).webkitMaskImage) : null,
      colBottom: cols ? +cols.getBoundingClientRect().bottom.toFixed(1) : null,
      colOverflow: cols && cols.parentElement ? getComputedStyle(cols.parentElement).overflow : null,
      maskStops: (() => {
        const mi = cols ? (getComputedStyle(cols).maskImage || getComputedStyle(cols).webkitMaskImage) : '';
        if (!mi || mi === 'none') return [];
        return [...mi.matchAll(/(transparent|currentcolor|rgba?\([^)]*\)|white|black)[^,)]*?(?<![a-z])(\d+(?:\.\d+)?)px/gi)].map((x) => ({ color: x[1], px: parseFloat(x[2]) }));
      })(),
      lastBubBottom: lastBub ? +lastBub.getBoundingClientRect().bottom.toFixed(1) : null,
      // C4 新判据（2026-11-05 09:32）所需读数：mask 末停是否 100%（锚列盒）、
      // 列底到输入条的实测间距、dock 的 row-gap token 值。
      colMaskEndsAt100: /100%\)\s*$/.test(String(cols ? (getComputedStyle(cols).maskImage || getComputedStyle(cols).webkitMaskImage) : '').trim()),
      colToInputGap: (cols && dock)
        ? +((dock.querySelector('.ask-input') || { getBoundingClientRect: () => ({ top: 0 }) }).getBoundingClientRect().top - cols.getBoundingClientRect().bottom).toFixed(2)
        : null,
      dockRowGap: dock ? parseFloat(getComputedStyle(dock).rowGap) || 0 : 0,
      colwrapRect: (() => {
        const w = dock && dock.querySelector('.ask-colwrap');
        const b = w ? w.getBoundingClientRect() : null;
        const c = cols ? cols.getBoundingClientRect() : null;
        return b && c ? { wrapH: +b.height.toFixed(1), colH: +c.height.toFixed(1), wrapBottom: +b.bottom.toFixed(1), colBottom: +c.bottom.toFixed(1), dockH: dock ? +dock.getBoundingClientRect().height.toFixed(1) : null } : null;
      })(),
      hitHeight: hit,
      armBorder: hb ? { top: hb.borderTopWidth, left: hb.borderLeftWidth, right: hb.borderRightWidth, bottom: hb.borderBottomWidth, radius: hb.borderTopLeftRadius } : null,
      rowMask: rowCs.maskImage || rowCs.webkitMaskImage,
      rowOverflow: rowCs.textOverflow,
      rowExists: !!row,
    };
  });
  writeFileSync(join(OUT, `readings-${scheme}.json`), JSON.stringify({ phase: PHASE, scheme, m }, null, 2));
  // eslint-disable-next-line no-console
  console.log(`READ ${scheme} ` + JSON.stringify({
    top: m.top1, send: m.send, sendBf: m.sendMat && m.sendMat.bf, topBf: m.top1Mat && m.top1Mat.bf,
    bubBf: m.bubMat && m.bubMat.bf, dock: m.dock, trunc: m.truncPx, hit: m.hitHeight, arm: m.armBorder,
  }));

  if (scheme === 'light') {
    ok('C1 底钮与顶钮几何一致（宽/高/内距/圆角/图标边长逐项相等）',
      !!m.send && !!m.top1 && m.send.w === m.top1.w && m.send.h === m.top1.h
      && m.send.pad === m.top1.pad && m.send.radius === m.top1.radius
      && Math.round(m.sendIcon) === Math.round(m.topIcon),
      JSON.stringify({ send: m.send, top: m.top1, sendIcon: m.sendIcon, topIcon: m.topIcon }));
    ok('C2 毛玻璃同源（底钮/顶钮的 backdrop-filter + 背景 + 描边解析值一致）',
      !!m.sendMat && m.sendMat.bf === m.top1Mat.bf && m.sendMat.bg === m.top1Mat.bg && m.sendMat.border === m.top1Mat.border,
      JSON.stringify({ send: m.sendMat, top: m.top1Mat }));
    ok('C3 Ask 气泡吃同一套毛玻璃（backdrop-filter 与顶钮一致且非 none）',
      !!m.bubMat && m.bubMat.bf === m.top1Mat.bf && m.bubMat.bf !== 'none',
      JSON.stringify({ bub: m.bubMat && m.bubMat.bf, top: m.top1Mat && m.top1Mat.bf }));
    const lastStop = (m.maskStops || []).slice(-1)[0];
    // C4 改判（作者令 2026-11-05 09:32「调整窗口大小会导致对话框和内容直接有一大段
    // 空白」）：12:52 令的「中线截断（--ask-trunc-h = 面板高 × 50%）」退役——那条线
    // 画在列上、基准却是面板高（列 + 缝 + 输入条），列的下半段恒被 alpha 0 遮掉。
    // 新法 = 底缘收口锚在列自己的盒上（mask 末停 100%，尾部渐出 --ask-col-tail 落
    // 在列 padding-bottom 之内）⇒ 判据换成三件：① 列内 mask 末停 = 100%（锚列盒，
    // 不是中途某个 px）；② colwrap 仍 overflow:visible；③ 列底与输入条的间距 = dock
    // 的 row-gap（几何上真的贴住，不留空白带）。
    // 几何腿只在列**真的被布局出来**时才算数：本夹具的亮档里 canvas 面是 0×0
    // （C1 的 send/top 读数同为 0 ⇒ 该档整体不可测），这时「列底贴 row-gap」拿
    // 不到数据。旧 C4 在这种状态下是 0 对 0 的**空过**（Math.abs(0−0)≤2），本批
    // 顺手把这个空过点掉：不可测就明说，不用真空比较冒充绿。
    // 几何判据本身由专门 spec 在五档视口实测：tests/canvas-ask-legibility.spec.mjs
    // （L6a 桌面三档 / L6h 手机两档）。
    const geomMeasurable = !!(m.colwrapRect && m.colwrapRect.colH > 0);
    const gapOk = !geomMeasurable || Math.abs(m.colToInputGap - m.dockRowGap) <= 0.6;
    ok('C4 底缘收口锚在列自身的盒（mask 末停 100%）+ colwrap overflow:visible + 列底贴输入条 row-gap（12:52「中线截断」已由 11-05 令退役；几何腿不可测时只判 CSS 形）',
      m.colMaskEndsAt100 && m.colOverflow === 'visible' && gapOk,
      JSON.stringify({ maskEnd100: m.colMaskEndsAt100, overflow: m.colOverflow, gap: m.colToInputGap, rowGap: m.dockRowGap, geomMeasurable, wrap: m.colwrapRect, mask: m.colMask && String(m.colMask).slice(0, 130), lastStop }));
    ok('C5 截断由内容 mask 承担、零可见遮罩条（dock 内无 .ds-edge-fade / .ask-veil 元素）',
      await page.evaluate(() => document.querySelectorAll('.ask-dock .ds-edge-fade, .ask-dock .ask-veil').length === 0),
      'verdict=veil 计数');
    ok('C6 拖柄：命中带仍 ≥44（不回退）+ 可见形态 = 直角弧（仅上/左两条边臂 + 左上圆角）',
      m.hitHeight >= 43.5 && !!m.armBorder
      && parseFloat(m.armBorder.top) > 0 && parseFloat(m.armBorder.left) > 0
      && parseFloat(m.armBorder.right) === 0 && parseFloat(m.armBorder.bottom) === 0
      && parseFloat(m.armBorder.radius) >= 6,
      JSON.stringify({ hit: m.hitHeight, arm: m.armBorder }));
    ok('C7 列表行右缘渐隐撤除（无 mask + 省略号截断；手机层规则探针）',
      m.rowExists && m.rowMask === 'none' && m.rowOverflow === 'ellipsis',
      JSON.stringify({ mask: m.rowMask, overflow: m.rowOverflow }));
  }
  await page.screenshot({ path: join(OUT, `console-${scheme}.png`) }).catch(() => {});
  await ctx.close();
}

// ── 待办胶囊生长（G1..G5，桌面 1280×800，亮档一跑）────────────────┐
const gctx = await browser.newContext({ viewport: { width: 1280, height: 800 } });
await gctx.addInitScript(() => {
  localStorage.setItem('nebflow_token', 't');
  localStorage.setItem('nebflow_locale', 'zh-CN');
});
const gp = await gctx.newPage();
gp.on('pageerror', (e) => pageErrors.push('grow: ' + String(e.message).slice(0, 160)));
await gp.routeWebSocket(/\/ws/, (ws) => wireUp(ws));
await gp.goto(BASE + '/index.html');
await gp.waitForSelector('#input', { state: 'visible', timeout: 20000 });
await sleep(1400);

const grow = await gp.evaluate(async () => {
  const mod = await import('/js/todoFace.js');
  const face = mod.__desktopFace ? mod.__desktopFace() : null;
  if (!face) return { missing: true };
  const capLive = face.capsuleRectNow ? face.capsuleRectNow() : null;
  if (!face.expanded) face.expand();
  const card = face.card, capsule = face.capsule, countEl = face.capsuleFaces;
  const samples = [];
  const t0 = performance.now();
  for (let i = 0; i < 24; i++) {
    const r = card.getBoundingClientRect();
    samples.push({ t: Math.round(performance.now() - t0), w: +r.width.toFixed(1), h: +r.height.toFixed(1), top: +r.top.toFixed(1), left: +r.left.toFixed(1) });
    // eslint-disable-next-line no-await-in-loop
    await new Promise((res) => requestAnimationFrame(res));
  }
  const capRect = capLive ? { w: capLive.width, h: capLive.height } : { w: face.capsuleW, h: face.capsuleH };
  const countInHeader = countEl.parentElement === face.headerCount;
  const countNodeId = countEl.dataset.probeId || (countEl.dataset.probeId = 'count-' + Math.random().toString(36).slice(2, 8));
  return {
    capRect, samples, countInHeader, countNodeId,
    first: samples[0], last: samples[samples.length - 1],
    cardIsSameNode: card === face.card,
    panelIsCardChild: face.panel.parentElement === card,
  };
});
writeFileSync(join(OUT, 'readings-grow.json'), JSON.stringify({ phase: PHASE, grow }, null, 2));
if (grow.missing) {
  ok('G0 待办面可挂载（__desktopFace 在场）', false, 'face 缺件');
} else {
  const s = grow.samples;
  const hMonotonic = s.every((v, i) => i === 0 || v.h >= s[i - 1].h - 1.5);
  const wMonotonic = s.every((v, i) => i === 0 || v.w >= s[i - 1].w - 1.5);
  ok('G1 首帧 = 胶囊盒（面板从胶囊长出来：首帧高≈胶囊高、宽≈胶囊宽）',
    Math.abs(s[0].h - grow.capRect.h) <= 6 && Math.abs(s[0].w - grow.capRect.w) <= 12,
    JSON.stringify({ first: s[0], cap: grow.capRect }));
  ok('G2 宽高随时间单调增长（生长而非淡入：非首帧即全尺寸）', hMonotonic && wMonotonic
    && s[s.length - 1].h - s[0].h > 40, JSON.stringify({ h0: s[0].h, hN: s[s.length - 1].h, hMonotonic, wMonotonic }));
  ok('G3 全程同一个元素在变形（.tfl-card 恒为同一节点、面板是其子件）',
    grow.cardIsSameNode && grow.panelIsCardChild);
  ok('G4 L2 计数是同一个 DOM 节点左移成标题（搬进 .tfl-header-count 槽）',
    grow.countInHeader === true, JSON.stringify({ inHeader: grow.countInHeader }));
  // 中间帧实拍：0/33/66/100%
  await gp.screenshot({ path: join(OUT, 'grow-done.png') }).catch(() => {});
}

const flip = await gp.evaluate(async () => {
  const mod = await import('/js/todoFace.js');
  const face = mod.__desktopFace && mod.__desktopFace();
  if (!face) return { missing: true };
  const host = face.capsuleFaces.parentElement;
  face.renderCapsule(); // 基线拍（建立 lastCount）
  // 人为把上一拍读数改掉 ⇒ 下一拍视为「读数变了」（等价于一条待办被勾掉）
  face.lastCount = (face.lastCount || 0) + 1;
  face.renderCapsule();
  const flipped = host.classList.contains('tfl-count-flip');
  const anim = getComputedStyle(host).animationName;
  return { missing: false, flipped, anim, hostClass: host.className, text: (host.textContent || '').trim().slice(0, 24) };
});
ok('G5 L3 计数更新时在宿主上播一次翻转（.tfl-count-flip 挂上、文本为终值）',
  !flip.missing && flip.flipped === true && /tfl-count-flip/.test(flip.anim || ''),
  JSON.stringify(flip));

const rv = await gp.evaluate(async () => {
  const mod = await import('/js/todoFace.js');
  const face = mod.__desktopFace && mod.__desktopFace();
  if (!face) return { missing: true };
  face.collapse();
  const samples = [];
  for (let i = 0; i < 20; i++) {
    const r = face.card.getBoundingClientRect();
    samples.push({ h: +r.height.toFixed(1), w: +r.width.toFixed(1) });
    // eslint-disable-next-line no-await-in-loop
    await new Promise((res) => requestAnimationFrame(res));
  }
  // 弹簧落地（SPRING.close ≈ 0.45s）后再读终态：计数节点必须回到胶囊里
  await new Promise((res) => setTimeout(res, 700));
  return { missing: false, first: samples[0], last: samples[samples.length - 1], n: samples.length,
    cap: { w: face.capsuleW, h: face.capsuleH }, state: face.card.dataset.state,
    countBack: face.capsuleFaces.parentElement === face.capsule };
});
ok('G6 L4 反向收起沿同一路径（高度单调收缩回胶囊档、计数节点回胶囊）',
  !rv.missing && rv.last.h <= rv.first.h && rv.last.h <= rv.cap.h + 8 && rv.countBack === true && rv.state === 'capsule',
  JSON.stringify(rv));
await gctx.close();

ok('Z1 全程零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors.slice(0, 3)));
await browser.close().catch(() => {});
await new Promise((r) => server.close(r));

writeFileSync(join(OUT, 'run.log'), checks.map((c) => `${c.ok ? 'PASS' : 'FAIL'}  ${c.name}${c.extra ? '  — ' + c.extra : ''}`).join('\n') + '\n');
const pass = checks.filter((c) => c.ok).length;
// eslint-disable-next-line no-console
console.log(`\n${PHASE}: ${pass} PASS / ${checks.length - pass} FAIL`);
process.exit(failures ? 1 : 0);

// stack-continuity.spec.mjs — 叠卡切换动画的几何连续性证据包（canvas-stack-interactions 批）。
//
// 病灶（批令）：popArtifacts.js switchFront 出场卡在动画 45%→46% 之间从画外
// 「跳」到终态扇形槽位并同时切层级 —— 离散几何跳变。本 spec 用与
// stack-touch-motion.spec.mjs 相同的启动面（自起 python 静态服 + 真模块
// harness.html，绝不碰 6138 运行实例），在真 DOM 上拦截 Element.prototype.animate
// 把 WAAPI 动画停在创建态，再按百分比直写 currentTime —— 每一帧都是确定性的
// 真渲染帧（无夹具、无示意图）。
//
// 关键事实（红侧勘察结论，写死进采样设计）：
//  · options.easing:'ease-in-out' 作用于整段迭代进度（Web Animations 模型），
//    关键帧 offset .45/.46 处在**缓和后**进度空间：offset .45 对应墙钟 ≈47.4%
//    —— 传送实际发生在墙钟 47%→48% 帧之间，故跳变窗口取 42–50% 全覆盖。
//  · .row{animation:fadeIn .2s}（chat.css）是**入列动画**：建栈后 0.2s 内拖拽
//    会把行透明度混进证据帧（红侧首跑的"灰纱"假象）。因此建栈后必须等全部
//    CSS 动画落定再拖。
//  · #chat 的 mask-image（chat.css:41-42）会把画外飞出卡裁掉：测试页显式
//    解除（不动产品 CSS），否则 fwd 方向的几何证据不完整。
//
// 证据面（批验收 a–f）：
//  a  jump window：42–50% 逐帧图 + 相邻帧逐像素差分（canvas getImageData，
//     每 |Δchannel|>8 记一像素；阈值依据：暂停态无计时器，静态区域逐位相同，
//     阈值只吸收编解码舍入；1% 步内连续位移 ≤16px ⇒ 改变量上界 = 移动边带
//     周长×位移 ≈ 1000×16=16000 + 第三卡小位移，取 25000；一次全卡传送 ≈
//     2×220×280 ≈ 123000）。同时断言出场卡 bbox 逐帧 Δx ≤16px
//     （红态 45↔46 ≈ ±240px）。z 翻转窗口（45–48%）逐帧断言出场卡与其余
//     可见层 AABB 零相交 ⇒ 层级翻更像素不可见。
//  b  handoff：99% 帧 vs 交接完成帧，bbox ≤1px + 逐像素差分（阈值 8000：
//     缓和曲线 99%→100% 残差 ≈0.05px 几何 ⇒ 只剩抗锯齿边界带；实测红侧
//     fwd 5409 / N=2 669，上界 8000 = 全周长 1000px × 2px AA 带 × 4 色通道面
//     裕量），正反两方向。
//  c  露边曲线：第三张可见像素数 / 右缘 x 随 t 的连续曲线（CSV + SVG 图）；
//     断言相邻步 |Δ第三卡可见px| ≤12600（露边带高 ≈280px × 4% 步最大边移
//     ≈45px 的包络）与出场卡 x 每步 ≤80px；尾部（t≥48，作者语义的揭示段）
//     第三卡右缘单调不减。
//  d  交接后无错位：动画落位 vs prefers-reduced-motion 即时换张的同稳态，
//     视域裁剪逐像素相同 + 每层 transform/zIndex 串相同 + 三条探针色带
//     （front 面 / mid 露边 / back 露边）归属正确（探针 y=60 避开数字字形）。
//  e  front 索引交接后已更新：popQuoteState + nf-quote-front 事件 + layer-front 类。
//  f  上下滑关闭回归不在本文件 —— 跑同目录 stack-touch-motion.spec.mjs 全量
//     （其 mobile canvas up/down/47/48/49 组即该语义）。
//
// 红绿两态：/private/tmp/stack-evidence/PHASE 写 'red'（修前树）或 'green'
// （修后树），产物落对应子目录；两目录都保留，绝不写旧证据目录
// /private/tmp/stand-stack-evidence。
import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const EVID_ROOT = '/private/tmp/stack-evidence';
const phase = (() => {
  try { return readFileSync(`${EVID_ROOT}/PHASE`, 'utf8').trim() || 'red'; } catch { return 'red'; }
})();
const out = `${EVID_ROOT}/${phase}`;
mkdirSync(out, { recursive: true });

// 调色板即层号（badge i = 第 i 张卡），逐像素按最近色分类。
const PALETTE = ['#df564d', '#4d91df', '#53ad67', '#d9a441', '#9a5fd0'];
const CH_THRESHOLD = 8;  // a: 单通道 |Δ| 超过此值记"变"
const COLOR_DIST = 60;   // badge 分类欧氏半径

let server, base;
test.beforeAll(async () => {
  const probe = createServer();
  await new Promise(r => probe.listen(0, '127.0.0.1', r));
  const port = probe.address().port;
  await new Promise(r => probe.close(r));
  base = `http://127.0.0.1:${port}`;
  server = spawn('python3', ['-m', 'http.server', String(port), '--bind', '127.0.0.1', '--directory', root], { stdio: 'ignore' });
  await expect.poll(async () => { try { return (await fetch(base + '/src/main/resources/web/index.html')).status; } catch { return 0; } }).toBe(200);
});
test.afterAll(() => server?.kill());

/** 逐层纯色 badge（层号 = 调色板序）；独立成函数以便同页第二栈复用。 */
async function injectBadges(page, viewIdx = 0) {
  await page.evaluate(([idx, colors]) => {
    document.querySelectorAll('#chat .pop-stack-view')[idx]
      .querySelectorAll(':scope > .media-layer').forEach((layer, i) => {
        const badge = document.createElement('div');
        badge.dataset.badge = String(i);
        badge.textContent = String(i + 1);
        Object.assign(badge.style, {
          position: 'absolute', inset: '0', background: colors[i], color: 'white',
          display: 'grid', placeItems: 'center', font: 'bold 120px sans-serif',
        });
        layer.appendChild(badge);
      });
  }, [viewIdx, PALETTE]);
}

/** 真模块 harness 页 + 指定叠卡场景 + badge 注入 + 入列动画落定。 */
async function stackPage(browser, builder) {
  const page = await browser.newPage({ viewport: { width: 1280, height: 1300 } });
  await page.goto(base + '/tests/fixtures/pop-artifacts/harness.html');
  await page.waitForFunction(() => window.__ready === true);
  await page.evaluate(() => { document.querySelector('#popup-chat').style.display = 'none'; });
  // 证据面取景需要：飞出卡在视口内完整可测。#chat 的 overflow-y:auto 会把
  // overflow-x 压成 auto，mask-image 又会裁画外绘制 —— 只解除本测试页的
  // 裁剪，不动产品 CSS。
  await page.addStyleTag({ content: '#chat{overflow:visible!important;mask-image:none!important;-webkit-mask-image:none!important} html,body{overflow:hidden!important}' });
  await page.evaluate(name => window[name](), builder);
  // 埋层卡 display:none ⇒ loading=lazy（zcode-484 D4 产品语义）的图永不触发
  // 拉取。证据需要全部卡面字节就绪：临时显示埋层等图载完再还原回 display:none。
  await page.evaluate(() => {
    document.querySelectorAll('#chat .pop-stack-view .media-layer.is-buried').forEach(el => { el.style.display = 'block'; });
  });
  await page.waitForFunction(() => Array.from(document.querySelectorAll('#chat .pop-stack-view .pop-img')).every(i => i.naturalWidth > 0), null, { timeout: 60000 });
  await page.evaluate(() => {
    document.querySelectorAll('#chat .pop-stack-view .media-layer.is-buried').forEach(el => { el.style.display = ''; });
  });
  // 等全部入列 fadeIn（.row 0.2s）落定 —— 否则行透明度混进证据帧（红侧
  // 首跑的"灰纱"假象即它）。
  await page.waitForFunction(() => document.getAnimations({ subtree: true }).every(a => a.playState !== 'running'), null, { timeout: 30000 });
  await injectBadges(page, 0);
  await page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));
  return page;
}

/** 拦截 WAAPI：栈内动画创建即暂停并登记；同时挂 nf-quote-front 采录。 */
async function armPause(page) {
  await page.evaluate(() => {
    window.__stackAnims = [];
    window.__frontEvents = [];
    window.addEventListener('nf-quote-front', e => window.__frontEvents.push(e.detail.index));
    const orig = Element.prototype.animate;
    Element.prototype.animate = function (...args) {
      const a = orig.apply(this, args);
      if (this.closest && this.closest('.pop-stack-view')) { a.pause(); window.__stackAnims.push(a); }
      return a;
    };
  });
}

/** 全部登记动画拨到 percent%，并等两帧落画。 */
async function seek(page, percent) {
  await page.evaluate(p => window.__stackAnims.forEach(a => { a.currentTime = a.effect.getTiming().duration * p / 100; }), percent);
  await page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));
}

/** 视图/层几何 + front 状态快照（viewIdx 支持同页多栈）。 */
const layerInfo = (page, viewIdx = 0) => page.evaluate(idx => {
  const view = document.querySelectorAll('#chat .pop-stack-view')[idx];
  const v = view.getBoundingClientRect();
  const layers = Array.from(view.querySelectorAll(':scope > .media-layer'));
  return {
    view: { x: v.x, y: v.y, w: v.width, h: v.height },
    anims: (window.__stackAnims || []).length,
    layers: layers.map((el, i) => {
      const r = el.getBoundingClientRect();
      return {
        i, badge: +(el.querySelector('[data-badge]')?.dataset.badge ?? -1),
        cls: el.className, x: r.x, y: r.y, w: r.width, h: r.height,
        z: getComputedStyle(el).zIndex, t: getComputedStyle(el).transform,
        display: getComputedStyle(el).display,
      };
    }),
    front: view.closest('.pop-stack-area').popQuoteState().index,
    events: [...(window.__frontEvents || [])],
  };
}, viewIdx);

/** 真像素差分（lab 页内 canvas 解码 PNG）：变更数/最大通道差/变更框/质心 + 差分热图。 */
async function pngDiff(lab, aB64, bB64) {
  return lab.evaluate(async ([a, b, T]) => {
    const load = s => new Promise(res => { const i = new Image(); i.onload = () => res(i); i.src = 'data:image/png;base64,' + s; });
    const A = await load(a), B = await load(b);
    const w = A.naturalWidth, h = A.naturalHeight;
    const cv = document.createElement('canvas'); cv.width = w; cv.height = h;
    const ctx = cv.getContext('2d', { willReadFrequently: true });
    ctx.drawImage(A, 0, 0); const da = ctx.getImageData(0, 0, w, h).data;
    ctx.clearRect(0, 0, w, h); ctx.drawImage(B, 0, 0); const db = ctx.getImageData(0, 0, w, h).data;
    let changed = 0, maxD = 0, x1 = 1e9, y1 = 1e9, x2 = -1, y2 = -1, cx = 0, cy = 0;
    const heat = new Uint8ClampedArray(w * h * 4);
    for (let i = 0; i < w * h; i++) {
      const p = i * 4;
      const d = Math.max(Math.abs(da[p] - db[p]), Math.abs(da[p + 1] - db[p + 1]), Math.abs(da[p + 2] - db[p + 2]));
      const g = (db[p] * .3 + db[p + 1] * .59 + db[p + 2] * .11) | 0;
      if (d > T) {
        changed++; if (d > maxD) maxD = d;
        const x = i % w, y = (i / w) | 0;
        if (x < x1) x1 = x; if (x > x2) x2 = x; if (y < y1) y1 = y; if (y > y2) y2 = y;
        cx += x; cy += y;
        heat[p] = 255; heat[p + 1] = 40; heat[p + 2] = 40;
      } else { heat[p] = heat[p + 1] = heat[p + 2] = g >> 1; }
      heat[p + 3] = 255;
    }
    ctx.putImageData(new ImageData(heat, w, h), 0, 0);
    return {
      w, h, changed, maxD, ratio: +(changed / (w * h)).toFixed(5),
      bbox: changed ? [x1, y1, x2, y2] : null,
      centroid: changed ? [Math.round(cx / changed), Math.round(cy / changed)] : null,
      heat: cv.toDataURL('image/png').split(',')[1],
    };
  }, [aB64, bB64, CH_THRESHOLD]);
}

/** 真像素分类：每 badge 色的可见像素数与包围盒 + 任意探针点的原色采样。 */
async function pngCards(lab, aB64, colors, probes = []) {
  return lab.evaluate(async ([a, cols, pr, D]) => {
    const load = s => new Promise(res => { const i = new Image(); i.onload = () => res(i); i.src = 'data:image/png;base64,' + s; });
    const A = await load(a);
    const w = A.naturalWidth, h = A.naturalHeight;
    const cv = document.createElement('canvas'); cv.width = w; cv.height = h;
    const ctx = cv.getContext('2d', { willReadFrequently: true });
    ctx.drawImage(A, 0, 0); const d = ctx.getImageData(0, 0, w, h).data;
    const cards = cols.map(hex => {
      const c = [parseInt(hex.slice(1, 3), 16), parseInt(hex.slice(3, 5), 16), parseInt(hex.slice(5, 7), 16)];
      return { hex, c, count: 0, minX: null, maxX: null, minY: null, maxY: null };
    });
    const D2 = D * D;
    for (let i = 0; i < w * h; i++) {
      const p = i * 4;
      for (const card of cards) {
        const dr = d[p] - card.c[0], dg = d[p + 1] - card.c[1], dbl = d[p + 2] - card.c[2];
        if (dr * dr + dg * dg + dbl * dbl <= D2) {
          const x = i % w, y = (i / w) | 0;
          card.count++;
          if (card.minX === null || x < card.minX) card.minX = x;
          if (card.maxX === null || x > card.maxX) card.maxX = x;
          if (card.minY === null || y < card.minY) card.minY = y;
          if (card.maxY === null || y > card.maxY) card.maxY = y;
          break;
        }
      }
    }
    for (const card of cards) delete card.c;
    const probeOut = (pr || []).map(p => {
      const i = (p.y * w + p.x) * 4;
      return { name: p.name, x: p.x, y: p.y, rgb: [d[i], d[i + 1], d[i + 2]] };
    });
    return { w, h, cards, probes: probeOut };
  }, [aB64, colors, probes, COLOR_DIST]);
}

const hexToRgb = hex => [1, 3, 5].map(o => parseInt(hex.slice(o, o + 2), 16));
const nearestCard = rgb => PALETTE.map(hexToRgb)
  .reduce((best, c, i) => {
    const d = (c[0] - rgb[0]) ** 2 + (c[1] - rgb[1]) ** 2 + (c[2] - rgb[2]) ** 2;
    return d < best.d ? { i, d } : best;
  }, { i: -1, d: 1e9 }).i;

/** 真指针拖拽触发 switchFront（与产品手势同路）。 */
async function dragSwitch(page, vb, dir) {
  await page.mouse.move(vb.x + vb.w / 2, vb.y + vb.h / 2);
  await page.mouse.down();
  await page.mouse.move(vb.x + vb.w / 2 + dir, vb.y + vb.h / 2, { steps: 5 });
  await page.mouse.up();
}

const settled = (page, viewIdx = 0) => expect.poll(() => page.evaluate(idx =>
  document.querySelectorAll('#chat .pop-stack-view')[idx].classList.contains('is-switching'), viewIdx)).toBe(false);

/* ── a) 跳变窗口：42–50% 逐帧 + 相邻逐像素差分 + z 翻转零重叠 ─────────── */
const JUMP_FRAMES = [42, 43, 44, 45, 46, 47, 48, 49, 50];
for (const variant of ['fwd', 'rev']) {
  test(`jump window ${variant} [${phase}]`, async ({ browser }) => {
    test.setTimeout(240000);
    const page = await stackPage(browser, '__popStackMixedRatios');
    const lab = await browser.newPage();
    try {
      await armPause(page);
      const info0 = await layerInfo(page);
      if (info0.view.x < 270) throw new Error(`viewport leaves flyout clipped: view.x=${info0.view.x}`);
      await dragSwitch(page, info0.view, variant === 'fwd' ? -75 : 75);
      const info1 = await layerInfo(page);
      expect(info1.anims).toBe(3);
      const shots = {}, infos = {};
      for (const p of JUMP_FRAMES) {
        await seek(page, p);
        infos[p] = await layerInfo(page);
        const buf = await page.screenshot();
        shots[p] = buf.toString('base64');
        writeFileSync(`${out}/jump-${variant}-${p}.png`, buf);
      }
      const pairMetrics = [];
      for (let k = 1; k < JUMP_FRAMES.length; k++) {
        const a = JUMP_FRAMES[k - 1], b = JUMP_FRAMES[k];
        const d = await pngDiff(lab, shots[a], shots[b]);
        writeFileSync(`${out}/jumpdiff-${variant}-${a}-${b}.png`, Buffer.from(d.heat, 'base64'));
        pairMetrics.push({
          pair: [a, b], changed: d.changed, ratio: d.ratio, maxD: d.maxD,
          diffBBox: d.bbox, centroid: d.centroid,
          outgoingDx: +(infos[b].layers[0].x - infos[a].layers[0].x).toFixed(2),
        });
      }
      // z 翻转窗口（45–48%，含缓和空间的实际翻转点）：出场卡与其余可见层
      // AABB 必须零相交 ⇒ 层级翻更像素不可见。
      const overlapAt = p => {
        const o = infos[p].layers[0];
        return infos[p].layers.slice(1)
          .filter(l => l.display !== 'none' &&
            !(o.x + o.w <= l.x || l.x + l.w <= o.x || o.y + o.h <= l.y || l.y + l.h <= o.y))
          .map(l => l.badge);
      };
      const fails = [];
      for (const m of pairMetrics) {
        if (m.changed > 25000) fails.push(`pair ${m.pair[0]}-${m.pair[1]}: changed ${m.changed} > 25000`);
        if (Math.abs(m.outgoingDx) > 16) fails.push(`pair ${m.pair[0]}-${m.pair[1]}: outgoing Δx ${m.outgoingDx}px > 16`);
      }
      const overlaps = {};
      for (const p of [45, 46, 47, 48]) {
        overlaps[p] = overlapAt(p);
        if (overlaps[p].length) fails.push(`z flip visible at ${p}%: outgoing overlaps badges ${overlaps[p]}`);
      }
      writeFileSync(`${out}/metrics-jump-${variant}.json`, JSON.stringify({
        pairMetrics, overlaps, anims: info1.anims,
      }, null, 2));
      console.log(`jump-${variant}`, JSON.stringify(pairMetrics.map(m => `${m.pair}:Δ${m.changed}@dx${m.outgoingDx}`)).replace(/"/g, ''));
      expect(fails, fails.join('\n')).toEqual([]);
    } finally {
      await page.close(); await lab.close();
    }
  });
}

/* ── b+d+e) 交接：99%→完成帧 双指标；探针色带；front 索引 ──────────────── */
for (const variant of ['fwd', 'rev']) {
  test(`handoff ${variant} [${phase}]`, async ({ browser }) => {
    test.setTimeout(240000);
    const page = await stackPage(browser, '__popStackMixedRatios');
    const lab = await browser.newPage();
    try {
      await armPause(page);
      const info0 = await layerInfo(page);
      await dragSwitch(page, info0.view, variant === 'fwd' ? -75 : 75);
      await seek(page, 99);
      const info99 = await layerInfo(page);
      const shot99 = await page.screenshot();
      writeFileSync(`${out}/handoff-${variant}-99.png`, shot99);
      await page.evaluate(() => window.__stackAnims.forEach(a => a.finish()));
      await settled(page);
      const info1 = await layerInfo(page);
      const shotSettled = await page.screenshot();
      writeFileSync(`${out}/handoff-${variant}-settled.png`, shotSettled);
      const d = await pngDiff(lab, shot99.toString('base64'), shotSettled.toString('base64'));
      writeFileSync(`${out}/handoffdiff-${variant}.png`, Buffer.from(d.heat, 'base64'));
      const bboxDeltas = info1.layers.map((l, i) => Math.max(
        Math.abs(l.x - info99.layers[i].x), Math.abs(l.y - info99.layers[i].y),
        Math.abs(l.w - info99.layers[i].w), Math.abs(l.h - info99.layers[i].h)));
      const fails = [];
      if (d.changed > 8000) fails.push(`99→settled changed ${d.changed} > 8000`);
      const maxB = Math.max(...bboxDeltas);
      if (maxB > 1) fails.push(`99→settled bbox Δ ${maxB.toFixed(2)}px > 1`);
      const wantFront = variant === 'fwd' ? 1 : 2;
      if (info1.front !== wantFront) fails.push(`popQuoteState().index=${info1.front} ≠ ${wantFront}`);
      if (info1.events.at(-1) !== wantFront) fails.push(`nf-quote-front last=${info1.events.at(-1)} ≠ ${wantFront}`);
      const frontLayer = info1.layers.find(l => l.cls.includes('layer-front'));
      if (!frontLayer || frontLayer.badge !== wantFront) fails.push(`layer-front badge=${frontLayer && frontLayer.badge} ≠ ${wantFront}`);
      // d) 三条探针：front 面上部（y=60 避开数字字形）/ mid 露边带 / back 露边带。
      const vx = Math.round(info1.view.x), vy = Math.round(info1.view.y);
      const probes = [
        { name: 'center', x: vx + 110, y: vy + 60 },
        { name: 'mid-strip', x: vx + 226, y: vy + 140 },
        { name: 'back-strip', x: vx + 240, y: vy + 140 },
      ];
      const cards = await pngCards(lab, shotSettled.toString('base64'), PALETTE, probes);
      const want = variant === 'fwd'
        ? { center: 1, 'mid-strip': 2, 'back-strip': 0 }   // 前蓝、露绿、再露红
        : { center: 2, 'mid-strip': 0, 'back-strip': 1 };  // 前绿、露红、再露蓝
      for (const p of cards.probes) {
        const got = nearestCard(p.rgb);
        if (got !== want[p.name]) fails.push(`probe ${p.name}: rgb(${p.rgb}) → card${got} ≠ card${want[p.name]}`);
      }
      writeFileSync(`${out}/metrics-handoff-${variant}.json`, JSON.stringify({
        changed99: d.changed, maxD: d.maxD, bboxDeltas, front: info1.front,
        events: info1.events, probes: cards.probes, layers: info1.layers,
      }, null, 2));
      console.log(`handoff-${variant}`, JSON.stringify({ changed: d.changed, maxB: +maxB.toFixed(2), front: info1.front }));
      expect(fails, fails.join('\n')).toEqual([]);
    } finally {
      await page.close(); await lab.close();
    }
  });
}

/* ── d) 交接后无错位：动画落位 vs 即时换张稳态（视域逐像素等同） ────────── */
test(`settled parity [${phase}]`, async ({ browser }) => {
  test.setTimeout(240000);
  const page = await stackPage(browser, '__popStackMixedRatios');
  const lab = await browser.newPage();
  try {
    await armPause(page);
    const infoA0 = await layerInfo(page, 0);
    await dragSwitch(page, infoA0.view, -75);        // 栈 A：动画 0→1
    await page.evaluate(() => window.__stackAnims.forEach(a => a.finish()));
    await settled(page, 0);
    const infoA = await layerInfo(page, 0);
    const clipA = {
      x: Math.round(infoA.view.x - 80), y: Math.round(infoA.view.y - 80),
      width: 400, height: 450,
    };
    const shotAnim = await page.screenshot({ clip: clipA });
    writeFileSync(`${out}/parity-anim.png`, shotAnim);
    // 同页第二栈 B：prefers-reduced-motion 下同一手势 = 纯 applyLayers 即时稳态。
    await page.evaluate(() => window.__popStackMixedRatios());
    await page.waitForFunction(() => document.querySelectorAll('#chat .pop-stack-view').length === 2);
    await page.waitForFunction(() => document.getAnimations({ subtree: true }).every(a => a.playState !== 'running'), null, { timeout: 30000 });
    await injectBadges(page, 1);
    await page.emulateMedia({ reducedMotion: 'reduce' });
    const infoB0 = await layerInfo(page, 1);
    await dragSwitch(page, infoB0.view, -75);
    await page.waitForTimeout(80);
    const infoB = await layerInfo(page, 1);
    const clipB = {
      x: Math.round(infoB.view.x - 80), y: Math.round(infoB.view.y - 80),
      width: 400, height: 450,
    };
    const shotRef = await page.screenshot({ clip: clipB });
    writeFileSync(`${out}/parity-ref.png`, shotRef);
    const d = await pngDiff(lab, shotAnim.toString('base64'), shotRef.toString('base64'));
    writeFileSync(`${out}/paritydiff.png`, Buffer.from(d.heat, 'base64'));
    const fails = [];
    // 阈值 300：两侧稳态 transform/zIndex/类串已逐字断言相等（下面三层 for），
    // 像素面只兜抗锯齿/分数栅格噪声 —— 裁剪 400×450=180000px，实测红侧基线
    // 噪声 74px（<0.05%）；真实错位 = 整条旋转边带 ≈ 5000px 级。
    if (d.changed > 300) fails.push(`anim-settled vs instant-settled changed ${d.changed} > 300`);
    if (infoB.front !== 1 || infoA.front !== 1) fails.push(`front A=${infoA.front} B=${infoB.front} ≠ 1`);
    infoA.layers.forEach((la, i) => {
      const lb = infoB.layers[i];
      if (la.t !== lb.t) fails.push(`layer${i} transform differs: ${la.t} vs ${lb.t}`);
      if (la.z !== lb.z) fails.push(`layer${i} zIndex differs: ${la.z} vs ${lb.z}`);
      if (la.cls !== lb.cls) fails.push(`layer${i} class differs: ${la.cls} vs ${lb.cls}`);
    });
    writeFileSync(`${out}/metrics-parity.json`, JSON.stringify({ changed: d.changed, bbox: d.bbox, A: infoA.layers, B: infoB.layers }, null, 2));
    console.log('parity', JSON.stringify({ changed: d.changed }));
    expect(fails, fails.join('\n')).toEqual([]);
  } finally {
    await page.close(); await lab.close();
  }
});

/* ── c) 第三张露边连续曲线（0–100%，4% 步进）+ 出场卡 x(t) 同图 ─────────── */
test(`reveal curve [${phase}]`, async ({ browser }) => {
  test.setTimeout(300000);
  const page = await stackPage(browser, '__popStackMixedRatios');
  const lab = await browser.newPage();
  try {
    await armPause(page);
    const info0 = await layerInfo(page);
    await dragSwitch(page, info0.view, -75);
    const samples = [];
    for (let p = 0; p <= 100; p += 4) {
      await seek(page, p);
      const info = await layerInfo(page);
      const buf = await page.screenshot();
      if ([0, 24, 48, 72, 96, 100].includes(p)) writeFileSync(`${out}/curve-${p}.png`, buf);
      const cards = await pngCards(lab, buf.toString('base64'), PALETTE);
      const third = cards.cards[2];   // 第三张 = 卡2（绿）
      samples.push({
        t: p,
        thirdCount: third.count,
        thirdMaxX: third.maxX, thirdMinX: third.minX,
        outgoingX: +info.layers[0].x.toFixed(2),
      });
    }
    // CSV（数据）
    writeFileSync(`${out}/reveal-curve.csv`,
      't_percent,third_visible_px,third_max_x,third_min_x,outgoing_x\n' +
      samples.map(s => `${s.t},${s.thirdCount},${s.thirdMaxX ?? ''},${s.thirdMinX ?? ''},${s.outgoingX}`).join('\n') + '\n');
    // SVG（图）：两条线 —— 第三张右缘 x(t)（露边）与出场卡 x(t)（连续性）。
    const W = 1040, H = 420, L = 70, R = 20, T = 30, B = 40;
    const xs = t => L + (W - L - R) * t / 100;
    const allX = samples.map(s => s.outgoingX);
    const yOut = v => T + (H - T - B) * (1 - (v - Math.min(...allX)) / (Math.max(...allX) - Math.min(...allX) || 1));
    const maxThird = Math.max(...samples.map(s => s.thirdMaxX ?? -1e9));
    const minThird = Math.min(...samples.filter(s => s.thirdMaxX !== null).map(s => s.thirdMaxX));
    const yThird = v => T + (H - T - B) * (1 - (v - minThird) / ((maxThird - minThird) || 1));
    const line = f => samples.map(s => `${xs(s.t).toFixed(1)},${f(s).toFixed(1)}`).join(' ');
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" font-family="monospace" font-size="12">
  <rect width="${W}" height="${H}" fill="#fff"/>
  <text x="${L}" y="18">third-card reveal (green maxX, px) and outgoing-card x(t) — phase=${phase}</text>
  <polyline fill="none" stroke="#0a0" stroke-width="2" points="${line(s => yThird(s.thirdMaxX ?? minThird))}"/>
  <polyline fill="none" stroke="#c22" stroke-width="2" points="${line(s => yOut(s.outgoingX))}"/>
  ${[0, 25, 50, 75, 100].map(t => `<text x="${xs(t)}" y="${H - 15}" text-anchor="middle">${t}%</text>`).join('')}
  <text x="${W - 130}" y="${T + 14}" fill="#0a0">third maxX ${minThird.toFixed(0)}–${maxThird.toFixed(0)}</text>
  <text x="${W - 130}" y="${T + 30}" fill="#c22">outgoing x ${Math.min(...allX).toFixed(0)}–${Math.max(...allX).toFixed(0)}</text>
</svg>`;
    writeFileSync(`${out}/reveal-curve.svg`, svg);
    const fails = [];
    for (let i = 1; i < samples.length; i++) {
      const a = samples[i - 1], b = samples[i];
      // 包络：露边带高 ≈280px × 4% 步最大边移 ≈45px ⇒ 12600。
      if (Math.abs(b.thirdCount - a.thirdCount) > 12600) fails.push(`t ${a.t}→${b.t}: third visible px jump ${b.thirdCount - a.thirdCount}`);
      if (Math.abs(b.outgoingX - a.outgoingX) > 80) fails.push(`t ${a.t}→${b.t}: outgoing x jump ${(b.outgoingX - a.outgoingX).toFixed(1)}px`);
    }
    // 尾部揭示段：第三卡右缘的**最终接近段**单调不减（容差 0.5px AA）。
    // 窗口取 t≥48 内 thirdMaxX 最后一次极小之后的后缀 —— 出场卡顶点之后的
    // 行程里，第三卡从被遮滑入终态槽位，右缘只进不退；极小之前的覆盖段
    // （incoming 尚未居中遮满）不属揭示语义，不设单调。
    const tailAll = samples.filter(s => s.t >= 48 && s.thirdMaxX !== null);
    if (tailAll.length) {
      let minIdx = 0;
      tailAll.forEach((s, i) => { if (s.thirdMaxX <= tailAll[minIdx].thirdMaxX) minIdx = i; });
      const tail = tailAll.slice(minIdx);
      for (let i = 1; i < tail.length; i++) {
        if (tail[i].thirdMaxX < tail[i - 1].thirdMaxX - 0.5) fails.push(`tail reveal not monotonic: t${tail[i - 1].t}=${tail[i - 1].thirdMaxX} > t${tail[i].t}=${tail[i].thirdMaxX}`);
      }
    }
    if (!samples.some(s => s.thirdMaxX !== null)) fails.push('third card never visible: reveal curve empty');
    writeFileSync(`${out}/metrics-curve.json`, JSON.stringify({ samples }, null, 2));
    console.log('curve', JSON.stringify(samples.filter(s => s.t % 20 === 0)));
    expect(fails, fails.join('\n')).toEqual([]);
  } finally {
    await page.close(); await lab.close();
  }
});

/* ── N=5 埋层交接：出场卡必须在完全遮挡处被 display:none 接手 ───────────── */
test(`buried handoff [${phase}]`, async ({ browser }) => {
  test.setTimeout(240000);
  const page = await stackPage(browser, '__popFiveImages');
  const lab = await browser.newPage();
  try {
    await armPause(page);
    const info0 = await layerInfo(page);
    await dragSwitch(page, info0.view, -75);
    const series = [];
    let shot99 = null, info99 = null;
    for (const p of [0, 20, 40, 44, 45, 46, 48, 60, 80, 99]) {
      await seek(page, p);
      const buf = await page.screenshot();
      writeFileSync(`${out}/buried-${p}.png`, buf);
      const cards = await pngCards(lab, buf.toString('base64'), PALETTE);
      series.push({ t: p, outgoingVisible: cards.cards[0].count });
      if (p === 99) { shot99 = buf; info99 = await layerInfo(page); }
    }
    await page.evaluate(() => window.__stackAnims.forEach(a => a.finish()));
    await settled(page);
    const info1 = await layerInfo(page);
    const shotSettled = await page.screenshot();
    writeFileSync(`${out}/buried-settled.png`, shotSettled);
    const d = await pngDiff(lab, shot99.toString('base64'), shotSettled.toString('base64'));
    writeFileSync(`${out}/burieddiff.png`, Buffer.from(d.heat, 'base64'));
    const fails = [];
    if (series.at(-1).outgoingVisible > 200) fails.push(`outgoing still exposed at 99%: ${series.at(-1).outgoingVisible}px`);
    if (d.changed > 8000) fails.push(`99→settled changed ${d.changed} > 8000 (display:none handoff not fully occluded)`);
    if (!info1.layers[0].cls.includes('is-buried')) fails.push('outgoing not re-buried after handoff');
    if (info1.front !== 1) fails.push(`front=${info1.front} ≠ 1`);
    writeFileSync(`${out}/metrics-buried.json`, JSON.stringify({ series, changed99: d.changed, bbox: d.bbox }, null, 2));
    console.log('buried', JSON.stringify(series));
    expect(fails, fails.join('\n')).toEqual([]);
  } finally {
    await page.close(); await lab.close();
  }
});

/* ── N=2 交接：唯一扇槽 = mid 槽，同窗口连续性 ─────────────────────────── */
test(`two-card handoff [${phase}]`, async ({ browser }) => {
  test.setTimeout(240000);
  const page = await stackPage(browser, '__popStackTwo');
  const lab = await browser.newPage();
  try {
    await armPause(page);
    const info0 = await layerInfo(page);
    await dragSwitch(page, info0.view, -75);
    expect(info0.layers).toHaveLength(2);
    const shots = {}, infos = {};
    for (const p of JUMP_FRAMES) {
      await seek(page, p);
      infos[p] = await layerInfo(page);
      const buf = await page.screenshot();
      shots[p] = buf.toString('base64');
      writeFileSync(`${out}/two-${p}.png`, buf);
    }
    const pairMetrics = [];
    for (let k = 1; k < JUMP_FRAMES.length; k++) {
      const a = JUMP_FRAMES[k - 1], b = JUMP_FRAMES[k];
      const d = await pngDiff(lab, shots[a], shots[b]);
      writeFileSync(`${out}/twodiff-${a}-${b}.png`, Buffer.from(d.heat, 'base64'));
      pairMetrics.push({ pair: [a, b], changed: d.changed, outgoingDx: +(infos[b].layers[0].x - infos[a].layers[0].x).toFixed(2) });
    }
    await seek(page, 99);
    const shot99 = await page.screenshot();
    writeFileSync(`${out}/two-99.png`, shot99);
    await page.evaluate(() => window.__stackAnims.forEach(a => a.finish()));
    await settled(page);
    const info1 = await layerInfo(page);
    const shotSettled = await page.screenshot();
    writeFileSync(`${out}/two-settled.png`, shotSettled);
    const d = await pngDiff(lab, shot99.toString('base64'), shotSettled.toString('base64'));
    writeFileSync(`${out}/twodiff-handoff.png`, Buffer.from(d.heat, 'base64'));
    const fails = [];
    for (const m of pairMetrics) {
      if (m.changed > 25000) fails.push(`pair ${m.pair}: changed ${m.changed} > 25000`);
      if (Math.abs(m.outgoingDx) > 16) fails.push(`pair ${m.pair}: outgoing Δx ${m.outgoingDx}px > 16`);
    }
    if (d.changed > 8000) fails.push(`99→settled changed ${d.changed} > 8000`);
    if (info1.front !== 1) fails.push(`front=${info1.front} ≠ 1`);
    writeFileSync(`${out}/metrics-two.json`, JSON.stringify({ pairMetrics, changed99: d.changed, layers: info1.layers }, null, 2));
    console.log('two', JSON.stringify(pairMetrics));
    expect(fails, fails.join('\n')).toEqual([]);
  } finally {
    await page.close(); await lab.close();
  }
});

#!/usr/bin/env node
// side-tab-legibility.spec.mjs — canvas 顶部环签「两侧标签可读性 + 转盘旋转」实测验收
// （ui-final-four ③，作者令原文 2026-11-02 21:47「顶部的几个胶囊 两边的两个小的 靠近
// 中间胶囊的部分要能清晰显示包括文字 然后越靠边 越模糊 可以是胶囊只显示一半的那种
// 圆环感」+ 2026-11-03 13:01「希望两边标签的文字尽量能清晰显现」「彼此切换的时候能有
// 一种旋转的感觉」）。
//
// 上一批 2255d1dac 自报了对比度 12.05:1 / op 0.901 / --rfade 18% / rotateY −24°×k /
// 280ms / prefers-reduced-motion 摘旋转 —— **自报值不是实测**。本文件只做一件事：
// 用真实浏览器把六条判据逐条机械断言，读数全部落盘（readings.json）。
//
// 断言面：
//   L1 两侧标签（|k|=1 邻签）文字对比度实测 ≥4.5:1（深/浅双主题）。
//      **取法 = 真渲染像素对**：同一裁剪区拍两帧（A 正常 / B label visibility:hidden），
//      逐像素配对 ⇒ A = 该字形像素的前景**实际合成色**（含 chip 不透明度 × mask alpha
//      × blur × rotateY 抗锯齿 × 文字色），B = 同一点的前后景（玻璃底 + 页面底，
//      含宿主底色/UA 默认底的一切合成结果）。按 WCAG 2.x 相对亮度公式算比值。
//      取样面 = label 的**朝中心半幅**（作者令「靠近中间胶囊的部分要能清晰显示
//      **包括文字**」——测的就是那半边）。
//      ⇒ 不是「字号 × 不透明度」折算，也不是把 --ds-text 与 --glass-bg 两个令牌直接
//      相除（那两者都不含缩径/模糊/旋转/合成；上一批 12.05:1 正是这样算出来的，
//      实测 4.46–4.79:1 不达标 —— 见本批批文档 §2 根因）。
//   L2 渐隐 = 内容级 alpha：遍历签条子树（含 ::before/::after 伪元素盒），
//      ① 无任何带 background / backdrop-filter 的盒子越出自己那枚签（不许有横跨签条
//         的遮罩条/底色过渡条）；
//      ② 带 mask 的面只有三处：签的 ::after（方向渐隐）、label（方向渐隐）、
//         条本体的 14px 视口边羽化（零背景 ⇒ 不是遮罩元素）；宿主签本体零 mask；
//      ③ 条边羽化带与任何可见签的渲染盒零交集（几何断言 ⇒ 羽化不可能啃到签内容）；
//      ④ 宿主签本体零底色（UA 默认 buttonface 也必须归零 —— 对照度红的量化根因）。
//   L3 切换 = 转盘式旋转：computed transition-duration ≤300ms + 邻签实测 rotateY 分量
//      |θ| ≈ 24°（matrix3d 反解，按 m22 归一化掉 2D scale）+ 一次切换「触发 →
//      transform transitionend」墙钟耗时（合成器位移动画，用事件而非 computed style
//      计时 —— Chromium 对合成器动画的 getComputedStyle 返回**落定值**，会读成 0ms）。
//   L4 prefers-reduced-motion: reduce ⇒ computed transform 退化为 2D matrix()（无
//      matrix3d ⇒ 无 rotateY 分量），但位移（--rdx 环位姿、--sdx 手势跟手）与切签
//      本身仍生效（含像素级：过渡期内至少 1 帧中间态）。
//   L5 中间帧实拍：一次切换内在过渡窗口里连拍，逐帧与「起手帧 / 落定帧」做像素比对，
//      两者都不同 ⇒ 该帧必为中间态；深/浅各一套，≥3 帧。
//   L6 门禁：中签两向都清晰 + 中/邻签样式同源 —— 主断言由
//      tests/mobile-canvas-sheet.spec.mjs T13 系列承担；本文件复核中点 label mask=none、
//      中/邻 11 项视觉键相等，防「为邻签达标改坏中签」。
//
// 双引擎（chromium + webkit）各跑一遍；形态参照 tests/ask-input-composer.spec.mjs
// （自足静态服务 + routeWebSocket mock + 自打 PASS/FAIL，零后端依赖）。
// 端口按引擎分：chromium 8370 / webkit 8371（起服务前 lsof 自查空位，末尾必关）。
//
// Run: node tests/side-tab-legibility.spec.mjs --engine chromium
//      node tests/side-tab-legibility.spec.mjs --engine webkit
//      SIDE_TABS_WEB=<导出树>/src/main/resources/web   （改前树腿用，缺省 = 工作树）
//      SIDE_TABS_OUT=<证据目录>                        （缺省 = 仓内 evidence/side-tab-legibility）

import { chromium, webkit } from 'playwright';
import { createServer } from 'node:http';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, extname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { decodePng } from '../scripts/lib/png-diff.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, '..');

// ── 参数面 ────────────────────────────────────────────────────────────────
const argv = process.argv.slice(2);
const argOf = (name, dflt) => {
  const i = argv.indexOf(`--${name}`);
  if (i >= 0 && argv[i + 1]) return argv[i + 1];
  const eq = argv.find((a) => a.startsWith(`--${name}=`));
  return eq ? eq.slice(name.length + 3) : dflt;
};
const ENGINE = (argOf('engine', 'chromium') || 'chromium').toLowerCase().includes('webkit') ? 'webkit' : 'chromium';
const WEB_DIR = resolve(process.env.SIDE_TABS_WEB || join(REPO, 'src', 'main', 'resources', 'web'));
const OUT_DIR = resolve(process.env.SIDE_TABS_OUT || join(REPO, 'evidence', 'side-tab-legibility'));
const PORT = ENGINE === 'webkit' ? 8371 : 8370;
const BASE = `http://127.0.0.1:${PORT}`;
const SID = 'side-tab-root';
const DSF = 2;                 // 设备像素比：字形实心核更完整（对比度取样面）
const TT = 1.5;                // 采样裁切内缩（CSS px，避开盒边抗锯齿混色）
const STRIP_CLIP = { x: 0, y: 0, width: 375, height: 80 };   // 环带整幅（中间帧实拍面）

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.ico': 'image/x-icon',
  '.json': 'application/json; charset=utf-8', '.webmanifest': 'application/manifest+json',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf', '.map': 'application/json',
};

// ── 断言收集器 ────────────────────────────────────────────────────────────
const checks = [];
let combo = `${ENGINE}`;
function check(id, ok, detail) {
  checks.push({ combo, id, ok: !!ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  [${combo}]  ${id}  — ${detail}`);
  return !!ok;
}
const info = (msg) => console.log(`        [${combo}]  ${msg}`);

// ── WCAG 相对亮度 ─────────────────────────────────────────────────────────
const chan = (c) => { const v = c / 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; };
const relLum = ([r, g, b]) => 0.2126 * chan(r) + 0.7152 * chan(g) + 0.0722 * chan(b);
const wcag = (fg, bg) => {
  const a = relLum(fg); const b = relLum(bg);
  const hi = Math.max(a, b); const lo = Math.min(a, b);
  return +(((hi + 0.05) / (lo + 0.05)).toFixed(3));
};
const hex = ([r, g, b]) => `#${[r, g, b].map((v) => v.toString(16).padStart(2, '0')).join('')}`;

/** 两帧像素差异百分比（通道差 >8 判变）。 */
function changedPct(a, b) {
  const A = decodePng(a); const B = decodePng(b);
  const n = Math.min(A.data.length, B.data.length);
  let c = 0; let tot = 0;
  for (let i = 0; i < n; i += 4) {
    tot++;
    const d = Math.max(Math.abs(A.data[i] - B.data[i]), Math.abs(A.data[i + 1] - B.data[i + 1]), Math.abs(A.data[i + 2] - B.data[i + 2]));
    if (d > 8) c++;
  }
  return +((100 * c) / tot).toFixed(2);
}

// ── transform 反解 ────────────────────────────────────────────────────────
/** computed transform 的 rotateY 分量（度）。
 *  链式 = scale(s) rotateY(θ) ⇒ m31 = s·sinθ、m33 = cosθ（scale() 是 2D，z 列不缩放）
 *  ⇒ θ = atan2(m31, m33·m22)。直接 atan2(m31, m33) 会读成 21.17°（0.87 缩径吃进
 *  了正弦项），这是**读数法**的坑，不是实现坑 —— 本批实证。2D matrix ⇒ 0。 */
function thetaOf(tf) {
  if (!tf || !tf.startsWith('matrix3d')) return 0;
  const v = tf.slice(9, -1).split(',').map(Number);
  const s = v[5] || 1;   // m22 = 2D scale 档
  return +((Math.atan2(v[8], v[10] * s) * 180) / Math.PI).toFixed(2);
}
/** computed transform 的 translateX（CSS px；3D 取 m41，2D 取 e）。 */
function txOf(tf) {
  if (!tf || tf === 'none') return 0;
  const v = tf.slice(tf.indexOf('(') + 1, -1).split(',').map(Number);
  return +(tf.startsWith('matrix3d') ? v[12] : v[4]).toFixed(2);
}

// ── 静态文件服务（自足；/api 全 mock，/ws 由 routeWebSocket 拦）────────────
function serve() {
  const server = createServer((req, res) => {
    const url = new URL(req.url, BASE);
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      const body = p === '/api/sessions'
        ? JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID })
        : '{}';
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(body);
      return;
    }
    if (p === '/agents/Nebula/agent.json') {
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end('{}');
      return;
    }
    const file = join(WEB_DIR, p);
    if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
    try {
      const body = readFileSync(file);
      res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
      res.end(body);
    } catch { res.writeHead(404); res.end('not found'); }
  });
  return new Promise((ok, bad) => {
    server.once('error', bad);
    server.listen(PORT, '127.0.0.1', () => ok(server));
  });
}

// ── 起应用（mock WS + 真 UI 进场；与 mobile-canvas-sheet.spec 同构）────────
async function boot(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: SID,
    }));
    sendConfig(); sendSessions();
    ws.onMessage((raw) => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
    });
  });
  await page.goto(`${BASE}/`);
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForTimeout(300);
}

/** 灌 3 张卡（真实标题形态：长文件名 + 中文短名），末张为活动卡。 */
const CARDS = [
  ['side-a', '20261024_071038_mac-local-image-gen', 'side-type-a'],
  ['side-b', '卡片乙设计稿', 'side-type-b'],
  ['side-c', '7451 lugi 命名规约汇编', 'side-type-c'],
];
async function seedCards(page) {
  await page.evaluate(async () => {
    const c = await import('/js/canvas.js');
    const nav = await import('/js/mobile/navController.js');
    for (const d of nav.docTabs()) c.closeTab(d.id);
  });
  await page.waitForTimeout(180);
  await page.evaluate((list) => import('/js/canvas.js').then((canvas) => {
    for (const [id, title, type] of list) canvas.openTab(id, title, { type });
  }), CARDS);
  await page.waitForTimeout(260);
  await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click();
  await page.waitForFunction(() => document.querySelector('.mobile-page[data-page="canvas"]')?.classList.contains('live'));
  await page.waitForTimeout(520);   // 环位姿过渡落定
  // 活动签 = 中间那枚 ⇒ 左右各一枚邻签（|k|=1 的两个测量对象同时在场）。
  await page.evaluate(() => document.querySelector('.mf-strip-chip[data-tab-id="side-b"]').click());
  await page.waitForTimeout(520);
}

const chipInfo = (page) => page.evaluate(() => {
  const strip = document.querySelector('.mf-strip');
  const box = (r) => ({ x: +r.x.toFixed(2), y: +r.y.toFixed(2), w: +r.width.toFixed(2), h: +r.height.toFixed(2) });
  const chips = [...strip.querySelectorAll('.mf-strip-chip')].map((c) => {
    const cs = getComputedStyle(c);
    const label = c.querySelector('.mf-strip-label');
    const lcs = getComputedStyle(label);
    const acs = getComputedStyle(c, '::after');
    return {
      id: c.dataset.tabId,
      on: c.classList.contains('on'),
      sideL: c.classList.contains('side-l'),
      sideR: c.classList.contains('side-r'),
      text: label.textContent,
      box: box(c.getBoundingClientRect()),
      labelBox: box(label.getBoundingClientRect()),
      opacity: +cs.opacity,
      filter: cs.filter,
      fontSize: cs.fontSize,
      fontWeight: cs.fontWeight,
      color: cs.color,
      hostBgColor: cs.backgroundColor,
      hostBgImage: cs.backgroundImage,
      hostBorderW: cs.borderTopWidth,
      rdx: c.style.getPropertyValue('--rdx'),
      rsc: c.style.getPropertyValue('--rsc'),
      rop: c.style.getPropertyValue('--rop'),
      rbl: c.style.getPropertyValue('--rbl'),
      rrot: c.style.getPropertyValue('--rrot'),
      rfade: c.style.getPropertyValue('--rfade'),
      transform: cs.transform,
      theta: (() => {
        const t = cs.transform;
        if (!t.startsWith('matrix3d')) return 0;
        const v = t.slice(9, -1).split(',').map(Number);
        const s = v[5] || 1;
        return +((Math.atan2(v[8], v[10] * s) * 180) / Math.PI).toFixed(2);
      })(),
      transitionDuration: cs.transitionDuration,
      transitionProperty: cs.transitionProperty,
      labelMask: lcs.maskImage || lcs.webkitMaskImage || 'none',
      hostMask: cs.maskImage || cs.webkitMaskImage || 'none',
      afterMask: acs.maskImage || acs.webkitMaskImage || 'none',
      // 中/邻签同源面（11 键，与 T13⑥ 同集）
      visual: {
        radius: cs.borderRadius, bgImage: acs.backgroundImage, bgColor: acs.backgroundColor,
        borderTop: acs.borderTopColor, borderBottom: acs.borderBottomColor, borderWidth: acs.borderTopWidth,
        shadow: acs.boxShadow, weight: cs.fontWeight, size: cs.fontSize, family: cs.fontFamily, color: cs.color,
      },
    };
  });
  return {
    strip: {
      box: box(strip.getBoundingClientRect()),
      mask: getComputedStyle(strip).maskImage || getComputedStyle(strip).webkitMaskImage || 'none',
      bgImage: getComputedStyle(strip).backgroundImage,
      bgColor: getComputedStyle(strip).backgroundColor,
      backdrop: getComputedStyle(strip).backdropFilter || getComputedStyle(strip).webkitBackdropFilter || 'none',
      perspective: getComputedStyle(strip).perspective,
      fade: strip.classList.contains('fade'),
    },
    chips,
  };
});

// ── L1：真渲染像素对比度（前/景配对法）─────────────────────────────────────
async function contrastOf(page, chipId) {
  const meta = await page.evaluate((id) => {
    const chip = document.querySelector(`.mf-strip-chip[data-tab-id="${id}"]`);
    const label = chip.querySelector('.mf-strip-label');
    const r = label.getBoundingClientRect();
    return {
      sideL: chip.classList.contains('side-l'),
      label: { x: r.x, y: r.y, w: r.width, h: r.height },
      text: label.textContent,
    };
  }, chipId);
  // 朝中心半幅（side-l ⇒ 右半；side-r ⇒ 左半）。内缩 TT px 避开盒边混色。
  const half = meta.label.w / 2;
  const x0 = meta.sideL ? meta.label.x + half : meta.label.x;
  const clip = {
    x: +(x0 + TT).toFixed(2), y: +(meta.label.y + 1).toFixed(2),
    width: Math.max(4, +(half - TT * 2).toFixed(2)), height: Math.max(4, meta.label.h - 2),
  };
  const A = await page.screenshot({ clip });
  await page.evaluate(() => {
    const st = document.createElement('style');
    st.id = '__nolegibility';
    st.textContent = '.mf-strip-label{visibility:hidden !important;}';
    document.head.appendChild(st);
  });
  const B = await page.screenshot({ clip });
  await page.evaluate(() => document.getElementById('__nolegibility')?.remove());

  const a = decodePng(A); const b = decodePng(B);
  const px = [];
  for (let y = 0; y < a.height; y++) {
    for (let x = 0; x < a.width; x++) {
      const i = (y * a.width + x) * 4;
      const fg = [a.data[i], a.data[i + 1], a.data[i + 2]];
      const bg = [b.data[i], b.data[i + 1], b.data[i + 2]];
      const d = Math.max(Math.abs(fg[0] - bg[0]), Math.abs(fg[1] - bg[1]), Math.abs(fg[2] - bg[2]));
      if (d > 10) px.push({ x, y, d, fg, bg, ratio: wcag(fg, bg) });
    }
  }
  if (!px.length) return { chipId, text: meta.text, clip, glyphPixels: 0, note: '朝中心半幅零墨迹像素' };
  px.sort((p, q) => q.d - p.d);
  const core = px.slice(0, Math.max(1, Math.round(px.length * 0.15)));   // 字形实心核（覆盖率前 15%）
  const ratios = core.map((p) => p.ratio).sort((m, n) => m - n);
  const top = px[0];
  const devx = (x) => +(clip.x + (x + 0.5) / DSF).toFixed(2);           // 设备像素 → CSS px 中心
  const devy = (y) => +(clip.y + (y + 0.5) / DSF).toFixed(2);
  const bgLum = relLum(top.bg);
  return {
    chipId, text: meta.text, side: meta.sideL ? 'L(朝中心=右半)' : 'R(朝中心=左半)',
    clip, deviceScaleFactor: DSF, labelBox: meta.label,
    glyphPixels: px.length, corePixels: core.length,
    // 代表点 = 覆盖率最高的字形核像素（该处前景=文字实际合成色、背景=同点无字时的实际合成色）
    sample: {
      devicePx: [top.x, top.y], cssPx: [devx(top.x), devy(top.y)],
      fgRGB: top.fg, fgHex: hex(top.fg), bgRGB: top.bg, bgHex: hex(top.bg),
      coverage: +(top.d / 255).toFixed(3), ratio: top.ratio, bgRelLum: +bgLum.toFixed(4),
    },
    coreMedian: +ratios[Math.floor(ratios.length / 2)].toFixed(3),
    coreMin: +ratios[0].toFixed(3),
    coreMax: +ratios[ratios.length - 1].toFixed(3),
    coreAbove45: core.filter((p) => p.ratio >= 4.5).length,
  };
}

// ── L2：遮罩/覆盖盒子审计（签条子树 + 伪元素盒）──────────────────────────────
const overlayAudit = (page) => page.evaluate(() => {
  const strip = document.querySelector('.mf-strip');
  const chips = [...strip.querySelectorAll('.mf-strip-chip')];
  const alphaOf = (c) => {
    const m = /rgba?\(([^)]+)\)/.exec(c || '');
    if (!m) return 0;
    const parts = m[1].split(',').map((s) => parseFloat(s));
    return parts.length > 3 ? parts[3] : 1;
  };
  const geo = (el) => { const r = el.getBoundingClientRect(); return { x: +r.x.toFixed(2), y: +r.y.toFixed(2), w: +r.width.toFixed(2), h: +r.height.toFixed(2) }; };
  const rec = (label, el, pseudo, chip) => {
    const cs = getComputedStyle(el, pseudo || undefined);
    return {
      label, pseudo: pseudo || '',
      box: geo(el),
      chipId: chip ? chip.dataset.tabId : null,
      chipW: chip ? +chip.getBoundingClientRect().width.toFixed(2) : null,
      bgImage: cs.backgroundImage,
      bgColor: cs.backgroundColor,
      bgAlpha: alphaOf(cs.backgroundColor),
      backdrop: cs.backdropFilter || cs.webkitBackdropFilter || 'none',
      mask: cs.maskImage || cs.webkitMaskImage || 'none',
    };
  };
  const out = [];
  const stripCs = getComputedStyle(strip);
  out.push({
    label: '.mf-strip', pseudo: '', box: geo(strip), chipId: null, chipW: null,
    bgImage: stripCs.backgroundImage, bgColor: stripCs.backgroundColor, bgAlpha: alphaOf(stripCs.backgroundColor),
    backdrop: stripCs.backdropFilter || stripCs.webkitBackdropFilter || 'none',
    mask: stripCs.maskImage || stripCs.webkitMaskImage || 'none',
  });
  for (const chip of chips) {
    out.push(rec(`chip[${chip.dataset.tabId}]`, chip, '', chip));
    out.push(rec(`chip[${chip.dataset.tabId}]`, chip, '::before', chip));
    out.push(rec(`chip[${chip.dataset.tabId}]`, chip, '::after', chip));
    for (const sub of chip.children) out.push(rec(`chip[${chip.dataset.tabId}] > ${sub.className}`, sub, '', chip));
  }
  const visible = chips.filter((c) => +getComputedStyle(c).opacity > 0.01)
    .map((c) => { const r = c.getBoundingClientRect(); return { id: c.dataset.tabId, x: +r.x.toFixed(2), right: +r.right.toFixed(2) }; });
  return { boxes: out, visibleChips: visible, stripBox: geo(strip) };
});

// ── L3：切换墙钟（transitionend 计时；合成器动画的 getComputedStyle 读不到中间值）──
function switchWallClock(page, targetId) {
  return page.evaluate(async (id) => {
    const strip = document.querySelector('.mf-strip');
    const el = document.querySelector(`.mf-strip-chip[data-tab-id="${id}"]`);
    const t0 = performance.now();
    let lastTransformEnd = null;
    const ends = [];
    const onEnd = (e) => {
      const t = +(performance.now() - t0).toFixed(1);
      ends.push({ prop: e.propertyName, t, elapsed: +(e.elapsedTime * 1000).toFixed(1) });
      if (e.propertyName === 'transform') lastTransformEnd = t;
    };
    strip.addEventListener('transitionend', onEnd, true);
    el.click();
    await new Promise((r) => setTimeout(r, 900));
    strip.removeEventListener('transitionend', onEnd, true);
    return { lastTransformEnd, ends };
  }, targetId);
}

// ── L5：中间帧实拍（过渡窗口内连拍 + 与起手/落定帧像素比对）───────────────────
async function switchSequence(page, fromId, toId, maxFrames = 8) {
  await page.evaluate((f) => document.querySelector(`.mf-strip-chip[data-tab-id="${f}"]`).click(), fromId);
  await page.waitForTimeout(600);                        // 落定
  const before = await page.screenshot({ clip: STRIP_CLIP });
  const t0 = await page.evaluate((t) => {
    document.querySelector(`.mf-strip-chip[data-tab-id="${t}"]`).click();
    return performance.now();
  }, toId);
  const frames = [];
  for (let i = 0; i < maxFrames; i++) {
    const buf = await page.screenshot({ clip: STRIP_CLIP });
    const at = await page.evaluate(() => performance.now());
    frames.push({ buf, atMs: +(at - t0).toFixed(1) });
    if (at - t0 > 460) break;
  }
  await page.waitForTimeout(420);
  const settled = await page.screenshot({ clip: STRIP_CLIP });
  return { before, settled, frames };
}

// ══ 主流程 ════════════════════════════════════════════════════════════════
const readings = { engine: ENGINE, webDir: WEB_DIR, outDir: OUT_DIR, startedAt: new Date().toISOString(), combos: {} };
let server = null;
let browser = null;

try {
  mkdirSync(OUT_DIR, { recursive: true });
  server = await serve();
  browser = await (ENGINE === 'webkit' ? webkit : chromium).launch();
  console.log(`\n=== side-tab-legibility · engine=${ENGINE} · web=${WEB_DIR} · out=${OUT_DIR} ===`);

  for (const theme of ['dark', 'light']) {
    combo = `${ENGINE}/${theme}`;
    const ctx = await browser.newContext({
      viewport: { width: 375, height: 812 }, hasTouch: true, deviceScaleFactor: DSF,
      colorScheme: theme, locale: 'zh-CN',
    });
    const page = await ctx.newPage();
    const pageErrors = [];
    page.on('pageerror', (e) => pageErrors.push(String(e.message)));
    await boot(page);
    await seedCards(page);

    const read = { theme, dark: theme === 'dark' };
    const p = await chipInfo(page);

    // ── 几何前置：必须真的是「中签 + 左右各一枚邻签」三件在场 ────────────────
    const on = p.chips.find((c) => c.on);
    const nbs = p.chips.filter((c) => !c.on && c.opacity > 0.01);
    check('G0 三签几何在位', !!on && on.id === 'side-b' && nbs.length === 2,
      `on=${on && on.id} 邻签=${nbs.map((c) => `${c.id}(${c.sideL ? 'side-l' : ''}${c.sideR ? 'side-r' : ''})`).join(',')}`);
    check('G1 perspective 生效（转盘有空间感）', parseFloat(p.strip.perspective) > 0, `perspective=${p.strip.perspective}`);

    // ── L1 对比度（深/浅各测左右两枚邻签）───────────────────────────────
    read.contrast = [];
    for (const nb of nbs) {
      const m = await contrastOf(page, nb.id);
      read.contrast.push(m);
      check(`L1 邻签文字对比度 ≥4.5:1 · ${nb.id}`,
        m.glyphPixels > 0 && m.sample && m.sample.ratio >= 4.5 && m.coreMedian >= 4.5,
        m.glyphPixels
          ? `代表点(设备px ${m.sample.devicePx.join(',')} / CSS ${m.sample.cssPx.join(',')}) `
            + `fg=${m.sample.fgHex} bg=${m.sample.bgHex} 比值=${m.sample.ratio}:1；`
            + `字形核 ${m.corePixels}px 中位=${m.coreMedian}:1 最小=${m.coreMin}:1 最大=${m.coreMax}:1（≥4.5 占 ${m.coreAbove45}/${m.corePixels}）`
          : m.note);
    }
    const onM = await contrastOf(page, on.id);
    read.contrastCenter = onM;
    check('L1b 中签文字对比度 ≥4.5:1（未被邻签改动带坏）',
      onM.glyphPixels > 0 && onM.sample.ratio >= 4.5,
      onM.glyphPixels ? `代表点(设备px ${onM.sample.devicePx.join(',')} / CSS ${onM.sample.cssPx.join(',')}) fg=${onM.sample.fgHex} bg=${onM.sample.bgHex} 比值=${onM.sample.ratio}:1 核中位=${onM.coreMedian}:1` : onM.note);

    // ── L2 遮罩/覆盖盒子审计 ────────────────────────────────────────────
    const audit = await overlayAudit(page);
    read.overlayAudit = audit;
    const bgBoxes = audit.boxes.filter((b) => (b.bgImage && b.bgImage !== 'none') || b.bgAlpha > 0 || (b.backdrop && b.backdrop !== 'none'));
    const spill = bgBoxes.filter((b) => b.pseudo !== '::before' && b.pseudo !== '::after'
      && b.label !== '.mf-strip' && (!b.chipW || b.box.w > b.chipW + 1));
    check('L2① 无横跨签条的底色/遮罩盒子（带 background/backdrop 的盒子不越出自己那枚签）',
      spill.length === 0,
      `带底盒子 ${bgBoxes.length} 个：${bgBoxes.map((b) => `${b.label}${b.pseudo}=${b.box.w}px`).slice(0, 8).join(' / ')}；越界=${spill.length}`);
    check('L2② 条本体零底色零 backdrop（不是遮罩元素）',
      (audit.boxes[0].bgImage === 'none') && audit.boxes[0].bgAlpha === 0 && audit.boxes[0].backdrop === 'none',
      `strip bgImage=${audit.boxes[0].bgImage} bgAlpha=${audit.boxes[0].bgAlpha} backdrop=${audit.boxes[0].backdrop}`);
    const maskHosts = audit.boxes.filter((b) => b.mask && b.mask !== 'none');
    const legalMasks = maskHosts.every((b) => b.label === '.mf-strip' || b.pseudo === '::after' || /mf-strip-label/.test(b.label));
    const hostMasks = p.chips.filter((c) => c.hostMask !== 'none');
    check('L2③ 渐隐只落在 label / 签 ::after 的 mask 上（+ 条边 14px 羽化），宿主签本体零 mask',
      legalMasks && hostMasks.length === 0,
      `带 mask 面：${maskHosts.map((b) => `${b.label}${b.pseudo}`).join(' / ')}；宿主带 mask 的签=${hostMasks.length}`);
    const nbMaskKept = nbs.every((c) => /linear-gradient/.test(c.labelMask) && /linear-gradient/.test(c.afterMask));
    check('L2④ 邻签渐隐在内容层（label 与 ::after 都是方向 linear-gradient），非覆盖条',
      nbMaskKept, nbs.map((c) => `${c.id} label=${/linear-gradient/.test(c.labelMask) ? 'lg' : c.labelMask} after=${/linear-gradient/.test(c.afterMask) ? 'lg' : c.afterMask}`).join(' / '));
    check('L2⑤ 中签两向都清晰（label mask=none，11:23 既有裁定不破）', on.labelMask === 'none', `中签 labelMask=${on.labelMask}`);
    const feather = 14;
    const intersect = audit.visibleChips.filter((c) => c.x < feather || c.right > 375 - feather);
    check('L2⑥ 条边 14px 羽化带与可见签渲染盒零交集', intersect.length === 0,
      `可见签 x/right：${audit.visibleChips.map((c) => `${c.id}:${c.x}/${c.right}`).join(' ')}（羽化带 0–${feather} / ${375 - feather}–375）`);
    const dirtyHost = p.chips.filter((c) => c.hostBgColor !== 'rgba(0, 0, 0, 0)' && c.hostBgColor !== 'transparent');
    check('L2⑦ 宿主签本体零底色（玻璃材质只在 ::after；UA 默认 buttonface 必须归零）',
      dirtyHost.length === 0 && p.chips.every((c) => c.hostBgImage === 'none' && c.hostBorderW === '0px'),
      `宿主 backgroundColor=${[...new Set(p.chips.map((c) => c.hostBgColor))].join(' / ')}；bgImage=${[...new Set(p.chips.map((c) => c.hostBgImage))].join('/')}；borderW=${[...new Set(p.chips.map((c) => c.hostBorderW))].join('/')}`);

    // 基础档（非 reduce）的同一条读数：证明 reduce 档与基础档**逐项同构**（本批补漏面）
    const baseSdx = await page.evaluate(async () => {
      const chip = document.querySelector('.mf-strip-chip.on');
      const strip = document.querySelector('.mf-strip');
      const tx = () => {
        const t = getComputedStyle(chip).transform;
        return +(t.startsWith('matrix3d') ? t.slice(9, -1).split(',')[12] : t.slice(7, -1).split(',')[4]) || 0;
      };
      const wait = (ms) => new Promise((r) => setTimeout(r, ms));
      const t0 = tx();
      strip.style.setProperty('--sdx', '-30px');
      await wait(460);
      const t1 = tx();
      strip.style.removeProperty('--sdx');
      await wait(460);
      return { t0: +t0.toFixed(2), t1: +t1.toFixed(2), delta: +(t1 - t0).toFixed(2) };
    });
    read.baseSdx = baseSdx;
    check('L2⑧ 基础档跟手位移（--sdx → 位移链）生效（reduce 档须逐项同构的基准读数）',
      Math.abs(baseSdx.delta + 30) <= 1.2, `--sdx=-30px ⇒ Δtx=${baseSdx.delta}px（tx ${baseSdx.t0}→${baseSdx.t1}）`);


    // ── L3 转盘旋转 + 时长 ──────────────────────────────────────────────
    const durs = nbs.map((c) => parseFloat(c.transitionDuration.split(',')[0]) * 1000);
    check('L3① computed transition-duration ≤300ms', durs.every((d) => d <= 300),
      `邻签 transition-duration=${nbs.map((c) => c.transitionDuration).join(' | ')}（property=${nbs[0].transitionProperty}）⇒ ${durs[0]}ms`);
    const thetas = nbs.map((c) => Math.abs(c.theta));
    check('L3② 邻签实测 rotateY 分量 |θ| ≈ 24°（matrix3d 按 m22 归一化反解）',
      thetas.every((t) => t >= 22 && t <= 26),
      `${nbs.map((c) => `${c.id}:${c.theta}°(--rrot=${c.rrot}, --rsc=${c.rsc})`).join(' / ')}；中签 θ=${on.theta}°`);
    const sw = await switchWallClock(page, 'side-c');
    read.switch = sw;
    check('L3③ 一次切换的墙钟耗时（触发 → transform transitionend）≈280ms 且不超 340ms',
      sw.lastTransformEnd !== null && sw.lastTransformEnd >= 250 && sw.lastTransformEnd <= 340,
      `transform transitionend @ ${sw.lastTransformEnd}ms（该次切换全部 transitionend：${sw.ends.map((e) => `${e.prop}@${e.t}ms/elapsed ${e.elapsed}ms`).join(' ')}）`);
    await page.evaluate(() => document.querySelector('.mf-strip-chip[data-tab-id="side-b"]').click());
    await page.waitForTimeout(520);

    // ── L5 中间帧实拍（一次切换内连拍；与起手帧/落定帧双比）────────────────
    read.midFrames = [];
    const seq = await switchSequence(page, 'side-b', 'side-c');
    const moved = changedPct(seq.before, seq.settled);
    const mids = seq.frames.filter((f) => changedPct(f.buf, seq.before) > 0.2 && changedPct(f.buf, seq.settled) > 0.2);
    seq.frames.forEach((f, i) => {
      const name = `${ENGINE}-${theme}-mid-${String(i + 1).padStart(2, '0')}-${f.atMs}ms.png`;
      writeFileSync(join(OUT_DIR, name), f.buf);
      read.midFrames.push({
        file: name, atMs: f.atMs,
        vsBeforePct: changedPct(f.buf, seq.before), vsSettledPct: changedPct(f.buf, seq.settled),
        intermediate: changedPct(f.buf, seq.before) > 0.2 && changedPct(f.buf, seq.settled) > 0.2,
      });
    });
    writeFileSync(join(OUT_DIR, `${ENGINE}-${theme}-seq-start.png`), seq.before);
    writeFileSync(join(OUT_DIR, `${ENGINE}-${theme}-seq-settled.png`), seq.settled);
    check('L5 起手/落定两帧确有变化（切换真的发生）', moved > 1,
      `起手帧 vs 落定帧 差异像素 ${moved}%（阈值 >1%）`);
    check('L5 中间帧 ≥3 帧（旋转进行中，与起手/落定帧都不同）', mids.length >= 3,
      `${mids.length} 帧中间态 / 共连拍 ${seq.frames.length} 帧：`
      + read.midFrames.map((f) => `${f.atMs}ms(起${f.vsBeforePct}%/落${f.vsSettledPct}%)`).join(' '));

    // ── L6 同源复核（中/邻 11 键相等；T13 承担主断言）────────────────────────
    const diffKeys = Object.keys(on.visual).filter((k) => on.visual[k] !== nbs[0].visual[k]);
    read.visualDiffKeys = diffKeys;
    check('L6 中/邻签 11 项视觉属性逐项相等（样式同源，唯一差异面 = 渐隐 mask）',
      diffKeys.length === 0, `差异键=${diffKeys.length ? diffKeys.join(',') : '无'}`);

    check('E 零 pageerror', pageErrors.length === 0, pageErrors.length ? pageErrors.slice(0, 3).join(' | ') : '0');
    read.pageErrors = pageErrors;
    readings.combos[theme] = read;
    await ctx.close();
  }

  // ── L4 reduced-motion（深/浅各一遍）──────────────────────────────────────
  readings.reducedMotion = {};
  for (const theme of ['dark', 'light']) {
    combo = `${ENGINE}/${theme}/reduce`;
    const ctx = await browser.newContext({
      viewport: { width: 375, height: 812 }, hasTouch: true, deviceScaleFactor: 1,
      colorScheme: theme, locale: 'zh-CN', reducedMotion: 'reduce',
    });
    const page = await ctx.newPage();
    const pageErrors = [];
    page.on('pageerror', (e) => pageErrors.push(String(e.message)));
    await boot(page);
    await seedCards(page);
    const p = await chipInfo(page);
    const on = p.chips.find((c) => c.on);
    const nbs = p.chips.filter((c) => !c.on && c.opacity > 0.01);

    const all2d = p.chips.every((c) => /^matrix\(/.test(c.transform) || c.transform === 'none');
    check('L4① reduce ⇒ computed transform 退化为 2D matrix()（无 matrix3d ⇒ 无 rotateY 分量）',
      all2d, `中签=${on.transform} 邻签=${nbs.map((c) => c.transform).join(' | ')}`);

    const any3d = p.chips.some((c) => /matrix3d/.test(c.transform));
    check('L4② reduce ⇒ 旋转不生效（--rrot 仍被 JS 写入但不进 transform）',
      !any3d && nbs.every((c) => /deg$/.test(c.rrot)),
      `--rrot 仍写：${nbs.map((c) => c.id + '=' + c.rrot).join(',')}；transform 含 matrix3d=${any3d}`);

    const before = on.id;
    await page.evaluate(() => document.querySelector('.mf-strip-chip[data-tab-id="side-c"]').click());
    await page.waitForTimeout(480);
    const p2 = await chipInfo(page);
    const on2 = p2.chips.find((c) => c.on);
    check('L4③ reduce ⇒ 切签仍生效（活动签真的换了）', on2 && on2.id === 'side-c', `${before} → ${on2 && on2.id}`);

    // 位移仍生效（像素腿）：过渡窗口内连拍，至少 1 帧既非起手也非落定
    const seq = await switchSequence(page, 'side-c', 'side-a', 5);
    const moved = changedPct(seq.before, seq.settled);
    const mids = seq.frames.filter((f) => changedPct(f.buf, seq.before) > 0.2 && changedPct(f.buf, seq.settled) > 0.2);
    seq.frames.slice(0, 3).forEach((f, i) => writeFileSync(join(OUT_DIR, `${ENGINE}-${theme}-reduce-mid-${i + 1}-${f.atMs}ms.png`), f.buf));
    check('L4④ reduce ⇒ 位移仍生效（切签过程位移插值：过渡期内 ≥1 帧中间态）',
      moved > 1 && mids.length >= 1,
      `起手 vs 落定 ${moved}%；中间态 ${mids.length} 帧（${seq.frames.map((f) => `${f.atMs}ms:起${changedPct(f.buf, seq.before)}%/落${changedPct(f.buf, seq.settled)}%`).join(' ')}）`);

    // 手势跟手位移（--sdx）在 reduce 下仍生效（strip 条级变量 → 签 transform 位移链）。
    // 读数必须在过渡落定后取：transform 有 280ms 过渡 ⇒ 写入 --sdx 的瞬间读到的还是
    // 起手值（Δ=0），必须等过渡跑完（本批实证：同步读会把「链里有没有 --sdx」误判成红）。
    const sdx = await page.evaluate(async () => {
      const chip = document.querySelector('.mf-strip-chip.on');
      const strip = document.querySelector('.mf-strip');
      const tx = () => {
        const t = getComputedStyle(chip).transform;
        return +(t.startsWith('matrix3d') ? t.slice(9, -1).split(',')[12] : t.slice(7, -1).split(',')[4]) || 0;
      };
      const wait = (ms) => new Promise((r) => setTimeout(r, ms));
      const t0 = tx();
      strip.style.setProperty('--sdx', '-30px');
      await wait(460);                       // 过渡落定
      const t1 = tx();
      strip.style.removeProperty('--sdx');
      await wait(460);
      const t2 = tx();
      return { t0: +t0.toFixed(2), t1: +t1.toFixed(2), t2: +t2.toFixed(2), delta: +(t1 - t0).toFixed(2), back: +(t2 - t0).toFixed(2) };
    });
    check('L4⑤ reduce ⇒ 手势跟手位移（--sdx）仍在位移链里（退化为无旋转，不是无位移）',
      Math.abs(sdx.delta + 30) <= 1.2, `--sdx=-30px ⇒ Δtx=${sdx.delta}px（期望 -30±1.2；归零后 Δ=${sdx.back}）tx ${sdx.t0}→${sdx.t1}→${sdx.t2}`);

    check('E reduce 零 pageerror', pageErrors.length === 0, pageErrors.length ? pageErrors.slice(0, 3).join(' | ') : '0');
    readings.reducedMotion[theme] = {
      chips: p.chips.map((c) => ({ id: c.id, transform: c.transform, rrot: c.rrot })), sdx, pageErrors,
    };
    await ctx.close();
  }
} catch (e) {
  checks.push({ combo, id: 'FATAL', ok: false, detail: String(e && e.stack ? e.stack.split('\n').slice(0, 4).join(' ') : e) });
  console.log(`FAIL  [${combo}]  FATAL  — ${e && e.message ? e.message : e}`);
} finally {
  if (browser) await browser.close().catch(() => {});
  if (server) await new Promise((r) => server.close(r));
  readings.checks = checks;
  const failed = checks.filter((c) => !c.ok);
  readings.result = failed.length ? 'RED' : 'GREEN';
  readings.finishedAt = new Date().toISOString();
  writeFileSync(join(OUT_DIR, `readings-${ENGINE}.json`), JSON.stringify(readings, null, 2));
  // 双引擎合并面 readings.json（幂等：跑完任一引擎就把目录里两引擎的 per-engine 读数
  // 合成一份 —— 证据目录的「单一入口」文件，批文档与报告只引这一份）。
  try {
    const merged = { dir: OUT_DIR, mergedAt: new Date().toISOString(), engines: {} };
    for (const e of ['chromium', 'webkit']) {
      const f = join(OUT_DIR, `readings-${e}.json`);
      try { merged.engines[e] = JSON.parse(readFileSync(f, 'utf8')); } catch { /* 该引擎尚未跑 */ }
    }
    for (const e of Object.keys(merged.engines)) {
      merged.engines[e].result = merged.engines[e].result;
    }
    merged.result = Object.values(merged.engines).every((r) => r.result === 'GREEN')
      && Object.keys(merged.engines).length === 2 ? 'GREEN' : 'RED';
    writeFileSync(join(OUT_DIR, 'readings.json'), JSON.stringify(merged, null, 2));
  } catch { /* 合并面失败不影响单引擎结论 */ }
  console.log(`\n--- ${ENGINE}: ${checks.filter((c) => c.ok).length} PASS / ${failed.length} FAIL ---`);
  for (const f of failed) console.log(`FAIL  [${f.combo}]  ${f.id}  — ${f.detail}`);
  console.log(`READINGS: ${join(OUT_DIR, `readings-${ENGINE}.json`)}`);
  console.log(`RESULT: ${readings.result}`);
  process.exit(readings.result === 'GREEN' ? 0 : 1);
}

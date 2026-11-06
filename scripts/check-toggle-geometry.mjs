#!/usr/bin/env node
// check-toggle-geometry.mjs — 圆钮相对轨道「四向居中」的**像素级**门禁
// （toggle-center 批 2026-11-06；作者原话逐字：「我们的toggle按钮需要重新设计
//   中间的按钮相对于边框都没有居中」）
//
// 为什么要有这支脚本：上一批的几何是**设计值**（轨道 40×22 / 圆钮 16 / 内衬 2 /
// 行程 18），声明值自洽不等于**屏幕上**居中 —— 作者看到的是渲染后的像素偏移。
// 所以本门禁只认三样东西：
//   ① 真实级联：被服务的 HTML 把 index.html 的 <link rel=stylesheet> 全表按原顺序
//      装进来（同一套 CSS、同一顺序、同一条级联），标记由产品组件 js/toggle.js 的
//      createToggle() 产出 —— 不是重画的替身开关。
//   ② 真实盒：轨道与**圆钮（::after 伪元素）**的 border box 由浏览器布局引擎给出
//      （CDP DOM.getBoxModel，含伪元素节点），不读 CSS 声明值。
//   ③ 真实像素：把圆钮/轨道/描边涂成三种哨兵色（只改颜色、不改几何）后按
//      DSF=4 截图解码，逐像素求包围盒 —— 0.25px 分辨率，读的就是「眼睛看到的」。
//   ①②③ 三路读数必须互相吻合（差 ≤0.5px），否则判据本身失真。
//
// 判据（四向居中，单位 CSS px）：
//   V  上距 vs 下距：|top − bottom| ≤ 0.5（OFF 与 ON 两档各判一次）
//   H  左距(关) vs 右距(开)：|left(off) − right(on)| ≤ 0.5
//   C  包含：四个方向的距离都 ≥ 0（圆钮不许出轨道）
//   M  装置自检：布局盒读数 与 像素读数 的每项差 ≤ 0.5（防「量的是别的东西」）
// 覆盖矩阵：桌面档（40×22，default） × 手机档（body.mobile-layer，51×31）
//           × 亮档/暗档（html[data-theme]） × 关/开 两态 = 8 组。
//
// 用法：
//   node scripts/check-toggle-geometry.mjs                     # 门禁（红/绿）
//   node scripts/check-toggle-geometry.mjs --json              # 机器读
//   node scripts/check-toggle-geometry.mjs --web-root /tmp/before/src/main/resources/web
//   node scripts/check-toggle-geometry.mjs --mutate travel-ignores-border   # 验红
//   node scripts/check-toggle-geometry.mjs --evidence /tmp/tg-evidence      # 出特写图
// 退出码：0 = 全绿；1 = 有 FAIL（读数照旧全打印，便于改前取证）；
//         3 = 变异腿没转红（判据无载力，等同 FAIL）；2 = 装置错误。

import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  loadPlaywright, launchBrowser, setWebRoot, webRoot, settle,
} from './lib/visual-harness.mjs';
import { decodePng, encodePng } from './lib/png-diff.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, '..');

const argv = process.argv.slice(2);
const flag = (name, def = null) => {
  const i = argv.findIndex((a) => a === `--${name}` || a.startsWith(`--${name}=`));
  if (i < 0) return def;
  const tok = argv[i];
  if (tok.includes('=')) return tok.slice(tok.indexOf('=') + 1);
  const next = argv[i + 1];
  return next && !next.startsWith('--') ? next : true;
};

const TOL = 0.5;            // 四向居中容差（判据原文）
const TOL_CROSS = 0.5;      // 布局腿 ↔ 像素腿 互校容差
const DSF_DEFAULT = 4;      // 像素腿的采样密度（0.25 CSS px/物理 px）

const WEB_ROOT_OPT = flag('web-root', null);
if (WEB_ROOT_OPT) setWebRoot(resolve(REPO, WEB_ROOT_OPT));
// 读数落**临时目录**（不污染工作树：本门禁每次跑都重算，读数随控制台打印，
// 长期留档走 vault 报告；`--out` / `--evidence` 显式指定时才落盘到别处）。
const OUT = resolve(REPO, String(flag('out', join(tmpdir(), 'toggle-geometry'))));
const MUTATE = String(flag('mutate', '') || '');
const EVIDENCE = flag('evidence', null);
const JSON_OUT = argv.includes('--json');
const DSF = flag('dsf', null) ? Number(flag('dsf', DSF_DEFAULT)) : DSF_DEFAULT;

// ── 档位矩阵 ─────────────────────────────────────────────────────────────
const GEARS = [
  { id: 'desktop', label: '桌面档', width: 1280, height: 860, mobile: false },
  { id: 'mobile', label: '手机档', width: 390, height: 844, mobile: true },
];
const THEMES = ['light', 'dark'];
const STATES = ['off', 'on'];

// 哨兵色（只换颜色不换几何）：轨道底 / 轨道描边 / 圆钮（含圆钮自身描边）
const SENTINEL = {
  surface: '#808080',   // 舞台底色（三色哨兵都不会混到它）
  track: '#0000ff',     // 轨道填充
  stroke: '#00ff00',    // 轨道 1px 描边
  knob: '#ff0000',      // 圆钮填充 + 圆钮描边
};

// 变异腿：把历史故障形态注回去，证明判据不是恒真
const MUTATIONS = {
  // 上一批的**手机档真实故障形态**（改前读数 3/1/3/1）：缝写死 2px 又被 top/left
  // 当 padding box 内距用，行程按 w−钮径−2×2 算 ⇒ 圆钮下沉 1px、开档右缝塌到 1px。
  'prev-mobile-inset': {
    why: '缝只写 2px（不减描边）+ 行程不含描边 ⇒ 手机档 上3/下1、开档右缝1（改前几何）',
    css: 'body.mobile-layer .nb-toggle::after{top:2px !important;left:2px !important;}'
      + 'body.mobile-layer .nb-toggle.on::after{transform:translateX(20px) !important;}',
    expect: 'V-off',
  },
  // 行程手填回「轨道宽 − 钮径 − 2×描边」：桌面档圆钮越过轨道右缘 2px。
  'travel-ignores-inset': {
    why: '行程漏掉 2×缝（= 手抄 22px）⇒ 开档圆钮右出轨道 2px',
    css: '.nb-toggle.on::after{transform:translateX(22px) !important;}',
    expect: 'H',
  },
};

// ── 夹具页 ───────────────────────────────────────────────────────────────
const PROBE_URL = 'http://toggle-probe.local/probe.html';

/** 从产品 index.html 取样式表链接（原顺序、原集合 = 同一条级联）。 */
function stylesheetLinks(webDir) {
  const html = readFileSync(join(webDir, 'index.html'), 'utf8');
  const out = [];
  for (const m of html.matchAll(/<link\b[^>]*>/gi)) {
    const tag = m[0];
    if (!/\brel\s*=\s*["']stylesheet["']/i.test(tag)) continue;
    const href = (tag.match(/\bhref\s*=\s*["']([^"']+)["']/i) || [])[1];
    if (!href) continue;
    out.push(href);
  }
  return out;
}

function probeHtml(links, gearId, theme) {
  return `<!doctype html><html data-theme="${theme}"><head><meta charset="utf-8">
<title>toggle-geometry probe</title>
${links.map((h) => `<link rel="stylesheet" href="/${h.replace(/^\.?\//, '')}">`).join('\n')}
<style>
  html, body { margin: 0; padding: 0; background: ${SENTINEL.surface}; }
  #nf-toggle-probe {
    position: fixed; left: 20px; top: 20px; z-index: 2147483000;
    background: ${SENTINEL.surface}; padding: 20px;
    display: flex; gap: 40px; align-items: center;
  }
</style>
<script type="module">
  import { createToggle, setToggleState } from '/js/toggle.js';
  const stage = document.getElementById('nf-toggle-probe');
  const off = createToggle({ label: 'probe off' });
  const on = createToggle({ label: 'probe on', on: true });
  off.id = 'probe-off'; on.id = 'probe-on';
  stage.append(off, on);
  window.__probeReady = true;
</script>
</head>
<body class="${gearId === 'mobile' ? 'mobile-layer' : ''}">
  <div id="nf-toggle-probe"></div>
</body></html>`;
}

// ── 盒读数（布局引擎）──────────────────────────────────────────────────────
const READ_STYLES = function readStyles(sel) {
  const el = document.querySelector(sel);
  const cs = getComputedStyle(el);
  const k = getComputedStyle(el, '::after');
  const num = (v) => { const n = parseFloat(v); return Number.isFinite(n) ? n : null; };
  return {
    track: {
      width: num(cs.width), height: num(cs.height),
      borderTop: num(cs.borderTopWidth), borderRight: num(cs.borderRightWidth),
      borderBottom: num(cs.borderBottomWidth), borderLeft: num(cs.borderLeftWidth),
      paddingTop: num(cs.paddingTop), paddingRight: num(cs.paddingRight),
      paddingBottom: num(cs.paddingBottom), paddingLeft: num(cs.paddingLeft),
      boxSizing: cs.boxSizing, lineHeight: cs.lineHeight, verticalAlign: cs.verticalAlign,
      display: cs.display, position: cs.position, borderRadius: cs.borderRadius,
    },
    knob: {
      top: num(k.top), left: num(k.left), width: num(k.width), height: num(k.height),
      boxSizing: k.boxSizing, transform: k.transform,
      borderTop: num(k.borderTopWidth), borderLeft: num(k.borderLeftWidth),
    },
  };
};

const quadToBox = (q) => {
  // CDP border quad: [x1,y1, x2,y2, x3,y3, x4,y4]（TL,TR,BR,BL 顺时针）
  const xs = [q[0], q[2], q[4], q[6]];
  const ys = [q[1], q[3], q[5], q[7]];
  return { x: Math.min(...xs), y: Math.min(...ys), w: Math.max(...xs) - Math.min(...xs), h: Math.max(...ys) - Math.min(...ys) };
};

async function layoutBoxes(cdp, page, sel) {
  const { root } = await cdp.send('DOM.getDocument', { depth: -1 });
  const { nodeId } = await cdp.send('DOM.querySelector', { nodeId: root.nodeId, selector: sel });
  if (!nodeId) throw new Error(`夹具未渲染：${sel} 不存在`);
  const trackModel = await cdp.send('DOM.getBoxModel', { nodeId });
  const desc = await cdp.send('DOM.describeNode', { nodeId, depth: 0 });
  const after = (desc.node.pseudoElements || []).find((p) => p.pseudoType === 'after');
  if (!after) throw new Error(`${sel} 没有 ::after 圆钮（组件契约被破坏）`);
  const knobModel = await cdp.send('DOM.getBoxModel', { nodeId: after.nodeId });
  const track = quadToBox(trackModel.model.border);
  const knob = quadToBox(knobModel.model.border);
  return {
    track, knob,
    gaps: {
      top: +(knob.y - track.y).toFixed(3),
      bottom: +((track.y + track.h) - (knob.y + knob.h)).toFixed(3),
      left: +(knob.x - track.x).toFixed(3),
      right: +((track.x + track.w) - (knob.x + knob.w)).toFixed(3),
    },
  };
}

// ── 像素读数（哨兵色 + DSF=4 截图解码）─────────────────────────────────────
function bboxOf(img, match) {
  let x0 = Infinity; let y0 = Infinity; let x1 = -1; let y1 = -1;
  const { width, height, data } = img;
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const i = (y * width + x) * 4;
      if (!match(data[i], data[i + 1], data[i + 2], data[i + 3])) continue;
      if (x < x0) x0 = x;
      if (y < y0) y0 = y;
      if (x > x1) x1 = x;
      if (y > y1) y1 = y;
    }
  }
  if (x1 < 0) return null;
  return { x0, y0, x1, y1 };
}

/** 像素腿：截舞台 → 解码 → 三色包围盒 → 换算 CSS px。 */
async function pixelBoxes(pngBuf) {
  const img = decodePng(pngBuf);
  const isKnob = (r, g, b, a) => a > 128 && r > 100 && g < 100 && b < 100;           // 红
  const isStroke = (r, g, b, a) => a > 128 && g > 100 && r < 100 && b < 100;          // 绿
  const isTrack = (r, g, b, a) => a > 128 && b > 100 && r < 100 && g < 100;           // 蓝
  const trackBox = bboxOf(img, (r, g, b, a) => isKnob(r, g, b, a) || isStroke(r, g, b, a) || isTrack(r, g, b, a));
  const knobBox = bboxOf(img, isKnob);
  if (!trackBox || !knobBox) return null;
  const toCss = (b) => ({
    x: b.x0 / DSF, y: b.y0 / DSF,
    w: (b.x1 - b.x0 + 1) / DSF, h: (b.y1 - b.y0 + 1) / DSF,
  });
  const track = toCss(trackBox);
  const knob = toCss(knobBox);
  const r3 = (n) => +n.toFixed(3);
  return {
    track, knob, raw: { trackBox, knobBox, img: { width: img.width, height: img.height } },
    gaps: {
      top: r3(knob.y - track.y),
      bottom: r3((track.y + track.h) - (knob.y + knob.h)),
      left: r3(knob.x - track.x),
      right: r3((track.x + track.w) - (knob.x + knob.w)),
    },
  };
}

// ── 主流程 ───────────────────────────────────────────────────────────────
const results = [];
const assertions = [];
function assert(id, name, ok, detail) {
  assertions.push({ id, name, ok: !!ok, detail, gear: null });
  return ok;
}

async function measureGear(pw, gear, theme) {
  const browser = pw.browser;
  const page = await browser.newPage({
    viewport: { width: gear.width, height: gear.height },
    deviceScaleFactor: DSF,
    colorScheme: theme,
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  });
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e && e.message ? e.message : e)));

  const links = stylesheetLinks(webRoot());
  const html = probeHtml(links, gear.id, theme);
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    if (url.origin !== 'http://toggle-probe.local') return route.fulfill({ status: 404, body: '' });
    if (url.pathname === '/probe.html') {
      return route.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body: html });
    }
    const file = join(webRoot(), decodeURIComponent(url.pathname));
    const ext = file.slice(file.lastIndexOf('.'));
    const mime = {
      '.css': 'text/css', '.js': 'text/javascript', '.mjs': 'text/javascript',
      '.json': 'application/json', '.png': 'image/png', '.svg': 'image/svg+xml',
      '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf',
    }[ext] || 'application/octet-stream';
    try {
      return route.fulfill({ status: 200, contentType: mime, body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: '' });
    }
  });

  await page.goto(PROBE_URL, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => window.__probeReady === true
    && document.querySelectorAll('#nf-toggle-probe .nb-toggle').length === 2, null, { timeout: 20000 });
  await settle(page, 120);

  const cdp = await page.context().newCDPSession(page);
  await cdp.send('DOM.enable');

  if (MUTATE) {
    const m = MUTATIONS[MUTATE];
    if (!m) throw new Error(`未知变异腿 ${MUTATE}（可选：${Object.keys(MUTATIONS).join(' / ')}）`);
    await page.addStyleTag({ content: m.css });
    await settle(page, 80);
  }

  const entries = [];
  for (const state of STATES) {
    const sel = `#probe-${state}`;
    const layout = await layoutBoxes(cdp, page, sel);
    const styles = await page.evaluate(READ_STYLES, sel);
    entries.push({ state, layout, styles });
  }

  // 证据（真色，哨兵色**之前**拍）：整舞台 + 逐开关特写 + 过渡中一帧
  const stage = page.locator('#nf-toggle-probe');
  const shots = {};
  if (EVIDENCE) {
    const dir = join(resolve(REPO, String(EVIDENCE)), `${gear.id}-${theme}`);
    mkdirSync(dir, { recursive: true });
    const stagePath = join(dir, `stage@${DSF}x.png`);
    writeFileSync(stagePath, await stage.screenshot({ animations: 'disabled', scale: 'device', caret: 'hide' }));
    shots.stage = stagePath;
    for (const state of STATES) {
      const p = join(dir, `${state}@${DSF}x.png`);
      writeFileSync(p, await page.locator(`#probe-${state}`).screenshot({ animations: 'disabled', scale: 'device' }));
      shots[state] = p;
    }
    // 过渡中（180ms 曲线走一半）：把关档开关翻成开档，~90ms 后抓一帧（不冻结动效）
    await page.evaluate(() => {
      const el = document.querySelector('#probe-off');
      el.classList.add('on');
      el.setAttribute('aria-checked', 'true');
    });
    await page.waitForTimeout(90);
    const midPath = join(dir, `mid-transition@${DSF}x.png`);
    writeFileSync(midPath, await page.locator('#probe-off').screenshot({ scale: 'device', caret: 'hide' }));
    shots.mid = midPath;
    // 复原（后面还要量 ON 档的稳定盒）
    await page.evaluate(() => {
      const el = document.querySelector('#probe-off');
      el.classList.remove('on');
      el.setAttribute('aria-checked', 'false');
    });
    await settle(page, 320);
  }

  // 像素腿：哨兵色（只换色）→ 整舞台截图 → 逐像素包围盒
  await page.addStyleTag({
    content: `#nf-toggle-probe .nb-toggle,#nf-toggle-probe .nb-toggle.on{
        background:${SENTINEL.track} !important;border-color:${SENTINEL.stroke} !important;box-shadow:none !important;}` +
      `#nf-toggle-probe .nb-toggle::after{
        background:${SENTINEL.knob} !important;border-color:${SENTINEL.knob} !important;box-shadow:none !important;}`,
  });
  await settle(page, 80);
  const sentinel = await stage.screenshot({ animations: 'disabled', scale: 'device', caret: 'hide' });
  if (EVIDENCE) {
    writeFileSync(join(resolve(REPO, String(EVIDENCE)), `${gear.id}-${theme}`, `stage-sentinel@${DSF}x.png`), sentinel);
  }
  // 两个开关在同一张图里：分左右半幅各自求包围盒（舞台 gap 40px 保证不粘连）
  const img = decodePng(sentinel);
  const half = Math.floor(img.width / 2);
  const leftHalf = cropImg(img, 0, 0, half, img.height);
  const rightHalf = cropImg(img, half, 0, img.width - half, img.height);
  const pxOff = await pixelBoxes(encodePng(leftHalf.width, leftHalf.height, leftHalf.data));
  const pxOn = await pixelBoxes(encodePng(rightHalf.width, rightHalf.height, rightHalf.data));

  for (const e of entries) {
    const px = e.state === 'off' ? pxOff : pxOn;
    e.pixel = px ? px.gaps : null;
    e.pixelBoxes = px ? { track: px.track, knob: px.knob } : null;
  }

  await page.close();
  void errors;
  return { entries, shots, errors };
}

function cropImg(img, x, y, w, h) {
  const out = new Uint8Array(w * h * 4);
  for (let row = 0; row < h; row++) {
    const src = ((y + row) * img.width + x) * 4;
    out.set(img.data.subarray(src, src + w * 4), row * w * 4);
  }
  return { width: w, height: h, data: out };
}

const f2 = (n) => (n == null ? ' — ' : (Math.round(n * 100) / 100).toFixed(2));

async function run() {
  mkdirSync(OUT, { recursive: true });
  const pwRaw = loadPlaywright();
  const { browser, label } = await launchBrowser(pwRaw.mod, {});
  const pw = { browser };
  const report = {
    webRoot: webRoot(),
    browser: label,
    dsf: DSF,
    mutate: MUTATE || null,
    generatedAt: new Date().toISOString(),
    gears: {},
    assertions,
  };

  try {
    for (const gear of GEARS) {
      for (const theme of THEMES) {
        const run = await measureGear(pw, gear, theme);
        const key = `${gear.id}-${theme}`;
        const byState = Object.fromEntries(run.entries.map((e) => [e.state, e]));
        report.gears[key] = {
          gear: gear.id,
          theme,
          track: byState.off.layout.track,
          knob: byState.off.layout.knob,
          off: { layout: byState.off.layout, pixel: byState.off.pixel, styles: byState.off.styles },
          on: { layout: byState.on.layout, pixel: byState.on.pixel, styles: byState.on.styles },
          shots: run.shots,
        };
        const off = byState.off.layout.gaps;
        const on = byState.on.layout.gaps;
        const pOff = byState.off.pixel;
        const pOn = byState.on.pixel;

        // ── 判据 ──
        assert(`V-off`, `${gear.label}/${theme} 上距=下距（关档）`, Math.abs(off.top - off.bottom) <= TOL,
          `top=${f2(off.top)} bottom=${f2(off.bottom)} Δ=${f2(Math.abs(off.top - off.bottom))}`);
        assert(`V-on`, `${gear.label}/${theme} 上距=下距（开档）`, Math.abs(on.top - on.bottom) <= TOL,
          `top=${f2(on.top)} bottom=${f2(on.bottom)} Δ=${f2(Math.abs(on.top - on.bottom))}`);
        assert(`H`, `${gear.label}/${theme} 左距(关)=右距(开)`, Math.abs(off.left - on.right) <= TOL,
          `left(off)=${f2(off.left)} right(on)=${f2(on.right)} Δ=${f2(Math.abs(off.left - on.right))}`);
        const all = [off, on];
        const minGap = Math.min(...all.flatMap((g) => [g.top, g.bottom, g.left, g.right]));
        assert(`C`, `${gear.label}/${theme} 圆钮不出轨道`, minGap >= -0.01, `最小缝=${f2(minGap)}`);
        // 装置自检：布局盒 ↔ 像素盒
        let worst = 0;
        for (const [a, b] of [[off, pOff], [on, pOn]]) {
          if (!b) { worst = Infinity; continue; }
          for (const k of ['top', 'bottom', 'left', 'right']) worst = Math.max(worst, Math.abs(a[k] - b[k]));
        }
        assert(`M`, `${gear.label}/${theme} 布局读数↔像素读数吻合`, worst <= TOL_CROSS,
          `最大差=${Number.isFinite(worst) ? f2(worst) : '像素腿缺读数'}`);

        results.push({
          key, gear: gear.label, theme,
          off: { t: off.top, b: off.bottom, l: off.left, r: off.right },
          on: { t: on.top, b: on.bottom, l: on.left, r: on.right },
          pxOff: pOff, pxOn: pOn,
          track: `${+byState.off.layout.track.w.toFixed(2)}×${+byState.off.layout.track.h.toFixed(2)}`,
          knob: `${+byState.off.layout.knob.w.toFixed(2)}×${+byState.off.layout.knob.h.toFixed(2)}`,
        });
      }
    }
  } finally {
    await browser.close();
  }

  // ── 输出 ──
  const bad = assertions.filter((a) => !a.ok);
  const mutated = MUTATE ? MUTATIONS[MUTATE] : null;
  // 变异腿：只要「该红的判据」红了就算载力成立
  const mutationCaught = mutated
    ? assertions.some((a) => !a.ok && a.id === mutated.expect)
    : null;

  const payload = { ...report, assertions, rows: results, mutated: mutated ? { id: MUTATE, caught: mutationCaught } : null };
  writeFileSync(join(OUT, 'toggle-geometry.json'), JSON.stringify(payload, null, 2));

  if (JSON_OUT) {
    console.log(JSON.stringify(payload, null, 2));
  } else {
    console.log(`toggle-geometry: web=${webRoot()} browser=${label} dsf=${DSF}${MUTATE ? ` mutate=${MUTATE}` : ''}`);
    console.log('');
    console.log('  档位/主题        态   轨道      圆钮     上距   下距   左距   右距   |上−下| |左(关)−右(开)|');
    for (const r of results) {
      for (const [st, g, px] of [['关', r.off, r.pxOff], ['开', r.on, r.pxOn]]) {
        const lbl = st === '关' ? `${r.gear}/${r.theme}` : '';
        console.log(`  ${lbl.padEnd(16)} ${st}  ${r.track.padEnd(9)} ${r.knob.padEnd(8)}`
          + ` ${f2(g.t).padStart(5)} ${f2(g.b).padStart(6)} ${f2(g.l).padStart(6)} ${f2(g.r).padStart(6)}`
          + `  ${f2(Math.abs(g.t - g.b)).padStart(6)} ${(st === '关' ? f2(Math.abs(r.off.l - r.on.r)) : '').padStart(15)}`
          + (px ? `   [像素 ${f2(px.top)}/${f2(px.bottom)}/${f2(px.left)}/${f2(px.right)}]` : '   [像素腿缺读数]'));
      }
    }
    console.log('');
    for (const a of assertions) console.log(`${a.ok ? 'PASS' : 'FAIL'}  ${a.name}  — ${a.detail}`);
    console.log('');
    console.log(`读数 → ${join(OUT, 'toggle-geometry.json')}`);
    if (EVIDENCE) console.log(`特写 → ${resolve(REPO, String(EVIDENCE))}`);
  }

  if (mutated) {
    if (mutationCaught) {
      console.log(`RED-CONFIRMED: 变异腿 ${MUTATE} ⇒ 判据 ${mutated.expect} 转红（${mutated.why}）· 验红通过`);
      process.exit(0);
    }
    console.error(`✗ 变异腿 ${MUTATE} 未让判据 ${mutated.expect} 转红 ⇒ 判据无载力（等同 FAIL）`);
    process.exit(3);
  }
  if (bad.length) {
    console.error(`✗ toggle-geometry gate FAILED — ${bad.length} 条判据红`);
    for (const b of bad) console.error(`  ${b.name} — ${b.detail}`);
    process.exit(1);
  }
  console.log('✓ toggle-geometry gate passed — 圆钮四向居中（上/下、左(关)/右(开) 差 ≤ 0.5px，'
    + `桌面档 + 手机档 × 亮/暗，布局读数与像素读数吻合 ≤ ${TOL_CROSS}px）`);
  process.exit(0);
}

await run();

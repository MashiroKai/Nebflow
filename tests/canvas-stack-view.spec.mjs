// canvas-stack-view.spec.mjs — 手机 Canvas 堆叠/翻页视图三处机械断言集
// （ui-unification-20261103-canvas-stack-view，作者令 2026-11-03 12:59 实拍
// IMG_1722 / IMG_1723）。
//
// 三处（同脚本同判据，改前必红 / 改后必绿）：
//   ① 图片占比 ——
//     a 堆叠态（.mf-deck 卡叠）：卡内真实内容快照按槽宽等比铺满
//       （img 渲染宽 ≥ 快照槽内容宽 × 0.9，不得缩成中间一小块四周全白）；
//     b 展开态（阅读面 .canvas-image-viewport）：图片渲染宽 ≥ 视口宽 × 0.9
//       （不得出现「图只占 1/3–1/2 宽 + 上下巨量白」）。
//   ② 顶部环签 ——
//     a 两侧邻签文字清晰：渲染有效字号 ≥13px（--mfs 档 × --rsc），
//       WCAG 对比度（邻签字色 × 不透明度，对玻璃底合成色）≥ 4.5:1；
//     b 切换动效带「旋转感」：transform 含 rotateY 分量（非纯淡入淡出）、
//       transform 过渡时长 ≤300ms；prefers-reduced-motion 下 transform
//       无 rotate 分量（退化位移档）。
//   ③ 底部小点指示器 ——
//     a 幽灵点消除：.mf-dots::after content = none（旁侧孤立小点本体退役），
//       可见点数 === 文档卡数（n ≤ 3 一一对应档）、.on 点恰一枚；
//     b 整体水平居中：胶囊中心 x 与视口中线误差 ≤1px。
//
// 数据面 = 自包含 harness（route 静态 + WS 夹具 + /api/nf-ticket、/api/nf-file
// 票据腿喂真实 PNG 字节），零后端、零 sbt；视口 390×844（作者实拍机型档）。
// Run：PW_SELFCONTAINED_ONLY=1 npx playwright test tests/canvas-stack-view.spec.mjs
// 取证：TB_EVIDENCE_DIR=<目录> 环境变量把读数 JSON + 截图落指定目录。
import { test } from '@playwright/test';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { deflateSync } from 'node:zlib';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = process.env.TB_WEB || join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = process.env.TB_EVIDENCE_DIR || join(HERE, '..', '.nebflow', 'evidence', 'canvas-stack-view');
mkdirSync(OUT, { recursive: true });

const SID = 'csv-root-session';

/* ── PNG 编码（1024×430 白底图表样张：蓝横条 + 灰细条，宽图 = 作者实拍同族）──
   图为宽幅（2.38:1）⇒ fit-contain 的限制轴恒为宽 ⇒ 修后应铺满槽宽。
   自编码免外部夹具；CRC32 手表（node:zlib 无 crc32 的运行时兜底）。 */
const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
    t[n] = c >>> 0;
  }
  return t;
})();
function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}
function pngChunk(type, data) {
  const out = Buffer.alloc(12 + data.length);
  out.writeUInt32BE(data.length, 0);
  out.write(type, 4, 'ascii');
  data.copy(out, 8);
  out.writeUInt32BE(crc32(out.subarray(4, 8 + data.length)), 8 + data.length);
  return out;
}
/** 宽幅图表样张 PNG（1024×430）：白底 + 左侧蓝色横条族 + 右上灰字带。 */
function chartPng() {
  const W = 1024, H = 430;
  const stride = 1 + W * 3;
  const raw = Buffer.alloc(H * stride);
  const px = (x, y, r, g, b) => {
    const o = y * stride + 1 + x * 3;
    raw[o] = r; raw[o + 1] = g; raw[o + 2] = b;
  };
  for (let y = 0; y < H; y++) {
    raw[y * stride] = 0; // filter none
    for (let x = 0; x < W; x++) px(x, y, 255, 255, 255);
  }
  const bars = [
    [60, 90, 520, 26, 46, 116, 181], [60, 140, 400, 26, 46, 116, 181],
    [60, 190, 300, 26, 46, 116, 181], [60, 240, 220, 26, 46, 116, 181],
    [60, 290, 160, 26, 150, 150, 155],
  ];
  for (const [bx, by, bw, bh, r, g, b] of bars) {
    for (let y = by; y < Math.min(by + bh, H); y++) for (let x = bx; x < Math.min(bx + bw, W); x++) px(x, y, r, g, b);
  }
  for (let y = 24; y < 40; y++) for (let x = 640; x < 980; x++) px(x, y, 170, 172, 178);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(W, 0); ihdr.writeUInt32BE(H, 4);
  ihdr[8] = 8; ihdr[9] = 2; // 8-bit RGB
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', deflateSync(raw, { level: 6 })),
    pngChunk('IEND', Buffer.alloc(0)),
  ]);
}
const IMG_BYTES = chartPng();
const IMG_PATH = '/tmp/csv-fig/fig03_chart.png';

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

const PARA = (i) =>
  `第${i}段：堆叠视图三处断言的陪衬正文，写满六十个字以上让它在三百九十逻辑宽的` +
  `视口里折成三行，保证文档卡快照有真内容、阅读面有真实滚动高度，机械判据才能成立。`;
const MD_A = `# 20261031 中子能谱盘点报告\n\n${Array.from({ length: 24 }, (_, i) => PARA(i + 1)).join('\n\n')}\n`;
const MD_B = `# 20261103 剂量台账与结论速结\n\n${Array.from({ length: 24 }, (_, i) => PARA(i + 1)).join('\n\n')}\n`;

test.use({ viewport: { width: 390, height: 844 }, hasTouch: true, colorScheme: 'light' });

test.describe('canvas stack view · 三处机械断言', () => {
  test('①a deck thumb fills · ①b reading image ≥0.9 · ② ring labels legible+rotate · ③ dots exact+centered', async ({ page }) => {
    const failures = [];
    const readings = {};
    page.on('pageerror', (e) => {
      (readings.pageErrors = readings.pageErrors || []).push(String(e.message).slice(0, 200));
    });
    const check = (name, cond, extra = '') => {
      // eslint-disable-next-line no-console
      console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
      if (!cond) failures.push(name);
      return cond;
    };

    await page.addInitScript(() => {
      localStorage.setItem('nebflow_token', 'e2e-token');
      localStorage.setItem('nebflow_locale', 'zh-CN');
      localStorage.setItem('stand_sw', '0');
      Object.defineProperty(navigator.serviceWorker, 'register', {
        value: () => new Promise(() => {}), configurable: true,
      });
    });
    await page.route('**/*', (route) => {
      const url = new URL(route.request().url());
      const p0 = decodeURIComponent(url.pathname);
      const p = p0 === '/' ? '/index.html' : p0;
      if (p.startsWith('/api/')) {
        if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
        if (p === '/api/nf-ticket') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ tickets: { [IMG_PATH]: 'tk-csv-1' } }) });
        if (p === '/api/nf-file') return route.fulfill({ status: 200, contentType: 'image/png', body: IMG_BYTES });
        return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
      }
      const file = normalize(join(WEB, p));
      if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
      try {
        return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
      } catch {
        return route.fulfill({ status: 404, body: 'not found' });
      }
    });
    await page.routeWebSocket(/\/ws/, (ws) => {
      const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
      const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
      sendConfig(); sendSessions();
      ws.onMessage((raw) => {
        let m; try { m = JSON.parse(raw); } catch { return; }
        if (m.type === 'getConfig') sendConfig();
        else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
        else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
        else if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
      });
    });
    await page.goto('http://localhost:8899/');
    await page.waitForFunction(async (rootSid) => {
      const s = (await import('/js/state.js')).default;
      return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
    }, SID, { timeout: 20000 });
    await page.waitForSelector('body.mobile-layer', { timeout: 15000 });

    // 开三张文档：两张 md（长名 = 作者实拍「2026…」档）+ 一张真实图片查看器。
    // md 走 workspace-open-item（渲染链真实）；图片走 openTab + renderFile
    // （fileViewers 派发 → viewers/image.js viewImage → 票据腿取真 PNG 字节）。
    await page.waitForTimeout(3400); // watchCanvasEvents 3s 启动抑制窗（同 topbar 夹具）
    await page.evaluate(({ a, b }) => {
      for (const it of [a, b]) {
        window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: it }));
      }
    }, {
      a: { id: 'file:docs/20261031_zhongzinengpu_pandian.md', itemType: 'markdown', title: '20261031_中子能谱盘点报告', content: MD_A, absPath: '/tmp/docs/a.md', size: MD_A.length, pinned: true },
      b: { id: 'file:docs/20261103_jiliang_taizhang.md', itemType: 'markdown', title: '20261103_剂量台账与结论速结', content: MD_B, absPath: '/tmp/docs/b.md', size: MD_B.length, pinned: true },
    });
    await page.waitForSelector('.mobile-page[data-page="canvas"].live', { timeout: 12000 });
    await page.waitForTimeout(400);
    await page.evaluate(async (bytesLen) => {
      const canvas = await import('/js/canvas.js');
      const entry = canvas.openTab('file:docs/fig03_chart.png', 'fig03_任务谱剂量占比.png', { type: 'png-image-csv', pinned: true });
      const { renderFile } = await import('/js/fileViewers.js');
      await renderFile(entry.paneEl, {
        itemType: 'image', content: '', absPath: '/tmp/csv-fig/fig03_chart.png', fileName: 'fig03_任务谱剂量占比.png', size: bytesLen,
      });
      canvas.setActiveTab('file:docs/fig03_chart.png');
    }, IMG_BYTES.length);
    await page.waitForSelector('.canvas-image-viewport img', { timeout: 12000 });
    await page.waitForTimeout(900); // fit + RO 收敛窗

    /* ── 读数器：环签 / 小点 / 图片三面一次取齐 ─────────────────────── */
    const probe = () => page.evaluate(() => {
      const q = (sel, root = document) => root.querySelector(sel);
      const qa = (sel, root = document) => [...root.querySelectorAll(sel)];
      const sec = q('#mobile-pages .mobile-page.live[data-page="canvas"]') || document;
      const rect = (el) => {
        if (!el) return null;
        const b = el.getBoundingClientRect();
        return { x: +b.x.toFixed(1), y: +b.y.toFixed(1), w: +b.width.toFixed(1), h: +b.height.toFixed(1), cx: +(b.x + b.width / 2).toFixed(1), cy: +(b.y + b.height / 2).toFixed(1) };
      };
      /** WCAG 相对亮度 + 对比度（sRGB）。 */
      const lum = (r, g, b) => {
        const f = (c) => { c /= 255; return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); };
        return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
      };
      const ratio = (l1, l2) => {
        const hi = Math.max(l1, l2), lo = Math.min(l1, l2);
        return (hi + 0.05) / (lo + 0.05);
      };
      const parseColor = (s) => {
        const m = s.match(/rgba?\(([^)]+)\)/);
        if (!m) return null;
        const parts = m[1].split(/[,/\s]+/).filter(Boolean).map(parseFloat);
        return { r: parts[0], g: parts[1], b: parts[2], a: parts.length > 3 ? parts[3] : 1 };
      };
      const blend = (fg, bg) => ({
        r: Math.round(fg.r * fg.a + bg.r * (1 - fg.a)),
        g: Math.round(fg.g * fg.a + bg.g * (1 - fg.a)),
        b: Math.round(fg.b * fg.a + bg.b * (1 - fg.a)),
      });
      const out = { vw: window.innerWidth };
      // ── 环签 ──
      const strip = q('.mf-strip', sec);
      const chips = qa('.mf-strip-chip', strip || sec).map((c) => {
        const cs = getComputedStyle(c);
        return {
          id: c.dataset.tabId, on: c.classList.contains('on'), far: c.classList.contains('is-far'),
          side: c.classList.contains('side-l') ? 'l' : (c.classList.contains('side-r') ? 'r' : ''),
          rect: rect(c), op: +cs.opacity,
          transform: cs.transform, transitionProperty: cs.transitionProperty,
          transitionDuration: cs.transitionDuration.split(',').map((s) => s.trim()),
          fontSize: parseFloat(cs.fontSize), rsc: parseFloat(c.style.getPropertyValue('--rsc')) || 1,
          color: cs.color, rfade: c.style.getPropertyValue('--rfade').trim(),
          rrot: c.style.getPropertyValue('--rrot').trim(),
          perspective: getComputedStyle(c.parentElement || c).perspective,
        };
      });
      // 邻签（|k|=1 可见非中签）对比度：字色 × (op × label 自身 alpha=1)，对
      // 玻璃底合成色（::after --glass-bg 叠页面底）。
      const pageBg = parseColor(getComputedStyle(document.body).backgroundColor) || { r: 255, g: 255, b: 255, a: 1 };
      const glassBg = parseColor(getComputedStyle(q('.mf-strip-chip', sec), '::after').backgroundColor) || { r: 255, g: 255, b: 255, a: 0.55 };
      const chipBase = blend(glassBg, blend(pageBg, { r: 255, g: 255, b: 255, a: 1 }));
      out.chips = chips;
      out.chipBase = chipBase;
      out.contrast = chips.filter((c) => !c.on && !c.far && c.side).map((c) => {
        const tc = parseColor(c.color);
        const eff = { ...tc, a: tc.a * c.op };
        const ink = blend(eff, chipBase);
        return { id: c.id, side: c.side, op: c.op, effFontPx: +(c.fontSize * c.rsc).toFixed(2), contrast: +ratio(lum(ink.r, ink.g, ink.b), lum(chipBase.r, chipBase.g, chipBase.b)).toFixed(2) };
      });
      const onChip = chips.find((c) => c.on);
      if (onChip) {
        const tc = parseColor(onChip.color);
        const ink = blend({ ...tc, a: tc.a * onChip.op }, chipBase);
        out.contrastOn = +ratio(lum(ink.r, ink.g, ink.b), lum(chipBase.r, chipBase.g, chipBase.b)).toFixed(2);
      }
      // ── 小点 ──
      const dots = q('.mf-dots', sec);
      const dotsAfter = dots ? getComputedStyle(dots, '::after') : null;
      out.dots = {
        present: !!dots, hidden: dots ? dots.hidden : null,
        dotCount: dots ? qa('.mf-dot', dots).length : 0,
        onCount: dots ? qa('.mf-dot.on', dots).length : 0,
        rect: rect(dots),
        afterContent: dotsAfter ? dotsAfter.content : null,
        docCount: qa('.mf-strip-chip', strip || sec).length,
      };
      // ── 阅读面图片 ──
      const vp = q('.canvas-image-viewport', sec);
      const img = q('.canvas-image-viewport img', sec);
      out.readImg = {
        vp: rect(vp), img: rect(img),
        imgComplete: img ? img.complete : null,
        natural: img ? [img.naturalWidth, img.naturalHeight] : null,
        transform: img ? getComputedStyle(img).transform : null,
      };
      return out;
    });

    /* ── ② 环签（阅读态即量）＋ ③ 小点 ─────────────────────────────── */
    const r1 = await probe();
    readings.ringDots = r1;

    // ②a 两侧邻签：有效字号 ≥13px + 对比度 ≥4.5
    const sideChips = r1.contrast;
    check('②a-side-count ≥1 visible side chip', sideChips.length >= 1, JSON.stringify(sideChips));
    for (const c of sideChips) {
      check(`②a-font ≥13px (${c.id} ${c.side})`, c.effFontPx >= 13 - 0.05, `eff=${c.effFontPx}px`);
      check(`②a-contrast ≥4.5 (${c.id} ${c.side})`, c.contrast >= 4.5, `ratio=${c.contrast} op=${c.op}`);
    }
    const onC = r1.chips.find((c) => c.on);
    const sideMax = r1.chips.filter((c) => !c.on && !c.far).sort((a, b) => b.rect.w - a.rect.w)[0];
    if (onC && sideMax) check('②-hierarchy center wider than side', onC.rect.w > sideMax.rect.w, `on=${onC.rect.w} side=${sideMax.rect.w}`);

    // ②b 切换动效：transform 含 rotateY、transform 过渡 ≤300ms（非纯淡入淡出）。
    const anyChip = r1.chips[0];
    check('②b-transition-property includes transform', /\btransform\b/.test(anyChip.transitionProperty), anyChip.transitionProperty);
    const tDur = anyChip.transitionDuration;
    const transformDur = (() => {
      const props = anyChip.transitionProperty.split(',').map((s) => s.trim());
      const i = props.indexOf('transform');
      return i >= 0 ? parseFloat(tDur[i] || tDur[0]) : NaN;
    })();
    check('②b-transform-transition ≤300ms', Number.isFinite(transformDur) && transformDur > 0 && transformDur <= 300, `dur=${transformDur}ms all=[${tDur}]`);
    const visibleSides = r1.chips.filter((c) => !c.on && !c.far);
    // 计算样式把 rotateY 解析成 matrix3d（不是字面 'rotateY('）—— 判据两面：
    // ① --rrot 自定义属性非零（JS 位姿源）；② 计算 transform 是 3D 矩阵且
    // 平面旋转项 m13/m31（数组下标 2/8）非零 = 真转过去了（不是只写了个变量）。
    const rot3d = (t) => {
      if (!t || !t.startsWith('matrix3d(')) return 0;
      const v = t.slice(9, -1).split(',').map((x) => parseFloat(x));
      return Math.max(Math.abs(v[2] || 0), Math.abs(v[8] || 0));
    };
    check('②b-rest side chip carries rotateY (carousel pose)',
      visibleSides.length > 0
      && visibleSides.every((c) => parseFloat(c.rrot) !== 0 && rot3d(c.transform) > 0.1),
      visibleSides.map((c) => `${c.id}:rrot=${c.rrot} m13=${rot3d(c.transform).toFixed(3)}`).join(' | '));
    // 中签恒正面（0°）：转盘只在两侧，中心签文字不吃透视变形。
    check('②b-center chip stays front-facing (no rotation)',
      !!onC && Math.abs(parseFloat(onC.rrot || '0')) < 0.01 && rot3d(onC.transform) < 1e-6,
      onC ? `rrot=${onC.rrot} m13=${rot3d(onC.transform)}` : 'no center');

    // 切一签，过渡中段采样（旋转在动：中签 transform 处于位移+旋转插值中）。
    const switchProbe = await page.evaluate(async () => {
      const nav = await import('/js/mobile/navController.js');
      nav.switchDocCard(1);
      await new Promise((res) => setTimeout(res, 120)); // 280ms 曲线的 ~43% 处
      const chip = document.querySelector('.mf-strip-chip.on');
      return { transform: chip ? getComputedStyle(chip).transform : null };
    });
    readings.switchFrame = switchProbe;
    check('②b-switch animates transform (mid-flight ≠ identity)', !!switchProbe.transform && switchProbe.transform !== 'none'
      && !/^matrix\(1, 0, 0, 1, 0, 0\)$/.test(switchProbe.transform), switchProbe.transform);
    await page.waitForTimeout(420);

    // reduced-motion 退化：媒体特性切换即时生效（计算样式重算），量邻签 transform。
    // 采样必须**等过渡落定**（切媒体档本身也是一次 transform 变更，280ms 曲线
    // 中途读到的是插值帧 matrix3d —— 会假绿）。
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.waitForTimeout(600);
    const rmChip = await page.evaluate(() => {
      const c = document.querySelector('.mf-strip-chip:not(.on):not(.is-far)') || document.querySelector('.mf-strip-chip');
      const cs = getComputedStyle(c);
      return {
        transform: cs.transform,
        transitionDuration: cs.transitionDuration,
        rrot: c.style.getPropertyValue('--rrot').trim(),
      };
    });
    readings.reducedMotion = rmChip;
    check('②b-reduced-motion no rotate component (2D matrix, m13 = 0)',
      !rmChip.transform.startsWith('matrix3d('), rmChip.transform);
    await page.emulateMedia({ reducedMotion: 'no-preference' });
    await page.waitForTimeout(420);

    // ③ 小点：可见点数 === 卡数（3 卡档）+ on 唯一 + ::after 幽灵点退役 + 居中 ≤1px
    const r2 = await probe();
    readings.dotsAfterSwitch = r2.dots;
    check('③a-dots visible (n≥2)', r2.dots.present && r2.dots.hidden === false, JSON.stringify(r2.dots));
    check('③a-dot count === doc count', r2.dots.dotCount === r2.dots.docCount, `dots=${r2.dots.dotCount} docs=${r2.dots.docCount}`);
    check('③a-active dot unique', r2.dots.onCount === 1, `on=${r2.dots.onCount}`);
    check('③a-no ghost dot (::after content none)', r2.dots.afterContent === 'none', `after=${r2.dots.afterContent}`);
    const centerErr = Math.abs(r2.dots.rect.cx - r2.vw / 2);
    readings.centerErr = +centerErr.toFixed(2);
    check('③b-pill centered ≤1px', centerErr <= 1, `cx=${r2.dots.rect.cx} vw/2=${r2.vw / 2} err=${centerErr.toFixed(2)}px`);

    /* ── ①b 展开态（阅读面）：图片渲染宽 ≥ 视口宽 × 0.9 ─────────────── */
    // 回到图片卡（上面切了一签）。
    await page.evaluate(async () => {
      const canvas = await import('/js/canvas.js');
      canvas.setActiveTab('file:docs/fig03_chart.png');
    });
    await page.waitForSelector('.canvas-image-viewport img', { timeout: 8000 });
    await page.waitForTimeout(700);
    await page.screenshot({ path: join(OUT, 'after-reading-view.png'), fullPage: false });
    const r3 = await probe();
    readings.reading = r3.readImg;
    const vpW = r3.readImg.vp ? r3.readImg.vp.w : 0;
    const imgW = r3.readImg.img ? r3.readImg.img.w : 0;
    check('①b-reading image width ≥ viewport×0.9', vpW > 0 && imgW >= vpW * 0.9 - 0.5, `img=${imgW} vp=${vpW} ratio=${vpW ? (imgW / vpW).toFixed(3) : 'n/a'}`);
    const imgH = r3.readImg.img ? r3.readImg.img.h : 0;
    const vpH = r3.readImg.vp ? r3.readImg.vp.h : 0;
    readings.readingAspect = { imgW, imgH, natural: r3.readImg.natural };
    // 等比不畸变（宽高比 = 自然比 ±2%）——负面清单里「图被压成一块」的反证。
    const nat = r3.readImg.natural || [1, 1];
    const aspectErr = imgW > 0 && imgH > 0 ? Math.abs((imgW / imgH) / (nat[0] / nat[1]) - 1) : 1;
    check('①b-reading image keeps natural aspect (≤2%)', aspectErr <= 0.02, `rendered=${imgW}x${imgH} natural=${nat[0]}x${nat[1]} err=${(aspectErr * 100).toFixed(2)}%`);

    /* ── ①b' 换容器（横屏）后必须重适屏 —— 作者实拍「图只占 1/3–1/2 宽」的
       成因面：图片查看器把适屏比例算在**旧容器**上，一旦用户在图片上轻触过
       （userTouched 一票否决 refresize 重适屏），容器变宽后图就停在旧像素宽
       上 ⇒ 新容器里只剩一小条。本判据：轻触 → 转横屏 → 图片仍须铺满 ≥0.9。 */
    await page.evaluate(() => {
      const img = document.querySelector('.canvas-image-viewport img');
      img.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, pointerType: 'touch', pointerId: 7, clientX: 100, clientY: 300 }));
      img.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, pointerType: 'touch', pointerId: 7, clientX: 100, clientY: 300 }));
    });
    await page.waitForTimeout(160);
    await page.setViewportSize({ width: 700, height: 390 }); // 横屏（≤768 保持手机层）
    await page.waitForTimeout(900);
    const rLand = await probe();
    readings.landscape = rLand.readImg;
    const vpWl = rLand.readImg.vp ? rLand.readImg.vp.w : 0;
    const imgWl = rLand.readImg.img ? rLand.readImg.img.w : 0;
    // 门槛 0.9 → 0.8（方案 C 落地批 2026-11-06，实测标定，不是感觉）：
    // 顶栏从 72 长到 92（玻璃带 64 + 下缘渐隐 28），正文首行必须让到渐隐带**下沿**
    // 之外（让 64 会让首行整块落在 veil 里）。横屏 700×390 下正文面少 20px ⇒
    // 高度受限的阅读图按 aspect-fit 同比缩：让 64 实测 ratio=0.941、让 92 实测
    // ratio=0.841。本判据的语义是「换向后面真的重新贴合容器了（不是停在小块）」，
    // 0.8 仍能把「没 refit」的旧退化（<0.5）挡住，故按实测重定标。
    check('①b2-after container change (landscape) image refits ≥vp×0.8 (顶栏 92 后实测 0.841)',
      vpWl > 0 && imgWl >= vpWl * 0.8 - 0.5, `img=${imgWl} vp=${vpWl} ratio=${vpWl ? (imgWl / vpWl).toFixed(3) : 'n/a'}`);
    await page.screenshot({ path: join(OUT, 'after-reading-landscape.png'), fullPage: false });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.waitForTimeout(700);

    /* ── ①a 堆叠态：开卡叠，量中央卡快照宽 ────────────────────────── */
    await page.click('.mf-cv-stack');
    await page.waitForSelector('.mf-stack-view.open', { timeout: 8000 });
    await page.waitForSelector('.mf-deck-card.on .mf-deck-thumb.has-snap', { timeout: 12000 });
    await page.waitForTimeout(700); // 快照挂载 + 卡面落位
    const deck = await page.evaluate(() => {
      const q = (s, r = document) => r.querySelector(s);
      const card = q('.mf-deck-card.on');
      const snap = card ? card.querySelector('.mf-deck-snap') : null;
      const img = card ? card.querySelector('.mf-deck-snap-img') : null;
      const inner = card ? card.querySelector('.mf-deck-snap-inner') : null;
      const rect = (el) => {
        if (!el) return null;
        const b = el.getBoundingClientRect();
        return { x: +b.x.toFixed(1), y: +b.y.toFixed(1), w: +b.width.toFixed(1), h: +b.height.toFixed(1) };
      };
      const thumb = card ? card.querySelector('.mf-deck-thumb') : null;
      return {
        cards: [...document.querySelectorAll('.mf-deck-card')].map((c) => ({ id: c.dataset.tabId, on: c.classList.contains('on') })),
        snap: rect(snap), img: rect(img), inner: rect(inner), thumb: rect(thumb),
        imgTag: img ? { tag: img.tagName, cls: img.className, inlineTransform: img.style.transform || '', complete: img.complete, fit: getComputedStyle(img).objectFit } : null,
        innerStyle: inner ? { w: inner.style.width || '', h: inner.style.height || '', transform: inner.style.transform || '' } : null,
        countText: q('.mf-stack-count') ? q('.mf-stack-count').textContent : '',
      };
    });
    readings.deck = deck;
    check('①a-deck open with center card', !!deck.snap && !!deck.img, JSON.stringify(deck.cards));
    if (deck.snap && deck.img) {
      const ratio = deck.img.w / deck.snap.w;
      readings.deckFill = { imgW: deck.img.w, snapW: deck.snap.w, ratio: +ratio.toFixed(3) };
      check('①a-stack thumb width ≥ slot×0.9 (not a tiny block)', ratio >= 0.9, `img=${deck.img.w} slot=${deck.snap.w} ratio=${ratio.toFixed(3)}`);
      // 居中（不得缩成一小块贴角）：img 中心与槽中心 ≤ 槽宽 5%。
      const r = readings.deckFill;
      const offX = Math.abs((deck.img.x + deck.img.w / 2) - (deck.snap.x + deck.snap.w / 2));
      check('①a-stack thumb centered in slot', offX <= deck.snap.w * 0.05, `offX=${offX.toFixed(1)} slotW=${deck.snap.w}`);
      void r;
    } else {
      check('①a-stack thumb width ≥ slot×0.9 (not a tiny block)', false, 'snapshot img missing');
      check('①a-stack thumb centered in slot', false, 'snapshot img missing');
    }
    await page.screenshot({ path: join(OUT, 'after-deck-view.png'), fullPage: false });

    writeFileSync(join(OUT, 'readings.json'), JSON.stringify(readings, null, 2));
    check('zero page errors', !(readings.pageErrors && readings.pageErrors.length), JSON.stringify(readings.pageErrors || []));
    if (failures.length) throw new Error(`canvas-stack-view assertions failed (${failures.length}): ${failures.join(' | ')}`);
  });
});

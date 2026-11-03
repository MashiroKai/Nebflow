// mobile-canvas-topbar-semantic.spec.mjs — 手机端 Canvas 阅读面顶栏「去边界 + 透传
// 悬浮」机械断言集（ui-unification-20261103-mobile-canvas-topbar）。
//
// 作者令（2026-11-03 实拍 IMG_1718 / IMG_1719）：「顶栏不要有一个明显的边缘，
// 而是能让文字从下方穿过，呈现出一种胶囊和按钮悬浮在上面、正好可以显示它们毛玻璃
// 效果的感觉」——与主对话窗同一语义（初态内容在控件下方；滚动后从控件底下穿过，
// 层次只由控件自己的毛玻璃承载）。
//
// 四套断言（同脚本同判据，改前必红 / 改后必绿）：
//   A 顶栏容器零视觉底 —— ① .mf-cv-toolbar / ② .mf-strip / ③ 阅读面滚动容器顶缘
//     == 页面顶缘（=「边界」本体：旧形态带占流内 72px，滚动容器在其下硬裁正文）；
//   B 初态不重叠 —— ① 初态让位读数 = 手机顶栏簇几何单源 --mchrome-clear
//     （簇顶 16 + 钮 48 + 呼吸 8 = 72；旧形态用桌面胶囊档 52 ≠ 72 必红）；
//     ② 静止帧首条内容 top ≥ 控件底缘 + 8 呼吸（几何面）；
//   C 滚动穿透实证（像素级）—— 滚到中段后，顶栏带内「控件间自由柱」出现正文
//     墨像素（旧形态滚容器起于 72，带内永远是空底 ⇒ 必红）；
//   D 控件自持毛玻璃 —— ① 双钮 backdrop-filter 在岗、中签玻璃层无 mask × backdrop
//     同元素（已知坑①）、签宿主零描边（已知坑③）；② 控件底缘 2..8px 带内出现
//     正文墨像素（正文贴着控件下沿穿过 =「悬浮在上面」读数）；③ 穿透态截图落盘。
//   E 同一类族 —— 主对话窗悬浮层（#mobile-fab-layer）与 Canvas 工具条（.mf-cv-toolbar）
//     均挂 .ds-chrome-band（design-system.css 单源零底带类），计算样式零底逐项同值。
//
// 数据面 = 自包含 harness（route 静态 + WS 夹具），零后端、零 sbt；视口 390×844。
// Run：PW_SELFCONTAINED_ONLY=1 npx playwright test tests/mobile-canvas-topbar-semantic.spec.mjs
// 取证：TB_EVIDENCE_DIR=<目录> 环境变量把读数 JSON + 截图落指定目录。
import { test } from '@playwright/test';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { inflateSync } from 'node:zlib';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = process.env.TB_WEB || join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = process.env.TB_EVIDENCE_DIR || join(HERE, '..', '.nebflow', 'evidence', 'mobile-canvas-topbar');
mkdirSync(OUT, { recursive: true });

const SID = 'tb-topbar-session';
const DOC_TITLE = '20261103_11';

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

/* 长正文：每段 ≥60 个 CJK 字符（390 宽 17px 档 ≈ 20 字/行 ⇒ 每段 ≥3 行），保证
   滚动中段后带内自由柱 / 钮下窄带都有行穿越。page.evaluate 只送源码不送闭包 ⇒
   内容必须在 Node 侧拼好当参数传进页面。 */
const PARA = (i) =>
  `第${i}段：手机端阅读面顶栏透传语义验证正文。为了在滚动到中段之后让带内自由柱与` +
  `控件底缘窄带都出现正文墨像素，本段刻意写满六十个字以上，让它在三百九十逻辑宽的` +
  `视口里折成三行以上，从而覆盖顶栏高度带与控件下沿窄带两个采样区，机械判据才能成立。`;
const DOC_CONTENT = `# ${DOC_TITLE} 阅读面\n\n${Array.from({ length: 30 }, (_, i) => PARA(i + 1)).join('\n\n')}\n`;

test.use({ viewport: { width: 390, height: 844 }, hasTouch: true, colorScheme: 'light' });

/* ── PNG 解码 + 墨像素取证（与 tests/uifix-glass.spec.mjs 同一实现）───────── */
function decodePng(buf) {
  let off = 8, W = 0, H = 0, colorType = 6;
  const idat = [];
  while (off + 8 <= buf.length) {
    const len = buf.readUInt32BE(off);
    const type = buf.toString('ascii', off + 4, off + 8);
    const data = buf.subarray(off + 8, off + 8 + len);
    if (type === 'IHDR') { W = data.readUInt32BE(0); H = data.readUInt32BE(4); colorType = data[9]; }
    else if (type === 'IDAT') idat.push(data);
    off += 12 + len;
    if (type === 'IEND') break;
  }
  const ch = colorType === 6 ? 4 : colorType === 2 ? 3 : 1;
  const raw = inflateSync(Buffer.concat(idat));
  const stride = W * ch;
  const out = Buffer.alloc(H * stride);
  let p = 0;
  for (let y = 0; y < H; y++) {
    const filter = raw[p++];
    const row = raw.subarray(p, p + stride); p += stride;
    const cur = out.subarray(y * stride, (y + 1) * stride);
    const prev = y > 0 ? out.subarray((y - 1) * stride, y * stride) : null;
    for (let x = 0; x < stride; x++) {
      const a = x >= ch ? cur[x - ch] : 0;
      const b = prev ? prev[x] : 0;
      const c = x >= ch && prev ? prev[x - ch] : 0;
      let v = row[x];
      if (filter === 1) v = (v + a) & 255;
      else if (filter === 2) v = (v + b) & 255;
      else if (filter === 3) v = (v + ((a + b) >> 1)) & 255;
      else if (filter === 4) {
        const pa = Math.abs(b - c), pb = Math.abs(a - c), pc = Math.abs(a + b - 2 * c);
        v = (v + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c)) & 255;
      }
      cur[x] = v;
    }
  }
  const lum = (x, y) => {
    const i = (y * W + x) * ch;
    return ch >= 3 ? 0.299 * out[i] + 0.587 * out[i + 1] + 0.114 * out[i + 2] : out[i];
  };
  return { W, H, ch, lum };
}

/** 墨像素计数：clip 区内亮度偏离「区内众数底色」超过阈值的像素数。
 *  亮档正文近墨（底近白），暗档反相 —— 众数底 + 偏差判据两档通用。 */
async function inkPixels(page, clip, dev = 42) {
  const buf = await page.screenshot({ clip });
  const { W, H, lum } = decodePng(buf);
  const hist = new Array(256).fill(0);
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) hist[Math.round(lum(x, y))]++;
  const base = hist.indexOf(Math.max(...hist));
  let ink = 0;
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) if (Math.abs(lum(x, y) - base) > dev) ink++;
  return { ink, total: W * H, base };
}

test.describe('mobile canvas topbar zero-base + scroll-under', () => {
  test('A zero visual base · B initial clearance · C pass-through pixels · D glass integrity · E same class family', async ({ page }) => {
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
      const p0 = decodeURIComponent(new URL(route.request().url()).pathname);
      const p = p0 === '/' ? '/index.html' : p0;
      if (p.startsWith('/api/')) {
        if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
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

    // 开一张长 markdown 文档（生产同一条开法：workspace-open-item → openTab →
    // renderFile；手机层 navController.watchCanvasEvents 收 canvas-tab-switched
    // 顶起 canvas 页）。⚠ watchCanvasEvents 有 3s 启动抑制窗（boot 恢复路径不
    // 拽页）—— 夹具等它过期后再派发，走「画板已开时点文件卡」这条真实路径
    // （maybeAdopt('tab-switched') → dockTo('canvas')）。
    await page.waitForTimeout(3400);
    await page.evaluate(({ title, content }) => {
      window.dispatchEvent(new CustomEvent('workspace-open-item', {
        detail: {
          id: 'file:docs/' + title + '.md',
          itemType: 'markdown',
          title,
          content,
          absPath: `/tmp/docs/${title}.md`,
          size: content.length,
        },
      }));
    }, { title: DOC_TITLE, content: DOC_CONTENT });
    await page.waitForSelector('.mobile-page[data-page="canvas"].live', { timeout: 12000 });
    await page.waitForSelector('.mobile-page[data-page="canvas"] .canvas-md-scroll', { timeout: 12000 });
    await page.waitForTimeout(600);

    /* ── 几何读数 ─────────────────────────────────────────────────────── */
    const geo = await page.evaluate(() => {
      const q = (sel, root = document) => root.querySelector(sel);
      const sec = q('#mobile-pages .mobile-page.live[data-page="canvas"]');
      const r = (el) => {
        if (!el) return null;
        const b = el.getBoundingClientRect();
        return { top: +b.top.toFixed(1), bottom: +b.bottom.toFixed(1), left: +b.left.toFixed(1), right: +b.right.toFixed(1), w: +b.width.toFixed(1), h: +b.height.toFixed(1) };
      };
      const cs = (el, pe = null) => {
        if (!el) return null;
        const s = pe ? getComputedStyle(el, pe) : getComputedStyle(el);
        const pick = (names) => Object.fromEntries(names.map((n) => [n, s[n] === undefined ? null : String(s[n])]));
        return pick([
          'position', 'backgroundColor', 'backgroundImage', 'boxShadow',
          'borderTopWidth', 'borderRightWidth', 'borderBottomWidth', 'borderLeftWidth',
          'borderTopStyle', 'pointerEvents', 'paddingTop', 'maskImage', 'webkitMaskImage',
          'backdropFilter', 'webkitBackdropFilter', 'zIndex', 'height', 'content',
        ]);
      };
      const scroller = q('.canvas-md-scroll', sec);
      const mdPage = q('.mf-canvas-page', sec);
      const toolbar = q('.mf-cv-toolbar', sec);
      const strip = q('.mf-strip', sec);
      const close = q('.mf-cv-close', sec);
      const stack = q('.mf-cv-stack', sec);
      const chipOn = q('.mf-strip-chip.on', sec) || q('.mf-strip-chip', sec);
      const viewer = q('.canvas-md-viewer', sec);
      const firstBlock = viewer ? viewer.firstElementChild : null;
      const fabLayer = document.getElementById('mobile-fab-layer');
      return {
        diag: {
          stripHidden: strip ? strip.hidden : null,
          stripChips: strip ? strip.querySelectorAll('.mf-strip-chip').length : null,
          chipClasses: strip ? [...strip.querySelectorAll('.mf-strip-chip')].slice(0, 3).map((c) => c.className) : null,
          pageErrors: (window.__tbPageErrors || []),
        },
        safeTop: getComputedStyle(sec.parentElement || sec).paddingTop,
        chromeClear: getComputedStyle(document.body).getPropertyValue('--mchrome-clear').trim(),
        scroll: { rect: r(scroller), cs: cs(scroller), top: scroller ? scroller.scrollTop : null },
        page: { rect: r(mdPage) },
        toolbar: { rect: r(toolbar), cs: cs(toolbar), cls: toolbar ? toolbar.className : null, before: cs(toolbar, '::before'), after: cs(toolbar, '::after') },
        strip: { rect: r(strip), cs: cs(strip), cls: strip ? strip.className : null },
        close: { rect: r(close), cs: cs(close) },
        stack: { rect: r(stack), cs: cs(stack) },
        chipOn: { rect: r(chipOn), hostCs: cs(chipOn), afterCs: chipOn ? cs(chipOn, '::after') : null },
        firstContent: r(firstBlock),
        viewerPadTop: viewer ? getComputedStyle(viewer).paddingTop : null,
        scrollerPadTop: scroller ? getComputedStyle(scroller).paddingTop : null,
        fab: { rect: r(fabLayer), cs: cs(fabLayer), cls: fabLayer ? fabLayer.className : null },
        dpr: window.devicePixelRatio,
        scrollHeight: scroller ? scroller.scrollHeight : 0,
        clientHeight: scroller ? scroller.clientHeight : 0,
      };
    });
    readings.geo = geo;
    const zeroBase = (cs, label) => {
      const bgAlpha = cs.backgroundColor.startsWith('rgba') ? parseFloat(cs.backgroundColor.split(',')[3]) : (cs.backgroundColor === 'transparent' ? 0 : 1);
      check(`${label} background transparent`, bgAlpha === 0, cs.backgroundColor);
      check(`${label} background-image none`, cs.backgroundImage === 'none', cs.backgroundImage);
      check(`${label} borders 0`, ['borderTopWidth', 'borderRightWidth', 'borderBottomWidth', 'borderLeftWidth'].every((k) => parseFloat(cs[k]) === 0), JSON.stringify([cs.borderTopWidth, cs.borderRightWidth, cs.borderBottomWidth, cs.borderLeftWidth]));
      check(`${label} box-shadow none`, cs.boxShadow === 'none', cs.boxShadow);
    };

    /* ── A 顶栏容器零视觉底 ──────────────────────────────────────────── */
    check('A0 toolbar present', !!geo.toolbar.rect && !!geo.strip.rect);
    zeroBase(geo.toolbar.cs, 'A1 mf-cv-toolbar');
    check('A1a toolbar has no gradient pseudo elements',
      (!geo.toolbar.before || geo.toolbar.before.content === 'none') && (!geo.toolbar.after || geo.toolbar.after.content === 'none'),
      JSON.stringify([geo.toolbar.before && geo.toolbar.before.content, geo.toolbar.after && geo.toolbar.after.content]));
    zeroBase(geo.strip.cs, 'A2 mf-strip');
    check('A2a strip is an overlay (absolute) not flow space', geo.strip.cs.position === 'absolute', geo.strip.cs.position);
    check('A2b strip band lets scroll gestures through (pointer-events none)', geo.strip.cs.pointerEvents === 'none', geo.strip.cs.pointerEvents);
    const scrollerTopGap = Math.abs(geo.scroll.rect.top - geo.page.rect.top);
    readings.scrollerTopGap = scrollerTopGap;
    check('A3 reading surface reaches page top (boundary = scroller top edge, gap ≤1px)', scrollerTopGap <= 1, `gap=${scrollerTopGap}px`);

    /* ── B 初态不重叠 ────────────────────────────────────────────────── */
    const clearExpect = parseFloat(geo.chromeClear) || 72;
    const padTop = parseFloat(geo.scrollerPadTop);
    readings.padTop = padTop; readings.chromeClear = clearExpect;
    check('B1 initial clearance = topbar chrome geometry (--mchrome-clear, not desktop capsule 52)', Math.abs(padTop - clearExpect) <= 0.5, `padding-top=${padTop} expect=${clearExpect}`);
    const ctrlBottom = Math.max(geo.close.rect.bottom, geo.stack.rect.bottom, geo.chipOn.rect ? geo.chipOn.rect.bottom : 0);
    check('B2 first content below controls at rest (top ≥ controls bottom + 8)', geo.firstContent && geo.firstContent.top >= ctrlBottom + 8 - 0.5,
      `content.top=${geo.firstContent && geo.firstContent.top} ctrlBottom=${ctrlBottom}`);

    /* ── E 同一类族（主对话窗 ∪ Canvas 同一 .ds-chrome-band）────────── */
    check('E1 canvas toolbar carries .ds-chrome-band', /\bds-chrome-band\b/.test(geo.toolbar.cls), geo.toolbar.cls);
    check('E2 main-chat fab layer carries .ds-chrome-band', /\bds-chrome-band\b/.test(geo.fab.cls), geo.fab.cls);
    const zbEq = ['backgroundColor', 'backgroundImage', 'boxShadow', 'borderTopWidth', 'pointerEvents']
      .every((k) => geo.toolbar.cs[k] === geo.fab.cs[k]);
    check('E3 both bands resolve zero-base identically', zbEq, JSON.stringify({ toolbar: [geo.toolbar.cs.backgroundColor, geo.toolbar.cs.boxShadow], fab: [geo.fab.cs.backgroundColor, geo.fab.cs.boxShadow] }));

    /* ── C/D 滚到中段：穿透 + 毛玻璃（像素级）───────────────────────── */
    // 采样区（逻辑坐标）：
    //  · freeL / freeR = 两钮与中签之间的自由柱（旧形态带内永远空底）；
    //  · sliverL = 左钮底缘下 2..8px 窄带（正文贴着控件下沿穿过）。
    //  中签缺位兜底 = 环几何标称值（中签 max-width 112 居中）。
    const chipL = geo.chipOn.rect ? geo.chipOn.rect.left : 195 - 56;
    const chipR = geo.chipOn.rect ? geo.chipOn.rect.right : 195 + 56;
    const freeL = {
      x: Math.max(geo.close.rect.right + 6, 0),
      w: (chipL - 6) - Math.max(geo.close.rect.right + 6, 0),
      y: Math.max(geo.close.rect.top, 0) + 2,
      h: Math.max(geo.close.rect.bottom, 0) - Math.max(geo.close.rect.top, 0) - 4,
    };
    const freeR = {
      x: (chipR + 6),
      w: Math.max(geo.stack.rect.left - 6, 0) - (chipR + 6),
      y: freeL.y, h: freeL.h,
    };
    const sliverL = {
      x: geo.close.rect.left + 12, w: geo.close.rect.w - 24,
      y: geo.close.rect.bottom + 2, h: 6,
    };
    for (const [k, v] of Object.entries({ freeL, freeR, sliverL })) {
      check(`zone ${k} sane (positive size)`, v.w > 8 && v.h > 4, JSON.stringify(v));
    }

    // 初态对照：带内自由柱应是干净底（无正文墨）——滚动前读一次。
    const inkFreeRest = await inkPixels(page, { x: freeL.x, y: freeL.y, width: Math.max(freeL.w, 4), height: Math.max(freeL.h, 4) });
    readings.inkFreeRest = inkFreeRest.ink;

    // 把第 6 段的顶缘滚到视口 y≈24：其字形铺满 24..110+，同时覆盖自由柱
    // （y 18..62）与钮下窄带（y 66..72）。公式 = 段文档偏移 + 滚轮视口顶 − 24
    // （漏加滚轮顶会把落点压到 96 —— 首轮实测 96.4 的根因）。
    // instant 滚动（绕开 scroll-behavior 平滑动画竞速），落点读回自证。
    await page.evaluate(() => {
      const sec = document.querySelector('#mobile-pages .mobile-page.live[data-page="canvas"]');
      const sc = sec.querySelector('.canvas-md-scroll');
      const viewer = sec.querySelector('.canvas-md-viewer');
      const target = viewer.querySelectorAll('p')[5];
      const scTop = sc.getBoundingClientRect().top;
      const yDoc = target.getBoundingClientRect().top - scTop + sc.scrollTop;
      sc.scrollTo({ top: scTop + yDoc - 24, behavior: 'instant' });
    });
    await page.waitForTimeout(260);

    const probeGeo = await page.evaluate(() => {
      const sec = document.querySelector('#mobile-pages .mobile-page.live[data-page="canvas"]');
      const sc = sec.querySelector('.canvas-md-scroll');
      const viewer = sec.querySelector('.canvas-md-viewer');
      const target = viewer.querySelectorAll('p')[5];
      return { scrollTop: sc.scrollTop, paraTop: target.getBoundingClientRect().top };
    });
    readings.probe = probeGeo;
    check('C0 probe paragraph parked under the band (top 18..34)', probeGeo.paraTop >= 18 && probeGeo.paraTop <= 34, `paraTop=${probeGeo.paraTop}`);

    const inkFree = await inkPixels(page, { x: freeL.x, y: freeL.y, width: Math.max(freeL.w, 4), height: Math.max(freeL.h, 4) });
    const inkFreeR = await inkPixels(page, { x: freeR.x, y: freeR.y, width: Math.max(freeR.w, 4), height: Math.max(freeR.h, 4) });
    const inkSliver = await inkPixels(page, { x: sliverL.x, y: sliverL.y, width: Math.max(sliverL.w, 4), height: Math.max(sliverL.h, 4) });
    readings.inkFree = inkFree; readings.inkFreeR = inkFreeR; readings.inkSliver = inkSliver;
    check('C1 body-text pixels present in left free column (pass-through, pixel-level)', inkFree.ink >= 40, `ink=${inkFree.ink}/${inkFree.total}`);
    check('C2 body-text pixels present in right free column', inkFreeR.ink >= 40, `ink=${inkFreeR.ink}/${inkFreeR.total}`);
    check('D2 text pixels within 2..8px below the close button edge', inkSliver.ink >= 20, `ink=${inkSliver.ink}/${inkSliver.total}`);

    /* ── D 控件自持毛玻璃（计算样式面；已知坑①②③）────────────────── */
    const bf = (s) => s.backdropFilter || s.webkitBackdropFilter || '';
    check('D1a close button keeps its own blur', /blur\(\s*([\d.]+)px/.test(bf(geo.close.cs)) && parseFloat(bf(geo.close.cs).match(/blur\(\s*([\d.]+)px/)[1]) >= 8, bf(geo.close.cs));
    check('D1b stack button keeps its own blur', /blur\(\s*([\d.]+)px/.test(bf(geo.stack.cs)) && parseFloat(bf(geo.stack.cs).match(/blur\(\s*([\d.]+)px/)[1]) >= 8, bf(geo.stack.cs));
    const chipAfter = geo.chipOn.afterCs || {};
    check('D1c active chip glass layer has backdrop blur', /blur\(\s*([\d.]+)px/.test(bf(chipAfter)), bf(chipAfter));
    const chipMask = chipAfter.maskImage === undefined ? '' : chipAfter.maskImage;
    const chipMaskWk = chipAfter.webkitMaskImage === undefined ? '' : chipAfter.webkitMaskImage;
    check('D1d chip glass layer carries NO mask (pitfall①: mask×backdrop breaks compositor)',
      (chipAfter.content !== 'none') && chipMask === 'none' && chipMaskWk === 'none',
      JSON.stringify([chipMask, chipMaskWk]));
    const hostBw = geo.chipOn.hostCs;
    check('D1e chip host has zero own border (pitfall③: UA button outline must stay migrated)',
      parseFloat(hostBw.borderTopWidth) === 0 && hostBw.borderTopStyle === 'none', JSON.stringify([hostBw.borderTopWidth, hostBw.borderTopStyle]));

    // 穿透态截图（人眼复核面：模糊区 vs 穿透区可分辨）。
    await page.screenshot({ clip: { x: 0, y: 0, width: 390, height: 130 }, path: join(OUT, 'pass-through-band.png') });
    await page.screenshot({ fullPage: false, path: join(OUT, 'pass-through-full.png') });

    writeFileSync(join(OUT, 'readings.json'), JSON.stringify(readings, null, 2));
    if (failures.length) throw new Error(`topbar semantic assertions failed (${failures.length}): ${failures.join(' | ')}`);
  });
});


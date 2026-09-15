// uidefect-thinking-stream-image.spec.mjs — 钉①（件①：思考流带图 ⇒ 图渲染不出来
// + 连续抽动）双向红绿读数。
//
// 症状权威 = 作者原话逐字（2026-09-15 现场报）：「1.是如果思考过程中带图片，不仅
// 渲染不出来，还会导致连续抽动。」
//
// 真凶（取证见 .nebflow/evidence/20260915_uidefect/forensics.md）：
//   chat.js:2751  appendThinkingDelta 的 rAF 渲染体每帧
//                 `contentEl.innerHTML = renderMarkdownWithMath(累积文本)` ⇒
//                 `.thinking-content` 整棵子树（含 <img>）被逐帧重建 ⇒ 每个新 <img>
//                 的「图像加载状态」被逐帧重置：可加载图靠内存缓存掩盖，加载失败的图
//                 则每帧重发外呼、破图盒在 71↔90px 之间每秒来回（帧率量级）——
//                 即「渲染不出来 + 连续抽动」。
//
// 本 spec 自包含：route 拦截直接读真源码 web 树（`UIDEFECT_WEB_ROOT` 可指向
// 改前树做「转红」复现），/ws 用 routeWebSocket 走真 handler 链
// （sessionBusy{busy:true} → thinkingDelta ×N）。**不起网关、不占端口、不碰 8080、
// 不依赖 sbt**。亮/暗双主题各跑一遍（colorScheme）。
//
// Run（改后绿，默认）:
//   node tests/uidefect-thinking-stream-image.spec.mjs
// Run（改前红，同一 spec、同一读数口径，只换代码树与期望）:
//   mkdir -p /tmp/nb-uidefect-base && git archive <pre-fix-ref> src/main/resources/web | tar -x -C /tmp/nb-uidefect-base
//   UIDEFECT_WEB_ROOT=/tmp/nb-uidefect-base/src/main/resources/web PIN_EXPECT=red \
//     node tests/uidefect-thinking-stream-image.spec.mjs
//
// 读数口径（页面内采集；文本只增 ⇒ 正确渲染下内容高必须单调不减）：
//   distinctImgElements       流式期间在 .thinking-content 见过的**互不相同**的
//                             <img> 对象数（每次 innerHTML 重建 +1）——「反复
//                             unmount/remount」的机械读数
//   sameElementFrames         首图元素仍**是同一个 DOM 对象且 connected** 的采样帧数
//   imgErrorEvents            <img> error 事件数（外呼失败次数）
//   imageRequests             图片 URL 实际到达服务器的 HTTP 请求数（Node 侧计数）
//   heightResets(stream|tail) 内容高「下降 >2px」的相邻帧对数（抖动读数）
//   paint                     真渲染自检：naturalWidth + rect 面积 + canvas 采样
//                             像素方差 + elementFromPoint 命中该图

import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { deflateSync } from 'node:zlib';

const REPO = join(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = process.env.UIDEFECT_WEB_ROOT
  ? normalize(process.env.UIDEFECT_WEB_ROOT)
  : join(REPO, 'src', 'main', 'resources', 'web');
const EXPECT = process.env.PIN_EXPECT ?? 'green';
const SHOTS = process.env.UIDEFECT_SHOTS ?? join(tmpdir(), 'uidefect-shots', `pin1-${EXPECT}`);
mkdirSync(SHOTS, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const SID = 'pin1-root-session';
const IMG_OK = '/pin1-img-ok.png';
const IMG_BROKEN = '/pin1-img-404.png';
const TAIL_FRAMES = 20;
const TEXT_HEAD = 'Let me verify the layout before answering. ';
const TEXT_TAIL = 'Checking the panel widths, the chip alignment, and the footer badge position. ' +
  'Everything looks consistent with the spec so far. ';

let failures = 0;
const readings = {};
function ok(id, cond, detail) {
  console.log(`  ${cond ? 'PASS' : 'FAIL'}  [${id}] ${detail}`);
  if (!cond) failures++;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── 合成大 PNG（截图量级解码成本；文件内联生成 ⇒ 不依赖仓外资产）────────
const CRC_TABLE = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c;
  }
  return t;
})();
function crc32(buf) {
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}
function pngChunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type, 'latin1'), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
}
function makePng(w, h) {
  const stride = w * 4 + 1;
  const raw = Buffer.alloc(stride * h);
  for (let y = 0; y < h; y++) {
    const row = y * stride;
    for (let x = 0; x < w; x++) {
      const o = row + 1 + x * 4;
      raw[o] = x & 0xff; raw[o + 1] = y & 0xff; raw[o + 2] = 0x88; raw[o + 3] = 255;
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    pngChunk('IHDR', ihdr), pngChunk('IDAT', deflateSync(raw, { level: 6 })),
    pngChunk('IEND', Buffer.alloc(0)),
  ]);
}
const PNG_BIG = makePng(1200, 900);

const CASES = [
  { id: 'C1-loadable-224px', img: IMG_OK, png: () => readFileSync(join(WEB, 'css', 'logo-bright-4.png')), delay: 60, loadable: true },
  { id: 'C2-loadable-1200x900', img: '/pin1-img-big.png', png: () => PNG_BIG, delay: 100, loadable: true },
  { id: 'C3-load-fails-404', img: IMG_BROKEN, png: null, delay: 20, loadable: false },
  { id: 'C4-no-image-control', img: null, png: null, delay: 0, loadable: false },
];

async function openPage(browser, { colorScheme, counts, requestLog, t0 }) {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, colorScheme });
  const page = await ctx.newPage();
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await page.addInitScript(() => localStorage.setItem('nebflow_token', 'spec-token'));
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    const c = CASES.find((x) => x.img && (p === x.img || (x.img === IMG_OK && p === IMG_OK)));
    if (c && c.png) {
      counts[c.img] = (counts[c.img] || 0) + 1;
      requestLog.push({ p, dt: Math.round(performance.now ? Date.now() - t0 : 0) });
      const body = c.png();
      return sleep(c.delay).then(() => route.fulfill({
        status: 200, contentType: 'image/png', body,
        headers: { 'Cache-Control': 'public, max-age=600' },
      }));
    }
    if (c && !c.png) {
      counts[c.img] = (counts[c.img] || 0) + 1;
      requestLog.push({ p, dt: Math.round(performance.now ? Date.now() - t0 : 0) });
      return sleep(c.delay).then(() => route.fulfill({
        status: 404, contentType: 'text/plain', body: 'not found',
        headers: { 'Cache-Control': 'no-store' },
      }));
    }
    if (p.startsWith('/api/')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });

  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'serverConfig', streamTimeoutMs: 600000, version: 'spec', thinking: null, workSchedule: null }));
    const sendConfigLegacy = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'pin1', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig(); sendConfigLegacy(); sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });

  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.waitForTimeout(250);
  return { ctx, page, pageErrors };
}

async function runCase(browser, { caseDef, colorScheme }) {
  // per-case 计数（跨 case/主题累计会把上一轮的请求算进本轮读数）
  const counts = {};
  const requestLog = [];
  const t0 = Date.now();
  const { ctx, page, pageErrors } = await openPage(browser, { colorScheme, counts, requestLog, t0 });
  const imageMd = caseDef.img ? `\n\n![布局示意](${caseDef.img})\n\n` : '';
  const deltas = [];
  for (let i = 0; i < TEXT_HEAD.length; i += 8) deltas.push(TEXT_HEAD.slice(i, i + 8));
  if (imageMd) deltas.push(imageMd);
  for (let i = 0; i < TEXT_TAIL.length; i += 8) deltas.push(TEXT_TAIL.slice(i, i + 8));

  const raw = await page.evaluate(async ({ sid, deltas, tailFrames, imgSrc }) => {
    const s = (await import('/js/state.js')).default;
    const inject = (o) => s.ws.onmessage({ data: JSON.stringify(o) });
    const content = () => document.querySelector('.thinking-content');
    const p = { frames: [], errors: 0, first: null, running: true, seen: new WeakSet(), distinct: 0, insertions: 0 };
    document.addEventListener('error', (e) => { if (e.target && e.target.tagName === 'IMG') p.errors++; }, true);
    new MutationObserver((muts) => {
      for (const m of muts) for (const n of m.addedNodes) {
        if (n.nodeType !== 1) continue;
        if (n.tagName === 'IMG') p.insertions++;
        if (n.querySelectorAll) p.insertions += n.querySelectorAll('img').length;
      }
    }).observe(document.getElementById('chat'), { childList: true, subtree: true });

    const sample = () => {
      const c = content();
      const im = c ? c.querySelector('img') : null;
      if (!p.first && im) p.first = im;
      if (c) for (const el of c.querySelectorAll('img')) {
        if (!p.seen.has(el)) { p.seen.add(el); p.distinct++; }
      }
      const r = c ? c.getBoundingClientRect() : null;
      p.frames.push({
        h: r ? Math.round(r.height) : -1,
        imgs: c ? c.querySelectorAll('img').length : 0,
        imgH: im ? Math.round(im.getBoundingClientRect().height) : 0,
        nw: im ? im.naturalWidth : null,
        sameElement: p.first ? (p.first === im && p.first.isConnected) : null,
        distinct: p.distinct, errors: p.errors,
      });
      if (p.running) requestAnimationFrame(sample);
    };
    requestAnimationFrame(sample);

    inject({ type: 'sessionBusy', sessionId: sid, busy: true });
    await new Promise((r) => requestAnimationFrame(r));
    await new Promise((resolve) => {
      let i = 0;
      const step = () => {
        if (i < deltas.length) { inject({ type: 'thinkingDelta', sessionId: sid, delta: deltas[i++] }); requestAnimationFrame(step); }
        else resolve();
      };
      requestAnimationFrame(step);
    });
    const streamEnd = p.frames.length;
    let k = 0;
    await new Promise((resolve) => {
      const step = () => { if (k++ < tailFrames) requestAnimationFrame(step); else resolve(); };
      requestAnimationFrame(step);
    });
    p.running = false;
    await new Promise((r) => requestAnimationFrame(r));

    // ── 真渲染自检（收尾态）──
    const c = content();
    const im = c ? c.querySelector('img') : null;
    let paint = null;
    if (im) {
      const r = im.getBoundingClientRect();
      const cx = Math.round(r.left + r.width / 2);
      const cy = Math.round(r.top + r.height / 2);
      const hit = document.elementFromPoint(cx, cy);
      let variance = null;
      try {
        const cv = document.createElement('canvas');
        cv.width = Math.max(1, Math.min(220, Math.round(r.width)));
        cv.height = Math.max(1, Math.min(220, Math.round(r.height)));
        const g = cv.getContext('2d');
        g.drawImage(im, 0, 0, cv.width, cv.height);
        const d = g.getImageData(0, 0, cv.width, cv.height).data;
        const vals = [];
        for (let i = 0; i < d.length; i += 4 * 97) vals.push(d[i] + d[i + 1] + d[i + 2]);
        const mean = vals.reduce((a, b) => a + b, 0) / (vals.length || 1);
        variance = Math.round(vals.reduce((a, b) => a + (b - mean) ** 2, 0) / (vals.length || 1));
      } catch (e) { variance = -1; }
      paint = {
        complete: !!im.complete, naturalWidth: im.naturalWidth,
        w: Math.round(r.width), h: Math.round(r.height),
        hitTag: hit ? hit.tagName : null,
        hitIsImg: !!hit && (hit === im || hit.tagName === 'IMG'),
        pixelVariance: variance,
        alt: im.getAttribute('alt') || '',
        failedClass: im.classList.contains('nf-img-failed'),
      };
    }
    return { frames: p.frames, streamEnd, distinct: p.distinct, insertions: p.insertions, errors: p.errors, paint, hasContent: !!c, imgSrc };
  }, { sid: SID, deltas, tailFrames: TAIL_FRAMES, imgSrc: caseDef.img || '' });

  const fr = raw.frames;
  const resets = (arr) => { let n = 0; for (let i = 1; i < arr.length; i++) if (arr[i].h < arr[i - 1].h - 2) n++; return n; };
  const stream = fr.slice(0, raw.streamEnd);
  const tail = fr.slice(raw.streamEnd);
  const reading = {
    case: caseDef.id, theme: colorScheme, img: caseDef.img,
    sampledFrames: fr.length, streamFrames: stream.length,
    distinctImgElements: raw.distinct,
    imgInsertionsRaw: raw.insertions,
    imgErrorEvents: raw.errors,
    imageRequests: caseDef.img ? (counts[caseDef.img] || 0) : 0,
    requestLog,
    framesWithImg: fr.filter((f) => f.imgs > 0).length,
    sameElementFrames: fr.filter((f) => f.sameElement === true).length,
    heightResetsStream: resets(stream),
    heightResetsTail: resets(tail),
    paintedFrames: fr.filter((f) => f.imgH > 0).length,
    paint: raw.paint,
    heightTrace: fr.map((f) => f.h).join(','),
    pageErrors,
  };
  readings[`${caseDef.id}@${colorScheme}`] = reading;

  await page.screenshot({ path: join(SHOTS, `${caseDef.id}-${colorScheme}.png`) });
  await ctx.close();
  return reading;
}

const browser = await chromium.launch();
try {
  for (const theme of ['light', 'dark']) {
    for (const caseDef of CASES) {
      console.log(`\n── ${caseDef.id} @ ${theme} | PIN_EXPECT=${EXPECT} ──`);
      const r = await runCase(browser, { caseDef, colorScheme: theme });
      console.log(`  distinctImgElements=${r.distinctImgElements} sameElementFrames=${r.sameElementFrames}/${r.sampledFrames} ` +
        `imgErrorEvents=${r.imgErrorEvents} imageRequests=${r.imageRequests} ` +
        `heightResets(stream)=${r.heightResetsStream} heightResets(tail)=${r.heightResetsTail} paintedFrames=${r.paintedFrames}`);
      console.log(`  paint=${JSON.stringify(r.paint)}`);
      if (r.pageErrors.length) console.log(`  pageErrors=${JSON.stringify(r.pageErrors)}`);
      ok(`${caseDef.id}-${theme}-pageerrors`, r.pageErrors.length === 0, `pageerrors=${r.pageErrors.length}`);

      if (caseDef.img === null) {
        // C4 对照：无图情形两向都不该有任何图片节点 / 抖动
        ok(`${caseDef.id}-${theme}-no-img-node`, r.distinctImgElements === 0, `distinctImgElements=${r.distinctImgElements}`);
        ok(`${caseDef.id}-${theme}-no-jitter`, r.heightResetsStream === 0, `heightResets(stream)=${r.heightResetsStream}`);
        continue;
      }

      if (EXPECT === 'red') {
        ok(`R1-${caseDef.id}-${theme}-remount`, r.distinctImgElements > 5,
          `distinctImgElements=${r.distinctImgElements} (>5 = 逐帧重建)`);
        ok(`R2-${caseDef.id}-${theme}-identity-lost`, r.sameElementFrames <= 2,
          `sameElementFrames=${r.sameElementFrames} (<=2 = 首图元素不存活)`);
        if (!caseDef.loadable) {
          ok(`R3-${caseDef.id}-${theme}-repeated-attempts`, r.imgErrorEvents > 1,
            `imgErrorEvents=${r.imgErrorEvents} imageRequests=${r.imageRequests}`);
          ok(`R4-${caseDef.id}-${theme}-jitter`, r.heightResetsStream > 0,
            `heightResets(stream)=${r.heightResetsStream} (>0 = 破图盒塌缩-撑开循环)`);
          ok(`R5-${caseDef.id}-${theme}-not-rendered`, r.paint && r.paint.naturalWidth === 0,
            `naturalWidth=${r.paint && r.paint.naturalWidth}`);
        }
      } else {
        ok(`G1-${caseDef.id}-${theme}-single-element`, r.distinctImgElements === 1,
          `distinctImgElements=${r.distinctImgElements} (==1 = 图片节点跨帧存活)`);
        ok(`G2-${caseDef.id}-${theme}-identity-survives`, r.sameElementFrames === r.framesWithImg && r.framesWithImg > 0,
          `sameElementFrames=${r.sameElementFrames}/${r.framesWithImg} (图在场帧数)`);
        ok(`G3-${caseDef.id}-${theme}-no-jitter`, r.heightResetsStream === 0 && r.heightResetsTail === 0,
          `heightResets(stream)=${r.heightResetsStream} heightResets(tail)=${r.heightResetsTail}`);
        ok(`G4-${caseDef.id}-${theme}-single-load`, r.imgErrorEvents <= 1 && r.imageRequests <= 1,
          `imgErrorEvents=${r.imgErrorEvents} imageRequests=${r.imageRequests} log=${JSON.stringify(r.requestLog)}`);
        if (caseDef.loadable) {
          ok(`G5-${caseDef.id}-${theme}-rendered`, r.paint && r.paint.complete === true && r.paint.naturalWidth > 0 &&
            r.paint.w > 0 && r.paint.h > 0 && r.paint.hitIsImg === true && r.paint.pixelVariance > 0,
            `paint=${JSON.stringify(r.paint)}`);
        } else {
          ok(`G5-${caseDef.id}-${theme}-graceful-placeholder`, r.paint && r.paint.h >= 40 && r.paint.failedClass === true,
            `placeholderBox=${r.paint && r.paint.h}px failedClass=${r.paint && r.paint.failedClass}`);
        }
      }
    }
  }
} finally {
  await browser.close();
}

writeFileSync(join(SHOTS, `readings-pin1-${EXPECT}.json`), JSON.stringify(readings, null, 2));
console.log(`\nPIN1_READINGS ${JSON.stringify(readings)}`);
console.log(`\nRESULT: ${failures ? `FAIL (${failures} failures)` : 'PASS'}  [PIN_EXPECT=${EXPECT} web=${WEB}]`);
console.log(`screenshots/readings → ${SHOTS}`);
process.exit(failures ? 1 : 0);

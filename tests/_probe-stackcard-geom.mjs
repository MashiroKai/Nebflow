// _probe-stackcard-geom.mjs — AskUser 媒体叠卡宿主几何 + 裁切断言探针（独立 node 脚本）。
//
// 用途：在**真应用外壳**上把 AskUser 媒体题型（多选项 / 长标题 / 长描述 / 3 件媒体组）
//   推入真卡面，度量「叠卡宿主的横向预算 → 卡身 → 内容盒 → 叠卡行 → 扇形露边」
//   整条链，并判定**是否有任意已绘制像素越过 `.option-scroll` 的裁剪盒**（= 作者报的
//   「叠卡显示不完整」）。
//
// 直跑：  node tests/_probe-stackcard-geom.mjs
// 视口：  PROBE_VIEWPORT=mobile（缺省，375×812 真手机全屏页）| desktop（1280×900）
// 出图：  PROBE_SHOT_DIR=<绝对目录>
// 换树：  CHATFACE_WEB_ROOT=<web 树>（红腿：指向未修源码的树）
//
// 输出：JSON（stdout）×1 + 可选 PNG。
import { chromium } from '@playwright/test';
import zlib from 'node:zlib';
import { mkdirSync } from 'node:fs';
import { bootChat, SID } from './_chat-face-harness.mjs';

const OUT = process.env.PROBE_SHOT_DIR;
if (OUT) mkdirSync(OUT, { recursive: true });
const WHICH = process.env.PROBE_VIEWPORT || 'mobile';
const TAG = process.env.PROBE_TAG || WHICH;

const CRC_TABLE = (() => { const t = new Int32Array(256); for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c; } return t; })();
const crc32 = (buf) => { let c = -1; for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8); return (c ^ -1) >>> 0; };
const pngChunk = (type, data) => { const len = Buffer.alloc(4); len.writeUInt32BE(data.length, 0); const body = Buffer.concat([Buffer.from(type, 'ascii'), data]); const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(body), 0); return Buffer.concat([len, body, crc]); };
function png(w, h, [r, g, b]) {
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 2;
  const row = Buffer.alloc(1 + w * 3); for (let x = 0; x < w; x++) { row[1 + x * 3] = r; row[2 + x * 3] = g; row[3 + x * 3] = b; }
  const raw = Buffer.concat(Array.from({ length: h }, () => row));
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), pngChunk('IHDR', ihdr), pngChunk('IDAT', zlib.deflateSync(raw, { level: 9 })), pngChunk('IEND', Buffer.alloc(0))]);
}
const dataUri = (w, h, rgb) => 'data:image/png;base64,' + png(w, h, rgb).toString('base64');
const imgs = [dataUri(480, 320, [60, 170, 120]), dataUri(320, 480, [40, 110, 190]), dataUri(400, 300, [190, 120, 50])];

// 作者报障的形态：多选项叠卡 + 长标题 + 长描述（选项文字换行 + 卡身撑到上限）。
const ITEMS = [{
  question: '长卡下滑验收：请选一个方案（内容超高必须可滚动）',
  options: [0, 1, 2, 3, 4, 5].map((i) => ({
    label: `方案 ${i + 1}：这一档比较长的标题文字用于检验换行与裁切`,
    description: `第 ${i + 1} 套设计说明文字，这是一段较长的描述，用来把选项卡撑到自然宽度上限以复现裁切`,
    preview: { type: 'media', items: [
      { kind: 'image', src: imgs[i % imgs.length], name: `a${i}.png` },
      { kind: 'image', src: imgs[(i + 1) % imgs.length], name: `b${i}.png` },
      { kind: 'image', src: imgs[(i + 2) % imgs.length], name: `c${i}.png` }] },
  })),
}];
const frame = (items, requestId) => JSON.stringify({ type: 'askUser', sessionId: SID, agentName: 'E2E', requestId, items });

const MOBILE = WHICH === 'mobile';
const browser = await chromium.launch();
const ctx = await browser.newContext(MOBILE
  ? { viewport: { width: 375, height: 812 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2, colorScheme: 'light' }
  : { viewport: { width: 1280, height: 900 }, colorScheme: 'light' });
const page = await ctx.newPage();
let route = null;
await bootChat(page, { onWsRoute: (ws) => { route = ws; } });
route.send(frame(ITEMS, `rid-probe-${TAG}`));
await page.waitForSelector('#chat .option-box', { timeout: 20000 });
if (MOBILE) await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 20000 });
await page.waitForTimeout(1200);

const report = await page.evaluate(() => {
  const R = (el) => { if (!el) return null; const b = el.getBoundingClientRect(); return { l: +b.left.toFixed(1), r: +b.right.toFixed(1), t: +b.top.toFixed(1), b: +b.bottom.toFixed(1), w: +b.width.toFixed(1), h: +b.height.toFixed(1) }; };
  const box = document.querySelector('#chat .option-box');
  const bubble = box.parentElement;
  const scroll = box.querySelector('.option-scroll');
  const scrollCS = getComputedStyle(scroll);
  // overflow:hidden 的裁剪区 = **padding box**（= border box 内缩 border 宽）。滚动容器
  // 自身不属于「被裁内容」，子件扫描排除它。
  const clipRight = scroll.getBoundingClientRect().right - (parseFloat(scrollCS.borderRightWidth) || 0);
  const clipLeft = scroll.getBoundingClientRect().left + (parseFloat(scrollCS.borderLeftWidth) || 0);
  const host = box.querySelector('.option-preview-media');
  const view = host && host.querySelector('.pop-stack-view');
  const pill = host && host.querySelector('.pill');
  const layers = host ? [...host.querySelectorAll('.pop-stack-view .media-layer')]
    .map((l) => { const b = l.getBoundingClientRect(); return { cls: l.className.replace('media-layer ds-media-card', '').trim(), l: +b.left.toFixed(1), r: +b.right.toFixed(1) }; }) : [];
  // 滚动区内一切后代中「最右已绘制缘」（旋转层的 AABB = 真实绘制缘）。
  let worstR = -Infinity; let worstEl = '';
  let worstL = Infinity; let worstLEl = '';
  const painted = (el) => {
    const b = el.getBoundingClientRect();
    if (b.width <= 0 || b.height <= 0) return;
    if (b.right > worstR) { worstR = b.right; worstEl = el.className.toString().split(' ').slice(0, 2).join('.') || el.tagName; }
    if (b.left < worstL) { worstL = b.left; worstLEl = el.className.toString().split(' ').slice(0, 2).join('.') || el.tagName; }
  };
  // 滚动容器自身不属于「被裁内容」——只扫它的后代。
  scroll.querySelectorAll('*').forEach(painted);
  const selfB = scroll.getBoundingClientRect();

  const boxB = box.getBoundingClientRect();
  const tokenAsk = getComputedStyle(bubble).getPropertyValue('--ds-ask-card-max-w').trim();
  const tokenCol = getComputedStyle(bubble).getPropertyValue('--ds-bubble-max-w').trim();
  const rowW = +bubble.parentElement.getBoundingClientRect().width.toFixed(1);
  // 叠卡行固有宽（展开钮 + 缝 + 卡脸）= 叠卡能装下的最小内容宽。
  const faceW = view ? +view.getBoundingClientRect().width.toFixed(1) : 0;
  const pillW = pill ? +pill.getBoundingClientRect().width.toFixed(1) : 0;
  const gap = host ? parseFloat(getComputedStyle(host.querySelector('.pop-stack-area')).gap || '0') : 0;

  return {
    viewport: { w: window.innerWidth, h: window.innerHeight, dpr: window.devicePixelRatio },
    mobileLayer: document.body.classList.contains('mobile-layer'),
    docScrollW: document.documentElement.scrollWidth,
    row: { w: rowW },
    box: R(box),
    bubble: R(bubble),
    bubbleMaxW: getComputedStyle(bubble).maxWidth,
    tokenAskCardMaxW: tokenAsk,
    tokenBubbleMaxW: tokenCol,
    hostBox: R(host),
    viewBox: R(view),
    pillBox: R(pill),
    stackIntrinsicW: +(pillW + gap + faceW).toFixed(1),
    layers,
    clip: { left: +clipLeft.toFixed(1), right: +clipRight.toFixed(1), overflowX: scrollCS.overflowX },
    scrollSelf: { l: +selfB.left.toFixed(1), r: +selfB.right.toFixed(1) },
    worstPaintedRight: +worstR.toFixed(1), worstPaintedEl: worstEl,
    worstPaintedLeft: +worstL.toFixed(1), worstPaintedLeftEl: worstLEl,
    overflowPx: +(worstR - clipRight).toFixed(1),
    overflowLeftPx: +(clipLeft - worstL).toFixed(1),
    boxGapLeft: +boxB.left.toFixed(1),
    boxGapRight: +(window.innerWidth - boxB.right).toFixed(1),
    contentBoxW: +(boxB.width - parseFloat(getComputedStyle(box).paddingLeft) - parseFloat(getComputedStyle(box).paddingRight)).toFixed(1),
  };
});

console.log(JSON.stringify(report, null, 2));
if (OUT) {
  await page.screenshot({ path: `${OUT}/${WHICH}-top-${TAG}.png` });
  await page.evaluate(() => { const sc = document.querySelector('#chat .option-box .option-scroll'); sc.scrollTop = sc.scrollHeight; });
  await page.waitForTimeout(400);
  await page.screenshot({ path: `${OUT}/${WHICH}-bottom-${TAG}.png` });
}
await browser.close();

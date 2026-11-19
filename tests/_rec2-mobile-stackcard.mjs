// _rec2-mobile-stackcard.mjs — 手机全屏页（375×812 @ dpr2）叠卡裁切 before/after 逐帧录制。
//
// 为什么不用 Playwright recordVideo：它按 **CSS 像素 1×** 抓帧并把画面贴在给定 size 的
// 画布左上角（实测 750×1624 出片里有效画面仅左上 375×812，其余为底色）⇒ 拿不到真 2×。
// 本脚本改为**逐帧 page.screenshot()**（dpr 2 ⇒ 每帧真 750×1624 设备像素），ffmpeg 组片。
//
// 直跑：node tests/_rec2-mobile-stackcard.mjs
// 换树：CHATFACE_WEB_ROOT=<web 树>； 出帧：REC_FRAMES=<目录>； 标签：REC_TAG=<before|after>
import { chromium } from '@playwright/test';
import zlib from 'node:zlib';
import { mkdirSync } from 'node:fs';
import { bootChat, SID } from './_chat-face-harness.mjs';

const DIR = process.env.REC_FRAMES || '/tmp/stackcard-frames';
const TAG = process.env.REC_TAG || 'after';
mkdirSync(DIR, { recursive: true });

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

const LONG_MEDIA_ITEMS = [{
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

const browser = await chromium.launch();
const ctx = await browser.newContext({
  viewport: { width: 375, height: 812 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2, colorScheme: 'light',
});
const page = await ctx.newPage();
let route = null;
await bootChat(page, { onWsRoute: (ws) => { route = ws; } });
await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 20000 });
route.send(frame(LONG_MEDIA_ITEMS, `rid-rec2-${TAG}`));
await page.waitForSelector('#chat .option-box', { timeout: 20000 });
await page.waitForTimeout(1500);
await page.evaluate(() => { document.querySelector('#chat .option-box .option-scroll').style.scrollBehavior = 'auto'; });

let n = 0;
const snap = async (reps = 1) => {
  for (let i = 0; i < reps; i++) {
    await page.screenshot({ path: `${DIR}/${TAG}-${String(n++).padStart(3, '0')}.png` });
  }
};
const scrollTo = async (v) => { await page.evaluate((y) => { document.querySelector('#chat .option-box .option-scroll').scrollTop = y; }, v); };

const sc = page.locator('#chat .option-box .option-scroll');
const b0 = await sc.boundingBox();
await page.mouse.move(b0.x + b0.width / 2, b0.y + b0.height / 2);

await snap(8);                                   // ① 入场静置
const H = await page.evaluate(() => { const s = document.querySelector('#chat .option-box .option-scroll'); return s.scrollHeight - s.clientHeight; });
for (let i = 1; i <= 10; i++) { await page.mouse.wheel(0, H / 10); await page.waitForTimeout(40); await snap(1); }  // ② 下滑
await snap(10);                                  // ③ 底部静置
for (let i = 9; i >= 0; i--) { await scrollTo(H * i / 10); await page.waitForTimeout(30); await snap(1); }          // ④ 回顶
await snap(6);
await page.locator('#chat .option-box .option-btn').nth(0).locator('.pop-stack-view').click({ position: { x: 40, y: 60 } }); // ⑤ 点媒体叠卡面
await snap(8);
await page.locator('#chat .option-box .option-confirm').click();                                   // ⑥ 确认作答
await snap(12);

console.log(`frames: ${n} → ${DIR}`);
await ctx.close();
await browser.close();

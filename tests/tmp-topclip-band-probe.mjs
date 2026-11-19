// tmp-topclip-band-probe.mjs — 逐层归因探针：穿顶气泡第一行上的渐变条是谁画的？
// 候选：A) #top-overlays 的 .scroll-under-glass::after 裙边（28px tint+blur，带空也画）
//       B) #chat mask 过渡带（16..18px，Header 玻璃之下）
// 方法：同一穿顶状态四连拍 closeup 条带 —— ① 基线 ② 隐藏 #top-overlays
//       ③ 去掉 #chat mask ④ 两者都去 —— 各采样第一行文字区像素亮度对比。
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = join(HERE, '..', 'evidence', 'tmp-topclip-band-probe');
mkdirSync(OUT, { recursive: true });
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json' };
const SID = 's-header-cross';
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

function wireUp(ws) {
  ws.onMessage((raw) => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
    if (m.type === 'getHistory') { ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 })); return; }
  });
  ws.send(JSON.stringify({ type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: '归因探针' }], activeId: SID, folders: [] }));
}
const server = createServer(async (req, res) => {
  try {
    const p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    const file = normalize(join(WEB, p === '/' ? 'index.html' : p));
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end('{}'); }
});
await new Promise(r => server.listen(0, '127.0.0.1', r));

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1280, height: 860 }, colorScheme: 'dark', deviceScaleFactor: 2 });
await ctx.addInitScript(() => {
  localStorage.setItem('nebflow_token', 't');
  localStorage.setItem('nebflow_locale', 'zh-CN');
});
const page = await ctx.newPage();
await page.routeWebSocket(/\/ws/, ws => wireUp(ws));
await page.goto(`http://127.0.0.1:${server.address().port}/index.html`);
await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
await sleep(1200);
await page.evaluate(() => (async () => {
  const chat = await import('/js/chat.js');
  for (let i = 1; i <= 26; i++) {
    chat.renderUserBubble(`穿顶验证消息 ${i} —— 用于滚动遮罩锚点与渐隐退役的截图证据。`, []);
  }
})());
await page.evaluate(() => { const c = document.getElementById('chat'); c.scrollTop = c.scrollHeight * 0.45; });
await sleep(500);

// band 区 = header 底缘往下 30px、x 取聊天栏中部（避开侧栏）。逐案采样平均 RGB。
const probe = async (tag) => {
  const shot = await page.screenshot({ clip: { x: 470, y: 64, width: 360, height: 36 } });
  await page.screenshot({ path: join(OUT, `probe-${tag}.png`), clip: { x: 440, y: 0, width: 420, height: 140 } });
  const png = await PNG.load(shot);
  let r = 0, g = 0, b = 0, n = png.width * png.height;
  for (let i = 0; i < n; i++) { r += png.data[i*4]; g += png.data[i*4+1]; b += png.data[i*4+2]; }
  console.log(`[${tag}] band avg RGB = (${Math.round(r/n)}, ${Math.round(g/n)}, ${Math.round(b/n)})`);
};
// 简易 PNG 解码（无依赖）：用浏览器自己解码 —— 把截图贴回页面 canvas 读像素。
const avgColor = async (buf) => {
  const b64 = buf.toString('base64');
  return await page.evaluate(async (b64) => {
    const img = new Image();
    img.src = 'data:image/png;base64,' + b64;
    await img.decode();
    const cv = document.createElement('canvas');
    cv.width = img.width; cv.height = img.height;
    const c2 = cv.getContext('2d');
    c2.drawImage(img, 0, 0);
    const d = c2.getImageData(0, 0, cv.width, cv.height).data;
    let r = 0, g = 0, b = 0; const n = d.length / 4;
    for (let i = 0; i < n; i++) { r += d[i*4]; g += d[i*4+1]; b += d[i*4+2]; }
    return [Math.round(r/n), Math.round(g/n), Math.round(b/n)];
  }, b64);
};
const shotBand = () => page.screenshot({ clip: { x: 470, y: 68, width: 360, height: 26 } });
const report = async (tag) => {
  const [r, g, b] = await avgColor(await shotBand());
  console.log(`[${tag}] band(68..94px) avg RGB = rgb(${r},${g},${b})`);
  await page.screenshot({ path: join(OUT, `probe-${tag}.png`), clip: { x: 440, y: 0, width: 420, height: 150 } });
};

const info = await page.evaluate(() => {
  const to = document.getElementById('top-overlays');
  const cs = getComputedStyle(to, '::after');
  const r = to.getBoundingClientRect();
  return { topOverlaysTop: r.top, height: r.height, skirtHeight: cs.height, skirtBg: cs.backgroundImage.slice(0, 80), skirtBlur: cs.backdropFilter };
});
console.log('[geo]', JSON.stringify(info));
await report('A-baseline');
await page.evaluate(() => { document.getElementById('top-overlays').style.display = 'none'; });
await sleep(200);
await report('B-no-topoverlays');
await page.evaluate(() => { document.getElementById('top-overlays').style.display = ''; document.getElementById('chat').style.maskImage = 'none'; document.getElementById('chat').style.webkitMaskImage = 'none'; });
await sleep(200);
await report('C-no-chatmask');
await page.evaluate(() => { document.getElementById('top-overlays').style.display = 'none'; });
await sleep(200);
await report('D-neither');
await browser.close();
await new Promise(r => server.close(r));

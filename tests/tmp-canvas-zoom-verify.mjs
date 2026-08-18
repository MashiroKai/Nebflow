// tmp-canvas-zoom-verify.mjs — Canvas 查看器独立缩放验收（spec ①②③ + #302 回归）
// 静态服务器 :8977 serve web/；nf-file 用路由 mock 回真实文件字节。
import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';

const BASE = 'http://127.0.0.1:8977';
const SID = 'sess-1';
const PDF = '/Users/dev/datasheet/SIPM/AD8561.pdf';
const PNG = '/tmp/nb-canvas-html/src/main/resources/web/favicon-32.png';
const DECK = process.env.HOME + '/.nebflow/projects/html-deck-studio/ai-fpga-deck/ppt/index.html';
let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 });
await ctx.addInitScript(() => {
  localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
  localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
});
const page = await ctx.newPage();
page.on('pageerror', e => console.log('[pageerror]', String(e.message || e).slice(0, 150)));
await page.route('**/api/**', r => r.fulfill({ json: {} })); // catch-all first（后注册优先，具体路径必须在后）
await page.route('**/api/nf-file**', r => {
  const u = new URL(r.request().url());
  const p = u.searchParams.get('path') || '';
  if (p.endsWith('.pdf')) return r.fulfill({ body: readFileSync(PDF), contentType: 'application/pdf' });
  if (p.endsWith('.png')) return r.fulfill({ body: readFileSync(PNG), contentType: 'image/png' });
  return r.fulfill({ status: 404 });
});
let serverWs = null;
await page.routeWebSocket(/\/ws/, ws => {
  serverWs = ws;
  ws.onMessage(raw => { let m; try { m = JSON.parse(raw); } catch { return; } if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 })); });
  ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: SID, folders: [] }));
});
await page.goto(BASE + '/index.html');
await page.waitForSelector('#activity-bar', { timeout: 10000 });
await sleep(600);

// 走真实 fileContent 链路（explorer handler → workspace-open-item → canvas）
const openFile = (path, itemType, title, withContent) => serverWs.send(JSON.stringify({
  type: 'fileContent', path, absPath: path, fileName: title, itemType,
  ...(withContent ? { content: readFileSync(path, 'utf8') } : { content: '' }), size: 1234,
}));

// ══ 图片缩放 ══════════════════════════════════════════════
await openFile('/tmp/test.png', 'image', 'test.png');
await page.waitForSelector('.canvas-image-viewport img', { timeout: 8000 });
await sleep(400);

const imgState = () => page.evaluate(() => {
  const img = document.querySelector('.canvas-image-viewport img');
  const m = (img.style.transform.match(/scale\(([\d.]+)\)/) || [])[1];
  const t = img.style.transform.match(/translate\((-?[\d.]+)px, (-?[\d.]+)px\)/);
  return { scale: parseFloat(m || '1'), tx: parseFloat(t?.[1] || '0'), ty: parseFloat(t?.[2] || '0'),
           pct: document.querySelector('.canvas-zoom-pct')?.textContent };
});

ok('IMG-0 缩放工具条渲染（4 控件）', await page.$$eval('.canvas-zoom-bar > *', els => els.length) === 4);
const s0 = await imgState();

// ① ctrl+wheel 在查看器上 → 内容缩放且事件被拦截
const wheelIn = await page.evaluate(() => {
  const vp = document.querySelector('.canvas-image-viewport');
  const r = vp.getBoundingClientRect();
  const ev = new WheelEvent('wheel', { ctrlKey: true, deltaY: -240, bubbles: true, cancelable: true, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 });
  const notPrevented = vp.dispatchEvent(ev);
  return { prevented: !notPrevented };
});
await sleep(200);
const s1 = await imgState();
ok('IMG-1 ctrl+wheel → 内容放大 + preventDefault', wheelIn.prevented && s1.scale > s0.scale, `${s0.scale}→${s1.scale} pct=${s1.pct}`);

// ①b 普通 wheel（无修饰键）→ 不拦截
const wheelPlain = await page.evaluate(() => {
  const vp = document.querySelector('.canvas-image-viewport');
  const ev = new WheelEvent('wheel', { deltaY: -240, bubbles: true, cancelable: true });
  return { prevented: !vp.dispatchEvent(ev) };
});
const s1b = await imgState();
ok('IMG-2 无修饰 wheel → 不拦截不缩放', !wheelPlain.prevented && s1b.scale === s1.scale);

// ② 按钮缩放 + 复位
await page.click('.canvas-zoom-btn[aria-label="Zoom in"]');
await sleep(150);
const s2 = await imgState();
ok('IMG-3 Zoom in 按钮 → 放大 ×1.25', Math.abs(s2.scale - s1.scale * 1.25) < 0.01 && s2.pct !== s1.pct, `${s1.scale}→${s2.scale}`);
await page.click('.canvas-zoom-btn[aria-label="Fit to view"]');
await sleep(150);
const s3 = await imgState();
ok('IMG-4 Fit 按钮 → 回到适配', Math.abs(s3.scale - s0.scale) < 0.01, `${s3.scale} vs fit ${s0.scale}`);

// ②b 拖拽平移
await page.click('.canvas-zoom-btn[aria-label="Zoom in"]'); // 先放大便于观察平移
await sleep(120);
const p0 = await imgState();
const vpBox = await page.locator('.canvas-image-viewport').boundingBox();
await page.mouse.move(vpBox.x + vpBox.width / 2, vpBox.y + vpBox.height / 2);
await page.mouse.down();
await page.mouse.move(vpBox.x + vpBox.width / 2 + 80, vpBox.y + vpBox.height / 2 + 50, { steps: 4 });
await page.mouse.up();
const p1 = await imgState();
ok('IMG-5 拖拽平移 → translate 变化', Math.abs(p1.tx - p0.tx - 80) < 2 && Math.abs(p1.ty - p0.ty - 50) < 2, `t(${p0.tx},${p0.ty})→(${p1.tx},${p1.ty})`);

// ③ 键盘：悬停查看器时 cmd+= 接管；移出后不接管
const keyTest = async (hoverViewer) => page.evaluate((hover) => {
  const el = hover ? document.querySelector('.canvas-image-viewport') : document.body;
  const ev = new KeyboardEvent('keydown', { key: '=', metaKey: true, bubbles: true, cancelable: true });
  return { prevented: !el.dispatchEvent(ev) };
}, hoverViewer);
// 悬停查看器
await page.mouse.move(vpBox.x + 30, vpBox.y + 30);
const k1 = await keyTest(true);
const k1s = await imgState();
ok('IMG-6 悬停时 cmd+= → 接管缩放', k1.prevented && k1s.scale > p1.scale, `prevented=${k1.prevented} ${p1.scale}→${k1s.scale}`);
// 移到查看器外（主聊天区）
await page.mouse.move(200, 500);
await sleep(100);
const k2 = await keyTest(true); // 事件仍从 viewport 派发（合成事件），但 hover 集已空——不应接管
const k2s = await imgState();
ok('IMG-7 移出查看器 → cmd+= 不接管（浏览器缩放不受影响）', !k2.prevented && k2s.scale === k1s.scale, `prevented=${k2.prevented}`);
await page.screenshot({ path: process.env.HOME + '/.nebflow/docs/Nebflow/assets/canvas-zoom-image.png' });

// ══ PDF 缩放 ══════════════════════════════════════════════
await openFile('/tmp/test.pdf', 'pdf', 'test.pdf');
await page.waitForSelector('.pdf-page', { timeout: 15000 });
// 等首页渲染完成（canvas 有宽度）
await page.waitForFunction(() => {
  const c = document.querySelector('.pdf-page');
  return c && c.width > 100;
}, { timeout: 15000 });
await sleep(300);
ok('PDF-0 pdf.js 渲染出页面 canvas', true, await page.$eval('.pdf-page', c => `${c.width}x${c.height}`));
const pdfW0 = await page.$eval('.pdf-page', c => c.width);
await page.evaluate(() => {
  const pages = document.querySelector('.pdf-pages');
  const r = pages.getBoundingClientRect();
  pages.dispatchEvent(new WheelEvent('wheel', { ctrlKey: true, deltaY: -400, bubbles: true, cancelable: true, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 }));
});
await sleep(600); // 120ms debounce + re-render
const pdfW1 = await page.$eval('.pdf-page', c => c.width);
const pdfPct = await page.$eval('.canvas-zoom-pct', e => e.textContent);
ok('PDF-1 ctrl+wheel → pdf.js 按新 scale 重渲染（canvas 变宽）', pdfW1 > pdfW0, `${pdfW0}→${pdfW1} pct=${pdfPct}`);
await page.click('.canvas-zoom-btn[aria-label="Fit to view"]');
await sleep(600);
const pdfW2 = await page.$eval('.pdf-page', c => c.width);
ok('PDF-2 Fit 按钮 → 回适配宽度', Math.abs(pdfW2 - pdfW0) < 4, `${pdfW2} vs ${pdfW0}`);
await page.screenshot({ path: process.env.HOME + '/.nebflow/docs/Nebflow/assets/canvas-zoom-pdf.png' });

// ══ #302 回归：deck 打开不递归 ════════════════════════════
await openFile(DECK, 'html', 'index.html', true);
await sleep(5000);
const frames = page.frames();
const nested = frames.filter(f => f.url().startsWith(BASE)).length - 1;
ok('302-REG deck 打开 → 零嵌套零递归', nested === 0, `frames=${frames.length} nested=${nested}`);

await browser.close();
console.log(failures === 0 ? '\nALL PASS' : `\n${failures} FAILURES`);
process.exit(failures ? 1 : 0);

// AskUser option highlight geometry contract. Run: node tests/ask-option-highlight.spec.mjs
import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve, extname } from 'node:path';

const root = resolve('src/main/resources/web');
const server = createServer(async (req, res) => {
  try {
    const pathname = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);
    const file = resolve(root, '.' + pathname);
    if (!file.startsWith(root + '/')) throw Error('invalid path');
    const data = await readFile(file);
    res.setHeader('Content-Type', extname(file) === '.css' ? 'text/css' : 'text/html');
    res.end(data);
  } catch { res.writeHead(404).end(); }
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
let browser;
let failures = 0;
try {
  browser = await chromium.launch({ headless: true });
  const index = await readFile(resolve(root, 'index.html'), 'utf8');
  const links = [...index.matchAll(/<link[^>]+href="(css\/[^"?]+\.css)"[^>]*>/g)].map(m => `<link rel="stylesheet" href="/${m[1]}">`).join('');
  for (const width of [390, 1280]) for (const theme of ['dark', 'light']) {
    const context = await browser.newContext({ viewport: { width, height: 844 }, colorScheme: theme, hasTouch: width === 390, isMobile: width === 390 });
    const page = await context.newPage();
    await page.goto(`http://127.0.0.1:${server.address().port}/index.html`, { waitUntil: 'domcontentloaded' });
    await page.setContent(`<!doctype html><html data-theme="${theme}"><head><meta name="viewport" content="width=device-width,initial-scale=1">${links}</head><body><div id="chat"><div class="option-box"><div class="option-question">选择接下来的处理方式？</div><div class="option-opts"><button class="option-btn"><span class="option-text">准一次例外，先用宿主浏览器下<small>仅这次验收：浏览器直连，处理完成后清理临时资料。</small></span></button><button class="option-btn"><span class="option-text">不例外，先补载体<small>补齐后再验收，保留原有安全边界。</small></span></button></div></div></div></body></html>`, { waitUntil: 'load' });
    const button = page.locator('.option-btn').first();
    const box = await button.boundingBox();
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    await page.mouse.down();
    const state = await button.evaluate(el => {
      const s = getComputedStyle(el), r = el.getBoundingClientRect();
      return { row: { x: r.x, y: r.y, width: r.width, height: r.height }, background: s.backgroundColor, tap: s.webkitTapHighlightColor, radius: s.borderTopLeftRadius };
    });
    if (process.env.SHOT_DIR && theme === 'dark') await page.screenshot({ path: `${process.env.SHOT_DIR}/${process.env.SHOT_PREFIX || 'after'}-${width}.png` });
    await page.mouse.up();
    // Tap overlay is UA-painted, not a DOM element: transparent color makes it absent.
    // Active background is the button itself, and must either be absent or cover its row.
    const r = state.row;
    const transparent = state.tap === 'rgba(0, 0, 0, 0)' || state.tap === 'transparent';
    const ok = transparent && parseFloat(state.radius) < r.height / 2;
    console.log(`${ok ? 'PASS' : 'FAIL'} ${width} ${theme} ${JSON.stringify(state)}`);
    if (!ok) failures++;
    if (width === 1280) {
      await page.evaluate(() => document.activeElement.blur());
      await page.keyboard.press('Tab');
      await button.focus();
      const focus = await button.evaluate(el => ({ visible: el.matches(':focus-visible'), style: getComputedStyle(el).outlineStyle, width: getComputedStyle(el).outlineWidth }));
      const focusOk = focus.visible && focus.style !== 'none' && parseFloat(focus.width) > 0;
      console.log(`${focusOk ? 'PASS' : 'FAIL'} keyboard focus ${theme} ${JSON.stringify(focus)}`);
      if (!focusOk) failures++;
    }
    await context.close();
  }
} finally {
  if (browser) await browser.close();
  await new Promise(r => server.close(r));
}
if (failures) process.exitCode = 1;

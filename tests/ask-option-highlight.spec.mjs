// Real AskUser render path, touch overlay and highlight geometry regression.
// Run: node tests/ask-option-highlight.spec.mjs
import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve, extname, sep } from 'node:path';
import { execFileSync } from 'node:child_process';

const root = resolve('.');
const mime = { '.css': 'text/css', '.js': 'text/javascript', '.html': 'text/html', '.svg': 'image/svg+xml', '.json': 'application/json' };
const server = createServer(async (req, res) => {
  try {
    const file = resolve(root, '.' + decodeURIComponent(new URL(req.url, 'http://localhost').pathname));
    if (!file.startsWith(root + sep)) throw Error('invalid path');
    res.setHeader('Content-Type', mime[extname(file)] || 'application/octet-stream');
    if (process.env.ASK_BASELINE && file === resolve(root, 'src/main/resources/web/css/chat.css')) {
      res.end(execFileSync('git', ['show', `${process.env.ASK_BASELINE}:src/main/resources/web/css/chat.css`]));
    } else res.end(await readFile(file));
  } catch { res.writeHead(404).end(); }
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
let browser;
let failures = 0;
const report = (ok, label, detail) => { console.log(`${ok ? 'PASS' : 'FAIL'} ${label} ${JSON.stringify(detail)}`); if (!ok) failures++; };
try {
  browser = await chromium.launch({ headless: true });
  const index = await readFile(resolve(root, 'src/main/resources/web/index.html'), 'utf8');
  const styles = [...index.matchAll(/<link[^>]+href="(css\/[^"?]+\.css)"[^>]*>/g)].map(m => m[1]);
  for (const width of [390, 1280]) for (const theme of ['dark', 'light']) {
    const context = await browser.newContext({ viewport: { width, height: 844 }, colorScheme: theme, hasTouch: true, isMobile: width === 390 });
    const page = await context.newPage();
    const base = `http://127.0.0.1:${server.address().port}`;
    await page.goto(`${base}/tests/fixtures/askuser-expand/harness.html`);
    await page.waitForFunction(() => window.__ready === true);
    await page.evaluate(() => { document.querySelectorAll('link[rel="stylesheet"]').forEach(e => e.remove()); document.querySelector('#ax-chat').id = 'chat'; const meta = document.createElement('meta'); meta.name = 'viewport'; meta.content = 'width=device-width, initial-scale=1'; document.head.appendChild(meta); });
    for (const style of styles) await page.addStyleTag({ url: `${base}/src/main/resources/web/${style}` });
    await page.addStyleTag({ content: '*, *::before, *::after { animation-duration: 0s !important; transition-duration: 0s !important; }' });
    await page.evaluate(async ({ theme }) => {
      document.documentElement.dataset.theme = theme;
      await window.__axAsk([{ question: '选择接下来的处理方式？', allowOther: false, options: [
        { label: '准一次例外，先用宿主浏览器下载并完成验收', description: '仅这次验收：浏览器直连待下载清单中的出版商，处理完成后清理临时资料。' },
        { label: '不例外，先补载体', description: '补齐后再验收，保留原有安全边界。' },
      ] }], 'geometry-contract');
    }, { theme });
    const button = page.locator('.option-box .option-opts .option-btn').first();
    await button.waitFor();
    const census = async () => button.evaluate(row => {
      const rect = el => { const r = el.getBoundingClientRect(); return { x: r.x, y: r.y, width: r.width, height: r.height }; };
      const cs = (el, pseudo) => getComputedStyle(el, pseudo);
      const radius = s => Math.max(...['borderTopLeftRadius', 'borderTopRightRadius', 'borderBottomLeftRadius', 'borderBottomRightRadius'].map(k => parseFloat(s[k]) || 0));
      const nodes = [row, ...row.querySelectorAll('*')];
      return { row: rect(row), picked: row.classList.contains('picked'), rowRadius: radius(cs(row)), nodes: nodes.map(el => ({ tag: el.tagName, cls: el.className, box: rect(el), radius: radius(cs(el)), tap: cs(el).webkitTapHighlightColor, background: cs(el).backgroundColor, image: cs(el).backgroundImage })), radio: { content: cs(row, '::before').content, width: cs(row, '::before').width, height: cs(row, '::before').height, radius: cs(row, '::before').borderRadius } };
    });
    const inspect = (stage, state) => {
      const row = state.row;
      const bad = state.nodes.flatMap(n => {
        const issues = [];
        if (!['rgba(0, 0, 0, 0)', 'transparent'].includes(n.tap)) issues.push('tap');
        if (n.radius > state.rowRadius + 0.01) issues.push('radius');
        const visibleBg = !['rgba(0, 0, 0, 0)', 'transparent'].includes(n.background) || n.image !== 'none';
        if (visibleBg && (n.box.x > row.x + 0.5 || n.box.y > row.y + 0.5 || n.box.x + n.box.width < row.x + row.width - 0.5 || n.box.y + n.box.height < row.y + row.height - 0.5)) issues.push('background-not-covering-row');
        return issues.map(issue => `${n.tag}.${n.cls}:${issue}`);
      });
      // The ::before radio is a deliberately small selection indicator, not a row highlight.
      const radio = state.radio;
      if (radio.content === 'none' || parseFloat(radio.width) > 20 || parseFloat(radio.height) > 20) bad.push('radio-indicator-unexpected');
      if (state.rowRadius >= row.height / 2) bad.push('pill-row');
      if (stage === 'selected' && !state.picked) bad.push('not-picked');
      report(!bad.length, `${width} ${theme} ${stage}`, { bad, ...state });
    };
    inspect('initial', await census());
    // CDP sends native touch start/end, producing pointerdown/pointerup/click.
    const events = [];
    await button.evaluate(el => { window.__geometryEvents = []; for (const type of ['pointerdown', 'pointerup', 'click']) el.addEventListener(type, e => window.__geometryEvents.push(`${e.type}:${e.pointerType}`)); });
    const box = await button.boundingBox();
    const cdp = await context.newCDPSession(page);
    const point = { x: box.x + box.width / 2, y: box.y + box.height / 2 };
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [point] });
    inspect('pressed', await census());
    if (process.env.SHOT_DIR && theme === 'dark') await page.screenshot({ path: `${process.env.SHOT_DIR}/${process.env.SHOT_PREFIX || 'after'}-${width}.png` });
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    events.push(...await page.evaluate(() => window.__geometryEvents));
    report(events.some(e => e === 'pointerdown:touch') && events.some(e => e === 'pointerup:touch') && events.some(e => e.startsWith('click:')), `${width} ${theme} touch-events`, events);
    inspect('selected', await census());
    if (width === 1280) {
      await page.evaluate(() => document.activeElement.blur());
      await page.keyboard.press('Tab');
      await button.focus();
      const focus = await button.evaluate(el => { const s = getComputedStyle(el); const r = el.getBoundingClientRect(); return { visible: el.matches(':focus-visible'), style: s.outlineStyle, width: s.outlineWidth, box: { x: r.x, y: r.y, width: r.width, height: r.height } }; });
      report(focus.visible && focus.style !== 'none' && parseFloat(focus.width) > 0, `keyboard focus ${theme}`, focus);
    }
    await context.close();
  }
} finally {
  if (browser) await browser.close();
  await new Promise(r => server.close(r));
}
if (failures) process.exitCode = 1;

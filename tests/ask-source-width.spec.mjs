// Real ask renderer and styles, served by Playwright routes only; no engine or port.
import { test, expect } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const out = process.env.ABW_EVID || '/private/tmp/abw-evid';
const phase = process.env.ABW_PHASE || 'after';
const mime = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' };
const longOwner = 'Very-long-project-name-with-a-substantial-amount-of-extra-text';
const longNode = 'Very-long-node-name-with-a-substantial-amount-of-extra-text';

test('source badge fits content and bounds long labels', async ({ browser }) => {
  fs.mkdirSync(out, { recursive: true });
  const results = [];
  for (const mobile of [false, true]) for (const scheme of ['light', 'dark']) {
    const context = await browser.newContext({ viewport: mobile ? { width: 375, height: 812 } : { width: 1440, height: 900 }, colorScheme: scheme });
    const page = await context.newPage();
    await page.route('**/*', route => {
      const p = decodeURIComponent(new URL(route.request().url()).pathname);
      const file = path.resolve(root, '.' + p);
      if (!file.startsWith(root + path.sep)) return route.abort();
      try {
        const body = phase === 'before' && p === '/src/main/resources/web/css/ask.css'
          ? execFileSync('git', ['show', '7b221a22c:src/main/resources/web/css/ask.css'], { cwd: root })
          : fs.readFileSync(file);
        return route.fulfill({ body, contentType: mime[path.extname(file)] || 'application/octet-stream' });
      }
      catch { return route.fulfill({ status: 404, body: '' }); }
    });
    await page.goto('http://isolated.invalid/tests/fixtures/askowner-badge/harness.html');
    await page.waitForFunction(() => window.__ready === true);
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.evaluate(m => window.__mobile(m), mobile);
    const sample = async (source, name) => {
      await page.evaluate(s => window.__render({ source: s }), source);
      const data = await page.locator('.ask-user-source').evaluate(el => {
        const r = el.getBoundingClientRect(), cs = getComputedStyle(el), parent = el.querySelector('.ask-user-source-parent');
        const measure = document.createRange(); measure.selectNodeContents(parent);
        const text = measure.getBoundingClientRect();
        const parts = [...el.querySelectorAll('.ask-user-source-parent,.ask-user-source-node')].map(x => ({ width: x.getBoundingClientRect().width, client: x.clientWidth, scroll: x.scrollWidth, overflow: getComputedStyle(x).textOverflow }));
        const card = el.nextElementSibling?.getBoundingClientRect();
        return { width: r.width, height: r.height, top: r.top, bottom: r.bottom, paddingLeft: cs.paddingLeft, paddingRight: cs.paddingRight, textWidth: text.width, title: el.title, parts, nextTop: card?.top, maxWidth: cs.maxWidth };
      });
      await page.screenshot({ animations: 'disabled', path: path.join(out, `${phase}-${mobile ? 'mobile' : 'desktop'}-${scheme}-${name}.png`) });
      return data;
    };
    const short = await sample({ ownerName: 'Spider' }, 'short');
    const long = await sample({ ownerName: longOwner, nodeName: longNode }, 'long');
    results.push({ mobile, scheme, short, long });
    await context.close();
  }
  fs.writeFileSync(path.join(out, `${phase}-measurements.json`), JSON.stringify(results, null, 2));
  for (const { short, long } of results) {
    expect(Math.abs(short.width - short.textWidth - parseFloat(short.paddingLeft) - parseFloat(short.paddingRight))).toBeLessThan(1);
    expect(long.title).toBe(`${longOwner} · ${longNode}`);
    expect(long.parts.some(p => p.scroll > p.client && p.overflow === 'ellipsis')).toBe(true);
  }
});

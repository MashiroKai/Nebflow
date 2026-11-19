// capture-prototypes.mjs — shoot each candidate at a真全屏 phone form (375x812),
// dark + light, for both real diff payloads; record comparable geometry.
import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFileSync, statSync, writeFileSync, mkdirSync } from 'node:fs';
import { join, normalize, extname, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const EVID = process.env.EVID || '/Users/kaiyu/stand-evidence/mobile-diff-survey-20261118/candidates';
mkdirSync(EVID, { recursive: true });

const PORT = 8477;
const MONACO = '/private/tmp/stand-mobile-diff-survey-20261118/src/main/resources/web/vendor/monaco';
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.ttf': 'font/ttf', '.woff': 'font/woff', '.woff2': 'font/woff2', '.map': 'application/json' };
const server = createServer((req, res) => {
  let p = decodeURIComponent(new URL(req.url, 'http://127.0.0.1').pathname);
  let base = join(HERE, 'dist');
  if (p.startsWith('/monaco/')) { base = MONACO; p = p.slice('/monaco'.length); }
  if (p === '/') p = '/index.html';
  const file = normalize(join(base, p));
  if (!file.startsWith(base)) { res.writeHead(403); res.end(); return; }
  try { if (statSync(file).isDirectory()) throw 0;
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' }); res.end(readFileSync(file));
  } catch { res.writeHead(404); res.end('not found'); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

const KEYS = { 'a-codemirror': 'codemirror', 'b-diff2html': 'diff2html', 'c-monaco': 'monaco' };
const out = { viewport: { width: 375, height: 812 }, candidates: {} };
const browser = await chromium.launch();
try {
  for (const [name, key] of Object.entries(KEYS)) {
    for (const theme of ['dark', 'light']) {
      const ctx = await browser.newContext({ viewport: { width: 420, height: 900 }, colorScheme: theme, deviceScaleFactor: 3 });
      const page = await ctx.newPage();
      const errors = [];
      page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 240)));
      page.on('console', (m) => { if (m.type() === 'error') errors.push('console: ' + m.text().slice(0, 200)); });
      if (process.env.LOG_404) page.on('response', (r) => { if (r.status() >= 400) console.log('  ', r.status(), r.url()); });
      await page.goto(`http://127.0.0.1:${PORT}/${name}.html?theme=${theme}`, { waitUntil: 'load' });
      await page.waitForFunction((k) => window.__RESULTS__ && window.__RESULTS__[k], key, { timeout: 30000 }).catch(() => {});
      await page.waitForTimeout(1200);
      const probe = await page.evaluate(() => {
        const g = (sel) => { const e = document.querySelector(sel); if (!e) return null; const r = e.getBoundingClientRect(); return { w: +r.width.toFixed(1), h: +r.height.toFixed(1) }; };
        const scroll = (sel) => { const e = document.querySelector(sel); if (!e) return null; return { clientW: e.clientWidth, scrollW: e.scrollWidth, clientH: e.clientHeight, scrollH: e.scrollHeight, needsH: e.scrollWidth > e.clientWidth + 1 }; };
        const count = (sel) => document.querySelectorAll(sel).length;
        const buttons = [...document.querySelectorAll('#s-ask button, #s-ask .cm-merge-control, #s-ask .cm-chunkButtons button')].map((b) => { const r = b.getBoundingClientRect(); return { w: +r.width.toFixed(1), h: +r.height.toFixed(1), t: (b.textContent || '').trim().slice(0, 12) }; });
        // the real horizontal-scroll carrier is a descendant (CM scroller,
        // d2h code wrapper, monaco scrollable-element) — find the widest one
        const widest = (rootSel) => {
          const root = document.querySelector(rootSel); if (!root) return null;
          let best = { sel: rootSel, scrollW: root.scrollWidth, clientW: root.clientWidth };
          for (const e of root.querySelectorAll('*')) {
            const r = e.getBoundingClientRect();
            if (r.width === 0) continue;
            if (e.scrollWidth > best.scrollW && e.scrollWidth - e.clientWidth >= 1) {
              best = { sel: e.className ? '.' + String(e.className).split(' ')[0] : e.tagName, scrollW: e.scrollWidth, clientW: e.clientWidth };
            }
          }
          return { ...best, needsH: best.scrollW > best.clientW + 1 };
        };
        return {
          delegateScreen: g('#s-delegate'), askScreen: g('#s-ask'),
          delegateScroll: scroll('#delegate-root'), askScroll: scroll('#ask-root'),
          delegateWidest: widest('#delegate-root'), askWidest: widest('#ask-root'),
          gutterNodes: count('.cm-gutters, .d2h-code-linenumber, .line-numbers'),
          lineNoNodes: count('.cm-lineNumbers .cm-gutterElement, .d2h-code-linenumber'),
          wordDiffNodes: count('.cm-changedText, .d2h-code-line del, .d2h-code-line ins'),
          foldNodes: count('.cm-collapsedLines, .cm-foldGutter span, [class*="collapse"]'),
          askButtons: buttons,
          rootFontSize: getComputedStyle(document.querySelector('#ask-root') || document.body).fontSize,
        };
      });
      out.candidates[`${name}-${theme}`] = { ...probe, reported: await page.evaluate((k) => window.__RESULTS__[k], key), pageErrors: errors };
      await page.locator('#s-delegate').screenshot({ path: join(EVID, `${name}-delegate-${theme}.png`) });
      await page.locator('#s-ask').screenshot({ path: join(EVID, `${name}-ask-${theme}.png`) });
      const sbs = await page.locator('#s-sbs .root').count();
      if (sbs && await page.evaluate(() => document.querySelector('#s-sbs .root').children.length > 0)) {
        await page.locator('#s-sbs').screenshot({ path: join(EVID, `${name}-sbs-${theme}.png`) });
      }
      await ctx.close();
    }
  }
} finally { await browser.close(); server.close(); }
out.generatedAt = Date.now();
writeFileSync(join(EVID, 'measurements-candidates.json'), JSON.stringify(out, null, 2));
console.log(JSON.stringify(out, null, 2).slice(0, 6000));

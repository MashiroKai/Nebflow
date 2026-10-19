// verify-title.mjs — STAND tab-title batch (2026-10-19) runtime verification.
// Serves nothing itself: expects the repo's web/ static server on :8791
//   python3 -m http.server 8791 -d src/main/resources/web
// Static serving = no gateway __BRAND__ injection → brand.js whitelist
// fallback runs (the exact path worktree previews/tests take).
//
// Faces checked:
//   1. client tab      → brand.js initBranding()     → expect 'STAND'
//   2. embedded guard  → main.js nfEmbedded branch   → expect 'STAND (embedded)'
// Both via real module graph in headless chromium (playwright, global install).
import { chromium } from '/opt/homebrew/lib/node_modules/playwright/index.mjs';

const BASE = 'http://127.0.0.1:8791';
const results = [];
const browser = await chromium.launch();
try {
  // Face 1 — main client tab.
  const page = await browser.newPage();
  await page.goto(BASE + '/', { waitUntil: 'load' });
  await page.waitForFunction(() => document.title !== '');
  const t1 = await page.title();
  results.push({ face: 'client tab (brand.js initBranding)', expect: 'STAND', got: t1, pass: t1 === 'STAND' });

  // Face 2 — embedded guard (iframe ⇒ index.html inline classic script sets
  // nfEmbedded=1 ⇒ main.js sets the embedded title and refuses boot).
  const p2 = await browser.newPage();
  await p2.setContent(`<!doctype html><body><iframe id="f" src="${BASE}/"></iframe></body>`);
  const frame = p2.frames().find((f) => f !== p2.mainFrame());
  await frame.waitForFunction(() => document.title !== '' && /embedded/.test(document.title), null, { timeout: 15000 });
  const t2 = await frame.title();
  results.push({ face: 'embedded guard (main.js)', expect: 'STAND (embedded)', got: t2, pass: t2 === 'STAND (embedded)' });

  // Evidence sheet: programmatic readouts rendered for the record.
  const rows = results.map((r) =>
    `<tr><td>${r.face}</td><td><code>${r.expect}</code></td><td><code>${r.got}</code></td><td>${r.pass ? 'PASS' : 'FAIL'}</td></tr>`
  ).join('\n');
  const sheet = `<!doctype html><meta charset="utf-8"><title>STAND tab-title verification readout</title>
<style>body{font:13px/1.6 -apple-system,sans-serif;margin:32px;background:#fafafa;color:#111}
h1{font-size:16px;font-weight:600}table{border-collapse:collapse;background:#fff}
th,td{border:1px solid #ddd;padding:8px 12px;text-align:left}.ok{color:#0a7d33;font-weight:600}</style>
<h1>STAND tab-title verification · static-serve fallback brand path · ${new Date().toISOString()}</h1>
<table><tr><th>face</th><th>expected</th><th>got (document.title)</th><th></th></tr>${rows}</table>
<p>Assertion source: evidence/20261019_204721_stand-title-case/verify-title.mjs</p>`;
  await p2.setContent(sheet);
  await p2.screenshot({ path: new URL('./title-readout.png', import.meta.url).pathname.replaceAll('%20', ' '), fullPage: true });

  let failed = 0;
  for (const r of results) { console.log(`${r.pass ? 'PASS' : 'FAIL'}  ${r.face}: got "${r.got}"`); if (!r.pass) failed++; }
  process.exit(failed ? 1 : 0);
} finally {
  await browser.close();
}

#!/usr/bin/env node
// e2e-usage-dashboard-open.mjs — browser-side end-to-end timing of the token panel open
// (author ruling ④ of the tokenpanel-incremental batch: "打开耗时不倍增" must be measured
// end to end, not backend-only).
//
// Real isolated instance (own port + own home) + real gateway + real ledger. The
// script drives the *actual* user path — click #usage-btn, wait for the heatmap's
// first painted frame — and reports:
//   · performance.now() delta around the open (what the author feels);
//   · every /api/usage/aggregate request the panel issued (8 = 7 summary + 1 heatmap);
//   · the open repeated N times (cold first open = one full scan, then warm opens).
//
// Isolation + process hygiene: only the URL given via NEBFLOW_URL is touched; the
// browser is closed in `finally` and on SIGINT/SIGTERM; nothing is spawned here.
//
// Usage:
//   NEBFLOW_URL=http://127.0.0.1:8097 NEBFLOW_HOME_DIR=/tmp/nb-tp-home \
//     NODE_PATH=$(npm root -g) node scripts/e2e-usage-dashboard-open.mjs --out <json> --opens 3

import { chromium } from 'playwright';
import fs from 'node:fs';
import path from 'node:path';

const args = process.argv.slice(2);
const opt = (name, dflt) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : dflt;
};

const BASE = process.env.NEBFLOW_URL || 'http://127.0.0.1:8097';
const HOME = process.env.NEBFLOW_HOME_DIR || `${process.env.HOME}/.nebflow`;
const OUT = opt('--out', '/tmp/usage-dashboard-open.json');
const OPENS = Number(opt('--opens', '3'));
const TIMEOUT_MS = Number(opt('--timeout', '300000'));

const auth = JSON.parse(fs.readFileSync(path.join(HOME, 'auth.json'), 'utf8'));
const token = typeof auth === 'string' ? auth : (auth.token || auth.value || '');

let browser;
const cleanup = async () => {
  try { if (browser) await browser.close(); } catch { /* already closed */ }
};
process.on('SIGINT', async () => { await cleanup(); process.exit(130); });
process.on('SIGTERM', async () => { await cleanup(); process.exit(143); });

const openResults = [];
const apiCalls = [];

const main = async () => {
  browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  page.on('response', async (res) => {
    if (res.url().includes('/api/usage/aggregate')) {
      const req = res.request();
      apiCalls.push({ url: res.url(), status: res.status(), at: Date.now() });
    }
  });
  page.on('pageerror', (e) => console.log(`  [pageerror] ${e.message}`));

  await page.goto(`${BASE}/?token=${encodeURIComponent(token)}`, { waitUntil: 'domcontentloaded', timeout: TIMEOUT_MS });
  await page.waitForSelector('#usage-btn', { timeout: TIMEOUT_MS });
  // Let module init + first WS snapshot settle (no dashboard work yet).
  await page.waitForTimeout(1500);

  for (let i = 0; i < OPENS; i++) {
    const callsBefore = apiCalls.length;
    const result = await page.evaluate(async () => {
      const btn = document.getElementById('usage-btn');
      const t0 = performance.now();
      btn.click();
      // "first frame" = overlay visible, not in the reloading state, heatmap painted.
      const deadline = t0 + 300000;
      while (performance.now() < deadline) {
        const overlay = document.getElementById('usage-overlay');
        const wrap = document.getElementById('usage-heatwrap');
        const grid = document.getElementById('usage-grid');
        const painted = grid && grid.querySelectorAll('.ud-week').length > 0;
        const visible = overlay && overlay.hidden === false;
        const reloading = wrap ? wrap.classList.contains('ud-reloading') : true;
        if (painted && visible && !reloading) {
          // one more frame so the paint is real, not a DOM-only state
          await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
          return { ms: performance.now() - t0, painted, visible, reloading };
        }
        await new Promise((r) => setTimeout(r, 25));
      }
      return { ms: performance.now() - t0, timedOut: true };
    });
    const wall = result.ms;
    const calls = apiCalls.length - callsBefore;
    const cells = await page.evaluate(() => document.querySelectorAll('#usage-grid .ud-week').length);
    const summary = await page.evaluate(() => {
      const el = document.getElementById('usage-overlay');
      return el ? el.innerText.slice(0, 200) : '';
    });
    openResults.push({ open_index: i, browser_open_ms: wall, heatmap_weeks: cells, aggregate_requests: calls, timed_out: !!result.timedOut, summary_head: summary.replace(/\s+/g, ' ').slice(0, 120) });
    console.log(`[e2e] open#${i}: ${wall.toFixed(1)} ms, ${calls} aggregate requests, ${cells} heatmap weeks${result.timedOut ? ' (TIMEOUT)' : ''}`);
    // close again so the next open repeats the whole user path
    if (i < OPENS - 1) {
      await page.evaluate(() => document.getElementById('usage-btn')?.click());
      await page.waitForTimeout(800);
    }
  }

  const first = openResults[0];
  const warm = openResults.slice(1);
  const out = {
    batch: 'tokenpanel-incremental',
    base: BASE,
    home: HOME,
    opens: openResults,
    cold_open_ms: first ? first.browser_open_ms : null,
    warm_open_ms: warm.map((r) => r.browser_open_ms),
    warm_median_ms: warm.length ? warm.map((r) => r.browser_open_ms).sort((a, b) => a - b)[Math.floor(warm.length / 2)] : null,
    requests_total: apiCalls.length,
    baseline_reference: {
      backend_panel_open_wall_s: [9.506, 12.700],
      note: 'browser-side baseline was NOT measured by the read-only diagnosis batch (see its §4.5/§16.5) — the before/after browser comparison therefore has no pre-fix leg',
    },
  };
  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, JSON.stringify(out, null, 2));
  console.log(`[e2e] wrote ${OUT}`);
};

try {
  await main();
} finally {
  await cleanup();
}

// probe1.mjs — 只读探针：确认页面能否在无缓存新 context 中启动、UI 骨架有哪些可点面
import { chromium } from '/opt/homebrew/lib/node_modules/playwright/index.mjs';
import fs from 'node:fs';

const OUT = '/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/ui-audit/reports-evidence/ui-audit-post-restart/_probe';
fs.mkdirSync(OUT, { recursive: true });
const BASE = 'http://localhost:6138/';

const browser = await chromium.launch({ headless: true });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 });
const page = await ctx.newPage();
const logs = [];
page.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`.slice(0, 300)));
page.on('pageerror', (e) => logs.push(`[pageerror] ${String(e).slice(0, 300)}`));

await page.goto(BASE, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(6000);

// SW 状态
const swInfo = await page.evaluate(async () => {
  const regs = (await navigator.serviceWorker?.getRegistrations?.()) ?? [];
  const keys = (await caches?.keys?.()) ?? [];
  let indexCached = null;
  const idxKey = keys.find((k) => k.includes('index'));
  if (idxKey) {
    const c = await caches.open(idxKey);
    indexCached = (await c.keys()).slice(0, 10).map((r) => r.url);
  }
  return {
    controlled: !!navigator.serviceWorker?.controller,
    regs: regs.map((r) => ({ scope: r.scope, active: r.active?.scriptURL || null, state: r.active?.state || null })),
    cacheNames: keys,
    indexCached,
  };
});

const title = await page.title();
const url = page.url();
const bodyText = (await page.evaluate(() => document.body.innerText || '')).slice(0, 1200);

// 收集可见交互面
const ui = await page.evaluate(() => {
  const vis = (el) => {
    const r = el.getBoundingClientRect();
    const s = getComputedStyle(el);
    return r.width > 4 && r.height > 4 && s.visibility !== 'hidden' && s.display !== 'none' && s.opacity !== '0';
  };
  const out = [];
  document.querySelectorAll('[id], button, [role="button"], a[href]').forEach((el) => {
    if (!vis(el)) return;
    const r = el.getBoundingClientRect();
    out.push({
      tag: el.tagName.toLowerCase(),
      id: el.id || '',
      cls: (el.className || '').toString().slice(0, 90),
      text: (el.innerText || el.getAttribute('aria-label') || el.title || '').trim().slice(0, 40),
      box: [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)],
    });
  });
  return out;
});

await page.screenshot({ path: `${OUT}/p1-initial.png` });

console.log(JSON.stringify({ title, url, swInfo, bodyText, uiCount: ui.length, ui: ui.slice(0, 160), logs: logs.slice(0, 40) }, null, 1));
await browser.close();

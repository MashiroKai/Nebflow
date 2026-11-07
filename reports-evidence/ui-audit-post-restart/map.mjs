// map.mjs — 登录后枚举各面的可点结构与选择器（只读，为实测脚本定锚）
import { chromium } from '/opt/homebrew/lib/node_modules/playwright/index.mjs';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';

const OUT = '/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/ui-audit/reports-evidence/ui-audit-post-restart/_probe';
fs.mkdirSync(OUT, { recursive: true });
const BASE = 'http://localhost:6138/';

function mintTicket() {
  const out = execFileSync('/Users/kaiyu/.stand/bin/stand', ['token'], { encoding: 'utf8' });
  const m = out.match(/^\s*([A-Za-z0-9_-]{20,})\s*$/m);
  if (!m) throw new Error('no ticket');
  return m[1];
}

const dump = (page) => page.evaluate(() => {
  const vis = (el) => {
    const r = el.getBoundingClientRect();
    const s = getComputedStyle(el);
    return r.width > 4 && r.height > 4 && s.visibility !== 'hidden' && s.display !== 'none';
  };
  const btn = [...document.querySelectorAll('button, [role="button"], a[href], [data-act]')]
    .filter(vis)
    .map((el) => ({
      id: el.id || '', cls: (el.className || '').toString().slice(0, 70),
      t: (el.innerText || el.getAttribute('title') || el.getAttribute('aria-label') || '').trim().replace(/\s+/g, ' ').slice(0, 34),
      box: (() => { const r = el.getBoundingClientRect(); return [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)]; })(),
    }));
  return { btnCount: btn.length, btn };
});

const browser = await chromium.launch({ headless: true });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const page = await ctx.newPage();
await page.goto(`${BASE}?ticket=${encodeURIComponent(mintTicket())}`, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(8000);

const out = {};
out.main = await dump(page);

// 设置面板
await page.click('#settings-btn');
await page.waitForTimeout(1800);
out.settings = await page.evaluate(() => {
  const c = document.getElementById('settings-content');
  const tabs = [...document.querySelectorAll('#settings-modal [data-settings-tab], #settings-modal .settings-tab, #settings-modal .set-tab, #settings-modal [data-tab]')]
    .map((e) => ({ sel: e.id || (e.className || '').toString().slice(0, 60), t: (e.innerText || e.getAttribute('title') || '').trim().replace(/\s+/g, ' ').slice(0, 24) }));
  const secs = [...document.querySelectorAll('#settings-content > *')].map((e) => ({
    tag: e.tagName.toLowerCase(), id: e.id || '', cls: (e.className || '').toString().slice(0, 60),
    t: (e.innerText || '').trim().replace(/\s+/g, ' ').slice(0, 40),
  }));
  return { hasContent: !!c, html: (c?.innerHTML || '').slice(0, 400), tabs, secs: secs.slice(0, 30) };
});
await page.screenshot({ path: `${OUT}/map-settings.png` });
await page.click('#settings-modal-close');
await page.waitForTimeout(800);

// 后台 agent 面板 / 其他 nav
for (const [name, sel] of [['agents', '#agents-btn'], ['projects', '#projects-btn'], ['files', '#files-btn'], ['usage', '#usage-btn'], ['social', '#social-btn']]) {
  if (!(await page.$(sel))) { out[name] = 'no-el'; continue; }
  await page.click(sel);
  await page.waitForTimeout(1400);
  out[name] = await dump(page);
  await page.screenshot({ path: `${OUT}/map-${name}.png` });
}

console.log(JSON.stringify(out, null, 1));
await browser.close();

// probe2.mjs — 确认品牌键名与 token 注入方式，拿到已登录会话
import { chromium } from '/opt/homebrew/lib/node_modules/playwright/index.mjs';
import fs from 'node:fs';

const OUT = '/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/ui-audit/reports-evidence/ui-audit-post-restart/_probe';
fs.mkdirSync(OUT, { recursive: true });
const TOKEN = JSON.parse(fs.readFileSync('/Users/kaiyu/.stand/auth.json', 'utf8'));

const browser = await chromium.launch({ headless: true });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const page = await ctx.newPage();
await page.goto('http://localhost:6138/', { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(2500);

const info = await page.evaluate(() => ({
  brand: window.__BRAND__ || null,
  lsKeys: Object.keys(localStorage),
  cookie: document.cookie.split('; ').map((c) => c.split('=')[0]),
}));
console.log(JSON.stringify(info, null, 1));

const lower = (info.brand && (info.brand.lowerName || info.brand.lower)) || 'stand';
await page.evaluate(([k, t]) => localStorage.setItem(k, t), [`${lower}_token`, TOKEN]);
await page.reload({ waitUntil: 'domcontentloaded' });
await page.waitForTimeout(7000);
const after = await page.evaluate(() => ({
  conn: document.querySelector('#conn-banner')?.className,
  connText: document.querySelector('#conn-banner')?.innerText,
  lsKeys: Object.keys(localStorage),
  body: (document.body.innerText || '').slice(0, 600),
}));
console.log(JSON.stringify(after, null, 1));
await page.screenshot({ path: `${OUT}/p2-logged.png` });
await browser.close();

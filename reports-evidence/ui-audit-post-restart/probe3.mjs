// probe3.mjs — ticket 兑换登录 + 确认 WS 连上（作为后续全部实测的会话基座）
import { chromium } from '/opt/homebrew/lib/node_modules/playwright/index.mjs';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';

const OUT = '/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/ui-audit/reports-evidence/ui-audit-post-restart/_probe';
fs.mkdirSync(OUT, { recursive: true });

function mintTicket() {
  const out = execFileSync('/Users/kaiyu/.stand/bin/stand', ['token'], { encoding: 'utf8' });
  const m = out.match(/^\s*([A-Za-z0-9_-]{20,})\s*$/m);
  if (!m) throw new Error('no ticket in CLI output');
  return m[1];
}

const ticket = mintTicket();
const browser = await chromium.launch({ headless: true });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const page = await ctx.newPage();
page.on('pageerror', (e) => console.log('[pageerror]', String(e).slice(0, 200)));
await page.goto(`http://localhost:6138/?ticket=${encodeURIComponent(ticket)}`, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(9000);
const state = await page.evaluate(() => ({
  url: location.href.replace(/ticket=[^&]+/, 'ticket=<…>'),
  conn: document.querySelector('#conn-banner')?.className,
  cookies: document.cookie.split('; ').map((c) => c.split('=')[0]),
  ls: Object.keys(localStorage),
  bodyHead: (document.body.innerText || '').slice(0, 400),
}));
console.log(JSON.stringify(state, null, 1));
await page.screenshot({ path: `${OUT}/p3-logged.png` });
await browser.close();

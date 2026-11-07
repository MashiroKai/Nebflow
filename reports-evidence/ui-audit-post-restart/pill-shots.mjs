// pill-shots.mjs — 提示位去重四态实拍（手机 375×812 亮/暗）
// 用法：node pill-shots.mjs before  /  ROUTE=1 node pill-shots.mjs after
import { chromium } from '/opt/homebrew/lib/node_modules/playwright/index.mjs';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const ROOT = '/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/ui-audit/reports-evidence/ui-audit-post-restart';
const WT = '/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/ui-audit/src/main/resources/web';
const BASE = 'http://localhost:6138/';
const LABEL = process.argv[2] || 'after';
const ROUTE = process.env.ROUTE === '1';
const mint = () => execFileSync('/Users/kaiyu/.stand/bin/stand', ['token'], { encoding: 'utf8' })
  .match(/^\s*([A-Za-z0-9_-]{20,})\s*$/m)[1];
const OVR = [
  ['**/js/scrollPill.js*', `${WT}/js/scrollPill.js`, 'application/javascript'],
  ['**/css/chat.css*', `${WT}/css/chat.css`, 'text/css'],
];

const browser = await chromium.launch({ headless: true });
const report = {};
for (const theme of ['light', 'dark']) {
  const ctx = await browser.newContext({ viewport: { width: 375, height: 812 }, isMobile: true, hasTouch: true, colorScheme: theme, deviceScaleFactor: 2, locale: 'zh-CN' });
  if (ROUTE) for (const [glob, file, ct] of OVR) {
    await ctx.route(glob, async (r) => {
      try { await r.fulfill({ status: 200, contentType: `${ct}; charset=utf-8`, body: fs.readFileSync(file) }); } catch { await r.fallback(); }
    });
  }
  const page = await ctx.newPage();
  await page.goto(`${BASE}?ticket=${encodeURIComponent(mint())}`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(9000);
  const dir = path.join(ROOT, '20-fixes', '09-pill-dedupe');
  fs.mkdirSync(dir, { recursive: true });
  const shot = async (step) => {
    for (let i = 0; i < 2; i++) {
      const on = await page.evaluate(() => {
        const o = document.getElementById('modal-overlay');
        if (!o || !o.classList.contains('on') || getComputedStyle(o).display === 'none') return false;
        (document.getElementById('takeover-retake') || { click() {} }).click?.();
        return true;
      }).catch(() => false);
      await page.waitForTimeout(400);
      if (!on) break;
    }
    await page.screenshot({ path: path.join(dir, `${theme}-mobile__${step}-${LABEL}.png`), animations: 'disabled' }).catch(() => {});
  };
  const vis = () => page.evaluate(() => [...document.querySelectorAll('.scroll-new-pill')]
    .filter((e) => !e.classList.contains('hidden') && getComputedStyle(e).display !== 'none')
    .map((e) => e.textContent.trim()));
  const st = {};
  // 态1：无新消息但滚离底部
  await page.evaluate(() => { const c = document.getElementById('chat'); c.scrollTop = 0; c.dispatchEvent(new Event('scroll')); });
  await page.waitForTimeout(700);
  st['state1-backToBottom'] = await vis(); await shot('state1-backToBottom');
  // 态2/3：新消息到达（连续两条计数更新）
  await page.evaluate(async () => {
    const c = document.getElementById('chat'); const src = [...c.querySelectorAll('.row')].pop();
    for (let i = 0; i < 1; i++) { const n = src.cloneNode(true); n.dataset.probe = '1'; n.style.height = '60px'; c.append(n); }
    await new Promise((r) => setTimeout(r, 300));
  });
  await page.waitForTimeout(700);
  st['state2-newMsg1'] = await vis(); await shot('state2-newMsg1');
  await page.evaluate(async () => {
    const c = document.getElementById('chat'); const src = [...c.querySelectorAll('.row')].pop();
    const n = src.cloneNode(true); n.dataset.probe = '1'; n.style.height = '60px'; c.append(n);
    await new Promise((r) => setTimeout(r, 300));
  });
  await page.waitForTimeout(700);
  st['state3-newMsg2'] = await vis(); await shot('state3-newMsg2');
  // 态4：点击归位
  await page.evaluate(() => document.querySelector('.scroll-new-pill:not(.hidden)')?.click());
  await page.waitForTimeout(1500);
  st['state4-afterClick'] = await vis(); await shot('state4-afterClick');
  report[theme] = st;
  await ctx.close();
}
await browser.close();
console.log(JSON.stringify({ label: LABEL, route: ROUTE, report }, null, 1));

// mobile-todo-entry.mjs — 手机待办入口到底在哪：全局扫「待办」可点面 + 会话滚到顶后的回合头
import { chromium } from '/opt/homebrew/lib/node_modules/playwright/index.mjs';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
const ROOT = '/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/ui-audit/reports-evidence/ui-audit-post-restart';
const BASE = 'http://localhost:6138/';
const mint = () => execFileSync('/Users/kaiyu/.stand/bin/stand', ['token'], { encoding: 'utf8' }).match(/^\s*([A-Za-z0-9_-]{20,})\s*$/m)[1];
const browser = await chromium.launch({ headless: true });
const ctx = await browser.newContext({ viewport: { width: 375, height: 812 }, isMobile: true, hasTouch: true, colorScheme: 'light', deviceScaleFactor: 2, locale: 'zh-CN' });
const page = await ctx.newPage();
await page.goto(`${BASE}?ticket=${encodeURIComponent(mint())}`, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(9000);
const dir = path.join(ROOT, '06-todo'); fs.mkdirSync(dir, { recursive: true });
const scan = () => page.evaluate(() => [...document.querySelectorAll('body *')]
  .filter((e) => {
    const t = (e.textContent || '').trim();
    if (!/待办/.test(t) || t.length > 24) return false;
    const b = e.getBoundingClientRect(); const s = getComputedStyle(e);
    return b.width > 4 && b.height > 4 && s.visibility !== 'hidden' && s.display !== 'none' && +s.opacity > 0.1;
  })
  .map((e) => {
    const b = e.getBoundingClientRect();
    return { sel: `${e.tagName.toLowerCase()}#${e.id || ''}.${(e.className || '').toString().slice(0, 34)}`, t: (e.textContent || '').trim().slice(0, 18), box: [Math.round(b.x), Math.round(b.y), Math.round(b.width), Math.round(b.height)], inHeader: !!e.closest('#header, .mobile-head, .mh-chrome') };
  }));
const out = {};
out.atLoad = await scan();
out.taskListBox = await page.evaluate(() => {
  const t = document.getElementById('task-list');
  if (!t) return null;
  const b = t.getBoundingClientRect();
  return { cls: t.className, box: [Math.round(b.x), Math.round(b.y), Math.round(b.width), Math.round(b.height)], display: getComputedStyle(t).display, op: getComputedStyle(t).opacity };
});
// 会话滚到顶
await page.evaluate(() => {
  const c = document.getElementById('chat');
  const sc = [c, ...(c ? c.querySelectorAll('*') : [])].filter((e) => e && e.scrollHeight > e.clientHeight + 20);
  sc.forEach((e) => (e.scrollTop = 0));
});
await page.waitForTimeout(1200);
await page.screenshot({ path: path.join(dir, 'light-mobile__chat-scrolled-top.png'), animations: 'disabled' }).catch(() => {});
out.afterScrollTop = await scan();
// 若找到待办可点面就点开
const clicked = await page.evaluate(() => {
  const el = [...document.querySelectorAll('body *')].find((e) => {
    const t = (e.textContent || '').trim();
    const b = e.getBoundingClientRect(); const s = getComputedStyle(e);
    return /待办/.test(t) && t.length < 20 && b.width > 4 && b.height > 4 && s.display !== 'none' && e.children.length < 4;
  });
  if (!el) return null;
  (el.closest('button,[role=button]') || el).dispatchEvent(new MouseEvent('click', { bubbles: true }));
  return `${el.tagName.toLowerCase()}#${el.id || ''}.${(el.className || '').toString().slice(0, 30)}`;
});
await page.waitForTimeout(1600);
await page.screenshot({ path: path.join(dir, 'light-mobile__todo-opened.png'), animations: 'disabled' }).catch(() => {});
out.clicked = clicked;
out.todoPageAfter = await page.evaluate(() => {
  const p = document.getElementById('todo-page');
  if (!p) return null;
  const b = p.getBoundingClientRect();
  return { cls: p.className, box: [Math.round(b.x), Math.round(b.y), Math.round(b.width), Math.round(b.height)], text: (p.innerText || '').trim().replace(/\s+/g, ' ').slice(0, 90) };
});
await ctx.close(); await browser.close();
console.log(JSON.stringify(out, null, 1));

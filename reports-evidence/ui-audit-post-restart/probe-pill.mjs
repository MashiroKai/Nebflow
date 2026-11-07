// probe-pill.mjs — 主会话「回到底部 / 新消息」提示位的运行时探针（只读）
import { chromium } from '/opt/homebrew/lib/node_modules/playwright/index.mjs';
import { execFileSync } from 'node:child_process';

const BASE = 'http://localhost:6138/';
const mint = () => execFileSync('/Users/kaiyu/.stand/bin/stand', ['token'], { encoding: 'utf8' })
  .match(/^\s*([A-Za-z0-9_-]{20,})\s*$/m)[1];

const LIST = () => {
  const out = [];
  for (const e of document.querySelectorAll('*')) {
    const txt = (e.innerText || '').trim();
    if (!/回到底部|条新消息/.test(txt)) continue;
    if (e.children.length && [...e.children].some((c) => /回到底部|条新消息/.test(c.innerText || ''))) continue;
    const b = e.getBoundingClientRect();
    const cs = getComputedStyle(e);
    if (cs.display === 'none' || cs.visibility === 'hidden' || b.width < 4) continue;
    out.push({
      tag: e.tagName.toLowerCase(), id: e.id || '', cls: (e.className || '').toString().slice(0, 60),
      txt: txt.slice(0, 24), box: [Math.round(b.x), Math.round(b.y), Math.round(b.width), Math.round(b.height)],
      op: cs.opacity, z: cs.zIndex, parent: (e.parentElement?.id || e.parentElement?.className || '').toString().slice(0, 40),
    });
  }
  return out;
};

const browser = await chromium.launch({ headless: true });
const ctx = await browser.newContext({ viewport: { width: 375, height: 812 }, isMobile: true, hasTouch: true, colorScheme: 'light', deviceScaleFactor: 2, locale: 'zh-CN' });
const page = await ctx.newPage();
await page.goto(`${BASE}?ticket=${encodeURIComponent(mint())}`, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(9000);
const log = {};
log.initial = await page.evaluate(LIST);

log.scrolled = await page.evaluate(async () => {
  const c = document.getElementById('chat');
  if (!c) return 'no #chat';
  c.scrollTop = 0;
  c.dispatchEvent(new Event('scroll'));
  await new Promise((r) => setTimeout(r, 600));
  return { top: c.scrollTop, h: c.scrollHeight, ch: c.clientHeight };
});
await page.waitForTimeout(700);
log.afterScroll = await page.evaluate(LIST);

log.appended = await page.evaluate(async () => {
  const c = document.getElementById('chat');
  const src = [...c.querySelectorAll('.row')].pop();
  if (!src) return 'no .row';
  const clone = src.cloneNode(true);
  clone.dataset.probe = '1';
  c.append(clone);
  await new Promise((r) => setTimeout(r, 500));
  return clone.className;
});
await page.waitForTimeout(800);
log.afterAppend = await page.evaluate(LIST);
await page.screenshot({ path: '/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/ui-audit/reports-evidence/ui-audit-post-restart/_data/probe-pill.png' });
await browser.close();
console.log(JSON.stringify(log, null, 1));

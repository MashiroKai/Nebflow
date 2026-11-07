// map2.mjs — 细看：消息行结构、输入区结构、附件、待办入口、画布、合并队列
import { chromium } from '/opt/homebrew/lib/node_modules/playwright/index.mjs';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';

const OUT = '/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/ui-audit/reports-evidence/ui-audit-post-restart/_probe';
fs.mkdirSync(OUT, { recursive: true });
const BASE = 'http://localhost:6138/';
const mint = () => {
  const out = execFileSync('/Users/kaiyu/.stand/bin/stand', ['token'], { encoding: 'utf8' });
  return out.match(/^\s*([A-Za-z0-9_-]{20,})\s*$/m)[1];
};

const browser = await chromium.launch({ headless: true });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const page = await ctx.newPage();
await page.goto(`${BASE}?ticket=${encodeURIComponent(mint())}`, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(8000);

const res = {};
res.ids = await page.evaluate(() => [...document.querySelectorAll('[id]')].map((e) => e.id));
res.msgRow = await page.evaluate(() => {
  const rows = [...document.querySelectorAll('.msg, .message, [data-msg-id], .bubble')];
  const last = rows[rows.length - 1];
  return { count: rows.length, sample: last ? last.outerHTML.slice(0, 700) : null, parentCls: last?.parentElement?.className };
});
res.inputArea = await page.evaluate(() => {
  const el = document.querySelector('#input-area, #composer, .composer, #chat-input');
  return el ? { id: el.id, cls: el.className, html: el.outerHTML.slice(0, 1500) } : null;
});
res.todo = await page.evaluate(() => {
  const t = document.querySelector('#todo-inbox, #todo-chip, [data-todo], #todo-btn, #todo-panel');
  return t ? { id: t.id, cls: t.className, text: t.innerText.slice(0, 120) } : null;
});
res.canvas = await page.evaluate(() => {
  const c = document.querySelector('#canvas, #canvas-host, #right-panel');
  return c ? { id: c.id, cls: c.className, text: c.innerText.slice(0, 100) } : null;
});
res.panels = await page.evaluate(() => [...document.querySelectorAll('[data-panel-btn]')].map((e) => ({
  id: e.id, panel: e.dataset.panelBtn, t: (e.getAttribute('title') || '').slice(0, 30),
})));
console.log(JSON.stringify(res, null, 1));
await browser.close();

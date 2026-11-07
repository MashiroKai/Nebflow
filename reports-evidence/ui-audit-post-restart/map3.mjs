// map3.mjs — 探查交互面：消息编辑、附件、canvas、filechanges、task-list、合并队列
import { chromium } from '/opt/homebrew/lib/node_modules/playwright/index.mjs';
import { execFileSync } from 'node:child_process';

const BASE = 'http://localhost:6138/';
const mint = () => execFileSync('/Users/kaiyu/.stand/bin/stand', ['token'], { encoding: 'utf8' })
  .match(/^\s*([A-Za-z0-9_-]{20,})\s*$/m)[1];

const browser = await chromium.launch({ headless: true });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const page = await ctx.newPage();
await page.goto(`${BASE}?ticket=${encodeURIComponent(mint())}`, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(8000);
const out = {};

// 1) 消息行悬停显现的按钮
const rows = await page.$$('.row.user, .row.assistant, .msg');
if (rows.length) {
  const last = rows[rows.length - 1];
  await last.hover();
  await page.waitForTimeout(600);
  out.rowHover = await page.evaluate(() => {
    const r = [...document.querySelectorAll('.row')].filter((e) => e.querySelector('.row-actions, .msg-actions, .act'));
    return [...document.querySelectorAll('.row')].slice(-3).map((e) => ({
      cls: e.className,
      html: e.innerHTML.slice(0, 400),
    }));
  });
  await page.screenshot({ path: '_probe/m3-hover.png' });
}

// 2) 附件按钮 / file input
out.attach = await page.evaluate(() => {
  const area = document.getElementById('input-area');
  const files = [...document.querySelectorAll('input[type=file]')].map((i) => ({ id: i.id, cls: i.className, accept: i.accept, multiple: i.multiple }));
  return { fileInputs: files, attachBtn: !!document.getElementById('attach-btn'), queueBar: !!document.getElementById('queue-bar'), inputEl: document.getElementById('input')?.tagName };
});

// 3) canvas
await page.click('#canvas-toggle-btn').catch((e) => (out.canvasErr = String(e).slice(0, 80)));
await page.waitForTimeout(1500);
out.canvas = await page.evaluate(() => {
  const ids = [...document.querySelectorAll('[id]')].map((e) => e.id).filter((i) => /canvas|card|deck|window/i.test(i));
  return { ids, panelCls: document.getElementById('canvas-panel')?.className, visible: (() => { const e = document.getElementById('canvas-panel'); if (!e) return null; const r = e.getBoundingClientRect(); return [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)]; })() };
});
await page.screenshot({ path: '_probe/m3-canvas.png' });

// 4) filechanges 面板
out.filechanges = await page.evaluate(() => {
  const p = document.getElementById('panel-filechanges');
  const t = document.getElementById('panel-title-filechanges');
  return p ? { cls: p.className, text: (p.innerText || '').slice(0, 200) } : null;
});

// 5) task-list / todo
out.taskList = await page.evaluate(() => {
  const t = document.getElementById('task-list');
  return t ? { cls: t.className, text: (t.innerText || '').slice(0, 300), box: (() => { const r = t.getBoundingClientRect(); return [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)]; })() } : null;
});
out.todoIsland = await page.evaluate(() => {
  const t = document.getElementById('todo-island');
  return t ? { cls: t.className, text: (t.innerText || '').slice(0, 200) } : null;
});

// 6) 合并队列：项目面板/任务面板里找
out.mergeCandidates = await page.evaluate(() => {
  const hits = [];
  document.querySelectorAll('*').forEach((e) => {
    if (e.children.length) return;
    const tx = (e.textContent || '').trim();
    if (/合并队列|Merge queue|merge queue/i.test(tx) && tx.length < 60) {
      hits.push({ cls: (e.className || '').toString().slice(0, 40), tx, parent: e.parentElement?.className?.toString?.().slice(0, 40) });
    }
  });
  return hits.slice(0, 10);
});
console.log(JSON.stringify(out, null, 1));
await browser.close();

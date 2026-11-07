// verify2.mjs — 层叠冲突取证：设置模态 vs 输入条 vs 画布面板（同屏遮挡谁压谁）
import { chromium } from '/opt/homebrew/lib/node_modules/playwright/index.mjs';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const ROOT = '/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/ui-audit/reports-evidence/ui-audit-post-restart';
const BASE = 'http://localhost:6138/';
const DATA = path.join(ROOT, '_data');
const mint = () => execFileSync('/Users/kaiyu/.stand/bin/stand', ['token'], { encoding: 'utf8' })
  .match(/^\s*([A-Za-z0-9_-]{20,})\s*$/m)[1];
const out = {};
const browser = await chromium.launch({ headless: true });
const dir = path.join(ROOT, '12-stacking'); fs.mkdirSync(dir, { recursive: true });

const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'light', locale: 'zh-CN' });
const page = await ctx.newPage();
await page.goto(`${BASE}?ticket=${encodeURIComponent(mint())}`, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(8500);

const info = () => {
  const g = (s) => {
    const e = document.querySelector(s);
    if (!e) return null;
    const c = getComputedStyle(e);
    const b = e.getBoundingClientRect();
    return {
      sel: s, z: c.zIndex, pos: c.position, display: c.display, opacity: c.opacity, pe: c.pointerEvents,
      box: [Math.round(b.x), Math.round(b.y), Math.round(b.width), Math.round(b.height)],
      domOrder: [...document.querySelectorAll('*')].indexOf(e),
    };
  };
  return {
    settingsOverlay: g('#settings-overlay'), modal: g('#settings-modal'),
    inputBar: g('#input-bar'), inputArea: g('#input-area'),
    canvas: g('#canvas-panel'), header: g('#header'), sidebar: g('#sidebar'), bar: g('#activity-bar'),
  };
};

out.before = await page.evaluate(info);

// 打开画布（项目面板）→ 再打开设置
await page.evaluate(() => document.getElementById('canvas-toggle-btn')?.click());
await page.waitForTimeout(1200);
await page.evaluate(() => document.getElementById('settings-btn')?.click());
await page.waitForTimeout(1800);
out.settingsAndCanvas = await page.evaluate(info);

// 关键点命中测试：设置模态的右边缘区域、底部区域
out.hits = await page.evaluate(() => {
  const m = document.getElementById('settings-modal');
  if (!m) return null;
  const b = m.getBoundingClientRect();
  const probe = (x, y, label) => {
    const e = document.elementFromPoint(Math.round(x), Math.round(y));
    return { label, at: [Math.round(x), Math.round(y)], hit: e ? `${e.tagName.toLowerCase()}#${e.id || ''}.${(e.className || '').toString().slice(0, 34)}` : null };
  };
  return [
    probe(b.right - 30, b.top + 120, '模态右上（右侧栏区域）'),
    probe(b.right - 60, b.bottom - 20, '模态右下角'),
    probe(b.left + 40, b.bottom - 20, '模态左下角（输入条区域）'),
    probe((b.left + b.right) / 2, b.bottom - 12, '模态底边中部'),
  ];
});
await page.screenshot({ path: path.join(dir, 'light-desktop__settings-over-canvas.png'), animations: 'disabled' }).catch(() => {});
// 局部特写：底部与右侧重叠处
await page.screenshot({ path: path.join(dir, 'light-desktop__settings-overlap-bottom.png'), clip: { x: 300, y: 760, width: 720, height: 140 } }).catch(() => {});
await page.screenshot({ path: path.join(dir, 'light-desktop__settings-overlap-right.png'), clip: { x: 820, y: 60, width: 340, height: 500 } }).catch(() => {});

// 关设置，只留画布：画布是否压住输入条/顶栏
await page.evaluate(() => document.getElementById('settings-modal-close')?.click());
await page.waitForTimeout(1000);
out.canvasOnly = await page.evaluate(info);
out.canvasHits = await page.evaluate(() => {
  const c = document.getElementById('canvas-panel');
  if (!c) return null;
  const b = c.getBoundingClientRect();
  const probe = (x, y, label) => {
    const e = document.elementFromPoint(Math.round(x), Math.round(y));
    return { label, hit: e ? `${e.tagName.toLowerCase()}#${e.id || ''}.${(e.className || '').toString().slice(0, 34)}` : null };
  };
  return [
    probe(b.left + 20, b.top + 20, '画布左上（顶栏区域）'),
    probe(b.left + 20, b.bottom - 10, '画布左下（输入条区域）'),
  ];
});
await page.screenshot({ path: path.join(dir, 'light-desktop__canvas-only.png'), animations: 'disabled' }).catch(() => {});

// 设置面板全高是否可达底部（滚动到底看最后一屏）
await page.evaluate(() => document.getElementById('settings-btn')?.click());
await page.waitForTimeout(1200);
out.scroll = await page.evaluate(() => {
  const c = document.getElementById('settings-content');
  const m = document.getElementById('settings-modal');
  const host = c && c.scrollHeight > c.clientHeight ? c : m;
  if (host) host.scrollTop = host.scrollHeight;
  return { host: host?.id, st: host?.scrollTop, sh: host?.scrollHeight, ch: host?.clientHeight, reachable: host ? (host.scrollTop + host.clientHeight) >= host.scrollHeight - 2 : null };
});
await page.waitForTimeout(600);
await page.screenshot({ path: path.join(dir, 'light-desktop__settings-bottom-final.png'), animations: 'disabled' }).catch(() => {});

await ctx.close();
await browser.close();
fs.writeFileSync(path.join(DATA, 'stacking.json'), JSON.stringify(out, null, 1));
console.log(JSON.stringify(out, null, 1));

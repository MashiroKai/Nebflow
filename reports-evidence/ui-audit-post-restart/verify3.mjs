// verify3.mjs — 干净复测：启动即有的画布面板 与 设置模态 的层叠（先收掉接管弹窗再命中测试）
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
await page.waitForTimeout(9000);

const killTakeover = async () => {
  for (let i = 0; i < 3; i++) {
    const vis = await page.evaluate(() => {
      const ov = document.getElementById('modal-overlay');
      if (!ov) return false;
      const on = ov.classList.contains('on') && getComputedStyle(ov).display !== 'none';
      if (!on) return false;
      const b = document.getElementById('takeover-box');
      const boxVis = b && b.getBoundingClientRect().width > 10;
      if (boxVis) document.getElementById('takeover-retake')?.click();
      else document.getElementById('modal-overlay')?.classList.remove('on');
      return true;
    }).catch(() => false);
    await page.waitForTimeout(800);
    if (!vis) break;
  }
};

out.canvasAtBoot = await page.evaluate(() => {
  const c = document.getElementById('canvas-panel');
  if (!c) return null;
  const b = c.getBoundingClientRect();
  const cs = getComputedStyle(c);
  // 找最近的、带 z-index 的定位祖先
  let anc = c.parentElement, chain = [];
  while (anc && chain.length < 8) {
    const s = getComputedStyle(anc);
    if (s.position !== 'static' || s.zIndex !== 'auto' || s.transform !== 'none' || s.filter !== 'none') {
      chain.push({ sel: `${anc.tagName.toLowerCase()}#${anc.id || ''}.${(anc.className || '').toString().slice(0, 30)}`, pos: s.position, z: s.zIndex, transform: s.transform.slice(0, 20), filter: s.filter.slice(0, 20) });
    }
    anc = anc.parentElement;
  }
  return { box: [Math.round(b.x), Math.round(b.y), Math.round(b.width), Math.round(b.height)], z: cs.zIndex, pos: cs.position, ancestors: chain };
});

await killTakeover();
await page.screenshot({ path: path.join(dir, 'A-canvas-at-boot.png'), animations: 'disabled' }).catch(() => {});

// 打开设置
await page.evaluate(() => document.getElementById('settings-btn')?.click());
await page.waitForTimeout(1800);
await killTakeover();
await page.waitForTimeout(400);
out.afterSettings = await page.evaluate(() => {
  const info = (s) => { const e = document.querySelector(s); if (!e) return null; const c = getComputedStyle(e); const b = e.getBoundingClientRect(); return { z: c.zIndex, pos: c.position, box: [Math.round(b.x), Math.round(b.y), Math.round(b.width), Math.round(b.height)] }; };
  const m = document.getElementById('settings-modal').getBoundingClientRect();
  const probe = (x, y, label) => { const e = document.elementFromPoint(Math.round(x), Math.round(y)); return { label, hit: e ? `${e.tagName.toLowerCase()}#${e.id || ''}.${(e.className || '').toString().slice(0, 32)}` : null }; };
  return {
    overlay: info('#settings-overlay'), modal: info('#settings-modal'), canvas: info('#canvas-panel'), inputBar: info('#input-bar'),
    hits: [
      probe(m.right - 8, m.top + 200, '模态右边缘'),
      probe(m.right - 40, m.bottom - 8, '模态右下'),
      probe(m.left + 60, m.bottom - 6, '模态左下（输入条上方）'),
      probe(m.left + m.width / 2, m.top + 8, '模态顶边'),
    ],
  };
});
await page.screenshot({ path: path.join(dir, 'B-settings-with-canvas.png'), animations: 'disabled' }).catch(() => {});
await page.screenshot({ path: path.join(dir, 'C-settings-right-edge.png'), clip: { x: 860, y: 60, width: 320, height: 560 } }).catch(() => {});
await page.screenshot({ path: path.join(dir, 'D-settings-bottom-edge.png'), clip: { x: 420, y: 760, width: 620, height: 130 } }).catch(() => {});

// 关画布，只剩设置：再测底部输入条是否仍压模态
await page.evaluate(() => document.getElementById('canvas-toggle-btn')?.click());
await page.waitForTimeout(1000);
await killTakeover();
out.settingsOnly = await page.evaluate(() => {
  const m = document.getElementById('settings-modal').getBoundingClientRect();
  const c = document.getElementById('canvas-panel')?.getBoundingClientRect();
  const probe = (x, y, label) => { const e = document.elementFromPoint(Math.round(x), Math.round(y)); return { label, hit: e ? `${e.tagName.toLowerCase()}#${e.id || ''}.${(e.className || '').toString().slice(0, 32)}` : null }; };
  return { modal: [Math.round(m.x), Math.round(m.y), Math.round(m.width), Math.round(m.height)], canvasW: c ? Math.round(c.width) : null, hits: [probe(m.left + 60, m.bottom - 6, '模态左下（输入条区）'), probe(m.left + m.width / 2, m.bottom - 6, '模态底中')] };
});
await page.screenshot({ path: path.join(dir, 'E-settings-no-canvas.png'), animations: 'disabled' }).catch(() => {});

await ctx.close();
await browser.close();
fs.writeFileSync(path.join(DATA, 'stacking2.json'), JSON.stringify(out, null, 1));
console.log(JSON.stringify(out, null, 1));

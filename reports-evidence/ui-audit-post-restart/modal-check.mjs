// modal-check.mjs — 验证「#modal-overlay 空壳/隐形遮挡」假设（手机层 + 桌面层，干净加载后立刻测）
import { chromium } from '/opt/homebrew/lib/node_modules/playwright/index.mjs';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const ROOT = '/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/ui-audit/reports-evidence/ui-audit-post-restart';
const BASE = 'http://localhost:6138/';
const DATA = path.join(ROOT, '_data');
const mint = () => execFileSync('/Users/kaiyu/.stand/bin/stand', ['token'], { encoding: 'utf8' })
  .match(/^\s*([A-Za-z0-9_-]{20,})\s*$/m)[1];

const res = {};
const browser = await chromium.launch({ headless: true });

const PROBE = () => {
  const ov = document.getElementById('modal-overlay');
  const cs = ov ? getComputedStyle(ov) : null;
  const kids = ov ? [...ov.children].map((k) => {
    const b = k.getBoundingClientRect();
    return { id: k.id, cls: (k.className || '').toString().slice(0, 40), box: [Math.round(b.x), Math.round(b.y), Math.round(b.width), Math.round(b.height)], vis: getComputedStyle(k).display + '/' + getComputedStyle(k).visibility };
  }) : [];
  const at = (x, y) => { const e = document.elementFromPoint(x, y); return e ? `${e.tagName.toLowerCase()}#${e.id || ''}.${(e.className || '').toString().slice(0, 30)}` : null; };
  const sb = document.getElementById('settings-btn')?.getBoundingClientRect();
  return {
    overlay: ov ? { cls: ov.className, display: cs.display, visibility: cs.visibility, opacity: cs.opacity, pe: cs.pointerEvents, z: cs.zIndex, bg: cs.backgroundColor, rect: (() => { const b = ov.getBoundingClientRect(); return [Math.round(b.width), Math.round(b.height)]; })() } : null,
    kids,
    hitCenter: at(innerWidth / 2, innerHeight / 2),
    hitSettingsBtn: sb ? at(Math.round(sb.x + sb.width / 2), Math.round(sb.y + sb.height / 2)) : null,
    settingsBtnVisible: !!sb && sb.width > 0,
  };
};

for (const cfg of [
  { name: 'mobile-light', viewport: { width: 375, height: 812 }, isMobile: true, hasTouch: true, colorScheme: 'light' },
  { name: 'desktop-light', viewport: { width: 1440, height: 900 }, isMobile: false, hasTouch: false, colorScheme: 'light' },
]) {
  const ctx = await browser.newContext({ ...cfg, deviceScaleFactor: cfg.isMobile ? 2 : 1, locale: 'zh-CN' });
  const page = await ctx.newPage();
  await page.goto(`${BASE}?ticket=${encodeURIComponent(mint())}`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(9000);
  res[cfg.name] = { afterLoad: await page.evaluate(PROBE) };
  const dir = path.join(ROOT, '07-notify');
  fs.mkdirSync(dir, { recursive: true });
  await page.screenshot({ path: path.join(dir, `${cfg.name}__modal-overlay-probe.png`), animations: 'disabled' }).catch(() => {});
  // 点一次没反应的东西（画布切换），观察是否有层吃掉点击
  await page.evaluate(() => {
    const b = document.getElementById('canvas-toggle-btn');
    if (b) b.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  });
  await page.waitForTimeout(1200);
  res[cfg.name].afterCanvasToggle = await page.evaluate(PROBE);
  await ctx.close();
}

await browser.close();
fs.writeFileSync(path.join(DATA, 'modal-check.json'), JSON.stringify(res, null, 1));
console.log(JSON.stringify(res, null, 1));

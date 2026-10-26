// 设计样张 Playwright 实截（亮/暗双主题 + 关键交互态）
// 用法: node shots.mjs   （在工作分支 reports/<batch>/ 下执行；chromium 实截）
import { chromium } from 'playwright';
import { fileURLToPath } from 'url';
import path from 'path';

const dir = path.dirname(fileURLToPath(import.meta.url));
const base = 'file://' + dir + '/';
const browser = await chromium.launch();
const errors = [];

async function page(url, { w = 720, h = 1100, dark = false } = {}) {
  const ctx = await browser.newContext({ viewport: { width: w, height: h }, deviceScaleFactor: 2 });
  const p = await ctx.newPage();
  p.on('console', m => { if (m.type() === 'error') errors.push(`${url}: ${m.text()}`); });
  p.on('pageerror', e => errors.push(`${url}: ${e.message}`));
  await p.goto(base + url);
  if (dark) await p.evaluate(() => { document.documentElement.dataset.theme = 'dark'; });
  await p.waitForTimeout(450);
  return { ctx, p };
}

/* ── panel-perception.html ── */
{
  const { ctx, p } = await page('panel-perception.html');
  await p.screenshot({ path: 'shot-panel-entries-light.png', fullPage: true });
  await p.click('.demo-bar button[data-st="setup"]');
  await p.screenshot({ path: 'shot-panel-setup-light.png', fullPage: true });
  await p.click('.demo-bar button[data-st="err"]');
  await p.screenshot({ path: 'shot-panel-error-light.png', fullPage: true });
  await p.click('.demo-bar button[data-st="run"]');
  await p.click('#ev-run .actions .btn'); // 展开区「配置」按钮 → overlay
  await p.waitForTimeout(250);
  await p.screenshot({ path: 'shot-panel-config-light.png', fullPage: true });
  await p.evaluate(() => closeCfg());
  // 条目收起态：纯条目列表
  await p.click('#entry-mail-head');
  await p.waitForTimeout(250);
  await p.screenshot({ path: 'shot-panel-collapsed-light.png', fullPage: true });
  await ctx.close();

  const d = await page('panel-perception.html', { dark: true });
  await d.p.screenshot({ path: 'shot-panel-dark.png', fullPage: true });
  await d.ctx.close();
}

/* ── card-otp-ask.html ── */
{
  const { ctx, p } = await page('card-otp-ask.html', { w: 560, h: 940 });
  await p.screenshot({ path: 'shot-otp-detected-light.png', fullPage: true });
  await p.click('.demo-bar button[data-d="rest"]');
  await p.screenshot({ path: 'shot-otp-waiting-light.png', fullPage: true });
  await p.click('.demo-bar button[data-d="filled"]');
  await p.waitForTimeout(300);
  await p.screenshot({ path: 'shot-otp-filled-light.png', fullPage: true });
  await ctx.close();

  const d = await page('card-otp-ask.html', { w: 560, h: 940, dark: true });
  await d.p.screenshot({ path: 'shot-otp-dark.png', fullPage: true });
  await d.ctx.close();
}

/* ── card-send-confirm.html ── */
{
  const { ctx, p } = await page('card-send-confirm.html', { w: 1020, h: 1100 });
  await p.addStyleTag({ content: '.demo-bar{display:none!important}' }); // 防固定演示条遮挡画布脚注
  await p.screenshot({ path: 'shot-send-flow-light.png', fullPage: true });
  await p.locator('#canvas-panel').screenshot({ path: 'shot-send-canvas-light.png' });
  // 画布改动 → 快照停用联动（演示条已隐藏，直接调 markDirty）
  await p.evaluate(() => markDirty());
  await p.waitForTimeout(300);
  await p.screenshot({ path: 'shot-send-stale-light.png', fullPage: true });
  await ctx.close();

  const d = await page('card-send-confirm.html', { w: 1020, h: 1100, dark: true });
  await d.p.addStyleTag({ content: '.demo-bar{display:none!important}' });
  await d.p.locator('#canvas-panel').screenshot({ path: 'shot-send-canvas-dark.png' });
  await d.ctx.close();
}

await browser.close();
if (errors.length) { console.error('CONSOLE/PAGE ERRORS:\n' + errors.join('\n')); process.exit(1); }
console.log('OK: all screenshots rendered, zero console errors');

// capture2.mjs — gitviz 第二轮证据采集（dev-only；Playwright + 本机 Chromium）。
// 跑法：
//   node tools/gitviz-dev/server.mjs &                     # 127.0.0.1:8791
//   node tools/gitviz-dev/capture2.mjs
// 产出（GITVIZ_EVIDENCE_DIR，缺省主检出 .stand/tmp/<batch>/evidence）：
//   d1/d2-1440-{dark,light}-*.png   桌面档（泳道标签列 + 问答窗 + 交办回执）
//   .frames-gv6-*.png               手机 GIF 帧（角钮 → 开窗 → 问 → 交办回执 → 面板多选合并）
// 桌面 1440 / 手机 375 各亮暗两档；手机四角与泳道判据在 tests/gitviz-mobile-corners.spec.mjs。

import { chromium } from 'playwright';
import { mkdir, writeFile } from 'node:fs/promises';

const OUT = process.env.GITVIZ_EVIDENCE_DIR || '/Users/kaiyu/Claude code/Stand/.stand/tmp/gitviz-mvp-20261119/evidence';
const BASE = process.env.GITVIZ_BASE || 'http://127.0.0.1:8791';
await mkdir(OUT, { recursive: true });

const report = { at: new Date().toISOString(), base: BASE, steps: [] };
const browser = await chromium.launch();

/** 桌面档：泳道标签列 + 多选 + 问答窗 + 交办回执。 */
async function desktop(colorScheme, tag) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme, deviceScaleFactor: 2, locale: 'zh-CN', timezoneId: 'Asia/Shanghai' });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  await page.goto(`${BASE}/?project=stand`, { waitUntil: 'load' });
  await page.waitForSelector('.gitviz[data-state="ready"]', { timeout: 20000 });
  await page.waitForFunction(() => window.__gitvizBridgeReady === true, { timeout: 10000 });
  await page.waitForTimeout(500);

  // 桌面：tl/tr/bl 隐藏（返回条 + 右栏已覆盖），br = 问答 FAB 必须可见可点
  const deskCorners = await page.evaluate(() => {
    const vis = (s) => { const e = document.querySelector(s); return !!e && getComputedStyle(e).display !== 'none' && e.getClientRects().length > 0; };
    return { tl: vis('.gitviz-corner--tl'), tr: vis('.gitviz-corner--tr'), bl: vis('.gitviz-corner--bl'), br: vis('.gitviz-corner--br') };
  });
  const cornersHidden = deskCorners.tl === false && deskCorners.tr === false && deskCorners.bl === false;

  const laneTags = await page.evaluate(() => [...document.querySelectorAll('.gitviz-lane-tag')].map((t) => t.textContent.trim()));
  await page.screenshot({ path: `${OUT}/d1-1440-${tag}-lanes.png`, animations: 'disabled' });

  // 问答窗（桌面同样可开：角钮在桌面隐藏，走面板右栏？—— 桌面入口 = 右下角 FAB 同款，
  // 但桌面隐藏四角 ⇒ 这里直接驱动 ask 面以取证；产品桌面入口后续批接入）
  // 桌面入口 = 保留下来的右下 FAB（不再是「强制 display」的取证后门）
  await page.click('.gitviz-corner--br');
  await page.waitForSelector('.gitviz-ask-dock:not([hidden])', { timeout: 5000 });
  const askOpen = true;
  if (askOpen) {
    await page.fill('.gitviz-ask-input textarea', '把 feat/a、feat/b 合进 feat/base');
    await page.click('.gitviz-ask-send');
    await page.waitForFunction(() => (window.__gitvizDevBridge?.handoffs || []).length > 0, { timeout: 15000 });
    await page.waitForTimeout(400);
  }
  await page.screenshot({ path: `${OUT}/d2-1440-${tag}-ask-handoff.png`, animations: 'disabled' });
  const handoff = await page.evaluate(() => (window.__gitvizDevBridge?.handoffs || []).map((h) => h.frame));
  report.steps.push({ tag, laneTags, deskCorners, cornersHidden, handoff });
  await ctx.close();
  return { laneTags, deskCorners, cornersHidden, handoff, errors };
}

const dark = await desktop('dark', 'dark');
const light = await desktop('light', 'light');

// ══ 手机 GIF 帧（375）：角钮 → 问 → 交办回执 → 面板多选合并 ════════════════════
const mctx = await browser.newContext({
  viewport: { width: 375, height: 812 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, colorScheme: 'dark',
  locale: 'zh-CN', timezoneId: 'Asia/Shanghai',
  userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1',
});
const mp = await mctx.newPage();
await mp.goto(`${BASE}/?mobile=1&project=stand`, { waitUntil: 'load' });
await mp.waitForSelector('.gitviz[data-state="ready"]', { timeout: 20000 });
await mp.waitForFunction(() => window.__gitvizBridgeReady === true, { timeout: 10000 });
await mp.waitForTimeout(500);

const frames = [];
const grab = async () => { frames.push(await mp.screenshot({ animations: 'disabled' })); };
await grab();
// ① 右下角 FAB → 开窗
await mp.click('.gitviz-corner--br');
await mp.waitForSelector('.gitviz-ask-dock:not([hidden])');
await mp.waitForTimeout(320);
await grab();
// ② 输入并发送
await mp.fill('.gitviz-ask-input textarea', '把 feat/a、feat/b 合进 feat/base');
await mp.waitForTimeout(220);
await grab();
await mp.click('.gitviz-ask-send');
await mp.waitForFunction(() => document.querySelectorAll('.gitviz-ask-bub.is-ai').length > 0, { timeout: 15000 });
await mp.waitForTimeout(420);
await grab();
await mp.waitForFunction(() => (window.__gitvizDevBridge?.handoffs || []).length > 0, { timeout: 15000 });
await mp.waitForTimeout(520);
await grab();
// ③ 关窗 → 面板多选 → 合并（同一出口）
await mp.evaluate(() => document.querySelector('.gitviz-ask-dock .gitviz-corner--inline')?.click());
await mp.waitForTimeout(260);
await mp.evaluate(() => {
  // 跳过 HEAD 行（首行是 feat/base 本身，选它 = 自己合自己）
  const rows = [...document.querySelectorAll('.gitviz-ref-row')];
  const pick = rows.filter((r) => !r.querySelector('.gitviz-ref-chip.is-head') && r.querySelector('input[type=checkbox]')).slice(0, 2);
  pick.forEach((r) => r.querySelector('input[type=checkbox]').click());
});
await mp.waitForTimeout(360);
await grab();
await mp.evaluate(() => { window.__gitvizDevBridge.handoffs.length = 0; });
await mp.evaluate(() => document.querySelector('.gitviz-forward .gitviz-btn')?.click());
await mp.waitForTimeout(520);
await grab();
const mergeFrame = await mp.evaluate(() => (window.__gitvizDevBridge?.handoffs || []).map((h) => h.frame));
report.mobileGif = { frames: frames.length, mergeFrame };
await mctx.close();

for (let i = 0; i < frames.length; i += 1) {
  await writeFile(`${OUT}/.frames-gv6-${String(i).padStart(3, '0')}.png`, frames[i]);
}
await browser.close();
report.desktop = { dark: { laneTags: dark.laneTags, deskCorners: dark.deskCorners, handoffCount: dark.handoff.length }, light: { laneTags: light.laneTags, deskCorners: light.deskCorners, handoffCount: light.handoff.length } };
report.errors = [...dark.errors, ...light.errors];
await writeFile(`${OUT}/capture2-report.json`, JSON.stringify(report, null, 2));
console.log(JSON.stringify({ ok: true, desktop: report.desktop, mobileGif: report.mobileGif, errors: report.errors }, null, 2));

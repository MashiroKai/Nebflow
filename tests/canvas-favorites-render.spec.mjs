#!/usr/bin/env node
// canvas-favorites-render.spec.mjs — 文档收藏渲染级闸（真 Chromium，三端）。
//
// 作者令（2026-11-23 补齐验收面）：三端渲染存在性、收藏/取消收藏 DOM 状态翻转、
// 失效条目置灰 + 点击不开 canvas、重复收藏置顶保持 savedAt；失效判定只认 404/410
// （403/500 等不确定状态不得置灰）。服务端依据：NfFilePolicy.scala not-found = 404，
// 全仓不产 410（见 .batch-reports 终报）。
//
// 装置：scripts/lib/visual-harness.mjs（同 mobile-overlap-sweep）。/api/nf-ticket 与
// /api/nf-file 由本文件的 page.route 装桩（后注册 = 先匹配，不 fallback）。
// 截图落 <primary>/.stand/tmp/canvas-favorites/evidence/screenshots/（工作区外，符合产物规则）。
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { ROOT, loadPlaywright, launchBrowser, createSession, settle } from '../scripts/lib/visual-harness.mjs';

// 主检出根：worktree 内 ROOT = <primary>/.stand/worktrees/<wt>，主检出内 ROOT 即 primary。
const PRIMARY = ROOT.includes('/.stand/worktrees/') ? ROOT.split('/.stand/worktrees/')[0] : ROOT;
const SHOT_DIR = process.env.FAV_SHOT_DIR || join(PRIMARY, '.stand', 'tmp', 'canvas-favorites', 'evidence', 'screenshots');
// 手机断言一律限定在工作台收藏块内：桌面侧栏的收藏区同在 DOM（隐藏），不限定会重复命中。
const HUB = '#canvas-fav-page';   // r2：收藏页（overlay）即条目容器；入口 = 工作台「收藏」栏
const BOOT = { config: { llm: { providers: {} }, features: {} }, sessionId: 'fav-root', messages: [] };

/** 服务端状态装桩：missing→404（真实判据），forbidden→403，flaky→500（均不得置灰）。 */
const FILE_STATUS = { '/w/gone.md': 404, '/w/forbidden.md': 403, '/w/flaky.md': 500 };

async function wireFileStubs(page) {
  await page.route('**/api/nf-ticket', (route) => {
    const body = JSON.parse(route.request().postData() || '{}');
    const tickets = {};
    for (const p of body.paths || []) tickets[p] = { t: `tok${p.length}`, exp: Date.now() + 1800000, size: 1 };
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ tickets }) });
  });
  await page.route(/\/api\/nf-file/, (route) => {
    const p = new URL(route.request().url()).searchParams.get('path');
    const status = FILE_STATUS[p] ?? 200;
    if (status !== 200) return route.fulfill({ status, contentType: 'application/json', body: '{"error":"x"}' });
    return route.fulfill({ status: 200, contentType: 'text/markdown', body: '# doc' });
  });
}

const results = [];
const textOf = (page, sel) => page.evaluate((q) => document.querySelector(q)?.textContent || '', sel);
/** 点击条目；被遮挡时记录命中元素（elementFromPoint），把遮挡源直接写进失败明细。 */
async function clickRow(page, path, label, { force = false } = {}) {
  const sel = `${HUB} .cfp-row[data-fav-path="${path}"]`;
  try {
    // aria-disabled 行对 Playwright 视为不可操作：失效行用 force 投递点击（命中测已由 elementFromPoint 复核）。
    await page.click(sel, { timeout: 8000, force });
    check(label, true);
  } catch (e) {
    const hit = await page.evaluate((q) => {
      const el = document.querySelector(q);
      if (!el) return 'row absent';
      const b = el.getBoundingClientRect();
      const top = document.elementFromPoint(b.x + b.width / 2, b.y + b.height / 2);
      return `rect=${[b.x, b.y, b.width, b.height].map((v) => Math.round(v)).join(',')} top=${top ? `${top.tagName}.${String(top.className).slice(0, 80)}` : 'null'}`;
    }, sel);
    check(label, false, `${String(e.message).split('\n')[0]} | ${hit}`);
  }
}

const check = (name, ok, detail = '') => {
  results.push({ name, ok: !!ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${ok ? '' : ` — ${detail}`}`);
};

/** 截图 = 证据面：元素不可见时记录几何（不静默），截图失败记为 FAIL。 */
async function shot(page, selector, file, label) {
  try {
    await page.locator(selector).first().screenshot({ path: join(SHOT_DIR, file), timeout: 8000 });
    check(`screenshot: ${label}`, true);
  } catch (e) {
    const geo = await page.evaluate((sel) => {
      const el = document.querySelector(sel);
      if (!el) return 'absent';
      const b = el.getBoundingClientRect();
      return `display=${getComputedStyle(el).display} vis=${getComputedStyle(el).visibility} rect=${[b.x, b.y, b.width, b.height].map((v) => Math.round(v)).join(',')}`;
    }, selector);
    check(`screenshot: ${label}`, false, `${String(e.message).split('\n')[0]} | ${geo}`);
  }
}

async function desktopCase(browser, failures) {
  const s = await createSession(browser, { width: 1280, height: 800, colorScheme: 'light' });
  s.page.on('pageerror', (e) => failures.push(`[desktop] pageerror ${e.message}`));
  await wireFileStubs(s.page);
  await s.boot(BOOT);
  await s.page.evaluate(() => window.dispatchEvent(new CustomEvent('workspace-open-item', {
    detail: { id: 'file:/w/a.md', itemType: 'markdown', title: 'a.md', content: '# A\n\nbody', pinned: true, absPath: '/w/a.md' },
  })));
  await s.page.waitForSelector('#canvas-fav-btn', { state: 'attached', timeout: 15000 });
  await settle(s.page, 300);

  const btn = s.page.locator('#canvas-fav-btn');
  check('desktop: 收藏钮可见', await btn.isVisible());
  const geo = await s.page.evaluate(() => {
    const b = document.getElementById('canvas-fav-btn').getBoundingClientRect();
    const t = document.getElementById('canvas-title').getBoundingClientRect();
    return { btnRight: b.right, titleLeft: t.left };
  });
  check('desktop: 收藏钮位于胶囊左侧', geo.btnRight <= geo.titleLeft + 1, JSON.stringify(geo));
  const measureEnds = () => s.page.evaluate(() => {
    const r = q => document.querySelector(q).getBoundingClientRect();
    const h = r('#canvas-panel .canvas-header'), a = r('#canvas-fav-btn'), b = r('#canvas-pop-btn');
    const ai = r('#canvas-fav-btn svg'), bi = r('#canvas-pop-btn .pop-ic-pop');
    const delta = (x, y) => Math.abs(x - y);
    const center = (x) => [x.left + x.width / 2, x.top + x.height / 2];
    const [ax, ay] = center(a), [bx, by] = center(b), [aix, aiy] = center(ai), [bix, biy] = center(bi);
    return { inset: delta(a.left - h.left, h.right - b.right), vertical: Math.max(delta(ay, h.top + h.height / 2), delta(by, h.top + h.height / 2)), optical: Math.max(delta(ax, aix), delta(ay, aiy), delta(bx, bix), delta(by, biy)), size: delta(ai.width, bi.width), radii: delta(parseFloat(getComputedStyle(document.querySelector('#canvas-fav-btn')).borderRadius), parseFloat(getComputedStyle(document.querySelector('#canvas-pop-btn')).borderRadius)) };
  });
  const valid = g => Object.values(g).every(v => v <= 1);
  const endGeometry = await measureEnds();
  check('desktop: capsule end optical geometry', valid(endGeometry), JSON.stringify(endGeometry));
  await s.page.locator('#canvas-fav-btn svg').evaluate(el => { el.style.transform = 'translateX(4px)'; });
  check('desktop: optical geometry negative control', !valid(await measureEnds()));
  await s.page.locator('#canvas-fav-btn svg').evaluate(el => { el.style.transform = ''; });
  const navGeometry = await s.page.evaluate(() => {
    const a = document.querySelector('#social-btn').getBoundingClientRect(), b = document.querySelector('#favorites-btn').getBoundingClientRect();
    const icon = document.querySelector('#favorites-btn svg').getBoundingClientRect();
    return { sameColumn: Math.abs(a.left - b.left) < 1 && Math.abs(a.width - b.width) < 1, adjacent: b.top >= a.bottom && b.top - a.bottom <= 12, iconFits: icon.left >= b.left && icon.right <= b.right && icon.top >= b.top && icon.bottom <= b.bottom, label: !!document.querySelector('#favorites-btn').getAttribute('aria-label') };
  });
  check('desktop: favorites below phone access, aligned and unclipped', Object.values(navGeometry).every(Boolean), JSON.stringify(navGeometry));
  await s.page.click('#favorites-btn');
  await s.page.waitForSelector('#panel-favorites.active #canvas-fav-sidebar-page', { state: 'visible' });
  check('desktop: favorites view within sidebar', await s.page.locator('#sidebar-panel #panel-favorites.active .cfp-sidebar').isVisible());
  await shot(s.page, '#activity-bar', 'desktop-favorites-entry.png', 'desktop favorites entry');
  await shot(s.page, '#panel-favorites', 'desktop-favorites-panel.png', 'desktop favorites sidebar panel');
  await s.page.click('#files-btn');
  check('desktop: 初始未收藏态（描边）', (await btn.getAttribute('class')).indexOf('is-on') < 0);
  await shot(s.page, '#canvas-panel .canvas-header', 'desktop-capsule-off.png', 'desktop capsule off');

  await btn.click();
  await settle(s.page, 150);
  check('desktop: 点击后翻为已收藏（实心）', (await btn.getAttribute('class')).indexOf('is-on') >= 0);
  check('desktop: aria-pressed=true', (await btn.getAttribute('aria-pressed')) === 'true');
  await shot(s.page, '#canvas-panel .canvas-header', 'desktop-capsule-on.png', 'desktop capsule on');

  const rows = s.page.locator('#canvas-fav-section .canvas-fav-row');
  check('desktop: 侧边栏收藏区出现条目', (await rows.count()) === 1, `count=${await rows.count()}`);
  check('desktop: 条目标题为快照 a.md', ((await textOf(s.page, '#canvas-fav-section .canvas-fav-row')) || '').includes('a.md'));
  await shot(s.page, '#canvas-fav-section', 'sidebar-with-entry.png', 'sidebar with entry');

  // 去重（重复收藏置顶、保留 savedAt）：经存储注入重复条目，渲染面须归一化成一条。
  await s.page.evaluate(() => {
    const k = Object.keys(localStorage).find((x) => x.endsWith('canvas_favorites_v1'));
    localStorage.setItem(k, JSON.stringify([
      { path: '/w/a.md', title: 'A', savedAt: 111 },
      { path: '/w/b.md', title: 'B', savedAt: 222 },
      { path: '/w/a.md', title: 'A-dup', savedAt: 999 },
    ]));
    window.dispatchEvent(new Event('nebflow-favorites-change'));
  });
  await settle(s.page, 150);
  const dedup = await s.page.evaluate(async () => (await import('/js/canvasFavorites.js')).loadFavorites());
  check('dedupe: 重复 path 归一为一条', dedup.length === 2, JSON.stringify(dedup));
  check('dedupe: 保留首次（置顶）且 savedAt 不被改写', dedup[0].path === '/w/a.md' && dedup[0].savedAt === 111, JSON.stringify(dedup[0]));
  check('dedupe: 侧边栏行数同步为 2', (await rows.count()) === 2);

  // 取消收藏（DOM 翻转）。
  await btn.click();
  await settle(s.page, 150);
  check('desktop: 再点取消收藏（描边）', (await btn.getAttribute('class')).indexOf('is-on') < 0);
  const stillA = await s.page.evaluate(async () => (await import('/js/canvasFavorites.js')).isFavoritePath('/w/a.md'));
  check('desktop: 取消后存储中移除 a.md', !stillA);

  // 空态：移空存储，侧边栏显示空态文案。
  await s.page.evaluate(() => {
    const k = Object.keys(localStorage).find((x) => x.endsWith('canvas_favorites_v1'));
    localStorage.setItem(k, '[]');
    window.dispatchEvent(new Event('nebflow-favorites-change'));
  });
  await settle(s.page, 150);
  const emptyText = await textOf(s.page, '#canvas-fav-section');
  check('sidebar: 空态文案', emptyText.includes('还没有收藏'), emptyText.slice(0, 60));
  await shot(s.page, '#canvas-fav-section', 'sidebar-empty.png', 'sidebar empty');
  await s.close();
}

async function mobileCase(browser, failures) {
  const s = await createSession(browser, { width: 390, height: 844, colorScheme: 'light', clock: 0 });
  s.page.on('pageerror', (e) => failures.push(`[mobile] pageerror ${e.message}`));
  await wireFileStubs(s.page);
  await s.boot(BOOT);
  await s.page.evaluate(async () => {
    const m = await import('/js/canvasFavorites.js');
    m.toggleFavoritePath({ path: '/w/a.md', title: 'a.md' });
    m.toggleFavoritePath({ path: '/w/gone.md', title: '已删文档' });
    m.toggleFavoritePath({ path: '/w/forbidden.md', title: '无权文档' });
    m.toggleFavoritePath({ path: '/w/flaky.md', title: '抖动文档' });
    (await import('/js/mobile/navController.js')).dockTo('workbench');
  });
  await s.page.waitForSelector('.mobile-page[data-page="workbench"].live .mf-hub-row[data-hub-page="favorites"]', { timeout: 15000 });
  await s.page.click('.mobile-page[data-page="workbench"].live .mf-hub-row[data-hub-page="favorites"]');
  await s.page.waitForSelector('#canvas-fav-page.is-open .cfp-row', { timeout: 15000 });
  // 失效判定是异步的（ticket 铸造 + 1 字节 Range 请求）：给足窗口，超时则记为失败而非静默。
  const invalidWait = await s.page.waitForSelector(HUB + ' .cfp-row[data-fav-path="/w/gone.md"].is-invalid', { timeout: 20000 }).then(() => true, () => false);
  check('mobile: 404 条目在限期内完成失效判定', invalidWait);
  await settle(s.page, 300);

  const gone = s.page.locator(HUB + ' .cfp-row[data-fav-path="/w/gone.md"]');
  check('mobile: 404 条目置灰（is-invalid + aria-disabled）', (await gone.getAttribute('aria-disabled')) === 'true');
  check('mobile: 失效标签文案', ((await textOf(s.page, HUB + ' .cfp-row[data-fav-path="/w/gone.md"]')) || '').includes('已失效'));
  check('mobile: 有效条目不置灰', (await s.page.locator(HUB + ' .cfp-row[data-fav-path="/w/a.md"]').getAttribute('class')).indexOf('is-invalid') < 0);
  check('mobile: 403 不置灰（非 404/410 不判失效）', (await s.page.locator(HUB + ' .cfp-row[data-fav-path="/w/forbidden.md"]').getAttribute('class')).indexOf('is-invalid') < 0);
  check('mobile: 500 不置灰（非 404/410 不判失效）', (await s.page.locator(HUB + ' .cfp-row[data-fav-path="/w/flaky.md"]').getAttribute('class')).indexOf('is-invalid') < 0);
  for (const [name, width, height] of [['mobile', 390, 844], ['tablet', 768, 1024], ['desktop', 1280, 800]]) {
    await s.page.setViewportSize({ width, height });
    const back = s.page.locator('#canvas-fav-page .cfp-back');
    const box = await back.boundingBox();
    const style = await back.evaluate(el => ({ font: getComputedStyle(el).fontSize, color: getComputedStyle(el).color, parentColor: getComputedStyle(el.parentElement).color }));
    check(`${name}: back geometry and color`, !!box && box.width === 36 && box.height === 36 && style.font === '30px' && style.color === style.parentColor, JSON.stringify({ box, style }));
    await shot(s.page, '#canvas-fav-page', `back-${name}.png`, `${name} favorites page`);
  }
  await s.page.setViewportSize({ width: 390, height: 844 });

  // 点失效条目：toast 可读提示，不开 canvas。
  await s.page.evaluate(() => {
    window.__favToasts = [];
    const orig = window.__showToast;
    window.__showToast = (msg, type, opts) => { window.__favToasts.push(String(msg)); return orig?.(msg, type, opts); };
  });
  await clickRow(s.page, '/w/gone.md', 'mobile: 失效条目可点击（未被遮挡）', { force: true });
  await settle(s.page, 400);
  const toasts = await s.page.evaluate(() => window.__favToasts || []);
  check('mobile: 点失效条目弹可读提示', toasts.some((m) => m.includes('已删文档')), JSON.stringify(toasts));
  check('mobile: 点失效条目不切到 canvas 页', (await s.page.locator('.mobile-page[data-page="canvas"].live').count()) === 0);
  await s.page.screenshot({ path: join(SHOT_DIR, 'mobile-invalid-click-toast.png') });

  // 点有效条目：打开并切到 canvas 页。
  await clickRow(s.page, '/w/a.md', 'mobile: 有效条目可点击（未被遮挡）');
  const closed = await s.page.waitForSelector('#canvas-fav-page', { state: 'detached', timeout: 15000 }).then(() => true, () => false);
  check('mobile: 点有效条目后收藏页关闭', closed);
  const opened = await s.page.waitForSelector('.mobile-page[data-page="canvas"].live', { timeout: 15000 }).then(() => true, () => false);
  check('mobile: 点有效条目切到 canvas 页', opened);
  await s.close();
}

async function main() {
  mkdirSync(SHOT_DIR, { recursive: true });
  const pwRes = loadPlaywright();
  const pw = pwRes.mod.playwright || pwRes.mod;
  const { browser, label } = await launchBrowser(pw);
  console.log(`[canvas-favorites-render] browser=${label} shots=${SHOT_DIR}`);
  const failures = [];
  try {
    await desktopCase(browser, failures);
    await mobileCase(browser, failures);
  } finally {
    await browser.close();
  }
  for (const r of results) console.log(`${r.ok ? 'PASS' : 'FAIL'} ${r.name}${r.ok ? '' : ` — ${r.detail}`}`);
  const bad = results.filter((r) => !r.ok).length + failures.length;
  for (const f of failures) console.error(`FAIL ${f}`);
  if (bad) { console.error(`canvas-favorites-render: ${bad} failure(s)`); process.exit(1); }
  console.log(`canvas-favorites-render: green (${results.length} checks)`);
}

main().catch((e) => { console.error(`canvas-favorites-render: ${e && e.message ? e.message.split('\n')[0] : e}`); process.exit(e && /playwright|browser|Executable/i.test(String(e.message)) ? 2 : 1); });

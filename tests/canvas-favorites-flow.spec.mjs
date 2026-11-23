#!/usr/bin/env node
// canvas-favorites-flow.spec.mjs — 收藏页验收流（真 Chromium，双端；2026-11-23 收藏 r2）。
//
// 覆盖作者验收四条：
//  ① 入口 → 收藏页 → 分类 tab 切换（笔记 / 文件 / 图片与视频）→ 搜索过滤；
//  ② 星标 round-trip：文档顶胶囊点亮 → 收藏页出现该条 → 取消 → 条目消失；
//  ③ 双端截图（≥10 张，含星标点亮态特写、收藏页双端、tab 切换态）；
//  ④ 结构断言（tab 仅显示实际存在的类型、链接 tab 隐藏）。
// 装置同 canvas-favorites-render.spec.mjs：nf-ticket / nf-file 由本文件 page.route 装桩。
// 截图落 <primary>/.stand/tmp/canvas-favorites-r2/evidence/screenshots/（工作区外）。
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { ROOT, loadPlaywright, launchBrowser, createSession, settle } from '../scripts/lib/visual-harness.mjs';

const PRIMARY = ROOT.includes('/.stand/worktrees/') ? ROOT.split('/.stand/worktrees/')[0] : ROOT;
const SHOT_DIR = process.env.FAV_FLOW_SHOT_DIR || join(PRIMARY, '.stand', 'tmp', 'canvas-favorites-r2', 'evidence', 'screenshots');
const BOOT = { config: { llm: { providers: {} }, features: {} }, sessionId: 'fav-flow', messages: [] };
const PAGE = '#canvas-fav-page';
// 1x1 透明 PNG：图片缩略图桩（真字节，避免破图干扰截图）。
const PNG_1PX = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=', 'base64');

const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok: !!ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${ok ? '' : ` — ${detail}`}`);
};

async function wireStubs(page) {
  await page.route('**/api/nf-ticket', (route) => {
    const body = JSON.parse(route.request().postData() || '{}');
    const tickets = {};
    for (const p of body.paths || []) tickets[p] = { t: `tok${p.length}`, exp: Date.now() + 1800000, size: 1 };
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ tickets }) });
  });
  await page.route(/\/api\/nf-file/, (route) => {
    const p = new URL(route.request().url()).searchParams.get('path') || '';
    if (/\.png$/i.test(p)) return route.fulfill({ status: 200, contentType: 'image/png', body: PNG_1PX });
    return route.fulfill({ status: 200, contentType: 'text/markdown', body: '# doc' });
  });
}

const SEED = [
  { path: '/w/notes/a.md', title: 'a.md 笔记', savedAt: Date.now() - 60000 },
  { path: '/w/docs/t.pdf', title: '论文终稿', savedAt: Date.now() - 3600000 },
  { path: '/w/docs/doc.docx', title: '项目申请书', savedAt: Date.now() - 86400000 * 3 },
  { path: '/w/img/pic.png', title: '路演截图', savedAt: Date.now() - 86400000 * 9 },
];

async function seed(page) {
  await page.evaluate(async (list) => {
    const m = await import('/js/canvasFavorites.js');
    for (const e of list) m.toggleFavoritePath({ path: e.path, title: e.title });
  }, SEED);
}

const rowPaths = (page) => page.$$eval(`${PAGE} .cfp-row`, (els) => els.map((e) => e.dataset.favPath));
const tabIds = (page) => page.$$eval(`${PAGE} .cfp-tab`, (els) => els.map((e) => e.dataset.tab));
const tabOn = (page, id) => page.$eval(`${PAGE} .cfp-tab[data-tab="${id}"]`, (e) => e.classList.contains('is-on'));
async function shot(page, file, label, selector = null) {
  try {
    const target = selector ? page.locator(selector).first() : page;
    await (selector ? target.screenshot({ path: join(SHOT_DIR, file), timeout: 8000 }) : page.screenshot({ path: join(SHOT_DIR, file), timeout: 8000 }));
    check(`screenshot: ${label}`, true);
  } catch (e) {
    check(`screenshot: ${label}`, false, String(e.message).split('\n')[0]);
  }
}

/** 星标 round-trip：文档顶胶囊点亮 → 收藏页出现 → 取消 → 消失。 */
async function starRoundTrip(page, tag, openPage) {
  const doc = { id: `file:/w/z-${tag}.md`, itemType: 'markdown', title: `z-${tag}.md`, content: '# Z\n\nbody', pinned: true, absPath: `/w/z-${tag}.md` };
  await page.evaluate((d) => window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: d })), doc);
  // 手机端：文档页顶胶囊只在 canvas dock 页可见 ⇒ 先切到 canvas 页再找星标。
  if (tag === 'mobile') await page.evaluate(async () => (await import('/js/mobile/navController.js')).dockTo('canvas'));
  const starSel = tag === 'mobile' ? '.mf-cv-star' : '#canvas-fav-btn';
  const headSel = tag === 'mobile' ? '.mf-cv-toolbar' : '#canvas-panel .canvas-header';
  await page.waitForSelector(starSel, { state: 'attached', timeout: 15000 });
  await page.waitForFunction((q) => { const b = document.querySelector(q); return !!b && !b.hidden && b.getBoundingClientRect().width > 0; }, starSel, { timeout: 15000 });
  await settle(page, 300);
  const star = page.locator(starSel);
  const offClass = await star.getAttribute('class');
  check(`${tag}: 星标初始描边态`, offClass.indexOf('is-on') < 0);
  await star.click({ timeout: 8000 }).catch(async (e) => {
    const diag = await page.evaluate(() => {
      const el = document.querySelector(starSel);
      if (!el) return 'btn absent';
      const b = el.getBoundingClientRect();
      const top = document.elementFromPoint(b.x + b.width / 2, b.y + b.height / 2);
      return `rect=${[b.x, b.y, b.width, b.height].map(Math.round).join(',')} hidden=${el.hidden} top=${top ? top.tagName + '#' + top.id + '.' + String(top.className).slice(0, 60) : 'null'} vw=${innerWidth}`;
    });
    check(`${tag}: 星标可点击`, false, `${String(e.message).split('\n')[0]} | ${diag}`);
  });
  await settle(page, 200);
  check(`${tag}: 点亮后 is-on + aria-pressed`, (await star.getAttribute('class')).indexOf('is-on') >= 0 && (await star.getAttribute('aria-pressed')) === 'true');
  const toast = await page.waitForSelector('.nebflow-toast-success', { timeout: 3000 }).then(() => true, () => false);
  check(`${tag}: 点亮弹出成功 toast`, toast);
  // 胶囊/工具条截图按包围盒裁切（工具条为悬浮带，locator 截图会因稳定性等待超时）。
  try {
    const box = await page.locator(headSel).first().boundingBox();
    if (!box) throw new Error('no bounding box');
    await page.screenshot({ path: join(SHOT_DIR, `${tag}-star-on-capsule.png`), clip: { x: Math.max(0, box.x - 8), y: Math.max(0, box.y - 8), width: box.width + 16, height: box.height + 16 }, timeout: 8000 });
    check(`screenshot: ${tag} star lit (capsule)`, true);
  } catch (e) {
    check(`screenshot: ${tag} star lit (capsule)`, false, String(e.message).split('\n')[0]);
  }
  await shot(page, `${tag}-star-on-close.png`, `${tag} star lit close-up`, starSel);
  await openPage();
  const present = (await rowPaths(page)).includes(`/w/z-${tag}.md`);
  check(`${tag}: 收藏页出现该条`, present);
  await closePage(page);
  if (tag === 'mobile') await page.evaluate(async () => (await import('/js/mobile/navController.js')).dockTo('canvas'));
  await page.waitForFunction((q) => { const b = document.querySelector(q); return !!b && !b.hidden && b.getBoundingClientRect().width > 0; }, starSel, { timeout: 15000 });
  await star.click({ timeout: 8000 });
  await settle(page, 200);
  check(`${tag}: 取消后描边态`, (await star.getAttribute('class')).indexOf('is-on') < 0);
  await openPage();
  const gone = !(await rowPaths(page)).includes(`/w/z-${tag}.md`);
  check(`${tag}: 取消后条目消失`, gone);
  await closePage(page);
}

async function closePage(page) {
  await page.keyboard.press('Escape');
  await page.waitForSelector(PAGE, { state: 'detached', timeout: 5000 }).catch(() => {});
}

async function desktopCase(browser, failures) {
  const s = await createSession(browser, { width: 1200, height: 800, colorScheme: 'light' });
  const page = s.page;
  page.on('pageerror', (e) => failures.push(`[desktop] pageerror ${e.message}`));
  await wireStubs(page);
  await s.boot(BOOT);
  await seed(page);
  const openPage = async () => {
    await page.click('#canvas-fav-section .canvas-fav-head');
    await page.waitForSelector(`${PAGE}.is-open .cfp-row`, { timeout: 15000 });
    await settle(page, 200);
  };
  await openPage();
  check('desktop: 收藏页打开', (await page.locator(PAGE).count()) === 1);
  check('desktop: 全部 4 条', (await rowPaths(page)).length === 4, JSON.stringify(await rowPaths(page)));
  const tabs = await tabIds(page);
  check('desktop: tab 仅显示实际存在类型（笔记/文件/图片）', JSON.stringify(tabs) === JSON.stringify(['note', 'media', 'file']), JSON.stringify(tabs));
  check('desktop: 无链接 tab（无数据）', !tabs.includes('link'));
  await shot(page, 'desktop-favorites-all.png', 'desktop favorites page (all)', PAGE);

  await page.click(`${PAGE} .cfp-tab[data-tab="note"]`);
  await settle(page, 150);
  check('desktop: 笔记 tab 过滤为 1 条', (await rowPaths(page)).join() === '/w/notes/a.md', JSON.stringify(await rowPaths(page)));
  check('desktop: 笔记 tab 选中态', await tabOn(page, 'note'));
  await shot(page, 'desktop-tab-note.png', 'desktop tab: note', PAGE);

  await page.click(`${PAGE} .cfp-tab[data-tab="file"]`);
  await settle(page, 150);
  const fileRows = (await rowPaths(page)).sort().join();
  check('desktop: 文件 tab 含 pdf + docx', fileRows === '/w/docs/doc.docx,/w/docs/t.pdf', fileRows);
  await shot(page, 'desktop-tab-file.png', 'desktop tab: file (pdf+docx)', PAGE);

  await page.click(`${PAGE} .cfp-tab[data-tab="media"]`);
  await settle(page, 150);
  check('desktop: 图片 tab 过滤为 1 条', (await rowPaths(page)).join() === '/w/img/pic.png');
  await shot(page, 'desktop-tab-media.png', 'desktop tab: media', PAGE);
  await page.click(`${PAGE} .cfp-tab[data-tab="media"]`);
  await settle(page, 150);
  check('desktop: 再点取消 tab → 全部', (await rowPaths(page)).length === 4);

  // 搜索匹配标题或路径：'doc' 同时命中 docs/ 目录下的两条（路径口径，预期内）。
  await page.fill(`${PAGE} .cfp-search-input`, 'doc');
  await settle(page, 150);
  check('desktop: 搜索 "doc" 按路径命中 docs/ 下两条', (await rowPaths(page)).length === 2, JSON.stringify(await rowPaths(page)));
  await page.fill(`${PAGE} .cfp-search-input`, 'doc.docx');
  await settle(page, 150);
  check('desktop: 搜索 "doc.docx" 精确过滤为 1 条', (await rowPaths(page)).join() === '/w/docs/doc.docx', JSON.stringify(await rowPaths(page)));
  await shot(page, 'desktop-search.png', 'desktop search filter', PAGE);
  await page.fill(`${PAGE} .cfp-search-input`, '');
  await settle(page, 150);
  check('desktop: 清空搜索恢复全部', (await rowPaths(page)).length === 4);

  // 点条目 → canvas 打开，收藏页关闭（桌面不切页）。
  await page.fill(`${PAGE} .cfp-search-input`, '论文');
  await settle(page, 150);
  await page.click(`${PAGE} .cfp-row[data-fav-path="/w/docs/t.pdf"]`);
  const closed = await page.waitForSelector(PAGE, { state: 'detached', timeout: 15000 }).then(() => true, () => false);
  check('desktop: 点 PDF 条目后收藏页关闭', closed);
  await settle(page, 300);

  await starRoundTrip(page, 'desktop', openPage);
  await openPage();
  await shot(page, 'desktop-favorites-after-star.png', 'desktop favorites page after star round-trip', PAGE);
  await closePage(page);
  await s.close();
}

async function mobileCase(browser, failures) {
  const s = await createSession(browser, { width: 390, height: 844, colorScheme: 'light', clock: 0 });
  const page = s.page;
  page.on('pageerror', (e) => failures.push(`[mobile] pageerror ${e.message}`));
  await wireStubs(page);
  await s.boot(BOOT);
  await seed(page);
  await page.evaluate(async () => (await import('/js/mobile/navController.js')).dockTo('workbench'));
  const openPage = async () => {
    await page.click('.mobile-page[data-page="workbench"].live .mf-hub-row[data-hub-page="favorites"]');
    await page.waitForSelector(`${PAGE}.is-open .cfp-row`, { timeout: 15000 });
    await settle(page, 200);
  };
  await openPage();
  check('mobile: 工作台收藏栏打开收藏页', (await page.locator(PAGE).count()) === 1);
  check('mobile: 全部 4 条', (await rowPaths(page)).length === 4);
  await shot(page, 'mobile-favorites-all.png', 'mobile favorites page (all)', PAGE);

  await page.click(`${PAGE} .cfp-tab[data-tab="note"]`);
  await settle(page, 150);
  check('mobile: 笔记 tab 过滤', (await rowPaths(page)).join() === '/w/notes/a.md');
  await shot(page, 'mobile-tab-note.png', 'mobile tab: note', PAGE);
  await page.click(`${PAGE} .cfp-tab[data-tab="note"]`);
  await page.click(`${PAGE} .cfp-tab[data-tab="file"]`);
  await settle(page, 150);
  check('mobile: 文件 tab 含 2 条', (await rowPaths(page)).length === 2);
  await shot(page, 'mobile-tab-file.png', 'mobile tab: file', PAGE);
  await page.click(`${PAGE} .cfp-tab[data-tab="file"]`);

  await page.fill(`${PAGE} .cfp-search-input`, '截图');
  await settle(page, 150);
  check('mobile: 搜索 "截图" 命中 1 条', (await rowPaths(page)).join() === '/w/img/pic.png');
  await shot(page, 'mobile-search.png', 'mobile search filter', PAGE);
  await page.fill(`${PAGE} .cfp-search-input`, '');
  await closePage(page);

  // 星标 round-trip（移动端：文档页顶胶囊；切到 canvas 页再回收藏页）。
  await starRoundTrip(page, 'mobile', async () => {
    await page.evaluate(async () => (await import('/js/mobile/navController.js')).dockTo('workbench'));
    await openPage();
  });
  await openPage();
  await shot(page, 'mobile-favorites-after-star.png', 'mobile favorites page after star round-trip', PAGE);
  await closePage(page);
  await s.close();
}

async function main() {
  mkdirSync(SHOT_DIR, { recursive: true });
  const pwRes = loadPlaywright();
  const pw = pwRes.mod.playwright || pwRes.mod;
  const { browser, label } = await launchBrowser(pw);
  console.log(`[canvas-favorites-flow] browser=${label} shots=${SHOT_DIR}`);
  const failures = [];
  try {
    await desktopCase(browser, failures);
    await mobileCase(browser, failures);
  } finally {
    await browser.close();
  }
  const bad = results.filter((r) => !r.ok).length + failures.length;
  for (const f of failures) console.error(`FAIL ${f}`);
  console.log(`[flow] checks=${results.length} shots=${results.filter((r) => r.name.startsWith('screenshot')).length}`);
  if (bad) { console.error(`canvas-favorites-flow: ${bad} failure(s)`); process.exit(1); }
  console.log(`canvas-favorites-flow: green (${results.length} checks)`);
}

main().catch((e) => { console.error(`canvas-favorites-flow: ${e && e.message ? e.message.split('\n')[0] : e}`); process.exit(e && /playwright|browser|Executable/i.test(String(e.message)) ? 2 : 1); });

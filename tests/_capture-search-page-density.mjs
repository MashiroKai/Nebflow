#!/usr/bin/env node
// _capture-search-page-density.mjs — search-page-density 批 交付证据采集
//（作者令①–④ + 追加两条：手机层日期滚轮 / 次级浮层可读性）。
//
// 产出（默认 OUT = .stand/tmp/search-page-density/delivery/）：
//   390×844 手机层 ·
//     m-search-after.png          改后搜索页首屏（tabs → chips 行（含选择文件）→ 计数行 → 结果）
//     m-search-before.png         改前同页（用 base 工作树跑同一脚本，见 --root）
//     m-files-selected.png        选中 1 个文件 ⇒ 底部悬浮「打开所选文件 (1)」
//     m-date-wheel.png            日期 tab ⇒ 三列滚轮（年/月/日）+ 幕布
//     m-date-wheel-after.png      滚轮确认后 tab 落锚（日期标签 + ×）
//     select-to-bar.gif           选中文件 → 悬浮条出现的动效（逐帧连拍）
//   1440×900 桌面 ·
//     d-secondary-month-open.png  桌面日历年/月下拉（压不透）
//     d-filter-menu.png           桌面筛选动作表菜单（压不透）
//
// Run: node tests/_capture-search-page-density.mjs [--root <repo>] [--tag before|after]

import { chromium } from 'playwright';
import { mkdirSync, readFileSync } from 'node:fs';
import { join, dirname, extname, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const argOf = (k, d) => { const i = args.indexOf(k); return i >= 0 ? args[i + 1] : d; };
const ROOT = resolve(argOf('--root', join(HERE, '..')));
const TAG = argOf('--tag', 'after');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const OUT = process.env.SPD_OUT || '/Users/kaiyu/Claude code/Stand/.stand/tmp/search-page-density/delivery';
const FRAMES = join(OUT, `.frames-${TAG}`);
mkdirSync(OUT, { recursive: true });
mkdirSync(FRAMES, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const SID = 'spd-capture';
const T0 = Date.now();
const KW = '密度';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const history = { messages: [
  { type: 'user', text: `${KW} 检查一下这批的空间开销`, timestamp: T0 - 60_000,
    attachments: [{ type: 'file', name: 'density-note.md', path: '/tmp/spd/density-note.md' }] },
  { type: 'ai', text: `${KW} 相关结论在下面：筛选行合并后省下 100px 上下。`, timestamp: T0 - 40_000 },
  { type: 'user', text: '另外一条无关消息', timestamp: T0 - 20_000 },
] };

async function makePage(browser, ctxOpts) {
  const context = await browser.newContext(ctxOpts);
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === `/api/sessions/${SID}/history`) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(history) });
    }
    if (p === '/api/sessions') {
      return route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
    }
    if (/^\/agents\/[^/]+\/agent\.json$/.test(p)) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ name: 'Nebula', displayName: 'Nebula' }) });
    }
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const config = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sessions = () => ws.send(JSON.stringify({ type: 'sessionList',
      sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    config(); sessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') config();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await sleep(400);
  return { page, context, errors };
}

/** 夹具噪音：零后端 harness 下 canvas 持久化必失败 ⇒ 长驻 `.nebflow-toast`
 *  「Canvas 保存失败」（z 10000）既拦截点击又压在截图底缘。这与被测功能无关，
 *  证据采集期整族摘除（判据用 tests/search-page-density.spec.mjs，不在本脚本）。 */
async function clearToasts(page) {
  await page.evaluate(() => {
    if (!document.getElementById('spd-capture-notoast')) {
      const st = document.createElement('style');
      st.id = 'spd-capture-notoast';
      st.textContent = '.nebflow-toast{display:none!important}';
      (document.head || document.documentElement).appendChild(st);
    }
    document.querySelectorAll('.nebflow-toast, .toast, .nf-toast, #toast-container > *')
      .forEach((el) => el.remove());
  });
}

async function openMobileSearch(page) {
  await page.locator("[data-chrome='search']").click();
  await page.waitForSelector('#search-modal', { timeout: 10000 });
  await sleep(600);
  await clearToasts(page);
}

const all = [];

async function mobileShots(browser) {
  const { page, context, errors } = await makePage(browser, {
    viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, hasTouch: true, isMobile: true, colorScheme: 'light',
  });
  await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 20000 });
  await sleep(500);
  await openMobileSearch(page);

  // ① 首屏：tabs → chips 行（含「选择文件」）→ 计数行 → 结果
  await page.locator('#search-keyword').fill(KW);
  await sleep(1200);
  await clearToasts(page);
  await sleep(200);
  const shot = async (name) => {
    const p = join(OUT, `${TAG === 'before' ? 'm-search-before' : 'm-search-after'}.png`);
    await page.screenshot({ path: name === 'main' ? p : join(OUT, name), animations: 'disabled' });
    all.push(name === 'main' ? p : join(OUT, name));
  };
  await shot('main');

  const chrome = await page.evaluate(() => {
    const s = document.getElementById('search-status');
    const c = document.querySelector('#search-modal .search-controls');
    const kindRow = !!document.getElementById('mh-search-kind-row');
    return {
      statusH: Math.round(s.getBoundingClientRect().height),
      controlsH: Math.round(c.getBoundingClientRect().height),
      chromeH: Math.round(c.getBoundingClientRect().height + s.getBoundingClientRect().height),
      kindRow,
      selectFilesInRow: document.getElementById('search-select-files')?.parentElement?.id || null,
    };
  });
  console.log(`${TAG} chrome readings: ${JSON.stringify(chrome)}`);

  // ② 文件档 + 选中 ⇒ 底部悬浮条（逐帧连拍做 GIF）
  //    先清关键词：文件档要的是「浏览全部附件」这批网格，不是关键词子集。
  await page.locator('#search-keyword').fill('');
  await page.locator('#search-tab-files').click();
  await sleep(1200);
  await clearToasts(page);
  await page.locator('#search-select-files').click();
  await sleep(400);
  await shot(`${TAG}-files-selectmode.png`);
  const barBefore = await page.locator('#search-open-selected-files').isVisible().catch(() => false);
  const card = page.locator('#search-results .search-media-card').first();
  await card.waitFor({ state: 'visible', timeout: 8000 });

  let f = 0;
  const frame = async () => {
    await page.screenshot({ path: join(FRAMES, `f${String(f++).padStart(3, '0')}.png`) });
  };
  await frame();                                   // 选中前
  await card.click();
  for (let i = 0; i < 14; i++) { await frame(); await sleep(45); }   // 悬浮条浮现
  await sleep(500);
  await shot(`${TAG}-files-selected.png`);
  const barAfter = await page.locator('#search-open-selected-files').isVisible();
  const barText = await page.locator('#search-open-selected-files').textContent().catch(() => '');
  console.log(`${TAG} open-bar before/after: ${barBefore}/${barAfter} text=${JSON.stringify(barText)}`);

  // ③ 日期 tab ⇒ 三列滚轮 + 幕布
  await page.locator('#search-tab-date').click();
  await sleep(900);
  await clearToasts(page);
  await shot(`${TAG}-date-wheel.png`);
  const wheel = await page.evaluate(() => ({
    panel: !!document.querySelector('.tw-panel'),
    cols: document.querySelectorAll('.tw-body-date .tw-col').length,
    roles: [...document.querySelectorAll('.tw-body-date .tw-col')].map((el) => el.dataset.role),
    scrim: !!document.querySelector('.tw-scrim.in'),
    calGrid: document.querySelectorAll('.tw-panel .cal-grid').length,
    pill: document.querySelectorAll('.tw-panel .cal-pill').length,
  }));
  console.log(`${TAG} wheel: ${JSON.stringify(wheel)}`);
  const confirm = page.locator('.tw-panel .tw-confirm');
  if (await confirm.count()) {
    await confirm.click();
    await sleep(700);
    await shot(`${TAG}-date-anchored.png`);
  }

  if (errors.length) console.log(`${TAG} mobile page errors: ${JSON.stringify(errors.slice(0, 3))}`);
  await context.close();
}

async function desktopShots(browser) {
  const { page, context, errors } = await makePage(browser, {
    viewport: { width: 1440, height: 900 }, colorScheme: 'light',
  });
  await page.locator('#search-btn').click();
  await page.waitForSelector('#search-overlay.on', { timeout: 10000 });
  await sleep(500);

  // 日历年/月下拉（作者截图证据那一面）
  await page.locator('#search-tab-date').click();
  await sleep(400);
  const pill = page.locator('#search-date-popover .cal-pill[data-pill="month"]');
  if (await pill.count()) {
    await pill.click();
    await sleep(400);
    await page.screenshot({ path: join(OUT, `${TAG}-d-month-open.png`), animations: 'disabled' });
    all.push(join(OUT, `${TAG}-d-month-open.png`));
    const bg = await page.locator('.cal-pill-listbox').evaluate((el) => getComputedStyle(el).backgroundColor);
    console.log(`${TAG} month listbox bg: ${bg}`);
    await page.keyboard.press('Escape');
    await sleep(300);
  }

  // 筛选动作表菜单
  await page.locator('#search-tool').click();
  await sleep(400);
  if (await page.locator('.search-filter-menu').count()) {
    await page.screenshot({ path: join(OUT, `${TAG}-d-filter-menu.png`), animations: 'disabled' });
    all.push(join(OUT, `${TAG}-d-filter-menu.png`));
    const bg = await page.locator('.search-filter-menu').evaluate((el) => getComputedStyle(el).backgroundColor);
    console.log(`${TAG} filter-menu bg: ${bg}`);
  }
  if (errors.length) console.log(`${TAG} desktop page errors: ${JSON.stringify(errors.slice(0, 3))}`);
  await context.close();
}

const browser = await chromium.launch();
try {
  await mobileShots(browser);
  await desktopShots(browser);
} finally {
  await browser.close();
}
console.log(`captured ${all.length} stills (tag=${TAG}) → ${OUT}`);

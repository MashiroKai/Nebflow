// shoot.mjs — 真机 :8080 全景截图工具（delegate 视图重设计实施批 · 证据面）。
//
// 用法（在主树跑，playwright 只在主树）：
//   node shoot.mjs full          # 暗+亮双主题：全景全量 + 子过程星扇（stand 项目过滤）
//   node shoot.mjs merge [dark|light|both]   # 合并管线视图（候合并/合并中真实状态在场时截）
//
// 会话链 = console-ticket（tests/helpers/session-rail.mjs 同款）：
//   POST /api/auth/console-ticket (Bearer <auth.json token>) → ticket → GET /?ticket= 303 + cookie。
// 主题 = 生产 token 自带 prefers-color-scheme → 用 Playwright context colorScheme 切。
// 只读视图操作，零写请求。

import { createRequire } from 'node:module';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const MAIN = '/Users/kaiyu/Claude code/Stand';
const require = createRequire(join(MAIN, 'package.json'));
const { chromium } = require('playwright');

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = HERE;
const BASE = process.env.PANO_BASE || 'http://127.0.0.1:8123';
const TOKEN = JSON.parse(readFileSync('/Users/kaiyu/.stand/auth.json', 'utf8'));

const mode = process.argv[2] || 'full';
const schemes = (process.argv[3] || 'both') === 'both' ? ['dark', 'light'] : [process.argv[3]];

mkdirSync(OUT, { recursive: true });

async function mintTicket() {
  const r = await fetch(`${BASE}/api/auth/console-ticket`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${TOKEN}` },
  });
  if (!r.ok) throw new Error(`console-ticket HTTP ${r.status}`);
  const b = await r.json();
  if (typeof b.ticket !== 'string' || b.ticket.length < 20) throw new Error('no ticket');
  return b.ticket;
}

/** 开一个已认证页面并等到 provider 镜像首刷完成（行集非空）。 */
async function openReadyPage(context, ticket) {
  const page = await context.newPage();
  await page.goto(`${BASE}/?ticket=${encodeURIComponent(ticket)}`, { waitUntil: 'load' });
  await page.waitForFunction(async () => {
    const s = (await import('/js/state.js')).default;
    return s.ws && s.ws.readyState === 1;
  }, undefined, { timeout: 30_000 });
  await page.waitForFunction(async () => {
    const stub = await import('/js/delegateChainStub.js');
    return stub.listDelegateRows().length > 0;
  }, undefined, { timeout: 30_000 });
  return page;
}

async function openPanorama(page, project) {
  await page.evaluate(async (p) => {
    const m = await import('/js/delegatePanoramaView.js');
    m.openDelegatePanorama(p || '');
  }, project);
  // 布局判据自管（playwright visible 判定在应用壳内偶发失灵）：未隐藏带带画布
  // 且带体已排版（高 > 40px）。镜像刷新整树重渲由下一轮轮询吸收。
  await page.waitForFunction((proj) => {
    const bands = [...document.querySelectorAll('.canvas-tab-pane[data-tab-id="projects"] .dvp-band')];
    return bands.some((b) => !b.hidden && b.querySelector('.dvp-canvas') && b.getBoundingClientRect().height > 40);
  }, project, { timeout: 20_000 });
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(700); // 边路径/徽标落位余量
}

const BODY_SEL = '.canvas-tab-pane[data-tab-id="projects"] .delegate-chain-view-body';

/** 视口拉宽拉高：全景体 1336 min-width 装满视口（应用壳把 pane 压到 ~690px——
 *  截图座把滚动容器本体 fixed 占满视口，六泳道全入镜；仅证据座 CSS，零产品改动）。 */
async function fitViewport(page) {
  // 应用壳的列容器带 transform/will-change 族 → fixed 被困在 690px 列内且被真
  // 视口裁边。证据座先把整棵壳的 containing-block 触发器解除，再令视图体固定
  // 占满视口（仅截图进程内存样式，零产品文件改动）。
  await page.addStyleTag({
    content: '.deck-look-v2, .deck-look-v2 *{transform:none!important;filter:none!important;backdrop-filter:none!important;perspective:none!important;will-change:auto!important;contain:none!important;content-visibility:visible!important;}',
  });
  await page.addStyleTag({
    content: `${BODY_SEL}{position:fixed!important;top:0!important;left:0!important;right:0!important;bottom:0!important;width:100vw!important;max-width:none!important;z-index:9999!important;background:var(--color-bg,#101418);}`,
  });
  const h = await page.evaluate((sel) => {
    const body = document.querySelector(sel);
    if (!body) return 0;
    const extra = window.innerHeight - body.clientHeight;
    return Math.min(body.scrollHeight + extra + 24, 4600);
  }, BODY_SEL);
  if (h > 0) await page.setViewportSize({ width: 1720, height: h });
  await page.waitForTimeout(400);
}

async function shoot(page, name) {
  const dbg = await page.evaluate((sel) => {
    const b = document.querySelector(sel);
    const band = b && b.querySelector('.dvp-band');
    const canvas = b && b.querySelector('.dvp-canvas');
    const r = (el) => el ? [Math.round(el.getBoundingClientRect().x), Math.round(el.getBoundingClientRect().width)] : null;
    return {
      body: r(b), bodyPos: b ? getComputedStyle(b).position : null, bodyW: b ? getComputedStyle(b).width : null,
      band: r(band), canvas: r(canvas), canvasScrollW: canvas ? canvas.scrollWidth : null,
      innerW: window.innerWidth, dpr: window.devicePixelRatio,
    };
  }, BODY_SEL);
  console.log('pre-shot', JSON.stringify(dbg));
  await page.locator(BODY_SEL).first().screenshot({ path: join(OUT, `${name}.png`), animations: 'disabled' });
  console.log(`saved ${name}.png`);
}

const browser = await chromium.launch();
try {
  // 一 ticket 一用（one-time）：每个 context 各铸一张；单 scheme 失败重试一次
  //（镜像重渲窗口期的偶发空档）。
  for (const scheme of schemes) {
    for (let attempt = 1; attempt <= 2; attempt += 1) {
      try {
        const context = await browser.newContext({
          colorScheme: scheme,
          viewport: { width: 1720, height: 1100 },
          deviceScaleFactor: 2,
        });
        const ticket = await mintTicket();
        const page = await openReadyPage(context, ticket);

        if (mode === 'full') {
          await openPanorama(page, ''); // 全景全量（全项目带）
          await fitViewport(page);
          await shoot(page, `pano-full-${scheme}`);

          await openPanorama(page, 'stand'); // 子过程星扇（stand 带：#694 star + #648 star failed）
          // 展开子过程区（拥挤折叠 >12 行缺省收起——星扇视图逐带展开）。
          const toggles = page.locator('.canvas-tab-pane[data-tab-id="projects"] .dvp-zone-toggle[aria-expanded="false"]');
          const n = await toggles.count();
          for (let i = 0; i < n; i += 1) await toggles.nth(i).click();
          if (n) await page.waitForTimeout(600);
          await fitViewport(page);
          await shoot(page, `pano-star-${scheme}`);
        } else if (mode === 'merge') {
          await openPanorama(page, '');
          // 等真实候合并/合并中状态在场（本 delegate 自己 report 后进队/持窗）。
          const got = await page
            .waitForSelector('.canvas-tab-pane .dvp-nd[data-status="merging"], .canvas-tab-pane .dvp-nd[data-status="ready-to-merge"]', { timeout: 120_000 })
            .then(() => true)
            .catch(() => false);
          console.log(`merge-state visible: ${got}`);
          await fitViewport(page);
          await shoot(page, `pano-merge-${scheme}`);
        }
        await context.close();
        break; // 本 scheme 成功
      } catch (e) {
        console.log(`scheme ${scheme} attempt ${attempt} failed: ${String(e).split('\n')[0].slice(0, 140)}`);
        if (attempt === 2) throw e;
        await new Promise((r) => setTimeout(r, 2000));
      }
    }
  }
} finally {
  await browser.close();
}
console.log('done');

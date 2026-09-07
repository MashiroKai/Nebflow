#!/usr/bin/env node
// shot-archive-split-live.cjs — 裁定④视觉验收（真实隔离实例路线）。
// 由 e2e-ttl-split-archive-shots.sh 驱动（种子+引导+清理）；本文件只做页面操作与截图。
// Env: NEBFLOW_URL / NEBFLOW_TOKEN / OUT_DIR / PROJ

const { chromium } = require('playwright');
const { mkdirSync } = require('node:fs');
const { join } = require('node:path');

const URL_BASE = process.env.NEBFLOW_URL;
const TOKEN = process.env.NEBFLOW_TOKEN;
const OUT_DIR = process.env.OUT_DIR;
const PROJ = process.env.PROJ || 'e2e-ttl';

(async () => {
  mkdirSync(OUT_DIR, { recursive: true });
  const browser = await chromium.launch();
  for (const colorScheme of ['dark', 'light']) {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, colorScheme });
    const consoleErrors = [];
    page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
    page.on('pageerror', (e) => consoleErrors.push(String(e)));
    await page.addInitScript((tok) => {
      localStorage.setItem('nebflow_token', tok);
      localStorage.setItem('nebflow_locale', 'zh-CN');
    }, TOKEN);

    await page.goto(`${URL_BASE}/`, { waitUntil: 'domcontentloaded' });
    // 等 WS 通道起来（真实实例：sessionList/configData 到达即就绪）
    await page.waitForFunction(() => document.querySelector('.sidebar, #sidebar, nav'), null, { timeout: 20000 });
    await page.waitForTimeout(1500);

    // 打开就地 Flow Map 视图（生产入口）
    await page.evaluate((proj) => import('/js/projectTab.js').then((m) => m.openProjectFlowMapAt(proj)), PROJ);
    await page.waitForFunction(() => document.querySelectorAll('.flowmap-view-body .fm-node').length >= 2, null, { timeout: 15000 });
    // remote 首拉落定：徽章出现（归档批 ≥2：迁移遗产批 + 链A sweep 批 [+ 链C失败归档批]）
    await page.waitForFunction(() => {
      const b = document.querySelector('.fm-archive-badge');
      return b && !b.classList.contains('hidden') && Number(b.textContent) >= 2;
    }, null, { timeout: 15000 });
    await page.waitForTimeout(800);

    // 主图：链A 已离场（不可见），链B（completed+blocked）保留
    const mainState = await page.evaluate(() => {
      const ids = Array.from(document.querySelectorAll('.fm-node')).map((el) => el.getAttribute('data-node-id'));
      return { ids, badge: document.querySelector('.fm-archive-badge')?.textContent };
    });
    console.log(`main map nodes (${colorScheme}):`, JSON.stringify(mainState));

    const shotMain = join(OUT_DIR, `20260907_archive-live-1-main-${colorScheme}.png`);
    await page.screenshot({ path: shotMain });
    console.log(`saved ${shotMain}`);

    // 归档面板：remote 批次条目
    await page.click('.fm-fab');
    await page.waitForFunction(() => {
      const p = document.querySelector('.fm-archive-panel');
      return p && !p.hidden && p.classList.contains('open') && p.querySelectorAll('.fm-entry').length >= 2;
    }, null, { timeout: 8000 });
    const panelInfo = await page.evaluate(() => {
      const entries = Array.from(document.querySelectorAll('.fm-entry')).map((el) => ({
        id: el.getAttribute('data-chain-id'),
        name: el.querySelector('.fm-entry-name')?.textContent,
        nodes: el.querySelector('.fm-entry-nodes')?.textContent,
      }));
      return { entries, count: document.querySelector('.fm-panel-count')?.textContent };
    });
    console.log(`panel (${colorScheme}):`, JSON.stringify(panelInfo));
    // 展开遗产批（迁移来源）验成员行
    await page.click('.fm-entry[data-chain-id="chain-n-legacy1"]').catch(() => {});
    await page.waitForTimeout(350);
    const shotPanel = join(OUT_DIR, `20260907_archive-live-2-panel-${colorScheme}.png`);
    await page.locator('.fm-archive-panel').screenshot({ path: shotPanel });
    console.log(`saved ${shotPanel}`);

    // remote 成员详情 + 结果全文（真实后端 findNode 归档兜底 → results/<id>.md）
    await page.click('.fm-member[data-node="n-legacy1"]').catch(() => {});
    await page.waitForFunction(() => {
      const d = document.querySelector('.fm-detail');
      return d && !d.hidden && d.classList.contains('open');
    }, null, { timeout: 8000 }).catch(() => {});
    await page.waitForTimeout(700);
    const detailHasResult = await page.evaluate(() =>
      (document.querySelector('.fm-detail-result')?.textContent || '').includes('LEGACY-RESULT-1'));
    console.log(`detail full-result loaded (${colorScheme}):`, detailHasResult);
    const shotDetail = join(OUT_DIR, `20260907_archive-live-3-detail-${colorScheme}.png`);
    await page.locator('.fm-detail').screenshot({ path: shotDetail }).catch(() => {});
    console.log(`saved ${shotDetail}`);

    if (consoleErrors.length) console.log(`console errors (${colorScheme}):`, consoleErrors.slice(0, 5));
    await page.close();
  }
  await browser.close();
  console.log('done');
})().catch((e) => { console.error(e); process.exit(1); });

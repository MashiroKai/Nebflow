#!/usr/bin/env node
// Real application shell, with the shared WS fixture; screenshots go outside the checkout.
import assert from 'node:assert/strict';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { ROOT, loadPlaywright, launchBrowser, createSession, settle } from '../scripts/lib/visual-harness.mjs';

const primary = ROOT.split('/.stand/worktrees/')[0];
const shots = process.env.FAV_SLOT_SHOTS || join(primary, '.stand/tmp/favorites-slot/evidence/geometry');
const boot = { config: { llm: { providers: {} }, features: {} }, sessionId: 'fav-slot', messages: [] };
mkdirSync(shots, { recursive: true });
const { browser } = await launchBrowser(loadPlaywright().mod);
try {
  for (const [width, height] of [[1280, 800], [1440, 900]]) {
    const s = await createSession(browser, { width, height, colorScheme: 'light' });
    try {
      await s.boot(boot);
      await s.page.evaluate(async () => {
        (await import('/js/activityBar.js')).enableFriendPanels();
        (await import('/js/canvasFavorites.js')).toggleFavoritePath({ path: '/workspace/notes/example.md', title: 'Example note' });
      });
      const rects = [];
      for (const [name, btn, panel] of [['favorites', '#favorites-btn', '#panel-favorites'], ['files', '#files-btn', '#panel-sessions'], ['messages', '#messages-btn', '#panel-messages']]) {
        await s.page.click(btn);
        await s.page.waitForSelector(`${panel}.active`, { state: 'visible' });
        await settle(s.page, 100);
        if (name === 'favorites') assert.equal(await s.page.locator('#panel-favorites .cfp-search-input').evaluate(el => el === document.activeElement), true, 'desktop focus enters search');
        const geometry = await s.page.evaluate((sel) => {
          const r = document.querySelector(sel).getBoundingClientRect();
          const bar = document.querySelector('#activity-bar').getBoundingClientRect();
          return { x: r.x, y: r.y, width: r.width, height: r.height, right: r.right, bottom: r.bottom, barRight: bar.right, viewportWidth: innerWidth, viewportHeight: innerHeight };
        }, panel);
        console.log(JSON.stringify({ width, height, name, geometry }));
        assert(geometry.x >= -1 && geometry.y >= -1 && geometry.right <= width + 1 && geometry.bottom <= height + 1 && geometry.barRight <= geometry.x + 1, 'panel bounds or activity bar overlap');
        rects.push([geometry.x, geometry.y, geometry.width, geometry.height]);
        await s.page.screenshot({ path: join(shots, `desktop-${width}-${name}.png`), animations: 'disabled' });
      }
      assert.deepEqual(rects[1], rects[0]); assert.deepEqual(rects[2], rects[0]);
      assert.equal(await s.page.locator('#canvas-fav-section').count(), 0, 'legacy compact favorites removed');
      assert.equal(await s.page.locator('#panel-favorites .cfp-back').count(), 0, 'desktop has no back button');
    } finally { await s.close(); }
  }
  for (const width of [375, 390]) {
    const s = await createSession(browser, { width, height: 844, colorScheme: 'light' });
    try {
      await s.boot(boot);
      await s.page.evaluate(async () => {
        (await import('/js/canvasFavorites.js')).toggleFavoritePath({ path: '/workspace/notes/example.md', title: 'Example note' });
        (await import('/js/mobile/navController.js')).dockTo('workbench');
      });
      const entry = s.page.locator('.mobile-page[data-page="workbench"].live .mf-hub-row[data-hub-page="favorites"]');
      await entry.focus(); await entry.click();
      await s.page.waitForSelector('#canvas-fav-page.is-open .cfp-row');
      await settle(s.page, 100);
      assert.equal(await s.page.locator('#canvas-fav-page .cfp-back').evaluate(el => el === document.activeElement), true, 'focus enters page');
      const geom = await s.page.evaluate(() => {
        const page = document.querySelector('#canvas-fav-page');
        const row = page.querySelector('.cfp-row');
        const r = row.getBoundingClientRect();
        return { scrollWidth: document.documentElement.scrollWidth, clientWidth: document.documentElement.clientWidth, pageScrollWidth: page.scrollWidth, pageClientWidth: page.clientWidth, row: { left: r.left, right: r.right, top: r.top, bottom: r.bottom }, title: row.querySelector('.cfp-row-title').textContent, path: row.querySelector('.cfp-row-src').textContent };
      });
      console.log(JSON.stringify({ width, mobile: geom }));
      assert(geom.scrollWidth <= geom.clientWidth + 1 && geom.pageScrollWidth <= geom.pageClientWidth + 1);
      assert(geom.row.left >= -1 && geom.row.right <= width + 1 && geom.row.top >= -1 && geom.row.bottom <= 845);
      assert.equal(geom.path, '/workspace/notes/example.md');
      await s.page.screenshot({ path: join(shots, `mobile-${width}.png`), animations: 'disabled' });
      await s.page.keyboard.press('Escape');
      assert.equal(await s.page.locator('#canvas-fav-page').count(), 0);
      assert.equal(await entry.evaluate(el => el === document.activeElement), true, 'focus restored on Escape');
      await entry.click(); await s.page.waitForSelector('#canvas-fav-page.is-open');
      await s.page.click('#canvas-fav-page .cfp-back');
      assert.equal(await entry.evaluate(el => el === document.activeElement), true, 'focus restored on back');
    } finally { await s.close(); }
  }
  console.log('favorites-slot-geometry: PASS');
} finally { await browser.close(); }

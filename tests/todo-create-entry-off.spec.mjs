#!/usr/bin/env node
// Fixture-backed real frontend consumer; all requests are routed by visual-harness.
import assert from 'node:assert/strict';
import { loadPlaywright, createSession, settle } from '../scripts/lib/visual-harness.mjs';
const browser = await loadPlaywright().mod.chromium.launch({ headless: true });
try {
  for (const mobile of [false, true]) {
    const context = await browser.newContext({ viewport: { width: mobile ? 390 : 1280, height: 844 }, isMobile: mobile, hasTouch: mobile });
    const { page, boot } = await createSession({ newPage: () => context.newPage() }, { width: mobile ? 390 : 1280, height: 844 });
    await boot();
    await page.evaluate(async () => {
      const { todoStore } = await import('/js/todoStore.js');
      todoStore.__seed({ tagList: [{ id: 'tag-a', name: 'List A', color: 'rgb(59,130,246)' }], itemList: [{ id: 'one', tagId: 'tag-a', title: 'Existing item', notes: '', due: null, priority: 0, createdAt: 1 }] });
      if (document.body.classList.contains('mobile-layer')) (await import('/js/mobile/navController.js')).pushPage('tasks');
      else (await import('/js/todoFace.js')).renderTodoTaskList('fixture');
    });
    if (mobile) {
      await page.waitForSelector('.mobile-page.live .ta-card-all');
      assert.equal(await page.locator('.mobile-page.live .ta-fab').count(), 0);
      await page.locator('.mobile-page.live .ta-card-all').click();
      await page.waitForSelector('.mobile-page.live .ta-items');
      assert.equal(await page.locator('.mobile-page.live .ta-pill-btn').count(), 0);
      assert.equal(await page.locator('.mobile-page.live .ta-items').getByText('Existing item').count(), 1);
      assert.equal(await page.locator('[data-todo-create-overlay]').count(), 0);
    } else {
      await page.waitForSelector('.tfl-capsule');
      await page.locator('.tfl-capsule').click();
      await settle(page);
      assert.equal(await page.locator('.tfl-fab-add, .tfl-add-btn').count(), 0);
      assert.equal(await page.locator('.tfl-row').count(), 1);
      await page.locator('.tfl-list').click({ position: { x: 5, y: 5 } });
      await page.locator('.tfl-list').press('Enter');
      assert.equal(await page.locator('.tfl-create-row').count(), 0);
    }
    assert.equal(await page.evaluate(async () => (await import('/js/todoStore.js')).todoStore.allItems().length), 1);
    await context.close();
    console.log(`${mobile ? 'mobile' : 'desktop'} creation entry off: PASS; fixture count 1`);
  }
} finally { await browser.close(); }

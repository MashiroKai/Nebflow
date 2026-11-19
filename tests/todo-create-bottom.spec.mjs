#!/usr/bin/env node
// Active todo creation flow. The previous test asserted a retired page-push
// design; this checks the actual bottom FAB -> create sheet -> cancel flow.
import assert from 'node:assert/strict';
import { loadPlaywright, createSession, settle } from '../scripts/lib/visual-harness.mjs';
const { mod } = loadPlaywright();
const browser = await mod.chromium.launch({ headless: true });
let assertions = 0;
function ok(value, message) { assert.ok(value, message); assertions++; }
try {
  const context = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
  const { page, boot } = await createSession({ newPage: () => context.newPage() }, { width: 390, height: 844, baseURL: 'https://stand.test/' });
  await boot(); await page.waitForSelector('body.mobile-layer');
  await page.evaluate(async () => {
    const { todoStore } = await import('/js/todoStore.js');
    todoStore.__seed({ tagList: [{id:'work', name:'Work', color:'rgb(59,130,246)'}], itemList: [] });
    (await import('/js/mobile/navController.js')).pushPage('tasks');
  });
  await page.waitForSelector('.mobile-page.live .ta-fab'); await settle(page);
  ok(await page.locator('.mobile-page.live .ta-fab').count() === 1, 'single FAB');
  await page.locator('.mobile-page.live .ta-fab').click();
  await page.waitForSelector('[data-todo-create-sheet]', { state: 'visible' });
  ok(await page.locator('[data-todo-create-sheet]').isVisible(), 'actual sheet is visible');
  ok(await page.locator('.ta-create-title').inputValue() === '', 'create title begins empty');
  const focus = await page.evaluate(() => ({active:document.activeElement?.className, visualViewportHeight: window.visualViewport?.height, innerHeight:window.innerHeight, keyboardInset:getComputedStyle(document.documentElement).getPropertyValue('--ta-kb')}));
  console.log('focused textarea / viewport (headless, no real OS keyboard):', JSON.stringify(focus));
  await page.locator('.ta-create-cancel').click();
  ok(await page.locator('[data-todo-create-sheet]').count() === 0, 'cancel closes sheet');
  ok(await page.locator('.mobile-page.live .ta-fab').isVisible(), 'FAB available after cancel');
  await context.close();
  console.log(`todo create bottom: ${assertions} assertions PASS`);
} finally { await browser.close(); }

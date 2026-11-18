#!/usr/bin/env node
// Active Apple todo route: replace retired .tfl topmenu assertions with
// geometry, reachability and end-of-list regression checks. No backend.
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
    todoStore.__seed({ tagList: Array.from({length: 18}, (_, i) => ({id: `tag-${i}`, name: `List ${i}`, color: 'rgb(59,130,246)'})), itemList: [] });
    (await import('/js/mobile/navController.js')).pushPage('tasks');
  });
  await page.waitForSelector('.mobile-page.live .ta-listcard'); await settle(page);
  const geometry = await page.evaluate(() => {
    const q = sel => { const el = document.querySelector(sel); return el?.getBoundingClientRect().toJSON() ?? null; };
    const root = document.querySelector('.mobile-page.live');
    const button = root.querySelector('.ta-fab');
    const rect = button.getBoundingClientRect();
    const hit = document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2);
    return {back:q('.mobile-page.live .mobile-back-btn'), capsule:q('.mobile-page.live .ta-pill'), fab:q('.mobile-page.live .ta-fab'), dock:q('#mobile-dock'), hit: button === hit || button.contains(hit)};
  });
  console.log('actual control rectangles:', JSON.stringify(geometry));
  ok(geometry.back?.height > 0, 'actual back control exists');
  ok(geometry.capsule?.height > 0, 'actual todo capsule exists');
  ok(geometry.fab?.height > 0, 'actual FAB exists');
  ok(geometry.fab.bottom <= geometry.dock.top && geometry.hit, 'FAB above dock and receives hits');
  const endpoint = await page.evaluate(() => {
    const card = document.querySelector('.mobile-page.live .ta-listcard');
    let scroller = card.parentElement;
    while (scroller && !(scroller.scrollHeight > scroller.clientHeight + 1 && /auto|scroll/.test(getComputedStyle(scroller).overflowY))) scroller = scroller.parentElement;
    if (!scroller) return null;
    scroller.scrollTop = scroller.scrollHeight;
    const last = [...card.querySelectorAll('.ta-row')].filter(el => el.getBoundingClientRect().height > 0).at(-1);
    return { scrollTop: scroller.scrollTop, max: scroller.scrollHeight-scroller.clientHeight, card:card.getBoundingClientRect().toJSON(), last:last?.getBoundingClientRect().toJSON(), scroller:scroller.getBoundingClientRect().toJSON(), scrollClass: scroller.className, root:card.closest('.ta-root')?.getBoundingClientRect().toJSON(), rootPadding:getComputedStyle(card.closest('.ta-root')).paddingBottom, fab:document.querySelector('.mobile-page.live .ta-fab').getBoundingClientRect().toJSON(), dock:document.querySelector('#mobile-dock').getBoundingClientRect().toJSON() };
  });
  console.log('todo end geometry:', JSON.stringify(endpoint));
  ok(endpoint && endpoint.max > 0 && endpoint.scrollTop >= endpoint.max - 1, 'real list reaches scroll end');
  ok(endpoint.last && endpoint.last.height > 0 && endpoint.last.top >= endpoint.scroller.top && endpoint.last.bottom <= Math.min(endpoint.fab.top, endpoint.dock.top, endpoint.scroller.bottom) + 1, 'last visible row fully above controls');
  await context.close();
  console.log(`todo mobile top controls: ${assertions} assertions PASS`);
} finally { await browser.close(); }

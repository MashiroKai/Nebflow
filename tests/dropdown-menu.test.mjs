import assert from 'node:assert/strict';
import { createSession, loadPlaywright } from '../scripts/lib/visual-harness.mjs';

const { mod } = loadPlaywright();
const browser = await mod.chromium.launch({ headless: true });
try {
  const session = await createSession(browser, { width: 390, height: 844, baseURL: 'https://stand.test/' });
  await session.boot();
  const { page } = session;
  await page.evaluate(async () => {
    const { openDropdownMenu } = await import('/js/dropdownMenu.js');
    const trigger = document.createElement('button');
    trigger.id = 'dropdown-test-trigger';
    trigger.style.cssText = 'position:fixed;right:0;bottom:0;z-index:10001';
    trigger.textContent = 'Open';
    document.body.append(trigger);
    trigger.onclick = () => {
      const menu = document.createElement('div');
      menu.className = 'project-open-menu nf-glass';
      menu.innerHTML = Array.from({ length: 40 }, (_, i) => `<button type="button" role="menuitemradio" aria-checked="${i === 0}">Item ${i}</button>`).join('');
      let close;
      menu.addEventListener('click', event => {
        if (event.target.matches('button')) { trigger.dataset.value = event.target.textContent; close(); }
      });
      close = openDropdownMenu(trigger, menu);
    };
  });
  for (let iteration = 0; iteration < 20; iteration++) {
    await page.locator('#dropdown-test-trigger').click();
    const menu = page.locator('.project-open-menu');
    assert.equal(await menu.count(), 1);
    assert.equal(await menu.locator('[aria-checked="true"]').count(), 1);
    const box = await menu.boundingBox();
    assert.ok(box.x >= 0 && box.y >= 0 && box.x + box.width <= 390 && box.y + box.height <= 844);
    assert.ok(await menu.evaluate(el => el.scrollHeight > el.clientHeight));
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('Enter');
    assert.equal(await page.locator('#dropdown-test-trigger').getAttribute('data-value'), 'Item 1');
    assert.equal(await menu.count(), 0);
    await page.locator('#dropdown-test-trigger').click();
    await page.keyboard.press('Escape');
    assert.equal(await menu.count(), 0);
    assert.equal(await page.evaluate(() => document.activeElement.id), 'dropdown-test-trigger');
    await page.locator('#dropdown-test-trigger').click();
    await page.mouse.click(1, 1);
    assert.equal(await menu.count(), 0);
  }
  console.log('PASS dropdown lifecycle: 20 consecutive iterations');
} finally { await browser.close(); }

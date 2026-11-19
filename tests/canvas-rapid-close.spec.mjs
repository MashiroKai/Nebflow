// Regression: rapid close actions must advance to a distinct active tab.
import assert from 'node:assert/strict';
import { createSession, loadPlaywright } from '../scripts/lib/visual-harness.mjs';
const { mod } = loadPlaywright();
for (const mobile of [true, false]) {
  const browser = await mod.chromium.launch({ headless: true });
  try {
    const context = await browser.newContext(mobile
      ? { viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true }
      : { viewport: { width: 1280, height: 844 } });
    try {
      const session = await createSession({ newPage: () => context.newPage() }, { width: mobile ? 390 : 1280, height: 844, baseURL: 'https://stand.test/' });
      const { page } = session;
      await session.boot();
      if (mobile) await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click();
      else await page.evaluate(async () => (await import('/js/canvas.js')).openCanvas());
      await page.evaluate(() => {
        for (let i = 0; i < 6; i++) window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: {
          id: `file:/tmp/rapid-${i}.md`, title: `rapid-${i}.md`, absPath: `/tmp/rapid-${i}.md`, pinned: true,
        } }));
      });
      await page.waitForFunction(() => document.querySelectorAll('.canvas-tab[data-tab-id^="file:/tmp/rapid-"]').length === 6);
      const selector = mobile ? '.mf-cv-close' : '#canvas-head-close';
      for (let i = 5; i >= 1; i--) {
        assert.equal(await page.locator('.canvas-tab.active').getAttribute('data-tab-id'), `file:/tmp/rapid-${i}.md`);
        const box = await page.locator(selector).boundingBox();
        assert.ok(box);
        if (mobile) await page.touchscreen.tap(box.x + box.width / 2, box.y + box.height / 2);
        else await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
      }
      assert.deepEqual(await page.locator('.canvas-tab[data-tab-id^="file:/tmp/rapid-"]').evaluateAll(els => els.map(el => el.dataset.tabId)), ['file:/tmp/rapid-0.md']);
      console.log(`${mobile ? 'mobile touch' : 'desktop mouse'}: five rapid closes passed`);
    } finally { await context.close(); }
  } finally { await browser.close(); }
}

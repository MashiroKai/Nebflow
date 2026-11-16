// Structural regression: production canvas panels with synthetic local inputs.
import assert from 'node:assert/strict';
import { createSession, loadPlaywright, settle } from '../scripts/lib/visual-harness.mjs';

const { mod } = loadPlaywright();
for (const engine of ['chromium', 'webkit']) {
  const browser = await mod[engine].launch({ headless: true });
  try {
    const context = await browser.newContext({ viewport: { width: 1000, height: 800 } });
    const session = await createSession({ newPage: () => context.newPage() }, { width: 1000, height: 800, baseURL: 'https://stand.test/' });
    const { page } = session;
    await session.boot();
    await page.evaluate(() => window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: {
      id: 'file:/tmp/blank-close.md', itemType: 'markdown', title: 'fixture.md', content: '# Fixture', absPath: '/tmp/blank-close.md', pinned: true,
    } })));
    await page.waitForSelector('.note-fab:not(.gate-off)');
    await settle(page);
    for (const [fab, dock] of [['.note-fab', '.note-dock'], ['.ask-fab:not(.note-fab)', '.ask-dock']]) {
      const open = async () => { await page.locator(fab).click(); await page.waitForSelector(`${dock}:not(.closed)`); };
      const closed = () => page.locator(dock).evaluate(el => el.classList.contains('closed'));
      await open();
      await page.locator(dock).locator('textarea').first().dispatchEvent('pointerdown', { bubbles: true, pointerId: 1, pointerType: 'mouse', isPrimary: true, button: 0 });
      assert.equal(await closed(), false, `${engine} ${dock}: inside pointerdown`);
      await page.evaluate(() => document.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, pointerId: 1 })));
      await page.evaluate(() => {
        const event = { bubbles: true, pointerId: 2, pointerType: 'mouse', isPrimary: true, button: 0, clientX: 400, clientY: 200 };
        document.body.dispatchEvent(new PointerEvent('pointerdown', event));
        document.body.dispatchEvent(new PointerEvent('pointermove', { ...event, clientX: 420 }));
        document.body.dispatchEvent(new PointerEvent('pointerup', { ...event, clientX: 420 }));
      });
      assert.equal(await closed(), false, `${engine} ${dock}: outside drag`);
      await page.evaluate(() => {
        const event = { bubbles: true, pointerId: 2, pointerType: 'mouse', isPrimary: true, button: 0, clientX: 400, clientY: 200 };
        document.body.dispatchEvent(new PointerEvent('pointerdown', event));
        document.body.dispatchEvent(new PointerEvent('pointerup', event));
      });
      assert.equal(await closed(), true, `${engine} ${dock}: outside click`);
      await open();
      // A second pointer while another interaction is underway must not dismiss.
      await page.evaluate(() => {
        const event = { bubbles: true, pointerType: 'touch', button: 0, clientX: 400, clientY: 200 };
        document.body.dispatchEvent(new PointerEvent('pointerdown', { ...event, pointerId: 3, isPrimary: true }));
        document.body.dispatchEvent(new PointerEvent('pointerdown', { ...event, pointerId: 4 }));
        document.body.dispatchEvent(new PointerEvent('pointerup', { ...event, pointerId: 4 }));
        document.body.dispatchEvent(new PointerEvent('pointerup', { ...event, pointerId: 3, isPrimary: true }));
      });
      assert.equal(await closed(), false, `${engine} ${dock}: multi-pointer gesture`);
      await page.evaluate(() => {
        const event = { bubbles: true, pointerType: 'touch', pointerId: 3, isPrimary: true, button: 0, clientX: 400, clientY: 200 };
        document.body.dispatchEvent(new PointerEvent('pointerdown', event));
        document.body.dispatchEvent(new PointerEvent('pointercancel', event));
        document.body.dispatchEvent(new PointerEvent('pointerup', event));
        document.body.dispatchEvent(new PointerEvent('pointerdown', { ...event, clientX: 1 }));
        document.body.dispatchEvent(new PointerEvent('pointerup', { ...event, clientX: 1 }));
      });
      assert.equal(await closed(), false, `${engine} ${dock}: cancelled and edge gestures`);
      await page.locator(fab).click();
      assert.equal(await closed(), true, `${engine} ${dock}: button toggle`);
      await open();
      await page.evaluate(() => {
        document.querySelector('#deck-layer').classList.add('live');
        document.body.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, pointerId: 5, pointerType: 'mouse', isPrimary: true, button: 0, clientX: 400 }));
        document.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, pointerId: 5, clientX: 400 }));
        document.querySelector('#deck-layer').classList.remove('live');
      });
      assert.equal(await closed(), false, `${engine} ${dock}: deck gesture surface`);
      await page.locator(fab).click();
    }
    assert.deepEqual(session.errors, []);
    await context.close();
    console.log(`${engine}: panel outside/inside/gesture/toggle passed`);
  } finally { await browser.close(); }
}

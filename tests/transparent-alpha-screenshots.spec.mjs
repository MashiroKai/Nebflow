import { test, expect } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { bootChat } from './_chat-face-harness.mjs';

const png = readFileSync(new URL('./fixtures/transparent-dark-line.png', import.meta.url));
const src = `data:image/png;base64,${png.toString('base64')}`;
const out = '/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/transparent-img-support/evidence/transparent-alpha';
mkdirSync(out, { recursive: true });
const history = [
  { type: 'user', text: '透明 PNG 测试夹具', timestamp: Date.now() - 1000 },
  { type: 'tool', label: 'Pop', summary: 'transparent fixture', timestamp: Date.now(), content: '___POP_JSON___' + JSON.stringify({
  items: [{ kind: 'image', name: 'transparent-dark-line.png', path: '/tmp/transparent-dark-line.png', src }],
  fileRefs: { ok: 1, failed: 0 }, warnings: [],
}) },
];

for (const [viewportName, viewport, isMobile] of [
  ['desktop', { width: 1280, height: 800 }, false],
  ['mobile', { width: 375, height: 812 }, true],
]) for (const theme of ['light', 'dark']) for (const face of ['chat', 'lightbox', 'canvas']) {
  test(`${viewportName}-${theme}-${face}`, async ({ browser }) => {
    const context = await browser.newContext({ viewport, isMobile, hasTouch: isMobile, deviceScaleFactor: isMobile ? 2 : 1, colorScheme: theme });
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', e => errors.push(String(e)));
    await bootChat(page, { history });
    if (face === 'chat') await page.evaluate(() => { document.body.classList.toggle('mobile-layer', innerWidth <= 400); });
    await page.route('**/api/nf-file**', route => route.fulfill({ status: 200, contentType: 'image/png', body: png }));
    if (face === 'chat' || face === 'lightbox') {
      await page.waitForSelector('#chat .pop-media-flat img');
      await page.waitForFunction(() => { const i = document.querySelector('#chat .pop-media-flat img'); return i?.complete && i.naturalWidth === 640; });
      if (face === 'lightbox') {
        await page.evaluate(async (imageSrc) => {
          const module = await import('/js/lightbox.js');
          module.openLightbox(imageSrc, 'transparent-dark-line.png');
        }, src);
        await page.waitForFunction(() => document.querySelector('.nf-lightbox.on .nf-lightbox-img.loaded'));
      }
    } else {
      await page.evaluate(() => window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: {
        id: 'transparent-alpha.png', itemType: 'image', title: 'transparent-dark-line.png',
        absPath: '/tmp/transparent-dark-line.png', path: '/tmp/transparent-dark-line.png', size: 4096,
      }})));
      await page.waitForFunction(() => { const i = document.querySelector('.canvas-image-viewport img'); return i?.complete && i.naturalWidth === 640; });
    }
    const reading = await page.evaluate(({ targetSel, matSel }) => {
      const target = document.querySelector(targetSel), mat = document.querySelector(matSel);
      const c = document.createElement('canvas'); c.width = target.naturalWidth; c.height = target.naturalHeight;
      const x = c.getContext('2d'); x.drawImage(target, 0, 0);
      const v = document.querySelector('.canvas-image-viewport')?.getBoundingClientRect();
      const r = target.getBoundingClientRect();
      return {
        imageAlpha: x.getImageData(10,10,1,1).data[3],
        mat: getComputedStyle(mat).backgroundColor,
        theme: matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light',
        dims: [innerWidth, innerHeight],
        centerDelta: v ? { x: +((r.left + r.width / 2) - (v.left + v.width / 2)).toFixed(2), y: +((r.top + r.height / 2) - (v.top + v.height / 2)).toFixed(2) } : null,
      };
    }, { targetSel: face === 'chat' ? '#chat .pop-media-flat img' : face === 'lightbox' ? '.nf-lightbox-img' : '.canvas-image-viewport img', matSel: face === 'lightbox' ? '.nf-lightbox-img' : face === 'canvas' ? '.canvas-image-viewport' : '#chat .pop-media-flat .pop-media' });
    console.log(`${viewportName}-${theme}-${face}`, JSON.stringify(reading));
    expect(reading.imageAlpha).toBe(0);
    await page.waitForTimeout(250); // let lightbox image opacity transition settle before evidence capture
    if (face === 'canvas') {
      expect(Math.abs(reading.centerDelta.x)).toBeLessThanOrEqual(1);
      expect(Math.abs(reading.centerDelta.y)).toBeLessThanOrEqual(1);
    }
    await page.screenshot({ path: `${out}/${viewportName}-${theme}-${face}.png` });
    if (face === 'chat') await page.locator('#chat .pop-media-flat img').click().catch(() => {});
    expect(errors).toEqual([]);
    await context.close();
  });
}

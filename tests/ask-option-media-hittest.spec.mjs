import { test, expect } from '@playwright/test';
import { bootChat, SID } from './_chat-face-harness.mjs';

test.use({ viewport: { width: Number(process.env.ASK_HIT_WIDTH || 390), height: 900 } });

const svg = color => `data:image/svg+xml,${encodeURIComponent(`<svg xmlns="http://www.w3.org/2000/svg" width="160" height="120"><rect width="160" height="120" fill="${color}"/></svg>`)}`;
const items = ['red', 'blue', 'green'].map((color, i) => ({ kind: 'image', src: svg(color), name: `${i}.svg` }));

async function setup(page, multiple) {
  let ws;
  await bootChat(page, { onWsRoute: route => { ws = route; } });
  ws.send(JSON.stringify({ type: 'askUser', sessionId: SID, requestId: 'hit-test', agentName: 'E2E', items: [{ question: 'Choose a design', multiple, allowOther: false, options: [{ label: 'Design option', description: 'Description text', preview: { type: 'media', items } }, { label: 'Other option' }] }] }));
  const card = page.locator('#chat .option-box .option-btn').first();
  await expect(card.locator('.pop-stack-view')).toBeVisible();
  return card;
}

for (const multiple of [false, true]) {
  for (const action of ['image', 'text', 'blank', 'control', 'swipe', 'expand', 'keyboard']) {
    test(`${multiple ? 'multi' : 'single'} ${action}`, async ({ page }, info) => {
      const card = await setup(page, multiple);
      const view = card.locator('.pop-stack-view');
      if (action === 'image' && !multiple && process.env.ASK_HIT_SHOTS) await page.screenshot({ path: `${process.env.ASK_HIT_SHOTS}/${process.env.ASK_HIT_LABEL || 'after'}-${info.project.name}-${page.viewportSize().width}.png` });
      if (action === 'image') {
        await view.click();
        await expect(page.locator('.nf-lightbox')).toBeVisible();
        await expect(card).not.toHaveClass(/picked/);
      } else if (action === 'text') {
        await card.locator('.option-text').click();
        await expect(card).toHaveClass(/picked/);
        await expect(page.locator('.nf-lightbox')).toHaveCount(0);
      } else if (action === 'blank') {
        await card.click({ position: { x: 5, y: 5 } });
        await expect(card).toHaveClass(/picked/);
      } else if (action === 'control') {
        if (multiple) await card.locator('.option-check').click();
        else await card.click({ position: { x: 5, y: 5 } });
        await expect(card).toHaveClass(/picked/);
      } else if (action === 'swipe') {
        const front = view.locator('.media-layer.layer-front');
        const before = await front.locator('img').getAttribute('src');
        const b = await view.boundingBox();
        await page.mouse.move(b.x + b.width * .8, b.y + b.height / 2);
        await page.mouse.down();
        await page.mouse.move(b.x + b.width * .2, b.y + b.height / 2, { steps: 8 });
        await page.mouse.up();
        await expect(front.locator('img')).not.toHaveAttribute('src', before);
        await expect(card).not.toHaveClass(/picked/);
      } else if (action === 'expand') {
        await card.locator('.pop-stack-area > .pill').click();
        await expect(card.locator('.pop-expand-view')).toBeVisible();
        await expect(card).not.toHaveClass(/picked/);
      } else {
        await card.focus();
        await page.keyboard.press('Enter');
        await expect(card).toHaveClass(/picked/);
        await view.focus();
        await page.keyboard.press('Enter');
        await expect(page.locator('.nf-lightbox')).toBeVisible();
      }
      if (action === 'text') {
        const geometry = await card.locator('.option-text').evaluate(el => {
          const r = el.getBoundingClientRect(), p = el.parentElement.getBoundingClientRect();
          return { left: r.left, right: r.right, top: r.top, bottom: r.bottom, parentLeft: p.left, parentRight: p.right, parentTop: p.top, parentBottom: p.bottom, scrollWidth: el.scrollWidth, clientWidth: el.clientWidth, viewport: innerWidth };
        });
        expect(geometry.left).toBeGreaterThanOrEqual(0);
        expect(geometry.right).toBeLessThanOrEqual(geometry.viewport);
        expect(geometry.left).toBeGreaterThanOrEqual(geometry.parentLeft - 1);
        expect(geometry.right).toBeLessThanOrEqual(geometry.parentRight + 1);
        expect(geometry.top).toBeGreaterThanOrEqual(geometry.parentTop - 1);
        expect(geometry.bottom).toBeLessThanOrEqual(geometry.parentBottom + 1);
        expect(geometry.scrollWidth).toBeLessThanOrEqual(geometry.clientWidth + 1);
      }
    });
  }
}

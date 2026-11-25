import { test, expect } from '@playwright/test';
import { bootChat, SID } from './_chat-face-harness.mjs';

test.use({ viewport: { width: Number(process.env.ASK_HIT_WIDTH || 390), height: 900 }, colorScheme: process.env.ASK_HIT_SCHEME || 'light' });
test.setTimeout(30000);

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

async function clickVisibleBlank(card) {
  const point = await card.evaluate(el => {
    const r = el.getBoundingClientRect();
    const occupied = [...el.querySelectorAll('.option-text, .option-check, .pop-stack-view, .pill')].map(e => e.getBoundingClientRect());
    for (let y = 2; y < r.height - 2; y += 2) for (let x = 2; x < r.width - 2; x += 2) {
      const px = r.left + x, py = r.top + y;
      if (document.elementFromPoint(px, py) !== el) continue;
      if (occupied.some(b => px >= b.left && px <= b.right && py >= b.top && py <= b.bottom)) continue;
      return { x, y };
    }
    throw new Error('未找到形状内的空白点击点');
  });
  console.log('visible blank', JSON.stringify(point));
  await card.click({ position: point });
}

for (const multiple of [false, true]) {
  for (const action of ['image', 'text', 'blank', 'control', 'swipe', 'touch', 'expand', 'keyboard', 'geometry']) {
    test(`${multiple ? 'multi' : 'single'} ${action}`, async ({ page }, info) => {
      const card = await setup(page, multiple);
      const view = card.locator('.pop-stack-view');
      await view.scrollIntoViewIfNeeded();
      if (action === 'image') {
        await view.click();
        if (!multiple && process.env.ASK_HIT_SHOTS) {
          if (process.env.ASK_HIT_LABEL !== 'before') await expect(page.locator('.nf-lightbox')).toBeVisible();
          await page.screenshot({ path: `${process.env.ASK_HIT_SHOTS}/${process.env.ASK_HIT_LABEL || 'after'}-${info.project.name}-${page.viewportSize().width}-${process.env.ASK_HIT_SCHEME || 'light'}.png`, animations: 'disabled' });
        }
        await expect(page.locator('.nf-lightbox')).toBeVisible();
        await expect(card).not.toHaveClass(/picked/);
      } else if (action === 'text') {
        await card.locator('.option-text').click();
        await expect(card).toHaveClass(/picked/);
        await expect(page.locator('.nf-lightbox')).toHaveCount(0);
      } else if (action === 'blank') {
        await clickVisibleBlank(card);
        await expect(card).toHaveClass(/picked/);
      } else if (action === 'control') {
        if (multiple) await card.locator('.option-check').click();
        else await clickVisibleBlank(card);
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
      } else if (action === 'touch') {
        const front = view.locator('.media-layer.layer-front');
        const before = await front.locator('img').getAttribute('src');
        const b = await view.boundingBox();
        await view.dispatchEvent('pointerdown', { pointerType: 'touch', pointerId: 42, isPrimary: true, clientX: b.x + b.width * .8, clientY: b.y + b.height / 2, buttons: 1 });
        await view.dispatchEvent('pointermove', { pointerType: 'touch', pointerId: 42, isPrimary: true, clientX: b.x + b.width * .2, clientY: b.y + b.height / 2, buttons: 1 });
        await view.dispatchEvent('pointerup', { pointerType: 'touch', pointerId: 42, isPrimary: true, clientX: b.x + b.width * .2, clientY: b.y + b.height / 2 });
        await expect(front.locator('img')).not.toHaveAttribute('src', before);
        await expect(card).not.toHaveClass(/picked/);
      } else if (action === 'expand') {
        await card.locator('.pop-stack-area > .pill').click();
        await expect(card.locator('.pop-expand-view')).toBeVisible();
        await expect(card).not.toHaveClass(/picked/);
      } else if (action === 'keyboard') {
        await card.focus();
        await expect(card).toBeFocused();
        await page.keyboard.press('Enter');
        await expect(card).toHaveClass(/picked/);
        await card.focus();
        for (let i = 0; i < 5 && !(await view.evaluate(el => el === document.activeElement)); i++) await page.keyboard.press('Tab');
        await expect(view).toBeFocused();
        const focus = await view.evaluate(el => { const s = getComputedStyle(el); return { outline: s.outlineStyle, width: s.outlineWidth, shadow: s.boxShadow }; });
        expect(focus.outline !== 'none' && focus.outline !== 'hidden' && focus.width !== '0px' || focus.shadow !== 'none').toBeTruthy();
        await page.keyboard.press('Enter');
        await expect(page.locator('.nf-lightbox')).toBeVisible();
      }
      if (action === 'geometry') {
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

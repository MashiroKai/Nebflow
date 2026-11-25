// Full product styles, real todo renderer, synthetic data; no server or production connection.
import { test, expect } from '@playwright/test';
import { join } from 'node:path';
import { bootTodoFace, openPanel } from './_todo-face-harness.mjs';

const items = [{ id: 'contrast', tagId: 'tag-work', name: '决策待办标题', detail: '对比度回归', priority: 1, createdAt: 1, decision: { question: '如何处理？', options: [{ id: 'a', title: '选项甲' }] } }];
for (const scheme of ['light', 'dark']) {
  test(`决策标题实际背景对比度 ${scheme}`, async ({ browser }) => {
    const context = await browser.newContext({ viewport: { width: 1280, height: 800 }, colorScheme: scheme });
    const page = await context.newPage();
    await bootTodoFace(page, { items });
    await openPanel(page);
    const title = page.locator('button.tfl-row-title').first();
    await expect(title).toBeVisible();
    if (process.env.CONTRAST_SHOTS) await page.screenshot({ path: join(process.env.CONTRAST_SHOTS, `${process.env.CONTRAST_STAGE}-${scheme}-desktop.png`) });
    const result = await title.evaluate(el => {
      const parse = s => s.match(/[\d.]+/g).map(Number);
      const mix = (top, bottom) => { const a = top[3] ?? 1; return top.slice(0, 3).map((v, i) => v * a + bottom[i] * (1 - a)); };
      const layers = []; for (let n = el; n; n = n.parentElement) layers.push(parse(getComputedStyle(n).backgroundColor));
      let bg = [255, 255, 255]; for (const layer of layers.reverse()) bg = mix(layer, bg);
      const fg = mix(parse(getComputedStyle(el).color), bg);
      const lum = c => c.map(v => v / 255).map(v => v <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4).reduce((a, v, i) => a + v * [.2126, .7152, .0722][i], 0);
      const a = lum(fg), b = lum(bg);
      return { fg, bg, ratio: (Math.max(a, b) + .05) / (Math.min(a, b) + .05), background: getComputedStyle(el).backgroundColor };
    });
    console.log(scheme, JSON.stringify(result));
    expect(result.ratio).toBeGreaterThanOrEqual(4.5);
    await context.close();
  });
  test(`手机待办视图截图 ${scheme}`, async ({ browser }) => {
    const context = await browser.newContext({ viewport: { width: 375, height: 812 }, colorScheme: scheme, hasTouch: true, isMobile: true });
    const page = await context.newPage();
    await bootTodoFace(page, { items, mobile: true });
    await page.evaluate(async () => (await import('/js/mobile/navController.js')).pushPage('todo-apple-list'));
    const list = page.locator('.mobile-page.live[data-page="todo-apple-list"]');
    await expect(list.getByText('决策待办标题')).toBeVisible();
    if (process.env.CONTRAST_SHOTS) await page.screenshot({ path: join(process.env.CONTRAST_SHOTS, `${process.env.CONTRAST_STAGE}-${scheme}-mobile.png`) });
    await context.close();
  });
}

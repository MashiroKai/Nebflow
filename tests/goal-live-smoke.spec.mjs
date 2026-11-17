import { test, expect } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { establishSession } from './helpers/session-rail.mjs';

const port = process.env.GOAL_PORT || '18163';
if (!['18163', '18164'].includes(port)) throw new Error('This fixture only targets its owned isolated instances');
const base = `http://127.0.0.1:${port}`;
const home = '/tmp/qa-stand-goal-fwb43m2o';
const token = JSON.parse(readFileSync(join(home, port === '18163' ? 'app/auth.json' : `app-${port}/auth.json`), 'utf8'));
const shots = join(process.cwd(), '.zcode/goal-shots');
mkdirSync(shots, { recursive: true });

for (const mobile of [true, false]) {
  for (const theme of ['light', 'dark']) {
    test(`live ${mobile ? 'mobile' : 'desktop'} ${theme} navigation and settings`, async ({ browser }) => {
      const context = await browser.newContext({
        viewport: mobile ? { width: 390, height: 844 } : { width: 1280, height: 800 },
        colorScheme: theme, hasTouch: mobile, reducedMotion: 'reduce',
      });
      const page = await context.newPage();
      const errors = [];
      page.on('pageerror', error => errors.push(error.message));
      try {
        await establishSession(base, token, page, { waitUntil: 'domcontentloaded' });
        await expect(page.locator('#input')).toBeVisible();
        await expect(page.locator('#takeover-overlay')).not.toBeVisible();
        if (mobile) {
          await expect(page.locator('body')).toHaveClass(/mobile-layer/);
          await page.getByRole('tab', { name: '工作台', exact: true }).click();
          await expect(page.getByRole('tab', { name: '工作台', exact: true })).toHaveAttribute('aria-selected', 'true');
        } else {
          await page.locator('#settings-btn').click();
          await expect(page.getByRole('button', { name: '5 类会话模型', exact: true })).toBeVisible();
        }
        await page.screenshot({ path: join(shots, `${port}-${mobile ? 'mobile-workbench' : 'desktop-settings'}-${theme}.png`) });
        const metrics = await page.evaluate(() => ({ width: innerWidth, body: document.body.scrollWidth, root: document.documentElement.scrollWidth }));
        expect(metrics.body).toBeLessThanOrEqual(metrics.width + 1);
        expect(metrics.root).toBeLessThanOrEqual(metrics.width + 1);
        expect(errors).toEqual([]);
        if (mobile) {
          await page.getByRole('tab', { name: '对话', exact: true }).click();
          await expect(page.locator('#input')).toBeVisible();
          await page.screenshot({ path: join(shots, `mobile-${theme}-chat.png`) });
        }
      } finally {
        await context.close();
      }
    });
  }
}

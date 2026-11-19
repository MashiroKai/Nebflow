import { test, expect } from '@playwright/test';

const SOURCE = new URL('../src/main/resources/web/js/staleTabHint.js', import.meta.url);

test('refresh banner bypasses the controlling service worker and preserves the current URL', async ({ page }) => {
  await page.route('http://localhost:1/**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname === '/js/staleTabHint.js') {
      const { readFile } = await import('node:fs/promises');
      return route.fulfill({
        status: 200,
        contentType: 'text/javascript',
        body: await readFile(SOURCE, 'utf8'),
      });
    }
    return route.fulfill({
      status: 200,
      contentType: 'text/html',
      body: '<!doctype html><html><body><script type="module">import { showStaleTabHint } from "/js/staleTabHint.js"; showStaleTabHint();</script></body></html>',
    });
  });

  await page.goto('http://localhost:1/?keep=yes#section');
  const refresh = page.getByRole('button', { name: '刷新页面' });
  await expect(refresh).toBeVisible();
  await Promise.all([
    page.waitForURL('http://localhost:1/?keep=yes&nfsw=0#section'),
    refresh.click(),
  ]);
});

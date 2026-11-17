import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
let server, base;
test.beforeAll(async () => {
  const probe = createServer();
  await new Promise(r => probe.listen(0, '127.0.0.1', r));
  const port = probe.address().port;
  await new Promise(r => probe.close(r));
  base = `http://127.0.0.1:${port}`;
  server = spawn('python3', ['-m', 'http.server', String(port), '--bind', '127.0.0.1', '--directory', root], { stdio: 'ignore' });
  await expect.poll(async () => { try { return (await fetch(base + '/tests/fixtures/pop-artifacts/harness.html')).status; } catch { return 0; } }).toBe(200);
});
test.afterAll(() => server?.kill());

test('keyboard wraps in both directions and queues rapid steps', async ({ page }) => {
  await page.goto(base + '/tests/fixtures/pop-artifacts/harness.html');
  await page.waitForFunction(() => window.__ready === true);
  // Fixture setup only; all navigation below uses browser keyboard input.
  await page.evaluate(() => { document.querySelector('#popup-chat').style.display = 'none'; window.__popStackMixedRatios(); });
  const view = page.locator('#chat .pop-stack-view');
  await expect(view.locator('.media-layer')).toHaveCount(3);
  const current = () => view.locator('.media-layer').evaluateAll(ls => ls.findIndex(l => l.classList.contains('layer-front')));
  await view.focus();
  expect(await current()).toBe(0);
  await page.keyboard.press('ArrowLeft');
  await expect.poll(current).toBe(2);
  await page.keyboard.press('ArrowRight');
  await expect.poll(current).toBe(0);
  await page.keyboard.press('ArrowRight');
  await expect.poll(current).toBe(1);
  await page.keyboard.press('ArrowLeft');
  await expect.poll(current).toBe(0);
  await page.keyboard.press('ArrowRight');
  await page.keyboard.press('ArrowRight');
  await expect.poll(current).toBe(2);
});

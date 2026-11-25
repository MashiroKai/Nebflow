import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
let server, port;
test.beforeAll(async () => {
  port = await new Promise(resolve => { const s = net.createServer().listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => resolve(p)); }); });
  server = spawn('python3', ['-m', 'http.server', String(port), '--bind', '127.0.0.1', '--directory', root], { stdio: 'ignore' });
});
test.afterAll(() => server?.kill('SIGTERM'));
test('正文边界前不可见，收口整条弹出且多段互不拼接', async ({ page }) => {
  await expect(async () => { const r = await page.goto(`http://127.0.0.1:${port}/tests/fixtures/main-stream-render/harness.html`); expect(r.ok()).toBeTruthy(); }).toPass();
  await page.waitForFunction(() => window.__ready);
  await page.evaluate(() => window.__aiStream(['第一段 ', '完整正文']));
  await expect(page.locator('#chat .row.ai:not(.thinking-row)')).not.toContainText('第一段');
  await page.evaluate(() => window.__aiStreamFinish());
  const bubbles = page.locator('#chat .row.ai:not(.thinking-row) .bubble.ai');
  await expect(bubbles.last()).toContainText('第一段 完整正文');
  await expect(bubbles.last()).toHaveClass(/nf-pop/);
  await page.evaluate(async () => { const m = await import('/src/main/resources/web/js/chat.js'); m.appendAiText('第二段'); m.finishAi(); });
  await expect(bubbles).toHaveCount(2);
  await expect(bubbles.nth(1)).toContainText('第二段');
});

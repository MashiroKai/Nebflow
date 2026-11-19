// capture-stack-fan.mjs — 修前/修后对照截图：同一三异比例图 Pop 卡栈，
// 桌面 + 移动两视口。用法: node tests/capture-stack-fan.mjs <outDir> <tag>
import { chromium } from 'playwright';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/pop-artifacts/harness.html';
const [outDir, tag] = process.argv.slice(2);
if (!outDir || !tag) { console.error('usage: node tests/capture-stack-fan.mjs <outDir> <tag>'); process.exit(1); }
fs.mkdirSync(outDir, { recursive: true });

const freePort = () => new Promise((resolve, reject) => {
  const srv = net.createServer();
  srv.listen(0, '127.0.0.1', () => { const p = srv.address().port; srv.close(() => resolve(p)); });
  srv.on('error', reject);
});
const port = await freePort();
const server = spawn('python3', ['-m', 'http.server', String(port), '--bind', '127.0.0.1', '--directory', REPO_ROOT], { stdio: 'ignore' });
const up = async () => { for (let i = 0; i < 60; i++) { try { const r = await fetch(`http://127.0.0.1:${port}${HARNESS_PATH}`); if (r.ok) return; } catch {} await new Promise(r => setTimeout(r, 100)); } throw new Error('server never came up'); };
await up();

const browser = await chromium.launch();
const errors = [];
for (const vp of [{ name: 'desktop', w: 1200, h: 900 }, { name: 'mobile', w: 390, h: 844 }]) {
  const context = await browser.newContext({ viewport: { width: vp.w, height: vp.h } });
  const page = await context.newPage();
  page.on('pageerror', e => errors.push(`${vp.name}: ${e}`));
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction(() => window.__ready === true);
  await page.evaluate(() => window.__popStackMixedRatios());
  await page.waitForFunction(
    () => document.querySelectorAll('#chat .pop-stack-view .media-layer').length === 3,
    null, { timeout: 10000 },
  );
  await page.waitForTimeout(700); // bytes decode + transitions settle
  const row = page.locator('#chat > .row.pop-artifact');
  await row.scrollIntoViewIfNeeded();
  await page.waitForTimeout(200);
  await row.screenshot({ path: path.join(outDir, `${tag}-${vp.name}.png`) });
  await context.close();
  console.log(`NEBFLOW_SHOT:${path.join(outDir, `${tag}-${vp.name}.png`)}`);
}
await browser.close();
server.kill('SIGTERM');
if (errors.length) { console.error('page errors:', errors); process.exit(1); }
console.log('capture done');

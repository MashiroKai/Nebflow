import { chromium } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve, extname } from 'node:path';
import assert from 'node:assert/strict';

const root = resolve('src/main/resources/web');
const server = createServer(async (req, res) => {
  try {
    const path = resolve(root, '.' + new URL(req.url, 'http://localhost').pathname);
    if (!path.startsWith(root + '/')) throw Error('path');
    const bytes = await readFile(path);
    res.setHeader('Content-Type', ({ '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' })[extname(path)] || 'text/plain');
    res.end(bytes);
  } catch { res.writeHead(404).end(); }
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const port = server.address().port;
const browser = await chromium.launch({ headless: true });
try {
  for (const width of [390, 1280]) for (const theme of ['light', 'dark']) {
    const page = await browser.newPage({ viewport: { width, height: 850 }, colorScheme: theme });
    await page.goto(`http://127.0.0.1:${port}/css/lightbox.css`);
    await page.setContent(`<html><head><link rel="stylesheet" href="/css/lightbox.css"></head><body><div class="canvas-md-viewer"><div class="nf-mermaid"><div class="nf-mermaid-body"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 100" width="200" height="100"><rect width="200" height="100" fill="red"/></svg></div></div></div></body></html>`);
    await page.evaluate(async () => { const m = await import('/js/lightbox.js'); m.initLightbox(); });
    await page.locator('.nf-mermaid svg').click();
    const overlay = page.locator('.nf-lightbox');
    await overlay.waitFor();
    assert.equal(await overlay.getAttribute('data-nf-lb-gallery'), 'on', `${width} ${theme}: image gallery`);
    assert.equal(await overlay.getAttribute('data-nf-lb-mode'), 'img');
    assert.equal(await overlay.locator('.nf-lightbox-close').isVisible(), true);
    assert.equal(await overlay.locator('.nf-lightbox-opentab').isVisible(), true);
    assert.equal(await overlay.locator('.nf-lightbox-img').isVisible(), true);
    assert.equal(await overlay.locator('.nf-lightbox-bar:visible').count(), 0);
    await page.waitForFunction(() => document.querySelector('.nf-lightbox-img').naturalWidth > 0);
    const download = page.waitForEvent('download');
    await overlay.locator('.nf-lightbox-opentab').click();
    assert.equal((await download).suggestedFilename(), 'diagram.svg');
    console.log(`PASS ${width} ${theme}`);
    await page.close();
  }
} finally { await browser.close(); server.close(); }

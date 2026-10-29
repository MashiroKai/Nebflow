#!/usr/bin/env node
// errorfrozen-capture.mjs — before/after evidence capture for the error/frozen
// dialog minimalization batch (author directive 2026-10-29 13:51).
//
// Drives tests/fixtures/errorfrozen-minimal-harness.html (real production CSS
// chain + real /js renderers) and captures each surface as a PNG, in BOTH
// color schemes, into the batch evidence dir:
//
//   node tests/errorfrozen-capture.mjs <outDir> [--base http://127.0.0.1:8199]
//
// Shots per theme (<theme> = light | dark):
//   <theme>-overview.png          full gallery (?state=all)
//   <theme>-bar-frozen.png        #input-bar in schedule-freeze state
//   <theme>-bar-error.png         #input-bar in error-recovery state (+strip)
//   <theme>-escalation-card.png   .error-escalation-card
//   <theme>-tiles.png             frozen / frozen.error sub-agent footers
//   <theme>-toast-error.png       .nebflow-toast-error
//   <theme>-gate-banner.png       #stand-session-gate banner
//
// Self-contained: spins up its own static server (web dir + tests/), never
// touches a running instance.

import { chromium } from 'playwright-core';
import { readFileSync } from 'node:fs';
import { mkdirSync } from 'node:fs';
import { join, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createServer } from 'node:http';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = process.argv[2] || join(HERE, '..', 'evidence', 'errorfrozen-capture-tmp');
mkdirSync(OUT, { recursive: true });

const MIME = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.mjs': 'text/javascript' };
function startServer() {
  return new Promise((resolve) => {
    const server = createServer(async (req, res) => {
      try {
        let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
        if (p === '/' || p === '/harness') p = '/tests/fixtures/errorfrozen-minimal-harness.html';
        else if (p.startsWith('/fixtures/')) p = '/tests' + p;
        const file = (p.startsWith('/js/') || p.startsWith('/css/') || p.startsWith('/vendor/'))
          ? join(WEB, p) : join(HERE, '..', p);
        const data = readFileSync(file);
        res.writeHead(200, { 'content-type': MIME[extname(file)] || 'application/octet-stream' });
        res.end(data);
      } catch {
        res.writeHead(404); res.end('not found');
      }
    });
    server.listen(0, '127.0.0.1', () => resolve({ server, port: server.address().port }));
  });
}

const { server, port } = await startServer();
const base = `http://127.0.0.1:${port}`;
const browser = await chromium.launch();

async function captureTheme(theme) {
  const context = await browser.newContext({
    viewport: { width: 900, height: 1150 },
    deviceScaleFactor: 2,
    colorScheme: theme === 'dark' ? 'dark' : 'light',
  });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message)));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });

  const shot = async (name, locatorOpts) => {
    await page.screenshot({ path: join(OUT, `${theme}-${name}.png`), ...locatorOpts });
    console.log(`shot ${theme}-${name}.png`);
  };

  // Overview gallery: every surface on one page.
  await page.goto(`${base}/harness?state=all`);
  await page.waitForFunction('window.__ef.ready === true', null, { timeout: 15000 });
  await page.waitForTimeout(350); // transition settle
  await shot('overview', { fullPage: true });

  // Bar states (one real #input-bar, states toggled via the harness helper).
  await page.evaluate(() => window.__ef.applyBarState('frozen'));
  await page.waitForTimeout(420); // 300ms material transition + slack
  await shot('bar-frozen', { clip: await page.locator('#stage-bar').boundingBox() });

  await page.evaluate(() => window.__ef.applyBarState('error'));
  await page.waitForTimeout(420);
  await shot('bar-error', { clip: await page.locator('#stage-bar').boundingBox() });

  // Escalation card.
  await shot('escalation-card', { clip: await page.locator('.error-escalation-card').boundingBox() });

  // Tiles.
  await shot('tiles', { clip: await page.locator('#sec-tiles .tile-row').boundingBox() });

  // Error toast (re-fire right before the shot — auto-dismisses at ~4s).
  await page.evaluate(() => document.querySelectorAll('.nebflow-toast').forEach((t) => t.remove()));
  await page.evaluate(() => window.__ef.fireToast());
  await page.waitForSelector('.nebflow-toast-error', { timeout: 3000 });
  await page.waitForTimeout(320); // .show transition
  await shot('toast-error', { clip: await page.locator('.nebflow-toast-error').boundingBox() });
  await page.evaluate(() => document.querySelectorAll('.nebflow-toast').forEach((t) => t.remove()));

  // Session-gate banner (rendered at boot by the harness).
  await page.waitForSelector('#stand-session-gate', { timeout: 3000 });
  await shot('gate-banner', { clip: await page.locator('#stand-session-gate').boundingBox() });

  if (errors.length) console.log(`[${theme}] page errors:`, errors);
  await context.close();
  return errors;
}

const lightErrors = await captureTheme('light');
const darkErrors = await captureTheme('dark');

await browser.close();
server.close();
const allErrors = [...lightErrors, ...darkErrors];
if (allErrors.length) {
  console.error('CAPTURE HAD PAGE ERRORS — inspect before trusting shots');
  process.exit(2);
}
console.log(`\nevidence dir: ${OUT}`);

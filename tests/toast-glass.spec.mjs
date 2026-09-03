// toast-glass.spec.mjs — acceptance spec for the 2026-09-03 toast glass
// redesign (author ruling: .glass-control material, accent strip removed,
// type semantics via leading glyph icon).
//
// Loads the REAL production chain via tests/fixtures/toast-glass-harness.html:
//   real stylesheets base.css + modal.css + sapphire.css (production order)
//   real /js/modal.js module → showToast() driven directly.
//
// Assertions (per author ruling):
//   A1  per type (error/info/success): .nebflow-toast-{type} present, icon
//       span present with the type color, NO 3px left accent strip
//       (computed border-left-width must be 1px), backdrop-filter contains
//       blur, auto-removed within ~4.3s (4000ms hide + 300ms remove + slack).
//   A2  stacking: 3 toasts fired back-to-back — all render, all removed in
//       time, no console/page errors.
//   A3  .nebflow-toast-link variant: the activityBar popupBlockedFallback
//       assembly (msg span + a.glass-control.nebflow-toast-link appended to
//       a .nebflow-toast) keeps its computed styles (no regression).
//
// Self-contained: spins up its own static server on an ephemeral port serving
// src/main/resources/web + tests/ (never touches the running Nebflow instance).
// Run: node tests/toast-glass.spec.mjs

import { chromium } from 'playwright-core';
import { readFileSync } from 'node:fs';
import { join, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createServer } from 'node:http';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const MIME = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html' };

/** Static server for the web dir + /harness fixture page. */
function startServer() {
  return new Promise((resolve) => {
    const server = createServer(async (req, res) => {
      try {
        let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
        if (p === '/' || p === '/harness') p = '/tests/fixtures/toast-glass-harness.html';
        else if (p.startsWith('/fixtures/')) p = '/tests' + p;
        const file = (p.startsWith('/js/') || p.startsWith('/css/')) ? join(WEB, p) : join(HERE, '..', p);
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

const checks = [];
function check(name, ok, extra = '') {
  checks.push({ name, ok });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
}

const { server, port } = await startServer();
const base = `http://127.0.0.1:${port}`;

const browser = await chromium.launch();
const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
const page = await context.newPage();
const consoleErrors = [];
page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
page.on('pageerror', (e) => consoleErrors.push(String(e.message)));

await page.goto(base + '/harness');
await page.waitForFunction('window.__toastTest.ready === true', null, { timeout: 10000 });

// Computed-style snapshot of the toast icon + container border/frost.
const ICON_RGB = {
  error: 'rgb(244, 67, 54)',   // #f44336 (author-ruled)
  info: 'rgb(7, 193, 96)',     // var(--color-primary) = #07c160 (base.css)
  success: 'rgb(76, 175, 80)', // var(--color-success) = #4caf50 (base.css)
};

// ── A1: per-type fired one at a time, each with its own lifecycle window ──
for (const type of ['error', 'info', 'success']) {
  await page.evaluate((t) => window.__toastTest.show(`测试通知 ${t}`, t), type);

  const toastSel = `.nebflow-toast-${type}`;
  await page.waitForSelector(toastSel, { timeout: 3000 });

  const snap = await page.evaluate((sel) => {
    const el = document.querySelector(sel);
    if (!el) return null;
    const icon = el.querySelector('.nebflow-toast-icon');
    const cs = getComputedStyle(el);
    return {
      iconPresent: !!icon,
      iconGlyph: icon ? icon.textContent : '',
      iconColor: icon ? getComputedStyle(icon).color : '',
      borderLeftWidth: cs.borderLeftWidth,
      borderLeftStyle: cs.borderLeftStyle,
      backdropFilter: cs.backdropFilter || cs.webkitBackdropFilter || '',
      position: cs.position,
    };
  }, toastSel);

  check(`A1 ${type}: toast renders with .nebflow-toast-${type}`, !!snap);
  check(`A1 ${type}: icon span present (glyph "${snap?.iconGlyph}")`, !!snap?.iconPresent);
  check(`A1 ${type}: icon computed color = type color (${ICON_RGB[type]})`,
    snap?.iconColor === ICON_RGB[type], `got ${snap?.iconColor}`);
  check(`A1 ${type}: no 3px left accent strip (border-left-width = ${snap?.borderLeftWidth})`,
    snap?.borderLeftWidth === '1px' && snap?.borderLeftStyle === 'solid',
    `border-left: ${snap?.borderLeftWidth} ${snap?.borderLeftStyle}`);
  check(`A1 ${type}: backdrop-filter contains blur (${snap?.backdropFilter})`,
    /blur\(/.test(snap?.backdropFilter || ''), snap?.backdropFilter);
  check(`A1 ${type}: stays fixed bottom-right overlay (position: fixed)`,
    snap?.position === 'fixed');

  // Auto-dismiss: 4000ms hide + 300ms remove → gone within ~4.3s (5s slack).
  const removed = await page.waitForFunction(
    (sel) => !document.querySelector(sel), toastSel, { timeout: 5000 },
  ).then(() => true).catch(() => false);
  check(`A1 ${type}: auto-removed within ~4.3s (no reload)`, removed);
}

// ── A2: stacking — 3 fired back-to-back, all render, all removed, no errors ──
await page.evaluate(() => {
  window.__toastTest.show('堆叠通知 一', 'success');
  window.__toastTest.show('堆叠通知 二', 'error');
  window.__toastTest.show('堆叠通知 三', 'info');
});
await page.waitForFunction(
  () => document.querySelectorAll('.nebflow-toast').length === 3,
  null, { timeout: 3000 },
);
check('A2: all 3 stacked toasts render simultaneously', true);

const stackTypes = await page.evaluate(() =>
  [...document.querySelectorAll('.nebflow-toast')].map((el) => el.className));
check('A2: stacked toasts keep their per-type classes',
  stackTypes.every((c) => /nebflow-toast-(success|error|info)/.test(c)),
  stackTypes.join(' | '));

const allGone = await page.waitForFunction(
  () => document.querySelectorAll('.nebflow-toast').length === 0,
  null, { timeout: 5500 },
).then(() => true).catch(() => false);
check('A2: all stacked toasts auto-removed within ~4.3s', allGone);

// ── A3: .nebflow-toast-link variant (activityBar popupBlockedFallback shape) ──
const link = await page.evaluate(() => {
  // Same assembly path as activityBar.js popupBlockedFallback (not showToast),
  // sharing the .nebflow-toast styles under test.
  const toast = document.createElement('div');
  toast.className = 'nebflow-toast nebflow-toast-info';
  const msg = document.createElement('span');
  msg.textContent = '弹窗被拦截';
  const a = document.createElement('a');
  a.className = 'glass-control nebflow-toast-link';
  a.href = 'https://example.com/';
  a.target = '_blank';
  a.rel = 'noopener';
  a.textContent = '打开页面';
  toast.append(msg, a);
  document.body.appendChild(toast);
  requestAnimationFrame(() => toast.classList.add('show'));
  const cs = getComputedStyle(a);
  const tcs = getComputedStyle(toast);
  return {
    display: cs.display,
    cursorVal: cs.cursor,
    glassBg: tcs.background.slice(0, 60),
    toastEl: toast,
  };
});
check('A3: link stays inline-block with pointer cursor',
  link.display === 'inline-block' && link.cursorVal === 'pointer',
  `display=${link.display} cursor=${link.cursorVal}`);

// link toast also auto-styles as glass (material regression check on the shared base class)
const linkToastFrost = await page.evaluate(() => {
  const el = [...document.querySelectorAll('.nebflow-toast')].find((t) => t.querySelector('.nebflow-toast-link'));
  if (!el) return null;
  return getComputedStyle(el).backdropFilter || '';
});
check('A3: link-bearing toast still gets the glass material (backdrop blur)',
  /blur\(/.test(linkToastFrost || ''), linkToastFrost);

await page.evaluate(() => {
  [...document.querySelectorAll('.nebflow-toast')].forEach((t) => t.remove());
});

check('A2+: no console/page errors across the whole run',
  consoleErrors.length === 0, consoleErrors.join('; '));

await browser.close();
server.close();

const failed = checks.filter((c) => !c.ok);
console.log(`\n${checks.length - failed.length}/${checks.length} passed`);
process.exit(failed.length ? 1 : 0);

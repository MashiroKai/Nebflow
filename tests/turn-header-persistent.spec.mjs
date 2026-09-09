// turn-header-persistent.spec.mjs — 2026-09-08 author ruling (turnHeader 每
// 轮常显与点击展开修复): EVERY turn keeps its own ✻ header, and clicking ANY
// header (old or latest) toggles that turn's tuck/untuck.
//
// Replaces tests/turn-banner-dedupe.spec.mjs (the 2026-09-04 "only the latest
// header visible" dedupe was REMOVED 2026-09-08 — hiding the older headers
// made them unclickable, the root cause of the 「旧轮点不开」 regression).
//
// Live path: __liveTurn() + __postClosureTurn() (two collapsed turns).
// History path: restoreFromBackendHistory replay (two collapsed turns).
// E10 search-expand keeps working and is preserved by expandGroupContaining.
//
// Drives the REAL render modules (chatView.js / chat.js / turnGroup.js /
// persistence.js) in the static harness page
// (tests/fixtures/turn-collapse/harness.html). One throwaway static file
// server is spawned by this spec and killed in afterAll (never the 8080 host).
//
// Run: npx playwright test tests/turn-header-persistent.spec.mjs

import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/turn-collapse/harness.html';
const SHOT_DIR = process.env.NB_HEADER_SHOT_DIR ||
  path.join(os.homedir(), '.nebflow', 'docs', 'Nebflow');

let port;
const servers = [];

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.listen(0, '127.0.0.1', () => {
      const p = srv.address().port;
      srv.close(() => resolve(p));
    });
    srv.on('error', reject);
  });
}

function startStaticServer(p, dir) {
  return spawn('python3', ['-m', 'http.server', String(p), '--bind', '127.0.0.1', '--directory', dir], {
    stdio: 'ignore',
  });
}

async function waitUntilUp(url, tries = 50) {
  for (let i = 0; i < tries; i++) {
    try {
      const res = await fetch(url);
      if (res.ok) return;
    } catch { /* not up yet */ }
    await new Promise(r => setTimeout(r, 100));
  }
  throw new Error(`static server never came up at ${url}`);
}

test.beforeAll(async () => {
  fs.mkdirSync(SHOT_DIR, { recursive: true });
  port = await freePort();
  expect(port).not.toBe(8080); // host discipline: never touch the host port
  servers.push(startStaticServer(port, REPO_ROOT));
  await waitUntilUp(`http://127.0.0.1:${port}${HARNESS_PATH}`);
});

test.afterAll(async () => {
  for (const s of servers) s.kill('SIGTERM');
  servers.length = 0;
});

async function newPage(browser, opts = {}) {
  const context = await browser.newContext({
    locale: 'zh-CN',
    viewport: { width: 900, height: 1000 },
    ...opts,
  });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', (err) => pageErrors.push(err.message));
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction(() => window.__ready === true, null, { timeout: 15000 });
  return { context, page, pageErrors };
}

/** Per-header state: text head, visibility, turnState, and how many of that
 *  turn's rows are currently tucked. */
async function headerStates(page) {
  return page.evaluate(() => {
    const chat = document.getElementById('chat');
    return Array.from(chat.querySelectorAll(':scope > .turn-header')).map(h => {
      let tucked = 0;
      let total = 0;
      let n = h.nextElementSibling;
      while (n && !(n.classList.contains('turn-header'))) {
        if (n.classList.contains('row')) {
          total++;
          if (n.classList.contains('nf-tucked')) tucked++;
        }
        n = n.nextElementSibling;
      }
      return {
        text: (h.querySelector('.turn-header-text')?.textContent || '').trim(),
        state: h.dataset.turnState,
        display: getComputedStyle(h).display,
        visible: h.offsetHeight > 0,
        tucked,
        total,
      };
    });
  });
}

test.describe('every turn header stays visible + clickable (live path)', () => {
  test('two turns → both headers visible; clicking each toggles its own turn', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate('window.__liveTurn()');
    await page.evaluate('window.__postClosureTurn()');
    await page.waitForFunction(() =>
      document.querySelectorAll('#chat > .turn-header').length === 2, null, { timeout: 5000 });

    // BOTH headers are rendered and visible — no dedupe hiding.
    let st = await headerStates(page);
    expect(st).toHaveLength(2);
    for (const h of st) {
      expect(h.display, 'header must not be display:none (dedupe removed)').not.toBe('none');
      expect(h.visible, 'header must be laid out and clickable').toBe(true);
      expect(h.state).toBe('done');
      expect(h.tucked).toBeGreaterThan(0);
    }
    expect(st[0].text).toContain('✻ 整理线索 4 秒');
    expect(st[1].text).toContain('✻ 快速确认 2 秒');

    const headers = page.locator('#chat > .turn-header');

    // OLD turn (header[0]): real click → its own rows untuck, latest untouched.
    await headers.nth(0).click({ timeout: 3000 });
    st = await headerStates(page);
    expect(st[0].state).toBe('done-expanded');
    expect(st[0].tucked).toBe(0);
    expect(st[1].state).toBe('done');
    expect(st[1].tucked).toBeGreaterThan(0);

    // OLD turn header collapses again on second click.
    await headers.nth(0).click({ timeout: 3000 });
    st = await headerStates(page);
    expect(st[0].state).toBe('done');
    expect(st[0].tucked).toBeGreaterThan(0);

    // LATEST turn (header[1]): same toggle contract.
    await headers.nth(1).click({ timeout: 3000 });
    st = await headerStates(page);
    expect(st[1].state).toBe('done-expanded');
    expect(st[1].tucked).toBe(0);
    expect(st[0].state).toBe('done');

    await headers.nth(1).click({ timeout: 3000 });
    st = await headerStates(page);
    expect(st[1].state).toBe('done');
    expect(st[1].tucked).toBeGreaterThan(0);

    expect(pageErrors, 'no page errors during toggling').toEqual([]);
    await page.screenshot({ path: path.join(SHOT_DIR, 'turn-header-persistent-live.png') }).catch(() => {});
    await context.close();
  });

  test('a third turn keeps ALL headers visible and clickable', async ({ browser }) => {
    const { context, page } = await newPage(browser);
    await page.evaluate('window.__liveTurn()');
    await page.evaluate('window.__postClosureTurn()');
    await page.evaluate('window.__multiRoundTurn()');
    await page.waitForFunction(() =>
      document.querySelectorAll('#chat > .turn-header').length === 3, null, { timeout: 5000 });

    const st = await headerStates(page);
    expect(st).toHaveLength(3);
    for (const h of st) {
      expect(h.display).not.toBe('none');
      expect(h.visible).toBe(true);
      expect(h.tucked).toBeGreaterThan(0);
    }

    // each header individually expands exactly its own scope
    const headers = page.locator('#chat > .turn-header');
    for (let i = 0; i < 3; i++) {
      await headers.nth(i).click({ timeout: 3000 });
      const s = await headerStates(page);
      expect(s[i].state, `header[${i}] expands`).toBe('done-expanded');
      expect(s[i].tucked, `header[${i}] untucks its own rows`).toBe(0);
      for (let j = 0; j < 3; j++) {
        if (j !== i) expect(s[j].state, `header[${j}] untouched`).toBe('done');
      }
      await headers.nth(i).click({ timeout: 3000 });
    }
    await context.close();
  });
});

test.describe('every turn header stays visible + clickable (history path)', () => {
  test('history replay: both headers visible, each toggles its own turn', async ({ browser }) => {
    const { context, page } = await newPage(browser);
    await page.evaluate(() => window.__history([
      { type: 'user', content: '第一轮问题', timestamp: 1000 },
      { type: 'tool', label: 'Read\n  ("f1.js")', summary: '10 lines', isError: false, input: { file_path: '/tmp/f1.js' } },
      { type: 'ai', text: '第一轮回复。', durationMs: 1200, model: 'm-hist', timestamp: 2000 },
      { type: 'user', content: '第二轮问题', timestamp: 3000 },
      { type: 'tool', label: 'Bash\n  (ls)', summary: 'ok', isError: false, input: { command: 'ls' } },
      { type: 'ai', text: '第二轮回复。', durationMs: 800, model: 'm-hist', timestamp: 4000 },
    ]));
    await page.waitForFunction(() =>
      document.querySelectorAll('#chat > .turn-header').length === 2, null, { timeout: 5000 });

    const st = await headerStates(page);
    expect(st).toHaveLength(2);
    for (const h of st) {
      expect(h.display, 'history header must not be display:none').not.toBe('none');
      expect(h.visible).toBe(true);
      expect(h.state).toBe('done');
      expect(h.tucked).toBeGreaterThan(0);
    }

    const headers = page.locator('#chat > .turn-header');
    await headers.nth(0).click({ timeout: 3000 });
    let s = await headerStates(page);
    expect(s[0].state).toBe('done-expanded');
    expect(s[0].tucked).toBe(0);
    expect(s[1].state).toBe('done');

    await headers.nth(0).click({ timeout: 3000 });
    s = await headerStates(page);
    expect(s[0].state).toBe('done');
    expect(s[0].tucked).toBeGreaterThan(0);
    await context.close();
  });
});

test.describe('E10 search-expand still works alongside persistent headers', () => {
  test('expandGroupContaining expands the turn holding a search hit', async ({ browser }) => {
    const { context, page } = await newPage(browser);
    await page.evaluate('window.__liveTurn()');
    await page.evaluate('window.__postClosureTurn()');
    await page.waitForFunction(() =>
      document.querySelectorAll('#chat > .turn-header').length === 2, null, { timeout: 5000 });

    // pick a tucked tool row inside the FIRST (older) turn
    const target = await page.evaluate(() => {
      const h = document.querySelectorAll('#chat > .turn-header')[0];
      let n = h.nextElementSibling;
      while (n && !(n.classList.contains('turn-header'))) {
        if (n.classList.contains('tool') && n.classList.contains('nf-tucked')) return true;
        n = n.nextElementSibling;
      }
      return false;
    });
    expect(target, 'first turn has a tucked tool row').toBe(true);

    const expanded = await page.evaluate(() => {
      const h = document.querySelectorAll('#chat > .turn-header')[0];
      let n = h.nextElementSibling;
      while (n && !(n.classList.contains('turn-header'))) {
        if (n.classList.contains('tool') && n.classList.contains('nf-tucked')) {
          return window.__expandContaining(n);
        }
        n = n.nextElementSibling;
      }
      return false;
    });
    expect(expanded).toBe(true);

    const st = await headerStates(page);
    expect(st[0].state).toBe('done-expanded');
    expect(st[0].tucked).toBe(0);
    await context.close();
  });
});

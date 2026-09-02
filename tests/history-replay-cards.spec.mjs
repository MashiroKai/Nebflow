// history-replay-cards.spec.mjs — History replay card regression (AskUser /
// compaction cards degrade to flat text after browser refresh).
//
// Drives the REAL render modules (chat.js / persistence.js / chatView.js) in
// a static harness page (tests/fixtures/history-replay/harness.html) against
// a REAL fixture extracted from ~/.nebflow/sessions/<sid>.ui.json — no live
// Nebflow instance involved, never the 8080 host. Two throwaway static file
// servers are spawned by this spec and killed in afterAll:
//   - baseline: src/main/resources/web extracted from git HEAD (pre-fix code)
//   - fixed:    the working tree (post-fix code)
//
// Three states screenshotted to ~/.nebflow/docs/Nebflow/:
//   20260902-history-replay-1-degraded.png     (HEAD code, replay path)
//   20260902-history-replay-2-replay-fixed.png (fixed code, replay path)
//   20260902-history-replay-3-live.png         (fixed code, live path)
//
// Run: npx playwright test tests/history-replay-cards.spec.mjs

import { test, expect } from '@playwright/test';
import { spawn, execSync } from 'node:child_process';
import net from 'node:net';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_DIR = path.join(REPO_ROOT, 'tests', 'fixtures', 'history-replay');
const SHOT_DIR = path.join(os.homedir(), '.nebflow', 'docs', 'Nebflow');
const HARNESS_PATH = '/tests/fixtures/history-replay/harness.html';

// Baseline ref for the degraded-state comparison: the commit BEFORE the
// replay-card fix (fix landed in 5473ccb2). Pinned so the degraded baseline
// stays stable as HEAD advances; override with REPLAY_BASELINE_REF to point
// at another pre-fix revision.
const BASELINE_REF = process.env.REPLAY_BASELINE_REF ?? '4dfc4a39';

let baselineRoot;
let baselinePort;
let fixedPort;
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

function startStaticServer(port, dir) {
  return spawn('python3', ['-m', 'http.server', String(port), '--bind', '127.0.0.1', '--directory', dir], {
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
  // Baseline tree: the web resources exactly as they were before the fix.
  baselineRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'nb-replay-baseline-'));
  execSync(`git archive ${BASELINE_REF} src/main/resources/web | tar -x -C "${baselineRoot}"`, { cwd: REPO_ROOT });
  // Harness + fixture must be reachable from BOTH roots (same-origin module imports).
  const baseHarnessDir = path.join(baselineRoot, 'tests', 'fixtures', 'history-replay');
  fs.mkdirSync(baseHarnessDir, { recursive: true });
  fs.copyFileSync(path.join(HARNESS_DIR, 'harness.html'), path.join(baseHarnessDir, 'harness.html'));
  fs.copyFileSync(path.join(HARNESS_DIR, 'fixture.json'), path.join(baseHarnessDir, 'fixture.json'));

  baselinePort = await freePort();
  fixedPort = await freePort();
  servers.push(startStaticServer(baselinePort, baselineRoot));
  servers.push(startStaticServer(fixedPort, REPO_ROOT));
  await waitUntilUp(`http://127.0.0.1:${baselinePort}${HARNESS_PATH}`);
  await waitUntilUp(`http://127.0.0.1:${fixedPort}${HARNESS_PATH}`);
});

test.afterAll(async () => {
  for (const s of servers) s.kill('SIGTERM');
  servers.length = 0;
  if (baselineRoot) fs.rmSync(baselineRoot, { recursive: true, force: true });
});

async function newPage(browser, port) {
  const context = await browser.newContext({ locale: 'zh-CN', viewport: { width: 900, height: 1000 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', (err) => pageErrors.push(err.message));
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction(() => window.__ready === true, null, { timeout: 15000 });
  return { context, page, pageErrors };
}

// The fixture's askUser card was answered with the Other free text '都做。'
// (matches no preset option label) — reproduce that exact interaction live.
// The Other button is the one WITHOUT data-label (preset options all carry
// data-label in both single and multi branches); it is the last .option-btn.
async function answerLiveCard(page) {
  const box = page.locator('.option-box').last();
  const otherBtn = box.locator('.option-btn:not([data-label])').last();
  await otherBtn.click();
  await box.locator('.option-custom-input').fill('都做。');
  await box.locator('.option-confirm').click();
}

test.describe('history replay cards — degraded baseline (git HEAD)', () => {
  test('replay path renders flat error-cards + bare disabled option box', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser, baselinePort);
    await page.evaluate(() => window.__replay());
    await page.waitForTimeout(400);
    expect(pageErrors).toEqual([]);

    // Compaction persisted as i18nKey'd system entries — the HEAD restore has
    // no compact-card branch: BOTH entries fall through to the flat error-card.
    await expect(page.locator('.compact-card')).toHaveCount(0);
    await expect(page.locator('.error-card')).toHaveCount(2);

    // AskUser restore: bare option-box — no question wrapper, no option
    // descriptions, no confirm row, flat 0.5-opacity buttons.
    const box = page.locator('.option-box').first();
    await expect(box.locator('.option-q-wrapper')).toHaveCount(0);
    await expect(box.locator('.option-desc')).toHaveCount(0);
    await expect(box.locator('.option-confirm')).toHaveCount(0);
    await expect(box.locator('.option-btn').first()).toHaveCSS('opacity', '0.5');

    await page.screenshot({ path: path.join(SHOT_DIR, '20260902-history-replay-1-degraded.png'), fullPage: true });
    await context.close();
  });
});

test.describe('history replay cards — fixed replay vs live parity', () => {
  test('replay renders the same card components as the live path', async ({ browser }) => {
    // ---- Replay state (post-refresh path, fixed code) ----
    const replay = await newPage(browser, fixedPort);
    await replay.page.evaluate(() => window.__replay());
    await replay.page.waitForTimeout(400);
    expect(replay.pageErrors).toEqual([]);

    // Compaction: ONE final-state card (the live card morphs in place, so
    // history shows a single done card, not two) built by the SAME
    // buildCompactCardRow component the live path uses.
    await expect(replay.page.locator('.compact-card')).toHaveCount(1);
    const rCompact = replay.page.locator('.compact-card[data-state="done"]');
    await expect(rCompact).toHaveCount(1);
    await expect(rCompact.locator('.compact-card-icon.ok svg')).toHaveCount(1);
    const rCompactLabel = await rCompact.locator('.compact-card-label').textContent();
    expect(rCompactLabel).toBe('上下文已压缩：184 → 1 条消息 (report: 20260608-151104-report.md)');
    // No flat error-card fallback for compaction anymore.
    await expect(replay.page.locator('.error-card')).toHaveCount(0);

    // AskUser: the full showOptions component + terminal lock state.
    const rBox = replay.page.locator('.option-box').first();
    await expect(rBox.locator('.option-q-wrapper')).toHaveCount(1);
    await expect(rBox.locator('.option-desc')).toHaveCount(4); // 4 options carry descriptions
    await expect(rBox.locator('.option-custom-input')).toHaveCount(1);
    await expect(rBox.locator('.option-answer')).toHaveText('-> 都做。');
    // Other free-text answer restored: Other button picked + textarea value.
    // (Other button = the option-btn without data-label.)
    await expect(rBox.locator('.option-btn:not([data-label])').last()).toHaveClass(/picked/);
    await expect(rBox.locator('.option-custom-input')).toHaveValue('都做。');
    // Locked like the live confirm end-state: everything disabled, action row hidden.
    const btnCount = await rBox.locator('.option-btn').count();
    for (let i = 0; i < btnCount; i++) await expect(rBox.locator('.option-btn').nth(i)).toBeDisabled();
    await expect(rBox.locator('.option-confirm')).toBeHidden();
    await expect(rBox.locator('.option-cancel')).toBeHidden();
    const rBoxHtml = await rBox.evaluate(el => el.innerHTML);

    await replay.page.screenshot({ path: path.join(SHOT_DIR, '20260902-history-replay-2-replay-fixed.png'), fullPage: true });
    await replay.context.close();

    // ---- Live state (WS-event path, fixed code, real interaction) ----
    const live = await newPage(browser, fixedPort);
    await live.page.evaluate(() => window.__live());
    await answerLiveCard(live.page);
    await live.page.waitForTimeout(200);
    expect(live.pageErrors).toEqual([]);

    // Live compaction morphs into the same done card.
    await expect(live.page.locator('.compact-card')).toHaveCount(1);
    const lCompact = live.page.locator('.compact-card[data-state="done"]');
    await expect(lCompact.locator('.compact-card-icon.ok svg')).toHaveCount(1);
    const lCompactLabel = await lCompact.locator('.compact-card-label').textContent();
    // The live label carries the ephemeral elapsed suffix (live-only timing
    // data — history entries carry no timestamps to recompute it from).
    expect(lCompactLabel.startsWith(rCompactLabel)).toBe(true);

    // THE core parity assertion: the restored answered card is byte-identical
    // (innerHTML) to the live answered card of the same fixture entry.
    const lBox = live.page.locator('.option-box').last();
    await expect(lBox.locator('.option-answer')).toHaveText('-> 都做。');
    const lBoxHtml = await lBox.evaluate(el => el.innerHTML);
    expect(rBoxHtml).toBe(lBoxHtml);

    await live.page.screenshot({ path: path.join(SHOT_DIR, '20260902-history-replay-3-live.png'), fullPage: true });
    await live.context.close();
  });
});

// pop-media-alignment.spec.mjs — #491 主题三: user attachments aligned with the
// LLM Pop faces (author 2026-10-05 12:14 原话 + the three 【已裁决】 rulings).
//
//   AC-S1 点击放大查看 — media (image/video) click-to-zoom is a WeChat-style
//        IN-STREAM lightbox (in place, Esc / blank-click close); Canvas stays a
//        secondary explicit entry on the card and on the overlay; file-type
//        items keep small-card → Canvas. SAME form and interaction on both
//        sides (user attachments and LLM Pop).
//   AC-S2 小卡片 + Canvas 打开 — user file attachments render the Pop file-card
//        face; a click fires the SAME workspace-open-item single point with the
//        SAME detail shape (absPath) as a Pop file card.
//   AC-S3 多张折叠 — multiple user images render the Pop media stack (front +
//        peeking layers, expand/collapse pill), not N separate bubbles.
//   AC-S4 独立卡片位置 — user media is an INDEPENDENT card row after the user
//        row (「用户消息 → 独立媒体卡 → 后续行」, time order = message order);
//        BOTH history rebuild legs restore the same order; the row is not in
//        turnGroup's tuck set.
//
// The spec drives the REAL render modules through
// tests/fixtures/pop-artifacts/harness.html (same self-spawned static server
// as pop-artifacts.spec.mjs — never the host instance). Probes read the
// RENDERED DOM.
//
// Run: node node_modules/@playwright/test/cli.js test tests/pop-media-alignment.spec.mjs

import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/pop-artifacts/harness.html';

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

async function waitUntilUp(url, tries = 60) {
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
  expect(fs.existsSync(path.join(REPO_ROOT, HARNESS_PATH.replace(/^\//, '')))).toBe(true);
  port = await freePort();
  servers.push(startStaticServer(port, REPO_ROOT));
  await waitUntilUp(`http://127.0.0.1:${port}${HARNESS_PATH}`);
});

test.afterAll(async () => {
  for (const s of servers) s.kill('SIGTERM');
});

async function newPage(browser) {
  const context = await browser.newContext({ viewport: { width: 1200, height: 900 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', e => pageErrors.push(String(e)));
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction(() => window.__ready === true);
  return { context, page, pageErrors };
}

/* ═══════════ AC-S4 — independent card placement ═══════════ */

test.describe('AC-S4 — user media is an independent card row (live)', () => {
  test('user row stays clean; media + file cards land in a row AFTER it', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__userMediaLive());

    const read = await page.evaluate(() => {
      const seq = window.__seq('#chat');
      const userRow = document.querySelector('#chat > .row.user');
      const mediaRow = document.querySelector('#chat > .row.user-media');
      return {
        seq,
        userHasAttBubble: !!userRow.querySelector('.att-bubble .att-img, .att-bubble .att-file-tag'),
        mediaRowExists: !!mediaRow,
        mediaRowAfterUser: mediaRow ? (mediaRow.previousElementSibling === userRow) : false,
        stackLayers: mediaRow ? mediaRow.querySelectorAll('.pop-stack-view .media-layer').length : 0,
        fileCards: mediaRow ? mediaRow.querySelectorAll('.file-card').length : 0,
        // Same layer/form as a Pop artifact row: same row class family.
        isPopArtifactClass: mediaRow ? mediaRow.classList.contains('pop-artifact') : false,
        notToolRow: mediaRow ? !mediaRow.classList.contains('tool') : false,
      };
    });

    expect(read.userHasAttBubble,
      'no media/file bubbles inside the user row anymore (AC-S4: 不挂消息下方)').toBe(false);
    expect(read.mediaRowExists, 'an independent media row exists').toBe(true);
    expect(read.mediaRowAfterUser, 'it comes directly after the user row').toBe(true);
    expect(read.stackLayers, 'two user images stack like Pop (AC-S3)').toBe(2);
    expect(read.fileCards, 'the user file renders the Pop file-card face (AC-S2)').toBe(1);
    expect(read.isPopArtifactClass, 'same layer as Pop cards (.row.pop-artifact)').toBe(true);
    expect(read.notToolRow, 'never a tool row (collapse exemption holds)').toBe(true);
    // Time order: user → media row → (nothing else yet).
    expect(read.seq.filter(s => s.kind === 'user').length, 'one user row').toBe(1);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

test.describe('AC-S4 — replay legs restore the same independent order', () => {
  test('backend full-rebuild leg: user → media row → ai', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__userMediaBackendReplay(false));

    const read = await page.evaluate(() => {
      const kids = Array.from(document.querySelector('#chat').children);
      const idx = sel => kids.findIndex(k => k.matches(sel));
      const mediaRow = document.querySelector('#chat > .row.user-media');
      return {
        userIdx: idx('#chat > .row.user'),
        mediaIdx: idx('#chat > .row.user-media'),
        aiIdx: idx('#chat > .row.ai'),
        mediaImgs: mediaRow ? mediaRow.querySelectorAll('.pop-img').length : 0,
        mediaCards: mediaRow ? mediaRow.querySelectorAll('.file-card').length : 0,
        tucked: mediaRow ? mediaRow.classList.contains('nf-tucked') : null,
      };
    });
    expect(read.userIdx, 'the user row exists').toBeGreaterThanOrEqual(0);
    expect(read.mediaIdx, 'the media row exists on replay').toBeGreaterThan(read.userIdx);
    expect(read.aiIdx, 'the following ai row comes AFTER the media row (time order)')
      .toBeGreaterThan(read.mediaIdx);
    expect(read.mediaImgs, 'the image rebuilt on the media row').toBe(1);
    expect(read.mediaCards, 'the file rebuilt as a card on the media row').toBe(1);
    expect(read.tucked, 'the media row is never tucked').toBe(false);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('localStorage incremental leg: same order', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__userMediaStorageReplay());

    const read = await page.evaluate(() => {
      const kids = Array.from(document.querySelector('#chat').children);
      const idx = sel => kids.findIndex(k => k.matches(sel));
      return {
        userIdx: idx('#chat > .row.user'),
        mediaIdx: idx('#chat > .row.user-media'),
        aiIdx: idx('#chat > .row.ai'),
      };
    });
    expect(read.userIdx, 'user row from cache').toBeGreaterThanOrEqual(0);
    expect(read.mediaIdx, 'media row from cache').toBeGreaterThan(read.userIdx);
    expect(read.aiIdx, 'ai row after the media row').toBeGreaterThan(read.mediaIdx);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════════ AC-S3 — multi-image stacking ═══════════ */

test.describe('AC-S3 — user multi-image folding matches the Pop stack', () => {
  test('three user images: one stack area, expand/collapse pill works', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__userMediaBackendReplay(true));
    await page.waitForFunction(
      () => document.querySelectorAll('#chat .row.user-media .pop-stack-view .media-layer').length === 3,
      null, { timeout: 10000 },
    ).catch(() => {});

    const read = await page.evaluate(() => {
      const area = document.querySelector('#chat .row.user-media .pop-stack-area');
      return {
        exists: !!area,
        layers: document.querySelectorAll('#chat .row.user-media .pop-stack-view .media-layer').length,
        front: document.querySelectorAll('#chat .row.user-media .layer-front').length,
        pill: !!(area && area.querySelector('.pill')),
        expandHidden: area ? getComputedStyle(area.querySelector('.pop-expand-view')).display : '',
      };
    });
    expect(read.exists, 'a stack area exists for 3 user images').toBe(true);
    expect(read.layers, 'every image has its layer').toBe(3);
    expect(read.front, 'exactly one front card').toBe(1);
    expect(read.pill, 'the expand pill is present (same face as Pop)').toBe(true);
    expect(read.expandHidden, 'expanded view starts collapsed').toBe('none');

    const toggled = await page.evaluate(() => {
      const pill = document.querySelector('#chat .row.user-media .pop-stack-area .pill');
      if (!pill) return false;
      pill.click();
      return true;
    });
    expect(toggled, 'the pill was clickable').toBe(true);
    const expanded = await page.evaluate(() => ({
      shown: document.querySelector('#chat .row.user-media .pop-expand-view').classList.contains('show'),
    }));
    expect(expanded.shown, 'expand flattens the stack (same interaction as Pop)').toBe(true);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════════ AC-S2 — small cards + the Canvas single point ═══════════ */

test.describe('AC-S2 — user file cards fire the same workspace-open-item as Pop', () => {
  test('click parity: same event, same detail shape (absPath)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);

    // User side: a lone file card.
    const userOpen = await page.evaluate(async () => window.__captureOpenEvents(async () => {
      await window.__userFileLive();
      const card = document.querySelector('#chat > .row.user-media .file-card');
      if (!card) throw new Error('no user file card rendered');
      card.click();
    }));

    // Pop side: a Pop artifact's file card — the parity twin.
    const popOpen = await page.evaluate(async () => window.__captureOpenEvents(async () => {
      await window.__popFileLive();
      const card = document.querySelector('#chat > .row.pop-artifact .file-card');
      if (!card) throw new Error('no Pop file card rendered');
      card.click();
    }));

    expect(userOpen.length, 'the user file card opened something').toBe(1);
    expect(popOpen.length, 'the Pop file card opened something').toBe(1);
    expect(userOpen[0].absPath, 'the user detail carries absPath').toBe('/tmp/up/report.pdf');
    expect(userOpen[0].id, 'identity = file:<absPath> (same scheme as Pop)').toBe('file:/tmp/up/report.pdf');
    expect(userOpen[0].pinned, 'pinned like the Pop open').toBe(true);
    // The full detail shapes are IDENTICAL (same single point, same builder).
    expect(userOpen[0], 'user open detail == Pop open detail (AC-S2 parity)').toEqual(popOpen[0]);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════════ AC-S1 — WeChat-style lightbox, both sides ═══════════ */

test.describe('AC-S1 — click-to-zoom lightbox: user and Pop sides are the same face', () => {
  test('user single image: lightbox opens; Esc closes', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__userSingleLive());

    const probe = await page.evaluate(() => window.__lightboxProbeViaClick('#chat > .row.user-media'));
    expect(probe.clicked, 'the media face was clickable').toBe(true);
    expect(probe.open, 'the lightbox overlay opened').toBe(true);
    expect(probe.hasMedia, 'it carries the zoomed media').toBe(true);
    expect(probe.mediaTag, 'an image face').toBe('img');
    expect(probe.hasName, 'the file name is shown').toBe(true);

    const esc = await page.evaluate(() => window.__closeLightboxEsc());
    expect(esc.open, 'Escape closes the lightbox').toBe(false);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('Pop single image: the SAME lightbox structure (同形态、同交互)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const pop = await page.evaluate(() => window.__popLightboxProbe());
    expect(pop.open, 'the Pop media opened the lightbox').toBe(true);
    expect(pop.hasMedia, 'media face present').toBe(true);
    expect(pop.hasCanvasBtn, 'the overlay carries the Canvas secondary entry (path known)').toBe(true);

    const esc = await page.evaluate(() => window.__closeLightboxEsc());
    expect(esc.open, 'Escape closes it (same interaction)').toBe(false);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('backdrop click closes; the user side with a path carries the Canvas entry too', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__userMediaBackendReplay(false));

    const probe = await page.evaluate(() => window.__lightboxProbeViaClick('#chat > .row.user-media'));
    expect(probe.open, 'the replayed user image opened the lightbox').toBe(true);
    expect(probe.hasCanvasBtn, 'path known on replay → Canvas entry present').toBe(true);

    const backdrop = await page.evaluate(() => window.__closeLightboxBackdrop());
    expect(backdrop.open, 'a blank-area click closes the lightbox (WeChat style)').toBe(false);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

// pop-media-alignment.spec.mjs — #491 主题三: user attachments aligned with the
// LLM Pop faces (author 2026-10-05 12:14 原话 + the three 【已裁决】 rulings).
//
//   AC-S1 点击放大查看 — AMENDED by the 2026-10-06 10:59 author screenshot
//        order (pop-media 三修 ②/③): an image card's whole-face click is the
//        CANVAS open (the file card's workspace-open-item single point), and
//        the collapsed stack's click opens Canvas with the FIRST item. The
//        WeChat-style in-stream lightbox remains the zoom face for pathless
//        inline bytes (live user attachments), videos, and the expanded stack
//        layers; file-type items keep small-card → Canvas. SAME form and
//        interaction on both sides (user attachments and LLM Pop).
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

/* ═══════════ AC-S1 — media click faces (amended: 2026-10-06 10:59 截图令) ═══════════

   The 2026-10-05 12:14 ruling made a media click the WeChat-style lightbox
   with Canvas as the secondary entry. The 2026-10-06 10:59 author screenshot
   order amends the IMAGE faces (pop-media 三修 ②/③): a whole-image-card click
   is now the CANVAS open — the SAME workspace-open-item single point the file
   card fires — and the COLLAPSED stack's click opens Canvas with the FIRST
   item (front card). The lightbox remains the zoom face ONLY where Canvas
   cannot apply: a pathless live user attachment (bytes ride inline, no
   absPath yet).

   ═══════════ 复验批 (2026-10-06 16:47 author re-verification order) ═══════════

   The author's 16:47 live test found the 10:59 fix NOT firing: a click on the
   image body still did not open Canvas. Root causes, both reproduced with
   real mouse clicks against this very harness:
   • the COLLAPSED stack's binding lived on the layers, but the drag leg's
     view.setPointerCapture() retargets a real click to `view` (the capture
     target) — every layer listener was dead code for real input, while a JS
     element.click() (which the old spec used) skipped the pointer sequence
     and masked the defect;
   • the EXPANDED rows' binding still called openMediaLightbox — the last
     lightbox leg.
   The fix moves the collapsed binding to the stack view and points the
   expanded binding at openMediaItemClick. EVERY case below therefore drives a
   REAL Playwright mouse click at the element's center point (real input
   pipeline: hit-test → pointer events → click) and first asserts that the
   point hit-tests to the image itself — the acceptance the author wrote in
   the 16:47 order. */

test.describe('AC-S1 — image card click faces: Canvas single point, lightbox fallback', () => {
  test('pathless live user image: lightbox zoom fallback; Esc and backdrop close', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__userSingleLive());

    const probe = await page.evaluate(() => window.__lightboxProbeViaClick('#chat > .row.user-media'));
    expect(probe.clicked, 'the media face was clickable').toBe(true);
    expect(probe.open, 'no absPath on a live attachment → the lightbox zoom fallback').toBe(true);
    expect(probe.hasMedia, 'it carries the zoomed media').toBe(true);
    expect(probe.mediaTag, 'an image face').toBe('img');
    expect(probe.hasName, 'the file name is shown').toBe(true);

    const esc = await page.evaluate(() => window.__closeLightboxEsc());
    expect(esc.open, 'Escape closes the lightbox').toBe(false);

    // Reopen once: the blank-area click still closes (点击空白关闭 retained).
    await page.evaluate(() => window.__lightboxProbeViaClick('#chat > .row.user-media'));
    const backdrop = await page.evaluate(() => window.__closeLightboxBackdrop());
    expect(backdrop.open, 'a blank-area click closes the lightbox (WeChat style)').toBe(false);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('Pop single image: whole-card click fires the SAME workspace-open-item as the file card (三修 ②)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);

    const opened = await page.evaluate(async () => window.__captureOpenEvents(async () => {
      await window.__popSingleWide();
      const box = document.querySelector('#chat > .row.pop-artifact .pop-media-flat');
      if (!box) throw new Error('no flat media card rendered');
      box.click();
    }));
    expect(opened.length, 'the whole image card opened something').toBe(1);
    expect(opened[0].absPath, 'Canvas open = the image absPath (file-card parity)').toBe('/tmp/pop/wide.png');
    expect(opened[0].id, 'identity = file:<absPath> (same scheme as the file card)').toBe('file:/tmp/pop/wide.png');
    expect(opened[0].pinned, 'pinned like the file-card open').toBe(true);
    expect(await page.evaluate(() => !!document.querySelector('.nf-lightbox')),
      'a pathed image click opens Canvas directly — no lightbox detour (三修 ②)').toBe(false);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('a pathed user image opens Canvas; the collapsed stack opens the FIRST item, both sides (三修 ③)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);

    const opened = await page.evaluate(async () => window.__captureOpenEvents(async () => {
      await window.__userMediaBackendReplay(false);
      const media = document.querySelector('#chat > .row.user-media .pop-img');
      if (!media) throw new Error('no replayed user image rendered');
      media.click();
    }));
    expect(opened.length, 'the replayed user image opened something').toBe(1);
    expect(opened[0].absPath, 'path known on replay → the Canvas single point (三修 ②)')
      .toBe('/uploads/pop-harness/m1.png');
    expect(await page.evaluate(() => !!document.querySelector('.nf-lightbox')),
      'no lightbox once a path exists').toBe(false);

    // 三修 ③: the COLLAPSED stack's click opens Canvas with the FIRST image —
    // user side (uploads paths) and Pop side (payload paths) alike; no lightbox.
    await page.evaluate(() => window.__clearChat());
    const stackOpened = await page.evaluate(async () => window.__captureOpenEvents(async () => {
      await window.__userMediaBackendReplay(true);
      const front = document.querySelector('#chat > .row.user-media .pop-stack-view .media-layer.layer-front');
      if (!front) throw new Error('no collapsed user stack rendered');
      front.click();
    }));
    expect(stackOpened.length, 'the collapsed user stack opened something').toBe(1);
    expect(stackOpened[0].absPath, 'opened content = the FIRST image').toBe('/uploads/pop-harness/m1.png');

    await page.evaluate(() => window.__clearChat());
    const popStackOpened = await page.evaluate(async () => window.__captureOpenEvents(async () => {
      await window.__popStack();
      const front = document.querySelector('#chat > .row.pop-artifact .pop-stack-view .media-layer.layer-front');
      if (!front) throw new Error('no collapsed Pop stack rendered');
      front.click();
    }));
    expect(popStackOpened.length, 'the collapsed Pop stack opened something').toBe(1);
    expect(popStackOpened[0].absPath, 'Pop side: opened content = the FIRST image too').toBe('/tmp/pop/shot-1.png');
    expect(await page.evaluate(() => !!document.querySelector('.nf-lightbox')),
      'a collapsed-stack click never opens the lightbox').toBe(false);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════════ AC-S1 复验 — REAL-click acceptance (author 2026-10-06 16:47) ═══════════

   The author's acceptance bar is a REAL click on the image body — not a JS
   element.click(), which skips the pointer sequence and (per the 复验 root
   cause) never exercises the capture retarget that killed the collapsed
   stack's layer binding. Every case here drives page.mouse at the element's
   center point and first asserts the point hit-tests to the image itself. */

test.describe('AC-S1 复验 — whole-card Canvas open under REAL mouse clicks', () => {
  /** Drive a REAL mouse click at `locator`'s center: pre-read the hit-test at
   *  that point, arm the workspace-open-item capture, click, then report what
   *  opened (Canvas events vs the lightbox overlay). */
  async function realCenterClick(page, locator) {
    await locator.scrollIntoViewIfNeeded();
    await page.waitForTimeout(120);
    const box = await locator.boundingBox();
    const point = { x: box.x + box.width / 2, y: box.y + box.height / 2 };
    const hit = await page.evaluate(([x, y]) => {
      const el = document.elementFromPoint(x, y);
      return el ? el.tagName.toLowerCase() + '.' + Array.from(el.classList).join('.') : 'null';
    }, [point.x, point.y]);
    await page.evaluate(() => {
      window.__opens = [];
      window.__onOpen = e => window.__opens.push(e.detail
        ? { absPath: e.detail.absPath, id: e.detail.id, title: e.detail.title } : null);
      window.addEventListener('workspace-open-item', window.__onOpen);
    });
    await page.mouse.click(point.x, point.y);
    await page.waitForTimeout(300);
    const result = await page.evaluate(() => {
      window.removeEventListener('workspace-open-item', window.__onOpen);
      return {
        opens: window.__opens,
        lightbox: !!document.querySelector('.nf-lightbox'),
        lightboxName: document.querySelector('.nf-lightbox-name')
          ? document.querySelector('.nf-lightbox-name').textContent : '',
      };
    });
    return { point, hit, ...result };
  }

  async function renderStack(page, layers) {
    await page.evaluate(() => window.__popStack());
    await page.waitForFunction(
      n => document.querySelectorAll('#chat .pop-stack-view .media-layer').length === n,
      layers, { timeout: 10000 },
    );
    await page.waitForTimeout(300); // data-URI bytes decode; boxes pin their ratio
  }

  test('(a) 展开态: a real click on an image body opens Canvas with THAT item', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await renderStack(page, 3);

    // Expand via a REAL click on the pill (the author's gesture path).
    const pill = page.locator('#chat .pop-stack-area .pill');
    await pill.scrollIntoViewIfNeeded();
    const pb = await pill.boundingBox();
    await page.mouse.click(pb.x + pb.width / 2, pb.y + pb.height / 2);
    await page.waitForFunction(() => document.querySelector('#chat .pop-expand-view').classList.contains('show'),
      null, { timeout: 5000 });
    await page.waitForTimeout(620); // the 0.42s height transition settles

    // The 复验 point: click the IMAGE BODY of expanded row #2 (shot-2.png).
    const layerImg = page.locator('#chat .pop-expand-view .media-layer').nth(1).locator('.pop-img');
    const probe = await realCenterClick(page, layerImg);
    expect(probe.hit, `the click point hit-tests to the image body (got ${probe.hit})`).toContain('img.pop-img');
    expect(probe.opens.length, 'exactly one Canvas open fired').toBe(1);
    expect(probe.opens[0].absPath, 'opened file = the clicked row\'s item (shot-2.png)').toBe('/tmp/pop/shot-2.png');
    expect(probe.opens[0].id, 'identity = file:<absPath> (the file-card single point)').toBe('file:/tmp/pop/shot-2.png');
    expect(probe.lightbox, 'no lightbox detour on a pathed image (复验令 a)').toBe(false);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('(b) 收起态: a real click on the collapsed stack opens Canvas with the FIRST item', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await renderStack(page, 3);

    // The 复验 root cause lived exactly here: a REAL click on the front card —
    // the capture-retargeted click never reached the old layer binding.
    const frontImg = page.locator('#chat .pop-stack-view .media-layer.layer-front .pop-img');
    const probe = await realCenterClick(page, frontImg);
    expect(probe.hit, `the click point hit-tests to the image body (got ${probe.hit})`).toContain('img.pop-img');
    expect(probe.opens.length, 'exactly one Canvas open fired').toBe(1);
    expect(probe.opens[0].absPath, 'opened file = the FIRST item (shot-1.png)').toBe('/tmp/pop/shot-1.png');
    expect(probe.opens[0].id, 'identity = file:<absPath>').toBe('file:/tmp/pop/shot-1.png');
    expect(probe.lightbox, 'no lightbox on the collapsed-stack open (复验令 b)').toBe(false);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('三修 ② regression: a real click on the FLAT single image opens Canvas', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__popSingleWide());
    await page.waitForSelector('#chat .pop-media-flat .pop-img');
    await page.waitForTimeout(300);

    const probe = await realCenterClick(page, page.locator('#chat .pop-media-flat .pop-img'));
    expect(probe.hit, `the click point hit-tests to the image body (got ${probe.hit})`).toContain('img.pop-img');
    expect(probe.opens.length, 'exactly one Canvas open fired').toBe(1);
    expect(probe.opens[0].absPath, 'the flat card opened Canvas with the image').toBe('/tmp/pop/wide.png');
    expect(probe.lightbox, 'no lightbox (the 10:59 face already worked — keep it working)').toBe(false);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('the corner button remains a working SECOND entry (复验令: 可留但不再是唯一通路)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await renderStack(page, 3);
    const pill = page.locator('#chat .pop-stack-area .pill');
    await pill.scrollIntoViewIfNeeded();
    const pb = await pill.boundingBox();
    await page.mouse.click(pb.x + pb.width / 2, pb.y + pb.height / 2);
    await page.waitForFunction(() => document.querySelector('#chat .pop-expand-view').classList.contains('show'),
      null, { timeout: 5000 });
    await page.waitForTimeout(620);

    // Real click on the row's own corner button (28×28, top-right, z-index 6;
    // opacity 0 until hover — opacity never blocks real input). The hit-test
    // reads the DEEPEST node, which inside the 14×14 glyph is the svg path —
    // still the button's own box (the icon is its child), so both readings count.
    const btn = page.locator('#chat .pop-expand-view .media-layer').nth(1).locator('.pop-media-canvas');
    const probe = await realCenterClick(page, btn);
    expect(probe.hit === 'button.pop-media-canvas' || probe.hit.startsWith('path') || probe.hit.startsWith('svg'),
      `the click point lands inside the corner button (got ${probe.hit})`).toBe(true);
    expect(probe.opens.length, 'the explicit entry still opens Canvas').toBe(1);
    expect(probe.opens[0].absPath, '…with the same item as the body click').toBe('/tmp/pop/shot-2.png');
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('(c) 展开钮几何: vertical centering on the stack midline + sender side (user left / LLM right)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await renderStack(page, 3); // LLM row: .row.pop-artifact (no .user)
    await page.evaluate(() => window.__userMediaLive()); // appends the user-media row (2-image stack)
    await page.waitForFunction(
      () => document.querySelectorAll('#chat .row.user-media .pop-stack-view .media-layer').length === 2,
      null, { timeout: 10000 },
    );

    const geo = await page.evaluate(() => {
      const read = (row) => {
        const area = row.querySelector('.pop-stack-area');
        const pill = area.querySelector('.pill');
        const view = area.querySelector('.pop-stack-view');
        const pr = pill.getBoundingClientRect(), vr = view.getBoundingClientRect();
        return {
          pillCenterY: +(pr.y + pr.height / 2).toFixed(1),
          stackCenterY: +(vr.y + vr.height / 2).toFixed(1),
          deltaY: +((pr.y + pr.height / 2) - (vr.y + vr.height / 2)).toFixed(1),
          pillLeft: +pr.left.toFixed(1), pillRight: +pr.right.toFixed(1),
          stackLeft: +vr.left.toFixed(1), stackRight: +vr.right.toFixed(1),
          sideLeft: pr.right <= vr.left + 0.5,
          sideRight: pr.left >= vr.right - 0.5,
        };
      };
      return {
        llm: read(document.querySelector('#chat > .row.pop-artifact:not(.user)')),
        user: read(document.querySelector('#chat > .row.user-media')),
      };
    });

    expect(geo.llm, 'the LLM row was probed').toBeTruthy();
    expect(geo.user, 'the user row was probed').toBeTruthy();
    expect(Math.abs(geo.llm.deltaY), `LLM row: |钮心y − 栈中线y| = ${geo.llm.deltaY}px (垂直居中)`).toBeLessThanOrEqual(1);
    expect(Math.abs(geo.user.deltaY), `user row: |钮心y − 栈中线y| = ${geo.user.deltaY}px (垂直居中)`).toBeLessThanOrEqual(1);
    expect(geo.llm.sideRight, `LLM row: pill RIGHT of the stack (pill.x ${geo.llm.pillLeft} ≥ stack.right ${geo.llm.stackRight})`).toBe(true);
    expect(geo.user.sideLeft, `user row: pill LEFT of the stack (pill.right ${geo.user.pillRight} ≤ stack.x ${geo.user.stackLeft})`).toBe(true);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

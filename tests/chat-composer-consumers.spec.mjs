// FCC-SCN-001..014 consumer regressions for the chat composer module (openspec/changes/frontend-chat-composer-repairs).
// They boot the real web tree under scripts/lib/visual-harness.mjs (static files from disk, a MockWS that feeds the
// real ws.js dispatch). This is consumer-level evidence only: no gateway, no persistence layer beyond the browser's
// own localStorage/IndexedDB, no manual GUI acceptance.
import { test, expect } from '@playwright/test';
import { createHash } from 'node:crypto';
import { createSession, settle } from '../scripts/lib/visual-harness.mjs';

const SID = 'chat-root';
const BOOT = { sessionId: SID, config: { llm: { providers: {} }, features: {} } };
// 1x1 PNG; the composer re-encodes images through a canvas, so only a decodable image matters.
const PNG_1x1 = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');

async function session(browser, extra = {}) {
  const s = await createSession(browser, { width: 1280, height: 800, colorScheme: 'light', ...extra });
  // No service worker: with no server behind the virtual origin, WebKit lets it fail every same-origin fetch.
  await s.page.addInitScript(() => { try { localStorage.setItem('stand_sw', '0'); } catch { /* storage unavailable */ } });
  return s;
}

// Same sequence as the harness boot(), on an origin WebKit accepts (the harness origin uses port 1, which WebKit blocks).
async function boot(s, base = BOOT) {
  const { page, frame } = s;
  await page.goto('http://localhost:8380/', { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => window.__wsMock, null, { timeout: 20000 });
  await page.evaluate(() => { window.__wsMock.readyState = 1; if (window.__wsMock.onopen) window.__wsMock.onopen(); });
  await page.waitForTimeout(250);
  await frame({ type: 'sessionList', sessionId: base.sessionId, activeId: base.sessionId, folders: [], sessions: base.sessions || [{ id: base.sessionId, name: 'Nebula', agentName: 'Nebula' }] });
  await frame({ type: 'configData', config: JSON.stringify(base.config), configured: true, onboarding: 'done' });
  await frame({ type: 'historyPage', sessionId: base.sessionId, messages: base.messages || [], hasMore: false, offset: 0 });
  await settle(s.page);
}

/** Attach files through the real paperclip -> file chooser path. */
async function attachFiles(page, files, total = files.length) {
  const chooser = page.waitForEvent('filechooser');
  await page.locator('#attach-btn').click();
  await (await chooser).setFiles(files);
  await expect(page.locator('#attachment-preview > *')).toHaveCount(total);
}

/** The user-message frames the page has written to the socket (no `type` field, `content` present). */
const userFrames = (page) => page.evaluate(() => window.__wsSent.filter((f) => !f.type && 'content' in f));

/** End the running turn so the next Enter sends directly instead of queueing. */
async function finishTurn(s) {
  await s.frame({ type: 'done', sessionId: SID });
  await expect.poll(() => s.page.evaluate(async (sid) => (await import('/js/state.js')).default.busySessionIds.has(sid), SID)).toBe(false);
}

/** One history step the way a user does it: caret to the start of the input, then ArrowUp. The caret is placed
 *  through the selection API: the Home key scrolls instead of moving the caret on macOS WebKit. */
async function recallOlder(input) {
  await input.focus();
  await input.evaluate((el) => el.setSelectionRange(0, 0));
  await input.press('ArrowUp');
}

const pending = (page) => page.evaluate(async () => (await import('/js/chatView.js')).activeView.pendingAttachments
  .map((a) => ({ type: a.type, name: a.name, size: (a.data || '').length })));

test('FCC-SCN-001 recall restores text and attachments, re-sends the same bytes and removes only what it added', async ({ browser }) => {
  const s = await session(browser);
  const { page } = s;
  try {
    await boot(s);
    // One at a time: the composer finishes attachments asynchronously, so a joint pick has no stable order.
    await attachFiles(page, [{ name: 'shot.png', mimeType: 'image/png', buffer: PNG_1x1 }]);
    const chooser = page.waitForEvent('filechooser');
    await page.locator('#attach-btn').click();
    await (await chooser).setFiles([{ name: 'notes.txt', mimeType: 'text/plain', buffer: Buffer.from('notes body') }]);
    await expect(page.locator('#attachment-preview > *')).toHaveCount(2);
    const input = page.locator('#input');
    await input.fill('look at these');
    await input.press('Enter');
    await expect.poll(async () => (await userFrames(page)).length).toBe(1);
    const sent = (await userFrames(page))[0];
    expect(sent.content).toBe('look at these');
    expect(sent.attachments.map((a) => a.name)).toEqual(['shot.png', 'notes.txt']);
    await finishTurn(s);
    await expect(page.locator('#attachment-preview > *')).toHaveCount(0);

    // ArrowUp in the empty input recalls the text and both attachments, in order.
    await recallOlder(input);
    await expect(input).toHaveValue('look at these');
    await expect.poll(() => pending(page)).toEqual([
      { type: 'image', name: 'shot.png', size: sent.attachments[0].data.length },
      { type: 'text', name: 'notes.txt', size: sent.attachments[1].data.length },
    ]);
    await expect(page.locator('#attachment-preview > *')).toHaveCount(2);

    // An attachment the user adds by hand survives stepping back to the draft; only the recalled ones go.
    await attachFiles(page, [{ name: 'manual.txt', mimeType: 'text/plain', buffer: Buffer.from('mine') }], 3);
    await input.evaluate((el) => el.setSelectionRange(el.value.length, el.value.length));
    await input.press('ArrowDown');
    await expect(input).toHaveValue('');
    await expect.poll(() => pending(page)).toEqual([{ type: 'text', name: 'manual.txt', size: 8 }]);
    await page.evaluate(async () => { (await import('/js/chatView.js')).activeView.pendingAttachments.length = 0; });

    // Recall again and send: the very same bytes go out, in the same order.
    await recallOlder(input);
    await expect(page.locator('#attachment-preview > *')).toHaveCount(2);
    await input.press('Enter');
    await expect.poll(async () => (await userFrames(page)).length).toBe(2);
    const again = (await userFrames(page))[1];
    expect(again.content).toBe('look at these');
    expect(again.attachments.map((a) => [a.name, a.mimeType, a.data])).toEqual(sent.attachments.map((a) => [a.name, a.mimeType, a.data]));
  } finally {
    await s.close();
  }
});

test('FCC-SCN-001 recall survives a reload: the payload comes back from the browser store', async ({ browser }) => {
  const s = await session(browser);
  const { page } = s;
  try {
    await boot(s);
    await attachFiles(page, [{ name: 'shot.png', mimeType: 'image/png', buffer: PNG_1x1 }]);
    await page.locator('#input').fill('persist me');
    await page.locator('#input').press('Enter');
    await expect.poll(async () => (await userFrames(page)).length).toBe(1);
    const sent = (await userFrames(page))[0];
    // The payload write is asynchronous; wait until the vault really holds it before the page goes away.
    await expect.poll(() => page.evaluate(async () => (await import('/js/queueVault.js')).keys())).toHaveLength(1);

    await boot(s);
    const input = page.locator('#input');
    await recallOlder(input);
    await expect(input).toHaveValue('persist me');
    await expect.poll(() => pending(page)).toEqual([{ type: 'image', name: 'shot.png', size: sent.attachments[0].data.length }]);
    await input.press('Enter');
    await expect.poll(async () => (await userFrames(page)).length).toBe(1);
    expect((await userFrames(page))[0].attachments[0].data).toBe(sent.attachments[0].data);
  } finally {
    await s.close();
  }
});

test('FCC-SCN-002 legacy text-only history and lost payloads are reported and never sent hollow', async ({ browser }) => {
  const s = await session(browser);
  const { page } = s;
  try {
    // History written by an earlier build: plain strings, no attachment ledger at all.
    await page.addInitScript(() => {
      if (!sessionStorage.getItem('fcc-legacy-seeded')) {
        sessionStorage.setItem('fcc-legacy-seeded', '1');
        localStorage.setItem('nebflow_input_history', JSON.stringify(['legacy one', 'legacy two']));
      }
    });
    await boot(s);
    const input = page.locator('#input');
    await recallOlder(input);
    await expect(input).toHaveValue('legacy two');
    await recallOlder(input);
    await expect(input).toHaveValue('legacy one');
    expect(await pending(page)).toEqual([]);
    await input.evaluate((el) => el.setSelectionRange(el.value.length, el.value.length));
    await input.press('ArrowDown');
    await input.evaluate((el) => el.setSelectionRange(el.value.length, el.value.length));
    await input.press('ArrowDown');
    await expect(input).toHaveValue('');

    // A new entry with an image; then the stored payload disappears (cleared site data, evicted quota).
    await attachFiles(page, [{ name: 'shot.png', mimeType: 'image/png', buffer: PNG_1x1 }]);
    await input.fill('with image');
    await input.press('Enter');
    await expect.poll(async () => (await userFrames(page)).length).toBe(1);
    await expect.poll(() => page.evaluate(async () => (await import('/js/queueVault.js')).keys())).toHaveLength(1);
    await boot(s);
    await page.evaluate(async () => { await (await import('/js/queueVault.js')).wipe(); });

    await recallOlder(input);
    await expect(input).toHaveValue('with image');
    await expect(page.locator('.nebflow-toast-error .nebflow-toast-msg')).toHaveCount(1);
    await expect(page.locator('.nebflow-toast-error .nebflow-toast-msg')).toContainText('1 个附件');
    expect(await pending(page)).toEqual([]);
    // The text still goes out, with no attachment frame fabricated.
    await input.press('Enter');
    await expect.poll(async () => (await userFrames(page)).length).toBe(1);
    expect((await userFrames(page))[0].attachments).toEqual([]);
    // The older legacy entries are still reachable after the new ones.
    await finishTurn(s);
    await recallOlder(input);
    await recallOlder(input);
    await recallOlder(input);
    await expect(input).toHaveValue('legacy two');
  } finally {
    await s.close();
  }
});

/** A friend message with three stacked media layers (a1, a2, a3) in `#fcc-host`: the real bubble the contact windows build. */
async function renderStackedMessage(page) {
  await page.evaluate(async () => {
    const { bubbleEl } = await import('/js/messages.js');
    const conv = { conversationId: 'c1', kind: 'direct', friend: { userId: 'u-f', name: 'Friend' } };
    const message = {
      id: 'm1', senderId: 'u-f', body: '', createdAt: Date.now(),
      attachments: ['a1', 'a2', 'a3'].map((id, i) => ({ id, name: `p${i + 1}.png`, state: 'ready', size: 1000 })),
    };
    const host = document.createElement('div');
    host.id = 'fcc-host';
    document.body.appendChild(host);
    host.appendChild(bubbleEl(message, conv));
  });
}

test('FCC-SCN-003 a failed stacked layer retries on its own and leaves its siblings and the message alone', async ({ browser, browserName }) => {
  // The layers show blob: object URLs; Playwright's WebKit fails every blob: image load while request interception
  // is active (verified separately: the same blob loads fine without interception), so the layer states cannot be
  // observed there. This is a limit of the test engine, not a product signal.
  test.skip(browserName === 'webkit', 'blob: images do not load in Playwright WebKit with request interception');
  const s = await session(browser);
  const { page } = s;
  const hits = {};
  await page.route('**/api/friends/attachments/*', (route) => {
    const id = decodeURIComponent(new URL(route.request().url()).pathname.split('/').pop());
    hits[id] = (hits[id] || 0) + 1;
    // Layer a2 fails twice, then loads; a1 and a3 load at once.
    if (id === 'a2' && hits[id] <= 2) return route.fulfill({ status: 500, contentType: 'application/json', body: '{}' });
    return route.fulfill({ status: 200, contentType: 'application/octet-stream', body: PNG_1x1 });
  });
  try {
    await boot(s);
    await renderStackedMessage(page);
    const expanded = page.locator('#fcc-host .pop-expand-view .media-layer');
    await page.locator('#fcc-host .ds-media-stack__pill').click();
    await expect(expanded).toHaveCount(3);
    const layerLoaded = (index) => expanded.nth(index).locator('img.pop-img').evaluate((img) => img.dataset.attLoaded === '1' && img.naturalWidth > 0);
    await expect.poll(() => layerLoaded(0)).toBe(true);
    await expect.poll(() => layerLoaded(2)).toBe(true);
    // The failed layer stays visible as failed and offers its own retry.
    await expect(expanded.nth(1).locator('img.pop-img.nf-img-failed')).toHaveCount(1);
    const retry = expanded.nth(1).locator('button.nf-media-retry');
    await expect(retry).toHaveCount(1);
    expect(hits).toEqual({ a1: 1, a2: 1, a3: 1 });

    // Keyboard first: focus the control and press Enter. The second attempt fails again and stays retryable.
    await retry.focus();
    await page.keyboard.press('Enter');
    await expect.poll(() => hits.a2).toBe(2);
    await expect(expanded.nth(1).locator('button.nf-media-retry')).toHaveCount(1);
    await expect(expanded.nth(1).locator('img.pop-img.nf-img-failed')).toHaveCount(1);

    // Third attempt succeeds: the layer paints, nothing failed is left on it.
    await expanded.nth(1).locator('button.nf-media-retry').click();
    await expect.poll(() => hits.a2).toBe(3);
    await expect.poll(() => layerLoaded(1)).toBe(true);
    await expect(expanded.nth(1).locator('.nf-media-retry, .nf-media-failed-note, .nf-img-failed')).toHaveCount(0);
    // Siblings were requested exactly once and the message was neither re-sent nor re-rendered.
    expect(hits).toEqual({ a1: 1, a2: 3, a3: 1 });
    expect(await userFrames(page)).toEqual([]);
    await expect(page.locator('#fcc-host .fm-msg')).toHaveCount(1);
  } finally {
    await s.close();
  }
});

test('FCC-SCN-003 the collapsed front layer retries on a real pointer click and both faces of a layer recover together', async ({ browser, browserName }) => {
  test.skip(browserName === 'webkit', 'blob: images do not load in Playwright WebKit with request interception');
  const s = await session(browser);
  const { page } = s;
  const hits = {};
  await page.route('**/api/friends/attachments/*', (route) => {
    const id = decodeURIComponent(new URL(route.request().url()).pathname.split('/').pop());
    hits[id] = (hits[id] || 0) + 1;
    // The front layer (a1) and the middle one (a2) fail on their first request; a3 loads at once.
    if ((id === 'a1' || id === 'a2') && hits[id] <= 1) return route.fulfill({ status: 500, contentType: 'application/json', body: '{}' });
    return route.fulfill({ status: 200, contentType: 'application/octet-stream', body: PNG_1x1 });
  });
  const opened = () => page.evaluate(async () => {
    const canvas = await import('/js/canvas.js');
    return ['a1', 'a2', 'a3'].some((id) => canvas.hasTab('attach:' + id) || canvas.hasTab('file:' + id));
  });
  try {
    await boot(s);
    await renderStackedMessage(page);
    const collapsed = page.locator('#fcc-host .pop-stack-view .media-layer');
    const expanded = page.locator('#fcc-host .pop-expand-view .media-layer');
    const leftover = (layer) => layer.locator('.nf-media-retry, .nf-media-failed-note, .nf-img-failed');
    await expect(collapsed.nth(0).locator('button.nf-media-retry')).toHaveCount(1);
    await expect(expanded.nth(1).locator('button.nf-media-retry')).toHaveCount(1);
    expect(hits).toEqual({ a1: 1, a2: 1, a3: 1 });

    // A real pointer click on the front layer's retry control retries that layer: it does not open the item and it
    // does not do nothing (the stack captures the pointer for its swipe; a control inside it must not be swallowed).
    await collapsed.nth(0).locator('button.nf-media-retry').click();
    await expect.poll(() => hits.a1).toBe(2);
    expect(await opened()).toBe(false);
    // The layer recovers on the collapsed face and on the expanded face of the same item.
    await expect(leftover(collapsed.nth(0))).toHaveCount(0);
    await expect(leftover(expanded.nth(0))).toHaveCount(0);

    // Retried from the expanded face, the collapsed face of that layer recovers too.
    await page.locator('#fcc-host .ds-media-stack__pill').click();
    await expanded.nth(1).locator('button.nf-media-retry').click();
    await expect.poll(() => hits.a2).toBe(2);
    await expect(leftover(expanded.nth(1))).toHaveCount(0);
    await expect(leftover(collapsed.nth(1))).toHaveCount(0);
    // Each layer was requested once more, the untouched one not at all, and nothing was opened or re-sent.
    expect(hits).toEqual({ a1: 2, a2: 2, a3: 1 });
    expect(await opened()).toBe(false);
    expect(await userFrames(page)).toEqual([]);
  } finally {
    await s.close();
  }
});

test('FCC-SCN-003 the Pop stack retries on a real pointer click on its collapsed front layer instead of opening the item', async ({ browser }) => {
  // The same stack component serves the main conversation's cards; its layers load from the uploads route first and
  // fall back to a ticket leg, which is refused here, so the first load of the front layer ends as a failed item.
  const s = await session(browser);
  const { page } = s;
  const hits = { count: 0 };
  await page.route('**/uploads/pop-1.png', (route) => {
    hits.count += 1;
    if (hits.count === 1) return route.fulfill({ status: 404, body: 'gone' });
    return route.fulfill({ status: 200, contentType: 'image/png', body: PNG_1x1 });
  });
  await page.route('**/uploads/pop-2.png', (route) => route.fulfill({ status: 200, contentType: 'image/png', body: PNG_1x1 }));
  await page.route('**/api/nf-ticket', (route) => route.fulfill({ status: 500, contentType: 'application/json', body: '{}' }));
  try {
    await boot(s);
    await page.evaluate(async () => {
      const pop = await import('/js/popArtifacts.js');
      window.__opened = [];
      const items = [1, 2].map((n) => ({ kind: 'image', name: `pop-${n}.png`, path: `/abs/pop-${n}.png`, resolver: () => `/uploads/pop-${n}.png` }));
      const area = pop.buildMediaStack(items, { onOpen: (item, index) => window.__opened.push(index) });
      const host = document.createElement('div');
      host.id = 'fcc-pop-stack';
      host.style.cssText = 'position:fixed;left:200px;top:120px;width:320px;';
      document.body.appendChild(host);
      host.appendChild(area);
    });
    const front = page.locator('#fcc-pop-stack .pop-stack-view .layer-front');
    const retry = front.locator('button.nf-media-retry');
    await expect(retry).toHaveCount(1);
    expect(hits.count).toBe(1);
    // A real pointer click on the retry control retries that layer; it neither opens the item nor does nothing.
    await retry.click();
    await expect.poll(() => hits.count).toBe(2);
    expect(await page.evaluate(() => window.__opened)).toEqual([]);
    await expect(front.locator('.nf-media-retry, .nf-media-failed-note, .nf-img-failed')).toHaveCount(0);
    await expect.poll(() => front.locator('img').first().evaluate((img) => img.naturalWidth > 0)).toBe(true);
    // The face itself still opens the shown item on a click, as before.
    await front.click({ position: { x: 20, y: 20 } });
    await expect.poll(() => page.evaluate(() => window.__opened)).toEqual([0]);
  } finally {
    await s.close();
  }
});

test('FCC-SCN-004 a failed conversation card image and a failed input thumbnail each offer a retry for that item', async ({ browser }) => {
  const s = await session(browser);
  const { page } = s;
  const uploads = { count: 0 };
  // The card image comes from the uploads route first; its ticket leg is refused, so the failure is terminal.
  await page.route('**/uploads/pop-1.png', (route) => {
    uploads.count += 1;
    if (uploads.count === 1) return route.fulfill({ status: 404, body: 'gone' });
    return route.fulfill({ status: 200, contentType: 'image/png', body: PNG_1x1 });
  });
  await page.route('**/api/nf-ticket', (route) => route.fulfill({ status: 500, contentType: 'application/json', body: '{}' }));
  try {
    await boot(s);
    // (a) conversation card image (shared card face used by the main conversation and by the contact windows)
    await page.evaluate(async () => {
      const pop = await import('/js/popArtifacts.js');
      const face = pop.buildMediaFlat({ kind: 'image', name: 'pop-1.png', path: '/abs/pop-1.png', resolver: () => '/uploads/pop-1.png' }, { onOpen: null, corner: null });
      const host = document.createElement('div');
      host.id = 'fcc-pop';
      document.body.appendChild(host);
      host.appendChild(face);
    });
    const failedImage = page.locator('#fcc-pop img.pop-img.nf-img-failed');
    await expect(failedImage).toHaveCount(1);
    const cardRetry = page.locator('#fcc-pop button.nf-media-retry');
    await expect(cardRetry).toHaveCount(1);
    await expect(page.locator('#fcc-pop .nf-media-failed-note')).toHaveCount(1);
    expect(uploads.count).toBe(1);
    await cardRetry.click();
    await expect.poll(() => uploads.count).toBe(2);
    await expect.poll(() => page.locator('#fcc-pop img.pop-img').evaluate((img) => img.naturalWidth > 0)).toBe(true);
    await expect(page.locator('#fcc-pop .nf-media-retry, #fcc-pop .nf-media-failed-note, #fcc-pop .nf-img-failed')).toHaveCount(0);

    // (b) input strip thumbnail: one good attachment and one whose image data cannot be decoded
    const png = PNG_1x1.toString('base64');
    await page.evaluate(async (good) => {
      const { activeView } = await import('/js/chatView.js');
      const chat = await import('/js/chat.js');
      window.__thumbErrors = 0;
      document.addEventListener('error', (e) => { if (e.target && e.target.classList && e.target.classList.contains('att-thumb')) window.__thumbErrors += 1; }, true);
      activeView.pendingAttachments.push(
        { type: 'image', name: 'good.png', mimeType: 'image/png', data: good, preview: 'data:image/png;base64,' + good },
        { type: 'image', name: 'bad.png', mimeType: 'image/png', data: 'AAAA', preview: 'data:image/png;base64,AAAA' },
      );
      chat.renderAttachmentPreview();
    }, png);
    const strip = page.locator('#attachment-preview');
    await expect(strip.locator('.att-thumb-wrap')).toHaveCount(2);
    const failedThumb = strip.locator('.att-thumb-wrap.is-failed');
    await expect(failedThumb).toHaveCount(1);
    // It stays visible as an item of the strip, not removed or hidden without trace.
    const box = await failedThumb.boundingBox();
    expect(box && box.width).toBeGreaterThan(8);
    expect(box && box.height).toBeGreaterThan(8);
    const thumbRetry = failedThumb.locator('button.att-thumb-retry');
    await expect(thumbRetry).toHaveCount(1);
    await expect(thumbRetry).toHaveAttribute('aria-label', /重试/);
    await page.evaluate(() => { document.querySelector('#attachment-preview .att-thumb-wrap:not(.is-failed) img').dataset.probe = 'kept'; window.__thumbErrors = 0; });
    await thumbRetry.focus();
    await expect(thumbRetry).toBeFocused();
    await page.keyboard.press('Enter');
    // The retry loads that thumbnail again (it fails again: the data is still broken) and shows the failed item again.
    await expect.poll(() => page.evaluate(() => window.__thumbErrors)).toBe(1);
    await expect(strip.locator('.att-thumb-wrap.is-failed button.att-thumb-retry')).toHaveCount(1);
    // The good thumbnail was never touched.
    expect(await strip.locator('img[data-probe="kept"]').evaluate((img) => img.naturalWidth > 0)).toBe(true);
  } finally {
    await s.close();
  }
});

test('FCC-SCN-005 reply attachment chips open their preview; remove and retry do not, and a secret question offers no entry', async ({ browser, browserName }) => {
  const s = await session(browser);
  const { page } = s;
  const uploads = [];
  await page.route('**/api/askuser/attachments**', (route) => {
    const body = (route.request().postDataBuffer() || Buffer.alloc(0)).toString('latin1');
    const name = (/filename="([^"]+)"/.exec(body) || [])[1] || '';
    uploads.push(name);
    // The first upload of bad.txt is refused so that the chip shows its retry control.
    if (name === 'bad.txt' && uploads.filter((n) => n === 'bad.txt').length === 1) {
      return route.fulfill({ status: 500, contentType: 'application/json', body: '{}' });
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ok: true, path: '/tmp/askatt/' + name, sha256: 'feedface', seq: uploads.length, mime: 'application/octet-stream' }) });
  });
  const files = {
    shot: { name: 'shot.png', mimeType: 'image/png', buffer: PNG_1x1 },
    notes: { name: 'notes.txt', mimeType: 'text/plain', buffer: Buffer.from('notes body') },
    bundle: { name: 'bundle.zip', mimeType: 'application/zip', buffer: Buffer.from('PK\u0003\u0004zip-bytes\u0000\u0001\u0002') },
    bad: { name: 'bad.txt', mimeType: 'text/plain', buffer: Buffer.from('bad body') },
    extra: { name: 'extra.txt', mimeType: 'text/plain', buffer: Buffer.from('extra body') },
  };
  const tabId = (file) => 'askreply:req-attach:' + file.name + ':' + file.buffer.length;
  const hasTab = (id) => page.evaluate(async (tab) => (await import('/js/canvas.js')).hasTab(tab), id);
  try {
    await boot(s);
    await s.frame({ type: 'askUser', sessionId: SID, items: [{ question: 'Send me the files' }], requestId: 'req-attach', agentName: 'Nebula' });
    const card = page.locator('.option-box').last();
    await expect(card.locator('.ask-answer-attach-zone')).toBeVisible();
    const chooser = page.waitForEvent('filechooser');
    await card.locator('.ask-answer-attach-empty').click();
    await (await chooser).setFiles(Object.values(files));
    const chips = card.locator('.ask-answer-attach-chip');
    await expect(chips).toHaveCount(5);
    await expect.poll(() => uploads.length).toBe(5);
    const chipOf = (file) => chips.filter({ hasText: file.name });
    const openOf = (file) => chipOf(file).locator('button.ask-answer-attach-open');
    // The chip is a group whose one preview control is a button; no interactive control sits inside another one
    // (a button's children are presentational for assistive technology, so a remove control inside it would be lost).
    await expect(chipOf(files.shot)).toHaveAttribute('role', 'group');
    await expect(openOf(files.shot)).toHaveAttribute('aria-label', /shot\.png/);
    await expect(openOf(files.shot)).toHaveCount(1);
    expect(await card.evaluate((box) => box.querySelectorAll('[role="button"] button, button button, button [role="button"]').length)).toBe(0);
    // The chip is not a tab stop of its own: the first control in a chip's tab order is its preview button, then its
    // remove button (structure, so it holds in every engine).
    await expect(chipOf(files.shot)).not.toHaveAttribute('tabindex', /.*/);
    expect(await chipOf(files.shot).evaluate((chip) => [...chip.querySelectorAll('button, [tabindex]')]
      .filter((node) => node.getAttribute('tabindex') !== '-1').map((node) => node.className))).toEqual(['ask-answer-attach-open', 'ask-answer-attach-rm']);
    // And with the real key where the engine tabs to buttons (macOS Safari/WebKit skips buttons unless the user enables it).
    if (browserName !== 'webkit') {
      await openOf(files.shot).focus();
      await page.keyboard.press('Tab');
      await expect(chipOf(files.shot).locator('.ask-answer-attach-rm')).toBeFocused();
    }

    // A click on the chip opens the image, Enter on the preview control opens the text file; both land in one Canvas
    // tab per item.
    await chipOf(files.shot).click({ position: { x: 20, y: 12 } });
    await expect.poll(() => hasTab(tabId(files.shot))).toBe(true);
    await openOf(files.notes).focus();
    await page.keyboard.press('Enter');
    await expect.poll(() => hasTab(tabId(files.notes))).toBe(true);
    // Space on a type no viewer can render: the user is told, nothing is silently ignored.
    await openOf(files.bundle).focus();
    await page.keyboard.press('Space');
    await expect(page.locator('.nebflow-toast-error .nebflow-toast-msg', { hasText: '暂不支持预览该类型：bundle.zip' })).toHaveCount(1);
    expect(await hasTab(tabId(files.bundle))).toBe(false);

    // The remove control removes; it does not open.
    await chipOf(files.extra).locator('.ask-answer-attach-rm').click();
    await expect(chips).toHaveCount(4);
    await page.waitForTimeout(300);
    expect(await hasTab(tabId(files.extra))).toBe(false);
    // The retry control re-uploads; it does not open either.
    const retry = chipOf(files.bad).locator('button', { hasText: '重试' });
    await expect(retry).toHaveCount(1);
    await retry.click();
    await expect.poll(() => uploads.filter((n) => n === 'bad.txt').length).toBe(2);
    await page.waitForTimeout(300);
    expect(await hasTab(tabId(files.bad))).toBe(false);

    // A secret question never gets the attachment entry.
    await s.frame({ type: 'askUser', sessionId: SID, items: [{ question: 'Your token?', secret: true }], requestId: 'req-secret', agentName: 'Nebula' });
    await expect(page.locator('.option-box').last().locator('.ask-answer-attach-zone')).toHaveCount(0);
  } finally {
    await s.close();
  }
});

/** Open a document in the Canvas, open its Ask dock and answer the dock's `askOpen` with `messages`. */
async function openAsk(s, docPath, messages) {
  const { page } = s;
  await page.evaluate((absPath) => window.dispatchEvent(new CustomEvent('workspace-open-item', {
    detail: { id: 'file:' + absPath, itemType: 'markdown', title: 'doc.md', content: '# Fixture', absPath, pinned: true },
  })), docPath);
  await page.locator('.ask-fab:not(.note-fab)').click();
  await expect.poll(() => page.evaluate(() => window.__wsSent.some((f) => f.type === 'askOpen'))).toBe(true);
  await s.frame({
    type: 'askState', sessionId: 'ask-' + createHash('sha256').update(docPath).digest('hex').slice(0, 8), fileName: 'doc.md',
    messages,
  });
}

async function askHistoryStaysLegible(browser, theme) {
  {
    const s = await session(browser, { colorScheme: theme, width: 1440, height: 900 });
    const { page } = s;
    try {
      await boot(s);
      await openAsk(s, '/tmp/qa-fcc-ask/opacity.md', Array.from({ length: 18 }, (_, i) => ({ role: i % 2 ? 'ai' : 'user', text: `Fixture message ${i}: readable historic content` })));
      const column = page.locator('.ask-col');
      await expect(column.locator('.ask-bub')).toHaveCount(18);
      // The entrance animation of the bubbles briefly animates opacity; steady state is what the contract is about.
      await page.waitForFunction(() => !document.querySelector('.ask-col').getAnimations({ subtree: true }).some((a) => a.playState === 'running'));
      const opacities = () => column.locator('.ask-bub').evaluateAll((nodes) => nodes.map((n) => getComputedStyle(n).opacity));
      expect((await opacities()).every((o) => o === '1')).toBe(true);

      // Scroll so that the first bubble sits 6px above the column's top edge: part of it is still visible.
      const placed = await column.evaluate((col) => {
        const first = col.querySelector('.ask-bub');
        const colTop = col.getBoundingClientRect().top;
        col.scrollTop += first.getBoundingClientRect().top - colTop + 6;
        col.dispatchEvent(new Event('scroll'));
        const box = first.getBoundingClientRect();
        return { offset: box.top - colTop, bottom: box.bottom - colTop, scrollTop: col.scrollTop, max: col.scrollHeight - col.clientHeight };
      });
      expect(placed.max).toBeGreaterThan(0);
      await page.waitForTimeout(250);
      // Every scroll position keeps every bubble fully opaque, including the one crossing the edge.
      for (const fraction of [0, 0.25, 0.5, 0.75, 1]) {
        await column.evaluate((col, f) => { col.scrollTop = (col.scrollHeight - col.clientHeight) * f; col.dispatchEvent(new Event('scroll')); }, fraction);
        await page.waitForTimeout(120);
        expect((await opacities()).every((o) => o === '1')).toBe(true);
      }
      await column.evaluate((col) => {
        const first = col.querySelector('.ask-bub');
        col.scrollTop += first.getBoundingClientRect().top - col.getBoundingClientRect().top + 6;
        col.dispatchEvent(new Event('scroll'));
      });
      await page.waitForTimeout(250);
      const crossing = await column.locator('.ask-bub').first().evaluate((bubble) => {
        const col = bubble.closest('.ask-col').getBoundingClientRect();
        const box = bubble.getBoundingClientRect();
        return { offset: box.top - col.top, visible: box.bottom - col.top, opacity: getComputedStyle(bubble).opacity, text: bubble.innerText };
      });
      expect(crossing.offset).toBeLessThan(0);
      expect(crossing.visible).toBeGreaterThan(12);
      expect(crossing.opacity).toBe('1');
      expect(crossing.text).toContain('Fixture message 0');
      // No stylesheet or inline rule dims a bubble either.
      expect(await column.locator('.ask-bub').evaluateAll((nodes) => nodes.filter((n) => n.style.opacity !== '').length)).toBe(0);

      // The dock is still operable after scrolling.
      const input = page.locator('.ask-input textarea');
      await input.fill('follow-up');
      await page.locator('.ask-send').click();
      await expect.poll(() => page.evaluate(() => window.__wsSent.filter((f) => f.type === 'askQuestion' && f.content === 'follow-up').length)).toBe(1);
    } finally {
      await s.close();
    }
  }
}

test('FCC-SCN-006 light: an Ask history bubble crossing the column top keeps opacity 1 and the dock stays operable', async ({ browser }) => askHistoryStaysLegible(browser, 'light'));
test('FCC-SCN-006 dark: an Ask history bubble crossing the column top keeps opacity 1 and the dock stays operable', async ({ browser }) => askHistoryStaysLegible(browser, 'dark'));

/** One key press through the browser's own key pipeline (what the page sees as a real keydown/keyup). */
async function pressKey(cdp, keyCode) {
  const common = { key: 'Enter', code: 'Enter', windowsVirtualKeyCode: keyCode, nativeVirtualKeyCode: keyCode };
  await cdp.send('Input.dispatchKeyEvent', { type: 'rawKeyDown', ...common, ...(keyCode === 13 ? { text: '\r' } : {}) });
  await cdp.send('Input.dispatchKeyEvent', { type: 'keyUp', ...common });
}

/**
 * Drive one composer with the browser's real input-method pipeline: a composition is started and extended,
 * the confirming Enter arrives while the composition is still active (Chromium order), the candidate is committed,
 * and a keyCode-229 Enter arrives after compositionend (WebKit order). None of them may send. A later independent
 * Enter must send exactly once.
 */
async function confirmCandidateThenSend(page, cdp, field, sentCount, expectedSent) {
  await field.focus();
  await cdp.send('Input.imeSetComposition', { text: 'ni', selectionStart: 2, selectionEnd: 2 });
  await cdp.send('Input.imeSetComposition', { text: 'nihao', selectionStart: 5, selectionEnd: 5 });
  expect(await field.evaluate((node) => node.dataset.imeComposing)).toBe('1');
  await pressKey(cdp, 13);
  expect(await sentCount()).toBe(expectedSent);
  await cdp.send('Input.insertText', { text: '你好' });
  await pressKey(cdp, 229);
  expect(await sentCount()).toBe(expectedSent);
  await expect(field).toHaveValue('你好');
  await pressKey(cdp, 13);
  await expect.poll(sentCount).toBe(expectedSent + 1);
}

test('FCC-SCN-007 main composer and Ask dock: the Enter that confirms an input-method candidate sends nothing, the next Enter sends once', async ({ browser, browserName }) => {
  test.skip(browserName !== 'chromium', 'only Chromium exposes the input-method composition pipeline to automation (CDP Input.imeSetComposition)');
  const s = await session(browser, { width: 1440, height: 900 });
  const { page } = s;
  try {
    await boot(s);
    const cdp = await page.context().newCDPSession(page);
    // Main composer: a message frame has no `type` and carries `content`.
    await confirmCandidateThenSend(page, cdp, page.locator('#input'), async () => (await userFrames(page)).length, 0);
    expect((await userFrames(page))[0].content).toBe('你好');
    await finishTurn(s);

    // Ask dock.
    await openAsk(s, '/tmp/qa-fcc-ask/ime.md', []);
    const askFrames = () => page.evaluate(() => window.__wsSent.filter((f) => f.type === 'askQuestion').length);
    await confirmCandidateThenSend(page, cdp, page.locator('.ask-input textarea'), askFrames, 0);
    expect(await page.evaluate(() => window.__wsSent.filter((f) => f.type === 'askQuestion').map((f) => f.content))).toEqual(['你好']);
  } finally {
    await s.close();
  }
});

const visiblePills = (page) => page.locator('.scroll-new-pill:not(.hidden)');

test('FCC-SCN-008 main conversation: scrolling away, new messages and returning keep a single return entry', async ({ browser }) => {
  const s = await session(browser, { width: 1440, height: 900 });
  const { page } = s;
  const messages = [];
  for (let i = 0; i < 40; i += 1) {
    messages.push({ type: 'user', text: `User message ${i + 1}: please continue with item ${i + 1}.`, ts: Date.now() - (80 - i) * 8000 });
    messages.push({ type: 'ai', text: `Reply ${i + 1}: done with this item, summary and next steps follow.`, ts: Date.now() - (80 - i) * 8000 + 4000 });
  }
  try {
    await boot(s, { ...BOOT, messages });
    await expect.poll(() => page.evaluate(() => { const c = document.getElementById('chat'); return c.scrollHeight - c.scrollTop - c.clientHeight; })).toBeLessThan(60);
    await expect(visiblePills(page)).toHaveCount(0);

    // Away from the bottom with nothing unread: one "back to bottom" entry. The scroll is a real wheel gesture: the
    // opening pin to the bottom only yields to user input, never to a script writing scrollTop.
    await page.locator('#chat').hover();
    await page.mouse.wheel(0, -30000);
    await expect.poll(() => page.evaluate(() => document.getElementById('chat').scrollTop)).toBeLessThan(100);
    await expect(visiblePills(page)).toHaveCount(1);
    await expect(visiblePills(page)).toContainText('回到底部');

    // Real new messages arrive while away: the same single entry now counts them.
    for (let n = 1; n <= 2; n += 1) {
      await s.frame({ type: 'user', sessionId: SID, injected: true, source: 'delegate', sender: 'Nebula/nebflow', text: `Delegate notice ${n}.`, timestamp: Date.now() });
      await expect(visiblePills(page)).toHaveCount(1);
      await expect(visiblePills(page)).toContainText(String(n));
    }
    expect(await page.locator('.scroll-new-pill').count()).toBeGreaterThanOrEqual(1);

    // One click returns to the bottom and clears the entry; it does not come back on its own.
    await visiblePills(page).click();
    await expect(visiblePills(page)).toHaveCount(0);
    await expect.poll(() => page.evaluate(() => { const c = document.getElementById('chat'); return c.scrollHeight - c.scrollTop - c.clientHeight; })).toBeLessThan(60);
    await page.waitForTimeout(300);
    await expect(visiblePills(page)).toHaveCount(0);
  } finally {
    await s.close();
  }
});

// ── Desktop conversations in the main chat area (FCC-REQ-007) ──────────────────────────────────────────────────────
const NOW = Date.now();
// Message ids are disjoint between conversations, so a message of one can never be mistaken for a message of another.
const conversationMessages = (label, peer, count, firstId) => Array.from({ length: count }, (_, i) => ({
  id: firstId + i, senderId: i % 2 ? peer : 'me', kind: 'text',
  body: `${label} message ${i + 1}: readable text for scrolling`, createdAt: new Date(NOW - (count - i) * 60000).toISOString(),
}));
const SOCIAL_SEED = {
  self: { userId: 'me', username: 'me', email: 'me@example.com', displayName: 'Me', avatar: '' },
  users: [],
  friends: [
    { userId: 'u-lin', neblinkId: 'lin@example.com', name: '林小满', avatarUrl: '', since: new Date().toISOString() },
    { userId: 'u-wang', neblinkId: 'wang@example.com', name: '王二', avatarUrl: '', since: new Date().toISOString() },
    // Friends nobody has written to yet: their conversation does not exist until the first message is sent.
    { userId: 'u-new1', neblinkId: 'new1@example.com', name: '新朋友一', avatarUrl: '', since: new Date().toISOString() },
    { userId: 'u-new2', neblinkId: 'new2@example.com', name: '新朋友二', avatarUrl: '', since: new Date().toISOString() },
  ],
  incoming: [], outgoing: [],
  conversations: [
    { conversationId: 'c-lin', friend: { userId: 'u-lin', neblinkId: 'lin@example.com', name: '林小满', avatarUrl: '' }, lastMessage: null, unreadCount: 0 },
    { conversationId: 'c-wang', friend: { userId: 'u-wang', neblinkId: 'wang@example.com', name: '王二', avatarUrl: '' }, lastMessage: null, unreadCount: 0 },
  ],
  messages: { 'c-lin': conversationMessages('lin', 'u-lin', 40, 100), 'c-wang': conversationMessages('wang', 'u-wang', 8, 500) },
  groups: [{ groupId: 'g-demo', title: '项目群', role: 'owner', memberCount: 2, lastMessage: null, unreadCount: 0, lastMessageId: 0, createdAt: 0 }],
  groupInvites: [],
  groupMembers: { 'g-demo': [
    { userId: 'me', username: null, display_name: 'Me', avatar: null, role: 'owner', joinedAt: 0 },
    { userId: 'u-lin', username: 'lin', display_name: '林小满', avatar: null, role: 'member', joinedAt: 0 },
  ] },
};
const AGENT_HISTORY = Array.from({ length: 60 }, (_, i) => (i % 2
  ? { type: 'ai', text: `Agent reply ${(i + 1) / 2}: a readable answer for scrolling.`, ts: NOW - (120 - i) * 5000 }
  : { type: 'user', text: `Agent question ${i / 2 + 1}: please continue.`, ts: NOW - (120 - i) * 5000 }));

/** A desktop session with the friends feature on, a mocked friends REST surface and one other device. */
async function socialSession(browser, { delay = 10, colorScheme = 'light', sessions, beforeBoot } = {}) {
  const s = await session(browser, {
    width: 1440, height: 900, colorScheme,
    apiStubs: {
      '/api/neblink/status': { body: { loggedIn: true, device: { deviceId: 'd1', id: 'd1', name: 'This Mac', platform: 'macos', userDescription: '', avatarUrl: '' }, peers: [{ deviceId: 'dev-2', id: 'dev-2', deviceName: 'iPad', platform: 'ios', online: true, directOnline: true, userDescription: '' }] } },
    },
  });
  await s.page.addInitScript(([seed, ms]) => {
    localStorage.setItem('fm_api_mock', '1');
    localStorage.setItem('fm_api_mock_seed', seed);
    localStorage.setItem('fm_api_mock_delay', String(ms));
  }, [JSON.stringify(SOCIAL_SEED), delay]);
  if (beforeBoot) await beforeBoot(s.page);
  await boot(s, { ...BOOT, config: { llm: { providers: {} }, features: { friends: true } }, messages: AGENT_HISTORY, sessions });
  return s;
}

/** Show a sidebar panel (its activity button toggles, so it is pressed only when the wanted content is not visible). */
async function showPanel(page, button, wanted) {
  if (!(await wanted.first().isVisible())) await page.click(button);
  await expect(wanted.first()).toBeVisible();
}

const CONVERSATIONS = {
  friend: { title: '林小满', open: async (page) => {
    const row = page.locator('#fm-conversations .fm-conv-row[data-conversation-id="c-lin"]');
    await showPanel(page, '#messages-btn', row);
    await row.click();
  } },
  group: { title: '项目群', open: async (page) => {
    const row = page.locator('#fm-conversations .fm-conv-row', { hasText: '项目群' });
    await showPanel(page, '#messages-btn', row);
    await row.click();
  } },
  device: { title: 'iPad', open: async (page) => {
    const entry = page.locator('#fm-contacts-body .fm-nf-entry', { hasText: '设备' });
    await showPanel(page, '#contacts-btn', entry);
    const row = page.locator('#fm-contacts-body .fm-device-row', { hasText: 'iPad' });
    if (!(await row.first().isVisible())) await entry.first().click();
    await row.first().click();
  } },
};

const mainAreaState = (page) => page.evaluate(() => {
  const overlay = document.getElementById('fm-chat-overlay');
  const modal = document.querySelector('.fm-modal');
  const main = document.getElementById('main').getBoundingClientRect();
  const box = modal ? modal.getBoundingClientRect() : null;
  const avatar = document.getElementById('activity-avatar');
  return {
    overlayParent: overlay && overlay.parentElement && overlay.parentElement.id,
    dialogs: document.querySelectorAll('#fm-chat-overlay [role="dialog"], .cfg-modal-overlay#fm-chat-overlay').length,
    widthShare: box ? +(box.width / main.width).toFixed(3) : 0,
    insideMain: !!box && box.left >= main.left - 1 && box.right <= main.right + 1 && box.top >= main.top - 1 && box.bottom <= main.bottom + 1,
    headName: (document.querySelector('#header .fm-main-head .fm-modal-name') || {}).textContent || '',
    sessionNameDisplay: getComputedStyle(document.getElementById('session-name')).display,
    bypassDisplay: getComputedStyle(document.getElementById('bypass-dropdown')).display,
    searchDisplay: getComputedStyle(document.getElementById('search-btn')).display,
    face: avatar.dataset.face || 'user',
    avatarTitle: avatar.title,
    agentFaceDisplay: getComputedStyle(avatar.querySelector('.activity-avatar-agent') || avatar).display,
    chatVisibility: getComputedStyle(document.getElementById('chat')).visibility,
    chatInert: document.getElementById('chat').inert,
    inputAreaInert: document.getElementById('input-area').inert,
    headParts: document.querySelectorAll('#header .fm-main-head').length,
  };
});

async function conversationInMainArea(browser, kind) {
  {
    const s = await socialSession(browser);
    const { page } = s;
    const target = CONVERSATIONS[kind];
    try {
      const before = await mainAreaState(page);
      expect(before).toMatchObject({ overlayParent: null, face: 'user', chatVisibility: 'visible', chatInert: false, headParts: 0 });
      expect(before.sessionNameDisplay).not.toBe('none');

      await target.open(page);
      await expect(page.locator('#fm-chat-overlay .fm-input')).toBeVisible();
      // In the main area: not a dialog, not a floating window, filling the main column.
      const open = await mainAreaState(page);
      expect(open.overlayParent).toBe('main');
      expect(open.dialogs).toBe(0);
      expect(open.insideMain).toBe(true);
      expect(open.widthShare).toBeGreaterThan(0.95);
      // The header names the conversation; the Agent's session controls are gone from it, the search stays.
      expect(open.headName).toBe(target.title);
      expect(open.sessionNameDisplay).toBe('none');
      expect(open.bypassDisplay).toBe('none');
      expect(open.searchDisplay).not.toBe('none');
      // The activity-bar avatar flipped to the Agent face; the Agent view is hidden and inert, not destroyed.
      expect(open.face).toBe('agent');
      expect(open.avatarTitle).toBe('返回智能体对话');
      expect(open.agentFaceDisplay).not.toBe('none');
      expect(open.chatVisibility).toBe('hidden');
      expect(open.chatInert).toBe(true);
      expect(open.inputAreaInert).toBe(true);
      expect(await page.locator('#chat .row').count()).toBeGreaterThan(0);
      // The header search is that conversation's search (scoped), not the Agent history search.
      await page.locator('#search-btn').click();
      await expect(page.locator('#search-overlay')).toHaveAttribute('data-conv-scope', '1');
      await page.keyboard.press('Escape');
      await expect(page.locator('#search-overlay.on')).toHaveCount(0);

      // Clicking the flipped avatar returns to the Agent and puts everything back.
      await page.locator('#activity-avatar').click();
      await expect(page.locator('#fm-chat-overlay')).toHaveCount(0);
      const back = await mainAreaState(page);
      expect(back).toMatchObject({ overlayParent: null, face: 'user', chatVisibility: 'visible', chatInert: false, inputAreaInert: false, headParts: 0 });
      expect(back.sessionNameDisplay).not.toBe('none');
      expect(back.bypassDisplay).not.toBe('none');

      // The keyboard works the same way: Enter on the focused avatar.
      await target.open(page);
      await expect(page.locator('#fm-chat-overlay .fm-input')).toBeVisible();
      await page.locator('#activity-avatar').focus();
      await page.keyboard.press('Enter');
      await expect(page.locator('#fm-chat-overlay')).toHaveCount(0);
      expect((await mainAreaState(page)).face).toBe('user');
      // And so does Escape from the composer.
      await target.open(page);
      await page.locator('#fm-chat-overlay .fm-input').focus();
      await page.keyboard.press('Escape');
      await expect(page.locator('#fm-chat-overlay')).toHaveCount(0);

      if (kind === 'group') {
        // The group's settings open from the name in the header, inside the window, below the header.
        await target.open(page);
        await page.locator('#header .fm-main-head .fm-modal-name').click();
        await expect(page.locator('.fm-modal-main > .fm-group-settings')).toHaveCount(1);
        await page.locator('#header .fm-main-head .fm-modal-name').click();
        await expect(page.locator('.fm-modal-main > .fm-group-settings')).toHaveCount(0);
      }
      if (kind === 'device') {
        // The device description control lives in the header with its icon drawn.
        await target.open(page);
        await expect(page.locator('#header .fm-main-head .fm-device-desc-btn svg')).toHaveCount(1);
      }
    } finally {
      await s.close();
    }
  }
}

test('FCC-SCN-009 friend: the conversation opens in the main chat area, the header follows it and the flipped avatar returns to the Agent', async ({ browser }) => conversationInMainArea(browser, 'friend'));
test('FCC-SCN-009 group: the conversation opens in the main chat area, the header follows it and the flipped avatar returns to the Agent', async ({ browser }) => conversationInMainArea(browser, 'group'));
test('FCC-SCN-009 device: the conversation opens in the main chat area, the header follows it and the flipped avatar returns to the Agent', async ({ browser }) => conversationInMainArea(browser, 'device'));

const flowOf = (page) => page.locator('#fm-chat-overlay .fm-flow');
const fieldOf = (page) => page.locator('#fm-chat-overlay .fm-input');
const flowTop = (page) => page.evaluate(() => Math.round(document.querySelector('#fm-chat-overlay .fm-flow').scrollTop));
const agentTop = (page) => page.evaluate(() => Math.round(document.getElementById('chat').scrollTop));
/** Wheel the scroller under the pointer upwards, then wait for the offset to stop changing. */
async function wheelUp(page, locator, delta, readTop) {
  await locator.hover();
  await page.mouse.wheel(0, -delta);
  let last = -1;
  await expect.poll(async () => { const now = await readTop(); const settled = now === last; last = now; return settled; }).toBe(true);
}

test('FCC-SCN-010 the Agent and each conversation keep their own draft, attachments and scroll position', async ({ browser }) => {
  const s = await socialSession(browser);
  const { page } = s;
  try {
    // The opening pin to the bottom yields only to user input; give it time to lapse, then read the Agent normally.
    await page.waitForTimeout(1400);
    await page.locator('#input').fill('agent draft');
    await attachFiles(page, [{ name: 'agent.txt', mimeType: 'text/plain', buffer: Buffer.from('agent attachment') }]);
    await wheelUp(page, page.locator('#chat'), 1500, () => agentTop(page));
    const agentBefore = await agentTop(page);
    expect(agentBefore).toBeGreaterThan(100);

    // Conversation A: its own draft and reading position.
    await CONVERSATIONS.friend.open(page);
    await expect(flowOf(page)).toContainText('lin message 40');
    await expect(fieldOf(page)).toHaveValue('');
    await fieldOf(page).fill('draft for A');
    await wheelUp(page, flowOf(page), 1200, () => flowTop(page));
    const aBefore = await flowTop(page);
    const aMax = await page.evaluate(() => { const f = document.querySelector('#fm-chat-overlay .fm-flow'); return f.scrollHeight - f.clientHeight; });
    expect(aBefore).toBeLessThan(aMax - 100);

    // Conversation B starts clean: nothing of A's draft, nothing of A's messages.
    await page.locator('#fm-conversations .fm-conv-row[data-conversation-id="c-wang"]').click();
    await expect(flowOf(page)).toContainText('wang message 8');
    await expect(fieldOf(page)).toHaveValue('');
    expect(await flowOf(page).innerText()).not.toContain('lin message');
    await fieldOf(page).fill('draft for B');

    // Back to A: its draft and reading position, and only its own messages.
    await page.locator('#fm-conversations .fm-conv-row[data-conversation-id="c-lin"]').click();
    await expect(flowOf(page)).toContainText('lin message');
    await expect(fieldOf(page)).toHaveValue('draft for A');
    await expect.poll(() => flowTop(page)).toBeGreaterThanOrEqual(aBefore - 3);
    expect(await flowTop(page)).toBeLessThanOrEqual(aBefore + 3);
    expect(await flowOf(page).innerText()).not.toContain('wang message');

    // B kept its own draft too.
    await page.locator('#fm-conversations .fm-conv-row[data-conversation-id="c-wang"]').click();
    await expect(fieldOf(page)).toHaveValue('draft for B');

    // Back to the Agent: draft, attachment and scroll position are exactly as they were.
    await page.locator('#activity-avatar').click();
    await expect(page.locator('#fm-chat-overlay')).toHaveCount(0);
    await expect(page.locator('#input')).toHaveValue('agent draft');
    expect(await pending(page)).toEqual([{ type: 'text', name: 'agent.txt', size: Buffer.from('agent attachment').toString('base64').length }]);
    await expect(page.locator('#attachment-preview > *')).toHaveCount(1);
    expect(Math.abs((await agentTop(page)) - agentBefore)).toBeLessThanOrEqual(2);

    // A message typed in a conversation goes to that conversation, never to the Agent's socket.
    await CONVERSATIONS.friend.open(page);
    await fieldOf(page).fill('hello from A');
    await fieldOf(page).press('Enter');
    await expect(page.locator('#fm-chat-overlay .fm-msg.out', { hasText: 'hello from A' })).toHaveCount(1);
    expect(await userFrames(page)).toEqual([]);
    await page.locator('#activity-avatar').click();
    await expect(page.locator('#input')).toHaveValue('agent draft');
  } finally {
    await s.close();
  }
});

test('FCC-SCN-010 a late response for a conversation that is no longer shown is not painted into the current one', async ({ browser }) => {
  // Every friends request takes 700 ms: long enough to leave a conversation before its answer comes back.
  const s = await socialSession(browser, { delay: 700 });
  const { page } = s;
  try {
    await CONVERSATIONS.friend.open(page);
    await expect(flowOf(page)).toContainText('lin message 40');
    // Open B (cold: its request is in flight) and move straight back to A (served from the local copy at once).
    await page.locator('#fm-conversations .fm-conv-row[data-conversation-id="c-wang"]').click();
    await page.locator('#fm-conversations .fm-conv-row[data-conversation-id="c-lin"]').click();
    await expect(flowOf(page)).toContainText('lin message 40');
    // B's answer arrives after A is on screen: it must not touch A's flow or header.
    await page.waitForTimeout(1800);
    expect(await flowOf(page).innerText()).not.toContain('wang message');
    // A's flow is exactly A's 40 messages, none dropped or replaced by B's late answer.
    await expect(page.locator('#fm-chat-overlay .fm-flow .fm-msg')).toHaveCount(40);
    await expect(flowOf(page)).toContainText('lin message 40');
    await expect(page.locator('#header .fm-main-head .fm-modal-name')).toHaveText('林小满');
    // B itself still opens properly afterwards.
    await page.locator('#fm-conversations .fm-conv-row[data-conversation-id="c-wang"]').click();
    await expect(flowOf(page)).toContainText('wang message 8');
    await expect(page.locator('#header .fm-main-head .fm-modal-name')).toHaveText('王二');
  } finally {
    await s.close();
  }
});

const visibleReturnEntries = (page) => page.evaluate(() => [...document.querySelectorAll('.scroll-new-pill')]
  .filter((pill) => !pill.classList.contains('hidden') && getComputedStyle(pill).visibility === 'visible')
  .map((pill) => ({ text: pill.textContent, inConversation: !!pill.closest('.fm-modal') })));

test('FCC-SCN-008 conversation: the conversation and the Agent never show a return entry at the same time', async ({ browser }) => {
  const s = await socialSession(browser);
  const { page } = s;
  try {
    await page.waitForTimeout(1400);
    // The Agent is scrolled away from its bottom: its entry is the one visible.
    await wheelUp(page, page.locator('#chat'), 30000, () => agentTop(page));
    await expect.poll(() => visibleReturnEntries(page)).toEqual([{ text: '回到底部', inConversation: false }]);

    // A conversation opens at its bottom: no entry at all, the Agent's is out of sight.
    await CONVERSATIONS.friend.open(page);
    await expect(flowOf(page)).toContainText('lin message 40');
    await expect.poll(() => visibleReturnEntries(page)).toEqual([]);
    // Scrolled away inside the conversation: exactly one entry, and it is the conversation's own.
    await wheelUp(page, flowOf(page), 30000, () => flowTop(page));
    await expect.poll(() => visibleReturnEntries(page)).toEqual([{ text: '回到底部', inConversation: true }]);
    // One click returns to the bottom of the conversation and clears it.
    await page.locator('.fm-modal .scroll-new-pill:not(.hidden)').click();
    await expect.poll(() => visibleReturnEntries(page)).toEqual([]);
    await expect.poll(() => page.evaluate(() => { const f = document.querySelector('#fm-chat-overlay .fm-flow'); return f.scrollHeight - f.scrollTop - f.clientHeight; })).toBeLessThan(60);

    // Back on the Agent: its own entry again, and nothing from the conversation.
    await page.locator('#activity-avatar').click();
    await expect.poll(() => visibleReturnEntries(page)).toEqual([{ text: '回到底部', inConversation: false }]);
  } finally {
    await s.close();
  }
});

test('FCC-SCN-007 friend, group and device composers: the Enter that confirms an input-method candidate sends nothing, the next Enter sends once', async ({ browser, browserName }) => {
  test.skip(browserName !== 'chromium', 'only Chromium exposes the input-method composition pipeline to automation (CDP Input.imeSetComposition)');
  const s = await socialSession(browser);
  const { page } = s;
  try {
    const cdp = await page.context().newCDPSession(page);
    for (const kind of ['friend', 'group', 'device']) {
      await CONVERSATIONS[kind].open(page);
      const field = fieldOf(page);
      await expect(field).toBeVisible();
      const sent = () => page.locator('#fm-chat-overlay .fm-msg.out', { hasText: '你好' }).count();
      await confirmCandidateThenSend(page, cdp, field, sent, 0);
      await page.locator('#activity-avatar').click();
      await expect(page.locator('#fm-chat-overlay')).toHaveCount(0);
    }
  } finally {
    await s.close();
  }
});

// ── Independent review follow-up: persisted copies, sends that outlive their window, other layers, the flipped avatar ──
const rowOf = (page, id) => page.locator(`#fm-conversations .fm-conv-row[data-conversation-id="${id}"]`);
/** The message bodies the local store keeps for a conversation (null: no copy). */
const storedBodies = (page, id) => page.evaluate(async (conversationId) => {
  const store = await import('/js/localStore.js');
  const entry = store.readMessages(conversationId);
  return entry ? entry.msgs.map((m) => String(m.body)) : null;
}, id);
/** Set or clear one of the friends mock's knobs in localStorage (the mock reads them on every request). */
const setMock = (page, name, value) => page.evaluate(([key, v]) => { if (v === null) localStorage.removeItem(key); else localStorage.setItem(key, v); }, [name, value]);

/** Give conversation A (林小满) and B (王二) a local copy by opening each once; B stays shown. */
async function warmBoth(page) {
  await CONVERSATIONS.friend.open(page);
  await expect(flowOf(page)).toContainText('lin message 40');
  await rowOf(page, 'c-wang').click();
  await expect(flowOf(page)).toContainText('wang message 8');
  await expect.poll(async () => ((await storedBodies(page, 'c-lin')) || []).length).toBe(40);
  await expect.poll(async () => ((await storedBodies(page, 'c-wang')) || []).length).toBe(8);
}

test('FCC-SCN-010 leaving a conversation while it is still syncing never stores another conversation under it', async ({ browser }) => {
  const s = await socialSession(browser);
  const { page } = s;
  try {
    await warmBoth(page);
    // Every friends request now takes 1.5 s: A is served from its copy at once and its sync is still in flight when B is picked.
    await setMock(page, 'fm_api_mock_delay', '1500');
    await rowOf(page, 'c-lin').click();
    await expect(flowOf(page)).toContainText('lin message 40');
    await rowOf(page, 'c-wang').click();
    await expect(flowOf(page)).toContainText('wang message 8');
    await page.waitForTimeout(3500);
    const lin = await storedBodies(page, 'c-lin');
    expect(lin).toHaveLength(40);
    expect(lin.every((body) => body.startsWith('lin message'))).toBe(true);
    expect((await storedBodies(page, 'c-wang')).every((body) => body.startsWith('wang message'))).toBe(true);
    // A, opened again, shows A and nothing of B.
    await setMock(page, 'fm_api_mock_delay', '10');
    await rowOf(page, 'c-lin').click();
    await expect(page.locator('#fm-chat-overlay .fm-flow .fm-msg')).toHaveCount(40);
    expect(await flowOf(page).innerText()).not.toContain('wang message');
  } finally {
    await s.close();
  }
});

test('FCC-SCN-010 closing a conversation and opening another while the first is still syncing never stores the second under the first', async ({ browser }) => {
  const s = await socialSession(browser);
  const { page } = s;
  try {
    await warmBoth(page);
    await setMock(page, 'fm_api_mock_delay', '1500');
    await rowOf(page, 'c-lin').click();
    await expect(flowOf(page)).toContainText('lin message 40');
    // Leave A (Escape from its composer) and open B before A's sync has answered.
    await fieldOf(page).focus();
    await page.keyboard.press('Escape');
    await expect(page.locator('#fm-chat-overlay')).toHaveCount(0);
    await rowOf(page, 'c-wang').click();
    await expect(flowOf(page)).toContainText('wang message 8');
    await page.waitForTimeout(3500);
    const lin = await storedBodies(page, 'c-lin');
    expect(lin).toHaveLength(40);
    expect(lin.every((body) => body.startsWith('lin message'))).toBe(true);
    expect((await storedBodies(page, 'c-wang')).every((body) => body.startsWith('wang message'))).toBe(true);
  } finally {
    await s.close();
  }
});

test('FCC-SCN-010 a send that outlives its window never stores the other conversation under the first', async ({ browser }) => {
  const s = await socialSession(browser);
  const { page } = s;
  try {
    await warmBoth(page);
    await rowOf(page, 'c-lin').click();
    await expect(flowOf(page)).toContainText('lin message 40');
    await page.waitForTimeout(300);
    await setMock(page, 'fm_api_mock_delay', '1500');
    await fieldOf(page).fill('hello from A');
    await fieldOf(page).press('Enter');
    await expect(page.locator('#fm-chat-overlay .fm-msg.out', { hasText: 'hello from A' })).toHaveCount(1);
    // B is picked before the answer to A's send comes back.
    await rowOf(page, 'c-wang').click();
    await expect(flowOf(page)).toContainText('wang message 8');
    await page.waitForTimeout(3500);
    const lin = await storedBodies(page, 'c-lin');
    expect(lin.filter((body) => !body.startsWith('lin message') && body !== 'hello from A')).toEqual([]);
    expect((await storedBodies(page, 'c-wang')).every((body) => body.startsWith('wang message'))).toBe(true);
    // A, opened again, shows A's own messages and nothing of B.
    await setMock(page, 'fm_api_mock_delay', '10');
    await rowOf(page, 'c-lin').click();
    await expect(flowOf(page)).toContainText('lin message 40');
    expect(await flowOf(page).innerText()).not.toContain('wang message');
  } finally {
    await s.close();
  }
});

test('FCC-SCN-010 a send that is refused after its window was left gives the text back to its own conversation and touches nothing else', async ({ browser }) => {
  const s = await socialSession(browser);
  const { page } = s;
  try {
    await warmBoth(page);
    await rowOf(page, 'c-lin').click();
    await expect(flowOf(page)).toContainText('lin message 40');
    await page.waitForTimeout(300);
    await setMock(page, 'fm_api_mock_delay', '1500');
    await setMock(page, 'fm_api_mock_send_error', 'not_friends'); // a final refusal: the text comes back, no retry key
    await fieldOf(page).fill('text for A');
    await fieldOf(page).press('Enter');
    await rowOf(page, 'c-wang').click();
    await expect(flowOf(page)).toContainText('wang message 8');
    await fieldOf(page).fill('draft for B');
    await page.waitForTimeout(3000); // the refusal for A has arrived
    // B's window is exactly as the user left it, and the user is told about the failure.
    await expect(fieldOf(page)).toHaveValue('draft for B');
    await expect(page.locator('#header .fm-main-head .fm-modal-name')).toHaveText('王二');
    await expect(page.locator('.nebflow-toast-error .nebflow-toast-msg').first()).toBeVisible();
    // A's text is waiting in A.
    await setMock(page, 'fm_api_mock_delay', '10');
    await setMock(page, 'fm_api_mock_send_error', null);
    await rowOf(page, 'c-lin').click();
    await expect(flowOf(page)).toContainText('lin message 40');
    await expect(fieldOf(page)).toHaveValue('text for A');

    // A refusal that is not final (a retry would make sense) loses the failed bubble with the window: the text still
    // goes back to the draft of the conversation it was typed in.
    await setMock(page, 'fm_api_mock_delay', '1500');
    await setMock(page, 'fm_api_mock_send_error', 'rate_limited');
    await fieldOf(page).fill('second text for A');
    await fieldOf(page).press('Enter');
    await rowOf(page, 'c-wang').click();
    await expect(flowOf(page)).toContainText('wang message 8');
    await expect(fieldOf(page)).toHaveValue('draft for B');
    await page.waitForTimeout(3000);
    await expect(fieldOf(page)).toHaveValue('draft for B');
    await setMock(page, 'fm_api_mock_delay', '10');
    await setMock(page, 'fm_api_mock_send_error', null);
    await rowOf(page, 'c-lin').click();
    await expect(flowOf(page)).toContainText('lin message 40');
    await expect(fieldOf(page)).toHaveValue('second text for A');

    // Back in A before the refusal arrives: the text lands in the window that shows A by then, not in a draft nobody sees.
    await setMock(page, 'fm_api_mock_delay', '1500');
    await setMock(page, 'fm_api_mock_send_error', 'not_friends');
    await fieldOf(page).fill('third text for A');
    await fieldOf(page).press('Enter');
    await rowOf(page, 'c-wang').click();
    await expect(flowOf(page)).toContainText('wang message 8');
    await rowOf(page, 'c-lin').click();
    await expect(flowOf(page)).toContainText('lin message 40');
    await expect(fieldOf(page)).toHaveValue('');
    await expect(fieldOf(page)).toHaveValue('third text for A', { timeout: 6000 });
    await expect(page.locator('#fm-chat-overlay .fm-msg.out', { hasText: 'third text for A' })).toHaveCount(0);
  } finally {
    await s.close();
  }
});

test('FCC-SCN-010 a group send that is refused for good after its window was left does not close the conversation that is shown', async ({ browser }) => {
  const s = await socialSession(browser);
  const { page } = s;
  try {
    await warmBoth(page);
    await CONVERSATIONS.group.open(page);
    await expect(page.locator('#header .fm-main-head .fm-modal-name')).toHaveText('项目群');
    await expect(fieldOf(page)).toBeVisible();
    await page.waitForTimeout(300);
    await setMock(page, 'fm_api_mock_delay', '1500');
    await setMock(page, 'fm_api_mock_send_error', 'group_disbanded');
    await fieldOf(page).fill('hello group');
    await fieldOf(page).press('Enter');
    await rowOf(page, 'c-wang').click();
    await expect(flowOf(page)).toContainText('wang message 8');
    await page.waitForTimeout(3000); // the refusal for the group has arrived
    await expect(page.locator('#fm-chat-overlay')).toHaveCount(1);
    await expect(page.locator('#header .fm-main-head .fm-modal-name')).toHaveText('王二');
    await expect(flowOf(page)).toContainText('wang message 8');
  } finally {
    await s.close();
  }
});

test('FCC-SCN-010 two conversations that do not exist yet keep separate drafts', async ({ browser }) => {
  const s = await socialSession(browser);
  const { page } = s;
  try {
    const openNew = (userId, name) => page.evaluate(async ([id, label]) => (await import('/js/messages.js'))
      .openChatWithFriend({ userId: id, neblinkId: id + '@example.com', name: label, avatarUrl: '' }), [userId, name]);
    await openNew('u-new1', '新朋友一');
    await expect(page.locator('#header .fm-main-head .fm-modal-name')).toHaveText('新朋友一');
    await fieldOf(page).fill('draft one');
    await openNew('u-new2', '新朋友二');
    await expect(page.locator('#header .fm-main-head .fm-modal-name')).toHaveText('新朋友二');
    await expect(fieldOf(page)).toHaveValue('');
    await fieldOf(page).fill('draft two');
    await openNew('u-new1', '新朋友一');
    await expect(page.locator('#header .fm-main-head .fm-modal-name')).toHaveText('新朋友一');
    await expect(fieldOf(page)).toHaveValue('draft one');
    await openNew('u-new2', '新朋友二');
    await expect(fieldOf(page)).toHaveValue('draft two');
  } finally {
    await s.close();
  }
});

/** Paste an image the way the browser delivers it: a `paste` event whose clipboard holds a PNG, aimed at `selector`. */
const pasteImageInto = (page, selector) => page.evaluate(async ([target, b64]) => {
  const node = document.querySelector(target);
  node.focus();
  const dt = new DataTransfer();
  dt.items.add(new File([Uint8Array.from(atob(b64), (c) => c.charCodeAt(0))], 'pasted.png', { type: 'image/png' }));
  const event = new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true });
  node.dispatchEvent(event);
  await new Promise((resolve) => setTimeout(resolve, 800));
  const { activeView } = await import('/js/chatView.js');
  return { agentPending: activeView.pendingAttachments.map((a) => a.name), strip: document.getElementById('attachment-preview').children.length };
}, [selector, PNG_1x1.toString('base64')]);

test('FCC-SCN-015 an image pasted into a conversation input never lands in the hidden Agent composer', async ({ browser }) => {
  const s = await socialSession(browser);
  const { page } = s;
  try {
    await CONVERSATIONS.friend.open(page);
    await expect(fieldOf(page)).toBeVisible();
    // Aimed at the conversation: the Agent's draft area gets nothing it cannot show.
    expect(await pasteImageInto(page, '#fm-chat-overlay .fm-input')).toEqual({ agentPending: [], strip: 0 });
    // Back on the Agent, a paste is still the Agent's.
    await page.locator('#activity-avatar').click();
    await expect(page.locator('#fm-chat-overlay')).toHaveCount(0);
    expect((await pasteImageInto(page, '#input')).agentPending).toEqual(['pasted.png']);
  } finally {
    await s.close();
  }
});

test('FCC-SCN-015 the Escape that closes another layer does not also leave the conversation', async ({ browser }) => {
  const s = await socialSession(browser);
  const { page } = s;
  try {
    await CONVERSATIONS.friend.open(page);
    await expect(fieldOf(page)).toBeVisible();
    await page.click('#settings-btn');
    await expect(page.locator('#settings-overlay.on')).toHaveCount(1);
    await page.keyboard.press('Escape');
    await expect(page.locator('#settings-overlay.on')).toHaveCount(0);
    // Settings took that Escape; the conversation is still on screen.
    await expect(page.locator('#fm-chat-overlay')).toHaveCount(1);
    // An Escape that another handler of the page has consumed (a dropdown, an overlay closed by the global handler)
    // does not leave the conversation either, even when the focus is in its composer.
    await page.evaluate(() => { window.__consumeEscape = false; });
    await fieldOf(page).focus();
    await page.evaluate(() => {
      // A capturing listener runs before every bubbling one, so it stands in for a page handler that consumes the key first.
      document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && window.__consumeEscape) e.preventDefault(); }, true);
      window.__consumeEscape = true;
    });
    await page.keyboard.press('Escape');
    await page.waitForTimeout(300);
    await expect(page.locator('#fm-chat-overlay')).toHaveCount(1);
    // The next Escape, from the composer, leaves as before.
    await page.evaluate(() => { window.__consumeEscape = false; });
    await page.keyboard.press('Escape');
    await expect(page.locator('#fm-chat-overlay')).toHaveCount(0);
  } finally {
    await s.close();
  }
});

test('FCC-SCN-012 selecting or creating an Agent session while a conversation is shown brings the Agent view back', async ({ browser }) => {
  const sessions = ['s2', 's3'].map((id) => ({ id, name: id, agentName: 'Nebula' }));
  const s = await socialSession(browser, { sessions: [{ id: SID, name: 'Nebula', agentName: 'Nebula' }, ...sessions] });
  const { page } = s;
  try {
    const agentShown = () => mainAreaState(page).then((state) => ({ overlay: state.overlayParent, face: state.face, chatVisibility: state.chatVisibility, chatInert: state.chatInert, inputAreaInert: state.inputAreaInert }));
    const agentBack = { overlay: null, face: 'user', chatVisibility: 'visible', chatInert: false, inputAreaInert: false };
    const reopen = async () => {
      await CONVERSATIONS.friend.open(page);
      await expect(fieldOf(page)).toBeVisible();
    };
    // Every path that selects an Agent session (a search hit, a pending question, a session row) calls this function.
    await reopen();
    await page.evaluate(async () => (await import('/js/sidebar.js')).switchToSession('s2'));
    await expect(page.locator('#fm-chat-overlay')).toHaveCount(0);
    expect(await agentShown()).toEqual(agentBack);
    expect(await page.evaluate(async () => (await import('/js/state.js')).default.activeSessionId)).toBe('s2');
    // A session the gateway creates or activates arrives as a session list with another active id.
    await reopen();
    await s.frame({ type: 'sessionList', activeId: 's3', folders: [], sessions: [{ id: SID, name: 'Nebula', agentName: 'Nebula' }, ...sessions] });
    await expect(page.locator('#fm-chat-overlay')).toHaveCount(0);
    expect(await agentShown()).toEqual(agentBack);
    expect(await page.evaluate(async () => (await import('/js/state.js')).default.activeSessionId)).toBe('s3');
    // The session that is already current counts as a request to see the Agent too.
    await reopen();
    await page.evaluate(async () => (await import('/js/sidebar.js')).switchToSession('s3'));
    await expect(page.locator('#fm-chat-overlay')).toHaveCount(0);
    expect(await agentShown()).toEqual(agentBack);
    // An unrelated refresh of the session list (same active session) leaves the conversation alone.
    await reopen();
    await s.frame({ type: 'sessionList', activeId: 's3', folders: [], sessions: [{ id: SID, name: 'Nebula', agentName: 'Nebula' }, ...sessions] });
    await page.waitForTimeout(300);
    await expect(page.locator('#fm-chat-overlay')).toHaveCount(1);
  } finally {
    await s.close();
  }
});

const avatarFlipEvents = (page) => page.evaluate(() => {
  const avatar = document.getElementById('activity-avatar');
  window.__flipEvents = [];
  for (const type of ['animationstart', 'animationend', 'animationcancel']) avatar.addEventListener(type, () => window.__flipEvents.push(type));
});

test('FCC-SCN-013 the avatar flip runs to its end and the Agent tooltip survives the periodic status refresh', async ({ browser }) => {
  // The status refresh runs every 30 s; the page clock is faked so the test can jump over that wait.
  const s = await socialSession(browser, { beforeBoot: (page) => page.clock.install() });
  const { page } = s;
  try {
    await page.emulateMedia({ reducedMotion: 'no-preference' });
    await avatarFlipEvents(page);
    const avatar = page.locator('#activity-avatar');
    const accountTitle = await avatar.getAttribute('title');
    await CONVERSATIONS.friend.open(page);
    await expect(avatar).toHaveAttribute('data-face', 'agent');
    // The flip is not cut off half way: it starts, ends and is never cancelled.
    await expect.poll(() => page.evaluate(() => window.__flipEvents.slice())).toEqual(['animationstart', 'animationend']);
    const agentTitle = await avatar.getAttribute('title');
    expect(agentTitle).not.toBe(accountTitle);
    // One status refresh later the tooltip still says where a click goes.
    await page.clock.runFor(31000);
    await page.waitForTimeout(500);
    await expect(avatar).toHaveAttribute('data-face', 'agent');
    await expect(avatar).toHaveAttribute('title', agentTitle);
    await expect(avatar).toHaveAttribute('aria-label', agentTitle);
    // Back on the account face its tooltip is the account's again.
    await avatar.click();
    await expect(page.locator('#fm-chat-overlay')).toHaveCount(0);
    await expect(avatar).not.toHaveAttribute('data-face', /.+/);
    await expect(avatar).toHaveAttribute('title', accountTitle);
  } finally {
    await s.close();
  }
});

test('FCC-SCN-013 returning to the Agent inside the flip puts the account tooltip back', async ({ browser }) => {
  const s = await socialSession(browser);
  const { page } = s;
  try {
    await page.emulateMedia({ reducedMotion: 'no-preference' });
    const before = await page.locator('#activity-avatar').getAttribute('title');
    expect(before).toBeTruthy();
    await showPanel(page, '#messages-btn', rowOf(page, 'c-lin'));
    const out = await page.evaluate(async () => {
      const avatar = document.getElementById('activity-avatar');
      document.querySelector('#fm-conversations .fm-conv-row[data-conversation-id="c-lin"]').click();
      for (let i = 0; i < 200 && !document.getElementById('fm-chat-overlay'); i += 1) await new Promise((resolve) => setTimeout(resolve, 2));
      avatar.click(); // well inside the first half of the flip
      await new Promise((resolve) => setTimeout(resolve, 450));
      return { title: avatar.title, label: avatar.getAttribute('aria-label'), face: avatar.dataset.face || 'user', overlay: !!document.getElementById('fm-chat-overlay') };
    });
    expect(out).toMatchObject({ title: before, face: 'user', overlay: false });
    expect(out.label).not.toBe('返回智能体对话');
  } finally {
    await s.close();
  }
});

test('FCC-SCN-014 the retry of a lost connection resends the last text even when the newest history entry has attachments only', async ({ browser }) => {
  const s = await session(browser);
  const { page } = s;
  try {
    await boot(s);
    const input = page.locator('#input');
    await input.fill('the older text');
    await input.press('Enter');
    await expect.poll(async () => (await userFrames(page)).length).toBe(1);
    await finishTurn(s);
    // A message with an attachment and no text is the newest history entry now.
    await attachFiles(page, [{ name: 'only.txt', mimeType: 'text/plain', buffer: Buffer.from('only an attachment') }]);
    await input.press('Enter');
    await expect.poll(async () => (await userFrames(page)).length).toBe(2);
    expect((await userFrames(page))[1].content).toBe('');
    await finishTurn(s);
    // The retry key of the terminal row sends again: it is not a silent no-op.
    await page.evaluate(async () => (await import('/js/chat.js')).renderTimeoutNotice());
    await page.locator('.row.error .error-card button').first().click();
    await expect.poll(async () => (await userFrames(page)).length).toBe(3);
    expect((await userFrames(page))[2].content).toBe('the older text');
  } finally {
    await s.close();
  }
});

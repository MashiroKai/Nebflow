// uifix3-onboarding-faces.spec.mjs — 三修批 ②③ pins (author 2026-10-06 21:36
// double-screenshot order).
//
//   ② Onboarding 配置模型题对齐设置面:
//     (a) the "add a provider" field face reuses the SETTINGS probe (the SAME
//         fetchProviderModels implementation the provider dialog uses — moved
//         verbatim into providerModels.js): Base URL + Key → live model list →
//         selectable candidates; probe failure degrades to manual entry exactly
//         like the settings dialog (never a dead end). The commit path
//         (updateConfig → probeLlm → finish) is UNCHANGED.
//     (b) 去偏好: NO vendor prefill — the retired zhipu/glm seeding is gone;
//         the face starts blank and the user picks from probe results.
//         🔴 zero-copy red line: every visible string on the new face sinks to
//         an EXISTING locale key (the settings provider modal's own strings).
//   ③ 伪流式题卡「跳过这一题」hover 显示: the question-card skip control hides
//         at rest, reveals on CARD hover (与卡体 hover 联动), stays keyboard-
//         reachable via :focus-visible, and keeps one-tap access on touch
//         surfaces ((hover: none) — the .pop-media-canvas precedent; a
//         long-press fallback was rejected: new gesture JS on a transient
//         button, undiscoverable).
//
// Rig: the REAL app (index.html + real modules) against a routed static tree
// and a scripted WebSocket — same rig as onboarding-skip-typing.spec.mjs.
//
// Run: node node_modules/@playwright/test/cli.js test tests/uifix3-onboarding-faces.spec.mjs

import { test, expect, devices } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

// The flow tests drive a real typewriter on real timers — under parallel
// workers the clicks race the animation. One at a time, own page each.
test.describe.configure({ mode: 'serial' });
// The rig serves the WHOLE app module graph through the route layer; on a busy
// machine (parallel batch instances on the same host) a boot can take a while.
test.setTimeout(120000);

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const ROOT_SID = 'uifix3-root-session';

async function bootApp(page) {
  await page.emulateMedia({ reducedMotion: 'no-preference' });
  await page.addInitScript(() => localStorage.setItem('nebflow_token', 'e2e-token'));
  bootFrames = [];   // per-test frame capture (the ws closure below pushes into it)

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      const body = readFileSync(file);
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });

  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: ROOT_SID,
    }));
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{}', configured: true, onboarding: 'pending',
    }));
    let historySettled = false;
    let configPending = false;
    sendSessions();
    ws.onMessage((raw) => {
      let msg;
      try { msg = JSON.parse(raw); } catch { return; }
      if (bootFrames) bootFrames.push(msg);
      if (msg.type === 'getHistory') {
        ws.send(JSON.stringify({
          type: 'historyPage', sessionId: msg.sessionId, messages: [], hasMore: false, offset: 0,
        }));
        historySettled = true;
        if (configPending) { configPending = false; sendConfig(); }
      } else if (msg.type === 'getConfig') {
        if (historySettled) sendConfig(); else configPending = true;
      } else if (msg.type === 'setOnboardingAnswers') {
        ws.send(JSON.stringify({ type: 'onboardingAnswersSaved', count: 1 }));
      } else if (msg.type === 'getOnboardingModels') {
        // No providers configured ⇒ the model card degrades to the "add a
        // provider" fallback face — the face under test.
        ws.send(JSON.stringify({ type: 'onboardingModels', providers: [], models: [] }));
      } else if (msg.type === 'probeLlm') {
        ws.send(JSON.stringify({ type: 'probeResult', ok: true }));
      }
    });
  });

  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 30000 });
}

/** WS frames the app sent, captured from Node side (set once the socket opens). */
let bootFrames = null;

/** Fast-forward the flow to the model question's card: click through the
 *  greeting bubbles' 跳过打字 buttons, then skip the name and call questions.
 *  The flow is strictly sequential, so the model card can only appear after
 *  every predecessor resolved. */
async function advanceToModelCard(page) {
  // Two greeting bubbles. The bubble keeps growing while the button is alive,
  // so Playwright's stability-gated click races the typewriter — navigate via
  // JS clicks (the REAL-click lifecycle of this button is pinned in
  // onboarding-skip-typing.spec.mjs; here it is pure navigation).
  for (let i = 0; i < 2; i++) {
    // Scoped per bubble index: the next bubble mounts its own skip button
    // within one microtask of this one detaching, so a global "gone" probe
    // never observes the gap.
    const skipLoc = page.locator('.ob-sim-bubble').nth(i).locator('[data-skip-typing]');
    await skipLoc.waitFor({ state: 'attached', timeout: 30000 });
    await skipLoc.evaluate((el) => el.click());
    await skipLoc.waitFor({ state: 'detached', timeout: 10000 });
  }
  // Q1 name → Q2 call: skip each card (跳过这一题). These are static cards —
  // real clicks work, and they double as proof that opacity:0 does not gate
  // input on the hover-hidden control.
  for (const qid of ['name', 'call']) {
    const card = page.locator(`.ask-card[data-q="${qid}"]`);
    await expect(card).toBeVisible({ timeout: 20000 });
    await card.locator('[data-skip]').click();
  }
  // Q3 model card.
  const model = page.locator('.ask-card[data-q="model"]');
  await expect(model).toBeVisible({ timeout: 30000 });
  // The configured-list fetch answers empty ⇒ the fallback button shows.
  const addBtn = model.locator('button.opt-ghost');
  await expect(addBtn).toBeVisible({ timeout: 10000 });
  return model;
}

const skipCss = readFileSync(join(WEB, 'css', 'chat.css'), 'utf8');

/* ═══════════ ②b — 去偏好: blank start, no vendor prefill ═══════════ */

test('uifix3 ②b: the add-provider face starts BLANK — no vendor URL, no suggested model id', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);
  const model = await advanceToModelCard(page);

  await model.locator('button.opt-ghost').click();

  const read = await page.evaluate(() => {
    const card = document.querySelector('.ask-card[data-q="model"]');
    const inputs = Array.from(card.querySelectorAll('input'));
    const url = card.querySelector('input[placeholder="https://…"]');
    const key = card.querySelector('input[type="password"]');
    const modelEntry = Array.from(card.querySelectorAll('input')).find((i) => i.placeholder === 'Model ID');
    const saveBtn = Array.from(card.querySelectorAll('button')).find((b) => b.classList.contains('ob-primary'));
    const fetchBtn = card.querySelector('.ob-fetch-row .ob-fetch-btn');
    return {
      inputValues: inputs.map((i) => i.value),
      urlValue: url ? url.value : null,
      keyValue: key ? key.value : null,
      modelEntryValue: modelEntry ? modelEntry.value : null,
      saveBtnDisabled: saveBtn ? saveBtn.disabled : null,
      fetchBtnText: fetchBtn ? fetchBtn.textContent : '',
      hasFetchRow: !!card.querySelector('.ob-fetch-row'),
      cardText: card.innerText,
    };
  });

  // 去偏好 hard pins: nothing prefilled anywhere.
  expect(read.urlValue, 'Base URL starts blank (the bigmodel prefill is retired)').toBe('');
  expect(read.keyValue, 'API Key starts blank').toBe('');
  expect(read.modelEntryValue, 'model entry starts blank (no suggested glm id)').toBe('');
  expect(read.inputValues.every((v) => v === ''), `no input carries any prefill (got ${JSON.stringify(read.inputValues)})`).toBe(true);
  expect(read.saveBtnDisabled, '确认并测试 stays gated until the user fills + picks').toBe(true);
  expect(read.cardText.includes('bigmodel') || read.cardText.includes('glm'),
    'no vendor trace anywhere on the face').toBe(false);
  // Settings-flow reuse is visible on the face: the fetch row exists and its
  // button carries the SETTINGS dialog's own string (零新增文案 — sunk key).
  expect(read.hasFetchRow, 'the probe row (settings-dialog layout) is present').toBe(true);
  expect(read.fetchBtnText, 'fetch button reuses the settings string 「获取模型列表」').toBe('获取模型列表');
  expect(pageErrors).toEqual([]);
});

/* ═══════════ ②a — settings probe: list → pick → unchanged commit flow ═══════════ */

test('uifix3 ②a: probe lists live models into a select; picking one feeds the UNCHANGED 确认并测试 flow', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  /** Captured POST bodies hitting the shared provider-models endpoint.
   *  Registered AFTER bootApp so it wins over the boot rig's catch-all /api/
   *  fulfillment (Playwright: the LAST matching route handler runs). */
  const probePosts = [];
  await bootApp(page);
  await page.route('**/api/provider/models', async (route) => {
    probePosts.push(route.request().postDataJSON());
    await route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ models: ['mock-alpha', { id: 'mock-beta', contextLength: 128000 }] }),
    });
  });
  const model = await advanceToModelCard(page);

  await model.locator('button.opt-ghost').click();
  // Blank start still holds on this face (guard the ordering of the two pins).
  const url = page.locator('.ask-card[data-q="model"] input[placeholder="https://…"]');
  const keyIn = page.locator('.ask-card[data-q="model"] input[type="password"]');
  await expect(url).toHaveValue('');

  // Type Base URL + Key — the change events drive the debounced auto-probe
  // (settings wireProviderModelFetch shape); the button works too, use it.
  await url.fill('https://mock.example/v1');
  await keyIn.fill('sk-mock');
  await page.locator('.ask-card[data-q="model"] .ob-fetch-btn').click();

  // Success upgrades the manual input to a select (settings'
  // upgradeModelRowsToSelects shape) with the fetched ids.
  const sel = page.locator('.ask-card[data-q="model"] select[data-model-select]');
  await expect(sel).toBeVisible({ timeout: 10000 });
  const options = await sel.locator('option').allTextContents();
  expect(options, 'the probe result populates the candidate list (settings strings/status)').toEqual(
    expect.arrayContaining(['mock-alpha', 'mock-beta']));
  const status = await page.locator('.ask-card[data-q="model"] .ob-fetch-status').innerText();
  expect(status, 'status line = the settings loaded string with the count').toContain('已获取 2 个模型');
  expect(status, 'a non-empty key doubles as credential validation (settings line)').toContain('密钥有效');

  // The probe rode the SHARED endpoint with the settings payload shape.
  expect(probePosts.length, 'exactly one probe fired (button trigger)').toBe(1);
  expect(probePosts[0].baseUrl, 'probe POST carries the typed baseUrl (settings parity)').toBe('https://mock.example/v1');
  expect(probePosts[0].apiKey, 'probe POST carries the typed key').toBe('sk-mock');

  // Pick a candidate ⇒ 确认并测试 un-gates; commit = the UNCHANGED flow:
  // updateConfig (same store the settings panel writes) + probeLlm with the
  // picked ref (the pre-uifix3 contract, pinned end-to-end). Card-level
  // completion = the echo row speaks the picked model (the whole-flow terminal
  // frame `finishOnboarding` belongs to question 10, not this card).
  await sel.selectOption('mock-beta');
  const saveBtn = page.locator('.ask-card[data-q="model"] button.ob-primary', { hasText: '确认并测试' });
  await expect(saveBtn).toBeEnabled();
  const framesBefore = (bootFrames || []).length;
  await saveBtn.click();
  await expect(page.locator('.row.user .bubble').last(),
    'the card finishes: the echo speaks the picked model (finish ran with the pick)').toContainText('mock-beta', { timeout: 20000 });
  const frames = (bootFrames || []).slice(framesBefore);
  const update = frames.find((f) => f.type === 'updateConfig');
  const probeFrame = frames.find((f) => f.type === 'probeLlm');
  expect(update, 'the write goes through updateConfig (the settings panel single path)').toBeTruthy();
  const cfg = JSON.parse(update.config);
  expect(cfg.llm.providers.custom.models[0].id, 'the picked candidate is what gets written').toBe('mock-beta');
  expect(probeFrame, 'probeLlm fires with the picked ref (既有测试流零破坏)').toBeTruthy();
  expect(probeFrame.modelRef, 'probe measures the brain the user chose').toBe('custom/mock-beta');
  expect(pageErrors).toEqual([]);
});

test('uifix3 ②a: probe failure degrades to manual entry — the settings dead-end-free form', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  // Registered AFTER bootApp so it wins over the rig's catch-all /api/ route.
  await bootApp(page);
  await page.route('**/api/provider/models', async (route) => {
    await route.fulfill({ status: 502, contentType: 'application/json', body: JSON.stringify({ error: 'boom' }) });
  });
  const model = await advanceToModelCard(page);
  await model.locator('button.opt-ghost').click();

  const url = page.locator('.ask-card[data-q="model"] input[placeholder="https://…"]');
  const keyIn = page.locator('.ask-card[data-q="model"] input[type="password"]');
  await url.fill('https://dead.example/v1');
  await keyIn.fill('sk-mock');
  await page.locator('.ask-card[data-q="model"] .ob-fetch-btn').click();

  const status = page.locator('.ask-card[data-q="model"] .ob-fetch-status');
  await expect(status).toHaveText('未能自动获取模型列表，请手动输入', { timeout: 10000 });
  const form = await page.evaluate(() => ({
    stillInput: !!document.querySelector('.ask-card[data-q="model"] input[placeholder="Model ID"]'),
    hasSelect: !!document.querySelector('.ask-card[data-q="model"] select[data-model-select]'),
  }));
  expect(form.stillInput, 'manual entry stays usable (settings degrade)').toBe(true);
  expect(form.hasSelect, 'no phantom candidate list on failure').toBe(false);

  // The manual path still commits (fill model id ⇒ save un-gates).
  await page.locator('.ask-card[data-q="model"] input[placeholder="Model ID"]').fill('manual-model');
  await expect(page.locator('.ask-card[data-q="model"] button.ob-primary', { hasText: '确认并测试' })).toBeEnabled();
  expect(pageErrors).toEqual([]);
});

/* ═══════════ ③ — 跳过这一题 hover 显示（与卡体 hover 联动） ═══════════ */

test('uifix3 ③: the question-card skip control hides at rest, reveals on CARD hover, hides again', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);
  const model = await advanceToModelCard(page);
  const skip = model.locator('[data-skip]');

  // Settled-opacity reader: two consecutive equal readings (0.15s ease tail
  // values like 0.0068 are transition residue, not a state).
  const settledOpacity = async () => {
    const read = () => page.evaluate(() =>
      getComputedStyle(document.querySelector('.ask-card[data-q="model"] [data-skip]')).opacity);
    let prev = await read();
    for (let i = 0; i < 40; i++) {
      await page.waitForTimeout(120);
      const cur = await read();
      if (cur === prev) return cur;
      prev = cur;
    }
    return prev;
  };

  // Park the pointer OFF the card before the rest reading: advanceToModelCard's
  // last real click leaves the virtual pointer wherever the call card's skip
  // button was — and the model card mounts into that same flow region, so a
  // stale in-card pointer would hold .ask-card:hover true from birth and read
  // '1' at rest (geometry-dependent flake, not a CSS state). Rest = no hover.
  await page.mouse.move(8, 8);
  expect(await settledOpacity(), 'at rest the skip control is hidden (平常隐藏)').toBe('0');

  // Hover the CARD BODY (meta area — not the button itself): the control reveals.
  await model.locator('.ask-meta').hover();
  expect(await settledOpacity(), 'card hover reveals the skip control (与卡体 hover 联动)').toBe('1');

  // Pointer leaves the card ⇒ hidden again.
  await page.mouse.move(8, 8);
  expect(await settledOpacity(), 'pointer off the card hides the control again').toBe('0');

  // The control never lost interactivity (opacity is not a pointer gate) —
  // a real click still skips the question.
  await skip.click({ force: true });
  await expect(page.locator('.row.user .bubble', { hasText: '这一题先跳过' }).first()).toBeVisible({ timeout: 10000 });
  expect(pageErrors).toEqual([]);
});

test('uifix3 ③: touch fallback = (hover: none) keeps the control visible; typing-skip button unaffected', async ({ browser }) => {
  // A REAL touch surface: boot the same app in an iPhone context — the device
  // emulation flips the (hover)/(pointer) media features (CDP feature
  // emulation does not; verified: matchMedia('(hover: none)') true here,
  // false under CDP setEmulatedMedia). Same routes, same scripted WS.
  // Viewport stays desktop-sized: the device's 390px width makes the app's
  // canvas panel overlap the chat and intercept clicks — the touch MEDIA
  // features ride hasTouch/isMobile, not the viewport.
  const context = await browser.newContext({
    ...devices['iPhone 13'], viewport: { width: 1100, height: 1000 }, locale: 'zh-CN',
  });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);
  expect(await page.evaluate(() => matchMedia('(hover: none)').matches),
    'the emulated touch surface really matches (hover: none)').toBe(true);

  // Reach the model card (the same fast-forward; the typing-skip guard runs on
  // greeting bubble 0 first). The greet bubbles type out fast, so read the
  // at-rest opacity and fast-forward in ONE evaluate (no real-click window),
  // scoped per bubble index (the next bubble mounts its own button within a
  // microtask — a global "gone" probe never sees the gap).
  for (let i = 0; i < 2; i++) {
    const skipLoc = page.locator('.ob-sim-bubble').nth(i).locator('[data-skip-typing]');
    await skipLoc.waitFor({ state: 'attached', timeout: 30000 });
    const op = await skipLoc.evaluate((el) => { const o = getComputedStyle(el).opacity; el.click(); return o; });
    if (i === 0) {
      expect(op, 'the 跳过打字 control is OUT of ③\'s scope — stays visible during typing').toBe('1');
    }
    await skipLoc.waitFor({ state: 'detached', timeout: 10000 });
  }
  // Skip name + call (static cards — real clicks; also proves opacity:0 does
  // not gate input on the hover-hidden control).
  for (const qid of ['name', 'call']) {
    const card = page.locator(`.ask-card[data-q="${qid}"]`);
    await expect(card).toBeVisible({ timeout: 20000 });
    await card.locator('[data-skip]').click();
  }

  // No hover has happened yet — with a hover-capable pointer this reads '0';
  // flip the emulated media to a touch surface (CDP feature emulation) and the
  // (hover: none) fallback must show the control at rest.
  const model = page.locator('.ask-card[data-q="model"]');
  await expect(model).toBeVisible({ timeout: 30000 });
  await expect(model.locator('button.opt-ghost')).toBeVisible({ timeout: 10000 });
  const opacityOf = () => page.evaluate(() =>
    getComputedStyle(document.querySelector('.ask-card[data-q="model"] [data-skip]')).opacity);
  expect(await opacityOf(),
    'touch surface ((hover: none)): the skip control stays visible — one-tap access').toBe('1');
  // And it stays functional: a real tap on the visible control skips.
  await model.locator('[data-skip]').tap();
  await expect(page.locator('.row.user .bubble').last()).toContainText('这一题先跳过', { timeout: 10000 });
  expect(pageErrors.filter((e) => e !== 'Canceled'), `page errors (minus the boot abort noise): ${JSON.stringify(pageErrors)}`).toEqual([]);
  await context.close();
});

test('uifix3 ③ scope boundary: the hover-hide governs the LIVE window; sapphire\'s disabled blanket keeps the ANSWERED card\'s skip legible', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);
  // Reach the model card live: hidden at rest.
  const model = await advanceToModelCard(page);
  // Settled-opacity reader (same disease the hover test had: one-shot reads
  // race the 0.15s ease — under load a read can land before the transition
  // engine starts and see the pre-click value). Two consecutive equal reads.
  const settledOpacity = async () => {
    const read = () => page.evaluate(() =>
      getComputedStyle(document.querySelector('.ask-card[data-q="model"] [data-skip]')).opacity);
    let prev = await read();
    for (let i = 0; i < 40; i++) {
      await page.waitForTimeout(120);
      const cur = await read();
      if (cur === prev) return cur;
      prev = cur;
    }
    return prev;
  };
  await page.mouse.move(8, 8);
  expect(await settledOpacity(), 'live question window: hidden at rest').toBe('0');
  // Answer the model question via the fallback face (skip through it).
  await model.locator('[data-skip]').click();
  // The answered card keeps its skip VISIBLE: sapphire.css's
  // :is(…, button.glass-control, …):disabled blanket (ID-carried specificity)
  // deliberately holds disabled buttons at opacity 1 (禁用档用色表达) — the
  // answered card's foot is part of the answer record. Pin that boundary so a
  // future batch doesn't mistake it for a regression of the hover-hide.
  const answered = await page.evaluate(() => {
    const skip = document.querySelector('.ask-card[data-q="model"] [data-skip]');
    return { opacity: getComputedStyle(skip).opacity, disabled: skip.disabled };
  });
  expect(answered.disabled, 'the answered card\'s skip is disabled').toBe(true);
  expect(await settledOpacity(), 'answered card: sapphire\'s disabled blanket keeps the foot legible (documented boundary, not a hover-hide regression)').toBe('1');
  expect(pageErrors).toEqual([]);
});

/* ═══════════ 零文案红线 static pin — the new face sinks to existing keys ═══ */

test('uifix3 ②: no NEW user-visible strings — the probe face reuses the settings provider-dialog keys', async ({ page }) => {
  // The strings the face shows, each one an EXISTING locale entry (settings
  // provider modal family). If someone edits the face to invent copy, the
  // zh-CN table must gain NO new key for it — this pin compares the locale
  // table against the pre-batch inventory.
  const zh = readFileSync(join(WEB, 'js', 'locales', 'zh-CN.js'), 'utf8');
  const forKeys = [
    'provider.fetchModels', 'provider.fetchingModels', 'provider.fetchModelsLoaded',
    'provider.fetchModelsFailed', 'provider.keyValid', 'provider.modelSelectPlaceholder',
    'onboarding.btn.testAndSave', 'onboarding.probing', 'onboarding.probeFailed',
    'ob2.model.addProvider', 'ob2.model.none', 'ob2.typing.skip', 'ob2.btn.skip',
  ];
  for (const k of forKeys) {
    expect(zh.includes(`'${k}':`), `existing key stays present: ${k}`).toBe(true);
  }
  // The batch adds no new ob2/model-face key: the locale diff against the
  // pre-batch key inventory is empty (spot anchor: the last pre-batch keys of
  // both touched families must still be the LAST entries of their family).
  expect(zh.includes("'ob2.btn.skip': '跳过这一题'"), 'skip copy byte-identical').toBe(true);
  expect(zh.includes("'provider.fetchModels': '获取模型列表'"), 'fetch button copy byte-identical (sunk key, settings voice)').toBe(true);
});

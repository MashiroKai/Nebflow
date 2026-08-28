// askuser.spec.mjs — AskUserQuestion card E2E: single / multiple / mixed.
//
// Coverage split (frontend vs backend, per the task contract):
//  - THIS spec covers the frontend card: render, checkbox interaction,
//    confirm gating, answer payload assembly. Driven by importing the real
//    chat.js module in the page and capturing outgoing WS payloads via a
//    state.ws stub — a real question would burn an LLM turn for zero extra
//    coverage of the card layer.
//  - Backend schema decode + answer serialization: AskUserQuestionToolSpec
//    (Scala unit spec, sbt test).
//
// Run against an ISOLATED instance (never the 8080 live one):
//   sbt "run --home /tmp/nb-askuser-smoke --port 8094 --no-browser"
//   BASE_URL=http://localhost:8094 NEBFLOW_TOKEN=<auth.json> npx playwright test tests/askuser.spec.mjs
// NEBFLOW_TOKEN defaults to reading <NEBFLOW_HOME>/auth.json so plain
// `npx playwright test tests/askuser.spec.mjs` works with the smoke home.

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const BASE_URL = process.env.BASE_URL ?? 'http://localhost:8094';
const NEBFLOW_HOME = process.env.NEBFLOW_HOME ?? '/tmp/nb-askuser-smoke';

function defaultToken() {
  try {
    return JSON.parse(readFileSync(join(NEBFLOW_HOME, 'auth.json'), 'utf8'));
  } catch {
    return '';
  }
}
const TOKEN = process.env.NEBFLOW_TOKEN ?? defaultToken();

const SID = 'e2e-askuser-session';

/**
 * Load the app, then render an askUser card through the exact code path the
 * ws `askUser` handler uses (chat.renderAskUser), with state.ws stubbed so
 * every outgoing askUserAnswer payload is captured window.__captured.
 */
async function setupCard(page, items) {
  await page.goto(`${BASE_URL}/?token=${TOKEN}`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(600);

  // A fresh isolated home has no providers configured — the first-run
  // onboarding wizard overlay intercepts pointer events. Dismiss it BEFORE
  // stubbing state.ws so the onboarding-state write reaches the backend via
  // the real socket and the overlay stays dismissed for later loads.
  const overlay = page.locator('.onboarding-overlay');
  if (await overlay.isVisible().catch(() => false)) {
    const dismiss = overlay.locator('#ob-skip, #ob-no');
    if (await dismiss.count()) {
      await dismiss.first().click();
      await overlay.waitFor({ state: 'detached', timeout: 3000 }).catch(() => {});
    } else {
      throw new Error('Onboarding overlay present but no dismiss button found');
    }
  }

  await page.evaluate(() => localStorage.removeItem('nebflow_askuser_drafts'));

  // #chat has zero width until a session exists — and the Canvas panel can
  // claim the full width on load (body.canvas-open). Close it, create a
  // session over the real socket (no LLM traffic — just the session actor)
  // and wait for the chat panel to lay out.
  await page.evaluate(async () => {
    const { isCanvasOpen, closeCanvas } = await import('/js/canvas.js');
    if (isCanvasOpen()) closeCanvas();
    const { sendWs } = await import('/js/ws.js');
    sendWs({ type: 'createSession', name: 'e2e-askuser', agentName: 'Nebula' });
  });
  await page.waitForFunction(() => {
    const c = document.getElementById('chat');
    return c && c.getBoundingClientRect().width > 100;
  }, null, { timeout: 10000 });

  await page.evaluate(async (items) => {
    const { renderAskUser } = await import('/js/chat.js');
    const { chatViews, setActiveView } = await import('/js/chatView.js');
    const state = (await import('/js/state.js')).default;
    setActiveView(chatViews.primary);
    window.__captured = [];
    state.ws = { readyState: 1, send: (data) => window.__captured.push(JSON.parse(data)) };
    renderAskUser(items, 'e2e-askuser-session', 'E2E');
  }, items);
}

function lastCard(page) {
  return page.locator('.option-box').last();
}

// ============================================================
// Scenario 1 — single-select: behavior unchanged (regression guard)
// ============================================================

test.describe('AskUser card — single-select (unchanged)', () => {
  test('radio pick → scalar string payload, confirm gated until answered', async ({ page }) => {
    await setupCard(page, [{
      question: 'Which stack?',
      options: [{ label: 'Scala' }, { label: 'Rust' }],
    }]);

    const box = lastCard(page);
    await expect(box).toBeVisible();
    await expect(box.locator('.option-q')).toHaveText('Which stack?');

    // No multi classes leak into single questions
    await expect(box.locator('.option-opts.multi')).toHaveCount(0);

    const confirm = box.locator('.option-confirm');
    await expect(confirm).toBeDisabled();

    await box.locator('.option-btn', { hasText: 'Rust' }).first().click();
    await expect(confirm).toBeEnabled();

    // Picking the other option moves the pick (radio semantics)
    await box.locator('.option-btn', { hasText: 'Scala' }).first().click();
    await confirm.click();

    const payloads = await page.evaluate(() => window.__captured);
    expect(payloads).toEqual([{ type: 'askUserAnswer', sessionId: SID, answers: ['Scala'] }]);

    // Card is consumed: options disabled, answer echoed
    await expect(box.locator('.option-btn').first()).toBeDisabled();
    await expect(box.locator('.option-answer')).toContainText('Scala');
  });
});

// ============================================================
// Scenario 2 — multiple-select: checkbox group + array payload
// ============================================================

test.describe('AskUser card — multiple-select', () => {
  test('checkbox group: toggle several, confirm gated on >=1, JSON array payload', async ({ page }) => {
    await setupCard(page, [{
      question: 'Which areas should we cover?',
      multiple: true,
      options: [
        { label: 'Frontend', description: 'JS/CSS' },
        { label: 'Backend' },
        { label: 'Docs' },
      ],
    }]);

    const box = lastCard(page);
    const confirm = box.locator('.option-confirm');
    const opts = box.locator('.option-opts.multi .option-btn:not([data-other])');

    await expect(opts).toHaveCount(3);
    // Checkbox indicator rendered
    await expect(opts.first().locator('.option-check')).toBeVisible();
    await expect(confirm).toBeDisabled();

    // A single check opens the gate
    await opts.nth(0).click();
    await expect(opts.nth(0)).toHaveClass(/picked/);
    await expect(confirm).toBeEnabled();

    // Uncheck it → back under the >=1 gate
    await opts.nth(0).click();
    await expect(opts.nth(0)).not.toHaveClass(/picked/);
    await expect(confirm).toBeDisabled();

    // Check all three → submit; array is in DOM order
    await opts.nth(0).click();
    await opts.nth(1).click();
    await opts.nth(2).click();
    await confirm.click();

    const payloads = await page.evaluate(() => window.__captured);
    expect(payloads).toEqual([{
      type: 'askUserAnswer',
      sessionId: SID,
      answers: ['["Frontend","Backend","Docs"]'],
    }]);
    await expect(box.locator('.option-answer')).toContainText('[Frontend, Backend, Docs]');
  });

  test('stacks vertically with no horizontal overflow at ~490px chat width', async ({ page }) => {
    await setupCard(page, [{
      question: 'Which areas should we cover in the next release cycle?',
      multiple: true,
      options: [
        { label: 'Frontend rendering pipeline' },
        { label: 'Backend API and persistence layer' },
        { label: 'Documentation and onboarding flow' },
      ],
    }]);

    // ~490px CHAT COLUMN (not window): collapse the file sidebar, then size
    // the window so the chat column lands around 490px like the user's setup.
    const sidebarToggle = page.locator('#sidebar-toggle');
    if (await sidebarToggle.isVisible().catch(() => false)) {
      await sidebarToggle.click();
      await page.waitForTimeout(600);
    }
    await page.setViewportSize({ width: 560, height: 900 });
    await page.waitForTimeout(400);

    const box = lastCard(page);
    const opts = box.locator('.option-opts.multi .option-btn:not([data-other])');
    await expect(opts).toHaveCount(3);

    // Vertical stack: same left edge, strictly increasing tops, full-width rows
    const boxes = await opts.evaluateAll((els) => els.map(el => {
      const r = el.getBoundingClientRect();
      return { left: r.left, top: r.top, width: r.width, right: r.right };
    }));
    for (let i = 1; i < boxes.length; i++) {
      expect(boxes[i].top).toBeGreaterThan(boxes[i - 1].top);
    }
    expect(Math.abs(boxes[0].left - boxes[1].left)).toBeLessThan(1);
    for (const b of boxes) {
      expect(b.right - b.left).toBeGreaterThan(100); // rows stretch, not pill-chips
    }

    // No horizontal overflow anywhere in the card
    const overflow = await box.evaluate(el => el.scrollWidth - el.clientWidth);
    expect(overflow).toBeLessThanOrEqual(1);
    const chat = await page.evaluate(() => {
      const el = document.getElementById('chat');
      return { w: el.getBoundingClientRect().width, overflow: el.scrollWidth - el.clientWidth };
    });
    // Document the width this actually validated (target: the user's ~490px column)
    expect(chat.w).toBeGreaterThan(300);
    expect(chat.w).toBeLessThan(650);
    expect(chat.overflow).toBeLessThanOrEqual(1);
  });
});

// ============================================================
// Scenario 3 — mixed: one call, both question kinds
// ============================================================

test.describe('AskUser card — mixed single + multiple', () => {
  test('answer slots stay aligned: scalar first, JSON array second', async ({ page }) => {
    await setupCard(page, [
      { question: 'Which stack?', options: [{ label: 'Scala' }, { label: 'Rust' }] },
      { question: 'Which areas?', multiple: true, options: [{ label: 'Frontend' }, { label: 'Backend' }] },
    ]);

    const box = lastCard(page);
    const wrappers = box.locator('.option-q-wrapper');
    await expect(wrappers).toHaveCount(2);

    // First question stays radio
    await expect(wrappers.nth(0).locator('.option-opts.multi')).toHaveCount(0);
    // Second question is the checkbox group
    await expect(wrappers.nth(1).locator('.option-opts.multi')).toHaveCount(1);

    const confirm = box.locator('.option-confirm');
    await expect(confirm).toBeDisabled();

    // Mixed completion gating: multiple answered but single missing → disabled
    const multiOpts = wrappers.nth(1).locator('.option-btn:not([data-other])');
    await multiOpts.nth(0).click();
    await expect(confirm).toBeDisabled();

    // Complete the single question → enabled
    await wrappers.nth(0).locator('.option-btn', { hasText: 'Rust' }).first().click();
    await expect(confirm).toBeEnabled();
    await multiOpts.nth(1).click();
    await confirm.click();

    const payloads = await page.evaluate(() => window.__captured);
    expect(payloads).toEqual([{
      type: 'askUserAnswer',
      sessionId: SID,
      answers: ['Rust', '["Frontend","Backend"]'],
    }]);
  });
});

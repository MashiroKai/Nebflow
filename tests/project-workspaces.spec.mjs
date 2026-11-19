import assert from 'node:assert/strict';
import { loadPlaywright, launchBrowser, createSession } from '../scripts/lib/visual-harness.mjs';
const loaded = loadPlaywright();
const { browser } = await launchBrowser(loaded.mod.playwright || loaded.mod);
try {
  const session = await createSession(browser);
  await session.boot({ sessionId: 'parent', messages: [] });
  // Read-only legacy ledger payload (the retired mechanism's recorded history).
  let snapshot = {
    revision: 1,
    workspaces: [
      { id: 'recorded-open', branch: 'delegate/recorded', lifecycle: 'open', activity: 'idle',
        owner: 'owner-1', baseBranch: 'main', baseCommit: 'abc123' },
      { id: 'recorded-merged', branch: 'delegate/merged', lifecycle: 'closed-merged', activity: 'idle',
        owner: 'owner-2', baseBranch: 'main', baseCommit: 'def456' },
    ],
    mergeRequests: [],
    merger: { delegateId: 'legacy-merger' },
    cleanups: { 'recorded-merged': { error: 'recorded note' } },
  };
  await session.page.route('**/api/projects/example/workspaces**', route => route.fulfill({ json: snapshot }));
  await session.page.evaluate(async () => {
    const { mountProjectWorkspaces } = await import('/js/projectWorkspaces.js');
    const card = document.createElement('section');
    card.id = 'workspace-test-card';
    card.style.cssText = 'position:fixed;top:100px;left:100px;width:600px;z-index:2147483647;background:white;color:black;padding:20px';
    document.body.appendChild(card);
    window.__wsInstance = mountProjectWorkspaces(card, 'example');
    card.querySelector('details').open = true;
  });
  const card = session.page.locator('#workspace-test-card');
  await card.locator('.project-workspaces-row').first().waitFor();
  // Recorded rows render as captured: branch, lifecycle/activity words, owner, base.
  assert((await card.textContent()).includes('delegate/recorded'));
  assert((await card.textContent()).includes('delegate/merged'));
  assert((await card.textContent()).includes('abc123'));
  const mergedRow = card.locator('.project-workspaces-row', { hasText: 'delegate/merged' });
  assert((await mergedRow.textContent()).includes('recorded note'), 'recorded cleanup note stays visible');
  // Read-only face: zero controls (no baseline input, no checkboxes, no buttons).
  assert(await card.locator('input, button, form, select').count() === 0, 'no write-side controls remain');
  // Revision-gated refresh: a stale (lower) revision never rewrites the rows.
  snapshot = { ...snapshot, workspaces: [{ id: 'recorded-open', branch: 'delegate/stale', lifecycle: 'open',
    activity: 'idle', owner: 'x', baseBranch: 'main', baseCommit: 'fff' }], revision: 0 };
  await session.page.evaluate(() => window.__wsInstance.refresh());
  await session.page.waitForTimeout(150);
  assert(!(await card.textContent()).includes('delegate/stale'), 'stale revision dropped');
  console.log('PASS project workspaces read-only legacy view: recorded rows, zero controls, revision gating');
  await session.close();
} finally { await browser.close(); }

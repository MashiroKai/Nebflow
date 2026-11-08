import assert from 'node:assert/strict';
import { loadPlaywright, launchBrowser, createSession } from '../scripts/lib/visual-harness.mjs';
const loaded = loadPlaywright();
const { browser } = await launchBrowser(loaded.mod.playwright || loaded.mod);
try {
  const session = await createSession(browser);
  await session.boot({ sessionId: 'parent', messages: [] });
  let snapshot = { revision: 1, baseline: 'main', workspaces: [{ id: 'task', branch: 'delegate/task',
    lifecycle: 'open', activity: 'idle', owner: 'owner-one', baseBranch: 'main', baseCommit: 'base' }],
    mergeRequests: [], merger: { delegateId: 'merger' } };
  let cleanupRequest = null;
  let heldRoute = null;
  let holdNext = false;
  await session.page.route('**/api/projects/live/workspaces**', async route => {
    if (route.request().url().endsWith('/cleanup')) {
      if (route.request().method() === 'POST') {
        cleanupRequest = route.request().postDataJSON();
        snapshot = { ...snapshot, revision: 5, workspaces: snapshot.workspaces.map(w => ({ ...w, lifecycle: 'closed-discarded' })) };
        await route.fulfill({ json: { workspace: snapshot.workspaces[0], branchRetained: true } });
      } else await route.fulfill({ json: { path: '/isolated/task', branch: 'delegate/task', unmerged: true,
        changes: [' M result.txt'], previewToken: 'exact-preview' } });
    } else if (holdNext) { holdNext = false; heldRoute = route; }
    else await route.fulfill({ json: snapshot });
  });
  await session.page.evaluate(async () => {
    const { mountProjectWorkspaces } = await import('/js/projectWorkspaces.js');
    const card = document.createElement('section');
    card.id = 'workspace-live-card';
    card.style.cssText = 'position:fixed;top:80px;left:80px;width:600px;z-index:500;background:var(--ds-bg);padding:20px';
    document.body.appendChild(card);
    window.workspaceLive = mountProjectWorkspaces(card, 'live');
    card.querySelector('details').open = true;
  });
  const card = session.page.locator('#workspace-live-card');
  const input = card.locator('input[required]');
  await input.waitFor();
  await input.fill('draft-baseline');
  await card.locator('input[type=checkbox]').check();
  await input.focus();
  await input.evaluate(element => { window.originalWorkspaceInput = element; });
  snapshot = { ...snapshot, revision: 2, workspaces: snapshot.workspaces.map(w => ({ ...w, owner: 'owner-two' })) };
  await session.frame({ type: 'workspaceLedgerUpdated', revision: 2 });
  await session.page.waitForFunction(() => document.querySelector('#workspace-live-card').textContent.includes('owner-two'));
  assert.equal(await input.inputValue(), 'draft-baseline');
  assert(await card.locator('input[type=checkbox]').isChecked());
  assert(await input.evaluate(element => element === window.originalWorkspaceInput && document.activeElement === element));
  holdNext = true;
  await session.page.evaluate(() => { window.workspaceLive.refresh(); });
  await session.page.waitForTimeout(50);
  assert(heldRoute);
  snapshot = { ...snapshot, revision: 4, workspaces: snapshot.workspaces.map(w => ({ ...w, owner: 'owner-four' })) };
  await session.frame({ type: 'workspaceLedgerUpdated', revision: 4 });
  await session.page.waitForFunction(() => document.querySelector('#workspace-live-card').textContent.includes('owner-four'));
  await heldRoute.fulfill({ json: { ...snapshot, revision: 3, workspaces: snapshot.workspaces.map(w => ({ ...w, owner: 'stale-owner' })) } });
  await session.page.waitForTimeout(100);
  assert(!(await card.textContent()).includes('stale-owner'));
  await card.getByRole('button', { name: /清理工作区|Clean up workspace/ }).click();
  const confirm = session.page.locator('#delete-confirm');
  await session.page.waitForFunction(() => document.querySelector('#delete-msg')?.textContent.includes('/isolated/task'));
  const text = await session.page.locator('#delete-msg').textContent();
  assert(text.includes('result.txt') && text.includes('delegate/task'));
  assert.equal(cleanupRequest, null, 'preview never deletes before confirmation');
  await confirm.click();
  await session.page.waitForFunction(() => document.querySelector('#workspace-live-card').textContent.includes('目录已清理'));
  assert.deepEqual(cleanupRequest, { previewToken: 'exact-preview', confirmUnmerged: true, discardUncommitted: true });
  assert(await card.getByRole('button', { name: /清理工作区|Clean up workspace/ }).isDisabled());
  console.log('PASS live workspaces: event refresh, focus/draft/selection preservation, stale response rejection, explicit cleanup confirmation');
  await session.close();
} finally { await browser.close(); }

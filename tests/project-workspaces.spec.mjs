import assert from 'node:assert/strict';
import { loadPlaywright, launchBrowser, createSession } from '../scripts/lib/visual-harness.mjs';
const loaded = loadPlaywright();
const { browser } = await launchBrowser(loaded.mod.playwright || loaded.mod);
try {
  const session = await createSession(browser);
  await session.boot({ sessionId: 'parent', messages: [] });
  let saved = null;
  let submitted = null;
  let stopped = false;
  let expiredNote = false;
  const idlePayload = () => ({ baseline: saved?.branch || 'main', facts: {
    'idle-task': { aheadCount: 2, dirtyTracked: 1, dirtyUntracked: 0, headCommit: 'f'.repeat(40),
      lastVerification: null, reconcile: [] },
  }, workspaces: [{
    id: 'reserved-task', branch: 'delegate/task', lifecycle: 'reserved', activity: 'idle', owner: 'owner-1',
    baseBranch: 'main', baseCommit: 'abc123',
  }, { id: 'idle-task', branch: 'delegate/idle', lifecycle: 'open', activity: 'idle', owner: 'owner-2', baseBranch: 'main', baseCommit: 'abc123' }], mergeRequests: [{ id: 'existing-request', status: 'processing', items: [{ workspaceId: 'reserved-task', phase: 'merged-cleanup-pending', mergeCommit: 'actual-merge' }] }], merger: { delegateId: 'merger-1' } });
  await session.page.route('**/api/projects/example/workspaces**', async route => {
    if (route.request().url().endsWith('/cancel')) {
      stopped = true;
      await route.fulfill({ json: { request: { id: 'existing-request', cancelRequested: true } } });
    } else if (route.request().method() === 'POST') {
      submitted = route.request().postDataJSON();
      await route.fulfill({ json: { mergerLive: true, request: { id: submitted.requestId } } });
    } else if (route.request().method() === 'PUT') {
      saved = route.request().postDataJSON();
      await route.fulfill({ json: { baseline: saved.branch } });
    } else if (expiredNote) await route.fulfill({ json: {
      ...(idlePayload()), workspaces: idlePayload().workspaces.map(w =>
        w.id === 'idle-task' ? { ...w, activity: 'running' } : w),
    } });
    else await route.fulfill({ json: idlePayload() });
  });
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
  await card.locator('input[required]').waitFor();
  assert((await card.textContent()).includes('delegate/task'));
  assert((await card.textContent()).includes('等待合并') || (await card.textContent()).includes('Reserved for merge'));
  // Facts chips render on the open workspace row (ahead / dirty / verification).
  const idleRow = card.locator('.project-workspaces-row', { hasText: 'delegate/idle' });
  assert((await idleRow.textContent()).includes('+2 ahead') || (await idleRow.textContent()).includes('+2 领先'));
  assert((await idleRow.textContent()).includes('1 changed') || (await idleRow.textContent()).includes('1 处改动'));
  assert((await idleRow.textContent()).includes('no verification record') || (await idleRow.textContent()).includes('无验证记录'));
  // 继续工作 opens the owner session through the same popup channel as the merger entry.
  assert(await idleRow.getByRole('button', { name: /继续工作|Continue work/ }).isVisible());
  await card.locator('input[required]').fill('release');
  await card.locator('button[type=submit]').click();
  await session.page.waitForFunction(() => document.querySelector('#workspace-test-card [role=status]')?.textContent);
  assert.deepEqual(saved, { branch: 'release' });
  assert.equal(await card.locator('input[required]').inputValue(), 'release');
  assert((await card.textContent()).includes('abc123'));
  // Merge goes through the confirm dialog: pinned source SHA + target branch + facts.
  await card.locator('input[type=checkbox]:enabled').check();
  await card.getByRole('button', { name: /合并所选工作区|Merge selected workspaces/ }).click();
  const dialog = session.page.locator('#delete-box');
  await dialog.waitFor();
  const dialogText = await dialog.textContent();
  assert(dialogText.includes('delegate/idle'), 'confirm dialog lists the selected branch');
  assert(dialogText.includes('main'), 'confirm dialog lists the target baseline');
  assert(dialogText.includes('ffff'), 'confirm dialog shows the pinned source SHA');
  await dialog.getByRole('button', { name: /提交合并请求|Submit merge request/ }).click();
  await session.page.waitForFunction(() => document.querySelector('#workspace-test-card')?.textContent.includes('已提交合并请求'));
  assert.deepEqual(submitted.workspaceIds, ['idle-task']);
  assert(submitted.requestId);
  await card.locator('details').nth(1).locator('summary').click();
  await card.getByRole('button', { name: /请求终止|Request stop/ }).click();
  await session.page.waitForFunction(() => document.querySelector('#workspace-test-card')?.textContent.includes('已请求终止'));
  assert(stopped);
  assert((await card.textContent()).includes('actual-merge'));
  // Expired selection: the selected workspace leaves open+idle, the panel clears it
  // from the selection with a visible note (refresh = the workspaceLedgerUpdated path).
  expiredNote = true;
  await card.locator('input[type=checkbox]:enabled').check();
  await session.page.evaluate(() => window.__wsInstance.refresh());
  await session.page.waitForFunction(() => document.querySelector('#workspace-test-card')?.textContent.includes('已不再处于空闲开放状态'));
  assert(await card.locator('input[type=checkbox]').nth(1).evaluate(el => !el.checked), 'expired selection is unchecked');
  // No merger: the one-click compose fills the exact creation instruction into the
  // main chat input (nothing is sent automatically).
  await session.page.route('**/api/projects/empty-merger/workspaces**', route =>
    route.fulfill({ json: { revision: 1, baseline: 'main', cleanups: {}, workspaces: [], mergeRequests: [], merger: null } }));
  await session.page.evaluate(async () => {
    const { mountProjectWorkspaces } = await import('/js/projectWorkspaces.js');
    const card = document.createElement('section');
    card.id = 'workspace-no-merger-card';
    card.style.cssText = 'position:fixed;top:100px;left:800px;width:600px;z-index:2147483647;background:white;color:black;padding:20px';
    document.body.appendChild(card);
    const instance = mountProjectWorkspaces(card, 'empty-merger');
    card.querySelector('details').open = true;
    await instance.refresh();
  });
  const noMergerCard = session.page.locator('#workspace-no-merger-card');
  const create = noMergerCard.getByRole('button', { name: /请主智能体创建 merger|Ask the main agent to create the merger/ });
  await create.waitFor();
  await create.click();
  const filled = await session.page.evaluate(() => document.getElementById('input')?.value || '');
  assert(filled.includes('Delegate(mode="merger"'), 'compose carries the exact creation instruction');
  assert(filled.includes('empty-merger'), 'compose names the project');
  console.log('PASS project workspaces: facts chips, confirm dialog with source SHA, expired selection note, create-merger compose, baseline save, merge record display');
  await session.close();
} finally { await browser.close(); }

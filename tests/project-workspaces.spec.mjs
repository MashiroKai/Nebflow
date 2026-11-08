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
    } else await route.fulfill({ json: { baseline: saved?.branch || 'main', workspaces: [{
      id: 'reserved-task', branch: 'delegate/task', lifecycle: 'reserved', activity: 'idle', owner: 'owner-1',
      baseBranch: 'main', baseCommit: 'abc123',
    }, { id: 'idle-task', branch: 'delegate/idle', lifecycle: 'open', activity: 'idle', owner: 'owner-2', baseBranch: 'main', baseCommit: 'abc123' }], mergeRequests: [{ id: 'existing-request', status: 'processing', items: [{ workspaceId: 'reserved-task', phase: 'merged-cleanup-pending', mergeCommit: 'actual-merge' }] }], merger: { delegateId: 'merger-1' } } });
  });
  await session.page.evaluate(async () => {
    const { mountProjectWorkspaces } = await import('/js/projectWorkspaces.js');
    const card = document.createElement('section');
    card.id = 'workspace-test-card';
    card.style.cssText = 'position:fixed;top:100px;left:100px;width:600px;z-index:2147483647;background:white;color:black;padding:20px';
    document.body.appendChild(card);
    mountProjectWorkspaces(card, 'example');
    card.querySelector('details').open = true;
  });
  const card = session.page.locator('#workspace-test-card');
  await card.locator('input[required]').waitFor();
  assert((await card.textContent()).includes('delegate/task'));
  assert((await card.textContent()).includes('等待合并') || (await card.textContent()).includes('Reserved for merge'));
  await card.locator('input[required]').fill('release');
  await card.locator('button[type=submit]').click();
  await session.page.waitForFunction(() => document.querySelector('#workspace-test-card [role=status]')?.textContent);
  assert.deepEqual(saved, { branch: 'release' });
  assert.equal(await card.locator('input[required]').inputValue(), 'release');
  assert((await card.textContent()).includes('abc123'));
  await card.locator('input[type=checkbox]:enabled').check();
  await card.getByRole('button', { name: /合并所选工作区|Merge selected workspaces/ }).click();
  await session.page.waitForFunction(() => document.querySelector('#workspace-test-card')?.textContent.includes('已提交合并请求'));
  assert.deepEqual(submitted.workspaceIds, ['idle-task']);
  assert(submitted.requestId);
  await card.locator('details').nth(1).locator('summary').click();
  await card.getByRole('button', { name: /请求终止|Request stop/ }).click();
  await session.page.waitForFunction(() => document.querySelector('#workspace-test-card')?.textContent.includes('已请求终止'));
  assert(stopped);
  assert((await card.textContent()).includes('actual-merge'));
  console.log('PASS project workspaces: persisted-state display, baseline save, original workspace baseline retained');
  await session.close();
} finally { await browser.close(); }

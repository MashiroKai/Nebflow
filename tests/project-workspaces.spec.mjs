import assert from 'node:assert/strict';
import { loadPlaywright, launchBrowser, createSession } from '../scripts/lib/visual-harness.mjs';
const loaded = loadPlaywright();
const { browser } = await launchBrowser(loaded.mod.playwright || loaded.mod);
try {
  const session = await createSession(browser);
  await session.boot({ sessionId: 'parent', messages: [] });
  let saved = null;
  await session.page.route('**/api/projects/example/workspaces**', async route => {
    if (route.request().method() === 'PUT') {
      saved = route.request().postDataJSON();
      await route.fulfill({ json: { baseline: saved.branch } });
    } else await route.fulfill({ json: { baseline: 'main', workspaces: [{
      branch: 'delegate/task', lifecycle: 'reserved', activity: 'idle', owner: 'owner-1',
      baseBranch: 'main', baseCommit: 'abc123',
    }], merger: { delegateId: 'merger-1' } } });
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
  await card.locator('input').waitFor();
  assert((await card.textContent()).includes('delegate/task'));
  assert((await card.textContent()).includes('等待合并') || (await card.textContent()).includes('Reserved for merge'));
  await card.locator('input').fill('release');
  await card.locator('button[type=submit]').click();
  await session.page.waitForFunction(() => document.querySelector('#workspace-test-card [role=status]')?.textContent);
  assert.deepEqual(saved, { branch: 'release' });
  assert.equal(await card.locator('input').inputValue(), 'release');
  assert((await card.textContent()).includes('abc123'));
  console.log('PASS project workspaces: persisted-state display, baseline save, original workspace baseline retained');
  await session.close();
} finally { await browser.close(); }

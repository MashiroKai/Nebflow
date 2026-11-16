#!/usr/bin/env node
import assert from 'node:assert/strict';
import { join, isAbsolute } from 'node:path';
import { ROOT, loadPlaywright, launchBrowser, createSession, setWebRoot } from '../scripts/lib/visual-harness.mjs';

const root = process.argv[2] || 'src/main/resources/web';
setWebRoot(isAbsolute(root) ? root : join(ROOT, root));
const loaded = loadPlaywright();
const { browser } = await launchBrowser(loaded.mod.playwright || loaded.mod);
try {
  const session = await createSession(browser, { width: 1000, height: 800 });
  await session.boot({ sessionId: 'fixture-root', messages: [] });
  let saved = null;
  await session.page.route('**/api/executors/*/model', async route => {
    if (route.request().method() === 'PUT') {
      saved = route.request().postDataJSON().model;
      await route.fulfill({ json: { updated: true, model: saved } });
    } else {
      const unsupported = route.request().url().includes('/zcode/');
      await route.fulfill({ json: { model: 'sonnet', supported: !unsupported, suggestions: ['sonnet', 'opus'] } });
    }
  });
  const open = async id => session.page.evaluate(async id => {
    const popup = await import('/js/bgAgentPopup.js');
    popup.openStepPopup(id, 'External', 'fixture');
  }, id);
  await open('external-claude-code-12345678');
  const input = session.page.locator('.bgagent-overlay input[list="bgagent-external-model-suggestions"]');
  await input.waitFor({ timeout: 5000 });
  assert.equal(await input.inputValue(), 'sonnet');
  assert.equal(await session.page.locator('#bgagent-model-switch').isHidden(), true);
  await input.fill('custom-model');
  await input.dispatchEvent('change');
  await session.page.waitForFunction(() => document.querySelector('.bgagent-delegate-strip [role=status]')?.textContent);
  assert.equal(saved, 'custom-model');
  await open('external-zcode-12345678');
  await session.page.waitForFunction(() => document.querySelector('.bgagent-delegate-strip [role=status]')?.textContent);
  assert.equal(await session.page.locator('.bgagent-overlay input[list="bgagent-external-model-suggestions"]').count(), 0);
  console.log('PASS external executor model popup save and unsupported state');
} finally { await browser.close(); }

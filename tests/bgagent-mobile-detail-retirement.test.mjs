// FRB-SCN-001/002 consumer probe; NOT RUN. No live service or real credentials.
// Requires the repository visual harness and an isolated, supervised browser.
import assert from 'node:assert/strict';
import { loadPlaywright, launchBrowser, createSession, settle } from '../scripts/lib/visual-harness.mjs';

const sid = 'delegate-residual-fixture';
const detail = { record: { id: sid, executor: 'fixture-executor', parentSessionId: 'fixture-root', createdAt: 1793940000000, worktree: '/fixture/retired-worktree', plugins: ['fixture-plugin'] }, live: { status: 'running' }, verdict: null, task: null, childrenCount: 0 };
const mod = loadPlaywright().mod;
const { browser } = await launchBrowser(mod.playwright || mod);
try {
  for (const [width, height] of [[375, 812], [1440, 900]]) {
    const session = await createSession(browser, { width, height, colorScheme: 'light', apiStubs: { '/api/delegates': { body: detail } } });
    try {
      await session.boot({ config: { llm: { providers: {} }, features: {} }, sessionId: 'fixture-root' });
      await session.page.evaluate(async (id) => {
        window.__fixtureHistoryTimer = setInterval(() => {
          const pending = (window.__wsSent || []).filter((f) => f.type === 'getHistory' && f.sessionId === id);
          if (!pending.length || window.__fixtureHistoryReplied) return;
          window.__fixtureHistoryReplied = true;
          window.__wsMock.onmessage({ data: JSON.stringify({ type: 'historyPage', sessionId: id, messages: [{ type: 'user', injected: true, source: 'delegate', eventType: 'dispatch', sender: 'fixture-root', timestamp: 1793940000000, text: 'Fixture task detail complete and unique', attachments: [] }], total: 1, offset: 0, hasMore: false }) });
        }, 40);
        const m = await import('/js/bgAgentPopup.js');
        m.openStepPopup(id, 'Fixture agent', 'Fixture task detail complete and unique');
      }, sid);
      await settle(session.page, 800);
      if (width === 375) {
        const more = session.page.locator('.bgagent-more');
        await more.waitFor({ state: 'visible', timeout: 10000 });
        await more.click();
        const sheet = session.page.locator('.bgagent-more-sheet');
        await sheet.waitFor({ state: 'visible' });
        await session.page.waitForFunction(() => document.querySelectorAll('.bgagent-sheet-meta .bgagent-meta-row').length >= 2, null, { timeout: 10000 });
        const rows = await sheet.locator('.bgagent-meta-row').allTextContents();
        assert(rows.some((r) => r.includes('fixture-executor')), 'FRB-SCN-001 source retained');
        const expectedDate = await session.page.evaluate(() => new Date(1793940000000).toLocaleString());
        assert(rows.some((r) => r.includes(expectedDate)), 'FRB-SCN-001 exact creation time retained');
        assert(!rows.some((r) => r.includes('retired-worktree') || r.includes('fixture-plugin')), 'FRB-SCN-001 worktree and plugin omitted');
        const fullTask = sheet.locator('.bgagent-strip-tasktext');
        await fullTask.waitFor({ state: 'visible', timeout: 10000 });
        assert((await fullTask.textContent()).includes('Fixture task detail complete and unique'), 'FRB-SCN-001 complete task text retained');
        await sheet.locator('.bgagent-sheet-scrim').click({ position: { x: 5, y: 5 } });
        assert.equal(await sheet.isVisible(), false, 'FRB-SCN-001 detail closes');
      } else {
        assert.equal(await session.page.locator('.bgagent-more').count(), 0, 'FRB-SCN-002 desktop has no mobile detail shell');
        const close = session.page.locator('.flow-agent-close');
        assert.equal(await close.count(), 1, 'FRB-SCN-002 desktop close retained');
        assert.equal(await close.isVisible(), true, 'FRB-SCN-002 desktop close visible');
        assert.equal(await session.page.locator('.flow-agent-footer').isVisible(), true, 'FRB-SCN-002 desktop footer retained');
        await close.click();
        assert.equal(await session.page.locator('.bgagent-overlay').count(), 0, 'FRB-SCN-002 desktop close works');
      }
    } finally { await session.close(); }
  }
  console.log('FRB-SCN-001/002 partial consumer probe passed');
} finally { await browser.close(); }

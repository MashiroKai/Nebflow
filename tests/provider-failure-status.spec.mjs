import assert from 'node:assert/strict';
import { loadPlaywright, launchBrowser, createSession } from '../scripts/lib/visual-harness.mjs';

const loaded = loadPlaywright();
const { browser } = await launchBrowser(loaded.mod.playwright || loaded.mod);
try {
  const session = await createSession(browser);
  await session.boot({ sessionId: 'parent', messages: [] });
  const result = await session.page.evaluate(async () => {
    const { renderProviderFailureStatus: render } = await import('/js/providerFailureStatus.js');
    const root = document.createElement('section');
    document.body.appendChild(root);
    const view = { dom: { chat: root }, sessionId: 'parent' };
    const opened = [];
    const digest = {
      items: [{ sessionId: 'a', parentSessionId: 'parent', title: 'Task A' },
        { sessionId: 'b', parentSessionId: 'other', title: 'Private task' }],
      providerGroups: [{ provider: 'p', reason: '<script>unsafe</script> permission denied', sessionIds: ['a', 'b'] }],
    };
    render(view, digest, id => opened.push(id));
    render(view, digest, id => opened.push(id));
    const rowCount = root.querySelectorAll('aside').length;
    const text = root.textContent;
    const unsafeElements = root.querySelectorAll('script').length;
    root.querySelector('button').click();
    root.replaceChildren(); // history reload must reattach the same status surface
    await new Promise(resolve => setTimeout(resolve, 0));
    const restored = root.querySelectorAll('aside').length;
    render(view, { items: [], providerGroups: [] }, () => {});
    await new Promise(resolve => setTimeout(resolve, 0));
    return { rowCount, text, unsafeElements, opened, restored, cleared: root.childNodes.length === 0 };
  });
  assert.equal(result.rowCount, 1);
  assert(!result.text.includes('Private task'));
  assert(result.text.includes('Task A'));
  assert.equal(result.unsafeElements, 0);
  assert.deepEqual(result.opened, ['a']);
  assert.equal(result.restored, 1);
  assert(result.cleared);
  console.log('PASS provider status: update, session isolation, safe text, task navigation, history reload, recovery removal');
  await session.close();
} finally {
  await browser.close();
}

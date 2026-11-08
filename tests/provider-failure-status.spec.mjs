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
  const digest = {
    type: 'delegateFreezeDigest', nodeSessionId: 'delegate-provider-cached',
    items: [{ sessionId: 'delegate-provider-cached', parentSessionId: 'cached-parent', title: 'Cached failure' }],
    providerGroups: [{ provider: 'provider', reason: 'Authentication failed', sessionIds: ['delegate-provider-cached'] }],
  };
  await session.frame(digest);
  const cached = await session.page.evaluate(async () => {
    const { chatViews } = await import('/js/chatView.js');
    const { openStepPopup } = await import('/js/bgAgentPopup.js');
    const main = chatViews.primary;
    const before = main.dom.chat.querySelectorAll('aside[role=status]').length;
    main.setSession('cached-parent');
    const afterSwitch = main.dom.chat.querySelector('aside[role=status]')?.textContent;
    openStepPopup('delegate-provider-cached', 'Nebula', 'cached task');
    const popup = chatViews['bgagent-delegate-provider-cached'];
    return { before, afterSwitch, popup: popup.dom.chat.querySelector('aside[role=status]')?.textContent,
      activeSession: main.sessionId };
  });
  assert.equal(cached.before, 0, 'unrelated active conversation stays isolated');
  assert(cached.afterSwitch?.includes('Cached failure'), 'switching session renders cached digest immediately');
  assert(cached.popup?.includes('Cached failure'), 'new popup renders cached digest immediately');
  assert.equal(cached.activeSession, 'cached-parent');
  await session.frame({ ...digest, items: digest.items.map(item => ({ ...item, title: 'Renamed failure' })) });
  await session.page.waitForFunction(async () => {
    const { chatViews } = await import('/js/chatView.js');
    return chatViews.primary.dom.chat.querySelector('aside[role=status]')?.textContent.includes('Renamed failure');
  });
  await session.frame({ type: 'delegateFreezeDigest', items: [], providerGroups: [] });
  await session.page.waitForFunction(async () => {
    const { chatViews } = await import('/js/chatView.js');
    return !Object.values(chatViews).some(view => view.dom.chat?.querySelector('aside[role=status]'));
  });
  console.log('PASS cached provider digest: session switch, new popup, global frame routing, changed titles, empty reconnect snapshot');
  await session.close();
} finally {
  await browser.close();
}

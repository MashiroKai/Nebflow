import { readFileSync } from 'node:fs';
import { loadPlaywright, launchBrowser, createSession, settle } from '../../scripts/lib/visual-harness.mjs';

export async function measure(screenshot) {
  const panel = JSON.parse(readFileSync(new URL('./panels/mobile-messages.json', import.meta.url)));
  const { mod } = loadPlaywright();
  const { browser, version } = await launchBrowser(mod);
  let session;
  try {
    session = await createSession(browser, { width: 390, height: 844, colorScheme: 'dark', hasTouch: true, clock: 1793940000000, apiStubs: {
      '/api/neblink/status': { body: { loggedIn: true, device: { name: 'fixture' }, peers: [] } },
      '/api/plugins': { body: { plugins: [], rejected: [] } }, '/api/nf-authcheck': { status: 204 },
    } });
    await session.page.addInitScript(() => {
      localStorage.setItem('fm_api_mock', '1');
      const friends = Array.from({ length: 30 }, (_, i) => ({ userId: `u${i}`, neblinkId: `row${i}`, name: `好友${i}`, avatarUrl: '', since: Date.now() - 86400000 }));
      const conversations = friends.map((friend, i) => ({ conversationId: `c${i}`, friend, lastMessage: { id: i, senderId: friend.userId, kind: 'text', body: `消息 ${i}`, createdAt: Date.now() - 60000 }, unreadCount: 0 }));
      localStorage.setItem('fm_api_mock_seed', JSON.stringify({ self: { userId: 'me', neblinkId: 'me@example.com', name: '我自己' }, users: [], friends, incoming: [], outgoing: [], conversations, messages: {}, groups: [], groupInvites: [], groupMembers: {} }));
    });
    await session.boot({ config: { llm: { providers: {} }, features: { friends: true } } });
    const page = session.page;
    await page.locator('#mobile-dock .dock-key[data-dock="messages"]').click();
    await page.waitForSelector('.mobile-page[data-page="messages"].live .fm-conv-row');
    await page.locator('.mobile-page[data-page="messages"] #fm-conversations').evaluate(el => { el.scrollTop = 200; el.dispatchEvent(new Event('scroll')); });
    await page.waitForSelector('.mobile-page[data-page="messages"].live .mh-search-dock.mh-search-collapsed');
    await settle(page, 400);
    const elements = {};
    for (const item of panel.elements) {
      const locator = page.locator(item.selector).first();
      await locator.waitFor({ state: 'visible', timeout: 5000 });
      elements[item.id] = await locator.evaluate((el, spec) => {
        const cs = getComputedStyle(el), styles = {};
        for (const prop of spec.properties) styles[prop] = cs[prop];
        const r = el.getBoundingClientRect();
        return { selector: spec.selector, styles, ...(spec.box ? { box: { x: r.x, y: r.y, width: r.width, height: r.height } } : {}) };
      }, item);
    }
    await page.screenshot({ path: screenshot, animations: 'disabled', caret: 'hide', scale: 'css' });
    if (session.errors.length) throw new Error(`页面错误：${session.errors.join('; ')}`);
    return { panel: panel.panel, viewport: { width: 390, height: 844, theme: 'dark', dpr: 1 }, browser: version, elements };
  } finally { if (session) await session.close(); await browser.close(); }
}

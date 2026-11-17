// Mobile appearance acceptance: real routes/components, mocked APIs only.
// Scenario: shared contact chrome and green send face
// Scenario: workbench hero settings row account detail and existing logout transport
import assert from 'node:assert/strict';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { createSession, loadPlaywright, settle } from '../scripts/lib/visual-harness.mjs';
const out = process.env.CANVAS_EVIDENCE || '/tmp/stand-mobile-canvas-toolbar/pages-after';
mkdirSync(out, { recursive: true });
const engine = process.env.CANVAS_ENGINE || 'chromium';
const { mod } = loadPlaywright();
const browser = await mod[engine].launch({ headless: true, ...(process.env.CANVAS_BROWSER_PATH ? { executablePath: process.env.CANVAS_BROWSER_PATH } : {}) });
const failures = [];
const checks = [];
const check = (ok, name) => { checks.push({ name, ok }); if (!ok) failures.push(name); };
const captures = [];
try {
  const context = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, deviceScaleFactor: 2, colorScheme: 'dark' });
  const session = await createSession({ newPage: () => context.newPage() }, { width: 390, height: 844, colorScheme: 'dark', baseURL: 'https://stand.test/', apiStubs: {
    '/api/neblink/status': { body: { loggedIn: true, device: { id: 'fixture-device', name: 'Fixture', displayName: 'Mashiro', email: 'mashiro@example.test', userId: 'self' }, peers: [] } },
    '/api/friends': { body: { friends: [], incoming: [], outgoing: [] } },
    '/api/conversations': { body: [] },
  } });
  const { page } = session;
  await session.boot({ config: { features: { friends: true, groupChat: true }, llm: { providers: {} } } });
  await page.waitForSelector('body.mobile-layer');
  await page.evaluate(async () => { await (await import('/js/neblink.js')).fetchNeblinkStatus(); (await import('/js/mobile/navController.js')).dockTo('workbench'); });
  await settle(page);
  const shot = async name => { const path = join(out, `${engine}-${name}.png`); await page.screenshot({ path, animations: 'disabled' }); captures.push(path); };
  await shot('workbench');
  const hero = await page.locator('[data-me-entry]').evaluate(el => {
    const a = el.querySelector('.mf-me-avatar-frame').getBoundingClientRect(); const n = el.querySelector('.mf-me-name').getBoundingClientRect(); const s = el.querySelector('.mf-me-sub').getBoundingClientRect();
    return { ac: a.x + a.width / 2, nc: n.x + n.width / 2, sc: s.x + s.width / 2, ab: a.bottom, nt: n.top, nb: n.bottom, st: s.top };
  });
  check(Math.abs(hero.ac - 195) < 1 && Math.abs(hero.nc - 195) < 1 && Math.abs(hero.sc - 195) < 1 && hero.nt >= hero.ab && hero.st >= hero.nb, 'centered hero vertical hierarchy');
  check(await page.locator('[data-hero-settings]').count() === 0, 'no hero settings capsule');
  check(await page.locator('[data-page="workbench"] [data-hub-page="settings"]').count() === 1, 'one settings list row');
  // Use the visible production entry, both before and after relocation.
  const settings = page.locator('[data-page="workbench"] [data-hub-page="settings"]');
  if (await settings.count()) await settings.click(); else await page.locator('[data-hero-settings]').click();
  await page.waitForSelector('[data-page="settings"].live #settings-content');
  await settle(page);
  await shot('settings');
  check(await page.locator('[data-page="settings"] #neblink-logout').count() === 0, 'account/logout removed from settings');
  const skirt = await page.locator('#settings-modal').evaluate(el => getComputedStyle(el, '::after').content);
  check(skirt === 'none', 'settings has no bottom skirt');
  await page.locator('#settings-content').evaluate(el => el.scrollTo({ top: el.scrollHeight, behavior: 'instant' }));
  await settle(page);
  await shot('settings-bottom');
  check(await page.locator('#settings-content').evaluate(el => { const last = el.lastElementChild.getBoundingClientRect(); const r = el.getBoundingClientRect(); const t = document.elementFromPoint(last.x + last.width / 2, Math.min(last.bottom - 4, r.bottom - 4)); return last.bottom <= r.bottom + 1 && !!t && el.contains(t); }), 'settings last card reachable and hit-test unobscured');
  await page.evaluate(async () => (await import('/js/mobile/navController.js')).popPage());
  await page.locator('[data-me-entry]').click();
  await page.waitForSelector('[data-page="me"].live');
  await settle(page);
  await shot('account');
  check(await page.locator('[data-page="me"] #neblink-logout').count() === 1, 'avatar opens account with existing logout');
  // Observe the existing logout transport without touching any real account.
  let logoutRequests = 0;
  await page.route('**/api/neblink/auth/end-session', route => { logoutRequests++; return route.fulfill({ status: 500, contentType: 'application/json', body: '{"error":"fixture-no-side-effects"}' }); });
  const logout = page.locator('[data-page="me"] #neblink-logout');
  if (await logout.count()) {
    await page.evaluate(() => { window.open = () => null; });
    const request = page.waitForRequest(req => req.url().includes('/api/neblink/auth/end-session'), { timeout: 5000 });
    await logout.click();
    try { await request; check(logoutRequests === 1, 'logout uses existing end-session exactly once'); } catch { check(false, 'logout uses existing end-session exactly once'); }
  }
  const friend = { userId: 'fixture-friend', displayName: '安之则', name: '安之则', trustLevel: 'trusted', online: true };
  await page.route('**/api/conversations', route => route.fulfill({ json: [{ conversationId: 'fixture-conv', friend, unreadCount: 0 }] }));
  await page.route('**/api/conversations/fixture-conv/messages?*', route => route.fulfill({ json: Array.from({ length: 30 }, (_, i) => ({ id: i + 1, senderId: friend.userId, kind: 'text', body: `Message ${i + 1}: content scrolls beneath floating identity controls.`, createdAt: 1757000000000 + i })) }));
  await page.route('**/api/conversations/fixture-conv/receipts*', route => route.fulfill({ json: {} }));
  await page.route('**/api/conversations/fixture-conv/read', route => route.fulfill({ json: {} }));
  await page.evaluate(async friend => {
    (await import('/js/mobile/navController.js')).dockTo('messages');
    window.dispatchEvent(new Event('fm-friends-changed'));
  }, friend);
  await page.waitForFunction(async () => (await import('/js/messages.js')).conversationSnapshot().some(c => c.conversationId === 'fixture-conv'));
  await page.evaluate(async () => (await import('/js/messages.js')).openConversation('fixture-conv', null));
  await page.waitForSelector('[data-page="conversation"].live .fm-input');
  await page.locator('.fm-input').blur();
  await settle(page);
  await shot('contact-empty');
  const empty = await page.locator('.fm-send-btn').evaluate(el => { const s = getComputedStyle(el); return { bg: s.backgroundColor, ink: s.color, opacity: getComputedStyle(el.querySelector('svg')).opacity, disabled: el.disabled }; });
  await page.locator('.fm-input').fill('Mobile message');
  await page.locator('.fm-input').blur();
  await settle(page);
  await shot('contact-text');
  const filled = await page.locator('.fm-send-btn').evaluate(el => { const s = getComputedStyle(el); return { bg: s.backgroundColor, ink: s.color, opacity: getComputedStyle(el.querySelector('svg')).opacity, disabled: el.disabled }; });
  check(empty.bg === filled.bg && empty.ink === filled.ink && empty.opacity === filled.opacity && filled.bg === 'rgb(7, 193, 96)', 'empty/filled same green send appearance');
  check(empty.disabled && !filled.disabled, 'empty send guard unchanged');
  const head = await page.locator('[data-page="conversation"]').evaluate(sec => {
    const body = sec.querySelector('.mobile-page-body'); const flow = sec.querySelector('.fm-flow'); const header = sec.querySelector('.mobile-page-header'); const c = getComputedStyle(header);
    return { pageY: sec.getBoundingClientRect().y, flowY: flow.getBoundingClientRect().y, bodyPad: getComputedStyle(body).paddingTop, position: c.position, bg: c.backgroundColor, border: c.borderBottomWidth, className: header.className };
  });
  check(head.className.includes('ds-chrome-band') && head.bg === 'rgba(0, 0, 0, 0)' && head.border === '0px' && head.position === 'absolute', 'contact shares main transparent chrome band');
  check(Math.abs(head.flowY - head.pageY) < 1, 'contact scroller reaches page top for scroll-under');
  const flow = page.locator('[data-page="conversation"].live .fm-flow');
  const flowBox = await flow.boundingBox();
  await page.mouse.move(flowBox.x + flowBox.width / 2, 240);
  await page.mouse.wheel(0, -180);
  await settle(page);
  const under = await flow.evaluate(el => [...el.querySelectorAll('.fm-msg')].some(msg => { const r = msg.getBoundingClientRect(); return r.bottom > 0 && r.top < 100; }));
  check(under, 'contact messages actually cross the floating header band');
  await shot('contact-scrolled');
  check(session.errors.length === 0, 'no JS errors');
  // Reproduce retained desktop settings DOM, then enter mobile profile directly.
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.waitForFunction(() => !document.body.classList.contains('mobile-layer'));
  await page.evaluate(async () => (await import('/js/sidebar.js')).openSettingsPanel());
  await page.waitForSelector('#settings-content #neblink-logout');
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForSelector('body.mobile-layer');
  await page.evaluate(async () => (await import('/js/mobile/navController.js')).dockTo('workbench'));
  await page.locator('[data-me-entry]').click();
  await page.waitForSelector('[data-page="me"].live #neblink-logout');
  const beforeLogout = logoutRequests;
  const transitionRequest = page.waitForRequest(req => req.url().includes('/api/neblink/auth/end-session'));
  await page.locator('[data-page="me"].live #neblink-logout').click();
  await transitionRequest;
  check(logoutRequests === beforeLogout + 1, 'desktop to mobile account binds visible logout exactly once');
  // Mobile-rendered settings must regain its desktop account card on restoration.
  await page.evaluate(async () => {
    const nav = await import('/js/mobile/navController.js');
    nav.dockTo('workbench'); nav.pushPage('settings');
  });
  await page.waitForSelector('[data-page="settings"].live #settings-content');
  check(await page.locator('#settings-content #neblink-logout').count() === 0, 'mobile settings omits retained desktop account');
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.waitForFunction(() => !document.body.classList.contains('mobile-layer'));
  await page.waitForSelector('#settings-content #neblink-logout');
  check(await page.locator('#settings-content #account-card').count() === 1, 'mobile to desktop restores full account card');
  await page.route('**/api/neblink/status', route => route.fulfill({ json: { loggedIn: false, peers: [] } }));
  await page.evaluate(async () => (await import('/js/neblink.js')).refreshNeblink());
  await page.waitForFunction(() => !document.querySelector('#settings-content #neblink-logout'));
  check(await page.locator('#settings-content .neblink-login-section').count() === 1, 'desktop account refresh survives mobile owner teardown');
  check(session.errors.length === 0, 'no JS errors after desktop return');
  writeFileSync(join(out, `${engine}-page-readings.json`), JSON.stringify({ hero, head, empty, filled, errors: session.errors, network: session.netErrors, checks, failures, captures }, null, 2));
  await context.close();
} finally { await browser.close(); }
console.log(`${engine}: ${checks.length} checks; ${failures.length} failed; ${out}`);
if (!process.env.CANVAS_CAPTURE_ONLY) assert.deepEqual(failures, []);

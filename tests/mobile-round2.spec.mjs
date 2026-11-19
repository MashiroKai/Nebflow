// Scenario: mobile round two canvas workbench badges back and top-card motion
// Real production components with synthetic REST/WS inputs; no live data.
import assert from 'node:assert/strict';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { createSession, loadPlaywright, settle } from '../scripts/lib/visual-harness.mjs';
const out = process.env.ROUND2_OUT || '/tmp/mobile-round2-evidence/after';
const captureOnly = process.env.ROUND2_CAPTURE_ONLY === '1';
mkdirSync(out, { recursive: true });
const { mod } = loadPlaywright();
// Compare actual painted icon pixels: computed stroke and hit tests alone miss
// pointer-transparent morph seeds painting above the icon.
const iconPixels = path => Number(execFileSync('python3', ['-c', 'from PIL import Image; import sys; im=Image.open(sys.argv[1]).convert("RGB"); print(sum(max(p)-min(p)<80 and sum(p)/3<100 for p in im.get_flattened_data()))', path], { encoding: 'utf8' }).trim());
const results = [];
const engines = (process.env.ROUND2_ENGINES || 'chromium,webkit').split(',');
for (const engine of engines) {
  const browser = await mod[engine].launch({ headless: true });
  try {
    for (const width of (process.env.ROUND2_WIDTHS || '390,320').split(',').map(Number)) for (const theme of (process.env.ROUND2_THEMES || 'light,dark').split(',')) {
      const context = await browser.newContext({ viewport: { width, height: 844 }, hasTouch: true, deviceScaleFactor: 2, colorScheme: theme, reducedMotion: 'no-preference' });
      const session = await createSession({ newPage: () => context.newPage() }, { width, height: 844, colorScheme: theme, baseURL: 'https://stand.test/', apiStubs: {
        '/api/neblink/status': { body: { loggedIn: true, device: { id: 'self-device', name: 'Fixture', displayName: 'Fixture Person', email: 'fixture@example.test', userId: 'self' }, peers: [] } },
        '/api/users/search': { body: { users: [] } },
        '/api/friends': { body: { friends: [], incoming: [], outgoing: [] } },
        '/api/conversations': { body: [
          { conversationId: 'friend-fixture', friend: { userId: 'peer', displayName: 'Contact fixture' }, unreadCount: 2 },
          { conversationId: 'dev:remote-device', deviceId: 'remote-device', kind: 'device', device: { id: 'remote-device', name: 'Device fixture' }, unreadCount: 3 },
        ] },
        '/api/groups': { body: [
          { groupId: 'group-fixture', selfUserId: 'self', title: 'Group fixture', unreadCount: 4 },
        ] },
        '/api/groups/invites': { body: { incoming: [] } },
        '/api/plugins': { body: { plugins: [] } },
        '/api/config/provider-types': { body: { domains: [{ id: 'llm' }, { id: 'imagegen' }] } },
        '/api/config/imagegen': { body: { enabled: false, providers: {} } },
        '/api/config/provider-keys': { body: {} },
      } });
      const { page } = session;
      if (process.env.ROUND2_BASE) {
        // Read baseline bytes from Git objects; never switch/reset the shared tree.
        const files = execFileSync('git', ['diff', '--name-only', process.env.ROUND2_BASE, '--', 'src/main/resources/web/'], { encoding: 'utf8' }).trim().split('\n').filter(Boolean);
        for (const file of files) {
          const path = '/' + file.replace('src/main/resources/web/', '');
          const bytes = execFileSync('git', ['show', `${process.env.ROUND2_BASE}:${file}`]);
          await page.route(`https://stand.test${path}`, route => route.fulfill({ body: bytes, contentType: file.endsWith('.css') ? 'text/css' : 'text/javascript' }));
        }
      }
      const row = { engine, width, theme, checks: [], readings: {}, screenshots: [], errors: session.errors };
      results.push(row);
      const check = (ok, name) => row.checks.push({ name, ok: !!ok });
      const shot = async name => { const path = join(out, `${engine}-${width}-${theme}-${name}.png`); await page.screenshot({ path }); row.screenshots.push(path); };
      const drag = async (x0, x1, y, during) => {
        await page.mouse.move(x0, y); await page.mouse.down();
        await page.mouse.move(x1, y, { steps: 8 });
        if (during) await during();
        await page.mouse.up();
        await settle(page);
      };
      try {
        const providers = Object.fromEntries(Array.from({ length: 8 }, (_, i) => [`fixture-${i}`, { name: `Fixture provider ${i + 1}`, apiBase: 'https://provider.example.test', models: ['fixture-model'] }]));
        await session.boot({ config: { features: { friends: true, groupChat: true }, llm: { providers } } });
        await page.waitForSelector('body.mobile-layer');
        await page.emulateMedia({ reducedMotion: 'no-preference' });
        for (let n = 1; n <= 3; n++) await page.evaluate(async n => {
          window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: { id: `file:/tmp/round2-${n}.html`, itemType: 'html', title: `Board ${n}`, content: '<!doctype html><html><body><h1>Board content</h1>' + '<p>Synthetic readable board content.</p>'.repeat(70) + '</body></html>', absPath: `/tmp/round2-${n}.html`, pinned: true } }));
          (await import('/js/mobile/navController.js')).dockTo('canvas');
        }, n);
        await settle(page);
        row.readings.canvas = await page.evaluate(() => {
          const visible = el => el.getClientRects().length && getComputedStyle(el).display !== 'none';
          const rect = el => { const r = el.getBoundingClientRect(); return { x: r.x, y: r.y, w: r.width, h: r.height, right: r.right, bottom: r.bottom, cx: r.x + r.width / 2, cy: r.y + r.height / 2 }; };
          const controls = [...document.querySelectorAll('.mf-cv-host > .canvas-tab-pane button, .ask-fab')].filter(visible).map(el => {
            const r = rect(el); const svg = el.querySelector('svg'); const s = getComputedStyle(el); const ic = svg && getComputedStyle(svg); const target = document.elementFromPoint(r.cx, r.cy);
            return { name: el.className, title: el.getAttribute('aria-label') || el.title, ...r, hit: target === el || el.contains(target), ink: s.color, stroke: ic?.stroke, opacity: ic?.opacity, svg: !!svg, svgRect: svg ? rect(svg) : null, svgStyle: svg ? { position: ic.position, zIndex: ic.zIndex, filter: ic.filter, backdrop: ic.backdropFilter, bg: ic.backgroundColor, childStyles: svg.children[0] ? { backdrop:getComputedStyle(svg.children[0]).backdropFilter, strokeWidth:getComputedStyle(svg.children[0]).strokeWidth } : null, ns: svg.namespaceURI, children: [...svg.children].map(c => ({ ns:c.namespaceURI, stroke:getComputedStyle(c).stroke, opacity:getComputedStyle(c).opacity, color:getComputedStyle(c).color, fill:getComputedStyle(c).fill, rect:rect(c) })) } : null, pseudo: ['::before', '::after'].map(p => { const s = getComputedStyle(el, p); return { content: s.content, bg: s.backgroundColor, z: s.zIndex }; }), ancestry: (() => { const a = []; for(let p=el;p;p=p.parentElement) a.push({tag:p.tagName, cls:p.className, opacity:getComputedStyle(p).opacity}); return a; })(), hidden: el.hidden, html: el.innerHTML.slice(0, 350) };
          });
          const dots = rect(document.querySelector('.mf-dots'));
          return { dots, controls };
        });
        await shot('01-02-03-canvas');
        row.readings.iconPixels = [];
        for (const [i, el] of (await page.locator('.ask-fab').all()).entries()) {
          const r = await el.boundingBox();
          const path = join(out, `${engine}-${width}-${theme}-02-icon-${i}.png`);
          await page.screenshot({ path, clip: { x: r.x + r.width / 2 - 10, y: r.y + r.height / 2 - 10, width: 20, height: 20 } });
          row.screenshots.push(path);
          // Dark-mode ink is bright; inspect actual occluding sibling order too.
          const z = await el.evaluate(el => ({ fab: +getComputedStyle(el).zIndex, docks: [...document.querySelectorAll('.ask-dock,.note-dock')].map(d => +getComputedStyle(d).zIndex) }));
          row.readings.iconPixels.push({ path, darkPixels: iconPixels(path), z });
        }
        check(row.readings.iconPixels.every(p => p.z.docks.every(z => p.z.fab > z) && (theme === 'dark' || p.darkPixels > 80)), '02 actual painted icons above closed morph seeds');
        if (!process.env.ROUND2_BASE) {
          await page.locator('.note-fab').click();
          check(await page.locator('.note-dock:not(.closed)').isVisible(), '02 note action opens real panel');
          await page.locator('.note-fab').click();
          await page.locator('.ask-fab:not(.note-fab)').click();
          check(await page.locator('.ask-dock:not(.closed)').isVisible(), '02 ask action opens real panel');
          await page.locator('.ask-fab:not(.note-fab)').click();
        }
        const cv = row.readings.canvas;
        const tools = cv.controls.filter(c => /canvas-(source-toggle|ref-select|find-toggle)|ask-fab/.test(c.name));
        check(tools.every(c => Math.abs(c.cy - cv.dots.cy) < 1), '01 tools and pagination share one row');
        check(Math.abs(cv.dots.cx - width / 2) < 1, '01 pagination centered');
        check(tools.filter(c => c.cx < width / 2).length <= 2 && tools.filter(c => c.cx > width / 2).length <= 2 && !tools.some(c => c.name.includes('canvas-source-toggle')), '03 two controls per side and no source toggle');
        check(tools.every(c => c.svg && c.opacity !== '0' && !/255, 255, 255/.test(c.ink) && c.hit), '02 meaningful visible hit-testable icons');
        check(tools.every(c => c.w >= 44 && c.h >= 44 && c.x >= 0 && c.right <= width), '03 touch targets contained');
        await page.evaluate(async () => { await (await import('/js/neblink.js')).fetchNeblinkStatus(); (await import('/js/mobile/navController.js')).dockTo('workbench'); });
        await settle(page);
        row.readings.hero = await page.locator('[data-me-entry]').evaluate(el => { const s = getComputedStyle(el); return { bg: s.backgroundColor, image: s.backgroundImage, shadow: s.boxShadow, border: s.borderWidth }; });
        check(row.readings.hero.bg === 'rgba(0, 0, 0, 0)' && row.readings.hero.image === 'none' && row.readings.hero.shadow === 'none' && row.readings.hero.border === '0px', '04 hero directly on page background');
        await shot('04-workbench');
        await page.locator('[data-hub-page="settings"]').click();
        await page.waitForSelector('[data-page="settings"].live #settings-content');
        await page.locator('[data-settings-disclosure="providers"]').click();
        await page.waitForSelector('#provider-list [data-component="cfg-provider-card"]');
        check(await page.locator('#provider-list [data-component="cfg-provider-card"]').count() === 8, '06 eight synthetic configured providers rendered');
        await page.locator('#settings-content').evaluate(el => el.scrollTo(0, el.scrollHeight));
        await settle(page);
        row.readings.settings = await page.locator('#settings-content').evaluate(el => { const r = el.getBoundingClientRect(); const last = el.lastElementChild.getBoundingClientRect(); return { rect: { top: r.top, bottom: r.bottom, height: r.height }, last: { top: last.top, bottom: last.bottom }, skirt: getComputedStyle(el.parentElement, '::after').content, hit: el.contains(document.elementFromPoint(r.x + r.width / 2, Math.min(last.bottom - 4, r.bottom - 4))) }; });
        check(row.readings.settings.skirt === 'none' && row.readings.settings.last.bottom <= row.readings.settings.rect.bottom + 1 && row.readings.settings.hit, '06 settings bottom fully reachable');
        await shot('06-settings-bottom');
        // Expanded provider section must not be the end of the scroll range.
        await page.locator('#provider-list [data-component="cfg-provider-card"]').last().scrollIntoViewIfNeeded();
        check(await page.locator('#provider-list [data-component="cfg-provider-card"]').last().isVisible(), '06 last expanded provider card reachable');
        await shot('06-eight-providers');
        await page.locator('#settings-content').evaluate(el => el.scrollTo(0, el.scrollHeight));
        // Leftward swipe from the right edge, retaining left-edge rightward back too.
        const returns = [];
        for (const id of ['settings', 'me', 'plugins', 'settings-stt', 'settings-ttl']) {
          await page.evaluate(async id => { const nav = await import('/js/mobile/navController.js'); nav.dockTo('workbench'); nav.pushPage(id); }, id);
          await settle(page);
          await drag(width - 5, width - 155, 360);
          const top = await page.evaluate(() => window.__nfMobileNav.top);
          returns.push({ id, top, layers: await page.evaluate(() => ({ depth: document.documentElement.dataset.backSwipeDepth, last: document.documentElement.dataset.backSwipeLast, stack: window.__nfMobileNav.stack })) });
        }
        row.readings.returns = returns;
        check(returns.every(r => r.top === 'workbench'), '07 all sampled workbench subpages return on leftward edge swipe');
        // Native Chromium touch input confirms browser scroll arbitration too.
        if (engine === 'chromium') {
          await page.evaluate(async () => { const n = await import('/js/mobile/navController.js'); n.dockTo('workbench'); n.pushPage('settings'); });
          const cdp = await context.newCDPSession(page);
          await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: width - 5, y: 360 }] });
          for (const dx of [12, 35, 75, 120, 150]) await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: width - 5 - dx, y: 360 }] });
          await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
          await settle(page);
          check(await page.evaluate(() => window.__nfMobileNav.top) === 'workbench', '07 native CDP touch leftward settings back');
          await cdp.detach();
        }
        // Capture a trusted cancellation after a committed-distance touch drag.
        if (engine === 'chromium') {
          await page.evaluate(async () => { const n = await import('/js/mobile/navController.js'); n.dockTo('workbench'); n.pushPage('me'); });
          const cdp = await context.newCDPSession(page);
          await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: width - 5, y: 360 }] });
          await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: width - 155, y: 360 }] });
          await cdp.send('Input.dispatchTouchEvent', { type: 'touchCancel', touchPoints: [] });
          await settle(page);
          check(await page.evaluate(() => window.__nfMobileNav.top) === 'me', '07 cancelled native edge gesture does not return');
          await cdp.detach();
        }
        await shot('07-returned');
        // Secondary sheet path uses its own shared overlay back handler.
        await page.evaluate(async () => { const n = await import('/js/mobile/navController.js'); n.dockTo('workbench'); n.pushPage('settings'); });
        await page.locator('[data-settings-disclosure="providers"]').click();
        await page.waitForSelector('#btn-add-provider');
        await page.locator('#btn-add-provider').click();
        await page.waitForSelector('.mfs-back:visible');
        const overlayBefore = await page.locator('.mfs-back:visible').count();
        await drag(width - 5, width - 155, 360);
        const overlayAfter = await page.locator('.mfs-back:visible').count();
        row.readings.overlayReturn = { before: overlayBefore, after: overlayAfter, top: await page.evaluate(() => window.__nfMobileNav.top) };
        check(overlayAfter < overlayBefore && row.readings.overlayReturn.top === 'settings', '07 secondary provider sheet closes without popping settings');
        await shot('07-provider-sheet-return');
        // Production media component, mounted inside a real detail page shell.
        row.readings.stacks = [];
        for (const count of [2, 3, 5]) {
          await page.evaluate(async count => {
            const nav = await import('/js/mobile/navController.js'); nav.dockTo('workbench'); nav.pushPage('me');
            const { buildMediaStack } = await import('/js/popArtifacts.js');
            document.querySelector('#round2-stack')?.remove();
            const stack = buildMediaStack(Array.from({ length: count }, (_, i) => ({ kind: 'image', name: `Fixture ${i + 1}`, src: 'data:image/svg+xml,' + encodeURIComponent(`<svg xmlns="http://www.w3.org/2000/svg" width="220" height="280"><rect width="220" height="280" fill="${['#426b91', '#536f42', '#8b6251', '#655a89', '#417b79'][i]}"/><text x="30" y="140" fill="white" font-size="30">Image ${i + 1}</text></svg>`) })), { onOpen: () => {} });
            stack.id = 'round2-stack';
            const body = document.querySelector('[data-page="me"] .mobile-page-body');
            body.appendChild(stack); stack.style.margin = '80px 0';
            body.scrollTop = 0;
          }, count);
          await settle(page);
          const view = page.locator('#round2-stack .pop-stack-view');
          await view.scrollIntoViewIfNeeded();
          const box = await view.boundingBox();
          const state = () => page.locator('#round2-stack').evaluate(el => ({ index: el.popQuoteState().index, parent: getComputedStyle(el.querySelector('.pop-stack-view')).transform, layers: [...el.querySelectorAll('.pop-stack-view .media-layer')].map(l => ({ transform: getComputedStyle(l).transform, rect: (() => { const r = l.getBoundingClientRect(); return { x: r.x, y: r.y, w: r.width, h: r.height }; })(), cls: l.className })), page: window.__nfMobileNav.top }));
          const before = await state();
          await shot(`08-${count}-0-rest`);
          let middle;
          const x = Math.min(width - 45, box.x + box.width * .7);
          await page.mouse.move(x, box.y + box.height / 2); await page.mouse.down();
          await page.mouse.move(x - 90, box.y + box.height / 2, { steps: 8 });
          middle = await state(); await shot(`08-${count}-1-drag`);
          await page.mouse.up();
          const outgoing = await state();
          await shot(`08-${count}-2-outgoing`);
          const animations = await page.locator('#round2-stack .pop-stack-view').evaluate(el => el.getAnimations({ subtree: true }).map(a => ({ target: a.effect.target.className, properties: a.effect.getKeyframes().map(f => Object.keys(f)) })));
          check(animations.every(a => a.target.includes('layer-front') && a.properties.every(keys => keys.every(k => ['transform','offset','computedOffset','easing','composite'].includes(k)))), `08 ${count} outgoing animations transform-only on top card`);
          await page.waitForFunction(() => !document.querySelector('#round2-stack .is-switching'));
          const after = await state();
          await shot(`08-${count}-2-settled`);
          await view.press('ArrowLeft');
          const keyboard = await state();
          check(keyboard.index === 0, `08 ${count} keyboard previous retained`);
          await page.locator('#round2-stack .pill').click();
          await page.waitForFunction(n => [...document.querySelectorAll('#round2-stack .pop-expand-view .media-layer')].filter(el => el.getBoundingClientRect().height > 0).length === n, count);
          check(await page.locator('#round2-stack .pop-expand-view .media-layer:visible').count() === count, `08 ${count} expand retained`);
          await page.locator('#round2-stack .pill').click();
          await view.scrollIntoViewIfNeeded();
          const lowerStatic = before.layers.slice(1, Math.min(3, count)).every((l, i) => JSON.stringify(l.rect) === JSON.stringify(middle.layers[i + 1].rect));
          check(lowerStatic && JSON.stringify(before.layers[0].rect) !== JSON.stringify(middle.layers[0].rect) && middle.parent === 'none', `08 ${count} cards only top moves during drag`);
          check(after.index === 1 && after.page === 'me', `08 ${count} cards swipe advances without returning`);
          row.readings.stacks.push({ count, before, middle, outgoing, animations, after, lowerStatic });
          await drag(width - 5, width - 155, box.y + box.height / 2);
          check(await page.evaluate(() => window.__nfMobileNav.top) === 'workbench', `08 ${count} cards right edge returns not switches`);
        }
        // Badges: REST baselines plus real friend_event websocket consumer.
        await page.evaluate(async () => (await import('/js/mobile/navController.js')).dockTo('messages'));
        await settle(page);
        await page.evaluate(async () => { await (await import('/js/neblink.js')).fetchNeblinkStatus(); window.dispatchEvent(new Event('fm-friends-changed')); });
        await page.waitForFunction(async () => {
          const rows = (await import('/js/messages.js')).conversationSnapshot();
          return rows.some(c => c.conversationId === 'friend-fixture' && c.unreadCount === 2)
            && rows.some(c => c.conversationId === 'dev:remote-device' && c.unreadCount === 3)
            && rows.some(c => c.conversationId === 'group-fixture' && c.unreadCount === 4);
        });
        // Snapshot is a copy; badge publication follows after the refresh render completes.
        await page.waitForFunction(() => document.querySelector('#messages-badge')?.textContent === '9');
        await page.waitForFunction(() => document.querySelector('#mobile-dock [data-dock="messages"] .dock-badge')?.textContent === '9');
        const badges = () => page.locator('#mobile-dock .dock-badge').evaluateAll(els => els.map(el => ({ text: el.textContent, hidden: el.hidden, display: getComputedStyle(el).display, key: el.parentElement.dataset.dock })));
        row.readings.badgeDebug = await page.evaluate(async () => ({ loggedIn: (await import('/js/neblink.js')).getNeblinkState().loggedIn, desktop: document.getElementById('messages-badge')?.outerHTML, state: (await import('/js/messages.js')).conversationSnapshot().map(c => ({ id: c.conversationId, unread: c.unreadCount })) }));
        const baseline = await badges();
        const send = async (id, origin, kind = 'text', conversationId = 'friend-fixture') => { await session.frame({ type: 'friend_event', event: 'message_new', conversationId, messageId: id, senderId: conversationId === 'dev:remote-device' ? 'self' : 'peer', senderDeviceId: 'remote-device', kind, origin, body: 'Synthetic message', createdAt: 1757000000000 + id }); await settle(page); };
        await send(7001, 'agent'); const agent = await badges();
        await send(7002, 'system'); const system = await badges();
        await send(7003, 'user'); const user = await badges();
        await send(7004, 'user', 'text', 'dev:remote-device'); const device = await badges();
        await send(7005, 'user', 'text', 'group-fixture'); const group = await badges();
        await send(7006, 'agent', 'text', 'group-fixture'); const groupAgent = await badges();
        row.readings.badges = { baseline, agent, system, user, device, group, groupAgent };
        const countBadge = bs => bs.find(b => !b.hidden && b.text)?.text;
        check(countBadge(baseline) === '9' && countBadge(agent) === '9' && countBadge(system) === '9' && countBadge(user) === '10' && countBadge(device) === '11' && countBadge(group) === '12' && countBadge(groupAgent) === '12', '05 human contact device group count but agent system do not');
        check(user.filter(b => !b.hidden).length === 1 && user.filter(b => !b.hidden).every(b => b.text), '05 no empty gray badge pills');
        await shot('05-message-badge');
        // Desktop controls remain enabled after mobile teardown.
        await page.setViewportSize({ width: 1280, height: 800 });
        await page.waitForFunction(() => !document.body.classList.contains('mobile-layer'));
        await page.evaluate(async () => { const canvas = await import('/js/canvas.js'); canvas.setActiveTab((await import('/js/mobile/navController.js')).docTabs()[0].id); canvas.openCanvas(); });
        check(await page.locator('.canvas-tab-pane.active .canvas-source-toggle').isVisible(), 'desktop source toggle preserved');
        await shot('desktop');
        check(session.errors.length === 0, 'no JS or console errors');
      } catch (error) { row.exception = String(error.stack || error); row.failureState = await page.evaluate(async () => ({ loggedIn: (await import('/js/neblink.js')).getNeblinkState().loggedIn, badge: document.getElementById('messages-badge')?.outerHTML, rows: (await import('/js/messages.js')).conversationSnapshot().map(c => ({ id: c.conversationId, unread: c.unreadCount })) })).catch(() => null); check(false, 'scenario execution completed'); }
      finally { await context.close(); writeFileSync(join(out, 'readings.json'), JSON.stringify(results, null, 2)); }
    }
  } finally { await browser.close(); }
}
const failures = results.flatMap(r => r.checks.filter(c => !c.ok).map(c => `${r.engine}/${r.width}/${r.theme}: ${c.name}`));
console.log(JSON.stringify({ contexts: results.length, checks: results.reduce((n, r) => n + r.checks.length, 0), failures, out }, null, 2));
if (!captureOnly) assert.deepEqual(failures, []);

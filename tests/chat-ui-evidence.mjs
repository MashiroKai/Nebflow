// Synthetic inputs, production renderers/CSS; no server, live account or network.
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { createSession, loadPlaywright, settle } from '../scripts/lib/visual-harness.mjs';
const out = process.env.CHAT_UI_OUT || '/tmp/chat-unify-evidence';
mkdirSync(out, { recursive: true });
const { mod } = loadPlaywright();
const browser = await mod.chromium.launch({ headless: true });
const results = [];
try {
  for (const [device, width, height] of [['desktop', 1440, 900], ['mobile', 390, 844]]) for (const theme of ['light', 'dark']) {
    const ctx = await browser.newContext({ viewport: { width, height }, colorScheme: theme, hasTouch: device === 'mobile', reducedMotion: 'reduce' });
    const s = await createSession({ newPage: () => ctx.newPage() }, { width, height, colorScheme: theme, baseURL: 'https://stand.test/' });
    const { page } = s;
    if (process.env.CHAT_UI_BASE) {
      const files = execFileSync('git', ['diff', '--name-only', process.env.CHAT_UI_BASE, '--', 'src/main/resources/web/'], { encoding: 'utf8' }).trim().split('\n').filter(Boolean);
      for (const file of files) {
        const bytes = execFileSync('git', ['show', `${process.env.CHAT_UI_BASE}:${file}`]);
        const path = '/' + file.replace('src/main/resources/web/', '');
        await page.route(`https://stand.test${path}`, route => route.fulfill({ body: bytes, contentType: file.endsWith('.css') ? 'text/css' : 'text/javascript' }));
      }
    }
    const shot = async (id, variant, locator) => {
      await locator.first().waitFor({ state: 'visible', timeout: 15000 });
      const file = join(out, `${id}-${variant}-${device}-${theme}.png`);
      await locator.first().screenshot({ path: file, animations: 'disabled', caret: 'hide' });
      results.push({ id, variant, device, theme, file });
    };
    try {
      await s.boot({ messages: [
        { type: 'user', text: 'Synthetic question for visual comparison.', timestamp: 1760000000000 },
        { type: 'ai', text: 'Synthetic answer for visual comparison.', timestamp: 1760000001000 },
      ] });
      await page.waitForSelector('#chat .bubble', { state: 'visible' });
      await shot('D1', 'A-main', page.locator('#chat .bubble'));
      await shot('D2', 'A-main', page.locator('#input-bar'));
      await shot('D3', 'A-main', page.locator('#chat .duration-badge'));
      // Exercise the production message renderer and composer in a fixture host,
      // not a live conversation; preserve their native class hierarchy.
      await page.evaluate(async () => {
        const { bubbleEl } = await import('/js/messages.js');
        const { buildFmComposer } = await import('/js/messages/fmComposer.js');
        const host = document.querySelector('#chat');
        const flow = document.createElement('div'); flow.className = 'fm-flow';
        flow.append(bubbleEl({ id: 'fixture', body: 'Synthetic message for comparison.', ours: true, createdAt: 1760000000000 }, { id: 'fixture', kind: 'direct', friend: { userId: 'peer' } }));
        const composer = buildFmComposer({ canAttach: true, onSend() {}, onFiles() {} });
        host.append(flow, composer.bar);
      });
      await shot('D1', 'B-messages', page.locator('.fm-flow .bubble'));
      await shot('D2', 'B-messages', page.locator('.fm-input-bar'));
      await shot('D3', 'B-messages', page.locator('.fm-msg-meta'));
      await page.evaluate(async mobile => {
        window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: { id: 'file:/tmp/chat-fixture.html', itemType: 'html', title: 'Fixture', content: '<h1>Fixture document</h1>', absPath: '/tmp/chat-fixture.html', pinned: true } }));
        if (mobile) (await import('/js/mobile/navController.js')).dockTo('canvas');
      }, device === 'mobile');
      await settle(page);
      await page.locator('.ask-fab:not(.note-fab)').first().click();
      await page.waitForSelector('.ask-dock:not(.closed)', { state: 'visible' });
      await shot('buttons', 'ask-send', page.locator('.ask-dock .ask-send'));
      await page.locator('.ask-dock textarea').fill('Synthetic question for visual comparison.');
      await page.locator('.ask-dock .ask-send').click();
      await shot('buttons', 'ask-stop', page.locator('.ask-dock .ask-stop'));
      await settle(page);
      await shot('D1', 'C-ask', page.locator('.ask-dock .ask-bub'));
      await shot('D2', 'C-ask', page.locator('.ask-dock .ask-input'));
      // Ask intentionally has no message footer: the entire dock shows absence.
      await shot('D3', 'C-ask-no-footer', page.locator('.ask-dock'));
      if (s.errors.length) throw new Error(`Browser errors: ${s.errors.join('; ')}`);
    } finally { await ctx.close(); }
  }
} finally { await browser.close(); writeFileSync(join(out, 'index.json'), JSON.stringify(results, null, 2)); }
console.log(JSON.stringify({ captures: results.length, index: join(out, 'index.json') }));

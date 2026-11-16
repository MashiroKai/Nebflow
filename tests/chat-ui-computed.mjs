// Synthetic fixture, production renderers and CSS: compare computed chat surface contracts.
import assert from 'node:assert/strict';
import { createSession, loadPlaywright } from '../scripts/lib/visual-harness.mjs';

const { mod } = loadPlaywright();
const browser = await mod.chromium.launch({ headless: true });
let checks = 0;
try {
  for (const [device, width, height] of [['desktop', 1440, 900], ['mobile', 390, 844]]) {
    for (const theme of ['light', 'dark']) {
      const context = await browser.newContext({ viewport: { width, height }, colorScheme: theme, hasTouch: device === 'mobile', reducedMotion: 'reduce' });
      try {
        const s = await createSession({ newPage: () => context.newPage() }, { width, height, colorScheme: theme, baseURL: 'https://stand.test/' });
        const { page } = s;
        await s.boot({ messages: [
          { type: 'user', text: 'Synthetic question.', timestamp: 1760000000000 },
          { type: 'ai', text: 'Synthetic answer.', timestamp: 1760000001000 },
        ] });
        await page.waitForSelector('#chat .bubble');
        await page.evaluate(async () => {
          const { bubbleEl } = await import('/js/messages.js');
          const { buildFmComposer } = await import('/js/messages/fmComposer.js');
          const host = document.querySelector('#chat');
          const flow = document.createElement('div'); flow.className = 'fm-flow';
          flow.append(bubbleEl({ id: 'fixture', body: 'Synthetic message.', ours: true, createdAt: 1760000000000 }, { id: 'fixture', kind: 'direct', friend: { userId: 'peer' } }));
          host.append(flow, buildFmComposer({ canAttach: true, onSend() {}, onFiles() {} }).bar);
        });
        const label = `${device}/${theme}`;
        const compare = async (name, left, right, properties, pseudo = null) => {
          const values = await page.evaluate(({ left, right, properties, pseudo }) => {
            const a = document.querySelector(left), b = document.querySelector(right);
            if (!a || !b) throw new Error(`Missing fixture: ${left} or ${right}`);
            const x = getComputedStyle(a, pseudo), y = getComputedStyle(b, pseudo);
            return Object.fromEntries(properties.map(p => [p, [x.getPropertyValue(p), y.getPropertyValue(p)]]));
          }, { left, right, properties, pseudo });
          for (const [property, [a, b]] of Object.entries(values)) {
            assert.equal(a, b, `${label} ${name} ${property}: main=${a} messages=${b}`);
            checks++;
          }
          return values;
        };
        await compare('bubble', '#chat .row .bubble.user', '.fm-flow .bubble.user', ['border-radius', 'border-top-width', 'border-top-style', 'border-top-color', 'box-shadow']);
        await compare('composer', '#input-bar', '.fm-input-bar', ['border-radius']);
        const pseudo = await compare('refraction', '#input-bar', '.fm-input-bar', ['content', 'display', 'height', 'background-image'], '::before');
        assert.notEqual(pseudo.content[0], 'none', `${label} composer refraction missing`); checks++;
        const focusedShells = [];
        for (const [selector, shell] of [['#input', '#input-bar'], ['.fm-input', '.fm-input-bar']]) {
          await page.locator(selector).focus();
          const state = await page.locator(selector).evaluate((el, shellSelector) => ({ focused: document.activeElement === el, shadow: getComputedStyle(el).boxShadow, outline: getComputedStyle(el).outlineStyle, shellShadow: getComputedStyle(document.querySelector(shellSelector)).boxShadow }), shell);
          assert.ok(state.focused, `${label} ${selector} focus failed`); checks++;
          assert.equal(state.shadow, 'none', `${label} ${selector} inner focus shadow`); checks++;
          assert.equal(state.outline, 'none', `${label} ${selector} inner focus outline`); checks++;
          focusedShells.push(state.shellShadow);
        }
        assert.equal(focusedShells[0], focusedShells[1], `${label} focused composer shell shadow`); checks++;
        await compare('footer', '#chat .row .duration-badge', '.fm-flow .duration-badge', ['border-radius', 'background-color', 'border-top-width', 'border-top-color', 'box-shadow', 'color', 'gap']);
        await compare('footer-time', '#chat .row .duration-badge-time', '.fm-flow .duration-badge-time', ['color', 'font-size', 'opacity']);
        if (s.errors.length) throw new Error(`Browser errors: ${s.errors.join('; ')}`);
        console.log(`PASS chat-ui-computed ${label}`);
      } finally { await context.close(); }
    }
  }
} finally { await browser.close(); }
console.log(`chat-ui-computed: ${checks} assertions passed`);

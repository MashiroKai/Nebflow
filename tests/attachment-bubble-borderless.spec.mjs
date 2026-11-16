// File reference attachment: live and restored cards retain their face without a bubble shell.
// Self-contained production web fixture; no gateway or pixel comparison.
import assert from 'node:assert/strict';
import { createSession, loadPlaywright, settle } from '../scripts/lib/visual-harness.mjs';

const { mod } = loadPlaywright();
const ref = { type: 'ref', refType: 'file', source: { path: '/tmp/fixture/notes.md', fileName: 'notes.md' }, display: { label: 'notes.md' } };
for (const engine of ['chromium', 'webkit']) {
  const browser = await mod[engine].launch({ headless: true });
  try {
    for (const theme of ['light', 'dark']) {
      const s = await createSession(browser, { width: 1280, height: 800, colorScheme: theme, baseURL: 'http://localhost:8380/' });
      try {
        await s.page.addInitScript(() => { localStorage.setItem('stand_sw', '0'); });
        await s.boot({ sessionId: 'chat-root', config: { llm: { providers: {} }, features: {} } });
        await s.page.evaluate(async ref => {
          const { renderUserBubble } = await import('/js/chat.js');
          const { appendAttachmentBubble } = await import('/js/persistence.js');
          renderUserBubble('fixture', [ref]);
          const row = document.createElement('div');
          row.className = 'row user';
          document.querySelector('#chat').appendChild(row);
          appendAttachmentBubble(row, ref);
        }, ref);
        await settle(s.page);
        const wrappers = s.page.locator('.bubble.user.att-ref-bubble:has(.att-ref-card.file-lang)');
        assert.equal(await wrappers.count(), 2, 'live and replay wrappers');
        for (let i = 0; i < 2; i++) {
          const wrapper = wrappers.nth(i);
          const card = wrapper.locator('.att-ref-card.file-lang');
          assert.equal(await card.locator('.fc-icon').textContent(), 'MD');
          assert.equal(await card.locator('.fc-name').textContent(), 'notes.md');
          assert.match(await card.locator('.fc-sub').textContent(), /fixture/);
          for (const state of ['normal', 'hover', 'focus']) {
            if (state === 'hover') await card.hover();
            if (state === 'focus') await card.evaluate(el => { el.tabIndex = 0; el.focus(); });
            const style = await wrapper.evaluate(el => {
              const s = getComputedStyle(el);
              return { bg: s.backgroundColor, border: [s.borderTopWidth, s.borderRightWidth, s.borderBottomWidth, s.borderLeftWidth], padding: [s.paddingTop, s.paddingRight, s.paddingBottom, s.paddingLeft], shadow: s.boxShadow, outline: s.outlineStyle };
            });
            assert.equal(style.bg, 'rgba(0, 0, 0, 0)', `${engine}/${theme}/${i}/${state} background`);
            assert.ok(style.border.every(v => parseFloat(v) === 0), JSON.stringify(style));
            assert.ok(style.padding.every(v => parseFloat(v) === 0), JSON.stringify(style));
            assert.equal(style.shadow, 'none');
            assert.equal(style.outline, 'none');
          }
          const face = await card.evaluate(el => { const s = getComputedStyle(el); return { bg: s.backgroundColor, border: s.borderTopStyle, display: s.display }; });
          assert.notEqual(face.bg, 'rgba(0, 0, 0, 0)', 'card retains surface');
          assert.equal(face.border, 'solid');
          assert.equal(face.display, 'flex');
        }
        assert.deepEqual(s.errors, []);
        console.log(`PASS ${engine}/${theme}: live and replay, normal/hover/focus`);
      } finally { await s.close(); }
    }
  } finally { await browser.close(); }
}

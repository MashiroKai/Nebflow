import { chromium } from 'playwright';
import { readFile } from 'node:fs/promises';
import { strict as assert } from 'node:assert';

const source = await readFile(new URL('./fixtures/historyPageRecycler.mjs', import.meta.url), 'utf8');
const browser = await chromium.launch();
try {
  const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
  await page.setContent('<div id="chat" style="height:300px;overflow:auto"></div>');
  const result = await page.evaluate(async (code) => {
    const url = URL.createObjectURL(new Blob([code], { type: 'text/javascript' }));
    const { createHistoryPageRecycler } = await import(url);
    URL.revokeObjectURL(url);
    const chat = document.querySelector('#chat');
    for (let i = 0; i < 5; i++) {
      const el = document.createElement('div');
      el.className = 'history-page';
      el.style.cssText = 'height:240px;box-sizing:border-box;border:1px solid black';
      el.textContent = `page-${i}`;
      chat.appendChild(el);
    }
    chat.scrollTop = 100;
    const anchor = chat.firstElementChild;
    const before = anchor.getBoundingClientRect().top;
    const originalHeight = chat.scrollHeight;
    const recycle = createHistoryPageRecycler(chat, { maxDetachedPages: 2 });
    const detached = recycle.collect();
    const after = anchor.getBoundingClientRect().top;
    const afterHeight = chat.scrollHeight;
    const placeholders = chat.querySelectorAll('[data-history-placeholder]').length;
    chat.scrollTop = 650;
    const restored = recycle.restoreVisible();
    return { before, after, originalHeight, afterHeight, detached, placeholders, restored,
      pageRestored: [...chat.children].some(el => el.textContent === 'page-3') };
  }, source);
  assert.equal(result.before, result.after, 'recycling must not move the visible anchor');
  assert.equal(result.originalHeight, result.afterHeight, 'measured placeholder preserves height');
  assert.equal(result.detached, 2, 'detachment is bounded');
  assert.equal(result.placeholders, 2);
  assert.ok(result.restored >= 1, 'scrolling down restores an approaching page');
  assert.ok(result.pageRestored);
  console.log('PASS history page recycling preserves anchor, height and bounded restoration');
} finally { await browser.close(); }

import { chromium } from 'playwright';
import { readFile } from 'node:fs/promises';
import assert from 'node:assert/strict';

const css = await Promise.all(['design-system.css', 'chat.css', 'mobile-todo-apple.css'].map(f => readFile(new URL(`../src/main/resources/web/css/${f}`, import.meta.url), 'utf8')));
const browser = await chromium.launch();
try {
  for (const width of [320, 390, 1200]) for (const theme of ['light', 'dark']) {
    const page = await browser.newPage({ viewport: { width, height: 800 }, colorScheme: theme });
    await page.setContent(`<style>${css.join('\n')}</style><body class="${width < 769 ? 'mobile-layer' : ''}"><div id="chat"><div class="option-box"><div class="option-opts"><button class="option-btn">选项</button><button class="option-btn opt-other">其他</button></div><button class="option-cancel">取消</button><button class="option-confirm">确认</button></div></div><div class="mobile-page" data-page="todo-quick-decision"><label class="ta-qd-opt">选项</label><span class="ta-qd-chip">高</span><button class="ta-tool-btn on">高</button></div></body>`);
    const values = await page.evaluate(() => {
      const probe = document.createElement('div'); probe.style.borderRadius = 'var(--ds-radius-pill)'; document.body.append(probe);
      return Object.fromEntries(['#chat .option-btn', '#chat .opt-other', '#chat .option-cancel', '#chat .option-confirm', '.ta-qd-opt', '.ta-qd-chip', '.ta-tool-btn.on'].map(s => [s, getComputedStyle(document.querySelector(s)).borderRadius]).concat([['pill', getComputedStyle(probe).borderRadius]]));
    });
    if (width < 769) for (const selector of ['#chat .option-btn', '#chat .opt-other', '.ta-qd-opt', '.ta-qd-chip', '.ta-tool-btn.on']) assert.equal(values[selector], values.pill, `${width} ${theme} ${selector}: ${JSON.stringify(values)}`);
    assert.equal(values['#chat .option-cancel'], '8px');
    assert.equal(values['#chat .option-confirm'], '12px');
    if (width === 1200) { assert.equal(values['#chat .option-btn'], '10px'); assert.equal(values['#chat .opt-other'], '8px'); }
    console.log('PASS', width, theme, JSON.stringify(values));
    await page.close();
  }
} finally { await browser.close(); }

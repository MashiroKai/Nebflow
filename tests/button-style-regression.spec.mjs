import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';
const web = new URL('../src/main/resources/web/', import.meta.url);
const index = readFileSync(new URL('index.html', web), 'utf8');
const css = [...index.matchAll(/<link\s+rel="stylesheet"\s+href="(css\/[^"]+)"/g)].map(m => readFileSync(new URL(m[1], web), 'utf8')).join('\n');
const browser = await chromium.launch({ headless: true });
try {
  for (const theme of ['light', 'dark']) for (const width of [1100, 390]) {
    const page = await browser.newPage({ viewport: { width, height: 800 } });
    await page.setContent(`<html data-theme="${theme}"><head><style>${css}</style></head><body><div id="search-modal"><div class="search-filters"><button class="cfg-select">筛选</button></div></div><div class="tfl-row"><div class="tfl-row-detail"><div class="ta-qd-opts"><label class="ta-qd-opt"><input class="ta-qd-opt-input" type="checkbox"><span class="ta-qd-opt-body"><span class="ta-qd-opt-label">选项</span></span></label></div><button class="ta-decision-submit">提交作答</button></div></div></body></html>`);
    for (const selector of ['.cfg-select', '.ta-decision-submit', '.ta-qd-opt-input']) {
      const style = await page.locator(selector).evaluate(el => { const c = getComputedStyle(el); return { appearance: c.appearance, border: c.borderStyle, font: c.fontFamily, background: c.backgroundColor }; });
      assert.equal(style.appearance, 'none', `${theme}/${width} ${selector} appearance`);
      assert.notEqual(style.border, 'outset', `${theme}/${width} ${selector} border`);
      assert.notEqual(style.font, 'Arial', `${theme}/${width} ${selector} font`);
      if (selector !== '.ta-qd-opt-input') assert.notEqual(style.background, 'rgb(239, 239, 239)', `${theme}/${width} ${selector} background`);
    }
    const image = await page.locator('.cfg-select').evaluate(el => getComputedStyle(el).backgroundImage);
    assert.notEqual(image, 'none', 'search caret');
    await page.close();
  }
  console.log('button-style-regression PASS');
} finally { await browser.close(); }

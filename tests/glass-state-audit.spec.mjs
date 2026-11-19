// Computed-style audit of selected glass control states; not an exhaustive page inventory.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import assert from 'node:assert/strict';
import { loadPlaywright, launchBrowser, webRoot } from '../scripts/lib/visual-harness.mjs';
const { mod } = loadPlaywright();
const { browser } = await launchBrowser(mod);
try {
  for (const theme of ['light', 'dark']) {
    const page = await browser.newPage({ colorScheme: theme });
    const css = ['base.css', 'stand-tokens.css', 'sapphire.css', 'glass.css']
      .map(f => readFileSync(join(webRoot(), 'css', f), 'utf8')).join('\n');
    await page.setContent(`<html data-theme="${theme}"><body><div id="input-bar"></div>
      <button class="canvas-ref-select"></button><button class="canvas-ref-select active"></button>
      <button id="deck-fab" aria-pressed="true"></button>
      <div class="ask-dock"><div class="ask-bub user"></div></div></body></html>`);
    await page.addStyleTag({ content: css });
    const result = await page.evaluate(() => [...document.querySelectorAll('#input-bar,.canvas-ref-select,#deck-fab,.ask-bub')].map(el => {
      const s = getComputedStyle(el);
      return { node: el.outerHTML.split('>')[0] + '>', background: s.backgroundColor, filter: s.backdropFilter };
    }));
    console.log(JSON.stringify({ theme, result }));
    for (const row of result.slice(1)) {
      assert.equal(row.background, result[0].background, `${theme}: ${row.node} background differs`);
      assert.equal(row.filter, result[0].filter, `${theme}: ${row.node} filter differs`);
    }
    await page.close();
  }
} finally { await browser.close(); }

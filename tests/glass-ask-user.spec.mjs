// Real CSS cascade check; NEBFLOW_WEB_ROOT selects an unchanged pre-fix source tree.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { loadPlaywright, launchBrowser, webRoot } from '../scripts/lib/visual-harness.mjs';
import assert from 'node:assert/strict';
const { mod } = loadPlaywright();
const { browser } = await launchBrowser(mod);
try {
  const page = await browser.newPage({ colorScheme: 'light' });
  const css = ['base.css', 'stand-tokens.css', 'sapphire.css', 'glass.css']
    .map(f => readFileSync(join(webRoot(), 'css', f), 'utf8')).join('\n');
  await page.setContent('<div id="input-bar"></div><div class="ask-dock"><div class="ask-bub user"></div></div>');
  await page.addStyleTag({ content: css });
  const styles = await page.evaluate(() => {
    const pick = el => { const s = getComputedStyle(el); return { bg: s.backgroundColor, filter: s.backdropFilter }; };
    return { reference: pick(document.querySelector('#input-bar')), consumer: pick(document.querySelector('.ask-bub.user')) };
  });
  console.log(JSON.stringify(styles));
  assert.deepEqual(styles.consumer, styles.reference, 'ask user glass must match composer background and filter');
  console.log('PASS ask user glass references composer material');
} finally { await browser.close(); }

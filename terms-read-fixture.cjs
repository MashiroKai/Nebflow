const { chromium } = require('playwright');
const { readFileSync } = require('node:fs');
const assert = require('node:assert/strict');

(async () => {
  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage();
    await page.route('http://fixture.test/**', async (route) => {
      const url = new URL(route.request().url());
      if (url.pathname === '/') return route.fulfill({ contentType: 'text/html', body: '<a id="term" href="#">Alias</a>' });
      if (url.pathname === '/termTip.js') return route.fulfill({ contentType: 'text/javascript', body: readFileSync('src/main/resources/web/js/termTip.js', 'utf8') });
      if (url.pathname === '/flowHelpers.js') return route.fulfill({ contentType: 'text/javascript', body: 'export const authHeaders = () => ({})' });
      if (url.pathname === '/api/wiki/terms/stats') return route.fulfill({ json: { last_rebuild: 'one' } });
      if (url.pathname === '/api/wiki/terms/lookup') return route.fulfill({ json: { available: true, results: [{ name: 'Primary', aliases: ['Alias'], definition: 'derived definition', doc: { project: 'test', rel: 'doc.md' } }] } });
      throw new Error('Unexpected request: ' + url.pathname);
    });
    await page.goto('http://fixture.test/');
    await page.evaluate(async () => {
      const tip = await import('/termTip.js');
      const a = document.querySelector('#term');
      a.dataset.wikiResolved = JSON.stringify({ term: { name: 'Alias', definition: 'old definition' } });
      tip.attachTermFace(a, null, { term: true });
      a.addEventListener('click', (event) => { event.preventDefault(); tip.termTapExpand(a, null); });
    });
    await page.locator('#term').click();
    await page.locator('.nf-term-tip .ts').waitFor({ state: 'visible' });
    assert.equal(await page.locator('.nf-term-tip .ts').textContent(), 'derived definition');
    console.log('PASS: derived alias lookup precedes resolved payload');
  } finally { await browser.close(); }
})().catch((error) => { console.error(error); process.exitCode = 1; });

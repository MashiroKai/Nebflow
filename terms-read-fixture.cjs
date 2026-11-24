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
    await page.evaluate(async () => {
      const tip = await import('/termTip.js');
      tip.hideTermTip();
      const a = document.querySelector('#term');
      a.dataset.wikiTarget = 'pending';
      window.aborted = false;
      const originalFetch = window.fetch;
      window.fetch = (url, options) => String(url).includes('/lookup?')
        ? new Promise((resolve, reject) => options.signal.addEventListener('abort', () => {
          window.aborted = true;
          reject(new DOMException('Aborted', 'AbortError'));
        })) : originalFetch(url, options);
      tip.termTapExpand(a, null);
    });
    await page.waitForFunction(() => document.querySelector('#term') && window.aborted === false);
    await page.waitForTimeout(50);
    await page.evaluate(async () => (await import('/termTip.js')).hideTermTip());
    assert.equal(await page.evaluate(() => window.aborted), true);
    console.log('PASS: closing aborts pending lookup');
    await page.evaluate(async () => {
      const tip = await import('/termTip.js');
      const a = document.querySelector('#term');
      a.dataset.wikiTarget = 'shared';
      a.dataset.wikiProject = 'one';
      a.dataset.wikiResolved = JSON.stringify({ term: { name: 'shared', rel: 'one.md', definition: 'fallback' } });
      window.fetch = async (url) => String(url).includes('/lookup?')
        ? new Response(JSON.stringify({ available: true, results: [
          { name: 'shared', definition: 'correct project', doc: { project: 'one', rel: 'one.md' } },
          { name: 'shared', definition: 'wrong project', doc: { project: 'two', rel: 'two.md' } },
        ] }), { status: 200 }) : new Response('{}', { status: 200 });
      tip.termTapExpand(a, null);
    });
    await page.waitForFunction(() => document.querySelector('.nf-term-tip .ts').textContent === 'correct project');
    console.log('PASS: duplicate name matches project and document identity');
    await page.evaluate(async () => {
      const tip = await import('/termTip.js');
      tip.hideTermTip();
      const a = document.querySelector('#term');
      a.dataset.wikiTarget = 'missing';
      delete a.dataset.wikiResolved;
      window.legacyUrl = '';
      window.fetch = async (url) => {
        if (String(url).includes('/lookup?')) return new Response('{}', { status: 404 });
        if (String(url).includes('/api/wiki/term?')) {
          window.legacyUrl = String(url);
          return new Response(JSON.stringify({ resolved: true, term: { name: 'missing', definition: 'legacy' } }), { status: 200 });
        }
        return new Response('{}', { status: 200 });
      };
      tip.termTapExpand(a, null);
    });
    await page.waitForFunction(() => document.querySelector('.nf-term-tip .ts').textContent === 'legacy');
    assert.match(await page.evaluate(() => window.legacyUrl), /project=one/);
    console.log('PASS: lookup miss falls back to project-scoped registry');
    await page.evaluate(async () => {
      const tip = await import('/termTip.js?epoch-test');
      const a = document.querySelector('#term');
      delete a.dataset.wikiResolved;
      a.dataset.wikiTarget = 'race';
      let now = 100000;
      Date.now = () => now;
      let lookups = 0;
      window.fetch = async (url) => {
        if (String(url).includes('/stats')) return new Response(JSON.stringify({ last_rebuild: now === 100000 ? 'one' : 'two' }));
        if (String(url).includes('/lookup?')) {
          lookups++;
          if (lookups === 2) await new Promise((resolve) => { window.releaseLookup = resolve; });
          return new Response(JSON.stringify({ available: true, results: [{ name: 'race', definition: 'lookup ' + lookups, doc: {} }] }));
        }
        return new Response('{}', { status: 404 });
      };
      window.testTip = tip;
      window.advanceClock = () => { now += 301000; };
      window.lookupCount = () => lookups;
      tip.termTapExpand(a, null);
    });
    await page.waitForFunction(() => window.lookupCount() === 1 && [...document.querySelectorAll('.nf-term-tip .ts')].some((el) => el.textContent === 'lookup 1'));
    await page.evaluate(() => { window.testTip.hideTermTip(); window.advanceClock(); window.testTip.termTapExpand(document.querySelector('#term'), null); });
    await page.waitForFunction(() => typeof window.releaseLookup === 'function');
    await page.evaluate(() => window.releaseLookup());
    await page.waitForFunction(() => window.lookupCount() === 2);
    console.log('PASS: version check runs alongside lookup');
  } finally { await browser.close(); }
})().catch((error) => { console.error(error); process.exitCode = 1; });

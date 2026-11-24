const { chromium } = require('playwright');
const { readFileSync } = require('node:fs');
const assert = require('node:assert/strict');

(async () => {
  const browser = await chromium.launch({ headless: true });
  try {
    const capture = async (page, state) => {
      if (process.env.TERM_SCREENSHOTS) await page.screenshot({ path: process.env.TERM_SCREENSHOTS + '/' + state + '.png' });
    };
    const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
    await page.route('http://fixture.test/**', async (route) => {
      const url = new URL(route.request().url());
      if (url.pathname === '/') return route.fulfill({ contentType: 'text/html', body: '<link rel="stylesheet" href="/split.css"><a id="term" href="#">Alias</a>' });
      if (url.pathname === '/termTip.js') return route.fulfill({ contentType: 'text/javascript', body: readFileSync('src/main/resources/web/js/termTip.js', 'utf8') });
      if (url.pathname === '/split.css') return route.fulfill({ contentType: 'text/css', body: readFileSync('src/main/resources/web/css/split.css', 'utf8') });
      if (url.pathname === '/flowHelpers.js') return route.fulfill({ contentType: 'text/javascript', body: 'export const authHeaders = () => ({})' });
      if (url.pathname === '/i18n.js') return route.fulfill({ contentType: 'text/javascript', body: 'export const t = (key) => key' });
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
    await capture(page, '390x844-before-activation');
    await page.locator('#term').click();
    await page.waitForFunction(() => document.querySelector('.nf-term-tip .ts').textContent === 'derived definition');
    assert.equal(await page.locator('.nf-term-tip .ts').textContent(), 'derived definition');
    await capture(page, '390x844-normal');
    console.log('PASS: derived alias lookup precedes resolved payload');
    await page.evaluate(async () => {
      const tip = await import('/termTip.js');
      tip.hideTermTip();
      const a = document.querySelector('#term');
      a.dataset.wikiTarget = 'empty-definition';
      window.fetch = async (url) => String(url).includes('/lookup?')
        ? new Response(JSON.stringify({ available: true, results: [{ name: 'empty-definition', definition: '', doc: {} }] }))
        : new Response('{}', { status: 404 });
      tip.termTapExpand(a, null);
    });
    await page.waitForFunction(() => document.querySelector('.nf-term-tip .tt').textContent === 'empty-definition');
    assert.equal(await page.locator('.nf-term-tip .ts').textContent(), '');
    await capture(page, '390x844-empty-definition');
    console.log('PASS: empty definition rendered');
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
    await capture(page, '390x844-loading');
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
    await page.waitForFunction(() => [...document.querySelectorAll('.nf-term-tip .ts')].some((el) => el.textContent === 'lookup 2'));
    await page.evaluate(() => { window.testTip.hideTermTip(); window.testTip.termTapExpand(document.querySelector('#term'), null); });
    await page.waitForFunction(() => window.lookupCount() === 3);
    console.log('PASS: late lookup after version change does not repopulate cache');
    await page.evaluate(async () => {
      (await import('/termTip.js')).hideTermTip();
      window.testTip.hideTermTip();
      const a = document.querySelector('#term');
      a.dataset.wikiTarget = 'unavailable';
      window.fetch = async () => new Response('{}', { status: 404 });
      window.testTip.termTapExpand(a, null);
      window.loadingObserved = [...document.querySelectorAll('.nf-term-tip .ts')].some((el) => el.textContent === 'termPreview.loading');
    });
    assert.equal(await page.evaluate(() => window.loadingObserved), true);
    await page.waitForFunction(() => [...document.querySelectorAll('.nf-term-tip .ts')].some((el) => el.textContent === 'termPreview.unavailable'));
    assert.equal(await page.locator('.nf-term-tip .term-tip-retry:not([hidden])').count(), 1);
    console.log('PASS: loading then unavailable with retry');
    await page.evaluate(() => {
      window.retryCalls = 0;
      window.fetch = async (url) => {
        if (String(url).includes('/lookup?')) {
          window.retryCalls++;
          if (window.retryCalls === 1) return new Response('{}', { status: 404 });
          return new Response(JSON.stringify({ available: true, results: [{ name: 'unavailable', definition: 'recovered', doc: {} }] }));
        }
        return new Response('{}', { status: 404 });
      };
      document.querySelector('.term-tip-retry:not([hidden])').click();
      window.retryLoading = [...document.querySelectorAll('.nf-term-tip .ts')].some((el) => el.textContent === 'termPreview.loading');
    });
    assert.equal(await page.evaluate(() => window.retryLoading), true);
    await page.waitForFunction(() => window.retryCalls === 1 && [...document.querySelectorAll('.nf-term-tip .ts')].some((el) => el.textContent === 'termPreview.unavailable'));
    assert.equal(await page.locator('.nf-term-tip .term-tip-retry:not([hidden])').count(), 1);
    await page.locator('.nf-term-tip .term-tip-retry:not([hidden])').click();
    await page.waitForFunction(() => [...document.querySelectorAll('.nf-term-tip .ts')].some((el) => el.textContent === 'recovered'));
    assert.equal(await page.evaluate(() => window.retryCalls), 2);
    console.log('PASS: failed retry remains available and subsequent retry succeeds');
    const fallbackCases = [
      ['down', { available: false, results: [] }],
      ['ambiguous', { available: true, results: [{ name: 'ambiguous' }, { name: 'ambiguous' }] }],
      ['malformed', 'not-json'],
      ['missing', null],
    ];
    for (const [name, response] of fallbackCases) {
      await page.evaluate(({ name, response, broken }) => {
        window.testTip.hideTermTip();
        const a = document.querySelector('#term');
        a.dataset.wikiTarget = name;
        a.dataset.wikiResolved = JSON.stringify({ term: { name, definition: broken ? 'broken fallback' : 'fallback ' + name } });
        window.fetch = async (url) => {
          if (String(url).includes('/lookup?')) return response === null ? new Response('', { status: 404 }) : new Response(typeof response === 'string' ? response : JSON.stringify(response));
          return new Response('{}', { status: 404 });
        };
        window.testTip.termTapExpand(a, null);
      }, { name, response, broken: process.env.TERM_NEGATIVE_CONTROL === name });
      await page.waitForFunction(() => [...document.querySelectorAll('.nf-term-tip .ts')].some((el) => el.textContent.startsWith('fallback ') || el.textContent === 'broken fallback'));
      const actual = await page.locator('.nf-term-tip:visible .ts').textContent();
      assert.equal(actual, 'fallback ' + name, `fallback boundary: ${name}`);
    }
    console.log('PASS: unavailable, ambiguity, malformed JSON and 404 fall back to resolved payload');
    await page.evaluate(() => {
      window.testTip.hideTermTip();
      const a = document.querySelector('#term');
      a.dataset.wikiTarget = 'cache-test';
      delete a.dataset.wikiResolved;
      a.dataset.wikiProject = 'project-a';
      window.cacheLookups = 0;
      window.fetch = async (url) => {
        if (String(url).includes('/lookup?')) {
          window.cacheLookups++;
          return new Response(JSON.stringify({ available: true, results: [{ name: 'cache-test', definition: 'cache ' + window.cacheLookups, doc: {} }] }));
        }
        return new Response('{}', { status: 404 });
      };
      window.testTip.termTapExpand(a, null);
    });
    await page.waitForFunction(() => [...document.querySelectorAll('.nf-term-tip .ts')].some((el) => el.textContent === 'cache 1'));
    await page.evaluate(() => { window.testTip.hideTermTip(); window.testTip.termTapExpand(document.querySelector('#term'), null); });
    await page.waitForFunction(() => [...document.querySelectorAll('.nf-term-tip .ts')].some((el) => el.textContent === 'cache 1'));
    assert.equal(await page.evaluate(() => window.cacheLookups), 1);
    await capture(page, '390x844-positive-cache-reuse');
    await page.evaluate(() => {
      window.testTip.hideTermTip();
      window.fetch = async () => { throw new TypeError('service offline'); };
      window.testTip.termTapExpand(document.querySelector('#term'), null);
    });
    await page.waitForFunction(() => [...document.querySelectorAll('.nf-term-tip .ts')].some((el) => el.textContent === 'cache 1'));
    assert.equal(await page.evaluate(() => window.cacheLookups), 1);
    await capture(page, '390x844-cached-value-service-offline');
    await page.evaluate(() => {
      window.fetch = async (url) => {
        if (String(url).includes('/lookup?')) {
          window.cacheLookups++;
          return new Response(JSON.stringify({ available: true, results: [{ name: 'cache-test', definition: 'cache ' + window.cacheLookups, doc: {} }] }));
        }
        return new Response('{}', { status: 404 });
      };
    });
    await page.evaluate(() => { window.testTip.hideTermTip(); document.querySelector('#term').dataset.wikiProject = 'project-b'; window.testTip.termTapExpand(document.querySelector('#term'), null); });
    await page.waitForFunction(() => [...document.querySelectorAll('.nf-term-tip .ts')].some((el) => el.textContent === 'cache 2'));
    await page.evaluate(() => { window.testTip.hideTermTip(); window.advanceClock(); window.testTip.termTapExpand(document.querySelector('#term'), null); });
    await page.waitForFunction(() => [...document.querySelectorAll('.nf-term-tip .ts')].some((el) => el.textContent === 'cache 3'));
    console.log('PASS: positive cache reuse, project isolation and five-minute expiry');
    for (const width of [1440, 768, 390, 375]) {
      const screen = await browser.newPage({ viewport: { width, height: width === 1440 ? 900 : 812 } });
      await screen.route('http://fixture.test/**', async (route) => {
        const url = new URL(route.request().url());
        if (url.pathname === '/') return route.fulfill({ contentType: 'text/html', body: '<link rel="stylesheet" href="/split.css"><style>body{margin:0}#term{position:absolute;top:200px;left:30px}.nf-term-tip{background:white;padding:12px;border:1px solid #555;box-sizing:border-box}</style><a id="term" href="#">term</a>' });
        if (url.pathname === '/split.css') return route.fulfill({ contentType: 'text/css', body: readFileSync('src/main/resources/web/css/split.css', 'utf8') });
        if (url.pathname === '/termTip.js') return route.fulfill({ contentType: 'text/javascript', body: readFileSync('src/main/resources/web/js/termTip.js', 'utf8') });
        if (url.pathname === '/flowHelpers.js') return route.fulfill({ contentType: 'text/javascript', body: 'export const authHeaders = () => ({})' });
        if (url.pathname === '/i18n.js') return route.fulfill({ contentType: 'text/javascript', body: 'export const t = (key) => key' });
        if (url.pathname === '/api/wiki/terms/stats') return route.fulfill({ json: { last_rebuild: 'one' } });
        if (url.pathname === '/api/wiki/terms/lookup') return route.fulfill({ json: { available: true, results: [{ name: 'term', definition: 'LongEnglishText'.repeat(100), doc: {} }] } });
        throw new Error('Unexpected request: ' + url.pathname);
      });
      await screen.goto('http://fixture.test/');
      await screen.evaluate(async () => {
        const tip = await import('/termTip.js');
        const a = document.querySelector('#term');
        tip.attachTermFace(a, null, { fallbackName: 'term' });
        a.addEventListener('click', (event) => { event.preventDefault(); tip.termTapExpand(a, null); });
      });
      await screen.locator('#term').focus();
      await screen.keyboard.press('Enter');
      await screen.waitForFunction(() => document.querySelector('.nf-term-tip .ts').textContent.startsWith('LongEnglishText'));
      const geometry = await screen.evaluate(() => {
        const tip = document.querySelector('.nf-term-tip');
        const link = document.querySelector('#term');
        const body = tip.querySelector('.ts');
        const close = tip.querySelector('.term-tip-close');
        const r = tip.getBoundingClientRect();
        const a = link.getBoundingClientRect();
        const c = close.getBoundingClientRect();
        return { x: r.x, y: r.y, right: r.right, bottom: r.bottom, overlap: r.left < a.right && r.right > a.left && r.top < a.bottom && r.bottom > a.top, closeInside: c.left >= r.left - 1 && c.right <= r.right + 1 && c.top >= r.top - 1 && c.bottom <= r.bottom + 1, closeWidth: c.width, closeHeight: c.height, closeName: close.getAttribute('aria-label'), dialogName: tip.getAttribute('aria-label'), horizontal: body.scrollWidth <= body.clientWidth, role: tip.getAttribute('role'), live: body.getAttribute('aria-live'), focus: document.activeElement === close };
      });
      assert(geometry.x >= 16 && geometry.y >= 16 && geometry.right <= width - 16 && geometry.bottom <= (width === 1440 ? 900 : 812) - 16, JSON.stringify(geometry));
      assert(!geometry.overlap && geometry.closeInside && geometry.horizontal, JSON.stringify(geometry));
      assert(geometry.closeWidth >= 44 && geometry.closeHeight >= 44, JSON.stringify(geometry));
      assert(geometry.closeName && geometry.dialogName, JSON.stringify(geometry));
      assert.equal(geometry.role, 'dialog');
      assert.equal(geometry.live, 'polite');
      assert.equal(geometry.focus, true);
      await screen.keyboard.press('Escape');
      assert.equal(await screen.evaluate(() => document.activeElement.id), 'term');
      await screen.close();
    }
    console.log('PASS: four viewport geometry, keyboard activation, ARIA and escape focus');
    const sourcePage = await browser.newPage();
    await sourcePage.route('http://fixture.test/**', async (route) => {
      const url = new URL(route.request().url());
      if (url.pathname === '/') return route.fulfill({ contentType: 'text/html', body: '<a id="source-term" href="#">source</a>' });
      if (url.pathname === '/termTip.js') return route.fulfill({ contentType: 'text/javascript', body: readFileSync('src/main/resources/web/js/termTip.js', 'utf8') });
      if (url.pathname === '/flowHelpers.js') return route.fulfill({ contentType: 'text/javascript', body: 'export const authHeaders = () => ({})' });
      if (url.pathname === '/i18n.js') return route.fulfill({ contentType: 'text/javascript', body: 'export const t = (key) => key' });
      if (url.pathname === '/api/wiki/terms/stats') return route.fulfill({ json: { last_rebuild: 'one' } });
      if (url.pathname === '/api/wiki/terms/lookup') return route.fulfill({ json: { available: true, results: [{ name: 'source', definition: 'definition', doc: { rel: 'vault/source.md', abs_path: '/private/secret.md' } }] } });
      throw new Error('Unexpected request: ' + url.pathname);
    });
    await sourcePage.goto('http://fixture.test/');
    await sourcePage.evaluate(async () => {
      const tip = await import('/termTip.js');
      const a = document.querySelector('#source-term');
      a.dataset.wikiResolved = JSON.stringify({ term: { name: 'source', rel: 'vault/source.md' } });
      tip.attachTermFace(a, null, { term: true });
      tip.setTermTipNav((link, host) => { window.navReceived = link.id === 'source-term' && host === document.body; });
      tip.termTapExpand(a, document.body);
    });
    await sourcePage.waitForFunction(() => document.querySelector('.nf-term-tip .ts').textContent === 'definition');
    assert.equal(await sourcePage.locator('.nf-term-tip .td').textContent(), 'vault/source.md');
    assert.equal((await sourcePage.locator('.nf-term-tip').textContent()).includes('/private/secret.md'), false);
    await sourcePage.locator('.term-tip-source').click();
    assert.equal(await sourcePage.evaluate(() => window.navReceived), true);
    await sourcePage.close();
    console.log('PASS: source delegates navigation and hides absolute path');
    const missingSourcePage = await browser.newPage({ viewport: { width: 390, height: 844 } });
    await missingSourcePage.route('http://fixture.test/**', async (route) => {
      const url = new URL(route.request().url());
      if (url.pathname === '/') return route.fulfill({ contentType: 'text/html', body: '<link rel="stylesheet" href="/split.css"><a id="missing-source" href="#">missing-source</a>' });
      if (url.pathname === '/split.css') return route.fulfill({ contentType: 'text/css', body: readFileSync('src/main/resources/web/css/split.css', 'utf8') });
      if (url.pathname === '/termTip.js') return route.fulfill({ contentType: 'text/javascript', body: readFileSync('src/main/resources/web/js/termTip.js', 'utf8') });
      if (url.pathname === '/flowHelpers.js') return route.fulfill({ contentType: 'text/javascript', body: 'export const authHeaders = () => ({})' });
      if (url.pathname === '/i18n.js') return route.fulfill({ contentType: 'text/javascript', body: 'export const t = (key) => key' });
      if (url.pathname === '/api/wiki/terms/stats') return route.fulfill({ json: { last_rebuild: 'one' } });
      if (url.pathname === '/api/wiki/terms/lookup') return route.fulfill({ json: { available: true, results: [{ name: 'missing-source', definition: 'definition', doc: process.env.TERM_NEGATIVE_CONTROL === 'missing-source' ? { rel: 'unexpected.md' } : {} }] } });
      throw new Error('Unexpected request: ' + url.pathname);
    });
    await missingSourcePage.goto('http://fixture.test/');
    await missingSourcePage.evaluate(async () => {
      const tip = await import('/termTip.js');
      const a = document.querySelector('#missing-source');
      tip.attachTermFace(a, null, { term: true });
      tip.setTermTipNav(() => { window.missingSourceNavigated = true; });
      a.dataset.wikiTarget = 'missing-source';
      tip.termTapExpand(a, document.body);
    });
    await missingSourcePage.waitForFunction(() => document.querySelector('.nf-term-tip .ts').textContent === 'definition');
    await capture(missingSourcePage, '390x844-missing-source');
    assert.equal(await missingSourcePage.locator('.nf-term-tip .td').textContent(), '', 'missing source has no source text');
    assert.equal(await missingSourcePage.locator('.nf-term-tip .term-tip-source').isHidden(), true, 'unresolved source has no navigation action');
    assert.equal(await missingSourcePage.evaluate(() => !!window.missingSourceNavigated), false, 'unresolved source never navigated');
    await missingSourcePage.close();
    console.log('PASS: unresolved source lookup shows no source or navigation action');
    await page.evaluate(() => {
      window.testTip.hideTermTip();
      const a = document.querySelector('#term');
      a.dataset.wikiTarget = 'retry-miss';
      delete a.dataset.wikiResolved;
      window.missCalls = 0;
      window.fetch = async (url) => {
        if (String(url).includes('/lookup?')) window.missCalls++;
        return new Response('{}', { status: 404 });
      };
      window.testTip.termTapExpand(a, null);
    });
    await page.waitForFunction(() => [...document.querySelectorAll('.nf-term-tip .term-tip-retry')].some((el) => !el.hidden));
    await page.locator('.nf-term-tip .term-tip-retry:not([hidden])').click();
    await page.waitForFunction(() => window.missCalls === 2);
    console.log('PASS: missing results are not cached');
    await page.evaluate(() => {
      window.testTip.hideTermTip();
      const a = document.querySelector('#term');
      a.dataset.wikiTarget = 'offline';
      a.dataset.wikiResolved = JSON.stringify({ term: { name: 'offline', definition: 'offline fallback' } });
      window.fetch = async (url) => {
        if (String(url).includes('/lookup?')) throw new TypeError('network unavailable');
        return new Response('{}', { status: 404 });
      };
      window.testTip.termTapExpand(a, null);
    });
    await page.waitForFunction(() => [...document.querySelectorAll('.nf-term-tip .ts')].some((el) => el.textContent === 'offline fallback'));
    await capture(page, '390x844-network-error-resolved-payload');
    console.log('PASS: network failure falls back to resolved payload');
  } finally { await browser.close(); }
})().catch((error) => { console.error(error); process.exitCode = 1; });

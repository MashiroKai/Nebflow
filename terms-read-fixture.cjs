const { chromium } = require('playwright');
const { readFileSync } = require('node:fs');
const assert = require('node:assert/strict');

// argv 通道（env 前缀在权限面不可用）：--case=<negative-control> 等价
// TERM_NEGATIVE_CONTROL；--shots=<dir> 等价 TERM_SCREENSHOTS（截图重生成）。
for (const arg of process.argv.slice(2)) {
  if (arg.startsWith('--case=')) process.env.TERM_NEGATIVE_CONTROL = arg.slice(7);
  if (arg.startsWith('--shots=')) process.env.TERM_SCREENSHOTS = arg.slice(8);
}

(async () => {
  const browser = await chromium.launch({ headless: true });
  try {
    const capture = async (page, state) => {
      if (process.env.TERM_SCREENSHOTS) await page.screenshot({ path: process.env.TERM_SCREENSHOTS + '/' + state + '.png' });
    };
    const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
    await page.route('http://fixture.test/**', async (route) => {
      const url = new URL(route.request().url());
      if (url.pathname === '/') return route.fulfill({ contentType: 'text/html', body: '<meta charset="utf-8"><link rel="stylesheet" href="/split.css"><a id="term" href="#">Alias</a>' });
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
    await page.evaluate(async (registryDown) => {
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
          if (registryDown) return new Response('{}', { status: 404 });
          return new Response(JSON.stringify({ resolved: true, term: { name: 'missing', definition: 'legacy' } }), { status: 200 });
        }
        return new Response('{}', { status: 200 });
      };
      tip.termTapExpand(a, null);
    }, process.env.TERM_NEGATIVE_CONTROL === 'registry-down');
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
    await page.evaluate((neverRecovers) => {
      window.retryCalls = 0;
      window.fetch = async (url) => {
        if (String(url).includes('/lookup?')) {
          window.retryCalls++;
          if (window.retryCalls === 1 || neverRecovers) return new Response('{}', { status: 404 });
          return new Response(JSON.stringify({ available: true, results: [{ name: 'unavailable', definition: 'recovered', doc: {} }] }));
        }
        return new Response('{}', { status: 404 });
      };
      document.querySelector('.term-tip-retry:not([hidden])').click();
      window.retryLoading = [...document.querySelectorAll('.nf-term-tip .ts')].some((el) => el.textContent === 'termPreview.loading');
    }, process.env.TERM_NEGATIVE_CONTROL === 'retry-never-recovers');
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
    await capture(page, '390x844-unexpired-cache-hit-while-offline');
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
    // stats 重建失效：缓存未过期（仅过 60s）但注册表版本翻转 ⇒ 下一次展开必须
    // 重新打 lookup（termIndexCache.clear 的红证锚点）。独立模块实例隔离主流程状态。
    await page.evaluate(async () => {
      const tip = await import('/termTip.js?stats-rebuild');
      window.statsTip = tip;
      const a = document.querySelector('#term');
      a.dataset.wikiTarget = 'stats-rebuild';
      delete a.dataset.wikiResolved;
      let now = 2000000;
      Date.now = () => now;
      let lookups = 0;
      let statsCalls = 0;
      window.fetch = async (url) => {
        if (String(url).includes('/stats')) {
          statsCalls++;
          return new Response(JSON.stringify({ last_rebuild: now < 2000060 ? 'v1' : 'v2' }));
        }
        if (String(url).includes('/lookup?')) {
          lookups++;
          return new Response(JSON.stringify({ available: true, results: [{ name: 'stats-rebuild', definition: 'stats ' + lookups, doc: {} }] }));
        }
        return new Response('{}', { status: 404 });
      };
      window.statsLookups = () => lookups;
      window.statsCalls = () => statsCalls;
      window.statsAdvance = () => { now += 60000; };
      window.statsTip.termTapExpand(a, null);
    });
    await page.waitForFunction(() => window.statsLookups() === 1 && [...document.querySelectorAll('.nf-term-tip .ts')].some((el) => el.textContent === 'stats 1'));
    await page.evaluate(() => { window.statsTip.hideTermTip(); window.statsAdvance(); window.statsTip.termTapExpand(document.querySelector('#term'), null); });
    await page.waitForFunction(() => window.statsCalls() === 2);
    await page.evaluate(() => { window.statsTip.hideTermTip(); window.statsTip.termTapExpand(document.querySelector('#term'), null); });
    await page.waitForFunction(() => window.statsLookups() === 2 && [...document.querySelectorAll('.nf-term-tip .ts')].some((el) => el.textContent === 'stats 2'));
    await page.evaluate(() => window.statsTip.hideTermTip());
    console.log('PASS: registry rebuild invalidates unexpired cache');
    for (const width of [1440, 768, 390, 375]) {
      const screen = await browser.newPage({ viewport: { width, height: width === 1440 ? 900 : 812 } });
      await screen.route('http://fixture.test/**', async (route) => {
        const url = new URL(route.request().url());
        if (url.pathname === '/') return route.fulfill({ contentType: 'text/html', body: '<meta charset="utf-8"><link rel="stylesheet" href="/split.css"><style>body{margin:0}#term{position:absolute;top:200px;left:30px}.nf-term-tip{background:white;padding:12px;border:1px solid #555;box-sizing:border-box}</style><a id="term" href="#">term</a>' });
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
    // 375/390 全交互态几何+无障碍矩阵：loading / normal / empty definition /
    // ambiguous（歧义退回载荷）/ unavailable（含重试键）/ 缓存未过期命中（离线）/
    // 网络异常回退。每态过同一组几何断言，态间 Escape 验证焦点归还。
    for (const width of [390, 375]) {
      const height = width === 390 ? 844 : 812;
      const statePage = await browser.newPage({ viewport: { width, height } });
      await statePage.route('http://fixture.test/**', async (route) => {
        const url = new URL(route.request().url());
        if (url.pathname === '/') return route.fulfill({ contentType: 'text/html', body: '<meta charset="utf-8"><link rel="stylesheet" href="/split.css"><style>body{margin:0}#term{position:absolute;top:200px;left:30px}.nf-term-tip{background:white;padding:12px;border:1px solid #555;box-sizing:border-box}</style><a id="term" href="#">term</a>' });
        if (url.pathname === '/split.css') return route.fulfill({ contentType: 'text/css', body: readFileSync('src/main/resources/web/css/split.css', 'utf8') });
        if (url.pathname === '/termTip.js') return route.fulfill({ contentType: 'text/javascript', body: readFileSync('src/main/resources/web/js/termTip.js', 'utf8') });
        if (url.pathname === '/flowHelpers.js') return route.fulfill({ contentType: 'text/javascript', body: 'export const authHeaders = () => ({})' });
        if (url.pathname === '/i18n.js') return route.fulfill({ contentType: 'text/javascript', body: 'export const t = (key) => key' });
        if (url.pathname === '/api/wiki/terms/stats') return route.fulfill({ json: { last_rebuild: 'one' } });
        return route.fulfill({ json: {}, status: 404 });
      });
      await statePage.goto('http://fixture.test/');
      await statePage.evaluate(async () => {
        window.stateTip = await import('/termTip.js');
        const a = document.querySelector('#term');
        window.stateTip.attachTermFace(a, null, { term: true });
        a.addEventListener('click', (event) => { event.preventDefault(); window.stateTip.termTapExpand(a, null); });
      });
      const states = [
        ['loading', "t.style.display === 'block' && s.textContent === 'termPreview.loading'"],
        ['normal', "t.style.display === 'block' && s.textContent.startsWith('StateGeometryText')"],
        ['empty-definition', "t.style.display === 'block' && document.querySelector('.nf-term-tip .tt').textContent === 'state-empty'"],
        ['ambiguous', "t.style.display === 'block' && s.textContent === 'resolved fallback'"],
        ['unavailable', "t.style.display === 'block' && s.textContent === 'termPreview.unavailable' && !document.querySelector('.nf-term-tip .term-tip-retry').hidden"],
        ['cache-hit-offline', "t.style.display === 'block' && s.textContent === 'cached offline value'"],
        ['network-fallback', "t.style.display === 'block' && s.textContent === 'resolved payload fallback'"],
      ];
      for (const [state, condition] of states) {
        await statePage.evaluate((state) => {
          const tip = window.stateTip;
          tip.hideTermTip();
          const a = document.querySelector('#term');
          const resolved = (name, definition) => new Response(JSON.stringify({ available: true, results: [{ name, definition, doc: {} }] }), { status: 200 });
          delete a.dataset.wikiResolved;
          if (state === 'loading') {
            a.dataset.wikiTarget = 'state-loading';
            window.fetch = () => new Promise(() => {});
          } else if (state === 'normal') {
            a.dataset.wikiTarget = 'state-normal';
            window.fetch = async (url) => String(url).includes('/lookup?') ? resolved('state-normal', 'StateGeometryText'.repeat(60)) : new Response('{}', { status: 404 });
          } else if (state === 'empty-definition') {
            a.dataset.wikiTarget = 'state-empty';
            window.fetch = async (url) => String(url).includes('/lookup?') ? resolved('state-empty', '') : new Response('{}', { status: 404 });
          } else if (state === 'ambiguous') {
            a.dataset.wikiTarget = 'state-ambiguous';
            a.dataset.wikiResolved = JSON.stringify({ term: { name: 'state-ambiguous', definition: 'resolved fallback' } });
            window.fetch = async (url) => String(url).includes('/lookup?')
              ? new Response(JSON.stringify({ available: true, results: [
                { name: 'state-ambiguous', definition: 'candidate a', doc: {} },
                { name: 'state-ambiguous', definition: 'candidate b', doc: {} },
              ] }), { status: 200 })
              : new Response('{}', { status: 404 });
          } else if (state === 'unavailable') {
            a.dataset.wikiTarget = 'state-unavailable';
            window.fetch = async () => new Response('{}', { status: 404 });
          } else if (state === 'cache-hit-offline') {
            a.dataset.wikiTarget = 'state-cache-hit';
            window.fetch = async (url) => String(url).includes('/lookup?') ? resolved('state-cache-hit', 'cached offline value') : new Response('{}', { status: 404 });
          } else if (state === 'network-fallback') {
            a.dataset.wikiTarget = 'state-netfb';
            a.dataset.wikiResolved = JSON.stringify({ term: { name: 'state-netfb', definition: 'resolved payload fallback' } });
            window.fetch = async (url) => {
              if (String(url).includes('/lookup?')) throw new TypeError('offline');
              return new Response('{}', { status: 404 });
            };
          }
          tip.termTapExpand(a, null);
        }, state);
        if (state === 'cache-hit-offline') {
          await statePage.waitForFunction(() => {
            const t = document.querySelector('.nf-term-tip');
            const s = t && t.querySelector('.ts');
            return t && t.style.display === 'block' && s && s.textContent === 'cached offline value';
          });
          await statePage.evaluate(() => {
            window.stateTip.hideTermTip();
            window.fetch = async () => { throw new TypeError('service offline'); };
            window.stateTip.termTapExpand(document.querySelector('#term'), null);
          });
        }
        await statePage.waitForFunction(new Function(`
          const t = document.querySelector('.nf-term-tip');
          const s = t && t.querySelector('.ts');
          return t && s && (${condition});
        `));
        const geometry = await statePage.evaluate(() => {
          const tip = document.querySelector('.nf-term-tip');
          const link = document.querySelector('#term');
          const body = tip.querySelector('.ts');
          const close = tip.querySelector('.term-tip-close');
          const retry = tip.querySelector('.term-tip-retry');
          const r = tip.getBoundingClientRect();
          const a = link.getBoundingClientRect();
          const c = close.getBoundingClientRect();
          const b = retry.getBoundingClientRect();
          return {
            x: r.x, y: r.y, right: r.right, bottom: r.bottom,
            vw: window.innerWidth, vh: window.innerHeight,
            overlap: r.left < a.right && r.right > a.left && r.top < a.bottom && r.bottom > a.top,
            closeInside: c.left >= r.left - 1 && c.right <= r.right + 1 && c.top >= r.top - 1 && c.bottom <= r.bottom + 1,
            closeWidth: c.width, closeHeight: c.height,
            closeName: close.getAttribute('aria-label'),
            dialogName: tip.getAttribute('aria-label'),
            horizontal: body.scrollWidth <= body.clientWidth,
            role: tip.getAttribute('role'),
            live: body.getAttribute('aria-live'),
            focus: document.activeElement === close,
            retryHidden: retry.hidden, retryWidth: b.width, retryHeight: b.height,
            retryInside: b.left >= r.left - 1 && b.right <= r.right + 1 && b.top >= r.top - 1 && b.bottom <= r.bottom + 1,
          };
        });
        assert(geometry.x >= 16 && geometry.y >= 16 && geometry.right <= geometry.vw - 16 && geometry.bottom <= geometry.vh - 16, `${state}@${width}: out of bounds ${JSON.stringify(geometry)}`);
        assert(!geometry.overlap && geometry.closeInside && geometry.horizontal, `${state}@${width}: clipping or overlap ${JSON.stringify(geometry)}`);
        assert(geometry.closeWidth >= 44 && geometry.closeHeight >= 44 && geometry.closeName && geometry.dialogName, `${state}@${width}: target size or accessible name ${JSON.stringify(geometry)}`);
        assert.equal(geometry.role, 'dialog', `${state}@${width}: role`);
        assert.equal(geometry.live, 'polite', `${state}@${width}: aria-live`);
        assert.equal(geometry.focus, true, `${state}@${width}: close focused after open`);
        if (state === 'unavailable') {
          assert(!geometry.retryHidden && geometry.retryWidth >= 44 && geometry.retryHeight >= 44 && geometry.retryInside, `${state}@${width}: retry target ${JSON.stringify(geometry)}`);
          await statePage.keyboard.press('Tab');
          assert.equal(await statePage.evaluate(() => document.activeElement.classList.contains('term-tip-retry')), true, `${state}@${width}: tab reaches retry`);
        }
        await capture(statePage, `${width}x${height}-state-${state}`);
        await statePage.keyboard.press('Escape');
        assert.equal(await statePage.evaluate(() => document.activeElement.id), 'term', `${state}@${width}: escape returns focus`);
      }
      await statePage.close();
    }
    console.log('PASS: seven interaction states keep geometry and accessibility at 390 and 375');
    const sourcePage = await browser.newPage();
    await sourcePage.route('http://fixture.test/**', async (route) => {
      const url = new URL(route.request().url());
      if (url.pathname === '/') return route.fulfill({ contentType: 'text/html', body: '<meta charset="utf-8"><a id="source-term" href="#">source</a>' });
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
      if (url.pathname === '/') return route.fulfill({ contentType: 'text/html', body: '<meta charset="utf-8"><link rel="stylesheet" href="/split.css"><a id="missing-source" href="#">missing-source</a>' });
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
    await page.evaluate((brokenResolved) => {
      window.testTip.hideTermTip();
      const a = document.querySelector('#term');
      a.dataset.wikiTarget = 'offline';
      a.dataset.wikiResolved = brokenResolved ? 'not-json' : JSON.stringify({ term: { name: 'offline', definition: 'offline fallback' } });
      window.fetch = async (url) => {
        if (String(url).includes('/lookup?')) throw new TypeError('network unavailable');
        return new Response('{}', { status: 404 });
      };
      window.testTip.termTapExpand(a, null);
    }, process.env.TERM_NEGATIVE_CONTROL === 'net-broken-resolved');
    await page.waitForFunction(() => [...document.querySelectorAll('.nf-term-tip .ts')].some((el) => el.textContent === 'offline fallback'));
    await capture(page, '390x844-network-error-resolved-payload');
    console.log('PASS: network failure falls back to resolved payload');
    await page.evaluate(() => {
      window.testTip.hideTermTip();
      window.offlineLookups = 0;
      window.fetch = async (url) => {
        if (String(url).includes('/lookup?')) {
          window.offlineLookups++;
          return new Response(JSON.stringify({ available: true, results: [{ name: 'offline', definition: 'recovered' }] }), { status: 200 });
        }
        return new Response('{}', { status: 404 });
      };
      window.testTip.termTapExpand(document.querySelector('#term'), null);
    });
    await page.waitForFunction(() => [...document.querySelectorAll('.nf-term-tip .ts')].some((el) => el.textContent === 'recovered'));
    assert.equal(await page.evaluate(() => window.offlineLookups), 1, 'failed lookup must not be cached');
    console.log('PASS: failed lookup is not cached and recovery refetches');
    await page.evaluate((brokenResolved) => {
      window.testTip.hideTermTip();
      const a = document.querySelector('#term');
      a.dataset.wikiTarget = 'timed-out';
      a.dataset.wikiResolved = brokenResolved ? 'not-json' : JSON.stringify({ term: { name: 'timed-out', definition: 'timeout fallback' } });
      window.fetch = async (url, options) => {
        if (String(url).includes('/lookup?')) return new Promise((resolve, reject) => {
          options.signal.addEventListener('abort', () => reject(options.signal.reason), { once: true });
        });
        return new Response('{}', { status: 404 });
      };
      window.testTip.termTapExpand(a, null);
    }, process.env.TERM_NEGATIVE_CONTROL === 'timeout-broken-resolved');
    await page.waitForFunction(() => [...document.querySelectorAll('.nf-term-tip .ts')].some((el) => el.textContent === 'timeout fallback'), null, { timeout: 10000 });
    assert.equal(await page.locator('.nf-term-tip:visible .ts').textContent(), 'timeout fallback');
    console.log('PASS: lookup timeout falls back to resolved payload');
  } finally { await browser.close(); }
})().catch((error) => { console.error(error); process.exitCode = 1; });

// terms-read-neg-controls.cjs — 聚焦负控 harness（#2935 阶段二收尾）。
// 每个用例只搭一个场景、只断言一条保护，供三种运行面复用：
//   ① 基点树（git show 9c70f2781 的 termTip.js/split.css 拷进 /tmp 树）——
//      同一断言在基点红 = 分支新特性缺失的直证（a 组）；
//   ② 去保护拷贝（/tmp 树内 termTip.js 单点摘除一条保护）——
//      红 = 该保护是断言成立的必要条件（a 组）；
//   ③ 本分支无注入 ——绿（对照）。
// b 组守卫用例（net/timeout/registry-fallback）额外要求基点绿：基点行为是
// 「直读 resolved 载荷/直接打旧注册表」，内容断言两代同绿，回退不回归。
// 用法：node terms-read-neg-controls.cjs --case=<name> [--tree=<path>]
//   --tree 默认 cwd；树内须有 src/main/resources/web/js/termTip.js 与
//   css/split.css（harness 以 chdir 相对路径读取，模拟主夹具的取数方式）。
// 退出码：0 = 断言绿；1 = 红（负控的预期）；2 = 未知用例。
const { chromium } = require('playwright');
const { readFileSync } = require('node:fs');
const assert = require('node:assert/strict');

const argv = process.argv.slice(2);
const caseName = (argv.find((a) => a.startsWith('--case=')) || '').slice(7);
const tree = (argv.find((a) => a.startsWith('--tree=')) || '').slice(7) || process.cwd();
process.chdir(tree);
const TERM_JS = readFileSync('src/main/resources/web/js/termTip.js', 'utf8');
const TERM_CSS = readFileSync('src/main/resources/web/css/split.css', 'utf8');
const WAIT = { timeout: 3000 };

async function openCasePage(browser, opts = {}) {
  const page = await browser.newPage({ viewport: { width: opts.width || 390, height: opts.height || 844 } });
  const extraCss = opts.css ? `<style>${opts.css}</style>` : '';
  const html = `<meta charset="utf-8"><link rel="stylesheet" href="/split.css"><style>body{margin:0}#term{position:absolute;top:200px;left:30px}.nf-term-tip{background:white;padding:12px;border:1px solid #555;box-sizing:border-box}</style>${extraCss}<a id="term" href="#">term</a>`;
  await page.route('http://fixture.test/**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname === '/') return route.fulfill({ contentType: 'text/html', body: html });
    if (url.pathname === '/split.css') return route.fulfill({ contentType: 'text/css', body: TERM_CSS });
    if (url.pathname === '/termTip.js') return route.fulfill({ contentType: 'text/javascript', body: TERM_JS });
    if (url.pathname === '/flowHelpers.js') return route.fulfill({ contentType: 'text/javascript', body: 'export const authHeaders = () => ({})' });
    if (url.pathname === '/i18n.js') return route.fulfill({ contentType: 'text/javascript', body: 'export const t = (key) => key' });
    if (url.pathname === '/api/wiki/terms/stats') return route.fulfill({ json: { last_rebuild: 'one' } });
    return route.fulfill({ json: {}, status: 404 });
  });
  await page.goto('http://fixture.test/');
  await page.evaluate(async () => {
    window.tip = await import('/termTip.js');
    const a = document.querySelector('#term');
    window.tip.attachTermFace(a, null, { term: true });
    a.addEventListener('click', (event) => { event.preventDefault(); window.tip.termTapExpand(a, null); });
  });
  return page;
}

function tipText(page, timeout) {
  return page.waitForFunction(() => {
    const s = document.querySelector('.nf-term-tip .ts');
    return s && document.querySelector('.nf-term-tip').style.display === 'block' && s.textContent;
  }, null, timeout || WAIT).then(() => page.evaluate(() => document.querySelector('.nf-term-tip .ts').textContent));
}

/** 几何/无障碍检查束：与主夹具 4 视口循环同一组断言，单页 390x844。
 *  opts.css 注入带 !important 的破坏样式；opts.strip 摘除 aria 属性。 */
async function geometryCase(browser, opts = {}) {
  const page = await openCasePage(browser, { css: opts.css || '' });
  await page.evaluate(() => {
    const a = document.querySelector('#term');
    a.dataset.wikiTarget = 'term';
    window.fetch = async (url) => String(url).includes('/lookup?')
      ? new Response(JSON.stringify({ available: true, results: [{ name: 'term', definition: 'LongEnglishText'.repeat(100), doc: {} }] }), { status: 200 })
      : new Response('{}', { status: 404 });
  });
  await page.locator('#term').focus();
  await page.keyboard.press('Enter');
  await page.waitForFunction(() => document.querySelector('.nf-term-tip .ts').textContent.startsWith('LongEnglishText'), null, WAIT);
  if (opts.strip) {
    await page.evaluate((strip) => {
      if (strip.includes('dialog-name')) document.querySelector('.nf-term-tip').removeAttribute('aria-label');
      if (strip.includes('close-name')) document.querySelector('.nf-term-tip .term-tip-close').removeAttribute('aria-label');
      if (strip.includes('live')) document.querySelector('.nf-term-tip .ts').removeAttribute('aria-live');
      if (strip.includes('role')) document.querySelector('.nf-term-tip').removeAttribute('role');
    }, opts.strip);
  }
  const geometry = await page.evaluate(() => {
    const tip = document.querySelector('.nf-term-tip');
    const link = document.querySelector('#term');
    const body = tip.querySelector('.ts');
    const close = tip.querySelector('.term-tip-close');
    const r = tip.getBoundingClientRect();
    const a = link.getBoundingClientRect();
    const c = close.getBoundingClientRect();
    return {
      x: r.x, y: r.y, right: r.right, bottom: r.bottom, vw: window.innerWidth, vh: window.innerHeight,
      overlap: r.left < a.right && r.right > a.left && r.top < a.bottom && r.bottom > a.top,
      closeInside: c.left >= r.left - 1 && c.right <= r.right + 1 && c.top >= r.top - 1 && c.bottom <= r.bottom + 1,
      closeWidth: c.width, closeHeight: c.height,
      closeName: close.getAttribute('aria-label'), dialogName: tip.getAttribute('aria-label'),
      horizontal: body.scrollWidth <= body.clientWidth,
      role: tip.getAttribute('role'), live: body.getAttribute('aria-live'),
      focus: document.activeElement === close,
    };
  });
  assert(geometry.x >= 16 && geometry.y >= 16 && geometry.right <= geometry.vw - 16 && geometry.bottom <= geometry.vh - 16, 'out of bounds ' + JSON.stringify(geometry));
  assert(!geometry.overlap && geometry.closeInside && geometry.horizontal, 'clipping or overlap ' + JSON.stringify(geometry));
  assert(geometry.closeWidth >= 44 && geometry.closeHeight >= 44 && geometry.closeName && geometry.dialogName, 'target size or accessible name ' + JSON.stringify(geometry));
  assert.equal(geometry.role, 'dialog');
  assert.equal(geometry.live, 'polite');
  assert.equal(geometry.focus, true);
  await page.keyboard.press('Escape');
  assert.equal(await page.evaluate(() => document.activeElement.id), 'term');
}

const cases = {
  // — a 组：分支新特性（基点红 + 去保护拷贝红 + 分支绿）——
  identity: async (browser) => {
    const page = await openCasePage(browser);
    await page.evaluate(() => {
      const a = document.querySelector('#term');
      a.dataset.wikiTarget = 'shared';
      a.dataset.wikiProject = 'one';
      a.dataset.wikiResolved = JSON.stringify({ term: { name: 'shared', rel: 'one.md', definition: 'fallback' } });
      window.fetch = async (url) => {
        if (String(url).includes('/lookup?')) return new Response(JSON.stringify({ available: true, results: [
          { name: 'shared', definition: 'correct project', doc: { project: 'one', rel: 'one.md' } },
          { name: 'shared', definition: 'wrong project', doc: { project: 'two', rel: 'two.md' } },
        ] }), { status: 200 });
        return new Response('{}', { status: 404 });
      };
      window.tip.termTapExpand(a, null);
    });
    assert.equal(await tipText(page), 'correct project');
  },
  'late-write': async (browser) => {
    const page = await openCasePage(browser);
    await page.evaluate(() => {
      const a = document.querySelector('#term');
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
      window.lookupCount = () => lookups;
      window.advanceClock = () => { now += 301000; };
      window.tip.termTapExpand(a, null);
    });
    await page.waitForFunction(() => window.lookupCount() === 1 && document.querySelector('.nf-term-tip .ts').textContent === 'lookup 1', null, WAIT);
    await page.evaluate(() => { window.tip.hideTermTip(); window.advanceClock(); window.tip.termTapExpand(document.querySelector('#term'), null); });
    await page.waitForFunction(() => typeof window.releaseLookup === 'function', null, WAIT);
    await page.evaluate(() => window.releaseLookup());
    await page.waitForFunction(() => document.querySelector('.nf-term-tip .ts').textContent === 'lookup 2', null, WAIT);
    await page.evaluate(() => { window.tip.hideTermTip(); window.tip.termTapExpand(document.querySelector('#term'), null); });
    await page.waitForFunction(() => window.lookupCount() === 3, null, WAIT);
  },
  'ttl-expiry': async (browser) => {
    const page = await openCasePage(browser);
    await page.evaluate(() => {
      const a = document.querySelector('#term');
      a.dataset.wikiTarget = 'ttl';
      let now = 100000;
      Date.now = () => now;
      let lookups = 0;
      window.fetch = async (url) => {
        if (String(url).includes('/lookup?')) {
          lookups++;
          return new Response(JSON.stringify({ available: true, results: [{ name: 'ttl', definition: 'ttl ' + lookups, doc: {} }] }));
        }
        return new Response('{}', { status: 404 });
      };
      window.lookupCount = () => lookups;
      window.advanceClock = () => { now += 301000; };
      window.tip.termTapExpand(a, null);
    });
    await page.waitForFunction(() => document.querySelector('.nf-term-tip .ts').textContent === 'ttl 1', null, WAIT);
    await page.evaluate(() => { window.tip.hideTermTip(); window.advanceClock(); window.tip.termTapExpand(document.querySelector('#term'), null); });
    await page.waitForFunction(() => document.querySelector('.nf-term-tip .ts').textContent === 'ttl 2', null, WAIT);
    assert.equal(await page.evaluate(() => window.lookupCount()), 2);
  },
  'stats-rebuild': async (browser) => {
    const page = await openCasePage(browser);
    await page.evaluate(() => {
      const a = document.querySelector('#term');
      a.dataset.wikiTarget = 'stats';
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
          return new Response(JSON.stringify({ available: true, results: [{ name: 'stats', definition: 'stats ' + lookups, doc: {} }] }));
        }
        return new Response('{}', { status: 404 });
      };
      window.statsLookups = () => lookups;
      window.statsCalls = () => statsCalls;
      window.statsAdvance = () => { now += 60000; };
      window.tip.termTapExpand(a, null);
    });
    await page.waitForFunction(() => window.statsLookups() === 1 && document.querySelector('.nf-term-tip .ts').textContent === 'stats 1', null, WAIT);
    await page.evaluate(() => { window.tip.hideTermTip(); window.statsAdvance(); window.tip.termTapExpand(document.querySelector('#term'), null); });
    await page.waitForFunction(() => window.statsCalls() === 2, null, WAIT);
    await page.evaluate(() => { window.tip.hideTermTip(); window.tip.termTapExpand(document.querySelector('#term'), null); });
    await page.waitForFunction(() => window.statsLookups() === 2 && document.querySelector('.nf-term-tip .ts').textContent === 'stats 2', null, WAIT);
  },
  'miss-no-cache': async (browser) => {
    const page = await openCasePage(browser);
    await page.evaluate(() => {
      const a = document.querySelector('#term');
      a.dataset.wikiTarget = 'miss';
      let lookups = 0;
      window.fetch = async (url) => {
        if (String(url).includes('/lookup?')) lookups++;
        return new Response('{}', { status: 404 });
      };
      window.lookupCount = () => lookups;
      window.tip.termTapExpand(a, null);
    });
    await page.waitForFunction(() => !document.querySelector('.nf-term-tip .term-tip-retry').hidden, null, WAIT);
    await page.evaluate(() => { document.querySelector('.nf-term-tip .term-tip-retry').click(); });
    await page.waitForFunction(() => window.lookupCount() === 2, null, WAIT);
  },
  'fail-no-cache': async (browser) => {
    const page = await openCasePage(browser);
    await page.evaluate(() => {
      const a = document.querySelector('#term');
      a.dataset.wikiTarget = 'fail';
      a.dataset.wikiResolved = JSON.stringify({ term: { name: 'fail', definition: 'offline fallback' } });
      window.lookupCount = 0;
      window.fetch = async (url) => {
        if (String(url).includes('/lookup?')) { window.lookupCount++; throw new TypeError('offline'); }
        return new Response('{}', { status: 404 });
      };
      window.tip.termTapExpand(a, null);
    });
    await page.waitForFunction(() => document.querySelector('.nf-term-tip .ts').textContent === 'offline fallback', null, WAIT);
    await page.evaluate(() => {
      window.tip.hideTermTip();
      window.fetch = async (url) => {
        if (String(url).includes('/lookup?')) {
          window.lookupCount++;
          return new Response(JSON.stringify({ available: true, results: [{ name: 'fail', definition: 'recovered' }] }), { status: 200 });
        }
        return new Response('{}', { status: 404 });
      };
      window.tip.termTapExpand(document.querySelector('#term'), null);
    });
    await page.waitForFunction(() => document.querySelector('.nf-term-tip .ts').textContent === 'recovered', null, WAIT);
    assert.equal(await page.evaluate(() => window.lookupCount), 2);
  },
  'cache-reuse': async (browser) => {
    const page = await openCasePage(browser);
    await page.evaluate(() => {
      const a = document.querySelector('#term');
      a.dataset.wikiTarget = 'cache';
      window.lookupCount = 0;
      window.fetch = async (url) => {
        if (String(url).includes('/lookup?')) {
          window.lookupCount++;
          return new Response(JSON.stringify({ available: true, results: [{ name: 'cache', definition: 'cache ' + window.lookupCount, doc: {} }] }), { status: 200 });
        }
        return new Response('{}', { status: 404 });
      };
      window.tip.termTapExpand(a, null);
    });
    await page.waitForFunction(() => document.querySelector('.nf-term-tip .ts').textContent === 'cache 1', null, WAIT);
    await page.evaluate(() => { window.tip.hideTermTip(); window.tip.termTapExpand(document.querySelector('#term'), null); });
    await page.waitForFunction(() => document.querySelector('.nf-term-tip .ts').textContent === 'cache 1', null, WAIT);
    assert.equal(await page.evaluate(() => window.lookupCount), 1);
  },
  'registry-project-scope': async (browser) => {
    const page = await openCasePage(browser);
    await page.evaluate(() => {
      const a = document.querySelector('#term');
      a.dataset.wikiTarget = 'missing';
      a.dataset.wikiTermFallback = 'missing';
      a.dataset.wikiProject = 'one';
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
      window.tip.termTapExpand(a, null);
    });
    await page.waitForFunction(() => document.querySelector('.nf-term-tip .ts').textContent === 'legacy', null, WAIT);
    assert.match(await page.evaluate(() => window.legacyUrl), /project=one/);
  },
  // — b 组守卫：基点既有行为（基点绿 + 分支绿 = 回退不回归）——
  'net-fallback': async (browser) => {
    const page = await openCasePage(browser);
    await page.evaluate(() => {
      const a = document.querySelector('#term');
      a.dataset.wikiTarget = 'offline';
      a.dataset.wikiResolved = JSON.stringify({ term: { name: 'offline', definition: 'offline fallback' } });
      window.fetch = async (url) => {
        if (String(url).includes('/lookup?')) throw new TypeError('network unavailable');
        return new Response('{}', { status: 404 });
      };
      window.tip.termTapExpand(a, null);
    });
    await page.waitForFunction(() => document.querySelector('.nf-term-tip .ts').textContent === 'offline fallback', null, WAIT);
  },
  'timeout-fallback': async (browser) => {
    const page = await openCasePage(browser);
    await page.evaluate(() => {
      const a = document.querySelector('#term');
      a.dataset.wikiTarget = 'timed-out';
      a.dataset.wikiResolved = JSON.stringify({ term: { name: 'timed-out', definition: 'timeout fallback' } });
      window.fetch = async (url, options) => {
        if (String(url).includes('/lookup?')) return new Promise((resolve, reject) => {
          options.signal.addEventListener('abort', () => reject(options.signal.reason), { once: true });
        });
        return new Response('{}', { status: 404 });
      };
      window.tip.termTapExpand(a, null);
    });
    await page.waitForFunction(() => document.querySelector('.nf-term-tip .ts').textContent === 'timeout fallback', null, { timeout: 12000 });
  },
  'registry-fallback': async (browser) => {
    const page = await openCasePage(browser);
    await page.evaluate(() => {
      const a = document.querySelector('#term');
      a.dataset.wikiTarget = 'missing';
      a.dataset.wikiTermFallback = 'missing';
      a.dataset.wikiProject = 'one';
      delete a.dataset.wikiResolved;
      window.fetch = async (url) => {
        if (String(url).includes('/lookup?')) return new Response('{}', { status: 404 });
        if (String(url).includes('/api/wiki/term?')) return new Response(JSON.stringify({ resolved: true, term: { name: 'missing', definition: 'legacy' } }), { status: 200 });
        return new Response('{}', { status: 200 });
      };
      window.tip.termTapExpand(a, null);
    });
    await page.waitForFunction(() => document.querySelector('.nf-term-tip .ts').textContent === 'legacy', null, WAIT);
  },
  // — 几何/无障碍：分支注入红（geom-*）；纯 geometry 供去保护拷贝（focus/escape）复用 ——

  geometry: (browser) => geometryCase(browser),
  'geom-clip': (browser) => geometryCase(browser, { css: '.nf-term-tip .ts{white-space:nowrap!important}' }),
  'geom-touch': (browser) => geometryCase(browser, { css: '.nf-term-tip .term-tip-close{width:24px!important;height:24px!important;min-width:24px!important;min-height:24px!important}' }),
  'geom-bounds': (browser) => geometryCase(browser, { css: '.nf-term-tip{width:600px!important;max-width:600px!important}' }),
  'geom-overlap': (browser) => geometryCase(browser, { css: '.nf-term-tip{top:200px!important;left:30px!important}' }),
  'geom-name': (browser) => geometryCase(browser, { strip: 'dialog-name close-name' }),
  'geom-live': (browser) => geometryCase(browser, { strip: 'live' }),
  'geom-role': (browser) => geometryCase(browser, { strip: 'role' }),
};

(async () => {
  const fn = cases[caseName];
  if (!fn) {
    console.error('unknown case: ' + (caseName || '(none)') + '; known: ' + Object.keys(cases).join(', '));
    process.exitCode = 2;
    return;
  }
  const browser = await chromium.launch({ headless: true });
  try {
    await fn(browser);
    console.log('GREEN: ' + caseName + ' on ' + tree);
  } catch (error) {
    console.error('RED: ' + caseName + ' on ' + tree + ' — ' + String(error && error.message ? error.message.split('\n')[0] : error).slice(0, 200));
    process.exitCode = 1;
  } finally {
    await browser.close();
  }
})();

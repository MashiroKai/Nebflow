// FMA-SCN-002 / FMA-SCN-005: real consumers; zero server/runtime-data access.
// Scenario: transparent floating bands and independent centered pagination
// Run: node tests/mobile-canvas-toolbar-layout.spec.mjs
// CANVAS_EVIDENCE=/tmp/... CANVAS_ENGINE=webkit CANVAS_CAPTURE_ONLY=1 are optional.
import assert from 'node:assert/strict';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { loadPlaywright, createSession, settle } from '../scripts/lib/visual-harness.mjs';

const out = process.env.CANVAS_EVIDENCE || '/tmp/stand-mobile-canvas-toolbar/after';
const engine = process.env.CANVAS_ENGINE || 'chromium';
const { mod } = loadPlaywright();
const browser = await mod[engine].launch({ headless: true, ...(process.env.CANVAS_BROWSER_PATH ? { executablePath: process.env.CANVAS_BROWSER_PATH } : {}) });
mkdirSync(out, { recursive: true });
const failures = [];
const readings = [];
const check = (condition, label) => { if (!condition) failures.push(label); };
try {
  for (const width of [390, 320]) for (const theme of ['dark', 'light']) {
    const context = await browser.newContext({ hasTouch: true, deviceScaleFactor: 2 });
    const session = await createSession({ newPage: opts => context.newPage(opts) }, { width, height: 844, colorScheme: theme, baseURL: 'https://stand.test/' });
    const { page } = session;
    // Context owns viewport/theme with a real touch-enabled page.
    await page.setViewportSize({ width, height: 844 });
    await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
    await session.boot();
    await page.waitForSelector('body.mobile-layer');
    for (let n = 1; n <= 7; n++) {
      await page.evaluate(async n => {
        const title = n === 1 ? 'todo' : n === 2 ? 'suggestion' : `stand-${n}`;
        const content = n === 1
          ? '<!doctype html><html><body><h1>Canvas first line</h1>' + '<p>Readable canvas content below floating controls.</p>'.repeat(60) + '</body></html>'
          : '# Canvas first line\n\n' + 'Readable canvas content below floating controls.\n\n'.repeat(60);
        window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: {
          id: `file:/tmp/canvas-toolbar-${n}.${n === 1 ? 'html' : 'md'}`, itemType: n === 1 ? 'html' : 'markdown',
          title, content, pinned: true, absPath: `/tmp/canvas-toolbar-${n}.${n === 1 ? 'html' : 'md'}`, size: content.length,
        } }));
        (await import('/js/mobile/navController.js')).dockTo('canvas');
      }, n);
      await page.waitForSelector('.mf-cv-host > .canvas-tab-pane');
      if (![1, 2, 3, 7].includes(n)) continue;
      // Exercise the widest tool group even when many tabs are open.
      await page.evaluate(async () => {
        const nav = await import('/js/mobile/navController.js');
        const canvas = await import('/js/canvas.js');
        const first = nav.docTabs()[0];
        canvas.setActiveTab(first.id);
      });
      await settle(page);
      const label = `${engine}-${width}-${theme}-${n}tabs`;
      const geo = await page.evaluate(() => {
        const q = s => document.querySelector(s);
        const rect = el => { const b = el.getBoundingClientRect(); return { x: b.x, y: b.y, w: b.width, h: b.height, right: b.right, bottom: b.bottom, cx: b.x + b.width / 2 }; };
        const visible = el => el.getClientRects().length && getComputedStyle(el).display !== 'none';
        const controls = [...document.querySelectorAll('.mf-cv-host > .canvas-tab-pane .canvas-source-toggle, .mf-cv-host > .canvas-tab-pane .canvas-ref-select, .mf-cv-host > .canvas-tab-pane .canvas-find-toggle, .ask-fab, .mf-cv-host > .canvas-tab-pane .canvas-zoom-bar')].filter(visible).map(el => ({ name: el.className, ...rect(el), hit: (() => { const b = rect(el); const t = document.elementFromPoint(b.cx, b.y + b.h / 2); return t === el || el.contains(t); })() }));
        const dots = q('.mf-dots');
        const bands = ['.mf-strip', '.mf-cv-toolbar'].map(s => { const el = q(s); const c = getComputedStyle(el); return { name: s, position: c.position, bg: c.backgroundColor, shadow: c.boxShadow, borders: [c.borderTopWidth, c.borderBottomWidth], pseudos: ['::before', '::after'].map(p => { const c = getComputedStyle(el, p); return { content: c.content, bg: c.backgroundColor, image: c.backgroundImage }; }) }; });
        return { width: innerWidth, dots: dots.hidden ? null : rect(dots), controls, bands, page: rect(q('.mf-canvas-page')), host: rect(q('.mf-cv-host')), count: dots.children.length };
      });
      readings.push({ label, ...geo, errors: session.errors, network: session.netErrors });
      await page.screenshot({ path: join(out, `${label}.png`), animations: 'disabled' });
      if (n > 1) {
        check(!!geo.dots && Math.abs(geo.dots.cx - width / 2) < 0.5, `${label}: dots centered`);
        check(geo.count === Math.min(n, 3), `${label}: dot count`);
      }
      const overlaps = (a, b) => Math.min(a.right, b.right) - Math.max(a.x, b.x) > 0.5 && Math.min(a.bottom, b.bottom) - Math.max(a.y, b.y) > 0.5;
      for (const c of geo.controls) {
        check(c.x >= 0 && c.right <= width && c.y >= geo.page.y && c.bottom <= geo.page.bottom, `${label}: ${c.name} contained`);
        check(c.w >= 44 && c.h >= 44 && c.hit, `${label}: ${c.name} 44pt and hittable`);
        for (const other of geo.controls) if (c !== other) check(!overlaps(c, other), `${label}: ${c.name}/${other.name} separate`);
        if (geo.dots) check(!overlaps(c, { ...geo.dots, y: geo.dots.y - 11, bottom: geo.dots.bottom + 11 }), `${label}: ${c.name}/dots separate`);
      }
      for (const b of geo.bands) {
        check(b.position === 'absolute' && b.bg === 'rgba(0, 0, 0, 0)' && b.shadow === 'none' && b.borders.every(v => parseFloat(v) === 0), `${label}: ${b.name} transparent overlay`);
        check(b.pseudos.every(p => p.content === 'none' || (p.bg === 'rgba(0, 0, 0, 0)' && p.image === 'none')), `${label}: ${b.name} no full-width painted pseudo`);
      }
      check(Math.abs(geo.host.y - geo.page.y) < 1, `${label}: no permanent header space`);
      const frame = page.frames().find(f => f.url() === 'about:srcdoc');
      if (frame && n !== 7) {
        const first = await frame.locator('h1').evaluate(el => el.getBoundingClientRect().top);
        check(first >= 72, `${label}: iframe initial first line clear`);
      }
      if (width === 390 && n === 3) {
        // A real wheel scroll crosses the header band; no scrollTop assignment.
        const iframe = page.locator('.mf-cv-host iframe');
        const box = await iframe.boundingBox();
        await page.mouse.move(box.x + box.width / 2, box.y + 240);
        await page.mouse.wheel(0, 350);
        await page.waitForFunction(() => {
          const f = document.querySelector('.mf-cv-host iframe');
          return f?.contentDocument?.scrollingElement?.scrollTop > 0;
        });
        const scroll = await frame.evaluate(() => ({ top: document.scrollingElement.scrollTop, y: document.querySelector('h1').getBoundingClientRect().top }));
        check(scroll.top > 0 && scroll.y < 0, `${label}: content scrolls beneath top controls`);
        await page.screenshot({ path: join(out, `${label}-scrolled.png`), animations: 'disabled' });
      }
      check(session.errors.length === 0, `${label}: no JS/console errors`);
    }
    // Existing iframe nodes survive breakpoints; browser reparenting may reload srcdoc.
    await page.evaluate(() => {
      window.__clearanceFrame = document.querySelector('.mf-cv-host iframe');
    });
    for (const target of [1280, width, 1280, width]) {
      await page.setViewportSize({ width: target, height: 844 });
      await page.waitForFunction(mobile => document.body.classList.contains('mobile-layer') === mobile, target < 769);
      await settle(page);
      const state = await page.evaluate(() => {
        const f = window.__clearanceFrame;
        return { same: f.isConnected && document.querySelector('iframe[data-nf-canvas-html]') === f,
          padding: parseFloat(getComputedStyle(f.contentDocument.body).paddingTop) };
      });
      check(state.same, `${engine}-${width}-${theme}: breakpoint ${target} keeps iframe node`);
      check(state.padding === (target < 769 ? 72 : 0), `${engine}-${width}-${theme}: breakpoint ${target} updates clearance`);
    }
    // Also exercise the common Markdown tool group, not just the widest HTML group.
    await page.evaluate(async () => {
      const nav = await import('/js/mobile/navController.js');
      (await import('/js/canvas.js')).setActiveTab(nav.docTabs()[1].id);
    });
    await settle(page);
    const markdown = await page.evaluate(() => {
      const pane = document.querySelector('.mf-cv-host > .canvas-tab-pane');
      const controls = [...pane.querySelectorAll('button'), ...document.querySelectorAll('.ask-fab')]
        .filter(el => el.getClientRects().length && getComputedStyle(el).display !== 'none')
        .map(el => { const r = el.getBoundingClientRect(); return { x: r.x, right: r.right, y: r.y, bottom: r.bottom, w: r.width, h: r.height }; });
      return { controls, first: pane.querySelector('h1')?.getBoundingClientRect().top };
    });
    check(markdown.controls.every(r => r.w >= 44 && r.h >= 44 && r.x >= 0 && r.right <= width), `${engine}-${width}-${theme}: markdown controls contained and 44pt`);
    check(markdown.first >= 72, `${engine}-${width}-${theme}: markdown first line clear`);
    await page.screenshot({ path: join(out, `${engine}-${width}-${theme}-markdown.png`), animations: 'disabled' });
    await context.close();
  }
} finally {
  await browser.close();
  writeFileSync(join(out, `${engine}-readings.json`), JSON.stringify({ readings, failures }, null, 2));
}
console.log(`${engine}: ${readings.length} captures; ${failures.length} failed checks; ${out}`);
if (!process.env.CANVAS_CAPTURE_ONLY) assert.deepEqual(failures, []);

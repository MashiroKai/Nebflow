#!/usr/bin/env node
// Canvas chrome emitters must use the shared visual face; no local shadows.
// Dynamic emitters outside the template scan must be registered below; see CONTRIBUTING.
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
const web = fileURLToPath(new URL('../src/main/resources/web/', import.meta.url));
const read = p => readFileSync(join(web, p), 'utf8');
const failures = [];
for (const [file, patterns] of Object.entries({
  // Manual refresh was retired in favor of automatic refresh; do not require its emitter.
  'js/viewers/shared.js': ['canvas-source-toggle', 'canvas-ref-select', 'canvas-find-toggle'],
  'js/viewers/zoom.js': ['canvas-zoom-btn'],
  'js/viewers/image.js': ['canvas-zoom-btn canvas-pan-toggle', 'viewer-retry-btn'],
  'js/viewers/largeText.js': ['lt-btn'],
  'js/viewers/mobi.js': ['viewer-retry-btn'],
  'js/viewers/html.js': ['glass-control nf-frame-nav-back'],
  'js/askDock.js': ['ask-fab', 'ask-fab note-fab'],
  'js/canvas.js': ['deck-fab g2-fab', 'g2-fab canvas-button', 'canvas-pop-btn', 'canvas-refback-btn', 'deck-card-pop', 'canvas-merge-btn'],
  'js/mobile/navController.js': ['mf-cv-close g2-fab', 'mf-cv-stack g2-fab'],
})) {
  const src = read(file);
  for (const name of patterns) {
    const lines = src.split('\n').filter(l => /(?:className\s*=|class:)\s*['"]/.test(l) && new RegExp(`(?:^|[\\s'"])${name}(?=[\\s'"])`).test(l));
    if (!lines.length || lines.some(l => !l.includes('canvas-button'))) failures.push(`${file}: ${name} missing shared class`);
  }
}
// Reader templates are exclusively canvas chrome: scan every emitted button, including new ones.
for (const file of ['js/viewers/readerChrome.js', 'js/viewers/annotations.js', 'js/viewers/xlsx.js']) {
  for (const match of read(file).matchAll(/<button\b[^>]*>/g)) {
    if (!/class="[^"]*\bcanvas-button\b/.test(match[0])) failures.push(`${file}: button missing shared class: ${match[0].slice(0, 100)}`);
  }
}
// Closed ask/note morph seeds must not paint a second glass layer below the FAB.
const askCss = read('css/ask.css');
if (!/\.ask-dock\.closed\s+\.ask-input\s*,\s*\.note-dock\.closed\s+\.note-body\s*\{[^}]*visibility:\s*hidden\s*;/s.test(askCss)) failures.push('closed ask/note glass seeds must be hidden');
const css = read('css/split.css');
if (!/\.canvas-button\.canvas-button\s*\{[^}]*border-radius:\s*var\(--ds-radius-pill\);[^}]*box-shadow:\s*var\(--ds-shadow-float\)/s.test(css) || !/\.canvas-button\.canvas-button:hover\s*\{[^}]*var\(--cv-hover-bg\)/s.test(css)) failures.push('shared canvas face incomplete');
for (const file of readdirSync(join(web, 'css')).filter(f => f.endsWith('.css'))) {
  const src = read(`css/${file}`).replace(/\/\*[\s\S]*?\*\//g, '');
  for (const m of src.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    const selector = m[1].trim();
    if (!/(?:\.canvas-button|\.canvas-source-toggle|\.canvas-ref-select|\.canvas-find-toggle|\.canvas-refresh-toggle|\.ask-fab|\.mf-cv-close|\.mf-cv-stack|#deck-fab|#canvas-head-close)/.test(selector)) continue;
    if (/\bbox-shadow\s*:/.test(m[2]) && !(file === 'split.css' && selector === '.canvas-button.canvas-button' && /box-shadow:\s*var\(--ds-shadow-float\)\s*;/.test(m[2]))) failures.push(`${file}: private shadow in ${selector}`);
  }
}
// Browser-backed registration and compositing guard on the actual mobile Canvas.
// This deliberately scans the rendered fixture, not unvisited application states.
import { loadPlaywright, createSession, settle } from './lib/visual-harness.mjs';
const { mod } = loadPlaywright();
const browser = await mod.chromium.launch({ headless: true });
try {
  const context = await browser.newContext({ viewport: { width: 375, height: 812 }, hasTouch: true });
  const { page, boot } = await createSession({ newPage: () => context.newPage() }, { width: 375, height: 812, baseURL: 'https://stand.test/' });
  await boot();
  await page.waitForSelector('body.mobile-layer');
  await page.evaluate(async () => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: { id: 'file:/tmp/canvas-uniform.md', itemType: 'markdown', title: 'uniform', content: 'Canvas', pinned: true, absPath: '/tmp/canvas-uniform.md' } }));
    (await import('/js/mobile/navController.js')).dockTo('canvas');
  });
  await settle(page);
  const negative = process.env.CANVAS_BUTTON_NEGATIVE;
  if (negative) await page.evaluate(kind => {
    const search = document.querySelector('.mf-cv-host .canvas-find-toggle');
    if (kind === 'darken') { search.style.setProperty('transition', 'none', 'important'); search.style.setProperty('background', 'rgba(0,0,0,.8)', 'important'); }
    if (kind === 'unregistered') {
      const el = search.cloneNode(true); el.className = 'g2-fab unregistered-fab';
      el.style.cssText = 'position:fixed;left:100px;top:100px;z-index:9999'; document.body.append(el);
    }
    if (kind === 'layer') {
      const el = document.createElement('div'); const r = search.getBoundingClientRect();
      el.style.cssText = `position:fixed;left:${r.left}px;top:${r.top}px;width:${r.width}px;height:${r.height}px;backdrop-filter:blur(24px);background:rgba(0,0,0,.3);pointer-events:none;z-index:9`;
      document.body.append(el);
    }
  }, negative);
  const issues = await page.evaluate(() => {
    const errors = [];
    const visible = e => { const s = getComputedStyle(e); return e.getClientRects().length && s.display !== 'none' && s.visibility === 'visible' && Number(s.opacity) > 0; };
    const registry = ['canvas-find-toggle', 'canvas-source-toggle', 'canvas-ref-select', 'ask-fab', 'mf-cv-close', 'mf-cv-stack', 'mf-stack-x', 'mobile-back-btn', 'mh-chrome-btn', 'mh-sess-more', 'bgagent-more', 'bgagent-stop-fab', 'reminder-fab-add', 'tfl-fab-add', 'tfl-topmenu-btn', 'pf-edit-submit', 'pf-edit-cancel', 'fm-pick-back', 'fm-pick-fab', 'ta-fab', 'ta-save', 'mh-search-bar', 'deck-fab'];
    const buttons = [...document.querySelectorAll('button.g2-fab,button.canvas-find-toggle,button.canvas-source-toggle,button.canvas-ref-select,button.ask-fab,#deck-fab,#canvas-head-close')].filter(visible);
    for (const e of buttons) if (!registry.some(name => e.classList.contains(name)) && e.id !== 'canvas-head-close') errors.push(`unregistered circular button: ${e.className}`);
    const family = buttons.filter(e => e.matches('.canvas-find-toggle,.canvas-source-toggle,.canvas-ref-select,.ask-fab,.mf-cv-close,.mf-cv-stack'));
    const ref = family.find(e => e.classList.contains('canvas-find-toggle'));
    if (!ref || family.length < 5) errors.push(`fixture missing canvas controls: ${family.length}`);
    if (ref) {
      const baseline = getComputedStyle(family.find(e => e.classList.contains('ask-fab'))).backgroundColor;
      for (const e of family) if (getComputedStyle(e).backgroundColor !== baseline) errors.push(`unequal button background: ${e.className}`);
      // Detect independently painted overlapping glass of roughly the same size.
      for (const e of family) {
        const r = e.getBoundingClientRect();
        for (const layer of document.querySelectorAll('body *')) {
          if (layer === e || layer.contains(e) || e.contains(layer) || buttons.includes(layer) || !visible(layer)) continue;
          const s = getComputedStyle(layer); if (s.backdropFilter === 'none') continue;
          const b = layer.getBoundingClientRect(); const overlap = Math.max(0, Math.min(r.right,b.right)-Math.max(r.left,b.left))*Math.max(0,Math.min(r.bottom,b.bottom)-Math.max(r.top,b.top));
          if (overlap / (r.width*r.height) > .8 && b.width <= r.width*1.25 && b.height <= r.height*1.25) errors.push(`extra glass layer under/over ${e.className}: ${layer.className}`);
        }
      }
    }
    return errors;
  });
  failures.push(...issues);
  await context.close();
} finally { await browser.close(); }
if (failures.length) { console.error(`canvas-buttons FAIL (${failures.length} hits)\n` + failures.join('\n')); process.exitCode = 1; }
else console.log('canvas-buttons PASS: shared face, rendered registration and overlap guard');

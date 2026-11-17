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
if (failures.length) { console.error('canvas-buttons FAIL\n' + failures.join('\n')); process.exitCode = 1; }
else console.log('canvas-buttons PASS: shared class, shadow, radius and hover');

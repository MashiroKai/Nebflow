#!/usr/bin/env node
// Page-header back controls: scan CSS selectors with back/back-btn class names.
// Named exceptions are distinct controls, not an exemption for new back classes.
import { readdirSync, readFileSync } from 'node:fs';
import { join, relative, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const web = process.env.STAND_PAGE_BACK_WEB || join(root, 'src/main/resources/web');
const allow = new Map([
  ['mobile-back-btn', 'existing g2-fab floating mobile header control'],
  ['fm-pick-back', 'existing g2-fab picker control'],
  ['flowmap-back-btn', 'text-bearing navigation breadcrumb, distinct geometry'],
  ['canvas-refback-btn', 'canvas reference navigation control'],
  ['mfs-back', 'mobile sheet navigation control'],
]);
function walk(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap(e => e.name === 'vendor' ? [] : e.isDirectory() ? walk(join(dir, e.name)) : e.name.endsWith('.css') ? [join(dir, e.name)] : []);
}
let files;
try { files = walk(join(web, 'css')); } catch (e) { console.error(e.message); process.exit(2); }
if (!files.length) { console.error('no CSS files'); process.exit(2); }
let errors = 0;
const seenExceptions = new Set();
for (const file of files) {
  const source = readFileSync(file, 'utf8').replace(/\/\*[\s\S]*?\*\//g, s => s.replace(/[^\n]/g, ' '));
  const rules = /([^{}]+)\{([^{}]*)\}/g;
  for (const match of source.matchAll(rules)) {
    for (const selector of match[1].split(',')) {
      const classes = [...selector.matchAll(/\.((?:[\w-]+-)?back(?:-btn)?)(?![\w-])/g)].map(m => m[1]);
      for (const c of classes) if (allow.has(c)) seenExceptions.add(c);
      if (!classes.length || classes.every(c => allow.has(c))) continue;
      for (const declaration of match[2].split(';')) {
        const colon = declaration.indexOf(':');
        if (colon < 0) continue;
        const prop = declaration.slice(0, colon).trim();
        const value = declaration.slice(colon + 1).trim();
        if (!/^(?:min-|max-)?(?:width|height)$|^(?:font(?:-size)?|margin(?:-[\w]+)?|padding(?:-[\w]+)?|gap|color|background(?:-color)?|border(?:-color)?)$/.test(prop)) continue;
        // Remove variable calls before testing so literal fallbacks cannot mask a violation.
        const bare = value.replace(/var\(--[\w-]+\)/g, '');
        if (/(?:\d*\.)?\d+px\b|#[\da-f]{3,8}\b|\b(?:rgb|rgba|hsl|hsla)\s*\(/i.test(bare)) {
          console.error(`${relative(root, file)}:${source.slice(0, match.index).split('\n').length} ${selector.trim()} ${prop}: ${value}`);
          errors++;
        }
      }
    }
  }
}
for (const [c, reason] of allow) console.warn(`WARN known pending migration .${c}: ${reason}${seenExceptions.has(c) ? '' : ' (not matched by naming scan)'}`);
if (errors) { console.error(`FAIL ${errors} page-back literal(s)`); process.exit(1); }
console.log(`check-page-back-tokens: green (${files.length} CSS files; ${allow.size} documented special classes)`);

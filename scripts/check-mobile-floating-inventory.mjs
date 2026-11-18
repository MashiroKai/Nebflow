#!/usr/bin/env node
// Inventory checks declare source-level responsibility, not computed geometry.
// A modal drawer blocks interaction intentionally; it has no underlying scroll
// clearance requirement, but its own box is required to be scrollable.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
const css = name => readFileSync(new URL(`../src/main/resources/web/css/${name}`, import.meta.url), 'utf8');
const inventory = [
  ['todo top capsule', 'mobile-todo-apple.css', /\.ta-pill\s*$/, /height\s*:\s*var\(--mcap-h\)/, /\.mobile-page-body\s*$/, /padding-top\s*:[^;]*--mchrome-clear/],
  ['todo bottom FAB', 'mobile-todo-apple.css', /\.ta-fab-wrap\s*$/, /position\s*:\s*absolute/, /\.ta-root\s*$/, /padding-bottom\s*:[^;]*--mpage-fab-clearance/],
  ['todo editor tool', 'mobile-todo-apple.css', /\.ta-tool\s*$/, /position\s*:\s*fixed/, /\[data-page="todo-apple-item"\]\s+\.ta-root\s*$/, /padding-bottom\s*:[\s\S]*--ta-kb[\s\S]*--bottom-band-d/],
  ['message search', 'mobileSearchFlat.css', /\.mh-search-dock:not\(\.has-query\)\s*$/, /position\s*:\s*absolute/, /#fm-conversations/, /padding-top\s*:[^;]*--mhsc-band/],
  ['contact search', 'mobileSearchFlat.css', /\.mh-search-dock:not\(\.has-query\)\s*$/, /position\s*:\s*absolute/, /#fm-contacts-body/, /padding-top\s*:[^;]*--mhsc-band/],
  ['session identity', 'mobileLayer.css', /\.mh-idhead\s*$/, /position\s*:\s*absolute/, /\[data-page='conversation'\]\s+\.fm-flow\s*$/, /padding-top\s*:[^;]*104px/],
  ['modal drawer', 'mobileLayer.css', /^\.mf-drawer\s*$/, /position\s*:\s*absolute/, /^\.mf-drawer-body\s*$/, /overflow-y\s*:\s*auto/],
];
export function checkInventory(get = css) {
  const errors = [];
  for (const [name, file, overlay, overlayRule, owner, ownerRule] of inventory) {
    const source = get(file).replace(/\/\*[\s\S]*?\*\//g, ' ');
    const rules = [...source.matchAll(/([^{}]+)\{([^{}]*)\}/g)].map(m => ({ selector: m[1].trim(), body: m[2] }));
    if (!rules.some(r => overlay.test(r.selector) && overlayRule.test(r.body))) errors.push(`${name}: missing overlay declaration in ${file}`);
    if (!rules.some(r => owner.test(r.selector) && ownerRule.test(r.body))) errors.push(`${name}: missing or mismatched clearance owner in ${file}`);
  }
  return errors;
}
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const errors = checkInventory();
  if (errors.length) { console.error(errors.join('\n')); process.exitCode = 1; }
  else console.log('mobile floating inventory: PASS (source declarations only)');
}

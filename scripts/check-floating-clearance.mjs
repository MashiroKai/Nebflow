#!/usr/bin/env node
// Guard the mobile page/dock contract. This is intentionally selector-scoped;
// it does not claim to infer scroll ownership for arbitrary CSS.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

// Mobile chrome families and their scroll-clearance owners. Shared bands are
// zero-height positioning hosts; the controls, not their hosts, are measured.
const CHROME = [
  ['dock', /#mobile-dock\s*$/, /\.mobile-page-body\s*$/, 'padding-bottom', '--mobile-dock-clearance'],
  ['page back', /\.mobile-back-btn\s*$/, /\.mobile-page:not\([^\n]+\.mobile-page-body/, 'padding-top', '--mchrome-clear'],
  ['chat top actions', /\.mh-chrome-btn\s*$/, /\.mobile-page:not\([^\n]+\.mobile-page-body/, 'padding-top', '--mchrome-clear'],
  ['canvas tab strip', /\.mf-strip\s*$/, /canvas-md-scroll/, 'padding-bottom', '--mobile-dock-clearance'],
  ['canvas top actions', /\.mf-cv-toolbar\s+\.g2-fab\s*$/, /canvas-md-scroll/, 'padding-bottom', '--mobile-dock-clearance'],
];
export function check(css) {
  const errors = [];
  const clean = css.replace(/\/\*[\s\S]*?\*\//g, m => m.replace(/[^\n]/g, ' '));
  const rules = [...clean.matchAll(/([^{}]+)\{([^{}]*)\}/g)].map(m => ({
    selector: m[1].trim(), body: m[2], line: clean.slice(0, m.index).split('\n').length,
  }));
  for (const {selector, body, line} of rules) {
    const decl = name => [...body.matchAll(new RegExp(`(?:^|;)\\s*${name}\\s*:\\s*([^;]+)`, 'gi'))].map(m => m[1].trim());
    if (/height\s*:\s*100vh\b/i.test(body)) errors.push(`${line}: height:100vh is forbidden`);
    // Any newly introduced fixed host in the mobile shell requires review and
    // a clearance mapping. #mobile-pages is the noninteractive page shell.
    if (decl('position').includes('fixed') && !/(?:#mobile-dock|#mobile-pages)(?![\w-])/.test(selector)) errors.push(`${line}: unregistered fixed mobile host: ${selector}`);
    if (/(?:#mobile-dock|\.mobile-page-header)(?![\w-])/.test(selector) && decl('position').includes('absolute')) errors.push(`${line}: floating chrome cannot be absolute`);
    if (/(?:mobile-page-body|fm-conversations|settings-content|canvas-md-scroll|canvas-txt-scroll)/.test(selector) && decl('height').some(v => /^(?:\d+(?:\.\d+)?(?:px|vh|dvh)|calc\()/i.test(v)) && decl('overflow').includes('hidden')) errors.push(`${line}: fixed-height content clips overflow`);
  }
  const has = (sel, property, token) => rules.some(r => sel.test(r.selector) && new RegExp(`${property}\\s*:[^;]*${token}`).test(r.body));
  if (!has(/\.mobile-page-body\s*$/, 'padding-bottom', '--mobile-dock-clearance')) errors.push('page body lacks dock clearance');
  if (!has(/#fm-conversations/, 'padding-bottom', '--mobile-dock-clearance')) errors.push('message scroller lacks dock clearance');
  if (!has(/#settings-content\s*$/, 'padding-bottom', '--mobile-dock-clearance')) errors.push('settings scroller lacks dock clearance');
  if (!has(/canvas-md-scroll/, 'padding-bottom', '--mobile-dock-clearance')) errors.push('document scroller lacks dock clearance');
  for (const [name, selector, owner, property, token] of CHROME) {
    if (!rules.some(r => selector.test(r.selector))) errors.push(`missing registered chrome: ${name}`);
    if (!has(owner, property, token)) errors.push(`missing clearance for chrome: ${name}`);
  }
  return errors;
}
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const path = process.argv[2] || new URL('../src/main/resources/web/css/mobileLayer.css', import.meta.url);
  const errors = check(readFileSync(path, 'utf8'));
  if (errors.length) { console.error(errors.join('\n')); process.exitCode = 1; }
  else console.log('floating clearance contract: PASS');
}

#!/usr/bin/env node
// Guard the mobile page/dock contract. This is intentionally selector-scoped;
// it does not claim to infer scroll ownership for arbitrary CSS.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

// Mobile chrome families and their scroll-clearance owners. Shared bands are
// zero-height positioning hosts; the controls, not their hosts, are measured.
const CHROME = [
  // 滚域末端净空单源批（2026-11-18）：dock 让位在**滚域内容级**消费
  // `--mobile-scroll-end`（= dock 让位 + 浮钮带 + 输入条带），不再由各页手写
  // `--mobile-dock-clearance` 字号面。token 链本身在下块逐环断言。
  ['dock', /#mobile-dock\s*$/, /\.mobile-page-body\s*$/, 'padding-bottom', '--mobile-scroll-end'],
  ['page back', /\.mobile-back-btn\s*$/, /\.mobile-page:not\([^\n]+\.mobile-page-body/, 'padding-top', '--mchrome-clear'],
  ['chat top actions', /\.mh-chrome-btn\s*$/, /\.mobile-page:not\([^\n]+\.mobile-page-body/, 'padding-top', '--mchrome-clear'],
  ['canvas tab strip', /\.mf-strip\s*$/, /\.mf-canvas-page\s+\.canvas-viewer-safe-top/, 'padding-top', '--mf-strip-h'],
  ['canvas top actions', /\.mf-cv-toolbar\s+\.g2-fab\s*$/, /\.mf-canvas-page\s+\.canvas-viewer-safe-top/, 'padding-top', '--mf-strip-h'],
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
  // 单源 token 链：滚域末端净空只有 `--mobile-scroll-end` 一个来源，而它必须
  // 仍由 dock 让位派生（scroll-end ← dock-clearance ← dock-top ← 实测 dock 高
  // + 底内距 + 安全区）。逐环断言，防止「消费点换名后净空悄悄脱钩」。
  for (const [token, derives] of [
    ['--mobile-dock-top', /--mobile-dock-measured|--dock-h/],
    ['--mobile-dock-clearance', /--mobile-dock-top/],
    ['--mobile-scroll-end', /--mobile-dock-clearance/],
  ]) {
    const decls = [];
    for (const r of rules) {
      const m = r.body.match(new RegExp(`(?:^|[\\s;])${token}\\s*:\\s*([^;]+)`));
      if (m) decls.push({ value: m[1], line: r.line });
    }
    if (!decls.length) errors.push(`missing single-source token: ${token}`);
    else if (!decls.some(d => derives.test(d.value))) errors.push(`${decls[0].line}: ${token} no longer derives from ${derives}`);
  }
  if (!has(/\.mobile-page-body\s*$/, 'padding-bottom', '--mobile-scroll-end')) errors.push('page body lacks scroll-end clearance');
  if (!has(/#fm-conversations/, 'padding-bottom', '--mobile-scroll-end')) errors.push('message scroller lacks scroll-end clearance');
  if (!has(/#settings-content\s*$/, 'padding-bottom', '--mobile-scroll-end')) errors.push('settings scroller lacks scroll-end clearance');
  if (!has(/canvas-md-scroll/, 'padding-bottom', '--mobile-scroll-end')) errors.push('document scroller lacks scroll-end clearance');
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

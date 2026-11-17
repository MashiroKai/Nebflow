// CSSOM candidate inventory against booted product page; unmatched is not a pass.
import { mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { loadPlaywright, launchBrowser, createSession, webRoot } from '../scripts/lib/visual-harness.mjs';
const out = process.env.GSU_EVID || '/private/tmp/gsu-evid';
mkdirSync(out, { recursive: true });
const { mod } = loadPlaywright();
const { browser, version } = await launchBrowser(mod);
const runs = [];
const cssDir = join(webRoot(), 'css');
const sources = readdirSync(cssDir).filter(f => f.endsWith('.css')).map(f => ({ name: f, text: readFileSync(join(cssDir, f), 'utf8') }));
try {
  for (const colorScheme of ['light', 'dark']) {
    for (const viewport of [{ name: 'desktop', width: 1440, height: 900 }, { name: 'mobile', width: 375, height: 812 }]) {
      const session = await createSession(browser, { ...viewport, colorScheme });
      try {
        await session.boot();
        const data = await session.page.evaluate((sources) => {
          const candidates = [];
          function walk(rules, source, context = '') {
            for (const rule of rules) {
              if (!rule.selectorText || !rule.style) { if (rule.cssRules) walk(rule.cssRules, source, context + ' ' + rule.conditionText); continue; }
              const decl = rule.style.cssText;
              if (!/--(?:nf-glass|glass-|cv-bg|cv-border|cv-shadow)|(?:^|;)\s*(?:-webkit-)?backdrop-filter\s*:\s*(?!none)/i.test(decl)) continue;
              // CSSOM selectorText is retained verbatim. A grouped selector is one candidate;
              // its matched elements are enumerated, not inferred from static cascade.
              let matches;
              try { matches = [...document.querySelectorAll(rule.selectorText)]; }
              catch (e) { candidates.push({ source, selector: rule.selectorText, context, decl, status: 'unqueryable', error: String(e) }); continue; }
              if (!matches.length) { candidates.push({ source, selector: rule.selectorText, context, decl, status: 'unrendered' }); continue; }
              for (const el of matches) {
                const style = getComputedStyle(el), rect = el.getBoundingClientRect();
                candidates.push({ source, selector: rule.selectorText, context, decl, node: el.tagName + (el.id ? '#' + el.id : '') + (typeof el.className === 'string' ? '.' + el.className.trim().replace(/\s+/g, '.') : ''), background: style.backgroundColor, filter: style.backdropFilter, visible: !!(rect.width && rect.height), status: style.backdropFilter === 'none' ? 'non-glass-or-unresolved' : 'needs-comparison' });
              }
            }
          }
          for (const source of sources) { const sheet = new CSSStyleSheet(); sheet.replaceSync(source.text); walk(sheet.cssRules, source.name); }
          if (!candidates.length) throw new Error('CSS candidate discovery returned no rules');
          const ref = document.querySelector('#input-bar');
          const base = ref ? { bg: getComputedStyle(ref).backgroundColor, filter: getComputedStyle(ref).backdropFilter } : null;
          for (const c of candidates) if (c.status === 'needs-comparison') c.status = base && c.background === base.bg && c.filter === base.filter ? 'matches-reference' : 'differs-reference';
          return { reference: base, candidates };
        }, sources);
        runs.push({ colorScheme, viewport: viewport.name, ...data, pageErrors: session.errors });
      } finally { await session.close(); }
    }
  }
} finally { await browser.close(); }
writeFileSync(`${out}/page-inventory.json`, JSON.stringify({ version, limitations: 'booted main page only; grouped selectors, pseudo-elements, hidden elements and inactive media require review; no canvas, ask, deck or state driving', runs }, null, 2));
for (const run of runs) {
  const counts = {};
  for (const c of run.candidates) counts[c.status] = (counts[c.status] || 0) + 1;
  console.log(run.colorScheme, run.viewport, JSON.stringify(counts));
}
console.log(`Wrote ${out}/page-inventory.json`);

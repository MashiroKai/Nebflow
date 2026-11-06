// _composer-debug.mjs — one-off: why does #input-bar background compute transparent?
import { chromium } from 'playwright';
import { bootChat, historyFixture } from './_chat-face-harness.mjs';
const browser = await chromium.launch();
try {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'light' });
  const page = await ctx.newPage();
  await bootChat(page, { history: historyFixture(1) });
  const out = await page.evaluate(() => {
    const root = getComputedStyle(document.documentElement);
    const bar = document.getElementById('input-bar');
    const matched = [];
    for (const sheet of document.styleSheets) {
      let rules; try { rules = sheet.cssRules; } catch { continue; }
      const walk = (list) => {
        for (const r of list) {
          if (r.cssRules && !r.selectorText) { walk(r.cssRules); continue; }
          if (!r.selectorText || !r.style) continue;
          let matches = false;
          try { matches = bar.matches(r.selectorText); } catch {}
          if (matches && (r.style.background || r.style.backdropFilter)) {
            matched.push({
              sel: r.selectorText.slice(0, 80),
              href: (sheet.href || '').split('/').pop(),
              bg: r.style.background || r.style.backgroundColor,
              bdf: r.style.backdropFilter,
              prio: r.style.getPropertyPriority('backdrop-filter'),
            });
          }
        }
      };
      walk(rules);
    }
    return {
      tokenBg: root.getPropertyValue('--nf-glass-input-bg'),
      tokenFilter: root.getPropertyValue('--nf-glass-input-filter'),
      barHtml: bar?.outerHTML?.slice(0, 300),
      sheets: [...document.styleSheets].map(s => ({ href: s.href, count: s.cssRules?.length })).filter(s => s.href),
      allInputRules: [...document.styleSheets].flatMap(s => { try { return [...s.cssRules].filter(r => r.selectorText?.includes('input-bar')).map(r => ({href:s.href, sel:r.selectorText, bg:r.style?.background, filter:r.style?.backdropFilter})); } catch {return [];} }),
      computedBg: getComputedStyle(bar).backgroundColor,
      computedBdf: getComputedStyle(bar).backdropFilter,
      matched,
      dataTheme: document.documentElement.getAttribute('data-theme'),
      rootClass: document.documentElement.className,
      computedInputBg: getComputedStyle(bar).getPropertyValue('background'),
    };
  });
  console.log(JSON.stringify(out, null, 2));
} finally { await browser.close(); }

// Real settings DOM/CSS audit; transport is the shared mock harness only.
import { chromium } from 'playwright';
import { mkdirSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { bootChat, historyFixture } from './_chat-face-harness.mjs';
const outDir = fileURLToPath(new URL('../reports-evidence/notify-bubble-fix/', import.meta.url));
mkdirSync(outDir, { recursive: true });
const browser = await chromium.launch();
const results = [];
try {
  for (const theme of ['light', 'dark']) for (const width of [1440, 375]) {
    const context = await browser.newContext({ viewport: { width, height: 900 }, colorScheme: theme });
    const page = await context.newPage();
    await bootChat(page, { history: historyFixture(1) });
    const report = await page.evaluate(async () => {
      const sidebar = await import('/js/sidebar.js');
      sidebar.renderSettings();
      const overlay = document.getElementById('settings-overlay');
      overlay?.classList.add('on');
      const modal = document.getElementById('settings-modal');
      const content = document.getElementById('settings-content');
      const cards = [...content.querySelectorAll('.set-card')];
      const navs = [...content.querySelectorAll('.set-nav')];
      const rect = el => { const r = el.getBoundingClientRect(); return { x:r.x, y:r.y, width:r.width, height:r.height, right:r.right, bottom:r.bottom }; };
      const overflow = el => el.scrollWidth > el.clientWidth + 1 || el.scrollHeight > el.clientHeight + 1;
      const overwideCards = cards.filter(el => { const r=rect(el); return r.x < -1 || r.right > innerWidth + 1; }).map(el=>({title:el.querySelector('.set-card-title')?.textContent,rect:rect(el)}));
      const overwideNavs = navs.filter(el => { const r=rect(el); return r.x < -1 || r.right > innerWidth + 1; }).map(el=>({title:el.querySelector('.set-nav-label')?.textContent,rect:rect(el)}));
      const clippedValues = [...content.querySelectorAll('.set-nav-val,.set-row-val')].filter(el=>overflow(el)).map(el=>({ className:el.className, text:el.textContent.trim(), title:el.getAttribute('title'), rect:rect(el), scrollWidth:el.scrollWidth, clientWidth:el.clientWidth, scrollHeight:el.scrollHeight, clientHeight:el.clientHeight }));
      const valuesClipped = clippedValues.length;
      const badNav = navs.filter(el => !el.querySelector('.set-nav-chev')).map(el=>el.id || el.textContent.trim());
      const expanders = [...content.querySelectorAll('.set-nav-expand')];
      const rows = { cards:cards.length, navs:navs.length, expanders:expanders.length, overwideCards, overwideNavs, valuesClipped, clippedValues, badNav, titles:cards.map(c=>c.querySelector('.set-card-title')?.textContent.trim()).filter(Boolean) };
      return rows;
    });
    results.push({ theme, width, report });
    await page.screenshot({ path: join(outDir, `settings-${theme}-${width}.png`), fullPage: false });
    await context.close();
  }
} finally { await browser.close(); }
writeFileSync(join(outDir, 'settings-audit.json'), JSON.stringify(results,null,2));
console.log(JSON.stringify(results,null,2));

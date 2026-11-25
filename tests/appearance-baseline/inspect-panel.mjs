import { writeFileSync } from 'node:fs';
import { loadPlaywright, launchBrowser, createSession, settle } from '../../scripts/lib/visual-harness.mjs';
const [kind, out] = process.argv.slice(2);
const { mod } = loadPlaywright(); const { browser } = await launchBrowser(mod);
try {
  const s = await createSession(browser, { width: 1280, height: 800, colorScheme: 'dark', apiStubs: { '/api/plugins': { body: { plugins: [], rejected: [] } }, '/api/nf-authcheck': { status: 204 } } });
  await s.boot();
  if (kind === 'settings') await s.page.locator('#settings-btn').click();
  await settle(s.page, 400);
  const selectors = await s.page.locator(kind === 'settings' ? '#settings-overlay' : '#canvas-panel').evaluate(root => [...root.querySelectorAll('*')].filter(el => { const r = el.getBoundingClientRect(), s = getComputedStyle(el); return r.width > 0 && r.height > 0 && s.display !== 'none' && s.visibility === 'visible'; }).map(el => ({ tag: el.tagName.toLowerCase(), id: el.id, class: typeof el.className === 'string' ? el.className : '', text: el.textContent.trim().slice(0, 35) })));
  writeFileSync(out, JSON.stringify(selectors, null, 2)); await s.close();
} finally { await browser.close(); }

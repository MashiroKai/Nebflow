// Zero-port Chromium experiment. Screenshots intentionally retain all grid cells.
import { mkdirSync, writeFileSync } from 'node:fs';
import { loadPlaywright, launchBrowser } from '../scripts/lib/visual-harness.mjs';
const out = process.env.GSU_EVID || '/private/tmp/gsu-evid';
mkdirSync(out, { recursive: true });
const { mod } = loadPlaywright();
const { browser, version } = await launchBrowser(mod);
try {
  const page = await browser.newPage({ viewport: { width: 1120, height: 800 }, deviceScaleFactor: 1, colorScheme: 'light' });
  const alphas = [.85,.88,.90,.92,.94,.96,.98,1];
  const blurs = [0,8,16,24,32];
  await page.setContent(`<style>*{box-sizing:border-box}body{margin:0;background:#ddd}.row{height:160px;display:flex;gap:20px;padding:20px}.sample{width:250px;height:120px;position:relative;overflow:hidden;background:#fff}.text{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;font:bold 64px Arial;color:#000;white-space:nowrap}.glass{position:absolute;inset:0}.red{background:#f00}.blue{background:#00f}</style><div id="root"></div>`);
  await page.evaluate(({ alphas, blurs }) => {
    for (const a of alphas) for (const b of blurs) {
      const row = document.createElement('div'); row.className = 'row';
      for (const mode of ['letters','plain','red','blue']) {
        const el = document.createElement('div'); el.className = 'sample ' + mode;
        if (mode === 'letters') el.innerHTML = '<span class="text">STAND</span>';
        const glass = document.createElement('div'); glass.className = 'glass';
        glass.style.background = `rgba(255,255,255,${a})`;
        glass.style.backdropFilter = `blur(${b}px) saturate(1.15)`;
        el.append(glass); row.append(el);
      }
      document.querySelector('#root').append(row);
    }
  }, { alphas, blurs });
  await page.screenshot({ path: `${out}/parameter-grid.png`, fullPage: true, animations: 'disabled' });
  writeFileSync(`${out}/grid-info.json`, JSON.stringify({ version, alphas, blurs, metric: 'see tests/glass-grid-analyze.py', note: 'controlled synthetic surfaces; not product consumer red-green' }, null, 2));
  console.log(`PASS rendered ${alphas.length * blurs.length} synthetic cells: ${out}/parameter-grid.png (Chromium ${version})`);
} finally { await browser.close(); }

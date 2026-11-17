// Product-page composer comparison. Route-backed visual harness, no listening port.
import { mkdirSync, writeFileSync } from 'node:fs';
import { loadPlaywright, launchBrowser, createSession } from '../scripts/lib/visual-harness.mjs';
const out = '/private/tmp/gsu-evid/composer-compare';
mkdirSync(out, { recursive: true });
const { mod } = loadPlaywright();
const { browser, version } = await launchBrowser(mod);
const result = [];
try {
  for (const item of [{ name: 'light-095', theme: 'light', value: 'rgba(255,255,255,.95)' },
    { name: 'light-current', theme: 'light', value: null },
    { name: 'dark-current', theme: 'dark', value: null }]) {
    const session = await createSession(browser, { width: 1440, height: 900, colorScheme: item.theme });
    try {
      await session.boot();
      await session.page.evaluate(({theme, value}) => {
        document.documentElement.dataset.theme = theme;
        const bar = document.querySelector('#input-bar');
        if (!bar) throw new Error('missing product composer');
        const rect = bar.getBoundingClientRect();
        if (!rect.width || !rect.height) throw new Error('composer not visible');
        // Same colorful content behind the actual composer in every run.
        const bg = document.createElement('div');
        Object.assign(bg.style, { position:'fixed', left:'0', top:`${rect.top-80}px`, width:'100%', height:'250px', zIndex:'0', pointerEvents:'none', background:'linear-gradient(110deg,#db395b 0%,#eda859 26%,#638ee4 53%,#6c40a0 78%,#1caa93 100%)' });
        document.body.insertBefore(bg, document.body.firstChild);
        bar.style.setProperty('transition', 'none', 'important');
        if (value) bar.style.setProperty('background', value, 'important');
      }, item);
      const info = await session.page.evaluate(() => {
        const el = document.querySelector('#input-bar'), s = getComputedStyle(el), r = el.getBoundingClientRect();
        return { background:s.backgroundColor, filter:s.backdropFilter, rect:{ x:r.x, y:r.y, width:r.width, height:r.height } };
      });
      const r = info.rect;
      await session.page.screenshot({ path:`${out}/${item.name}.png`, clip:{ x:Math.max(0,Math.floor(r.x)-12), y:Math.floor(r.y)-30, width:Math.min(1440-Math.max(0,Math.floor(r.x)-12),Math.ceil(r.width)+24), height:Math.ceil(r.height)+60 }, animations:'disabled' });
      result.push({ ...item, ...info });
    } finally { await session.close(); }
  }
} finally { await browser.close(); }
writeFileSync(`${out}/readings.json`, JSON.stringify({ version, result }, null, 2));
console.log(JSON.stringify(result, null, 2));

#!/usr/bin/env node
// Standalone browser regression: real renderer and viewer, no running backend.
import { chromium, webkit } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { resolve, join, extname } from 'node:path';

const web = resolve(process.env.WEB_ROOT || 'src/main/resources/web');
const out = process.env.SHOTS_DIR;
if (!out) throw new Error('SHOTS_DIR is required');
mkdirSync(out, { recursive: true });
const mime = { '.js':'text/javascript', '.css':'text/css', '.svg':'image/svg+xml', '.woff2':'font/woff2' };
const html = `<!doctype html><html><head><meta charset="utf-8">${['stand-tokens','design-system','base','sapphire','mermaid','lightbox'].map(x => `<link rel="stylesheet" href="/css/${x}.css">`).join('')}<script src="/vendor/lucide.min.js"></script></head><body><div class="nf-mermaid" data-mermaid-src="flowchart TD&#10;A[Hello START] --&gt; B[World DONE]"><div class="nf-mermaid-body"></div></div><script type="module">import {armMermaidAutoHydrate, hydrateMermaidIn} from '/js/mermaidRenderer.js'; import {openDiagramLightbox} from '/js/lightbox.js'; armMermaidAutoHydrate(); hydrateMermaidIn(document.body); window.openDiagramLightbox = openDiagramLightbox;</script></body></html>`;
let failures = 0;
for (const [name, engine] of Object.entries({chromium, webkit}).filter(([n]) => !process.env.ENGINE || n === process.env.ENGINE)) {
  const browser = await engine.launch({headless:true});
  for (const dark of [false,true]) for (const width of (process.env.WIDTH ? [Number(process.env.WIDTH)] : [1280,375,390])) {
    const tag = `${name}-${dark?'dark':'light'}-${width}`;
    const context = await browser.newContext({viewport:{width,height:800},colorScheme:dark?'dark':'light',acceptDownloads:true,hasTouch:width<500});
    const page = await context.newPage();
    page.on('pageerror', e => console.log('PAGEERROR', e.stack || String(e)));
    if (process.env.REGEX_TRACE) await context.addInitScript(() => {
      const Original = window.RegExp;
      function Trace(pattern, flags) {
        try { return new Original(pattern, flags); }
        catch (e) { console.log('REGEX FAILURE', String(pattern), String(flags), new Error().stack); throw e; }
      }
      Object.setPrototypeOf(Trace, Original); Trace.prototype = Original.prototype;
      window.RegExp = Trace;
    });
    page.on('console', m => { if (m.type() === 'error' || m.text().startsWith('REGEX FAILURE')) console.log('CONSOLE', m.text()); });
    await page.route('**/*', route => {
      const p = new URL(route.request().url()).pathname;
      if (p === '/') return route.fulfill({status:200,contentType:'text/html',body:html});
      try { return route.fulfill({status:200,contentType:mime[extname(p)] || 'application/octet-stream',body:readFileSync(join(web,p))}); }
      catch { return route.fulfill({status:404,body:'missing'}); }
    });
    try {
      await page.goto('http://localhost:4173/');
      await page.waitForSelector('.nf-mermaid.nf-fence-done svg',{timeout:30000});
      await page.evaluate(() => window.openDiagramLightbox(document.querySelector('.nf-mermaid svg')));
      const buttons = page.locator('.nf-lightbox-bar button');
      await buttons.nth(0).hover();
      await page.screenshot({path:join(out,`${tag}-hover.png`)});
      console.log('HOVER', tag, JSON.stringify(await buttons.nth(0).evaluate(b => { const s=getComputedStyle(b); return {color:s.color,bg:s.backgroundColor}; })));
      if (await buttons.count() !== 3) throw new Error('missing download buttons');
      const geom = await buttons.evaluateAll(bs => bs.map(b => { const r=b.getBoundingClientRect(); const s=getComputedStyle(b); return {x:r.x,right:r.right,width:r.width,color:s.color,bg:s.backgroundColor}; }));
      if (geom.some(g=>g.x<0||g.right>width||g.width<30) || geom.some((g,i)=>i && g.x < geom[i-1].right)) throw new Error('button geometry');
      for (const [i, ext] of [[1,'svg'],[2,'png']]) {
        const [download] = await Promise.all([page.waitForEvent('download',{timeout:10000}),buttons.nth(i).click()]);
        if (download.suggestedFilename() !== `diagram.${ext}`) throw new Error('filename');
        const path = join(out,`${tag}.${ext}`); await download.saveAs(path);
        const b = readFileSync(path);
        if (ext === 'svg' && (!b.toString().includes('>Hello</tspan>') || !b.toString().includes('>World</tspan>') || b.toString().includes('<foreignObject'))) throw new Error('svg content');
        if (ext === 'png' && !b.subarray(0,8).equals(Buffer.from('89504e470d0a1a0a','hex'))) throw new Error('png signature');
      }
      console.log('PASS',tag,JSON.stringify(geom));
    } catch(e) { failures++; console.log('FAIL',tag,String(e)); await page.screenshot({path:join(out,`${tag}-failure.png`)}); if (process.env.ONE_CASE) { await context.close(); break; } }
    await context.close();
  }
  await browser.close();
}
writeFileSync(join(out,'browser-result.txt'), `failures=${failures}\n`);
process.exitCode = failures ? 1 : 0;

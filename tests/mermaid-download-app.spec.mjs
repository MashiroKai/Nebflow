#!/usr/bin/env node
// Real app shell, network fixture; downloads and contrast use real Chromium.
import { chromium } from 'playwright';
import { strict as assert } from 'node:assert';
import { readFileSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
process.env.MERMAID_DOWNLOAD_IMPORT = '1';
const temp = mkdtempSync(join(tmpdir(), 'diagram-check-'));
process.env.SHOTS_DIR = temp;
const { boot, P_DOC, CANVAS_DOC } = await import('./mermaid-inline-center.spec.mjs');
const browser = await chromium.launch();
try {
  for (const dark of [false, true]) {
    const { ctx, page, sendHistory } = await boot(browser, { width: 1280, height: 800, dark });
    try {
      await page.evaluate(({ absPath, content }) => window.dispatchEvent(new CustomEvent('workspace-open-item', {detail: { id: 'file:' + absPath, itemType: 'markdown', title: 'canvas-diagrams.md', content, absPath, pinned: true }})), {absPath: P_DOC, content: CANVAS_DOC});
      await page.waitForSelector('.canvas-tab-pane.active .nf-mermaid[data-mermaid-state="done"]', {timeout:45000});
      for (const surface of ['canvas','chat']) {
        if (surface === 'chat') { sendHistory(); await page.waitForSelector('#chat .nf-mermaid[data-mermaid-state="done"]', {timeout:45000}); }
        const figure = page.locator(surface === 'canvas' ? '.canvas-tab-pane.active .nf-mermaid[data-mermaid-state="done"]' : '#chat .nf-mermaid[data-mermaid-state="done"]').first();
        await figure.scrollIntoViewIfNeeded();
        await figure.locator('.nf-figure-open').focus();
        await page.keyboard.press('Enter');
        await page.waitForSelector('.nf-lightbox.on .nf-lightbox-bar');
        const buttons = page.locator('.nf-lightbox-bar button');
        await buttons.first().hover();
        await page.waitForTimeout(250);
        const colors = await buttons.first().evaluate(b => {
          const parse = s => (s.match(/[\d.]+/g) || []).map(Number);
          const mix = (a,b) => [0,1,2].map(i => a[i]*(a[3] ?? 1) + b[i]*(1-(a[3] ?? 1)));
          const button = parse(getComputedStyle(b).backgroundColor);
          const bar = parse(getComputedStyle(b.parentElement).backgroundColor);
          const overlay = parse(getComputedStyle(b.closest('.nf-lightbox')).backgroundColor);
          const body = parse(getComputedStyle(document.body).backgroundColor);
          const bg = mix(button, mix(bar, mix(overlay, body.length >= 3 ? body : [255,255,255])));
          const fg = parse(getComputedStyle(b).color);
          const lum = c => c.slice(0,3).map(x => x/255).map(x => x <= .04045 ? x/12.92 : ((x+.055)/1.055)**2.4).reduce((s,x,i)=>s+x*[.2126,.7152,.0722][i],0);
          const l1=lum(fg), l2=lum(bg);
          return {fg,bg,ratio:(Math.max(l1,l2)+.05)/(Math.min(l1,l2)+.05)};
        });
        console.log(surface,dark?'dark':'light','contrast',JSON.stringify(colors));
        assert.ok(colors.ratio >= 3, 'WCAG non-text contrast below 3:1');
        assert.equal(await buttons.count(), 3);
        let svgSize;
        for (const [index, ext, mime] of [[1,'svg','image/svg+xml'],[2,'png','image/png']]) {
          const [download] = await Promise.all([page.waitForEvent('download'), buttons.nth(index).click()]);
          assert.equal(download.suggestedFilename(), `diagram.${ext}`);
          const bytes = readFileSync(await download.path());
          const inferredMime = await page.evaluate(async url => (await fetch(url)).headers.get('content-type'), download.url());
          assert.equal(inferredMime, mime);
          if (ext === 'svg') {
            const text=bytes.toString();
            const info=await page.evaluate(xml => { const doc=new DOMParser().parseFromString(xml,'image/svg+xml'); const root=doc.documentElement; const refs=[...root.querySelectorAll('*')].flatMap(el=>[...el.attributes].filter(a=>/^(href|xlink:href|src)$/i.test(a.name)).map(a=>a.value)); return {error:!!doc.querySelector('parsererror'), width:+root.getAttribute('width'),height:+root.getAttribute('height'),refs}; },text);
            assert.equal(info.error,false); assert.ok(info.width>0 && info.height>0); svgSize=info;
            assert.ok(info.refs.every(ref=>ref.startsWith('#')));
            assert.ok(!/<(?:script|image|foreignObject)\b/i.test(text));
            assert.ok(!/@import/i.test(text));
            assert.ok([...text.replaceAll('&quot;', '"').matchAll(/url\(([^)]*)\)/gi)].every(m => m[1].trim().replace(/^["']|["']$/g,'').startsWith('#')));
            assert.ok(text.includes('xmlns="http://www.w3.org/2000/svg"'));
          } else {
            assert.equal(bytes.subarray(0,8).toString('hex'),'89504e470d0a1a0a');
            assert.equal(bytes.readUInt32BE(16),Math.round(svgSize.width*3));
            assert.equal(bytes.readUInt32BE(20),Math.round(svgSize.height*3));
          }
          console.log(surface, dark?'dark':'light',mime,bytes.length,svgSize.width,svgSize.height);
        }
        await page.keyboard.press('Escape');
      }
    } finally { await ctx.close(); }
  }
} finally { await browser.close(); rmSync(temp,{recursive:true,force:true}); }
console.log('PASS mermaid-download-app');

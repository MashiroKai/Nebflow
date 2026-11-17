import { test, expect } from '@playwright/test';
import { spawn, execFileSync } from 'node:child_process';
import { createServer } from 'node:net';
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const out = process.env.STACK_DIAGNOSTIC_OUT || '/private/tmp/stack-direction-diagnostic';
let server, base;
test.beforeAll(async () => {
  mkdirSync(out, { recursive: true });
  const probe = createServer(); await new Promise(r => probe.listen(0, '127.0.0.1', r));
  const port = probe.address().port; await new Promise(r => probe.close(r));
  base = `http://127.0.0.1:${port}`;
  server = spawn('python3', ['-m', 'http.server', String(port), '--bind', '127.0.0.1', '--directory', root], { stdio: 'ignore' });
  await expect.poll(async () => { try { return (await fetch(base + '/tests/fixtures/pop-artifacts/harness.html')).status; } catch { return 0; } }).toBe(200);
});
test.afterAll(() => server?.kill());

for (const version of (process.env.STACK_VERSIONS || 'worktree').split(',')) {
 for (const viewport of [{width:375,height:812}, {width:1280,height:900}]) {
  for (const count of [3, 5]) {
   for (const side of ['assistant', 'user']) {
    test(`pop ${version} ${viewport.width} n${count} ${side}`, async ({ browser }) => {
     const results = {};
     for (const direction of ['left', 'right']) {
      const page = await browser.newPage({ viewport });
      if (version !== 'worktree' || process.env.STACK_NEGATIVE === '1') {
       let source = version === 'worktree'
        ? execFileSync('git', ['show', 'HEAD:src/main/resources/web/js/popArtifacts.js'], {cwd:root,encoding:'utf8'})
        : execFileSync('git', ['show', `${version}:src/main/resources/web/js/popArtifacts.js`], {cwd:root,encoding:'utf8'});
       if (process.env.STACK_NEGATIVE === '1') source = (await import('node:fs')).readFileSync(path.join(root, 'src/main/resources/web/js/popArtifacts.js'), 'utf8').replace('direction * 24', '(direction < 0 ? direction * 40 : direction * 24)');
       await page.route('**/src/main/resources/web/js/popArtifacts.js', r => r.fulfill({contentType:'text/javascript',body:source}));
      }
      await page.goto(base + '/tests/fixtures/pop-artifacts/harness.html');
      await page.waitForFunction(() => window.__ready === true);
      await page.evaluate(async ({count,side}) => {
       document.querySelector('#popup-chat').style.display='none';
       await window[count === 3 ? '__popStackMixedRatios' : '__popFiveImages']();
       const view = document.querySelector('#chat .pop-stack-view');
       if (side === 'user') view.closest('.row').classList.add('user');
       view.querySelectorAll(':scope > .media-layer').forEach((layer,i) => {
        const badge=document.createElement('div'); badge.textContent=String(i+1);
        Object.assign(badge.style,{position:'absolute',inset:'0',background:['#df564d','#4d91df','#53ad67','#d9a441','#9a5fd0'][i],color:'white',display:'grid',placeItems:'center',font:'bold 90px sans-serif'}); layer.appendChild(badge);
       });
       const orig=Element.prototype.animate; window.__probeAnims=[];
       Element.prototype.animate=function(...args) { const a=orig.apply(this,args); if(this.closest('.pop-stack-view')) {a.pause(); window.__probeAnims.push(a);} return a; };
      }, {count,side});
      const view=page.locator('#chat .pop-stack-view');
      const box=await view.boundingBox(); await page.mouse.move(box.x+box.width/2,box.y+box.height/2); await page.mouse.down(); await page.mouse.move(box.x+box.width/2+(direction==='left'?-75:75),box.y+box.height/2,{steps:5}); await page.mouse.up();
      expect(await page.evaluate(()=>window.__probeAnims.length)).toBeGreaterThan(0);
      const samples=[];
      for (let p=0;p<=100;p+=5) {
       await page.evaluate(p=>window.__probeAnims.forEach(a=>a.currentTime=a.effect.getTiming().duration*p/100),p);
       await page.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))));
       samples.push(await view.locator('.media-layer').evaluateAll((els,p)=>({percent:p,layers:els.map(e=>({transform:getComputedStyle(e).transform,display:getComputedStyle(e).display,zIndex:getComputedStyle(e).zIndex}))}),p));
       if ([0,25,50,75,100].includes(p)) await page.screenshot({path:`${out}/${version}-${viewport.width}-n${count}-${side}-${direction}-${p}.png`});
      }
      results[direction]=samples; await page.close();
     }
     writeFileSync(`${out}/${version}-${viewport.width}-n${count}-${side}.json`,JSON.stringify(results,null,2));
     if (version === 'worktree' || process.env.STACK_ASSERT_SYMMETRY === '1') {
      const at75 = dir => Math.abs(Number(results[dir][15].layers[0].transform.match(/^matrix\(([^)]+)\)$/)[1].split(',')[4]));
      const left = at75('left'), right = at75('right');
      const ratio = left / right;
      console.log(JSON.stringify({version,viewport:viewport.width,count,side,left,right,ratio}));
      expect(ratio).toBeGreaterThanOrEqual(0.95);
      expect(ratio).toBeLessThanOrEqual(1.05);
     }
    });
   }
  }
 }
}

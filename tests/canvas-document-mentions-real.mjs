import { createSession } from '../scripts/lib/visual-harness.mjs';
import { createRequire } from 'node:module';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { webRoot } from '../scripts/lib/visual-harness.mjs';
const require = createRequire(import.meta.url);
const pw = require('/Users/kaiyu/Claude code/Stand/node_modules/playwright');
const engine = process.env.ENGINE || 'chromium';
const browser = await pw[engine].launch();
const vault='/fixture';
const titles=['UniqueDoc','DuplicateDoc','DuplicateDoc','HeaderDoc','CodeDoc','LinkDoc','BlockDoc','IndentDoc'];
const nodes=[{project:'p',device:'self',rel:'current.md',title:'Current',stub:false},...titles.map((title,i)=>({project:'p',device:'self',rel:`d${i}/${title}.md`,title,stub:false}))];
const graph={projects:[{name:'p',device:'self',vault,workspace:vault}],nodes,edges:[],stats:{}};
const session=await createSession(browser,{baseURL:'http://stand-fixture.test/',apiStubs:{'/api/wiki/graph':{body:graph}}});
if (process.env.FAULT) {
 await session.page.route('**/js/viewers/markdown.js', async route => {
   let source = readFileSync(webRoot() + '/js/viewers/markdown.js', 'utf8');
   if (process.env.FAULT === 'duplicate') source=source.replace('.filter(items => items.length === 1)', '.filter(items => items.length >= 1)');
   const selectors={heading:'h1,h2,h3,h4,h5,h6',code:'pre,code',link:'a,'};
   const fault=selectors[process.env.FAULT];
   if (fault) {
     const original = "p.closest('pre,code,a,button,script,style,.katex,.nf-term,h1,h2,h3,h4,h5,h6')";
     const altered = 'pre,code,a,button,script,style,.katex,.nf-term,h1,h2,h3,h4,h5,h6'.replace(fault, '').replace(/,+/g, ',').replace(/^,|,$/g, '');
     assert.ok(source.includes(original), 'fault injection target present');
     source=source.replace(original, `(window.__mentionFaultVisits=(window.__mentionFaultVisits||0)+1, p.closest('${altered}'))`);
   }
   await route.fulfill({status:200,contentType:'text/javascript',body:source});
 });
}
try {
 await session.boot(); const page=session.page;
 const content='# HeaderDoc\n\nUniqueDoc DuplicateDoc `[[CodeDoc]]` [LinkDoc](https://example.org)\n\n```\n[[BlockDoc]]\n```\n\n    [[IndentDoc]]';
 await page.evaluate(({content})=>window.dispatchEvent(new CustomEvent('workspace-open-item',{detail:{id:'file:/fixture/current.md',itemType:'markdown',title:'current.md',pinned:true,content,absPath:'/fixture/current.md',size:content.length}})),{content});
 const host=page.locator('.canvas-tab-pane[data-tab-id="file:/fixture/current.md"] .canvas-md-viewer');
 await host.waitFor();
 await page.waitForFunction(()=>!!document.querySelector('.canvas-md-viewer a.nf-term'),null,{timeout:10000});
 assert.equal(await host.locator('a.nf-term').filter({hasText:'UniqueDoc'}).count(),1,'unique name');
 assert.equal(await host.locator('a.nf-term').filter({hasText:'DuplicateDoc'}).count(),0,'duplicate plain');
 assert.equal(await host.locator('h1 a.nf-term').count(),0,'heading excluded');
 assert.equal(await host.locator('code').count() >= 3,true,'inline, fenced, indented code present');
 for (const name of ['CodeDoc','BlockDoc','IndentDoc']) assert.ok((await host.locator('code').allTextContents()).some(s=>s.includes('[['+name+']]')),name+' literal present');
 if (process.env.FAULT === 'code') assert.ok(await page.evaluate(()=>window.__mentionFaultVisits > 0),'injected predicate executed');
 assert.equal(await host.locator('pre a.nf-term, code a.nf-term, pre .nf-wikilink, code .nf-wikilink, pre .nf-wikilink-term, code .nf-wikilink-term').count(),0,'code excluded');
 assert.equal(await host.locator('a[href="https://example.org"] a.nf-term').count(),0,'existing link excluded');
 await host.locator('a.nf-term').first().click();
 await page.locator('.nf-term-tip').waitFor({state:'visible'});
 console.log('PASS',engine,'real markdown → term tip; unique, duplicate, exclusions');
} finally { await browser.close(); }

import { chromium } from '/opt/homebrew/lib/node_modules/playwright/index.mjs';
import { execFileSync } from 'node:child_process';
const t = execFileSync('/Users/kaiyu/.stand/bin/stand',['token'],{encoding:'utf8'}).match(/^\s*([A-Za-z0-9_-]{20,})\s*$/m)[1];
const b = await chromium.launch({ headless: true });
const ctx = await b.newContext({ viewport:{width:1440,height:900}, colorScheme:'light', locale:'zh-CN' });
const p = await ctx.newPage();
await p.goto('http://localhost:6138/?ticket='+encodeURIComponent(t), { waitUntil:'domcontentloaded' });
await p.waitForTimeout(8500);
const info = await p.evaluate(()=>{
  const bar = document.getElementById('input-bar');
  const ab = document.getElementById('attach-btn');
  const files = [...document.querySelectorAll('input[type=file]')].map(i=>({accept:i.accept, cls:i.className, hidden:i.hidden, parent:(i.parentElement?.className||'').toString().slice(0,30)}));
  const r = ab?.getBoundingClientRect();
  const cs = ab?getComputedStyle(ab):null;
  return {
    attachBtn: ab ? { box:[Math.round(r.x),Math.round(r.y),Math.round(r.width),Math.round(r.height)], display:cs.display, visibility:cs.visibility, opacity:cs.opacity, pe:cs.pointerEvents, html: ab.outerHTML.slice(0,200) } : null,
    fileInputs: files,
    barHTML: (bar?.innerHTML||'').slice(0, 1400),
  };
});
console.log(JSON.stringify(info,null,1));
await ctx.close(); await b.close();

import { chromium } from '/opt/homebrew/lib/node_modules/playwright/index.mjs';
import { execFileSync } from 'node:child_process';
const ROOT='/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/ui-audit/reports-evidence/ui-audit-post-restart';
const FIX=ROOT+'/_data/fixtures/ui-audit-attach.png';
const t = execFileSync('/Users/kaiyu/.stand/bin/stand',['token'],{encoding:'utf8'}).match(/^\s*([A-Za-z0-9_-]{20,})\s*$/m)[1];
const out={}; const b = await chromium.launch({ headless: true });
for (const [tag, vp, mobile] of [['desktop',{width:1440,height:900},false],['mobile',{width:375,height:812},true]]) {
  const ctx = await b.newContext({ viewport: vp, isMobile: mobile, hasTouch: mobile, colorScheme:'light', deviceScaleFactor: mobile?2:1, locale:'zh-CN' });
  const p = await ctx.newPage();
  await p.goto('http://localhost:6138/?ticket='+encodeURIComponent(t), { waitUntil:'domcontentloaded' });
  await p.waitForTimeout(8500);
  // 收掉接管弹窗
  for (let i=0;i<2;i++){ const on = await p.evaluate(()=>{ const o=document.getElementById('modal-overlay'); if(!o) return false; const vis=o.classList.contains('on')&&getComputedStyle(o).display!=='none'; if(vis) (document.getElementById('takeover-retake')||{click(){}}) .click?.(); return vis; }); await p.waitForTimeout(700); if(!on) break; }
  let r={};
  try {
    const [fc] = await Promise.all([ p.waitForEvent('filechooser',{timeout:6000}), p.click('#attach-btn',{timeout:6000}) ]);
    await fc.setFiles(FIX);
    await p.waitForTimeout(2200);
    r = await p.evaluate(()=>{
      const apr = document.getElementById('attachment-preview');
      const imgs=[...document.querySelectorAll('#attachment-preview img, .ap-pane img')].map(i=>({w:i.naturalWidth,h:i.naturalHeight,src:(i.currentSrc||i.src||'').slice(-40)}));
      return { paneText:(apr?.innerText||'').trim().replace(/\s+/g,' ').slice(0,80), kids: apr?.children.length, imgs, box: (()=>{const b=apr?.getBoundingClientRect(); return b?[Math.round(b.x),Math.round(b.y),Math.round(b.width),Math.round(b.height)]:null;})() };
    });
  } catch(e) { r={err:String(e).slice(0,120)}; }
  out[tag]=r;
  await p.screenshot({ path:`${ROOT}/01-chat/${tag==='desktop'?'light-desktop':'light-mobile'}__3-attach.png`, animations:'disabled' }).catch(()=>{});
  await ctx.close();
}
await b.close(); console.log(JSON.stringify(out,null,1));

import { chromium } from '/opt/homebrew/lib/node_modules/playwright/index.mjs';
import fs from 'node:fs';
const OUT='/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/ui-audit/reports-evidence/ui-audit-post-restart/07-notify';
const b = await chromium.launch({ headless: true });
for (const [name, vp] of [['desktop',{width:1440,height:900}],['mobile',{width:375,height:812}]]) {
  const ctx = await b.newContext({ viewport: vp, isMobile: name==='mobile', hasTouch: name==='mobile', colorScheme:'light', deviceScaleFactor: name==='mobile'?2:1, locale:'zh-CN' });
  const p = await ctx.newPage();
  await p.goto('http://localhost:6138/', { waitUntil: 'domcontentloaded' });  // 不带 ticket：握手被拒 ⇒ 真实断连态
  await p.waitForTimeout(7000);
  const st = await p.evaluate(()=>{ const c=document.getElementById('conn-banner'); const r=c?.getBoundingClientRect(); return { cls:c?.className, text:(c?.innerText||'').trim(), box: r?[Math.round(r.x),Math.round(r.y),Math.round(r.width),Math.round(r.height)]:null }; });
  if (st.box && st.box[3] > 0) await p.screenshot({ path: `${OUT}/${name}__reconnect-banner-real.png`, animations:'disabled', clip: { x:0,y:0,width:vp.width,height:Math.min(160,vp.height) } });
  console.log(name, JSON.stringify(st));
  await ctx.close();
}
await b.close();

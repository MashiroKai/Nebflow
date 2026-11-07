// 长窗验证：已登录后断网，按 ws.js 的心跳口径（30s ping ×3 miss）应在≈2min 内出现重连横幅
import { chromium } from '/opt/homebrew/lib/node_modules/playwright/index.mjs';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
const OUT='/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/ui-audit/reports-evidence/ui-audit-post-restart/07-notify';
const DATA='/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/ui-audit/reports-evidence/ui-audit-post-restart/_data';
const t = execFileSync('/Users/kaiyu/.stand/bin/stand',['token'],{encoding:'utf8'}).match(/^\s*([A-Za-z0-9_-]{20,})\s*$/m)[1];
const b = await chromium.launch({ headless: true });
const ctx = await b.newContext({ viewport:{width:1440,height:900}, colorScheme:'light', locale:'zh-CN' });
const p = await ctx.newPage();
await p.goto('http://localhost:6138/?ticket='+encodeURIComponent(t), { waitUntil:'domcontentloaded' });
await p.waitForTimeout(9000);
const cdp = await ctx.newCDPSession(p); await cdp.send('Network.enable');
await cdp.send('Network.emulateNetworkConditions',{offline:true,latency:0,downloadThroughput:-1,uploadThroughput:-1});
const tl=[];
for (let i=0;i<10;i++){ await p.waitForTimeout(20000);
  const s = await p.evaluate(()=>{ const c=document.getElementById('conn-banner'); const r=c?.getBoundingClientRect(); return { cls:c?.className, text:(c?.innerText||'').trim().slice(0,30), h: r?Math.round(r.height):0 }; });
  tl.push(`${(i+1)*20}s cls=${s.cls} h=${s.h} ${s.text}`);
  if (s.h>0) { await p.screenshot({ path: `${OUT}/desktop__offline-banner-at-${(i+1)*20}s.png`, animations:'disabled' }); break; }
}
fs.writeFileSync(`${DATA}/banner-long.json`, JSON.stringify(tl,null,1));
console.log(tl.join('\n'));
await ctx.close(); await b.close();

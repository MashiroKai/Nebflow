// 附件气泡取证：走真实 attach-btn → filechooser（而不是直接 setInputFiles 到隐藏 input）
import { chromium } from '/opt/homebrew/lib/node_modules/playwright/index.mjs';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
const ROOT='/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/ui-audit/reports-evidence/ui-audit-post-restart';
const FIX=ROOT+'/_data/fixtures/ui-audit-attach.png';
const t = execFileSync('/Users/kaiyu/.stand/bin/stand',['token'],{encoding:'utf8'}).match(/^\s*([A-Za-z0-9_-]{20,})\s*$/m)[1];
const out={};
const b = await chromium.launch({ headless: true });
for (const [tag, vp, mobile] of [['desktop',{width:1440,height:900},false],['mobile',{width:375,height:812},true]]) {
  const ctx = await b.newContext({ viewport: vp, isMobile: mobile, hasTouch: mobile, colorScheme:'light', deviceScaleFactor: mobile?2:1, locale:'zh-CN' });
  const p = await ctx.newPage();
  await p.goto('http://localhost:6138/?ticket='+encodeURIComponent(t), { waitUntil:'domcontentloaded' });
  await p.waitForTimeout(8500);
  // 方式 A：直接给 input 塞文件
  const inp = await p.$('#input-area input[type=file]');
  let a = null;
  if (inp) { await inp.setInputFiles(FIX); await p.waitForTimeout(1500);
    a = await p.evaluate(()=>({ preview: !!document.querySelector('#attachment-preview .att-item, #attachment-preview img, .attach-item'), previewText:(document.getElementById('attachment-preview')?.innerText||'').trim().slice(0,60), kids: document.getElementById('attachment-preview')?.children.length })); }
  out[tag+':A_setInputFiles'] = a;
  await p.screenshot({ path: `${ROOT}/01-chat/${tag==='desktop'?'light-desktop':'light-mobile'}__3-attach.png`, animations:'disabled' }).catch(()=>{});
  // 方式 B：点 attach 按钮走 filechooser
  let bb = null;
  try {
    const [fc] = await Promise.all([ p.waitForEvent('filechooser', { timeout: 5000 }), p.click('#attach-btn', { timeout: 4000 }) ]);
    await fc.setFiles(FIX);
    await p.waitForTimeout(2000);
    bb = await p.evaluate(()=>({ preview: !!document.querySelector('#attachment-preview .att-item, #attachment-preview img, .attach-item'), previewText:(document.getElementById('attachment-preview')?.innerText||'').trim().slice(0,60), kids: document.getElementById('attachment-preview')?.children.length, html:(document.getElementById('attachment-preview')?.innerHTML||'').slice(0,160) }));
  } catch(e) { bb = { err: String(e).slice(0,90) }; }
  out[tag+':B_filechooser'] = bb;
  await p.screenshot({ path: `${ROOT}/01-chat/${tag==='desktop'?'light-desktop':'light-mobile'}__3b-attach-chooser.png`, animations:'disabled' }).catch(()=>{});
  await ctx.close();
}
await b.close();
console.log(JSON.stringify(out,null,1));

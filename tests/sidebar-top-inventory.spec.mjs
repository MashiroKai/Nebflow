#!/usr/bin/env node
// 桌面侧栏盘点：只读、不改其他面板；离线应用壳。
import { writeFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { ROOT, loadPlaywright, launchBrowser, createSession } from '../scripts/lib/visual-harness.mjs';
const out = process.env.SIDEBAR_TOP_EVIDENCE || join(ROOT.split('/.stand/worktrees/')[0],'.stand/tmp/favorites-top-unify/evidence');
mkdirSync(out,{recursive:true});
const { browser } = await launchBrowser(loadPlaywright().mod);
const results = {};
try {
 for (const theme of ['light','dark']) {
  const s = await createSession(browser,{width:1280,height:800,colorScheme:theme,apiStubs:{'/api/neblink/status':{body:{loggedIn:true,device:{id:'d1',name:'本机',platform:'macos',displayName:'我'},peers:[]}}}});
  try {
   await s.page.addInitScript(() => {
    localStorage.setItem('fm_api_mock','1');
    localStorage.setItem('fm_api_mock_seed',JSON.stringify({self:{userId:'me',neblinkId:'me@example.com',name:'Me'},users:[],friends:[],incoming:[],outgoing:[],conversations:[],messages:{},groups:[],groupInvites:[],groupMembers:{}}));
   });
   await s.boot({config:{llm:{providers:{}},features:{friends:true}},sessionId:'inventory',messages:[]});
   await s.page.evaluate(async()=> (await import('/js/activityBar.js')).enableFriendPanels());
   await s.page.evaluate(async()=> (await import('/js/canvasFavorites.js')).toggleFavoritePath({path:'/workspace/example.md',title:'Example'}));
   results[theme]={};
   for (const [name,btn,panel,header,title,search] of [
    ['messages','#messages-btn','#panel-messages','.panel-header','.panel-title','.fm-msg-search'],
    ['favorites','#favorites-btn','#panel-favorites','.cfp-bar','.cfp-title','.cfp-search'],
    ['contacts','#contacts-btn','#panel-contacts','.panel-header','.panel-title','.fm-search-block'],
    ['files','#files-btn','#panel-sessions','.explorer-header','.explorer-title','.explorer-search-row']]) {
     await s.page.click(btn); await s.page.waitForSelector(`${panel}.active`,{state:'visible'});
     results[theme][name]=await s.page.evaluate(({panel,header,title,search})=>{
      const root=document.querySelector(panel);
      const get=(selector)=>{const e=root.querySelector(selector); if(!e)return null; const s=getComputedStyle(e),r=e.getBoundingClientRect(); return {classes:e.className,display:s.display,height:s.height,padding:[s.paddingTop,s.paddingRight,s.paddingBottom,s.paddingLeft],radius:[s.borderTopLeftRadius,s.borderTopRightRadius,s.borderBottomRightRadius,s.borderBottomLeftRadius],borderBottom:s.borderBottom,fontSize:s.fontSize,fontWeight:s.fontWeight,lineHeight:s.lineHeight,color:s.color,background:s.backgroundColor,rect:{x:r.x,y:r.y,width:r.width,height:r.height}}};
      return {header:get(header),title:get(title),search:get(search)};
     },{panel,header,title,search});
     await s.page.locator(panel).screenshot({path:join(out,`inventory-${name}-${theme}.png`),animations:'disabled'});
     if (name === 'files') {
      await s.page.locator('#explorer-tree').dispatchEvent('keydown',{key:'a',bubbles:true});
      await s.page.waitForSelector('#explorer-search-row:not([hidden])',{state:'visible'});
      await s.page.evaluate(() => document.getAnimations().forEach(a => a.finish()));
      results[theme].filesExpanded = await s.page.evaluate(() => {
       const get = selector => {const e=document.querySelector(selector),c=getComputedStyle(e),r=e.getBoundingClientRect();return {classes:e.className,display:c.display,height:c.height,padding:[c.paddingTop,c.paddingRight,c.paddingBottom,c.paddingLeft],radius:[c.borderTopLeftRadius,c.borderTopRightRadius,c.borderBottomRightRadius,c.borderBottomLeftRadius],border:c.border,background:c.backgroundColor,color:c.color,fontSize:c.fontSize,fontWeight:c.fontWeight,lineHeight:c.lineHeight,rect:{x:r.x,y:r.y,width:r.width,height:r.height}};};
       return {header:get('.explorer-header'),title:get('.explorer-title'),search:get('#explorer-search-row'),input:get('#explorer-search')};
      });
      await s.page.locator(panel).screenshot({path:join(out,`inventory-files-expanded-${theme}.png`),animations:'disabled'});
     }
   }
  } finally {await s.close();}
 }
 writeFileSync(join(out,'sidebar-top-inventory.json'),JSON.stringify(results,null,2));
 console.log('sidebar-top-inventory: PASS (observations; not a conformity verdict)');
} finally {await browser.close();}

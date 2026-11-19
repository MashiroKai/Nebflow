import { chromium } from '@playwright/test';
import zlib from 'node:zlib';
import { bootChat, SID } from './_chat-face-harness.mjs';
const T=(()=>{const t=new Int32Array(256);for(let n=0;n<256;n++){let c=n;for(let k=0;k<8;k++)c=c&1?0xedb88320^(c>>>1):c>>>1;t[n]=c;}return t;})();
const crc=b=>{let c=-1;for(let i=0;i<b.length;i++)c=T[(c^b[i])&0xff]^(c>>>8);return (c^-1)>>>0;};
const ck=(ty,d)=>{const l=Buffer.alloc(4);l.writeUInt32BE(d.length,0);const bo=Buffer.concat([Buffer.from(ty,'ascii'),d]);const cr=Buffer.alloc(4);cr.writeUInt32BE(crc(bo),0);return Buffer.concat([l,bo,cr]);};
function png(w,h,[r,g,b]){const i=Buffer.alloc(13);i.writeUInt32BE(w,0);i.writeUInt32BE(h,4);i[8]=8;i[9]=2;const row=Buffer.alloc(1+w*3);for(let x=0;x<w;x++){row[1+x*3]=r;row[2+x*3]=g;row[3+x*3]=b;}const raw=Buffer.concat(Array.from({length:h},()=>row));return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),ck('IHDR',i),ck('IDAT',zlib.deflateSync(raw,{level:9})),ck('IEND',Buffer.alloc(0))]);}
const du=(w,h,c)=>'data:image/png;base64,'+png(w,h,c).toString('base64');
const imgs=[du(480,320,[60,170,120]),du(320,480,[40,110,190]),du(400,300,[190,120,50])];
const ITEMS=[{question:'长卡下滑验收：请选一个方案',options:[0,1].map(i=>({label:`方案 ${i+1}：这一档比较长的标题文字用于检验换行与裁切`,description:`第 ${i+1} 套设计说明文字，这是一段较长的描述，用来把选项卡撑到自然宽度上限以复现裁切`,preview:{type:'media',items:[{kind:'image',src:imgs[0],name:'a.png'},{kind:'image',src:imgs[1],name:'b.png'},{kind:'image',src:imgs[2],name:'c.png'}]}}))}];
const br=await chromium.launch();const ctx=await br.newContext({viewport:{width:375,height:812},isMobile:true,hasTouch:true,deviceScaleFactor:2,colorScheme:'light'});
const page=await ctx.newPage();let route=null;
await bootChat(page,{onWsRoute:w=>{route=w;}});
await page.waitForFunction(()=>document.body.classList.contains('mobile-layer'),null,{timeout:20000});
route.send(JSON.stringify({type:'askUser',sessionId:SID,agentName:'E2E',requestId:'rid-txt',items:ITEMS}));
await page.waitForSelector('#chat .option-box',{timeout:20000});await page.waitForTimeout(1200);
const r=await page.evaluate(()=>{
  const box=document.querySelector('#chat .option-box');const bubble=box.parentElement;
  const sc=box.querySelector('.option-scroll');const scs=getComputedStyle(sc);
  const clipR=sc.getBoundingClientRect().right-(parseFloat(scs.borderRightWidth)||0);
  const out=[];
  box.querySelectorAll('.option-btn').forEach((b,i)=>{
    ['.option-text','.option-desc','.option-q'].forEach(sel=>{
      const el=b.querySelector(sel)||(sel==='.option-q'?box.querySelector('.option-q'):null);if(!el)return;
      const bb=el.getBoundingClientRect();const cs=getComputedStyle(el);
      out.push({i,sel,right:+bb.right.toFixed(1),left:+bb.left.toFixed(1),w:+bb.width.toFixed(1),
        scrollW:el.scrollWidth,clientW:el.clientWidth,textOverflow:cs.textOverflow,lineClamp:cs.webkitLineClamp||cs.lineClamp,
        overflowRightPx:+(bb.right-clipR).toFixed(1),clipped:bb.right>clipR+0.5});
    });
  });
  return {clipR:+clipR.toFixed(1),boxW:+box.getBoundingClientRect().width.toFixed(1),items:out};
});
console.log(JSON.stringify(r,null,1));
await br.close();

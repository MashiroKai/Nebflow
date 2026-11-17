import { test, expect } from '@playwright/test';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { once } from 'node:events';
const root = resolve(process.cwd());
const cp = readFileSync(join(root, 'target/e2e-classpath.txt'), 'utf8').trim();
async function start() {
  const dir = mkdtempSync(join(tmpdir(), 'canvas-writer-live-'));
  const home = join(dir, 'home'), data = join(home, '.stand'); mkdirSync(data, {recursive:true});
  writeFileSync(join(data, 'nebflow.json'), JSON.stringify({workSchedule:{enabled:false},safety:{defaultMode:'auto-all'}}));
  writeFileSync(join(data, 'onboarding.json'), JSON.stringify({state:'skipped'}));
  const socket = createServer(); socket.listen(0, '127.0.0.1'); await once(socket, 'listening');
  const port = socket.address().port; await new Promise((ok, fail) => socket.close(e => e ? fail(e) : ok()));
  if ([6138,8080,8097].includes(port)) throw Error('protected port');
  const base = `http://127.0.0.1:${port}`;
  const env = {PATH:process.env.PATH,HOME:home,STAND_HOME:data,NEBFLOW_HOME:data,NEBFLOW_GATEWAY_PORT:String(port),LANG:'en_US.UTF-8'};
  const child = spawn('java',[`-Duser.home=${home}`,'-cp',cp,'nebflow.Main','--foreground','--home',data,'--port',String(port),'--no-browser'],{cwd:root,env,stdio:['pipe','pipe','pipe']});
  let log=''; child.stdout.on('data', d => log+=d); child.stderr.on('data',d=>log+=d);
  const exit = new Promise(r=>child.once('exit',(code,signal)=>r({code,signal})));
  try {
    await expect.poll(async()=>{if(child.exitCode!==null) throw Error(log.slice(-3000)); try{return (await fetch(base+'/api/health')).status}catch{return 0}}, {timeout:90000}).toBe(200);
    return {dir,data,base,port,child,exit,token:JSON.parse(readFileSync(join(data,'auth.json'),'utf8'))};
  } catch(e) { child.stdin.write('quit\n'); await exit; throw e; }
}
async function boot(browser,s,colorScheme) {
  const context=await browser.newContext({viewport:{width:1440,height:960},colorScheme});
  const page=await context.newPage();
  const r=await fetch(s.base+'/api/auth/console-ticket',{method:'POST',headers:{Authorization:`Bearer ${s.token}`}});
  expect(r.status).toBe(200); const {ticket}=await r.json();
  await page.goto(`${s.base}/?ticket=${encodeURIComponent(ticket)}`,{waitUntil:'domcontentloaded'});
  await page.waitForSelector('#activity-bar',{state:'attached',timeout:30000});
  return {context,page};
}
test('same browser canvas takeover overlay', async ({browser}, info) => {
  test.setTimeout(180000); const s=await start(); let context;
  try {
    const first=await boot(browser,s,process.env.CANVAS_SCHEME || 'light'); context=first.context;
    const a=first.page, b=await context.newPage();
    await b.goto(s.base+'/',{waitUntil:'domcontentloaded'});
    await b.waitForSelector('#activity-bar',{state:'attached',timeout:30000});
    await a.evaluate(async()=>{const m=await import('/js/canvas.js');m.openCanvas()});
    await b.evaluate(async()=>{const m=await import('/js/canvas.js');m.openCanvas()});
    await expect(a.locator('#modal-overlay.on #takeover-box')).toBeVisible();
    await expect(b.locator('#modal-overlay.on #takeover-box')).toBeVisible();
    await b.locator('#takeover-retake').click();
    await expect(b.locator('#takeover-box')).toBeHidden();
    await a.locator('#takeover-retake').click();
    await expect(a.locator('#takeover-box')).toBeHidden();
    await expect(b.locator('#takeover-box')).toBeVisible({timeout:5000});
    await b.screenshot({path:info.outputPath('cover.png')});
    await b.locator('#takeover-retake').click();
    await expect(b.locator('#takeover-box')).toBeHidden();
    // Create a real debounced local change, then preempt it before the PUT fires.
    await b.evaluate(async()=>{const m=await import('/js/canvas.js');m.openTab('local-unsynced','Unsynced',{pinned:true})});
    await a.locator('#takeover-retake').click();
    await expect(b.locator('#takeover-retake')).toContainText('放弃本地变更', {timeout:5000});
    let claims=0; b.on('request',r=>{if(r.url().endsWith('/api/canvas-writer/claim')) claims++});
    b.once('dialog', dialog=>dialog.dismiss());
    await b.locator('#takeover-retake').click();
    await expect(b.locator('#takeover-box')).toBeVisible();
    expect(claims).toBe(0);
    expect(await b.evaluate(async()=>{const m=await import('/js/canvas.js');return m.hasTab('local-unsynced')})).toBe(true);
    b.once('dialog', dialog=>dialog.accept());
    await b.locator('#takeover-retake').click();
    await expect(b.locator('#takeover-box')).toBeHidden({timeout:30000});
    expect(await b.evaluate(async()=>{const m=await import('/js/canvas.js');return m.hasTab('local-unsynced')})).toBe(false);
  } finally {
    if(context) await context.close();
    s.child.stdin.write('quit\n'); const result=await Promise.race([s.exit,new Promise(r=>setTimeout(()=>r(null),20000))]);
    if(!result) throw Error(`isolated instance did not exit: ${s.dir}, ${s.port}`);
    const probe = createServer(); probe.listen(s.port,'127.0.0.1'); await once(probe,'listening');
    await new Promise((ok,fail)=>probe.close(e=>e?fail(e):ok()));
    rmSync(s.dir,{recursive:true});
    expect(existsSync(s.dir)).toBe(false);
    console.log(`[iso] exit=${result.code} signal=${result.signal} port=${s.port} port_free=true home_removed=true`);
  }
});

test('real reader, claim, persist, preemption', async ({browser}, info) => {
  test.setTimeout(180000); const s=await start(); let a,b;
  try {
    a=await boot(browser,s,process.env.CANVAS_SCHEME || 'light'); b=await boot(browser,s,process.env.CANVAS_SCHEME || 'light');
    await a.page.evaluate(async()=>{const m=await import('/js/canvas.js');m.openCanvas()});
    const status=a.page.locator('#canvas-panel .canvas-writer-controls [role=status]');
    await expect(status).toContainText('只读');
    await a.page.screenshot({path: info.outputPath('reader.png')});
    const unauthorized=await a.page.evaluate(async()=>{const r=await fetch('/api/canvas-tabs',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({v:2,tabs:[]})});return r.status});
    expect(unauthorized).toBe(409);
    await a.page.getByRole('button',{name:'取得控制权'}).click({force:true});
    await expect(status).toContainText('可写');
    await a.page.screenshot({path: info.outputPath('writer.png')});
    const beforeVersion = await a.page.evaluate(async()=>{const r=await fetch('/api/canvas-tabs'); return r.ok ? (await r.json()).writeVersion || 0 : 0});
    const put=a.page.waitForResponse(r=>new URL(r.url()).pathname==='/api/canvas-tabs' && r.request().method()==='PUT');
    await a.page.evaluate(async()=>{const m=await import('/js/canvas.js'); m.openTab('writer-test','Writer test',{type:'generic',pinned:true})});
    expect((await put).status()).toBe(200);
    await expect.poll(()=>a.page.evaluate(async()=>{const r=await fetch('/api/canvas-tabs'); const j=await r.json();return j.tabs?.some(t=>t.id==='writer-test')})).toBe(true);
    const disk = JSON.parse(readFileSync(join(s.data,'canvas_tabs.json'),'utf8'));
    expect(disk.writeVersion).toBe(beforeVersion + 1);
    const file = join(s.dir,'old.txt'); writeFileSync(file,'fixture');
    const mutation = async (code, arg) => {
      const response = a.page.waitForResponse(r=>new URL(r.url()).pathname==='/api/canvas-tabs' && r.request().method()==='PUT', {timeout:6000});
      await a.page.evaluate(code, arg); const result = await response; expect(result.status()).toBe(200);
      if (process.env.CANVAS_DIAGNOSTIC) console.log('[put-order]', JSON.parse(result.request().postData()).tabs.map(t=>t.id));
      return JSON.parse(readFileSync(join(s.data,'canvas_tabs.json'),'utf8'));
    };
    let archive = await mutation(async ({file})=>{const m=await import('/js/canvas.js');m.openTab('file:old.txt','Old',{type:'markdown',absPath:file,pinned:false})}, {file});
    expect(archive.tabs.find(t=>t.id==='file:old.txt').pinned).toBe(false);
    archive = await mutation(async()=>{const m=await import('/js/canvas.js');m.pinTab('file:old.txt')});
    expect(archive.tabs.find(t=>t.id==='file:old.txt').pinned).toBe(true);
    archive = await mutation(async ({dir,from})=>{const m=await import('/js/canvas.js');m.retargetFileTabs(from,'new.txt',dir)}, {dir:s.dir, from:process.env.CANVAS_PATH_NEGATIVE ? 'missing.txt' : 'old.txt'});
    expect(archive.tabs.find(t=>t.id==='file:new.txt').absPath).toBe(join(s.dir,'new.txt'));
    expect(archive.tabs.map(t=>t.id).indexOf('writer-test')).toBeLessThan(archive.tabs.map(t=>t.id).indexOf('file:new.txt'));
    archive = await mutation(async()=>{
      const bar=document.querySelector('#canvas-tab-bar');
      const drag=bar.querySelector('[data-tab-id="file:new.txt"]');
      const target=bar.querySelector('[data-tab-id="writer-test"]');
      const dt=new DataTransfer();
      drag.dispatchEvent(new DragEvent('dragstart',{bubbles:true,dataTransfer:dt}));
      target.dispatchEvent(new DragEvent('drop',{bubbles:true,dataTransfer:dt,clientX:-100}));
    });
    expect(archive.tabs.map(t=>t.id).indexOf('file:new.txt')).toBeLessThan(archive.tabs.map(t=>t.id).indexOf('writer-test'));
    await b.page.getByRole('button',{name:'取得控制权'}).click({force:true});
    await expect(b.page.locator('#canvas-panel .canvas-writer-controls [role=status]')).toContainText('可写');
    // A same-browser-tab takeover may not emit a console takeover frame; exercise
    // the real stale PUT by initiating another shared mutation from the old page.
    const stale=a.page.waitForResponse(r=>new URL(r.url()).pathname==='/api/canvas-tabs' && r.request().method()==='PUT');
    await a.page.evaluate(async()=>{const m=await import('/js/canvas.js');m.openTab('writer-stale','Stale',{type:'generic',pinned:true})});
    expect((await stale).status()).toBe(409);
    await expect(status).toContainText('只读');
    await expect(status).toContainText('仅本地');
    await expect(status).toContainText('不会自动同步');
    const diskBeforeSwitch = readFileSync(join(s.data,'canvas_tabs.json'),'utf8');
    if (!process.env.CANVAS_SWITCH_NEGATIVE) await a.page.evaluate(async()=>{const m=await import('/js/canvas.js'); m.setActiveTab('writer-test')});
    await expect(a.page.locator('#canvas-content .canvas-tab-pane[data-tab-id="writer-test"]')).toHaveClass(/active/);
    await expect(status).toContainText('仅本地');
    expect(readFileSync(join(s.data,'canvas_tabs.json'),'utf8')).toBe(diskBeforeSwitch);
    await expect(a.page.locator('[data-tab-id="writer-stale"]').first()).toBeAttached();
    await a.page.screenshot({path: info.outputPath('preempted.png')});
    const blocked = await a.page.evaluate(async()=>{
      const m=await import('/js/canvas.js');
      const before=m.canvasTabEntry('writer-test');
      const snapshot={id:before.id,pinned:before.pinned,absPath:before.absPath};
      m.openTab('blocked-open','blocked',{pinned:true});
      m.closeTab('writer-test'); m.pinTab('writer-test');
      m.retargetFileTabs('writer-test','renamed','/');
      return {opened:m.hasTab('blocked-open'), exists:m.hasTab('writer-test'), pinned:before.pinned, path:before.absPath, snapshot};
    });
    expect(blocked.opened).toBe(false);
    expect(blocked.exists).toBe(true);
    expect(blocked.pinned).toBe(blocked.snapshot.pinned);
    expect(blocked.path).toBe(blocked.snapshot.absPath);
  } finally {
    if(a) await a.context.close(); if(b) await b.context.close();
    s.child.stdin.write('quit\n'); const result=await Promise.race([s.exit,new Promise(r=>setTimeout(()=>r(null),20000))]);
    if(!result) throw Error(`isolated instance did not exit: ${s.dir}, ${s.port}`);
    console.log(`[iso] exit=${result.code} signal=${result.signal} port=${s.port} home=${s.dir}`);
  }
});

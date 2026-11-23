import { test, expect } from '@playwright/test';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync, realpathSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawn, execFileSync } from 'node:child_process';
import { createServer } from 'node:net';
import { once } from 'node:events';

const root = resolve(process.cwd());
const cp = readFileSync(join(root, 'target/e2e-classpath.txt'), 'utf8').trim();

test('reader can browse without takeover; rejected write notifies once', async ({ browser }, info) => {
  test.setTimeout(180000);
  const dir = mkdtempSync(join(tmpdir(), 'canvas-reader-'));
  const home = join(dir, 'home'), data = join(home, '.stand');
  mkdirSync(data, { recursive: true });
  const resolvedData = realpathSync(data);
  const productionData = realpathSync('/Users/kaiyu/.stand');
  console.log(`[isolation] resolved_data_root=${resolvedData} production_equal=${resolvedData === productionData}`);
  if (resolvedData === productionData || resolvedData.startsWith(productionData + '/')) throw Error('refusing production data root');
  writeFileSync(join(data, 'nebflow.json'), JSON.stringify({workSchedule:{enabled:false},safety:{defaultMode:'auto-all'}}));
  writeFileSync(join(data, 'onboarding.json'), JSON.stringify({state:'skipped'}));
  const sid='reader-fixture', now=Date.now();
  mkdirSync(join(data,'sessions'), {recursive:true});
  writeFileSync(join(data,'sessions','_index.json'), JSON.stringify({activeId:sid,sessions:[{id:sid,name:'Reader fixture',createdAt:now,updatedAt:now,hasUnread:false,agentName:'Nebula'}],folders:[]}));
  writeFileSync(join(data,'sessions',`${sid}.json`), JSON.stringify([{role:'user',content:'reader fixture message',timestamp:now}]));
  writeFileSync(join(data,'sessions',`${sid}.ui.json`), JSON.stringify([{type:'user',text:'reader fixture message',attachments:[{type:'file',name:'reader-fixture.md',path:join(home,'reader-fixture.md')}],timestamp:now}]));
  writeFileSync(join(home,'reader-fixture.md'),'# Real reader fixture\n\nREAL_READER_CONTENT\n');
  const socket = createServer(); socket.listen(0, '127.0.0.1'); await once(socket, 'listening');
  const port = socket.address().port; await new Promise((ok, fail) => socket.close(e => e ? fail(e) : ok()));
  if ([6138,8080,8097].includes(port)) throw Error('protected port');
  const base = `http://127.0.0.1:${port}`;
  const env = {PATH:process.env.PATH,HOME:home,STAND_HOME:data,NEBFLOW_HOME:data,NEBFLOW_GATEWAY_PORT:String(port),LANG:'en_US.UTF-8'};
  const child = spawn('java', [`-Duser.home=${home}`, '-cp', cp, 'nebflow.Main', '--foreground', '--home', data, '--port', String(port), '--no-browser'], {cwd:root,env,stdio:['pipe','pipe','pipe']});
  let log = ''; child.stdout.on('data', d => log += d); child.stderr.on('data', d => log += d);
  const exit = new Promise(r => child.once('exit', (code,signal) => r({code,signal})));
  let context;
  try {
    await expect.poll(async () => { if (child.exitCode !== null) throw Error(log.slice(-3000)); try { return (await fetch(base+'/api/health')).status; } catch { return 0; } }, {timeout:90000}).toBe(200);
    const token = JSON.parse(readFileSync(join(data,'auth.json'),'utf8'));
    const ticketResponse = await fetch(base+'/api/auth/console-ticket',{method:'POST',headers:{Authorization:`Bearer ${token}`}});
    expect(ticketResponse.status).toBe(200); const {ticket} = await ticketResponse.json();
    context = await browser.newContext({viewport:{width:375,height:812},isMobile:true,hasTouch:true,userAgent:'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1'});
    if (process.env.READER_BASELINE) {
      const source=execFileSync('git',['show','50bd47a06:src/main/resources/web/js/canvas.js'],{cwd:root,encoding:'utf8'});
      await context.route('**/js/canvas.js', route => route.fulfill({status:200,contentType:'application/javascript',body:source}));
    }
    const page = await context.newPage(); const errors = [];
    page.on('response', async r => { if (new URL(r.url()).pathname.endsWith('/canvas.js')) { const body=await r.text().catch(()=> ''); console.log('[canvas-module]', r.url(), 'status',r.status(),'retired-takeover-modal',body.includes('takeoverModal'),'no-takeover-surface',!/takeover/i.test(body)); } });
    page.on('pageerror', e => errors.push(`pageerror: ${e.stack || String(e)}`));
    page.on('console', m => { if(m.type()==='error') errors.push(`console.error: ${m.text()} ${JSON.stringify(m.location())}`); });
    page.on('response', r => { if(r.status()>=400) console.log('[http-error]',r.status(),new URL(r.url()).pathname); });
    await page.goto(`${base}/?ticket=${encodeURIComponent(ticket)}`, {waitUntil:'domcontentloaded'});
    await page.waitForSelector('#activity-bar',{state:'attached',timeout:30000});
    await page.waitForFunction(() => document.readyState === 'complete');
    if (process.env.READER_MODAL_NEGATIVE || process.env.READER_MODAL_FIXED) {
      await page.evaluate(async()=>{const m=await import('/js/canvas.js'); m.initCanvas();});
      if (process.env.READER_MODAL_NEGATIVE) await page.locator('#takeover-box').waitFor({state:'visible',timeout:10000});
    }
    await page.screenshot({path:process.env.READER_SCREENSHOT || info.outputPath('initial.png')});
    await expect(page.locator('#modal-overlay.on #takeover-box')).toBeHidden();
    if (process.env.READER_MODAL_NEGATIVE || process.env.READER_MODAL_FIXED) {
      await expect(page.locator('#takeover-box')).toBeHidden({timeout:5000});
      return;
    }
    await expect(page.locator('#chat .row').first()).toBeVisible({timeout:30000});
    await expect(page.locator('#chat')).toContainText('reader fixture message');
    console.log('[mobile-message-rows]', await page.locator('#chat .row').count());
    const puts = []; page.on('request', r => { if (new URL(r.url()).pathname === '/api/canvas-tabs' && r.method() === 'PUT') puts.push(r.url()); });
    const file = page.locator('#chat .fc-name').filter({hasText:'reader-fixture.md'});
    await expect(file).toBeVisible({timeout:15000});
    console.log('[attachment-name-title]',await file.getAttribute('title'));
    await page.evaluate(() => { window.__readerOpen=[]; window.addEventListener('workspace-open-item', e => window.__readerOpen.push(e.detail)); });
    await file.click();
    console.log('[attachment-open-event]',JSON.stringify(await page.evaluate(()=>window.__readerOpen)));
    await page.screenshot({path:'/private/tmp/reader-attachment-click.png'});
    await expect(page.locator('#canvas-content')).toContainText('REAL_READER_CONTENT',{timeout:15000});
    await page.screenshot({path:process.env.READER_FILE_SCREENSHOT || info.outputPath('file-open.png')});
    expect(puts).toHaveLength(0);
    await page.evaluate(() => { window.__readerNotices=[]; const old=window.__showToast; window.__showToast=(...args)=>{window.__readerNotices.push(args); return old?.(...args);}; });
    // Simulate a stale cached writer epoch; the isolated gateway itself rejects it.
    await page.route('**/api/canvas-writer?*', route => route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({role:'writer',epoch:99999})}));
    await page.reload({waitUntil:'domcontentloaded'});
    await page.waitForSelector('#activity-bar',{state:'attached'});
    await page.evaluate(() => { window.__readerNotices=[]; const old=window.__showToast; window.__showToast=(...args)=>{window.__readerNotices.push(args); return old?.(...args);}; });
    const rejection = page.waitForResponse(r => new URL(r.url()).pathname==='/api/canvas-tabs' && r.request().method()==='PUT' && r.status()===409);
    await page.evaluate(async () => { const m=await import('/js/canvas.js'); m.openTab('writer-fixture','writer fixture',{pinned:true}); });
    await rejection;
    // 2026-11-18 作者令：写者席位被服务端拒授时只出**真实保存失败文案**，
    // 「控制权已失效／请重新取得」这类接管措辞随状态机退役，绝不再出现。
    // 2026-11-22 作者令：保存失败静默保留，写路径不再发任何通知。
    await page.waitForTimeout(1000);
    expect(await page.evaluate(() => window.__readerNotices.length)).toBe(0);
    expect(await page.evaluate(() => window.__readerNotices.filter(x => /控制权|接管|让出|保存失败/.test(String(x[0]))).length)).toBe(0);
    console.log('[reader-browser-errors]', JSON.stringify(errors));
    expect(errors).toEqual([]);
    await page.screenshot({path:info.outputPath('rejected.png')});
  } finally {
    if (context) await context.close();
    child.stdin.write('quit\n');
    const result = await Promise.race([exit,new Promise(r=>setTimeout(()=>r(null),60000))]);
    if (!result) throw Error(`isolated instance did not exit: ${dir}, ${port}`);
    const probe = createServer(); probe.listen(port,'127.0.0.1'); await once(probe,'listening'); await new Promise((ok,fail)=>probe.close(e=>e?fail(e):ok()));
    rmSync(dir,{recursive:true}); console.log(`[iso] exit=${result.code} port=${port} port_free=true home_removed=true`);
  }
});

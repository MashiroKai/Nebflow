import { test, expect } from '@playwright/test';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { once } from 'node:events';

const root = resolve(process.cwd());
const cp = readFileSync(join(root, 'target/e2e-classpath.txt'), 'utf8').trim();

test('reader can browse without takeover; rejected write notifies once', async ({ browser }, info) => {
  test.setTimeout(180000);
  const dir = mkdtempSync(join(tmpdir(), 'canvas-reader-'));
  const home = join(dir, 'home'), data = join(home, '.stand');
  mkdirSync(data, { recursive: true });
  writeFileSync(join(data, 'nebflow.json'), JSON.stringify({workSchedule:{enabled:false},safety:{defaultMode:'auto-all'}}));
  writeFileSync(join(data, 'onboarding.json'), JSON.stringify({state:'skipped'}));
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
    context = await browser.newContext({viewport:{width:1440,height:960}});
    const page = await context.newPage(); const errors = [];
    page.on('pageerror', e => errors.push(e.stack || String(e)));
    await page.goto(`${base}/?ticket=${encodeURIComponent(ticket)}`, {waitUntil:'domcontentloaded'});
    await page.waitForSelector('#activity-bar',{state:'attached',timeout:30000});
    await page.evaluate(async () => { const m = await import('/js/canvas.js'); m.openCanvas(); });
    await page.locator('#takeover-box').waitFor({state:'visible',timeout:5000}).catch(() => {});
    await page.screenshot({path:'/private/tmp/canvas-reader-evidence.png'});
    await expect(page.locator('#modal-overlay.on #takeover-box')).toBeHidden();
    const puts = []; page.on('request', r => { if (new URL(r.url()).pathname === '/api/canvas-tabs' && r.method() === 'PUT') puts.push(r.url()); });
    const opened = await page.evaluate(async () => { const m = await import('/js/canvas.js'); m.openTab('file:fixture.md','fixture.md',{type:'markdown',absPath:'/fixture.md'}); return m.hasTab('file:fixture.md'); });
    expect(opened).toBe(true);
    await expect(page.locator('[data-tab-id="file:fixture.md"]').first()).toBeAttached();
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
    await expect.poll(() => page.evaluate(() => window.__readerNotices.filter(x => x[0] === 'Canvas 控制权已失效，请重新取得。').length)).toBe(1);
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

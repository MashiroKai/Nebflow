// FTI-SCN-006 consumer regression. Mock WS + actual routed web tree, not backend/GUI acceptance.
// Requires isolated, repo-version Playwright and supervised browser; NOT RUN.
import assert from 'node:assert/strict';
import { chromium, webkit } from 'playwright';
import { readFileSync } from 'node:fs';
import { resolve, join, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
const root = resolve(fileURLToPath(new URL('.', import.meta.url)), '..');
const web = join(root, 'src/main/resources/web');
const sid = 'todo-create-sheet-fixture';
const mime = {'.html':'text/html','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml','.json':'application/json','.woff2':'font/woff2'};
for (const engine of [chromium, webkit]) for (const theme of ['light','dark']) {
  const browser = await engine.launch();
  try {
    const context = await browser.newContext({ viewport:{width:375,height:812}, deviceScaleFactor:2, hasTouch:true, isMobile:true, colorScheme:theme });
    try {
      const page = await context.newPage();
      const errors = [];
      page.on('pageerror', e => errors.push(e.message));
      page.on('console', msg => { if (msg.type() === 'error') errors.push(msg.text()); });
      await page.addInitScript(() => { localStorage.setItem('nebflow_token','fixture'); localStorage.setItem('nebflow_locale','zh-CN'); localStorage.setItem('stand_sw','0'); try { Object.defineProperty(window.crypto, 'randomUUID', { value: undefined, configurable: true }); } catch { /* keep the native method */ } });
      await page.route('**/*', route => {
        const path = decodeURIComponent(new URL(route.request().url()).pathname);
        if (path.startsWith('/api/') || path === '/agents/Nebula/agent.json') return route.fulfill({status:200,contentType:'application/json',body:path === '/api/sessions' ? JSON.stringify({sessions:[{id:sid,name:'root'}],activeId:sid}) : '{}'});
        const file = normalize(join(web, path === '/' ? '/index.html' : path));
        if (!file.startsWith(web + '/')) return route.fulfill({status:403,body:'forbidden'});
        try { return route.fulfill({status:200,contentType:mime[extname(file)] || 'application/octet-stream',body:readFileSync(file)}); }
        catch { errors.push(`missing fixture asset: ${path}`); return route.fulfill({status:404,body:'not found'}); }
      });
      const frames = [];
      let attempts = 0;
      await page.routeWebSocket(/\/ws/, ws => {
        const config = () => ws.send(JSON.stringify({type:'configData',config:'{"features":{}}',configured:true,onboarding:'done',models:[],defaults:{}}));
        const sessions = () => ws.send(JSON.stringify({type:'sessionList',sessions:[{id:sid,name:'root'}],folders:[],activeId:sid}));
        config(); sessions();
        ws.onMessage(raw => { let m; try { m = JSON.parse(raw); } catch { return; } frames.push(m); if (m.type === 'ping') ws.send(JSON.stringify({type:'pong'})); else if (m.type === 'getConfig') config(); else if (m.type === 'getSessions' || m.type === 'listSessions') sessions(); else if (m.type === 'getHistory') ws.send(JSON.stringify({type:'historyPage',sessionId:m.sessionId,messages:[],hasMore:false,offset:0,total:0})); else if (m.type === 'listScheduledTasks') ws.send(JSON.stringify({type:'scheduledTaskList',sessionId:sid,scope:'all',tasks:[{id:'existing',sessionId:'other-session',kind:'todo',name:'已存在',detail:'保留',content:'保留',triggerAt:1700000000000,createdAt:123}]})); else if (m.type === 'createScheduledTask') { attempts++; if (attempts === 1) ws.send(JSON.stringify({type:'error',msgType:m.type,requestId:m.requestId,sessionId:sid,message:'rejected'})); else ws.send(JSON.stringify({type:'scheduledTaskCreated',requestId:m.requestId,sessionId:sid,task:{...m,id:m.requestId,createdAt:123,triggerAt:1700000000000}})); } });
      });
      // Virtual origin: all requests are intercepted; WebKit blocks port 1.
      await page.goto('http://localhost:8380/');
      await page.waitForFunction(async id => { const s = (await import('/js/state.js')).default; return document.body.classList.contains('mobile-layer') && s.activeSessionId === id && s.ws?.readyState === 1; }, sid, {timeout:20000});
      const v4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
      assert.equal(await page.evaluate(() => typeof window.crypto.randomUUID), 'undefined', 'fixture: native randomUUID is unavailable (non-secure context)');
      await page.locator("[data-chrome='tasks']").click();
      await page.waitForFunction(async () => (await import('/js/todoStore.js')).todoStore.dataSource === 'backend' && (await import('/js/todoStore.js')).todoStore.allItems().some(item => item.id === 'existing'));
      const tasks = page.locator(".mobile-page[data-page='tasks']");
      await page.waitForFunction(() => document.querySelector(".mobile-page[data-page='tasks']")?.classList.contains('live'));
      const before = await tasks.evaluate(el => ({height:el.scrollHeight, top:el.getBoundingClientRect().top, doc:document.documentElement.scrollHeight}));
      await tasks.locator('.ta-fab').click();
      // Proposed public test hook, to be supplied by the implementation; never fall back to the old full-page editor.
      const sheet = page.locator('[data-todo-create-sheet]');
      await sheet.waitFor({state:'visible',timeout:5000});
      const box = await sheet.boundingBox();
      assert.ok(box && box.height > 0 && box.y > 0 && box.y + box.height <= 812, 'visible bottom sheet');
      const pageBox = await tasks.boundingBox();
      assert.ok(pageBox && Math.abs(box.y + box.height - pageBox.y - pageBox.height) <= 1, 'sheet aligns to page bottom');
      assert.equal(await page.locator('[data-todo-create-overlay]').evaluate(el => getComputedStyle(el).backgroundColor), 'rgba(0, 0, 0, 0)', 'outside region is transparent');
      assert.ok(await sheet.getByRole('textbox').count() >= 2, 'separate name and detail fields');
      const after = await tasks.evaluate(el => ({height:el.scrollHeight, top:el.getBoundingClientRect().top, doc:document.documentElement.scrollHeight}));
      assert.deepEqual(after,before,'sheet does not reflow covered list');
      await sheet.getByRole('textbox').first().fill('新待办');
      await sheet.getByRole('button',{name:/取消|cancel/i}).click();
      assert.equal(frames.filter(f => f.type === 'createScheduledTask').length,0,'cancel must not send create');
      await tasks.locator('.ta-fab').click();
      await sheet.waitFor({state:'visible'});
      await sheet.getByRole('textbox').first().fill('新待办');
      await sheet.getByRole('textbox').nth(1).fill('第一行\n第二行');
      await page.evaluate(async () => { const s = (await import('/js/state.js')).default; window.__testSocket = s.ws; s.ws = null; });
      await sheet.getByRole('button',{name:/保存|save/i}).click();
      await sheet.getByRole('alert').waitFor({state:'visible'});
      assert.equal(frames.filter(f => f.type === 'createScheduledTask').length, 0, 'offline does not enter lossy queue');
      assert.equal(await sheet.getByRole('textbox').first().inputValue(), '新待办');
      await page.evaluate(async () => { (await import('/js/state.js')).default.ws = window.__testSocket; });
      await sheet.getByRole('button',{name:/保存|save/i}).click();
      await sheet.getByRole('alert').waitFor({state:'visible'});
      assert.match(await sheet.getByRole('alert').textContent(), /尚未确认/);
      assert.equal(await sheet.getByRole('textbox').first().inputValue(), '新待办');
      assert.equal(await sheet.getByRole('textbox').nth(1).inputValue(), '第一行\n第二行');
      await sheet.getByRole('button',{name:/保存|save/i}).click();
      await sheet.waitFor({state:'detached'});
      const writes = frames.filter(f => f.type === 'createScheduledTask');
      assert.equal(writes.length,2,'rejected create retries');
      assert.equal(writes[0].requestId,writes[1].requestId,'retry retains identity');
      assert.match(writes[0].requestId, v4, 'requestId stays a server-accepted v4 UUID without crypto.randomUUID');
      assert.equal(writes[0].kind,'todo');
      assert.equal(writes[0].sessionId,sid);
      assert.equal(writes[0].name,'新待办');
      assert.equal(writes[0].detail,'第一行\n第二行');
      assert.equal(writes[0].content,'第一行\n第二行');
      assert.equal(writes[0].priority,0);
      // Distinct tagged list: creation from that list must retain its tag.
      await page.evaluate(async () => {
        const { todoStore } = await import('/js/todoStore.js');
        todoStore.__seed({tagList:[{id:'first',name:'First',color:'#777777'},{id:'chosen',name:'Chosen',color:'#888888'}],itemList:[]});
      });
      const row = tasks.locator('.ta-row-tag').filter({hasText:'Chosen'});
      await row.waitFor({state:'visible'});
      await row.click();
      const list = page.locator('.mobile-page[data-page="todo-apple-list"]');
      await list.waitFor({state:'visible'});
      await list.locator('.ta-pill-btn').click();
      await sheet.waitFor({state:'visible'});
      assert.match(await sheet.locator('.ta-create-option').nth(1).textContent(), /Chosen/);
      await sheet.getByRole('textbox').first().fill('Tagged item');
      await sheet.getByRole('button',{name:/保存|save/i}).click();
      await sheet.waitFor({state:'detached'});
      const tagged = await page.evaluate(async () => (await import('/js/todoStore.js')).todoStore.allItems().find(item => item.title === 'Tagged item'));
      assert.equal(tagged?.tagId, 'chosen');
      // FTI-SCN-008: actual mobile detail replies through primary without consuming its draft/attachments.
      await list.locator('.ta-item-body').filter({hasText:'Tagged item'}).click();
      const detailPage = page.locator('.mobile-page[data-page="todo-apple-item"]');
      await detailPage.waitFor({state:'visible'});
      await page.evaluate(async () => {
        const { chatViews, setActiveView } = await import('/js/chatView.js');
        chatViews.primary.dom.input.value = 'existing composer draft';
        chatViews.primary.pendingAttachments = [{type:'ref',name:'existing attachment',data:'keep'}];
        window.__otherView = { id:'other', sessionId:'other-session', dom:{input:document.createElement('textarea')}, pendingAttachments:[] };
        window.__otherView.dom.input.value = 'other draft';
        setActiveView(window.__otherView);
      });
      const reply = detailPage.getByRole('textbox',{name:'回复这个待办…'});
      await reply.fill('reply body');
      await detailPage.getByRole('button',{name:'发送'}).click();
      await page.waitForFunction(() => document.querySelector('.ta-reply textarea')?.value === '');
      const replies = frames.filter(f => typeof f.content === 'string' && f.content.includes('reply body'));
      assert.equal(replies.length,1);
      assert.equal(replies[0].sessionId,sid);
      assert.deepEqual(replies[0].attachments,[]);
      const draftState = await page.evaluate(async () => { const {chatViews} = await import('/js/chatView.js'); return [chatViews.primary.dom.input.value, chatViews.primary.pendingAttachments[0]?.name]; });
      assert.deepEqual(draftState,['existing composer draft','existing attachment']);
      assert.deepEqual(await page.evaluate(async () => { const {activeView} = await import('/js/chatView.js'); return [activeView === window.__otherView, window.__otherView.dom.input.value]; }), [true,'other draft']);
      await reply.fill('failed reply');
      await detailPage.getByRole('button',{name:'发送'}).click();
      await detailPage.locator('.ta-reply [role="alert"]').waitFor({state:'visible'});
      assert.equal(await reply.inputValue(), 'failed reply', 'busy primary retains reply');
      assert.equal(frames.filter(f => typeof f.content === 'string' && f.content.includes('failed reply')).length, 0);
      // FTI-SCN-009: a parked retry pinned to another session (or an edited row) must have
      // an explicit way out; otherwise the sheet stays read-only forever.
      const parked = '11111111-2222-4333-8444-555555555555';
      await page.evaluate((id) => sessionStorage.setItem('stand.todo.pendingCreate', JSON.stringify({tagId:null,title:'pending title',notes:'pending notes',priority:0,sessionId:'other-session',requestId:id})), parked);
      await page.reload();
      await page.waitForFunction(async id => { const s = (await import('/js/state.js')).default; return document.body.classList.contains('mobile-layer') && s.activeSessionId === id && s.ws?.readyState === 1; }, sid, {timeout:20000});
      await page.locator("[data-chrome='tasks']").click();
      await page.waitForFunction(() => document.querySelector(".mobile-page[data-page='tasks']")?.classList.contains('live'));
      const tasks2 = page.locator(".mobile-page[data-page='tasks']");
      await tasks2.locator('.ta-fab').click();
      const sheet2 = page.locator('[data-todo-create-sheet]');
      await sheet2.waitFor({state:'visible'});
      const title2 = sheet2.getByRole('textbox').first();
      assert.equal(await title2.inputValue(), 'pending title');
      assert.equal(await title2.evaluate(el => el.readOnly), true, 'a parked retry locks its identity');
      const discard = sheet2.getByRole('button',{name:/放弃重试|discard retry/i});
      await discard.waitFor({state:'visible'});
      await discard.click();
      assert.equal(await title2.evaluate(el => el.readOnly), false, 'discard unlocks the sheet without deleting the row');
      const beforeDiscardWrites = frames.filter(f => f.type === 'createScheduledTask').length;
      await title2.fill('after discard');
      await sheet2.getByRole('button',{name:/保存|save/i}).click();
      await sheet2.waitFor({state:'detached'});
      const afterDiscard = frames.filter(f => f.type === 'createScheduledTask');
      assert.equal(afterDiscard.length, beforeDiscardWrites + 1);
      assert.notEqual(afterDiscard.at(-1).requestId, parked, 'a discarded retry gets a fresh identity');
      assert.match(afterDiscard.at(-1).requestId, v4);
      assert.deepEqual(errors,[],'no console/page errors');
    } finally { await context.close(); }
  } finally { await browser.close(); }
}
console.log('FTI-SCN-006 mobile create sheet consumer matrix passed');
console.log('FTI-SCN-008 mobile reply keeps primary draft and attachments');
console.log('FTI-SCN-009 parked retry has an explicit discard path');

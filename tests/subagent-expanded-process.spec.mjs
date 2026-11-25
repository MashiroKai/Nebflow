import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
const web = process.env.WEB_TREE || join(dirname(fileURLToPath(import.meta.url)), '..', 'src/main/resources/web');
const root = 'expanded-root', sid = 'delegate-expanded';
const mime = { '.html':'text/html', '.js':'text/javascript', '.css':'text/css', '.json':'application/json', '.svg':'image/svg+xml', '.woff2':'font/woff2' };
const thinking = Array.from({length: 9}, (_, i) => `思考步骤${i + 1}：检查结果`).join('\n\n');
const history = [{type:'user', text:'请检查'}, {type:'ai', thinking, text:''}, {type:'tool', label:'Read', content:'工具结果完整内容', input:{file_path:'/demo'}}, {type:'ai', text:'已完成', terminal:true}];
async function boot(page, replay) {
  await page.addInitScript(() => { localStorage.setItem('nebflow_token','e2e-token'); localStorage.setItem('nebflow_locale','zh-CN'); });
  await page.route('**/*', route => { const u = new URL(route.request().url()); let p = decodeURIComponent(u.pathname); if (p === '/') p = '/index.html'; if (p.startsWith('/api/')) return route.fulfill({status:200, contentType:'application/json', body:'{}'}); const f = normalize(join(web,p)); if (!f.startsWith(web)) return route.fulfill({status:403}); try { return route.fulfill({status:200, contentType:mime[extname(f)] || 'application/octet-stream', body:process.env.RULE_MUTANT === '6' && p === '/js/utils.js' ? readFileSync(f,'utf8').replace("if (bareProcess || card.closest('[data-nf-bare-process-rows=\"1\"]')) {", 'if (false) {') : readFileSync(f)}); } catch { return route.fulfill({status:404}); } });
  await page.routeWebSocket(/\/ws/, ws => { const config = () => ws.send(JSON.stringify({type:'configData',config:'{}',configured:true,onboarding:'done'})); const sessions = () => ws.send(JSON.stringify({type:'sessionList',sessions:[{id:root,name:'root',agentName:'Nebula'}],folders:[],activeId:root})); config(); sessions(); ws.onMessage(raw => {const m = JSON.parse(raw); if (m.type === 'getConfig') config(); if (m.type === 'getSessions' || m.type === 'listSessions') sessions(); if (m.type === 'getHistory') ws.send(JSON.stringify({type:'historyPage',sessionId:m.sessionId,messages:replay && m.sessionId === sid ? history : [],hasMore:false,offset:0,total:replay && m.sessionId === sid ? history.length : 0}));}); });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async id => { const s = (await import('/js/state.js')).default; return s.activeSessionId === id && s.ws?.readyState === 1; }, root);
  await page.waitForFunction(() => !document.documentElement.classList.contains('nf-boot-gate'), null, {timeout:25000});
  await page.evaluate(async id => (await import('/js/bgAgentPopup.js')).openStepPopup(id,'Coder','检查任务'), sid);
  await expect(page.locator('.flow-agent-chat:visible')).toBeVisible();
}
async function frame(page, type, extra = {}) {
  await page.evaluate(async frame => { const s = (await import('/js/state.js')).default; s.ws.onmessage({data:JSON.stringify(frame)}); }, {type,agentId:sid,nodeSessionId:sid,rootSessionId:root,sessionId:sid,name:'Coder',...extra});
}
async function renderLive(page) {
  await frame(page,'agentStart',{taskDescription:'检查任务'});
  await frame(page,'agentThinking',{delta:thinking});
  await frame(page,'agentToolStart',{label:'Read'});
  await frame(page,'agentToolEnd',{label:'Read',summary:'完成',content:'工具结果完整内容',isError:false});
}
async function verify(page) {
  const panel = page.locator('.flow-agent-chat:visible');
  await expect(panel.locator('.thinking-content')).toContainText('思考步骤9');
  const thinkingEl = panel.locator('.thinking-content').first();
  expect(await thinkingEl.evaluate(el => ({max:getComputedStyle(el).maxHeight, height:el.clientHeight, scroll:el.scrollHeight}))).toMatchObject({max:'none'});
  const card = panel.locator('.tool-card').first();
  await expect(card).toContainText('工具结果完整内容');
  await expect(panel.locator('.turn-header, .thinking-label.collapsible, .row.nf-tucked, .tool-card[aria-expanded]')).toHaveCount(0);
  await card.click();
  await expect(card).toContainText('工具结果完整内容');
  await expect(card).not.toHaveAttribute('aria-expanded');
  expect(await thinkingEl.evaluate(el => el.scrollHeight <= el.clientHeight + 1)).toBe(true);
}
test('子 agent 分页工具正文始终展开', async ({page}) => {
  await boot(page, false);
  await page.evaluate(async sid => {
    const {chatViews,activeView,setActiveView} = await import('/js/chatView.js');
    const {mountHistoryPageChunked} = await import('/js/persistence.js');
    const saved = activeView, view = chatViews['bgagent-' + sid];
    setActiveView(view);
    try { await mountHistoryPageChunked([{type:'tool',label:'Read',content:'分页工具完整结果'}], {}, {onBuilt: container => view.dom.chat.prepend(...container.children)}); } finally {setActiveView(saved);}
  }, sid);
  const card = page.locator('.flow-agent-chat:visible .tool-card').first();
  await expect(card).toContainText('分页工具完整结果');
  await expect(card).not.toHaveAttribute('aria-expanded');
  await card.click(); await expect(card).toContainText('分页工具完整结果');
});
for (const width of [390,1280]) for (const theme of ['light','dark']) for (const state of ['running','done','replay']) {
  test(`规则6 ${width} ${theme} ${state}`, async ({page}) => {
    await page.setViewportSize({width,height:850});
    await boot(page,state === 'replay');
    if (state === 'replay') {
      await page.reload();
      await page.waitForFunction(async id => { const s = (await import('/js/state.js')).default; return s.activeSessionId === id && s.ws?.readyState === 1; }, root);
      await page.waitForFunction(() => !document.documentElement.classList.contains('nf-boot-gate'), null, {timeout:25000});
      await page.evaluate(async id => (await import('/js/bgAgentPopup.js')).openStepPopup(id,'Coder','检查任务'), sid);
    }
    await page.evaluate(t => document.documentElement.setAttribute('data-theme',t), theme);
    if (state !== 'replay') {
      await renderLive(page);
      if (state === 'done') await frame(page,'agentDone', {result:'已完成'});
    }
    await verify(page);
    await page.evaluate(() => Promise.all(document.getAnimations().filter(a => a.playState === 'running' && a.effect?.getTiming().iterations !== Infinity).map(a => a.finished.catch(() => {}))));
    if (process.env.SUBAGENT_SHOTS) await page.screenshot({path:`${process.env.SUBAGENT_SHOTS}/subagent-${width}-${theme}-${state}.png`});
  });
}

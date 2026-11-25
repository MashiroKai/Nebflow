import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
const web = join(dirname(fileURLToPath(import.meta.url)), '..', 'src/main/resources/web');
const sid = 'buffered-root';
const mime = { '.html':'text/html', '.js':'text/javascript', '.css':'text/css', '.json':'application/json', '.svg':'image/svg+xml', '.woff2':'font/woff2' };
async function boot(page, history = []) {
  await page.addInitScript(() => { localStorage.setItem('nebflow_token','e2e-token'); localStorage.setItem('nebflow_locale','zh-CN'); });
  await page.route('**/*', route => {
    const u = new URL(route.request().url()); let p = decodeURIComponent(u.pathname); if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) return route.fulfill({ status:200, contentType:'application/json', body:'{}' });
    const f = normalize(join(web,p)); if (!f.startsWith(web)) return route.fulfill({ status:403, body:'' });
    try { const body = process.env.BUFFER_BASE === '1' && p === '/js/chat.js' ? execFileSync('git', ['show', '4976bf692:src/main/resources/web/js/chat.js'], { cwd:web }) : readFileSync(f); return route.fulfill({ status:200, contentType:mime[extname(f)] || 'application/octet-stream', body }); } catch { return route.fulfill({ status:404, body:'' }); }
  });
  await page.routeWebSocket(/\/ws/, ws => {
    const config = () => ws.send(JSON.stringify({ type:'configData', config:'{}', configured:true, onboarding:'done' }));
    const sessions = () => ws.send(JSON.stringify({ type:'sessionList', sessions:[{ id:sid, name:'root', agentName:'Nebula' }], folders:[], activeId:sid }));
    config(); sessions(); ws.onMessage(raw => { const m = JSON.parse(raw); if (m.type === 'getConfig') config(); if (m.type === 'getSessions' || m.type === 'listSessions') sessions(); if (m.type === 'getHistory') ws.send(JSON.stringify({ type:'historyPage', sessionId:m.sessionId, messages:history, hasMore:false, offset:0, total:history.length })); });
  });
  await page.goto('http://127.0.0.1:8188/');
  await page.waitForFunction(async sid => { const s = (await import('/js/state.js')).default; return s.activeSessionId === sid && s.ws?.readyState === 1; }, sid);
  await page.waitForFunction(() => !document.documentElement.classList.contains('nf-boot-gate'), null, { timeout: 25000 });
  await page.evaluate(async sid => { const s = (await import('/js/state.js')).default; s.turnExpecting[sid] = true; s.busySessionIds.add(sid); }, sid);
}
async function frame(page, obj) { await page.evaluate(async o => { const s = (await import('/js/state.js')).default; s.ws.onmessage({ data:JSON.stringify(o) }); }, { sessionId:sid, ...obj }); }
test('root 实际帧：报告正文收口前不可见，toolStart 收口后整条弹出', async ({ page }) => {
  await boot(page);
  await frame(page,{type:'textDelta', isRoot:true, delta:'外部隐藏<report>报告'});
  await frame(page,{type:'textDelta', isRoot:true, delta:'正文</report>尾部隐藏'});
  await page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));
  await expect(page.locator('#chat')).not.toContainText('报告正文');
  await frame(page,{type:'toolStart', label:'Read', toolId:'test-tool'});
  const bubble = page.locator('#chat .row.ai:not(.thinking-row) .bubble.ai').last();
  await expect(bubble).toBeVisible();
  await expect(bubble).toContainText('报告正文');
  await expect(bubble).not.toContainText('外部隐藏');
  await expect(bubble).toHaveClass(/nf-pop/);
  await frame(page,{type:'textDelta', isRoot:true, delta:'<report>第二段</report>'});
  await frame(page,{type:'roundComplete'});
  const bubbles = page.locator('#chat .row.ai:not(.thinking-row) .bubble.ai');
  await expect(bubbles).toHaveCount(2);
  await expect(bubbles.nth(1)).toContainText('第二段');
  await expect(bubbles.nth(1)).toHaveClass(/nf-pop/);
  await frame(page,{type:'textDelta', isRoot:true, delta:'<report>第三段</report>'});
  await frame(page,{type:'done'});
  await expect(bubbles).toHaveCount(3);
  await expect(bubbles.nth(2)).toContainText('第三段');
  await expect(bubbles.nth(2)).toHaveClass(/nf-pop/);
  const live = await bubbles.allTextContents();
  const replay = await page.context().newPage();
  await boot(replay, [
    {type:'ai', text:'外部隐藏<report>报告正文</report>尾部隐藏', isRoot:true},
    {type:'ai', text:'<report>第二段</report>', isRoot:true},
    {type:'ai', text:'<report>第三段</report>', isRoot:true, terminal:true}
  ]);
  const replayBubbles = replay.locator('#chat .row.ai:not(.thinking-row) .bubble.ai');
  await expect.poll(async () => await replayBubbles.count()).toBe(3);
  await expect.poll(async () => (await replayBubbles.allTextContents()).map(x => x.trim())).toEqual(live.map(x => x.trim()));
});

for (const width of [390, 1280]) for (const theme of ['light', 'dark']) {
  test(`截图 ${width} ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 850 });
    await boot(page);
    await page.evaluate(async t => { document.documentElement.setAttribute('data-theme', t); const chat = await import('/js/chat.js'); chat.renderUserBubble('请整理结果', [], Date.now()); }, theme);
    await expect(page.locator('#chat .row.user')).toBeVisible();
    await page.locator('#chat .row.user').evaluate(el => Promise.all(el.getAnimations({subtree:true}).map(a => a.finished.catch(() => {}))));
    await frame(page,{type:'thinkingDelta', delta:'正在整理相关文件'});
    await frame(page,{type:'textDelta', isRoot:true, delta:'<report>这是一条完整的回复，结束后一次显示。</report>'});
    await page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));
    const dir = process.env.BUFFER_SHOTS;
    if (width === 390 && theme === 'dark') console.log('DARK-DIAGNOSTIC', JSON.stringify(await page.evaluate(() => {
      const el = document.querySelector('#chat .row.user');
      const chain = []; for (let p = el; p; p = p.parentElement) { const s = getComputedStyle(p); chain.push({ selector: p.tagName.toLowerCase() + (p.id ? '#' + p.id : '') + (p.className && typeof p.className === 'string' ? '.' + p.className.trim().replace(/\s+/g, '.') : ''), opacity:s.opacity, filter:s.filter, backdropFilter:s.backdropFilter, animation:s.animationName, transform:s.transform }); }
      return chain;
    })));
    if (dir) await page.screenshot({ path:`${dir}/${process.env.BUFFER_BASE === '1' ? 'base' : 'after'}-${width}-${theme}-running.png` });
    await frame(page,{type:'done'});
    await expect(page.locator('#chat .bubble.ai').last()).toBeVisible();
    await page.locator('#chat .bubble.ai').last().evaluate(el => Promise.all(el.getAnimations().map(a => a.finished.catch(() => {}))));
    if (dir) await page.screenshot({ path:`${dir}/${process.env.BUFFER_BASE === '1' ? 'base' : 'after'}-${width}-${theme}-done.png` });
  });
}

test('主会话工作线和摘要不可点击，注入消息按序直接显示', async ({ page }) => {
  await boot(page);
  await frame(page,{type:'thinkingDelta', delta:'思考中'});
  await expect(page.locator('#chat .nf-workline .nf-chevron')).toHaveCount(0);
  await page.evaluate(async () => { const m = await import('/js/chat.js'); m.renderInjectedBubble('补充一', 'mail', Date.now(), 'mail'); m.renderInjectedBubble('补充二', 'mail', Date.now(), 'mail'); });
  await expect(page.locator('#chat [data-nf-injection-stack]')).toHaveCount(0);
  const injections = page.locator('#chat .bubble.injected');
  await expect(injections).toHaveCount(2);
  await expect(injections.nth(0)).toBeVisible();
  await expect(injections.nth(1)).toBeVisible();
  await expect(injections.nth(0)).toContainText('补充一');
  await expect(injections.nth(1)).toContainText('补充二');
  await frame(page,{type:'textDelta', isRoot:true, delta:'<report>结论</report>'});
  await frame(page,{type:'done'});
  await expect(page.locator('#chat .turn-header[role="button"]')).toHaveCount(0);
  await expect(page.locator('#chat .turn-header [class*="chevron"]')).toHaveCount(0);
  await expect(injections.nth(0)).toBeVisible();
});

test('主会话 report 内 human 正文不产生可点击过程 details', async ({ page }) => {
  await boot(page);
  await frame(page,{type:'textDelta', isRoot:true, delta:'<report>过程叙述\n\n```human\n用户结论\n```\n\n后续叙述</report>'});
  await frame(page,{type:'done'});
  await expect(page.locator('#chat .bubble.ai details.nf-human-details')).toHaveCount(0);
  await expect(page.locator('#chat .bubble.ai').last()).toContainText('用户结论');
  const replay = await page.context().newPage();
  await boot(replay, [{type:'ai', isRoot:true, terminal:true, text:'<report>过程叙述\n\n```human\n用户结论\n```\n\n后续叙述</report>'}]);
  await expect(replay.locator('#chat .bubble.ai')).toContainText('用户结论');
  await expect(replay.locator('#chat .bubble.ai details.nf-human-details')).toHaveCount(0);
});

test('历史回放直接显示注入且摘要不提供展开', async ({ page }) => {
  await boot(page, [
    {type:'user', text:'请求'},
    {type:'user', injected:true, source:'mail', eventType:'mail', text:'补充一'},
    {type:'tool', label:'Read', content:'工具结果', input:{file_path:'/x'}},
    {type:'user', injected:true, source:'mail', eventType:'mail', text:'补充二'},
    {type:'ai', isRoot:true, text:'<report>最终结果</report>', terminal:true}
  ]);
  const items = page.locator('#chat .bubble.injected');
  await expect(items).toHaveCount(2);
  await expect(items.nth(0)).toBeVisible();
  await expect(items.nth(1)).toBeVisible();
  await expect(items.nth(0)).toContainText('补充一');
  await expect(items.nth(1)).toContainText('补充二');
  await expect(page.locator('#chat [data-nf-injection-stack], #chat .turn-header[role="button"], #chat .turn-header .nf-chevron')).toHaveCount(0);
});

test('仅思考不产生正文空气泡', async ({ page }) => {
  await boot(page);
  await frame(page,{type:'thinkingDelta', delta:'思考内容'});
  await frame(page,{type:'done'});
  await expect(page.locator('#chat .row.ai:not(.thinking-row) .bubble.ai')).toHaveCount(0);
});

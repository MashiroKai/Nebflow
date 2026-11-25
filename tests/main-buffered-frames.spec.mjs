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
    await page.evaluate(t => { document.documentElement.dataset.theme = t; document.documentElement.setAttribute('data-theme', t); }, theme);
    await frame(page,{type:'thinkingDelta', delta:'正在整理相关文件'});
    await frame(page,{type:'textDelta', isRoot:true, delta:'<report>这是一条完整的回复，结束后一次显示。</report>'});
    await page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));
    const dir = process.env.BUFFER_SHOTS;
    if (dir) await page.screenshot({ path:`${dir}/${process.env.BUFFER_BASE === '1' ? 'base' : 'after'}-${width}-${theme}-running.png` });
    await frame(page,{type:'done'});
    if (dir) await page.screenshot({ path:`${dir}/${process.env.BUFFER_BASE === '1' ? 'base' : 'after'}-${width}-${theme}-done.png` });
  });
}

test('仅思考不产生正文空气泡', async ({ page }) => {
  await boot(page);
  await frame(page,{type:'thinkingDelta', delta:'思考内容'});
  await frame(page,{type:'done'});
  await expect(page.locator('#chat .row.ai:not(.thinking-row) .bubble.ai')).toHaveCount(0);
});

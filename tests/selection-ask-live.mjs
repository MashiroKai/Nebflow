// selection-ask-live.mjs — 选区 Ask **真机现场**探针（隔离网关 + loopback mock LLM +
// 本 worktree 的真实前端资源，零 stub）。
//
// 选区收敛批（作者令 2026-11-18 逐字「选中的复制和引用去掉 就只保留ask 然后是选中就
// 在气泡的旁边 你发的是右边 我发的是左边 出现一个ask按钮 点击就可以出现canvas Ask
// 那样的对话框提问」）的现场读数：
//   ① 选区菜单**零出场**（复制/引用不进选区面）：右键/触按命中区 ⇒ `.fm-context-menu` 数 = 0
//   ② `.selection-ask-btn` 出场，且**侧别与气泡同侧**（我方气泡=右 / 对方气泡=左，
//      逐条 rect 读数：按钮与气泡的中点比较 + 会话列中点）
//   ③ 点击 ⇒ **共用件** Ask 对话框（`.ask-dock.selection-dock`）在场；
//   ④ 真投递链路：mock LLM 命中 + `askTextDelta`/`askDone` 帧 + 对话框内答案文本
// 双视口（桌面 1200×900 / 手机 390×844，手机带 touch + 全程录像）。
//
// 运行（worktree 根）：node tests/selection-ask-live.mjs
// 前置：repo 内 node_modules（playwright）、离线 assembly jar（缺省取主检出 target；
//      可用 STAND_JAR 覆盖）。子进程一律协作退出（stdin 'quit'），端口全部自选自回收。
import { spawn } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import http from 'node:http';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import { establishSession } from './helpers/session-rail.mjs';

// repo 由本文件位置推导（任意 worktree 可直接跑，不写死临时路径）。
const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const root = `/private/tmp/qa-selask-live-${process.pid}`;
const privateHome = `/private/tmp/qa-selask-user-${process.pid}`;
const artifacts = `/private/tmp/selask-live-evidence-${process.pid}`;
const jar = process.env.STAND_JAR || '/Users/kaiyu/Claude code/Stand/target/scala-3.5.2/stand-assembly-2026.11.8-beta.1.jar';
const JAVA = process.env.STAND_JAVA || '/Library/Java/JavaVirtualMachines/temurin-23.jdk/Contents/Home/bin/java';
if (root === '/Users/kaiyu/.stand' || !root.startsWith('/private/tmp/')) throw Error('unsafe root');
mkdirSync(privateHome, { recursive: true }); mkdirSync(artifacts, { recursive: true });
const freePort = () => new Promise((resolve, reject) => {
  const server = net.createServer(); server.on('error', reject);
  server.listen(0, '127.0.0.1', () => { const port = server.address().port; server.close(() => resolve(port)); });
});
const listen = server => new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
let hits = 0, gateway, browser;
const ANSWER = '这是一段确定的模拟解释。';
const mock = http.createServer((req, res) => {
  if (req.method !== 'POST') return res.writeHead(404).end();
  let body = ''; req.on('data', d => { body += d; });
  req.on('end', () => {
    hits++;
    const text = JSON.stringify({ id: 'mock', object: 'chat.completion.chunk', model: 'mock-1', choices: [{ index: 0, delta: { content: ANSWER }, finish_reason: null }] });
    const done = JSON.stringify({ id: 'mock', object: 'chat.completion.chunk', model: 'mock-1', choices: [{ index: 0, delta: {}, finish_reason: 'stop' }] });
    res.writeHead(200, { 'content-type': 'text/event-stream' }); res.end(`data: ${text}\n\ndata: ${done}\n\ndata: [DONE]\n\n`);
  });
});

/** 在一次选区动作后读「按钮 × 气泡 × 会话列」的几何与侧别（③ 的判据面）。 */
const PROBE = () => {
  const btn = document.querySelector('.selection-ask-btn');
  const sel = getSelection();
  const startEl = sel && sel.rangeCount ? (sel.getRangeAt(0).startContainer.nodeType === 1 ? sel.getRangeAt(0).startContainer : sel.getRangeAt(0).startContainer.parentElement) : null;
  const bubble = startEl ? startEl.closest('.bubble') : null;
  const chat = document.getElementById('chat');
  const r = btn && !btn.hidden ? btn.getBoundingClientRect() : null;
  const br = bubble ? bubble.getBoundingClientRect() : null;
  const cr = chat ? chat.getBoundingClientRect() : null;
  return {
    btnVisible: !!r,
    btnSide: btn ? btn.dataset.askSide || null : null,
    bubbleSide: btn ? btn.dataset.bubbleSide || null : null,
    btnRect: r ? { left: Math.round(r.left), right: Math.round(r.right), top: Math.round(r.top), bottom: Math.round(r.bottom) } : null,
    bubbleRect: br ? { left: Math.round(br.left), right: Math.round(br.right), width: Math.round(br.width) } : null,
    chatMid: cr ? Math.round(cr.left + cr.width / 2) : null,
    // 同侧判据（逐条 rect 读数，不写死左右）：右气泡 ⇒ 钮在气泡右侧间隙外；左气泡 ⇒ 钮在左侧。
    gapRight: r && br ? Math.round(r.left - br.right) : null,
    gapLeft: r && br ? Math.round(br.left - r.right) : null,
    menuCount: document.querySelectorAll('.fm-context-menu').length,
    menuLabels: Array.from(document.querySelectorAll('.fm-context-menu button')).map(b => b.textContent),
    selected: sel ? sel.toString() : '',
  };
};

/** 在被选气泡里造 Range 选区（真 Selection 对象；桌面另发真右键、手机另发真触摸）。 */
const SELECT_IN_ROW = (rowId, start, len) => {
  const row = document.querySelector(`#chat .row[data-msg-id="${rowId}"]`);
  if (!row) return { ok: false, why: 'no row' };
  const bubble = row.querySelector('.bubble');
  if (!bubble) return { ok: false, why: 'no bubble' };
  const walker = document.createTreeWalker(bubble, NodeFilter.SHOW_TEXT);
  let n = walker.nextNode();
  while (n && !n.textContent.trim()) n = walker.nextNode();
  if (!n) return { ok: false, why: 'no text' };
  const end = Math.min(n.textContent.length, start + len);
  const r = document.createRange(); r.setStart(n, Math.min(start, n.textContent.length)); r.setEnd(n, end);
  const s = getSelection(); s.removeAllRanges(); s.addRange(r);
  return { ok: true, text: r.toString(), bubbleRect: bubble.getBoundingClientRect().toJSON() };
};

try {
  await listen(mock);
  const mockPort = mock.address().port, port = await freePort();
  if ([6138, 8080, 8097].includes(port) || [6138, 8080, 8097].includes(mockPort)) throw Error('protected port');
  const env = { PATH: '/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin', HOME: privateHome, NEBFLOW_HOME_DIR: root, MOCK_PORT: String(mockPort) };
  mkdirSync(root, { recursive: true });
  writeFileSync(`${root}/stand.json`, JSON.stringify({ workSchedule: { enabled: false }, safety: { defaultMode: 'auto-all' }, llm: { providers: { mock: { baseUrl: `http://127.0.0.1:${mockPort}/v1/`, apiKey: 'fixture-only-placeholder', protocol: 'openai', models: [{ id: 'mock-1', contextWindow: 128000 }] } }, fallback: { preferred: 'mock/mock-1' } } }));
  mkdirSync(`${root}/agents/Nebula`, { recursive: true });
  writeFileSync(`${root}/agents/Nebula/agent.json`, JSON.stringify({ name: 'Nebula', model: { preferred: 'mock/mock-1' } }));
  writeFileSync(`${root}/onboarding.json`, JSON.stringify({ state: 'skipped' }));
  console.log(`repo=${repo}`);
  console.log(`root=${root} gatewayPort=${port} mockPort=${mockPort} jar=${jar}`);
  gateway = spawn(JAVA, ['--add-opens', 'java.base/java.lang=ALL-UNNAMED', '-Dnebflow.webdist=dev', '-cp', `${repo}/src/main/resources:${jar}`, 'nebflow.Main', '--home', root, '--port', String(port), '--no-browser', '--foreground', 'start'], { cwd: repo, env: { ...env, NEBFLOW_GATEWAY_PORT: String(port) }, stdio: ['pipe', 'pipe', 'pipe'] });
  console.log(`gatewayPid=${gateway.pid}`);
  let log = '';
  gateway.stdout.on('data', d => { log += d; }); gateway.stderr.on('data', d => { log += d; });
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(Error(`gateway readiness timeout: ${log.slice(-1600)}`)), 90000);
    const check = d => { if (String(d).includes('gateway listening on')) { clearTimeout(timer); resolve(); } };
    gateway.stdout.on('data', check); gateway.stderr.on('data', check);
    gateway.once('exit', code => { clearTimeout(timer); reject(Error(`gateway exited ${code}: ${log.slice(-1600)}`)); });
  });
  const token = JSON.parse(readFileSync(`${root}/auth.json`, 'utf8'));
  const base = `http://127.0.0.1:${port}`;
  const created = await fetch(`${base}/api/sessions`, { method: 'POST', headers: { 'content-type': 'application/json', authorization: `Bearer ${token}` }, body: JSON.stringify({ name: 'selection fixture' }) });
  if (!created.ok) throw Error(`session create ${created.status}`);
  browser = await chromium.launch({ headless: true });
  const report = [];
  for (const mobile of [false, true]) {
    const label = mobile ? 'mobile' : 'desktop';
    const context = await browser.newContext({ viewport: mobile ? { width: 390, height: 844 } : { width: 1200, height: 900 }, isMobile: mobile, hasTouch: mobile, recordVideo: { dir: artifacts, size: mobile ? { width: 390, height: 844 } : { width: 1200, height: 900 } } });
    const page = await context.newPage();
    page.on('pageerror', error => console.log('pageerror', String(error)));
    await page.addInitScript(() => {
      const Native = window.WebSocket;
      window.__frames = [];
      window.WebSocket = class extends Native { constructor(...a) { super(...a); window.__socket = this; this.addEventListener('message', e => { try { window.__frames.push(JSON.parse(e.data)); } catch {} }); } };
    });
    await establishSession(base, token, page, { waitUntil: 'domcontentloaded' });
    await page.waitForFunction(() => window.__socket?.readyState === 1, null, { timeout: 30000 });
    await page.waitForFunction(() => window.__frames.some(f => f.type === 'sessionList' && f.activeId), null, { timeout: 30000 });
    const activeId = await page.evaluate(() => window.__frames.find(f => f.type === 'sessionList' && f.activeId).activeId);
    console.log(`[${label}] activeSession=${activeId}`);
    const warm = await fetch(`${base}/api/sessions/${activeId}/turn`, { method: 'POST', headers: { 'content-type': 'application/json', authorization: `Bearer ${token}` }, body: JSON.stringify({ content: 'fixture warmup', timeoutSec: 30 }) });
    console.log(`[${label}] warmup status=${warm.status} hits=${hits}`);
    if (!warm.ok || hits === 0) throw Error('warmup did not reach loopback model');
    await page.waitForFunction(() => window.__frames.some(f => f.type === 'historyPage'), null, { timeout: 30000 });
    // 两条真实行：我方（user，右侧气泡）+ 对方（ai，左侧气泡）—— ③ 左右各测一次。
    await page.evaluate(async () => {
      const chat = await import('/js/chat.js');
      chat.renderUserBubble('before selected after', [], Date.now(), { msgId: 'selection-fixture-user' });
      const persist = await import('/js/persistence.js');
      persist.restoreFromBackendHistory([{ type: 'ai', text: 'before selected after' }], { scrollToBottom: false, busyTail: false });
    });
    await page.waitForTimeout(400);

    // ③ 左右各一次：我方 user 行（右侧气泡）+ 对方 ai 行（左侧气泡）。
    const legs = [['user', 'right'], ['ai', 'left']];
    for (const [role, expectSide] of legs) {
      const rowId = role === 'user'
        ? 'selection-fixture-user'
        : await page.evaluate(() => document.querySelector('#chat .row.ai')?.dataset.msgId || null);
      if (!rowId) { console.log(`[${label}/${role}] SKIP 无 .row.ai 行`); continue; }
      // 目标行先滚到会话列中间（手机档 ai 行会落在视口外——视口外的行上做手势等于点在
      // 别处，读数无效），**滚完再落选区**（先选区后滚会让 rect 与手势落点错位）。
      await page.evaluate((id) => {
        const row = document.querySelector(`#chat .row[data-msg-id="${id}"]`);
        if (row) row.scrollIntoView({ block: 'center' });
      }, String(rowId));
      await page.waitForTimeout(150);
      // 造真 Selection（Range）——桌面后接真右键、手机后接真触摸（均须菜单零出场）。
      const sel = await page.evaluate(([rowIdStr, fn]) => new Function(`return (${fn})`)()(rowIdStr, 0, 6), [String(rowId), SELECT_IN_ROW.toString()]);
      console.log(`[${label}/${role}] select=${JSON.stringify(sel)}`);
      // 真手势：桌面右键 / 手机触按 —— **落在选区自身矩形内**（作者的真实动作：在
      // 选中的文字上呼出动作；点在选区外 = 原生「点空白处」，那条路会清掉选区并落到
      // 无选区的消息级菜单，不是选区面，本探针不拿它当判据）。
      const spot = await page.evaluate(() => {
        const r = getSelection().getRangeAt(0).getBoundingClientRect();
        return { x: Math.round(r.left + Math.min(r.width / 2, Math.max(r.width - 2, 1))), y: Math.round(r.top + r.height / 2) };
      });
      console.log(`[${label}/${role}] gestureSpot=${JSON.stringify(spot)}`);
      // 选中即出（**无需任何手势**）：selectionchange 腿的现场读数。
      let pre = null;
      try { await page.waitForFunction(() => { const b = document.querySelector('.selection-ask-btn'); return !!b && !b.hidden; }, null, { timeout: 4000 }); } catch {}
      pre = await page.evaluate(`(${PROBE.toString()})()`);
      console.log(`[${label}/${role}] onSelect=${JSON.stringify(pre)}`);
      await page.screenshot({ path: `${artifacts}/${label}-${role}-onSelect.png` }); // 选中即出按钮的现场图
      if (mobile) {
        // 手机档的真实动线 = 长按选中（原生）→ **点那枚按钮**。点在选中文字上属原生
        // 「轻点收起选区」（headless 里也会收起）⇒ 按钮跟着收，那不是选区面判据。
        // 这里如实登记这条原生行为，再走真实动线。
        const cdp = await context.newCDPSession(page);
        await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: spot.x, y: spot.y }] });
        await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
        const afterTap = await page.evaluate(`(${PROBE.toString()})()`);
        console.log(`[${label}/${role}] tapOnSelectedText=${JSON.stringify({ selected: afterTap.selected, btnVisible: afterTap.btnVisible, menuCount: afterTap.menuCount })}`);
        // 复原选中态（真实用户长按后选区仍在），再点按钮。
        await page.evaluate(([rowIdStr, fn]) => new Function(`return (${fn})`)()(rowIdStr, 0, 6), [String(rowId), SELECT_IN_ROW.toString()]);
        await page.waitForTimeout(120);
      } else {
        // 桌面：真右键落在**选区自身矩形内**（原生保留选区），选区面必须零菜单。
        await page.mouse.click(spot.x, spot.y, { button: 'right' });
      }
      let probe = null;
      try { await page.waitForFunction(() => { const b = document.querySelector('.selection-ask-btn'); return !!b && !b.hidden; }, null, { timeout: 4000 }); } catch {}
      probe = await page.evaluate(`(${PROBE.toString()})()`);
      // 按钮可点（真 click）⇒ 共用件对话框在场 + 答案文本落地。
      let opened = null, answer = null, frames = null;
      if (probe.btnVisible) {
        await page.locator('.selection-ask-btn').click();
        try { await page.locator('.ask-dock.selection-dock').waitFor({ timeout: 4000 }); } catch {}
        opened = await page.evaluate(() => {
          const p = document.querySelector('.ask-dock.selection-dock');
          return p ? { className: p.className, rect: p.getBoundingClientRect().toJSON(), closed: p.classList.contains('closed') } : null;
        });
        if (opened) {
          try { await page.locator('.ask-dock.selection-dock .ask-bub').getByText(ANSWER).first().waitFor({ timeout: 20000 }); } catch (e) { console.log(`[${label}/${role}] answer wait failed: ${String(e).slice(0, 200)}`); }
          answer = await page.locator('.ask-dock.selection-dock .ask-bub').allTextContents();
          await page.screenshot({ path: `${artifacts}/${label}-${role}-dialog.png` }); // 共用件对话框 + 答案落地现场图
        }
        frames = await page.evaluate(() => window.__frames.filter(f => /^ask/.test(f.type)).map(f => ({ type: f.type, sessionId: f.sessionId, question: f.question ? f.question.slice(0, 60) : undefined, len: typeof f.text === 'string' ? f.text.length : undefined })));
        // 收起（下一次选区前回到干净态；也验 Esc 通路）。
        await page.keyboard.press('Escape');
        await page.waitForTimeout(200);
      }
      const line = {
        viewport: label, role, expectSide, rowId,
        btnVisible: probe.btnVisible, btnSide: probe.btnSide, bubbleSide: probe.bubbleSide,
        sameSide: probe.btnSide != null && probe.btnSide === expectSide,
        gapRight: probe.gapRight, gapLeft: probe.gapLeft,
        btnRect: probe.btnRect, bubbleRect: probe.bubbleRect, chatMid: probe.chatMid,
        menuCount: probe.menuCount, menuLabels: probe.menuLabels,
        selected: probe.selected,
        dlgOpen: !!(opened && !opened.closed), dlgClass: opened ? opened.className : null,
        answerText: (answer || []).join(' | ').slice(0, 120),
        frames,
      };
      report.push(line);
      console.log(`[${label}/${role}] ${JSON.stringify(line)}`);
      const shot = `${artifacts}/${label}-${role}.png`;
      await page.screenshot({ path: shot });
    }
    console.log(`[${label}] askFrames=${await page.evaluate(() => window.__frames.filter(f => /^ask/.test(f.type)).length)} mockHits=${hits}`);
    const video = page.video(); await context.close(); console.log(`[${label}] video=${await video.path()}`);
  }
  writeFileSync(`${artifacts}/selection-ask-live-report.json`, JSON.stringify({ repo, jar, report, mockHits: hits }, null, 2));
  console.log(`report=${artifacts}/selection-ask-live-report.json`);
} finally {
  if (browser) await browser.close();
  if (gateway && gateway.exitCode === null) {
    const exit = new Promise(resolve => gateway.once('exit', resolve)); gateway.stdin.write('quit\n');
    const result = await Promise.race([exit.then(() => 'exited'), new Promise(resolve => setTimeout(() => resolve('NOT_EXITED'), 30000))]);
    console.log(`gatewayShutdown=${result} pid=${gateway.pid}`);
    if (result !== 'exited') process.exitCode = 1;
  }
  await new Promise(resolve => mock.close(resolve));
  console.log(`mockClosed=true hits=${hits} root=${root} artifacts=${artifacts}`);
}

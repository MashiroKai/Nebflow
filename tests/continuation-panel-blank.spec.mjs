// continuation-panel-blank.spec.mjs — 续聊 delegate 会话「详情面板正文空白」验收 + 实拍
// （2026-11-01 报障批；作者三次纠正口径：这是 bug、与 output 块掩码无关、
//   徽章行能渲染而正文不渲染 ⇒ 死在取数或渲染环节）。
//
// 判据链（本脚本 = 渲染腿证据；取数腿证据见批报告 + 实盘普查）：
//   ① 改前实盘载荷（fixtures/continuation-panel/injected-only.json，取自
//      ~/.stand/sessions/delegate-nebflow-8bbf8cf7.ui.json 逐字节）= 22 条注入行、
//      零 ai/tool 行 ⇒ 面板只剩归因徽章行（body 零行）。
//   ② 同一会话**修后**应得的载荷（fixtures/continuation-panel/with-body.json =
//      注入行原文 + 该会话真实 transcript 按录制层规则重投影的 305 条 ai/tool 行）
//      ⇒ 面板正文恢复（agent 文本 + 工具卡 + 思考块 + 归因行都在）。
//   两腿都走**真实前端**：真 bgAgentPopup.openStepPopup + 真 WS getHistory 往返 +
//   真 restoreFromBackendHistory，无桩无替身；浏览器双引擎（Chromium + WebKit）×
//   深浅双主题，全程零 console.error / pageerror。
//
// Run: node tests/continuation-panel-blank.spec.mjs
// 落: evidence/20261101_continuation-panel-blank/shots/<engine>-<theme>-<scene>.png

// playwright 解析：本 worktree 有 node_modules 就用本地，否则回落到主仓（仓内既有
// 实拍脚本同款显式路径约定）。
const PW = await (async () => {
  try { return await import('../node_modules/playwright/index.mjs'); }
  catch { return await import('/Users/kaiyu/Claude code/Stand/node_modules/playwright/index.mjs'); }
})();
const { chromium, webkit } = PW;
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const FIX = join(HERE, 'fixtures', 'continuation-panel');
const OUT = join(HERE, '..', 'evidence', '20261101_continuation-panel-blank', 'shots');
mkdirSync(OUT, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const ROOT_SID = 'cont-root-session';
const DELEGATE_SID = 'delegate-nebflow-8bbf8cf7';
const TASK_DESC = 'continuation: 统一按钮+玻璃设计从稿变实施（本批为重飞）';

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};

const load = (n) => JSON.parse(readFileSync(join(FIX, n), 'utf8'));

async function makePage(browserType, theme, payload, sink) {
  const browser = await browserType.launch();
  const context = await browser.newContext({
    viewport: { width: 1440, height: 900 },
    colorScheme: theme,
    deviceScaleFactor: 2,
  });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push('console.error: ' + m.text()); });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    if (p.startsWith('/agents/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(normalize(WEB))) return route.fulfill({ status: 403, body: 'x' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch { return route.fulfill({ status: 404, body: 'nf' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: ROOT_SID }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
      else if (m.type === 'getSessions' || m.type === 'listSessions') ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: ROOT_SID }));
      else if (m.type === 'getHistory') {
        // 真实回执形状（SessionStore.getHistoryPage → historyPage 帧）。
        if (sink) sink.push({ sessionId: m.sessionId, limit: m.limit });
        ws.send(JSON.stringify({
          type: 'historyPage',
          sessionId: m.sessionId,
          messages: payload.messages,
          offset: 0,
          total: payload.messages.length,
          hasMore: false,
        }));
      }
    });
  });
  // 端口 8931：非受限端口（WebKit 拒 <1024），路由全 mock，host 任意。
  await page.goto('http://127.0.0.1:8931/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 20000 });
  await page.waitForTimeout(300);
  return { browser, page, errors };
}

/** 面板入口等价路径：直接调面板行点击所走的同一函数（真实 bgAgentPopup 打开腿）。 */
async function openSessionPanel(page) {
  await page.evaluate(async ({ sid, desc }) => {
    const pop = await import('/js/bgAgentPopup.js');
    pop.openStepPopup(sid, 'nebflow', desc);
  }, { sid: DELEGATE_SID, desc: TASK_DESC });
  await page.waitForSelector('.flow-agent-modal .flow-agent-chat', { timeout: 10000 });
  await page.waitForTimeout(1200); // 历史回放 + rAF 升级批落定
}

async function audit(page) {
  return page.evaluate(() => {
    const chat = document.querySelector('.flow-agent-modal .flow-agent-chat');
    const q = (sel) => chat.querySelectorAll(sel).length;
    const injected = [...chat.querySelectorAll('.bubble.injected')];
    return {
      ai: q('.row.ai'),
      tool: q('.row.tool'),
      thinking: q('.thinking-row'),
      turnHeader: q('.turn-header'),
      injected: injected.length,
      injectedLabels: injected.slice(0, 3).map((b) => (b.textContent || '').trim().slice(0, 40)),
      badgePills: q('.nf-rcpt-pill'),
      // 正文体积：去掉徽章/标签行后仍可见的文本长度（>0 = 有正文）。
      textLen: (chat.textContent || '').replace(/\s+/g, ' ').trim().length,
      aiTextSample: (chat.querySelector('.row.ai .bubble.ai')?.textContent || '').replace(/\s+/g, ' ').slice(0, 60),
      strip: (document.querySelector('.bgagent-delegate-strip')?.textContent || '').replace(/\s+/g, ' ').slice(0, 60),
      scrollH: chat.scrollHeight,
    };
  });
}

const blank = load('injected-only.json');
const withBody = load('with-body.json');

for (const [engineName, browserType] of [['chromium', chromium], ['webkit', webkit]]) {
  for (const theme of ['dark', 'light']) {
    const tag = `${engineName}-${theme}`;
    const receipt = { engine: engineName, theme, scenes: {} };

    // ── 场景 A：改前实盘载荷（报障现场——只剩归因徽章行）───────────────
    {
      const sent = [];
      const { browser, page, errors } = await makePage(browserType, theme, { messages: blank }, sent);
      await openSessionPanel(page);
      const a = await audit(page);
      ok(`A getHistory 已发 (${tag})`, sent.some((s) => s.sessionId === DELEGATE_SID), JSON.stringify(sent.map((s) => s.sessionId)));
      ok(`A 注入行 22 条照常渲染 (${tag})`, a.injected === 22, `injected=${a.injected} labels=${a.injectedLabels.join('|')}`);
      ok(`A 正文零行（复现报障） (${tag})`, a.ai === 0 && a.tool === 0 && a.thinking === 0, `ai=${a.ai} tool=${a.tool} think=${a.thinking}`);
      ok(`A 归因徽章行在场 (${tag})`, a.injectedLabels.length > 0 && /DELEGATE|BACKGROUND|MAIL/.test(a.injectedLabels.join(' ')), a.injectedLabels.join('|'));
      ok(`A 零 console error (${tag})`, errors.length === 0, errors.slice(0, 3).join(' ‖ '));
      const p = join(OUT, `${tag}-A-blank-before.png`);
      await page.screenshot({ path: p });
      receipt.scenes.A = { ...a, shot: p };
      await browser.close();
    }

    // ── 场景 B：同一会话修后载荷（正文恢复：文本 + 工具 + 思考 + 归因行）──
    {
      const sent = [];
      const { browser, page, errors } = await makePage(browserType, theme, { messages: withBody }, sent);
      await openSessionPanel(page);
      const b = await audit(page);
      ok(`B 助手文本行渲染 (${tag})`, b.ai > 0, `ai=${b.ai} sample=${b.aiTextSample}`);
      ok(`B 工具过程行渲染 (${tag})`, b.tool > 0, `tool=${b.tool}`);
      ok(`B 思考块渲染 (${tag})`, b.thinking > 0, `thinking=${b.thinking}`);
      ok(`B 归因行（turn header）在场 (${tag})`, b.turnHeader > 0, `header=${b.turnHeader}`);
      ok(`B 注入行不丢 (${tag})`, b.injected === 22, `injected=${b.injected}`);
      ok(`B 正文体积 > 2KB (${tag})`, b.textLen > 2000, `textLen=${b.textLen}`);
      // 过程可见性（折叠契约 2026-09-05：工具/思考默认卷入，点归因行展开）——
      // 展开后两类过程行必须真实可见（offsetHeight>0），这是「过程可见」的底线面。
      await page.evaluate(() => {
        document.querySelectorAll('.flow-agent-modal .flow-agent-chat .turn-header')
          .forEach((h) => h.click());
      });
      await page.waitForTimeout(300);
      const exp = await page.evaluate(() => {
        const chat = document.querySelector('.flow-agent-modal .flow-agent-chat');
        const vis = (sel) => [...chat.querySelectorAll(sel)].filter((e) => e.getBoundingClientRect().height > 2).length;
        return { toolVis: vis('.row.tool'), thinkVis: vis('.thinking-row'), tucked: chat.querySelectorAll('.nf-tucked').length };
      });
      ok(`B 展开后工具卡可见 (${tag})`, exp.toolVis > 0, `visTool=${exp.toolVis} tucked=${exp.tucked}`);
      ok(`B 展开后思考块可见 (${tag})`, exp.thinkVis > 0, `visThink=${exp.thinkVis}`);
      ok(`B 零 console error (${tag})`, errors.length === 0, errors.slice(0, 3).join(' ‖ '));
      const p = join(OUT, `${tag}-B-body-restored.png`);
      await page.screenshot({ path: p });
      // 正文顶部（滚到首条 → 归因行 + 正文同屏）。
      await page.evaluate(() => {
        const chat = document.querySelector('.flow-agent-modal .flow-agent-chat');
        if (chat) chat.scrollTop = 0;
      });
      await page.waitForTimeout(200);
      const p2 = join(OUT, `${tag}-B2-body-top.png`);
      await page.screenshot({ path: p2 });
      // 展开态（工具卡 + 思考块真实可见）。
      await page.evaluate(() => {
        const chat = document.querySelector('.flow-agent-modal .flow-agent-chat');
        const first = chat?.querySelector('.row.tool');
        if (first && chat) chat.scrollTop = Math.max(0, first.offsetTop - 120);
      });
      await page.waitForTimeout(200);
      const p3 = join(OUT, `${tag}-B3-process-expanded.png`);
      await page.screenshot({ path: p3 });
      receipt.scenes.B = { ...b, ...exp, shot: p, shot2: p2, shot3: p3 };
      await browser.close();
    }

    console.log(`-- ${tag} done`);
    writeFileSync(join(OUT, `${tag}-audit.json`), JSON.stringify(receipt, null, 1));
  }
}

console.log(`\nshots + audit → ${OUT}`);
console.log(failures ? `FAILURES: ${failures}` : 'ALL GREEN');
process.exit(failures ? 1 : 0);

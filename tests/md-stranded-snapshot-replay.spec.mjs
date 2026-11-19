// md-stranded-snapshot-replay.spec.mjs — P14 md 渲染失效回归（2026-11-11，
// 作者报障「GFM 表格首渲染正常 → 重渲染退化为原始竖线文本」）。
//
// 根因（受控重放矩阵 + E2E 复现确证，证据 .nebflow/evidence/p14-md-rerender-regress__20261111/）：
//   渲染器本身无责 —— utils.js 共享管线对完整正文恒产出 <table>（C1 钉面）。
//   退化文本 = WS 断连窗内滞留在 state.sessionTexts[sid] 的**消息前缀**（终态帧族
//   done/textDone/工具边界随帧清缓冲，帧丢了缓冲就无人清）。断连重连的初载重放腿
//   （chat/historyReplay.js txtBuf 分支）把这条前缀当作未落盘流式段重建：正文截在
//   表头/分隔行窗口内时，GFM 表格整块退化为竖线文本（C2 钉面 = 报障同款形状），
//   !isStillBusy 分支再把它闭成永久泡。修复 = 滞留快照闸：缓冲文本是重放历史末条
//   带正文 Ai 的前缀（等长亦命中）⇒ 判为已落盘内容的滞留快照，丢弃缓冲、复位视图
//   流式指针、跳过重建（历史行已承载同一内容）；真未落盘的段（非前缀）照旧重建
//   （C4 守卫钉，pendingRestore 族的「内容正确」语义不回归）。
//
// 被测真实链路（前端零替身）：WS textDelta/sessionBusy/historyPage 帧 →
//   chat.js appendAiText/finishAi（live 面）→ WS 掉线（无终态帧）→ wsBackoff 重连
//   → main.js onReconnect → historyReplay.js replayHistoryPageInitialLoad
//   → utils.js renderMarkdownWithMath（共享字符串管线）→ RENDERED DOM。
//
// 判据（机械断言，独立 PASS/FAIL；失败 ⇒ EXIT 非 0）：
//   C1 首渲染面：完整正文经真 finishAi 恒产出恰好 1 个 <table>（渲染器无责钉面）
//   C2 降级形状钉面：表头无有效分隔行的正文 → 0 <table> + 竖线残文（= 报障同款，
//      证明 C3 的滞留夹具确实是症状形状）
//   C3 滞留快照闸（修复本体）：turn A 完整落盘渲染正常 → turn B 同文重流被截在
//      分隔行窗口内、WS 掉线零终态帧 → 重连（historyPage 先于 busy 重同步到达，
//      事件序竞态 = 报障序）→ 重放后 DOM 零降级泡、完整表格历史行在场
//   C4 未落盘段不误伤：同场景但 turn B 流的是**非前缀**的全新内容 → 重放后片段泡
//      照旧重建（旧设计语义守卫）
//
// 样本 = 与报障消息同构的合成 GFM 台账（真实样本不进仓；真样本读数在证据目录）。
//
// 运行：node tests/md-stranded-snapshot-replay.spec.mjs
// （自包含 node-runner spec：route 注入零端口、mock WS 自管、宿主网关端口零接触）

import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
// 红腿钩子（red-leg 惯例）：WEB_ROOT=<pre-fix web 树> 以修复前源码跑同一组判据，
// 期望 C3 红、C1/C2/C4 绿（红绿双向全绿 = 修复面证据）。缺省 = 被测仓自身。
const WEB = resolve(process.env.WEB_ROOT || ROOT, 'src/main/resources/web');
const SID = 'md-stranded-snapshot-session';

/* ─────────── 同构合成样本（intro + 3 列 GFM 台账 + 收尾 bullets） ─────────── */

const ROWS = [
  ['批次甲（#2101）', '✅ **有回执**：demo 三区块+规范稿更新，亮暗全绿（未提交）', '不重做，随 P1 一并提交'],
  ['批次乙对账（#2102）', '✅ **完成**：报告在登记库 `audit-batch__20261111_104700/`', '不重做'],
  ['批次丙收尾（#2103）', '挂 36 分钟无产出 → 已撤', '9.19 · P3'],
  ['批次丁三缺陷（#2104）', '同上 → 已撤', '9.19 · P2'],
  ['批次戊删除（#2105）', '同上 → 已撤', '9.19 · P4'],
  ['批次己治理（#2106）', '同上 → 已撤', '9.19 · P5'],
  ['批次庚续写（#2107/#2108）', '撤下 / 重派没起来', '9.19 · P13'],
  ['批次辛报错补详（#2109）', '静默失败', '9.19 · P6'],
  ['批次壬档位（#2110→2111 重派×2）', '静默失败', '9.19 · P7'],
  ['批次癸统计卡死（#2112）', '静默失败', '9.19 · P8'],
  ['批次子面板报障（#2113/#2114）', '静默失败', '9.19 · P9'],
  ['批次丑粒度（#2115）', '静默失败', '9.19 · P10'],
  ['批次寅批量打开（#2116）', '静默失败', '9.19 · P11'],
  ['批次卯大一统（#2117）', '静默失败', '9.19 · P12'],
];
const SAMPLE = [
  '注入已在后台跑（建会话 → 发总纲，完成会通知我）。今晨全量盘点与处置如下：',
  '',
  '| 今晨台账 | 状态 | 处置 |',
  '|---|---|---|',
  ...ROWS.map((r) => '| ' + r.join(' | ') + ' |'),
  '',
  '- **新令**：标题只 Canvas、无中间胶囊、图标居中/返回钮对齐，列为 **P1** 最高优先。',
  '- **回传通道**：每批把回执写 spool 目录，巡检逐批核验。',
  '- **规则已记**：从现在起，任务只交给 9.19。',
].join('\n');

// 滞留快照夹具：截在分隔行**窗口内**（半个 `|---|`），机械判定 = 整块表格退化。
const CUT_AT = SAMPLE.indexOf('|---|---|---|') + 5;
const STRANDED_PREFIX = SAMPLE.slice(0, CUT_AT);

// C4 的非前缀全新内容（与历史任何 Ai 正文无前缀关系）。
const FRESH_FRAGMENT = '这是一个尚未落盘的新段（重连前只收到一半）：\n\n- 要点甲\n- 要点乙的';

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.ico': 'image/x-icon', '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf',
};

const chunk = (s, n) => { const out = []; for (let i = 0; i < s.length; i += n) out.push(s.slice(i, i + n)); return out; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/* ─────────── 引导（零端口：route 从磁盘注入；mock WS 按脚本演帧） ─────────── */

async function boot(browser, wsScript) {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('neblink_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('stand_input_history', '[]');
    if (navigator.serviceWorker) {
      Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
    }
  });
  const page = await ctx.newPage();
  page.setDefaultTimeout(45000);
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(String((e && e.message) || e).slice(0, 300)));

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = join(WEB, p);
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });

  await page.routeWebSocket(/\/ws/, (ws) => wsScript(ws, (m) => { try { ws.send(JSON.stringify(m)); } catch { /* closed */ } }));

  await page.goto('http://localhost:1/');
  return { page, pageErrors, close: () => ctx.close() };
}

function handshake(send) {
  send({ type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {} });
  send({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID });
}

/** 重放后 DOM 读数：表格泡数 / 降级泡数（含台账头但无 <table>）。 */
const READ_DOM = () => {
  const bubbles = [...document.querySelectorAll('#chat .bubble.ai')];
  const withTable = bubbles.filter((b) => b.querySelector('table'));
  const degraded = bubbles.filter((b) => {
    if (b.querySelector('table')) return false;
    const t = b.textContent || '';
    return t.includes('今晨台账') && t.includes('|');
  });
  return { bubbles: bubbles.length, tableBubbles: withTable.length, degradedBubbles: degraded.length, degradedSnips: degraded.map((b) => (b.textContent || '').slice(0, 120)) };
};

let failures = 0;
function check(name, ok, detail) {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${ok ? '' : '  :: ' + detail}`);
  if (!ok) failures++;
}

async function main() {
  const browser = await chromium.launch();

  /* ── C1 + C2：渲染面钉板（一页直驱真模块，无 WS 剧场） ───────────────── */
  {
    const { page, close } = await boot(browser, (ws, send) => {
      handshake(send);
      ws.onMessage((raw) => {
        let m; try { m = JSON.parse(raw); } catch { return; }
        if (m.type === 'ping') return send({ type: 'pong' });
        if (m.type === 'getHistory') return send({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 });
      });
    });
    await page.waitForFunction(() => typeof window.marked !== 'undefined', null, { timeout: 30000 });
    const c12 = await page.evaluate(async ({ sample, broken }) => {
      const { renderMarkdownWithMath } = await import('/js/utils.js');
      const countTables = (html) => (html.match(/<table[\s>]/g) || []).length;
      const countRawPipes = (html) => {
        // 段落级竖线残文：<p> 文本含 `|`（表格化输出不会有裸竖线段落）
        const tmp = document.createElement('div');
        tmp.innerHTML = html;
        let n = 0;
        tmp.querySelectorAll('p').forEach((p) => { if (p.textContent.includes('|')) n++; });
        return n;
      };
      const full = renderMarkdownWithMath(sample, true, { cache: false });
      const brokenHtml = renderMarkdownWithMath(broken, true, { cache: false });
      return { fullTables: countTables(full), fullRawParas: countRawPipes(full), brokenTables: countTables(brokenHtml), brokenRawParas: countRawPipes(brokenHtml) };
    }, { sample: SAMPLE, broken: STRANDED_PREFIX });
    check('C1 首渲染面：完整正文 → 恰 1 个 <table>（渲染器无责）',
      c12.fullTables === 1 && c12.fullRawParas === 0,
      `fullTables=${c12.fullTables} rawParas=${c12.fullRawParas}`);
    check('C2 降级形状钉面：表头无有效分隔行 → 0 <table> + 竖线残文（报障同款）',
      c12.brokenTables === 0 && c12.brokenRawParas >= 1,
      `tables=${c12.brokenTables} rawParas=${c12.brokenRawParas}`);
    await close();
  }

  /* ── C3：E2E 滞留快照闸（修复本体） ──────────────────────────────────── */
  {
    let connection = 0;
    const { page, close, pageErrors } = await boot(browser, (ws, send) => {
      connection++;
      const conn = connection;
      handshake(send);
      ws.onMessage(async (raw) => {
        let m; try { m = JSON.parse(raw); } catch { return; }
        if (m.type === 'ping') return send({ type: 'pong' });
        if (m.type === 'getConfig') return handshake(send);
        if (m.type === 'getSessionBusy') return send({ type: 'sessionBusy', sessionId: SID, busy: false });
        if (m.type !== 'getHistory') return;
        if (conn === 1) return send({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 });
        // 重连腿：historyPage 先于 busy 重同步到达（报障事件序）。历史 = turn A 完整正文。
        send({ type: 'historyPage', sessionId: m.sessionId, messages: [{ type: 'ai', text: SAMPLE, timestamp: Date.now() - 3600_000 }], hasMore: false, offset: 0, total: 1 });
        await sleep(500);
        send({ type: 'sessionBusy', sessionId: SID, busy: false });
      });
      if (conn === 1) {
        (async () => {
          await sleep(2500);
          // turn A：完整流 + 正常终态（首渲染 OK 面）
          send({ type: 'sessionBusy', sessionId: SID, busy: true });
          await sleep(300);
          for (const c of chunk(SAMPLE, 180)) { send({ type: 'textDelta', sessionId: SID, delta: c, round: 0, block: 1 }); await sleep(50); }
          await sleep(300);
          send({ type: 'textDone', sessionId: SID });
          await sleep(300);
          send({ type: 'done', sessionId: SID, durationMs: 4200, model: 'test-model' });
          await sleep(2500);
          // turn B：同文重流被截在分隔行窗口内 + 网关死亡（零终态帧）
          send({ type: 'sessionBusy', sessionId: SID, busy: true });
          await sleep(300);
          for (const c of chunk(STRANDED_PREFIX, 120)) { send({ type: 'textDelta', sessionId: SID, delta: c, round: 1, block: 1 }); await sleep(50); }
          await sleep(300);
          try { ws.close(); } catch { /* gone */ }
        })().catch(() => {});
      }
    });
    await page.waitForSelector('#chat .bubble.ai table', { timeout: 30000 });
    await sleep(15000); // 掉线 → backoff 重连 → 重放 + busy 重同步落定
    const dom = await page.evaluate(READ_DOM);
    check('C3 滞留快照闸：重放后零降级泡、完整表格历史行在场',
      dom.degradedBubbles === 0 && dom.tableBubbles >= 1,
      `degraded=${dom.degradedBubbles} tableBubbles=${dom.tableBubbles} snips=${JSON.stringify(dom.degradedSnips)} pageErrors=${JSON.stringify(pageErrors)}`);
    await close();
  }

  /* ── C4：未落盘段不误伤（守卫钉） ────────────────────────────────────── */
  {
    let connection = 0;
    const { page, close, pageErrors } = await boot(browser, (ws, send) => {
      connection++;
      const conn = connection;
      handshake(send);
      ws.onMessage(async (raw) => {
        let m; try { m = JSON.parse(raw); } catch { return; }
        if (m.type === 'ping') return send({ type: 'pong' });
        if (m.type === 'getConfig') return handshake(send);
        if (m.type === 'getSessionBusy') return send({ type: 'sessionBusy', sessionId: SID, busy: false });
        if (m.type !== 'getHistory') return;
        if (conn === 1) return send({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 });
        // 历史只含 turn A；turn B 的片段是**非前缀**新内容 ⇒ 必须照旧重建。
        send({ type: 'historyPage', sessionId: m.sessionId, messages: [{ type: 'ai', text: SAMPLE, timestamp: Date.now() - 3600_000 }], hasMore: false, offset: 0, total: 1 });
        await sleep(500);
        send({ type: 'sessionBusy', sessionId: SID, busy: false });
      });
      if (conn === 1) {
        (async () => {
          await sleep(2500);
          send({ type: 'sessionBusy', sessionId: SID, busy: true });
          await sleep(300);
          for (const c of chunk(SAMPLE, 180)) { send({ type: 'textDelta', sessionId: SID, delta: c, round: 0, block: 1 }); await sleep(50); }
          await sleep(300);
          send({ type: 'textDone', sessionId: SID });
          await sleep(300);
          send({ type: 'done', sessionId: SID, durationMs: 4200, model: 'test-model' });
          await sleep(2500);
          // turn B：全新内容流到一半 + 网关死亡（零终态帧）
          send({ type: 'sessionBusy', sessionId: SID, busy: true });
          await sleep(300);
          for (const c of chunk(FRESH_FRAGMENT, 60)) { send({ type: 'textDelta', sessionId: SID, delta: c, round: 1, block: 1 }); await sleep(50); }
          await sleep(300);
          try { ws.close(); } catch { /* gone */ }
        })().catch(() => {});
      }
    });
    await page.waitForSelector('#chat .bubble.ai table', { timeout: 30000 });
    await sleep(15000);
    const dom = await page.evaluate(() => {
      const bubbles = [...document.querySelectorAll('#chat .bubble.ai')];
      const kept = bubbles.filter((b) => (b.textContent || '').includes('要点甲'));
      return { keptFragment: kept.length, tableBubbles: bubbles.filter((b) => b.querySelector('table')).length };
    });
    check('C4 未落盘段不误伤：非前缀片段照旧重建（pendingRestore 族语义守卫）',
      dom.keptFragment >= 1 && dom.tableBubbles >= 1,
      `keptFragment=${dom.keptFragment} tableBubbles=${dom.tableBubbles} pageErrors=${JSON.stringify(pageErrors)}`);
    await close();
  }

  await browser.close();
  const total = 4;
  console.log(`\n${failures === 0 ? 'ALL PASS' : 'FAILURES'}: ${total - failures}/${total}`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => { console.error('SPEC FAIL:', e); process.exit(2); });

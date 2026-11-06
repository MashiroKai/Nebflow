#!/usr/bin/env node
// todo-row-reply.spec.mjs — 行互动框 + 多行回复自证（impl-row-reply 批 · 2026-11-06）。
//
// 作者口径（台账批任务书 + 图2/图3）：点待办行**不再跳详情/编辑页**，原位浮出一块
// 互动框（「浮起 + 轻微上移」层级感）；点外部 / 再点同一行 / Esc 收起；回复 = 多行
// 输入（回车只换行），发送 = 独立钮，消息体 `#<待办名> <回复>` 逐字进主会话。
//
// 运行：node tests/todo-row-reply.spec.mjs
//
// 零后端法（与 tests/todo-create-bottom.spec.mjs 同源）：
//   · 静态面 = page.route 从磁盘注入本仓 web 树（零端口、零 sbt、不碰 :6138）；
//   · 数据面 = routeWebSocket 帧桩 + todoStore.__seed 夹具；
//   · 视口   = 375×812 / deviceScaleFactor 2 / hasTouch / isMobile（body.mobile-layer）。
//
// 判据：
//   A 组 开合与零位移
//     A1 点行体 ⇒ 互动框浮出（.tfl-hit.is-open，role=dialog，挂在页面壳里）
//     A2 起点几何 = 行盒（框 left/top/width 与行盒逐值同 ±2px）
//     A3 零位移不变量：首条行 rect / 列表滚区 / 文档滚轴 / dock 逐值不变
//     A4 开合过渡 = liquidMorph 单源（base 280ms / 唯一曲线；属性 ⊆ 白名单四腿）
//     A5 单实现：全壳恰好一块 .tfl-hit；换行开框 = 旧框瞬时清场（不叠两块）
//   B 组 多行输入 + 回复逐字
//     B1 占位符已本地化（无裸 'todoface.replyPh' 键名）
//     B2 回车 = 换行不发送（无 WS 用户帧；框不收；主输入框不被污染）
//     B3 点发送 ⇒ 用户帧 content = `#<待办名> <回复>` 逐字（换行原样保留）；
//        发送后框收、textarea 清空、主输入框清空（send() 单源出口已消费）
//   C 组 撞门禁名字（digit-head 同源门禁）
//     C1 数字头待办（「3d打印模型上色」）回复照发逐字（投递腿不受门禁影响）
//     C2 展示腿门禁同源生效：正常名 ⇒ .todo-tag chip 渲染；数字头名 ⇒ 零 chip
//        （matchTodoAt 形状门禁——任务号/色值不认作待办引用）
//   D 组 收起路径 + 命中盒
//     D1 Esc 收起；D2 点外部收起；D3 再点同一行收起（toggle）
//     D4 发送钮命中盒 ≥ 48（--ds-hit-tap-min 单源）

import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
// 期望值不从 CSS/JS 抄数：直接取液态形变单源模块的导出（改一处两处同步）。
import { DURATION, EASING, morphTransition } from '../src/main/resources/web/js/motion/liquidMorph.js';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const OUT = join(ROOT, 'reports-evidence', 'todo-mobile-revamp__20261106', 'impl-row-reply');
mkdirSync(OUT, { recursive: true });

const SID = 'todo-row-reply-spec-session';
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

/* ───────────── 夹具（f-2 名字故意数字头 = 撞门禁名字判据）───────────── */
const TAGS = [
  { id: 'tag-work', name: '工作', color: 'rgb(59,130,246)' },
  { id: 'tag-life', name: '生活', color: 'rgb(16,185,129)' },
];
function items() {
  const now = Date.now();
  return [
    { id: 'f-1', tagId: 'tag-work', title: '修登录态过期', notes: '', due: now + 3600_000, priority: 3, createdAt: now - 9 * 3600_000 },
    { id: 'f-2', tagId: 'tag-life', title: '3d打印模型上色', notes: '', due: now + 2 * 3600_000, priority: 2, createdAt: now - 8 * 3600_000 },
  ];
}

let pass = 0;
let fail = 0;
const lines = [];
function ok(name, cond, detail = '') {
  const line = `${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`;
  console.log(line);
  lines.push(line);
  if (cond) pass++; else fail++;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const near = (a, b, tol = 2) => a != null && b != null && Math.abs(a - b) <= tol;
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);

/* ───────────── 零后端 wire（客户端→服务端帧全量录制）───────────── */
/** @type {string[]} */
const clientFrames = [];
async function wire(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    if (navigator.serviceWorker) {
      Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
    }
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }),
        });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {},
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID,
    }));
    sendConfig(); sendSessions();
    // 页面→服务端方向全量录制 + 帧桩同槽（routeWebSocket 的 onMessage 是单槽设置，
    // 后注册覆盖先注册 ⇒ 录制与应答必须同一个处理器）
    ws.onMessage((raw) => {
      clientFrames.push(String(raw));
      let m;
      try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
      }
    });
  });
}

async function boot(page) {
  await wire(page);
  await page.goto('http://localhost:1/');
  await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 20000 });
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.evaluate(async ({ sid, tags, its }) => {
    const store = await import('/js/todoStore.js');
    store.todoStore.__seed({ tagList: tags, itemList: its });
    const face = await import('/js/todoFace.js');
    face.renderTodoTaskList(sid);
  }, { sid: SID, tags: TAGS, its: items() });
  await page.locator("[data-chrome='tasks']").first().click();
  await page.waitForFunction(() => {
    const el = document.querySelector('.mobile-page[data-page="tasks"]');
    return el && el.classList.contains('live');
  }, null, { timeout: 10000 });
  await page.waitForTimeout(600);
  await page.waitForSelector(".mobile-page[data-page='tasks'] .tfl-row", { timeout: 8000 });
}

/* ───────────── 读数脚本 ───────────── */
const READ = `(() => {
  const R = (el) => { if (!el) return null; const b = el.getBoundingClientRect();
    return { x: +b.x.toFixed(2), y: +b.y.toFixed(2), w: +b.width.toFixed(2), h: +b.height.toFixed(2) }; };
  const page = document.querySelector(".mobile-page[data-page='tasks']");
  const list = page && page.querySelector('.tfl-list');
  const se = document.scrollingElement;
  return {
    firstRow: R(page && page.querySelector('.tfl-row')),
    dock: R(document.querySelector('#mobile-dock')),
    list: list ? { top: list.scrollTop, sh: list.scrollHeight, ch: list.clientHeight } : null,
    doc: se ? { top: se.scrollTop, sh: se.scrollHeight, ch: se.clientHeight } : null,
  };
})()`;

const RECT_OF = (sel) => `(() => {
  const el = document.querySelector(${JSON.stringify(sel)});
  if (!el) return null;
  const b = el.getBoundingClientRect();
  return { x: +b.x.toFixed(2), y: +b.y.toFixed(2), w: +b.width.toFixed(2), h: +b.height.toFixed(2),
           left: +b.left.toFixed(2), top: +b.top.toFixed(2) };
})()`;

const TRANSITION_OF = (sel) => `(() => {
  const el = document.querySelector(${JSON.stringify(sel)});
  if (!el) return null;
  const cs = getComputedStyle(el);
  return { props: cs.transitionProperty, dur: cs.transitionDuration, ease: cs.transitionTimingFunction };
})()`;

/* 用户帧检索：client 录制里找带 content 的用户消息帧（type 裸帧 = dispatchUserMessageFrames 单帧腿）。 */
const userContentFrames = () => clientFrames
  .map((raw) => { try { return JSON.parse(raw); } catch { return null; } })
  .filter((m) => m && typeof m.content === 'string' && m.sessionId === SID)
  .map((m) => m.content);

/* ───────────── 主流程 ───────────── */
const browser = await chromium.launch();
const errors = [];
const shots = [];

try {
  const ctx = await browser.newContext({
    viewport: { width: 375, height: 812 }, deviceScaleFactor: 2, hasTouch: true, isMobile: true,
  });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  await boot(page);

  const PAGE = ".mobile-page[data-page='tasks']";
  const BOX = `${PAGE} .tfl-hit`;
  const ROW1 = `${PAGE} .tfl-row[data-id='f-1'] .tfl-row-main`;
  const ROW2 = `${PAGE} .tfl-row[data-id='f-2'] .tfl-row-main`;

  /* ══════ A 组：开合与零位移 ══════ */
  const before = await page.evaluate(READ);
  await page.locator(ROW1).click();
  await page.waitForSelector(BOX, { timeout: 5000 });
  await sleep(400); // base 280ms 落地
  await page.screenshot({ path: join(OUT, 'box-open.png') }); shots.push('box-open.png');

  const openShape = await page.evaluate(`(() => {
    const box = document.querySelector(${JSON.stringify(BOX)});
    if (!box) return null;
    return {
      open: box.classList.contains('is-open'),
      role: box.getAttribute('role'),
      label: box.getAttribute('aria-label'),
      inShell: !!box.closest(".mobile-page[data-page='tasks']"),
      glass: box.classList.contains('nf-glass'),
      hasReply: !!box.querySelector('.tfl-hit-reply'),
      hasSend: !!box.querySelector('.tfl-hit-send'),
      hasDone: !!box.querySelector('.tfl-hit-done'),
      hasEdit: !!box.querySelector('.tfl-hit-edit'),
      count: document.querySelectorAll('.tfl-hit').length,
    };
  })()`);
  ok('A1 点行体 ⇒ 互动框浮出（is-open / role=dialog / 页面壳内 / nf-glass / 五件齐）',
    openShape && openShape.open && openShape.role === 'dialog' && openShape.inShell && openShape.glass
    && openShape.hasReply && openShape.hasSend && openShape.hasDone && openShape.hasEdit,
    JSON.stringify(openShape));

  const rowRect = await page.evaluate(RECT_OF(`${PAGE} .tfl-row[data-id='f-1']`));
  const boxRect = await page.evaluate(RECT_OF(BOX));
  ok('A2 起点几何 = 行盒（left/top/width 与行盒同 ±2px；终点高 ≥ 行高）',
    near(boxRect.left, rowRect.x) && near(boxRect.top, rowRect.y) && near(boxRect.w, rowRect.w)
    && boxRect.h >= rowRect.h - 0.5,
    `row=${JSON.stringify(rowRect)} box=${JSON.stringify(boxRect)}`);

  const during = await page.evaluate(READ);
  ok('A3a 首条行零位移（开前 → 开时，≤0.5px）',
    same(before.firstRow, during.firstRow),
    `${JSON.stringify(before.firstRow)} → ${JSON.stringify(during.firstRow)}`);
  ok('A3b 列表滚区/文档滚轴/dock 不变',
    same(before.list, during.list) && same(before.doc, during.doc) && same(before.dock, during.dock),
    `list ${JSON.stringify(before.list)}→${JSON.stringify(during.list)} doc ${JSON.stringify(before.doc)}→${JSON.stringify(during.doc)}`);

  const tr = await page.evaluate(TRANSITION_OF(BOX));
  const wantDur = `${DURATION.base / 1000}s`;
  // getComputedStyle 口径：逐属性一份（逗号 join）。cubic-bezier(...) 内部也含逗号
  // ⇒ 不做 split 比对，直接与「N 段全同」的期望整串比对（create-bottom spec 同律）。
  const props = tr ? tr.props.split(',').map((s) => s.trim()) : []; // 属性名族无内嵌逗号
  const whitelist = ['height', 'border-radius', 'box-shadow', 'transform'];
  const wantEase = Array(props.length).fill(EASING).join(', ');
  const wantDurs = Array(props.length).fill(wantDur).join(', ');
  const trFlags = { hasTr: !!tr, dur: tr && tr.dur === wantDurs, ease: tr && tr.ease === wantEase, props: props.length > 0 && props.every((p) => whitelist.includes(p)) };
  ok('A4 开合过渡 = liquidMorph 单源（base 280ms / 唯一曲线 / 属性 ⊆ 白名单四腿）',
    Object.values(trFlags).every(Boolean),
    `flags=${JSON.stringify(trFlags)} wantDur=${wantDurs} wantEase=${wantEase} got=${JSON.stringify(tr)}`);

  /* 换行开框：框已盖住第二行 ⇒ 真指针落点会被浮层截走；走 DOM click（同一
     处理器链：row.click() → toggleItemBox('f-2')，旧框瞬时清场） */
  await page.evaluate(`document.querySelector(${JSON.stringify(ROW2)}).click()`);
  await sleep(400);
  const twoCount = await page.evaluate(() => document.querySelectorAll('.tfl-hit').length);
  const secondLabel = await page.evaluate(`(() => {
    const box = document.querySelector(${JSON.stringify(BOX)});
    return box ? box.getAttribute('aria-label') : null;
  })()`);
  ok('A5 单实现：换行开框后全壳恰一块 .tfl-hit，且服务新行（不叠两块）',
    twoCount === 1 && secondLabel === '3d打印模型上色',
    `count=${twoCount} label=${secondLabel}`);

  /* ══════ B 组：多行输入 + 回复逐字 ══════ */
  await page.locator(`${BOX} .tfl-hit-reply`).fill('');
  const ph = await page.evaluate(`(() => {
    const ta = document.querySelector(${JSON.stringify(`${BOX} .tfl-hit-reply`)});
    return ta ? ta.getAttribute('placeholder') : null;
  })()`);
  ok('B1 占位符已本地化（无裸键名）', !!ph && !ph.includes('todoface.'), `placeholder=${ph}`);

  // 回车 = 换行不发送：pressSequentially 逐键（真实按键道），Enter 后核对帧与框态
  const taSel = `${BOX} .tfl-hit-reply`;
  await page.locator(taSel).click();
  await page.locator(taSel).pressSequentially('第一行');
  await page.keyboard.press('Enter');
  await page.locator(taSel).pressSequentially('第二行');
  await sleep(250);
  const enterState = await page.evaluate(`(() => {
    const ta = document.querySelector(${JSON.stringify(taSel)});
    const box = document.querySelector(${JSON.stringify(BOX)});
    const composer = document.getElementById('input');
    return { value: ta ? ta.value : null, boxOpen: !!box, composerValue: composer ? composer.value : null };
  })()`);
  const framesAfterEnter = userContentFrames();
  ok('B2 回车 = 换行不发送（值含 \\n、框不收、无用户帧、主输入框零污染）',
    enterState.value === '第一行\n第二行' && enterState.boxOpen === true
    && framesAfterEnter.length === 0 && (enterState.composerValue === '' || enterState.composerValue == null),
    `value=${JSON.stringify(enterState.value)} boxOpen=${enterState.boxOpen} userFrames=${framesAfterEnter.length} composer=${JSON.stringify(enterState.composerValue)}`);

  await page.screenshot({ path: join(OUT, 'reply-multiline.png') }); shots.push('reply-multiline.png');

  // 发送 = 独立钮；消息体逐字 = `#<待办名> <回复>`
  const composerBefore = await page.evaluate("document.getElementById('input') ? document.getElementById('input').value : null");
  await page.locator(`${BOX} .tfl-hit-send`).click();
  await sleep(500);
  const afterSend = await page.evaluate(`(() => {
    const ta = document.querySelector(${JSON.stringify(taSel)});
    const box = document.querySelector(${JSON.stringify(BOX)});
    const composer = document.getElementById('input');
    return { taValue: ta ? ta.value : null, boxGone: !box, composerValue: composer ? composer.value : null };
  })()`);
  const frames = userContentFrames();
  const wantBody = '#3d打印模型上色 第一行\n第二行';
  ok('B3 点发送 ⇒ 用户帧逐字 `#<名> <回复>`（换行原样）、框收、双输入清空（send() 单源出口）',
    frames.length === 1 && frames[0] === wantBody && afterSend.boxGone === true
    && afterSend.taValue === null && (afterSend.composerValue === '' || afterSend.composerValue === null)
    && composerBefore === '' ,
    `frames=${JSON.stringify(frames)} want=${JSON.stringify(wantBody)} after=${JSON.stringify(afterSend)} composerBefore=${JSON.stringify(composerBefore)}`);
  await page.screenshot({ path: join(OUT, 'after-send.png') }); shots.push('after-send.png');

  /* ══════ C 组：撞门禁名字 ══════ */
  // C1 已由 B3 覆盖投递腿（f-2 = 数字头名，帧已逐字出线）——这里补展示腿同源读数
  const gateRead = await page.evaluate(async () => {
    const u = await import('/js/utils.js');
    const mk = () => document.createElement('div');
    const normal = mk(); u.appendTextWithMentions(normal, '#修登录态过期 请看这个');
    const digit = mk(); u.appendTextWithMentions(digit, '#3d打印模型上色 请看这个');
    const chips = (root) => root.querySelectorAll('.todo-tag').length;
    return { normalChips: chips(normal), digitChips: chips(digit),
      normalText: normal.textContent, digitText: digit.textContent };
  });
  ok('C2 展示腿门禁同源：正常名 ⇒ chip 渲染；数字头名 ⇒ 零 chip（任务号形状门禁）',
    gateRead.normalChips === 1 && gateRead.digitChips === 0
    && gateRead.normalText.includes('修登录态过期') && gateRead.digitText.includes('3d打印模型上色'),
    JSON.stringify(gateRead));
  ok('C1 数字头待办回复照发逐字（投递腿不受门禁影响；帧见 B3）',
    frames.length === 1 && frames[0].startsWith('#3d打印模型上色 '), frames[0]);

  /* ══════ D 组：收起路径 + 命中盒 ══════ */
  // D1 Esc 收起
  await page.locator(ROW1).click();
  await sleep(400);
  await page.keyboard.press('Escape');
  await sleep(400);
  const escGone = await page.evaluate(() => !document.querySelector('.tfl-hit'));
  ok('D1 Esc 收起', escGone, `boxGone=${escGone}`);

  // D2 点外部收起（页头大标题 = 惰性 span、框与行之外；不碰坐标——页头左缘是
  // 返回钮，坐标点击会误触 popPage）
  await page.locator(ROW1).click();
  await sleep(400);
  await page.locator(`${PAGE} .mobile-page-title`).click();
  await sleep(400);
  const outsideGone = await page.evaluate(() => !document.querySelector('.tfl-hit'));
  ok('D2 点外部收起（框外指针一击收）', outsideGone, `boxGone=${outsideGone}`);

  // D3 再点同一行收起（toggle；DOM click 与行点击处理器同一链）
  await page.evaluate(`document.querySelector(${JSON.stringify(ROW1)}).click()`);
  await sleep(400);
  const openAgain = await page.evaluate(() => !!document.querySelector('.tfl-hit'));
  await page.evaluate(`document.querySelector(${JSON.stringify(ROW1)}).click()`);
  await sleep(400);
  const toggledGone = await page.evaluate(() => !document.querySelector('.tfl-hit'));
  ok('D3 再点同一行收起（toggle 语义）', openAgain && toggledGone, `open=${openAgain} afterToggleGone=${toggledGone}`);

  // D4 发送钮命中盒 ≥ 48（最小可点尺寸令牌单源；本批不另跑全页机检——gate 节点做）
  const sendHit = await page.evaluate(async () => {
    // 开框读 .tfl-hit-send 的 min 尺寸声明与实际命中盒
    const row = document.querySelector(".mobile-page[data-page='tasks'] .tfl-row[data-id='f-1'] .tfl-row-main");
    row.click();
    await new Promise((r) => setTimeout(r, 400));
    const send = document.querySelector(".mobile-page[data-page='tasks'] .tfl-hit-send");
    if (!send) return null;
    const cs = getComputedStyle(send);
    const b = send.getBoundingClientRect();
    return { minW: cs.minWidth, minH: cs.minHeight, w: +b.width.toFixed(1), h: +b.height.toFixed(1) };
  });
  ok('D4 发送钮命中盒 ≥ --ds-hit-tap-min（令牌单源声明 + 实测）',
    !!sendHit && sendHit.minH === '48px' && sendHit.h >= 47.5 && sendHit.w >= 47.5,
    JSON.stringify(sendHit));
  await page.evaluate(() => { const b = document.querySelector('.tfl-hit'); if (b) b.remove(); });

  /* ══════ 页面错误守门 ══════ */
  ok('E 全程零页面错误', errors.length === 0, errors.join(' | ').slice(0, 300));

} catch (err) {
  ok('SPEC-ABORT', false, String(err && err.stack ? err.stack.split('\n').slice(0, 4).join(' | ') : err));
} finally {
  await browser.close();
}

const summary = `\n合计 ${pass} PASS / ${fail} FAIL`;
console.log(summary);
lines.push(summary);
writeFileSync(join(OUT, 'spec-output.txt'), lines.join('\n') + '\n');
writeFileSync(join(OUT, 'spec-readings.json'), JSON.stringify({
  at: new Date().toISOString(), viewport: '375x812@2', mobile: true,
  shots, pass, fail,
  frames: userContentFrames(),
}, null, 2));
process.exit(fail > 0 ? 1 : 0);

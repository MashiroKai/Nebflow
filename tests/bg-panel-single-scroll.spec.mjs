// bg-panel-single-scroll.spec.mjs — 后台任务面板「单一滚动容器」验收
// （作者令 2026-11-02：「把内容都放进这个面板，可以下滑；现在是分块了，然后分块下滑」）。
//
// 病灶（修前）：面板两节各自是独立滚动容器 —— `.bg-dropdown-list` 带
// overflow-y:auto + flex 收缩，两条列表按「哪张卡装不下就滚哪张」各滚各的，
// 两卡之间手势不连贯（分块下滑）。
// 目标：整个后台任务页 = **一个连续可滚动的文档流** —— 只有一处纵向滚动容器，
// 所有分组卡跟着整页一起滚（卡片保留圆角/描边/间距的分块视觉，但不再是滚动单元）。
//
// 六条机械断言（本 spec 逐条读数，不接受口头）：
//   V1 单一滚动容器 …… 内容区（页面体子树，排除底 dock、排除弹层）内
//      overflow-y auto|scroll 且 scrollHeight>clientHeight 的元素数 == 1。
//   V2 连贯滚动 ……… 滚到底 ⇒ 最后一个分组的最后一条**可见**（视口内）。
//   V3 不遮挡 ……… 滚到底 ⇒ 最后一条 bottom < 底部导航栏 top（间距 ≥ 0）。
//   V4 375px ……… 无横向溢出（scrollWidth === clientWidth）+ 行内两行文字不重叠。
//   V5 分组卡片仍在 …… 分组标题（「智能体 · N」）依然渲染，只是不再是滚动单元。
//   V6 桌面宽屏 …… 同样不出现「卡片内滚动」的双滚动条（面板内 1 个滚动器）。
// 另：标题行为判定腿（.mpt-pane-scroll / 大标题是否随内容滚走）。
//
// 数据面 = WS activeBgTasks 快照 + state.sessionBgAgents 直种，零后端、零 sbt。
// Run: node <stand>/node_modules/@playwright/test/cli.js test tests/bg-panel-single-scroll.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const SID = 'bgss-root-session';
const T0 = Date.now();
// 控制台噪声过滤器（自包含 harness 的既有口径：路由夹具下 favicon/sw 的 404 与
// service-worker 取脚本失败是夹具面噪声，不是被测行为——见 mobile-panel-title
// spec 同表）。
const NOISE = /Failed to load resource|fetching the script|ResizeObserver loop completed/i;
function watchErrors(page) {
  const errors = [];
  page.on('pageerror', (e) => { if (!NOISE.test(e.message)) errors.push(`pageerror: ${e.message}`); });
  page.on('console', (m) => { if (m.type() === 'error' && !NOISE.test(m.text())) errors.push(`console: ${m.text()}`); });
  return errors;
}
// 夹具行数：手机端「智能体」组 24 行（> 一屏，必有滚动余量；作者实拍 11 行的
// 1 屏半版），任务组 3 行 —— 修前两节各滚、修后整页一滚。
const AGENT_ROWS = 24;

function bgTasksFixture() {
  return { [SID]: [
    { taskId: 'job-1', description: 'Post-merge spec run in base repo', status: 'completed', startedAt: T0 - 90_000, sessionId: SID, kind: 'local', origin: 'nebula', originLabel: 'Nebula', heartbeat: { idleMs: 5_000, outputLines: 42 } },
    { taskId: 'job-2', description: '单滚批：面板改造验收夹具任务二', status: 'running', startedAt: T0 - 30_000, sessionId: SID, kind: 'local', origin: 'nebula', originLabel: 'Nebula' },
    { taskId: 'job-3', description: '单滚批：面板改造验收夹具任务三', status: 'running', startedAt: T0 - 8_000, sessionId: SID, kind: 'local', origin: 'nebula', originLabel: 'Nebula' },
  ] };
}

/** 智能体行夹具（24 行：1 个 delegate 组头 + 4 子行 + 19 平铺行）。 */
function agentEntries() {
  const rows = [{ id: 'delegate-fixture-1', info: {
    name: 'delegate-fixture-1', kind: 'Delegate', sessionId: 'delegate-fixture-1',
    status: 'Processing', startedAt: T0 - 120_000, parentSessionId: SID,
    task: '单滚批夹具：delegate 组头（子行 4 条）',
  } }];
  for (let i = 1; i <= 4; i += 1) {
    rows.push({ id: `subtask-fixture-${i}`, info: {
      name: `subtask-fixture-${i}`, kind: 'SubTask', sessionId: `subtask-fixture-${i}`,
      status: 'Processing', startedAt: T0 - (60_000 + i * 1_000), parentSessionId: 'delegate-fixture-1',
      task: `单滚批夹具：派生第 ${i} 条子行`,
    } });
  }
  for (let i = 1; i <= 19; i += 1) {
    rows.push({ id: `node-fixture-${i}`, info: {
      name: `node-fixture-${i}`, kind: 'AgentFlow', sessionId: `node-fixture-${i}`,
      status: i % 3 === 0 ? 'Completed' : 'Processing', startedAt: T0 - (i * 7_000), parentSessionId: SID,
      task: `单滚批夹具：平铺第 ${i} 条（AgentFlow 节点行）`,
    } });
  }
  return rows;
}

async function bootApp(page) {
  let liveSendBg = null;
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
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
      type: 'configData', config: '{}', configured: true, onboarding: 'done',
      selfDevice: { id: 'self-dev-1', name: 'Mashiros-MacBook-Pro-iso-a659864a' },
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: SID,
    }));
    const sendBg = (tasks) => ws.send(JSON.stringify({ type: 'activeBgTasks', tasks }));
    liveSendBg = sendBg;
    sendConfig();
    sendSessions();
    sendBg(bgTasksFixture());
    ws.onMessage((raw) => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.waitForTimeout(300);
  return { sendBg: (tasks) => liveSendBg?.(tasks) };
}

/** 种智能体行（renderBgAgentDropdown 数据面直种 + 指标刷新 —— 与 p6 spec 同款）。 */
async function seedAgents(page) {
  const n = await page.evaluate(({ sid, rows }) => {
    return import('/js/state.js').then((m) => {
      const bucket = {};
      for (const r of rows) bucket[r.id] = r.info;
      m.default.sessionBgAgents[sid] = bucket;
      m.default.updateBgAgentIndicator();
      return Object.keys(m.default.sessionBgAgents[sid]).length;
    });
  }, { sid: SID, rows: agentEntries() });
  expect(n).toBe(AGENT_ROWS);
}

/** 真实 UI 路径开后台任务页（dock 工作台根页 → 「后台任务」行）。 */
async function openBackgroundPage(page) {
  await page.locator('#mobile-dock .dock-key[data-dock="workbench"]').click();
  await page.locator('.mobile-page[data-page="workbench"] .mf-hub-row[data-hub-page="background"]').click();
  await page.waitForFunction(() => {
    const el = document.querySelector('.mobile-page[data-page="background"]');
    return !!el && el.classList.contains('live');
  }, null, { timeout: 10000 });
  await page.waitForSelector('.mobile-page[data-page="background"] .mobile-page-body > #bg-dropdown .bg-section-agents .bg-dropdown-list .bg-task-row', { timeout: 10000 });
  await page.waitForTimeout(400); // 行渲染/图标收敛
}

/**
 * 面板滚动结构读数（V1 的判据面）：
 *   scrollers …… 内容区（.mobile-page-body 子树）内 overflow-y auto|scroll 且
 *                 scrollHeight−clientHeight > 0 的元素（排除底 dock / 弹层——
 *                 它们不在页面体子树里，天然被排除）。
 *   lists ……… 两条分组列表的滚动相关读数（是否仍为内滚单元）。
 */
const STRUCT_JS = `(() => {
  const sec = document.querySelector('.mobile-page[data-page="background"]');
  const body = sec.querySelector('.mobile-page-body');
  const carrier = body.querySelector('#bg-dropdown');
  const ident = (el) => el === body ? '.mobile-page-body'
    : el === carrier ? '#bg-dropdown'
    : (el.id ? '#' + el.id : '.' + String(el.className || el.tagName).split(' ')[0]);
  const over = (el) => {
    const cs = getComputedStyle(el);
    return (cs.overflowY === 'auto' || cs.overflowY === 'scroll')
      && el.scrollHeight - el.clientHeight > 0;
  };
  const scrollers = [];
  for (const el of [body, ...body.querySelectorAll('*')]) {
    if (!over(el)) continue;
    const cs = getComputedStyle(el);
    scrollers.push({
      sel: ident(el),
      overflowY: cs.overflowY,
      overflowX: cs.overflowX,
      maxHeight: cs.maxHeight,
      flex: cs.flex,
      scrollHeight: el.scrollHeight,
      clientHeight: el.clientHeight,
      surplus: el.scrollHeight - el.clientHeight,
    });
  }
  const lists = [...carrier.querySelectorAll('.bg-dropdown-list')].map((l) => {
    const cs = getComputedStyle(l);
    const r = l.getBoundingClientRect();
    return {
      section: String(l.parentElement.className || '').replace('bg-section ', ''),
      rows: l.querySelectorAll('.bg-task-row').length,
      overflowY: cs.overflowY,
      maxHeight: cs.maxHeight,
      flex: cs.flex,
      scrollHeight: l.scrollHeight,
      clientHeight: l.clientHeight,
      surplus: l.scrollHeight - l.clientHeight,
      scrollHeightEqualsClient: l.scrollHeight === l.clientHeight,
      h: Math.round(r.height),
    };
  });
  const ccs = getComputedStyle(carrier);
  const cb = carrier.getBoundingClientRect();
  return {
    scrollers,
    count: scrollers.length,
    body: {
      overflowY: getComputedStyle(body).overflowY,
      scrollHeight: body.scrollHeight, clientHeight: body.clientHeight,
      scrollWidth: body.scrollWidth, clientWidth: body.clientWidth,
      surplus: body.scrollHeight - body.clientHeight,
    },
    carrier: {
      overflowY: ccs.overflowY, overflowX: ccs.overflowX,
      maxHeight: ccs.maxHeight, flex: ccs.flex,
      scrollHeight: carrier.scrollHeight, clientHeight: carrier.clientHeight,
      surplus: carrier.scrollHeight - carrier.clientHeight,
      scrollWidth: carrier.scrollWidth, clientWidth: carrier.clientWidth,
      scrollTop: carrier.scrollTop,
      top: Math.round(cb.top), h: Math.round(cb.height),
      overscroll: ccs.overscrollBehaviorY,
      iOSMomentum: ccs.webkitOverflowScrolling || '',
    },
    lists,
    vw: window.innerWidth, vh: window.innerHeight,
  };
})()`;

/** 标题行为读数（固定 / 随滚走）。
 *  titleLayoutTop = 大标题**布局流内**位置（页体盒顶 + offsetTop），不含 sticky
 *  钉住位移（那是吸附过程的钉扎形态量，不是滚动位移）；滚动若真把标题带走，
 *  页面体会先自己滚（页面体 scrollTop > 0）。
 *  标题单元素化批 2026-11-02：吸附胶囊 = .mobile-page-title 自身（装饰副本
 *  .mobile-page-title-mini 退役）—— 壳层读数改探 ::before（玻璃层 opacity）。 */
const TITLE_JS = `(() => {
  const sec = document.querySelector('.mobile-page[data-page="background"]');
  const title = sec.querySelector('.mobile-page-title');
  const bodyEl = sec.querySelector('.mobile-page-body');
  const tb = title.getBoundingClientRect();
  const bb = bodyEl.getBoundingClientRect();
  const before = getComputedStyle(title, '::before');
  return {
    paneScroll: sec.classList.contains('mpt-pane-scroll'),
    rolled: sec.classList.contains('rolled'),
    p: Number(getComputedStyle(title).getPropertyValue('--mtitle-p')) || 0,
    titlePos: getComputedStyle(title).position,
    titleTop: Math.round(tb.top), titleH: Math.round(tb.height),
    titleLayoutTop: Math.round(bb.top + title.offsetTop),
    bodyScrollTop: bodyEl.scrollTop,
    bodySurplus: bodyEl.scrollHeight - bodyEl.clientHeight,
    titleOpacity: Math.round(parseFloat(getComputedStyle(title).opacity) * 100) / 100,
    shellOpacity: Math.round(parseFloat(before.opacity) * 100) / 100,
    shellTop: Math.round(tb.top), shellH: Math.round(tb.height),
    scrollTop: (() => {
      const c = sec.querySelector('.mobile-page-body > #bg-dropdown');
      return c ? c.scrollTop : -1;
    })(),
  };
})()`;

/** 把「内容区那一个滚动器」滚到底（修前可能有多处，取实测唯一/最外层）。 */
async function scrollContentToBottom(page) {
  return page.evaluate(() => {
    const sec = document.querySelector('.mobile-page[data-page="background"]');
    const body = sec.querySelector('.mobile-page-body');
    const over = (el) => {
      const cs = getComputedStyle(el);
      return (cs.overflowY === 'auto' || cs.overflowY === 'scroll') && el.scrollHeight - el.clientHeight > 0;
    };
    const hit = [body, ...body.querySelectorAll('*')].filter(over);
    // 取最外层（重构后应恰一个 = 载体）
    const target = hit[0] || body;
    target.scrollTop = target.scrollHeight;
    target.dispatchEvent(new Event('scroll', { bubbles: false }));
    return { n: hit.length, sel: target.id || target.className, scrollTop: target.scrollTop };
  });
}

/** 底部几何 + 末条可见性 + 行内两行文字不重叠（V2/V3/V4 判据面）。 */
const BOTTOM_JS = `(() => {
  const sec = document.querySelector('.mobile-page[data-page="background"]');
  const carrier = sec.querySelector('.mobile-page-body > #bg-dropdown');
  const dock = document.getElementById('mobile-dock');
  const dockTop = dock ? Math.round(dock.getBoundingClientRect().top) : -1;
  const sections = [...carrier.querySelectorAll('.bg-section')].filter((s) => s.getClientRects().length);
  const lastSec = sections[sections.length - 1];
  const rows = lastSec ? [...lastSec.querySelectorAll('.bg-task-row')].filter((r) => r.getClientRects().length) : [];
  const last = rows[rows.length - 1] || null;
  const lb = last ? last.getBoundingClientRect() : null;
  const vh = window.innerHeight;
  const groupTitles = [...carrier.querySelectorAll('.mh-bg-grp, .bg-dropdown-header')]
    .filter((el) => el.getClientRects().length)
    .map((el) => el.textContent.trim());
  // 行内两行文字不重叠（主行 vs 次行）
  let overlap = 0; let overlapSample = '';
  for (const row of carrier.querySelectorAll('.bg-task-row')) {
    const main = row.querySelector('.bg-task-main');
    const sub = row.querySelector('.bg-task-subline');
    if (!main || !sub) continue;
    const a = main.getBoundingClientRect(); const b = sub.getBoundingClientRect();
    if (a.height === 0 || b.height === 0) continue;
    if (b.top < a.bottom - 0.5) { overlap += 1; if (!overlapSample) overlapSample = (row.textContent || '').slice(0, 30); }
  }
  const carrierB = carrier.getBoundingClientRect();
  const bodyB = sec.querySelector('.mobile-page-body').getBoundingClientRect();
  // 行热区（手机交互标准 ≥44）：行体最小高实测
  const rowHs = [...carrier.querySelectorAll('.bg-task-row')]
    .filter((r) => r.getClientRects().length)
    .map((r) => Math.round(r.getBoundingClientRect().height));
  const minRowH = rowHs.length ? Math.min(...rowHs) : -1;
  // 底部安全区实测：内容区底内距（padding-bottom）与页面体底缘 vs dock 顶缘
  const cs = getComputedStyle(carrier);
  const safeBottom = parseFloat(cs.paddingBottom) || 0;
  return {
    dockTop,
    minRowH, rowCount: rowHs.length, carrierPadBottom: safeBottom,
    vh,
    lastRowText: last ? (last.textContent || '').trim().slice(0, 40) : '',
    lastRowTop: lb ? Math.round(lb.top) : -1,
    lastRowBottom: lb ? Math.round(lb.bottom) : -1,
    lastRowVisible: !!lb && lb.top >= 0 && lb.bottom <= vh && lb.height > 0,
    gapToDock: lb ? Math.round(dockTop - lb.bottom) : -1,
    groupTitles,
    sectionCount: sections.length,
    overlap,
    overlapSample,
    carrierScrollTop: carrier.scrollTop,
    carrierSurplus: carrier.scrollHeight - carrier.clientHeight,
    bodyScrollWidth: sec.querySelector('.mobile-page-body').scrollWidth,
    bodyClientWidth: sec.querySelector('.mobile-page-body').clientWidth,
    carrierScrollWidth: carrier.scrollWidth,
    carrierClientWidth: carrier.clientWidth,
    pageBodyBottom: Math.round(bodyB.bottom),
    pageBodyH: Math.round(bodyB.height),
    carrierBottom: Math.round(carrierB.bottom),
  };
})()`;

test.describe('mobile 375', () => {
  test.use({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: 'dark' });

  test('M single scroll container: whole background page is one continuous scroll', async ({ page }) => {
    const errors = watchErrors(page);
    await bootApp(page);
    await seedAgents(page);
    await openBackgroundPage(page);

    // ── V1 单一滚动容器 ──
    const s0 = await page.evaluate(STRUCT_JS);
    console.log('[V1 before-scroll] scrollers=' + JSON.stringify(s0.scrollers, null, 1));
    console.log('[V1] body=' + JSON.stringify(s0.body) + ' carrier=' + JSON.stringify(s0.carrier));
    console.log('[V1] lists=' + JSON.stringify(s0.lists, null, 1));
    expect(s0.count, `V1 内容区纵向滚动容器数（应 1，实测 ${JSON.stringify(s0.scrollers.map((x) => x.sel))}）`).toBe(1);
    expect(s0.scrollers[0].sel).toBe('#bg-dropdown');
    // 两条分组列表都不是滚动单元（滚动高 == 客户端高，无 max-height 小滚框）
    for (const l of s0.lists) {
      expect(l.scrollHeightEqualsClient, `V1 列表 ${l.section} 内滚余量应为 0`).toBe(true);
      expect(l.maxHeight, `V1 列表 ${l.section} 不得有 max-height`).toBe('none');
    }

    // ── 标题行为（判定腿）：主滚轴 = 载体（≠ 页面体）⇒ 大标题不随内容滚走 ──
    // 判据 .mpt-pane-scroll 由 bindRollProbe 在**滚动事件**里按主滚轴归属翻写
    // （navController.js:246），故这里按真实用户动作取样：静止 → 小幅滚动 → 触底
    // → 回顶，逐态读标题几何（标题盒顶是否随内容位移）。
    const tRest = await page.evaluate(TITLE_JS);
    // 标题单元素化批 2026-11-02：载体 = 同一元素 sticky 原地收缩（旧两段式的
    // 「流内 static 大标题 + 绝对装饰副本」退役），故钉的是 sticky。
    expect(tRest.titlePos, '标题腿：大标题钉在原位（sticky）').toBe('sticky');
    expect(tRest.titleH, '标题腿：静止态大标题盒高 52').toBe(52);
    await page.evaluate(() => {
      const c = document.querySelector('.mobile-page[data-page="background"] .mobile-page-body > #bg-dropdown');
      c.scrollTop = 30;
      c.dispatchEvent(new Event('scroll', { bubbles: false }));
    });
    await page.waitForTimeout(200);
    const t0 = await page.evaluate(TITLE_JS);
    expect(t0.paneScroll, '标题腿：.mpt-pane-scroll 成立（主滚轴在面板内，不是页面体）').toBe(true);
    // 标题单元素化批 2026-11-02：标题的**流内**位置随形态量移动（margin-top
    // 0→4px 的插值，p≈0.46 ⇒ +2px），那不是滚动位移 —— 判据改为「流内位移
    // 只能 ≤ 形态量上限 4px」：真若随内容滚走，scrollTop 30 会带来 ≥30px 的位移。
    const flowShift = Math.abs(t0.titleLayoutTop - tRest.titleLayoutTop);
    expect(flowShift, `标题腿：面板滚动时大标题不随内容滚走（流内位移 ${flowShift}px 只能来自形态量 ≤4px）`).toBeLessThanOrEqual(4);
    expect(t0.bodyScrollTop, '标题腿：页面体自身不滚（scrollTop 恒 0）').toBe(0);

    // ── V2/V3 连贯滚动到底 + 不遮挡 ──
    const scrolled = await scrollContentToBottom(page);
    await page.waitForTimeout(300);
    const b = await page.evaluate(BOTTOM_JS);
    console.log('[V2/V3] scroll=' + JSON.stringify(scrolled) + ' bottom=' + JSON.stringify(b));
    expect(scrolled.n, 'V2 滚动做什么：内容区滚动器数').toBe(1);
    expect(b.carrierSurplus, 'V2 载体应真有滚动余量（夹具充足）').toBeGreaterThan(0);
    expect(b.lastRowVisible, `V2 最后一个分组的最后一条应可见（top=${b.lastRowTop} bottom=${b.lastRowBottom} vh=${b.vh}）`).toBe(true);
    expect(b.gapToDock, 'V3 末条底缘与 dock 顶缘间距 ≥ 0').toBeGreaterThanOrEqual(0);

    // ── 底部安全区 + 行热区实测（报告 §5 同源读数）──
    console.log('[safe-area] pageBodyBottom=' + b.pageBodyBottom + ' dockTop=' + b.dockTop
      + ' gapToDock=' + b.gapToDock + ' carrierPadBottom=' + b.carrierPadBottom + 'px'
      + ' minRowH=' + b.minRowH + 'px (rows=' + b.rowCount + ')');
    expect(b.pageBodyBottom, '安全区：页面体底缘不越过 dock 顶缘').toBeLessThanOrEqual(b.dockTop);
    expect(b.carrierPadBottom, '安全区：载体底内距 ≥ 12px').toBeGreaterThanOrEqual(12);
    expect(b.minRowH, `热区：行最小高 ≥ 44px（实得 ${b.minRowH}px）`).toBeGreaterThanOrEqual(44);

    // ── 标题腿（滚到底）：大标题不随内容滚走、吸附态由同一元素胶囊档接棒 ──
    const t1 = await page.evaluate(TITLE_JS);
    console.log('[title] rest=' + JSON.stringify(tRest) + ' mid=' + JSON.stringify(t0) + ' bottom=' + JSON.stringify(t1));
    // 标题单元素化批 2026-11-02：流内位置含形态量（margin-top 0→4p），判据 =
    // 位移 ≤ 4px 上限（随内容滚走会是 ≥ 滚距的量级）。
    const flowShift1 = Math.abs(t1.titleLayoutTop - tRest.titleLayoutTop);
    expect(flowShift1, `标题腿：滚到底后大标题不随内容滚走（位移 ${flowShift1}px ≤ 形态量上限 4px）`).toBeLessThanOrEqual(4);
    expect(t1.p, '标题腿：滚到底进度 p = 1').toBe(1);
    expect(t1.rolled, '标题腿：吸附判据 .rolled 成立').toBe(true);
    expect(t1.shellOpacity, '标题腿：吸附壳层（同一元素 ::before 玻璃层）opacity = 1').toBe(1);

    // ── V5 分组卡片仍在 ──
    expect(b.groupTitles.some((x) => /智能体/.test(x)), `V5 分组标题「智能体 · N」应仍渲染（实得 ${JSON.stringify(b.groupTitles)}）`).toBe(true);
    expect(b.sectionCount, 'V5 两个分组节仍在').toBe(2);

    // ── V4 375px 无横向溢出 + 两行文字不重叠 ──
    expect(b.bodyScrollWidth, 'V4 页面体无横向溢出').toBe(b.bodyClientWidth);
    expect(b.carrierScrollWidth, 'V4 载体无横向溢出').toBe(b.carrierClientWidth);
    expect(b.overlap, `V4 行内两行文字不重叠（实得 overlap=${b.overlap} ${b.overlapSample}）`).toBe(0);

    // 回顶（回滚反向成立、标题展开）
    await page.evaluate(() => {
      const c = document.querySelector('.mobile-page[data-page="background"] .mobile-page-body > #bg-dropdown');
      c.scrollTop = 0;
      c.dispatchEvent(new Event('scroll', { bubbles: false }));
    });
    await page.waitForTimeout(250);
    const t2 = await page.evaluate(TITLE_JS);
    expect(t2.p, '标题腿：回顶进度回 0').toBe(0);
    expect(t2.titleH, '标题腿：回顶大标题复现（盒高 52）').toBe(52);
    expect(errors).toEqual([]);
  });
});

test.describe('desktop 1280', () => {
  test.use({ viewport: { width: 1280, height: 800 }, colorScheme: 'dark' });

  test('D single scroll container on wide viewport (no double scrollbar inside cards)', async ({ page }) => {
    const errors = watchErrors(page);
    await bootApp(page);
    await seedAgents(page);
    // 桌面：智能体键开单族面板（bg-view-agents 隐任务节）——两节同源的滚动结构
    // 由同一条规则承担，任一视图都不得出现卡片内滚动。
    await page.click('#bgagent-indicator');
    await expect(page.locator('#bg-dropdown')).toBeVisible();
    await page.waitForTimeout(300);
    const d = await page.evaluate(() => {
      const c = document.getElementById('bg-dropdown');
      const over = (el) => {
        const cs = getComputedStyle(el);
        return (cs.overflowY === 'auto' || cs.overflowY === 'scroll') && el.scrollHeight - el.clientHeight > 0;
      };
      const scrollers = [];
      for (const el of [c, ...c.querySelectorAll('*')]) {
        if (!over(el)) continue;
        scrollers.push({
          sel: el.id ? '#' + el.id : '.' + String(el.className || el.tagName).split(' ')[0],
          scrollHeight: el.scrollHeight, clientHeight: el.clientHeight,
          surplus: el.scrollHeight - el.clientHeight,
          maxHeight: getComputedStyle(el).maxHeight,
        });
      }
      const lists = [...c.querySelectorAll('.bg-dropdown-list')].map((l) => ({
        section: String(l.parentElement.className || ''),
        scrollHeight: l.scrollHeight, clientHeight: l.clientHeight,
        maxHeight: getComputedStyle(l).maxHeight,
      }));
      return {
        scrollers,
        count: scrollers.length,
        panelMaxHeight: getComputedStyle(c).maxHeight,
        panelOverflowY: getComputedStyle(c).overflowY,
        panelSurplus: c.scrollHeight - c.clientHeight,
        lists,
        vw: window.innerWidth,
      };
    });
    console.log('[V6 desktop] ' + JSON.stringify(d, null, 1));
    expect(d.panelOverflowY, 'V6 桌面：载体是滚动容器').toBe('auto');
    expect(d.count, 'V6 桌面：面板内纵向滚动器数应 1（无双滚动条）').toBe(1);
    expect(d.scrollers[0].sel).toBe('#bg-dropdown');
    for (const l of d.lists) {
      expect(l.maxHeight, 'V6 桌面：列表不得有 max-height 小滚框').toBe('none');
      expect(l.scrollHeight, `V6 桌面：列表非滚动单元（${l.section}）`).toBe(l.clientHeight);
    }
    // 滚到底：末条不越出面板可视区
    const tail = await page.evaluate(() => {
      const c = document.getElementById('bg-dropdown');
      c.scrollTop = c.scrollHeight;
      const rows = [...c.querySelectorAll('.bg-task-row')].filter((r) => r.getClientRects().length);
      const last = rows[rows.length - 1];
      const lb = last ? last.getBoundingClientRect() : null;
      const cb = c.getBoundingClientRect();
      return { lastBottom: lb ? Math.round(lb.bottom) : -1, panelBottom: Math.round(cb.bottom), panelTop: Math.round(cb.top), lastTop: lb ? Math.round(lb.top) : -1 };
    });
    console.log('[V6 desktop tail] ' + JSON.stringify(tail));
    expect(tail.lastBottom, 'V6 桌面：滚到底末条落在面板内').toBeLessThanOrEqual(tail.panelBottom + 1);
    expect(tail.lastTop, 'V6 桌面：滚到底末条可见').toBeGreaterThanOrEqual(tail.panelTop - 1);
    expect(errors).toEqual([]);
  });
});

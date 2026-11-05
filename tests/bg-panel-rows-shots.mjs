// bg-panel-rows-shots.mjs — 「后台智能体 / 后台任务三行结构 + 扁平取消钮」批
// （作者令 2026-11-05 17:53）实拍 + DOM 取证脚本。
//
// 作者原话（逐字）：「优化一下后台agent的显示。分三行显示。第一行 name · 状态（用
// 图标显示）·time。第二行Badge。第三行 当前动作。 后台任务也是。name·状态·输出·
// time 第二行 badge  同时取消按钮需要设计一下，用我们最新的扁平风按钮风格。」
//
// 装置口径（与本仓既有 UI 批同构，见 tests/bg-flow-chain-visibility-shots.mjs）：
//   **零端口零进程**的真浏览器实拍 —— page.route 从磁盘服务 src/main/resources/web
//   的真实源文件（真 CSS 层叠、真 index.html 装配、真 lucide 图标、真 ws.js 分发
//   路径），WS 走 Playwright routeWebSocket 的**生产帧**（agentStart / activeAgents /
//   activeBgTasks 同形状）。结构上不可能碰到宿主 6138 实例，也不需要 sbt/jar。
//   ⇒ 「真跑」= 真 Chromium × 真前端代码 × 真渲染；不是手绘，也不是静态假图。
//
// 改前/改后对照：本脚本吃 `BG_WEB`（静态根）。
//   BG_WEB=<main 检出>/src/main/resources/web  → 改前（旧两行形态）
//   （缺省）                                    → 改后（本 worktree）
//
// Run（worktree 根）：
//   node tests/bg-panel-rows-shots.mjs                       # 改后实拍 + 取证
//   BG_WEB=/path/to/main/src/main/resources/web BG_OUT=/tmp/before \
//     node tests/bg-panel-rows-shots.mjs                     # 改前实拍
// 产物：BG_OUT/<scene>.png + BG_OUT/evidence.json

import { chromium } from '/Users/kaiyu/Claude code/Stand/node_modules/playwright/index.mjs';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = normalize(process.env.BG_WEB || join(HERE, '..', 'src', 'main', 'resources', 'web'));
const OUT = process.env.BG_OUT || join(HERE, '..', 'evidence', '20261105_agent-panel-rows', 'shots');
const THEMES = (process.env.BG_THEMES || 'dark,light').split(',').map((s) => s.trim()).filter(Boolean);
mkdirSync(OUT, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

const ROOT_SID = 'rows-root-session';
const T0 = Date.now();
const evidence = { web: WEB, out: OUT, scenes: {} };
let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};

// ── 数据面（直种 state：与本仓 bg-merged-panel.spec 同一注入口径）─────────────
/** 智能体行：一条一行地覆盖**全部状态族** + 三行结构的三个槽都有内容。 */
function agentEntries() {
  const base = (over) => Object.assign({
    kind: 'Delegate', status: 'Processing', startedAt: T0 - 11 * 60_000,
    project: '', taskId: '', taskTitle: '', delegateName: '', mailAddress: '',
    retryCount: 0, parentSessionId: ROOT_SID, currentTool: '', lastActivityMs: T0 - 20_000,
  }, over);
  return [
    // ① 运行中 + 满徽标 + 第三行动作（↳ 工具）
    { id: 'delegate-rows-0001', info: base({
      name: '搜索顶栏截断修复批', sessionId: 'delegate-rows-0001', delegateName: '搜索顶栏截断',
      mailAddress: 'node:delegate-rows-0001', project: 'Stand', taskId: '1658', taskTitle: 'UI 批 · 三行结构',
      retryCount: 1, currentTool: 'Bash(node tools/stand-plugins list)',
      task: '【UI 修复批 · 联系人 / 消息页搜索收成圆钮后，顶部内容被直接截断（应透明可透出下文）】',
    }) },
    // ② 候合并（waiting）——第三行「等待合并轮 · …」
    { id: 'delegate-rows-0002', info: base({
      name: '词条体系v2', sessionId: 'delegate-rows-0002', delegateName: '词条体系v2',
      kind: 'Delegate', status: 'Waiting', waiting: { mode: 'waiting' },
      project: 'Stand', task: '【规范+实施批 · 词条（term）体系 v2：词条化 + 标签 + 索引 + 查询 CLI】',
      startedAt: T0 - 72 * 60_000,
    }) },
    // ③ 自动恢复中（frozen 自动族）——第三行原因
    { id: 'delegate-rows-0003', info: base({
      name: '星云转动画', sessionId: 'delegate-rows-0003', delegateName: '星云转动画',
      status: 'Frozen', frozen: true, freezeKind: 'auto-recovering', frozenReason: 'network',
      needsHuman: false, frozenReasonLabel: '等待网络恢复',
      dormantHours: 0, project: 'Stand', startedAt: T0 - 71 * 60_000,
      task: '【转换批 · 三张超新星遗迹图 → living-ascii 动画（作者 2026-11-05 16:25 令）】',
    }) },
    // ④ 已完成（done）
    { id: 'delegate-rows-0004', info: base({
      name: '毛玻璃统一', sessionId: 'delegate-rows-0004', delegateName: '毛玻璃统一',
      status: 'Idle', done: true, project: 'Stand', startedAt: T0 - 95 * 60_000,
      task: '【设计统一批 · 「毛玻璃」成为唯一标准与唯一术语 + Canvas 按钮尺寸归一】',
    }) },
    // ⑤ 失败（error）
    { id: 'delegate-rows-0005', info: base({
      name: 'Header宽度回退', sessionId: 'delegate-rows-0005', delegateName: 'Header宽度回退',
      status: 'Error(provider 503)', project: 'Stand', startedAt: T0 - 29 * 60_000,
      task: '【UI 修复批 · 电脑端 Header 与输入框宽度对齐】',
    }) },
    // ⑥ 卡死（stuck）
    { id: 'delegate-rows-0006', info: base({
      name: 'IJBBEOT升级', sessionId: 'delegate-rows-0006', delegateName: 'IJBBEOT升级',
      status: 'Processing', stuck: { idleSecs: 640, action: 'restart' }, retryCount: 1,
      project: 'Stand', startedAt: T0 - 62 * 60_000,
      task: '【运维批 · DESKTOP-IJBBEOT 的 Nebflow 升级（第二阶段：换包 + 起服 + 验证）】',
    }) },
    // ⑦ 空闲（idle 长空闲）——第三行「空闲 N 分钟」
    { id: 'delegate-rows-0007', info: base({
      name: '引用图叠卡', sessionId: 'delegate-rows-0007', delegateName: '引用图叠卡',
      status: 'Idle', lastActivityMs: T0 - 70 * 60_000, project: 'Stand',
      startedAt: T0 - 71 * 60_000,
      task: '【UI 修复批 · 消息里引用的多张图片要改成「叠卡」，不再竖排逐个铺开】',
    }) },
    // ⑧ 超长名（演示截断 + title/展开拿全名）
    { id: 'delegate-rows-0008', info: base({
      name: '手机端「待办 / 定时任务」页改成 Apple 提醒事项式铺满布局 + Canvas 同款悬浮加号',
      sessionId: 'delegate-rows-0008',
      delegateName: '手机端「待办 / 定时任务」页改成 Apple 提醒事项式铺满布局 + Canvas 同款悬浮加号',
      project: 'Stand', startedAt: T0 - 13 * 60_000,
      task: '【UI 批 · 手机端「待办 / 定时任务」页改成 Apple 提醒事项式铺满布局 + Canvas 同款悬浮加号】',
    }) },
  ];
}

/** 后台任务行：运行中（取消钮）/ 慢心跳（idle）/ 卡死 / 取消中 / 完成。 */
function taskEntries() {
  return [
    { taskId: 'task-rows-0001', description: 'Start isolated instance on 8099', status: 'running',
      startedAt: T0 - 43 * 60_000, sessionId: ROOT_SID, kind: 'local', origin: 'nebula', originLabel: 'Nebula',
      ownerMode: 'kernel', ownerName: '毛玻璃统一',
      heartbeat: { alive: true, idleMs: 12_000, outputLines: 0 } },
    { taskId: 'task-rows-0002', description: 'node scripts/check-flat-buttons.mjs（扁平按钮门禁）', status: 'running',
      startedAt: T0 - 2 * 60_000, sessionId: ROOT_SID, kind: 'local', origin: 'dispatcher', originLabel: 'dispatcher',
      ownerMode: 'dispatcher', heartbeat: { alive: true, idleMs: 200_000, outputLines: 128 } },
    { taskId: 'task-rows-0003', description: 'sbt test（BgTaskRegistry 回归）', status: 'running',
      startedAt: T0 - 9 * 60_000, sessionId: ROOT_SID, kind: 'local', origin: 'node', originLabel: '实现节点',
      heartbeat: { alive: true, idleMs: 700_000, outputLines: 860 } },
    { taskId: 'task-rows-0004', description: 'python3 -m http.server 8099（静态预览）', status: 'cancelling',
      startedAt: T0 - 5 * 60_000, sessionId: ROOT_SID, kind: 'local', origin: 'nebula', originLabel: 'Nebula',
      heartbeat: { alive: true, idleMs: 9_000, outputLines: 3 } },
    { taskId: 'task-rows-0005', description: 'npm run build-web（前端产物）', status: 'completed',
      startedAt: T0 - 8 * 60_000, finishedAt: T0 - 20_000, durationMs: 460_000, exitCode: 0,
      sessionId: ROOT_SID, kind: 'local', origin: 'nebula', originLabel: 'Nebula',
      heartbeat: { alive: false, idleMs: 460_000, outputLines: 42 } },
  ];
}

// ── boot ────────────────────────────────────────────────────────────────────
async function boot({ theme, width = 1440, height = 900, mobile = false }) {
  const browser = await chromium.launch();
  const context = await browser.newContext({
    viewport: { width, height },
    colorScheme: theme,
    deviceScaleFactor: 2,
    isMobile: mobile,
    hasTouch: mobile,
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
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'x' });
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'nf' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList', sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: ROOT_SID,
    }));
    // 快照回包 = 同一份夹具（**开面板会发 getActiveAgents**；回空包会让终态行被
    // 合并规则①就地清掉——见 main.js activeAgents 合并注，这是生产行为，不是缺陷）。
    // 只有在快照里仍然列出的行才能「同屏展示全部状态」，故把夹具原样回成快照形。
    const sendAgents = () => ws.send(JSON.stringify({
      type: 'activeAgents',
      agents: agentEntries().map((a) => ({
        agentId: a.id, sessionId: a.info.sessionId, agentName: a.info.name,
        rootSessionId: ROOT_SID, kind: a.info.kind, status: a.info.status,
        startedAt: a.info.startedAt, task: a.info.task, project: a.info.project,
        delegateName: a.info.delegateName, mailAddress: a.info.mailAddress,
        taskId: a.info.taskId, taskTitle: a.info.taskTitle, retryCount: a.info.retryCount,
        currentTool: a.info.currentTool, lastActivityMs: a.info.lastActivityMs,
        standbySince: a.info.waiting ? Date.now() : undefined,
      })),
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      else if (m.type === 'getActiveAgents') sendAgents();
      else if (m.type === 'getBgTasks') ws.send(JSON.stringify({ type: 'activeBgTasks', tasks: {} }));
    });
  });
  await page.goto('http://127.0.0.1:8932/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 20000 });
  await page.waitForTimeout(400);
  return { browser, context, page, errors };
}

/** 直种智能体行 + 任务行（生产帧同形状的 state 直种：结构断言与实拍同源）。 */
async function seed(page) {
  await page.evaluate(async ({ sid, agents, tasks }) => {
    const s = (await import('/js/state.js')).default;
    const bucket = {};
    for (const a of agents) bucket[a.id] = a.info;
    s.sessionBgAgents[sid] = bucket;
    s.sessionBgTasks[sid] = tasks;
    s.updateBgAgentIndicator();
    s.updateBgTasksUI && s.updateBgTasksUI(null);
  }, { sid: ROOT_SID, agents: agentEntries(), tasks: taskEntries() });
  await page.waitForTimeout(150);
}

async function openDesktopTasks(page) {
  await page.evaluate(() => document.getElementById('bg-indicator').click());
  await page.waitForSelector('.bg-section-tasks .bg-task-row', { timeout: 8000 });
  await page.waitForTimeout(250);
}
async function openDesktopAgents(page) {
  await page.evaluate(() => document.getElementById('bgagent-indicator').click());
  await page.waitForSelector('.bg-section-agents .bg-task-row', { timeout: 8000 });
  await page.waitForTimeout(250);
}
async function openMobileBackground(page) {
  await page.locator('#mobile-dock .dock-key[data-dock="workbench"]').click();
  await page.locator('.mobile-page[data-page="workbench"] .mf-hub-row[data-hub-page="background"]').click();
  await page.waitForFunction(() => {
    const el = document.querySelector('.mobile-page[data-page="background"]');
    return !!el && el.classList.contains('live');
  }, null, { timeout: 10000 });
  await page.waitForSelector('.mobile-page[data-page="background"] #bg-dropdown .bg-task-row', { timeout: 10000 });
  await page.waitForTimeout(400);
}

// ── 取证 ────────────────────────────────────────────────────────────────────
const ROW_JS = `(() => {
  const rows = [...document.querySelectorAll('#bg-dropdown .bg-task-row')]
    .filter((r) => r.getClientRects().length);
  const rect = (el) => { if (!el) return null; const b = el.getBoundingClientRect();
    return { top: Math.round(b.top * 10) / 10, bottom: Math.round(b.bottom * 10) / 10,
             height: Math.round(b.height * 10) / 10, left: Math.round(b.left), right: Math.round(b.right) }; };
  // 仅**视觉可见**的文字（剥掉 sr-only 的状态词——那是读屏/断言的文本面，不是上屏面）。
  const visibleText = (el) => {
    if (!el) return '';
    const c = el.cloneNode(true);
    c.querySelectorAll('.bg-row-sr').forEach((n) => n.remove());
    return c.textContent.replace(/\\s+/g, ' ').trim();
  };
  return rows.map((r) => {
    const l1 = r.querySelector('.bg-task-row-line1');
    const l2 = r.querySelector('.bg-task-row-line2');
    const l3 = r.querySelector('.bg-task-row-line3');
    const name = r.querySelector('.bg-task-name');
    const st = r.querySelector('.bg-row-status, .bg-task-state');
    const cs = st ? getComputedStyle(st) : null;
    return {
      section: r.closest('.bg-section') ? r.closest('.bg-section').className.replace('bg-section ', '') : '',
      level: r.getAttribute('data-bg-row-level') || '',
      name: name ? name.textContent : '',
      nameTitle: name ? name.getAttribute('title') || '' : '',
      nameTruncated: name ? name.scrollWidth > name.clientWidth + 1 : false,
      statusTone: st ? st.getAttribute('data-tone') : '',
      statusAria: st ? st.getAttribute('aria-label') : '',
      statusColor: cs ? cs.color : '',
      statusIcon: st && st.querySelector('svg') ? (st.querySelector('svg').getAttribute('class') || '') : '',
      statusSrText: st && st.querySelector('.bg-row-sr') ? st.querySelector('.bg-row-sr').textContent : '',
      line1: rect(l1), line2: rect(l2), line3: rect(l3),
      line1Text: visibleText(l1),
      line2Text: visibleText(l2),
      line3Text: visibleText(l3),
      rowRect: rect(r),
      rowScrollW: r.scrollWidth, rowClientW: r.clientWidth,
      cancel: !!r.querySelector('.bg-task-cancel'),
    };
  });
})()`;

async function readRows(page) { return page.evaluate(ROW_JS); }

function assertThreeLines(tag, rows, minLines = 3) {
  const withL3 = rows.filter((r) => r.line3);
  if (minLines >= 3) {
    ok(`${tag} 三行 = 三个独立行盒（top 严格递增、互不重叠）`,
      withL3.length > 0 && withL3.every((r) => r.line1 && r.line2
        && r.line2.top >= r.line1.bottom - 0.5
        && r.line3.top >= r.line2.bottom - 0.5),
      `带第三行 ${withL3.length}/${rows.length}；行高 ${rows.map((r) => r.rowRect.height).join(',')}`);
  } else {
    ok(`${tag} 两行 = 两个独立行盒（后台任务行口径：L1 name·状态·输出·time / L2 badge）`,
      rows.every((r) => r.line1 && r.line2 && r.line2.top >= r.line1.bottom - 0.5),
      `行高 ${rows.map((r) => r.rowRect.height).join(',')}`);
  }
  const l2bad = rows.filter((r) => r.line1 && r.line2 && !(r.line2.top >= r.line1.bottom - 0.5));
  ok(`${tag} 第二行（badges）独立成行`, l2bad.length === 0, `违规 ${l2bad.length}`);
  const overflow = rows.filter((r) => r.rowScrollW > r.rowClientW + 1);
  ok(`${tag} 行内零横向溢出`, overflow.length === 0, `违规 ${overflow.length}`);
}

// ── 场景 ────────────────────────────────────────────────────────────────────
for (const theme of THEMES) {
  // ① 桌面 · 智能体列表
  {
    const { browser, page, errors } = await boot({ theme });
    await seed(page);
    await openDesktopAgents(page);
    const rows = await readRows(page);
    evidence.scenes[`desktop-${theme}-agents`] = { rows };
    assertThreeLines(`[desktop/${theme}/agents]`, rows);
    ok(`[desktop/${theme}/agents] 状态位只剩图标（无中文状态词上屏）`,
      rows.every((r) => !/进行中|候合并|自动恢复中/.test(r.line1Text)),
      rows.map((r) => r.line1Text).join(' | ').slice(0, 120));
    ok(`[desktop/${theme}/agents] 状态位都有 aria-label 文本兜底`,
      rows.every((r) => r.statusAria && r.statusAria.length > 0));
    await page.locator('#bg-dropdown').screenshot({ path: join(OUT, `desktop-${theme}-agents.png`) });
    evidence.scenes[`desktop-${theme}-agents`].errors = errors;
    ok(`[desktop/${theme}/agents] 零 console error`, errors.length === 0, errors.slice(0, 2).join(' / '));
    await browser.close();
  }

  // ② 桌面 · 后台任务列表（取消钮）
  {
    const { browser, page, errors } = await boot({ theme });
    await seed(page);
    await openDesktopTasks(page);
    const rows = await readRows(page);
    evidence.scenes[`desktop-${theme}-tasks`] = { rows };
    assertThreeLines(`[desktop/${theme}/tasks]`, rows, 2);
    const cancelInfo = await page.evaluate(() => {
      const b = document.querySelector('#bg-dropdown .bg-task-cancel');
      if (!b) return null;
      const cs = getComputedStyle(b);
      const r = b.getBoundingClientRect();
      return {
        cls: b.className, text: b.textContent, title: b.getAttribute('title'),
        color: cs.color, bg: cs.backgroundColor, border: cs.borderColor,
        borderRadius: cs.borderRadius, fontSize: cs.fontSize,
        boxShadow: cs.boxShadow, backgroundImage: cs.backgroundImage,
        transform: cs.transform, w: Math.round(r.width), h: Math.round(r.height),
      };
    });
    evidence.scenes[`desktop-${theme}-tasks`].cancel = cancelInfo;
    ok(`[desktop/${theme}/tasks] 取消钮挂扁平基类（ds-btn + ds-btn--danger）`,
      !!cancelInfo && /ds-btn/.test(cancelInfo.cls) && /ds-btn--danger/.test(cancelInfo.cls),
      cancelInfo ? cancelInfo.cls : 'no button');
    ok(`[desktop/${theme}/tasks] 取消钮零阴影/零渐变（扁平）`,
      !!cancelInfo && cancelInfo.boxShadow === 'none' && cancelInfo.backgroundImage === 'none',
      cancelInfo ? `shadow=${cancelInfo.boxShadow} bgimg=${cancelInfo.backgroundImage}` : '');
    ok(`[desktop/${theme}/tasks] 取消钮危险语义可见（danger 字色 + 文案「取消」+ title 说明）`,
      !!cancelInfo && /取消/.test(cancelInfo.text) && !!cancelInfo.title && /不可恢复/.test(cancelInfo.title),
      cancelInfo ? `${cancelInfo.color} · ${cancelInfo.text} · ${cancelInfo.title}` : '');
    await page.locator('#bg-dropdown').screenshot({ path: join(OUT, `desktop-${theme}-tasks.png`) });
    await page.locator('#bg-dropdown .bg-task-row[data-task-id="task-rows-0001"]')
      .screenshot({ path: join(OUT, `desktop-${theme}-cancel-row.png`) }).catch(() => {});
    evidence.scenes[`desktop-${theme}-tasks`].errors = errors;
    ok(`[desktop/${theme}/tasks] 零 console error`, errors.length === 0);
    await browser.close();
  }

  // ③ 手机 375 · 后台页（两节合并）
  {
    const { browser, page, errors } = await boot({ theme, width: 375, height: 812, mobile: true });
    await seed(page);
    await openMobileBackground(page);
    const rows = await readRows(page);
    evidence.scenes[`mobile375-${theme}`] = { rows };
    assertThreeLines(`[mobile375/${theme}]`, rows);
    const geo = await page.evaluate(() => {
      const sec = document.querySelector('.mobile-page[data-page="background"] .mobile-page-body > #bg-dropdown');
      const rows = [...sec.querySelectorAll('.bg-task-row')].filter((r) => r.getClientRects().length);
      return {
        rowHeights: rows.map((r) => Math.round(r.getBoundingClientRect().height)),
        scrollW: sec.scrollWidth, clientW: sec.clientWidth,
        vw: window.innerWidth, vh: window.innerHeight,
      };
    });
    evidence.scenes[`mobile375-${theme}`].geo = geo;
    ok(`[mobile375/${theme}] 行高仍 ≥ 44（触控）`, geo.rowHeights.every((h) => h >= 44),
      JSON.stringify(geo.rowHeights));
    ok(`[mobile375/${theme}] 无横向溢出`, geo.scrollW <= geo.clientW + 1,
      `${geo.scrollW}/${geo.clientW}`);
    await page.locator('.mobile-page[data-page="background"] .mobile-page-body > #bg-dropdown')
      .screenshot({ path: join(OUT, `mobile375-${theme}.png`) });
    evidence.scenes[`mobile375-${theme}`].errors = errors;
    ok(`[mobile375/${theme}] 零 console error`, errors.length === 0);
    await browser.close();
  }
}

// ④ 全状态同时出现（状态图标表实拍）+ 名字展开取证（只跑暗色一档）
{
  const { browser, page, errors } = await boot({ theme: 'dark' });
  await seed(page);
  await openDesktopAgents(page);
  const rows = await readRows(page);
  evidence.scenes['states-dark'] = { rows };
  ok('[states] 八种状态同屏（active/idle/waiting/frozen/stuck/error/done + 超长名）',
    new Set(rows.map((r) => r.statusTone)).size >= 7,
    [...new Set(rows.map((r) => r.statusTone))].join(','));
  await page.locator('#bg-dropdown').screenshot({ path: join(OUT, 'states-dark.png') });

  // 名字完整获取 ①：title（桌面 hover）
  const longRow = await page.evaluate(() => {
    const r = [...document.querySelectorAll('#bg-dropdown .bg-task-row')]
      .find((x) => (x.querySelector('.bg-task-name') || {}).textContent.includes('Apple'));
    if (!r) return null;
    const n = r.querySelector('.bg-task-name');
    return { title: n.getAttribute('title'), text: n.textContent, truncated: n.scrollWidth > n.clientWidth + 1 };
  });
  evidence.scenes['states-dark'].longName = longRow;
  ok('[states] 超长名被截断但有 title 全文', !!longRow && longRow.truncated
    && longRow.title.length > longRow.text.length, JSON.stringify(longRow).slice(0, 160));

  // 名字完整获取 ②：点击展开（手机/触屏唯一路）——点前后 rect 高度 + 文案对比。
  // 改前树**没有**这条能力（点名字 = 开详情弹窗，面板当场隐藏），故整段允许失败：
  // 改前跑批只取「无展开」的读数与截图，不为旧形态的缺席中断取证。
  let expand = null;
  try {
    expand = await page.evaluate(() => {
      const r = [...document.querySelectorAll('#bg-dropdown .bg-task-row')]
        .find((x) => (x.querySelector('.bg-task-name') || {}).textContent.includes('Apple'));
      if (!r) return null;
      const n = r.querySelector('.bg-task-name');
      const before = { h: Math.round(n.getBoundingClientRect().height), cls: n.className,
        clientW: n.clientWidth, scrollW: n.scrollWidth };
      n.click();
      const after = { h: Math.round(n.getBoundingClientRect().height), cls: n.className,
        aria: n.getAttribute('aria-expanded'), clientW: n.clientWidth, scrollW: n.scrollW || n.scrollWidth };
      const rowAfter = Math.round(r.getBoundingClientRect().height);
      return { before, after, rowAfter };
    });
  } catch (e) {
    expand = { error: String(e).slice(0, 120) };
  }
  evidence.scenes['states-dark'].expand = expand;
  ok('[states] 点击名字展开 = 名字高度/行高变大且 aria-expanded=true',
    !!expand && !expand.error && expand.after.h > expand.before.h && expand.after.aria === 'true',
    expand ? (expand.error || `${expand.before.h}px → ${expand.after.h}px（行 ${expand.rowAfter}px）`) : 'null');
  try {
    await page.locator('#bg-dropdown').screenshot({ path: join(OUT, 'name-expanded-dark.png') });
  } catch { /* 改前形态无展开态：面板已随点击开详情而隐藏，跳过 */ }
  evidence.scenes['states-dark'].errors = errors;
  ok('[states] 零 console error', errors.length === 0, errors.slice(0, 2).join(' / '));
  await browser.close();
}

writeFileSync(join(OUT, 'evidence.json'), JSON.stringify(evidence, null, 2));
console.log(`\n证据：${join(OUT, 'evidence.json')}`);
console.log(failures === 0 ? 'ALL PASS' : `${failures} FAIL`);
process.exit(failures === 0 ? 0 : 1);

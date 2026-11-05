// bg-agentflow-orphans.spec.mjs — 后台智能体面板「野节点归置」批（2026-11-05 腿③）
//
// 作者令（2026-11-05）：面板里「很多的野节点」必须归置到**一个明确的归置面**——
// 不许静默丢弃，也不许混在正常树里假装有父。野节点的判定式 = **没有可解析父的行**
// （父可解析三条款逐条在案，见 bgAgents.js 的 bgOrphanRowKeys 注释）：
//   ① 父整体行 key 可定位（父在该行集里且有键）；
//   ② 父行同屏可见且键可定位（父是本轮行集里的任一行）；
//   ③ 父会话在册且链可解析（注册行在场 + 链可解析 ⇒ 必有整体行）。
// 三条皆不成立 ⇒ 野。
//
// 断言面（任务书 ①-④ + 反向门 + 单点 + 回收规则）：
//   ① 野分组存在（data-bg-orphan-group）且恰好 3 行；
//   ② 这 3 行 data-bg-orphan="1"、data-bg-depth="0"、不带正常树归属锚 data-bg-agentflow；
//   ③ 2 条有父行在树里（level=child、带 data-bg-agentflow=整体行 key）；
//   ④ 面板头计数 == 实际渲染行数（野行计入，一行不丢）；
//   ⑤ 反向门：野行在分组头之后（不与正常树混排）、全行集一个不少、既有「来自 …」
//      来源角标保留（那是事实）；
//   ⑥ 回收规则：野分组内零关闭/回收动作（只展示、零自动处置）；
//   ⑦ 单点：判定式是一个函数（bgOrphanRowKeys），渲染面只读它的结论。
//
// 数据面 = WS 帧直种 + delegateChainStub 夹具（rows），零后端、零 sbt、零 6138。
// Run: node /opt/homebrew/lib/node_modules/@playwright/test/cli.js test tests/bg-agentflow-orphans.spec.mjs --reporter=line

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

const ROOT_SID = 'orphan-root-session';
// 整体行（一次编排 = 一个 agentflow）。
const FLOW_SID = 'delegate-orphan-a1b2c3d4';
// 两条有父成员行（父 = 整体行，真实 parentSessionId 链接）。
const NODE_A = 'node-orphan-11112222';
const NODE_B = 'node-orphan-33334444';
// 三条野行（父不可解析，三种形态各一）：
const WILD_GONE = 'subagent-orphan-55556666';  // 父 = 已离场的 node 会话（不在册、不在行集）
const WILD_NO_PARENT = 'node-orphan-88889999'; // 父字段为空（无父）
const WILD_LEGACY = 'dd-orphan-99990000';      // 父 = 历史 uuid 会话（既非 delegate 也不在册）
const T0 = Date.now();

/** 主桶行（delegate live 行 / 快照 leftover 行同构）。 */
function rootStart(sessionId, over = {}) {
  return {
    type: 'agentStart',
    sessionId: ROOT_SID, rootSessionId: ROOT_SID,
    agentId: sessionId, nodeSessionId: sessionId,
    name: sessionId, taskDescription: '',
    ...over,
  };
}

/** 作用域桶成员行（真 spawn 形：rootSessionId = delegateSid 自根）。 */
function chainStart(sessionId, over = {}) {
  return {
    type: 'agentStart',
    sessionId: FLOW_SID, rootSessionId: FLOW_SID,
    agentId: sessionId, nodeSessionId: sessionId,
    name: sessionId, taskDescription: '',
    project: 'Nebflow',
    ...over,
  };
}

/** 注册行镜像夹具（DelegateRow 形）。 */
function mirrorRow(over = {}) {
  return {
    taskNo: '99', delegateId: FLOW_SID, title: '野节点归置批次',
    name: '野批', executor: 'nebflow', status: 'running', external: false,
    project: '', createdAt: T0 - 30 * 60_000, orchestrationMode: 'agentflow',
    mode: 'dispatcher', archivedAt: null, mergeQueue: null, mergeQueuePos: null,
    ...over,
  };
}

async function installRoutes(page, locale) {
  await page.addInitScript((lc) => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', lc);
    localStorage.setItem('stand_sw', '0');
  }, locale);
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    if (p.startsWith('/agents/')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'nf' });
    }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{}', configured: true, onboarding: 'done',
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: ROOT_SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
}

async function bootApp(page, locale = 'zh-CN') {
  await installRoutes(page, locale);
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

/** 走真实 ws.js 分发路径注入一帧。 */
function inject(page, obj) {
  return page.evaluate(async (o) => {
    const s = (await import('/js/state.js')).default;
    s.ws.onmessage({ data: JSON.stringify(o) });
  }, obj);
}

/** 装 stub 夹具（rows 读口）。 */
async function setFixture(page, fx) {
  await page.evaluate(async (f) => {
    const stub = await import('/js/delegateChainStub.js');
    stub.__setFixture(f);
  }, fx);
}

async function openPanel(page) {
  await page.waitForSelector('#bgagent-indicator:not(.hidden)', { timeout: 8000 });
  await page.click('#bgagent-indicator');
  await page.waitForSelector('#bg-dropdown:not(.hidden)', { timeout: 8000 });
  await page.waitForTimeout(200);
}

const LIST = '#bg-dropdown .bg-section-agents .bg-dropdown-list';

/**
 * 种一棵「2 条有父 + 3 条野」的行集：
 *   整体行（depth 0）→ 节点 A / 节点 B（父 = 整体行，真实父链接）；
 *   三条野行（父已离场 / 无父 / 父为历史 uuid）落主桶 leftover——没有可解析父。
 */
async function seedOrphans(page) {
  await setFixture(page, { rows: [mirrorRow()] });
  await inject(page, rootStart(FLOW_SID, { name: '野批', taskDescription: '野节点归置批次' }));
  await inject(page, chainStart(NODE_A, { status: 'Processing', name: '节点A', parentSessionId: FLOW_SID }));
  await inject(page, chainStart(NODE_B, { status: 'Processing', name: '节点B', parentSessionId: FLOW_SID }));
  // 野①：父 = 已离场的 node 会话（既不在行集、也不在册）。
  await inject(page, rootStart(WILD_GONE, {
    status: 'Completed', name: '野一', taskDescription: '父已离场', parentSessionId: 'node-gone-77770000',
  }));
  // 野②：完全没有父字段。
  await inject(page, rootStart(WILD_NO_PARENT, { status: 'Completed', name: '野二', taskDescription: '无父', parentSessionId: '' }));
  // 野③：父 = 历史 uuid 会话（dd-* 前缀本身永不成整体行）。
  await inject(page, rootStart(WILD_LEGACY, {
    status: 'Completed', name: '野三', taskDescription: '历史父', parentSessionId: 'aac1c33a-legacy',
  }));
}

/** 行集 dump（四断言的机械读数面）。 */
function dumpRows(page) {
  return page.evaluate((sel) => {
    const list = document.querySelector(sel);
    if (!list) return [];
    const kids = [...list.children];
    const idxOf = (el) => kids.indexOf(el);
    const rows = kids.filter((el) => el.classList.contains('bg-task-row')).map((el) => {
      const cs = getComputedStyle(el);
      return {
        idx: idxOf(el),
        level: el.getAttribute('data-bg-row-level') || '',
        depth: el.getAttribute('data-bg-depth'),
        orphan: el.getAttribute('data-bg-orphan'),
        agentflow: el.getAttribute('data-bg-agentflow'),
        entity: el.getAttribute('data-bg-entity') || '',
        sid: el.getAttribute('data-node-session-id') || el.getAttribute('data-child-session') || '',
        name: (el.querySelector('.bg-task-name') || {}).textContent || '',
        origin: (el.querySelector('.bg-task-orphan') || {}).textContent || '',
        buttons: el.querySelectorAll('button, [role="button"]').length,
        padLeft: parseFloat(cs.paddingLeft),
      };
    });
    const groupEl = kids.find((el) => el.hasAttribute('data-bg-orphan-group'));
    return {
      rows,
      group: groupEl ? {
        idx: idxOf(groupEl),
        depth: groupEl.getAttribute('data-bg-depth'),
        text: (groupEl.textContent || '').trim(),
        buttons: groupEl.querySelectorAll('button, [role="button"]').length,
        isRow: groupEl.classList.contains('bg-task-row'),
      } : null,
      listTotal: list.getAttribute('data-bg-row-total'),
    };
  }, LIST);
}

/** 面板头部计数（模型单点写点）。 */
function panelCounts(page) {
  return page.evaluate(() => ({
    header: (document.querySelector('#bgagent-dropdown .bg-dropdown-header') || {}).textContent || '',
    badge: (document.querySelector('#bgagent-indicator .bgagent-count') || {}).textContent || '',
    section: (document.querySelector('#bg-dropdown .mh-bg-grp') || {}).textContent || '',
    rowTotal: (document.querySelector('#bg-dropdown .bg-section-agents .bg-dropdown-list') || {}).getAttribute('data-bg-row-total') || '',
  }));
}

/** 采集页面错误（pageerror + console.error）。 */
function trackErrors(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push('console.error: ' + m.text()); });
  return errors;
}

const WILD_SIDS = [WILD_GONE, WILD_NO_PARENT, WILD_LEGACY];
const PARENTED_SIDS = [NODE_A, NODE_B];

// ── ① ② ③：归置面 + 三锚 + 有父行仍在树里 ─────────────────────────────────

test('① ② ③ 野分组恰好 3 行（depth 0 / 带 data-bg-orphan / 不带归属锚）；2 条有父行仍在树里', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page, 'zh-CN');
  await seedOrphans(page);
  await openPanel(page);

  const dump = await dumpRows(page);
  console.log('[①②③ dump] ' + JSON.stringify(dump));
  const rows = dump.rows;

  // ① 野分组存在（机器锚 + 组头深度 0）。
  expect(dump.group).toBeTruthy();
  expect(dump.group.depth).toBe('0');
  const wild = rows.filter((r) => r.orphan === '1');
  console.log('[① ] group=' + JSON.stringify(dump.group) + ' wildRows=' + wild.length
    + ' sids=' + JSON.stringify(wild.map((r) => r.sid)));
  expect(wild).toHaveLength(3);
  expect(new Set(wild.map((r) => r.sid))).toEqual(new Set(WILD_SIDS));

  // ② 三条野行：data-bg-orphan="1" + depth 0 + 不带正常树归属锚。
  for (const w of wild) {
    expect(w.orphan).toBe('1');
    expect(w.depth).toBe('0');
    expect(w.agentflow).toBe(null);       // 不假装有父：无归属锚
    expect(w.level).toBe('top');          // 不是树里的 head/child
    expect(w.entity).toBe('');            // 也不是整体行
  }

  // ③ 2 条有父行在树里，带归属锚 = 整体行定位键。
  const head = rows.find((r) => r.level === 'head');
  expect(head).toBeTruthy();
  const parented = rows.filter((r) => PARENTED_SIDS.includes(r.sid));
  console.log('[③ ] parented=' + JSON.stringify(parented.map((r) => ({ sid: r.sid, level: r.level, depth: r.depth, agentflow: r.agentflow }))));
  expect(parented).toHaveLength(2);
  for (const p of parented) {
    expect(p.level).toBe('child');
    expect(p.agentflow).toBe(head.sid);
    expect(p.depth).toBe('1');
    expect(p.orphan).toBe(null);
  }
  expect(errors).toEqual([]);
});

// ── ④：计数同源（野行计入，一行不丢）─────────────────────────────────────

test('④ 面板头计数 == 实际渲染行数（野行计入，一行不丢）', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page, 'zh-CN');
  await seedOrphans(page);
  await openPanel(page);

  const dump = await dumpRows(page);
  const counts = await panelCounts(page);
  const rendered = dump.rows.length;
  const headTotal = Number(String(counts.header).replace(/[^0-9]/g, ''));
  console.log('[④] renderedRows=' + rendered + ' headerTotal=' + headTotal
    + ' badge=' + JSON.stringify(counts.badge) + ' section=' + JSON.stringify(counts.section)
    + ' rowTotal=' + dump.listTotal);
  console.log('[④ sids] ' + JSON.stringify(dump.rows.map((r) => r.sid)));

  // 1 整体行 + 2 有父成员 + 3 野行 = 6 行（组头标签不是行，不入行集）。
  expect(rendered).toBe(6);
  expect(new Set(dump.rows.map((r) => r.sid))).toEqual(new Set([FLOW_SID, ...PARENTED_SIDS, ...WILD_SIDS]));
  // 签与列表同源（头 / 徽标 / 节签 / 模型写出值四面同数）。
  expect(headTotal).toBe(rendered);
  expect(String(counts.badge).trim()).toBe(String(rendered));
  expect(String(counts.section)).toContain('· ' + rendered);
  expect(String(dump.listTotal)).toBe(String(rendered));
  expect(errors).toEqual([]);
});

// ── ⑤：反向门（不与正常树混排 + 来源角标保留）────────────────────────────

test('⑤ 反向门：野行排在分组头之后（不与正常树混排），既有「来自 …」角标保留', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page, 'zh-CN');
  await seedOrphans(page);
  await openPanel(page);

  const dump = await dumpRows(page);
  const rows = dump.rows;
  const wild = rows.filter((r) => r.orphan === '1');
  const tree = rows.filter((r) => r.orphan !== '1');
  console.log('[⑤ order] groupIdx=' + dump.group.idx + ' wild=' + JSON.stringify(wild.map((r) => [r.sid, r.idx]))
    + ' tree=' + JSON.stringify(tree.map((r) => [r.sid, r.idx])));

  // 组头在每个野行之前；每条野行在整棵正常树之后（不混排）。
  for (const w of wild) expect(w.idx).toBeGreaterThan(dump.group.idx);
  const lastTreeIdx = Math.max(...tree.map((r) => r.idx));
  for (const w of wild) expect(w.idx).toBeGreaterThan(lastTreeIdx);
  for (const t of tree) expect(t.idx).toBeLessThan(dump.group.idx);

  // 既有「来自 …」来源角标保留（事实，不删）：父已离场的那条有角标。
  const gone = wild.find((r) => r.sid === WILD_GONE);
  expect(gone).toBeTruthy();
  expect(gone.origin.length).toBeGreaterThan(0);
  console.log('[⑤ origin] ' + WILD_GONE + ' → ' + JSON.stringify(gone.origin));

  // 野行可读（归置不是压扁：有缩进档、有主名）。
  for (const w of wild) {
    expect(w.name.trim().length).toBeGreaterThan(0);
    expect(Number.isFinite(w.padLeft)).toBe(true);
  }
  expect(errors).toEqual([]);
});

// ── ⑥：回收规则（只展示、零自动处置、零回收按钮）──────────────────────────

test('⑥ 回收规则：野分组内零关闭/回收动作（引擎侧无明确判据 ⇒ 只展示）', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page, 'zh-CN');
  await seedOrphans(page);
  await openPanel(page);

  const dump = await dumpRows(page);
  const wild = dump.rows.filter((r) => r.orphan === '1');
  console.log('[⑥] groupButtons=' + dump.group.buttons + ' rowButtons=' + JSON.stringify(wild.map((r) => [r.sid, r.buttons])));

  // 组头与每条野行零按钮（无回收 / 无关闭 / 无归档动作）。
  expect(dump.group.buttons).toBe(0);
  for (const w of wild) expect(w.buttons).toBe(0);

  // 静态钉：本批不引入任何野节点回收动作（不新增 WS 动作、不调归档接口）。
  const bgSrc = readFileSync(join(WEB, 'js', 'main', 'bgAgents.js'), 'utf8');
  expect(/data-bg-orphan-close/.test(bgSrc)).toBe(false);
  const block = bgSrc.slice(bgSrc.indexOf('function bgOrphanGroupHeadHtml'), bgSrc.indexOf('function bgOrphanRowHtml'));
  console.log('[⑥ static block] ' + JSON.stringify(block.replace(/\s+/g, ' ').slice(0, 160)));
  expect(block.length).toBeGreaterThan(0);
  expect(/sendWs\s*\(/.test(block)).toBe(false);
  expect(/archive/i.test(block)).toBe(false);
  expect(/button/i.test(block)).toBe(false);
  expect(errors).toEqual([]);
});

// ── ⑦：判定式单点 + 组头文案（zh/en 各一键）──────────────────────────────

test('⑦ 判定式单点（bgOrphanRowKeys 一个函数）且组头文案 = locale 事实词（zh/en 同键）', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page, 'zh-CN');
  await seedOrphans(page);
  await openPanel(page);

  const dump = await dumpRows(page);
  const words = await page.evaluate(async () => {
    const i18n = await import('/js/i18n.js');
    return { orphanGroup: i18n.t('subagents.orphanGroup'), locale: i18n.getLocale() };
  });
  console.log('[⑦ words] ' + JSON.stringify(words) + ' groupText=' + JSON.stringify(dump.group.text));
  expect(words.locale).toBe('zh-CN');
  expect(words.orphanGroup.length).toBeGreaterThan(0);
  expect(dump.group.text).toBe(words.orphanGroup);

  // 单点：判定式只有一个函数（导出面），渲染分支不自算野性。
  const src = readFileSync(join(WEB, 'js', 'main', 'bgAgents.js'), 'utf8');
  expect(/export function bgOrphanRowKeys\s*\(/.test(src)).toBe(true);
  const defs = src.split('export function bgOrphanRowKeys').length - 1;
  const calls = src.split('bgOrphanRowKeys(').length - 1;
  console.log('[⑦ counts] defs=' + defs + ' mentions=' + calls);
  expect(defs).toBe(1);
  expect(calls).toBe(2); // 定义处 1 + 唯一调用点（bgRowPlan）1
  // 归置锚只有一个写点（构造器单点，渲染分支不许各写一份）——判面 = 代码行
  // （注释里叙旧提到锚名不算写点，与 terms spec H5 的 codeOnly 同口径）。
  const codeOnly = src
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .split('\n').map((l) => l.replace(/\/\/.*$/, '')).join('\n');
  console.log('[⑦ anchor writes] ' + (codeOnly.split('data-bg-orphan="1"').length - 1));
  expect(codeOnly.split('data-bg-orphan="1"').length - 1).toBe(1);
  expect(codeOnly.split('data-bg-orphan-group').length - 1).toBe(1);
  expect(errors).toEqual([]);
});

test('⑦ en 组头文案：同一个 locale 键（en 一键，非硬编码）', async ({ page }) => {
  const errors = trackErrors(page);
  await bootApp(page, 'en');
  await seedOrphans(page);
  await openPanel(page);

  const dump = await dumpRows(page);
  const words = await page.evaluate(async () => {
    const i18n = await import('/js/i18n.js');
    return { orphanGroup: i18n.t('subagents.orphanGroup'), locale: i18n.getLocale() };
  });
  console.log('[⑦ en] ' + JSON.stringify(words) + ' groupText=' + JSON.stringify(dump.group.text));
  expect(words.locale).toBe('en');
  expect(words.orphanGroup).not.toBe('');
  expect(dump.group.text).toBe(words.orphanGroup);
  expect(dump.rows.filter((r) => r.orphan === '1')).toHaveLength(3);
  expect(errors).toEqual([]);
});

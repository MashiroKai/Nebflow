// mergequeue-pos.spec.mjs — 合并窗「排队位次」可见性真渲染验收（自包含，零实例依赖）。
//
// 批：排队位次可见性实施批 2026-09-14（作者 16:39 双裁 = 显示「数字 + 持有者双显」，
//     路线「案 A：引擎条件键 + 前端渲染」）。
// **queuepos 批 2026-09-15 契约修正**（作者现场报「节点显示的前面还有几个都一样 /
//     点开的详情面板里也没有」）：数字面从 `mergeQueue.ahead`（**阻塞集合的势**——同刻只有
//     一个 running 时全体排队者 ≡1，看起来一模一样）改为 `mergeQueuePos.position`
//     （**位次**——逐节点唯一，真源 = SEM-2 次序键 rank=(readyAt,createdAt,id) 升序）。
//     故本 spec 的徽标期望文案随契约改为「排队中 · 第 N 位 / 共 M」，**断言条数与判据
//     强度只增不减**（新增互异性断言）；`mergeQueue`（ahead/holders）语义仍逐字冻结
//     （脚注「被 XX 挡着」与降级红线不变）。详情面板的位次行由
//     `tests/queuepos-detail.spec.mjs` 覆盖（真渲染 + 亮暗双主题 + 引擎真实载荷）。
// 契约（前端只读不派生；真源 = 引擎条件键 `mergeQueue` + `mergeQueuePos`，见 ProjectTypes.scala）：
//   head 行徽标  形态「排队中 · 第 N 位 / 共 M」（N/M 取 `mergeQueuePos.position/total`）
//   等待脚注行   形态「被 XX 挡着」（XX = 持有者节点名，多持有者全列——`mergeQueue.holders`）
//   降级红线     位次不可计算（键缺失/形状漂移）/ 同键多项目 ⇒ **不渲染数字**（至多裸「排队中」）
//
// 🔴 **本文件全部排队态载荷均为 FIXTURE（人工构造，非生产实测）**：载荷由本文件内联
//    定义并经 `page.route` 注入，不来自任何真实运行实例。真实排队态的隔离实例构造未
//    在本批完成（见节点报告「未证项」）——🔴 禁把本 fixture 读数冒充生产实测。
//    （本批同队的 `queuepos-detail.spec.mjs` 用的是**引擎真实 dump 载荷**，非手写。）
//
// 运行：node node_modules/@playwright/test/cli.js test tests/mergequeue-pos.spec.mjs
// 改动前对照（验红）：NEBFLOW_WEB_ROOT=<改动前 web 树绝对路径> 同一命令 ⇒ 本 spec 必红。

import { test, expect } from '@playwright/test';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

// 改动前/后对照开关：仅测试夹具用（缺省 = 本仓 src/main/resources/web）。
const WEB = process.env.NEBFLOW_WEB_ROOT
  || join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const ROOT_SID = 'fm-queue-root';
const T0 = Date.now();

// ── FIXTURE 载荷（人工构造；🔴 非生产实测）───────────────────────────────
// ① 单持有者排队态：徽标「排队中 · 第 2 位 / 共 4」+ 脚注「被 attach-merge 挡着」
//    位次 = `mergeQueuePos`（引擎派生；n-run 占位次 1 ⇒ 排队者从 2 起）
const N_Q1 = {
  id: 'n-q1', name: 'landing-merge-q1', agent: 'general', merge: true, status: 'pending',
  in: [], out: 'Nebula', hasWorktree: false, worktree: null,
  description: '落地方案 q1', result: null, blockCount: 0,
  createdAt: T0 - 3000, completedAt: null, ttlLeftSec: null,
  mergeQueue: { ahead: 1, holders: [{ id: 'n-hold-a', name: 'attach-merge', status: 'running' }] },
  mergeQueuePos: { position: 2, total: 4, queue: 'merge-window', arrived: true, readyAt: T0 - 9000, createdAt: T0 - 3000,
    rank: { primary: 'readyAt', tiebreaks: ['createdAt', 'id'] } },
};
// ② 多持有者排队态：徽标「排队中 · 第 4 位 / 共 4」+ 脚注三名全列（含 running 与开态先到者）
const N_Q3 = {
  id: 'n-q3', name: 'landing-merge-q3', agent: 'general', merge: true, status: 'pending',
  in: [], out: 'Nebula', hasWorktree: false, worktree: null,
  description: '落地方案 q3', result: null, blockCount: 0,
  createdAt: T0 - 2000, completedAt: null, ttlLeftSec: null,
  mergeQueue: {
    ahead: 3,
    holders: [
      { id: 'n-hold-c', name: 'homebrew-generalize-merge', status: 'pending' },
      { id: 'n-hold-a', name: 'attach-merge', status: 'running' },
      { id: 'n-hold-b', name: 'docs-merge', status: 'wiring' },
    ],
  },
  mergeQueuePos: { position: 4, total: 4, queue: 'merge-window', arrived: true, readyAt: T0 - 2000, createdAt: T0 - 2000,
    rank: { primary: 'readyAt', tiebreaks: ['createdAt', 'id'] } },
};
// ③ 降级（同键多项目 O-1）：引擎标 sameKeyProjects ⇒ 裸「排队中」，无数字
const N_QD = {
  id: 'n-qd', name: 'landing-merge-qd', agent: 'general', merge: true, status: 'pending',
  in: [], out: 'Nebula', hasWorktree: false, worktree: null,
  description: '落地方案 qd', result: null, blockCount: 0,
  createdAt: T0 - 1000, completedAt: null, ttlLeftSec: null,
  mergeQueue: {
    ahead: 1,
    holders: [{ id: 'n-hold-a', name: 'attach-merge', status: 'running' }],
    sameKeyProjects: ['other-project'],
  },
  // 位次**在载荷里**（引擎算得出来），但引擎同时标了同键多项目 ⇒ 前端按降级红线
  // **不渲染数字**（唯一可信来源被结构性破坏：他项目节点在本项目载荷里不可见）。
  mergeQueuePos: { position: 2, total: 4, queue: 'merge-window', arrived: true, readyAt: T0 - 9000, createdAt: T0 - 1000,
    rank: { primary: 'readyAt', tiebreaks: ['createdAt', 'id'] }, sameKeyProjects: ['other-project'] },
};
// ④ 降级（位次不可计算）：`mergeQueuePos` 缺键 ⇒ 裸「排队中」，无数字（🔴 禁编造数字；
//    也禁拿 `ahead`（阻塞数）顶替位次——旧形态正是这样冒充位次的）
const N_QZ = {
  id: 'n-qz', name: 'landing-merge-qz', agent: 'general', merge: true, status: 'wiring',
  in: [], out: 'Nebula', hasWorktree: false, worktree: null,
  description: '落地方案 qz', result: null, blockCount: 0,
  createdAt: T0 - 900, completedAt: null, ttlLeftSec: null,
  mergeQueue: { holders: [{ id: 'n-hold-a', name: 'attach-merge', status: 'running' }] },
};
// ⑤ 运行态圆 = 无键（引擎条件键只在被挡时携带）：零徽标零脚注
const N_RUN = {
  id: 'n-run', name: 'running-merge', agent: 'general', merge: true, status: 'running',
  in: [], out: 'Nebula', hasWorktree: false, worktree: null,
  description: '已在临界区', result: null, blockCount: 0,
  createdAt: T0 - 6000, completedAt: null, ttlLeftSec: null,
};
// ⑥ 非 merge 普通节点 = 无键（字段集零漂移）：零徽标零脚注
const N_PLAIN = {
  id: 'n-plain', name: 'plain-node', agent: 'coder', status: 'pending',
  in: [], out: 'Nebula', hasWorktree: false, worktree: null,
  description: '普通节点', result: null, blockCount: 0,
  createdAt: T0 - 5000, completedAt: null, ttlLeftSec: null,
};

const FM = () => ({
  nodes: [N_Q1, N_Q3, N_QD, N_QZ, N_RUN, N_PLAIN].map((n) => structuredClone(n)),
  worktrees: [], meta: { project: 'alpha', updatedAt: Date.now() },
});

async function bootApp(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      const body = readFileSync(file);
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: ROOT_SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let msg;
      try { msg = JSON.parse(raw); } catch { return; }
      if (msg.type === 'getConfig') sendConfig();
      else if (msg.type === 'getSessions' || msg.type === 'listSessions') sendSessions();
      else if (msg.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: msg.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
    });
  });
  await page.route('**/api/projects', (r) => r.fulfill({
    json: { projects: [{ name: 'alpha', workspace: '/w/alpha', agentFile: 'alpha', description: '', createdAt: new Date(T0).toISOString() }] },
  }));
  await page.route('**/api/projects/alpha/flow-map', (r) => r.fulfill({ json: FM() }));
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

async function openFlowMapInPlace(page) {
  await page.click('#projects-btn');
  await page.waitForSelector('.project-card[data-project="alpha"]', { timeout: 8000 });
  await page.click('.project-card[data-project="alpha"] [data-open-flowmap="alpha"]');
  await page.waitForSelector('.flowmap-view-body .fm-node', { timeout: 8000 });
}

/** 读数采集：徽标文案 / 脚注文案 / 几何（卡不溢出、徽标不溢出、数字可读）。 */
const readQueue = (page) => page.evaluate(() => {
  const out = {};
  document.querySelectorAll('.fm-node').forEach((card) => {
    const id = card.dataset.nodeId;
    const head = card.querySelector('.fm-node-head');
    const badge = card.querySelector('.fm-flag-queue');
    const note = card.querySelector('.fm-wait-note');
    const noteText = card.querySelector('.fm-wait-note-text');
    const r = badge ? badge.getBoundingClientRect() : null;
    const c = card.getBoundingClientRect();
    out[id] = {
      badge: badge ? badge.textContent : null,
      badgeTitle: badge ? badge.getAttribute('title') : null,
      badgeCls: badge ? badge.className : null,
      headCls: head ? head.className : null,
      noteCls: note ? note.className : null,
      noteText: noteText ? noteText.textContent : null,
      cardOverflow: card.scrollWidth - card.clientWidth,
      // 徽标是否被裁（scrollWidth>clientWidth ⇒ 文案（含数字）被 ellipsis 截断）
      badgeTruncated: badge ? badge.scrollWidth > badge.clientWidth + 1 : null,
      // 徽标是否出卡界（右缘 > 卡右缘 - 1px）
      badgeInsideCard: r ? (r.right <= c.right + 1 && r.left >= c.left - 1) : null,
    };
  });
  return out;
});

const THEMES = ['light', 'dark'];
const VIEWPORTS = [
  { label: 'narrow', width: 900, height: 700 },
  { label: 'mid', width: 1280, height: 800 },
  { label: 'wide', width: 1680, height: 1000 },
];

for (const theme of THEMES) {
  for (const vp of VIEWPORTS) {
    test.describe(`排队位次 — ${theme} / ${vp.label}(${vp.width}x${vp.height})`, () => {
      test.use({ colorScheme: theme, viewport: { width: vp.width, height: vp.height } });

      test(`徽标数字 + 持有者双显 / 降级不渲染数字 / 零漂移 / 卡内不溢出（fixture 载荷）`, async ({ page }) => {
        const pageErrors = [];
        page.on('pageerror', (e) => pageErrors.push(e.message));
        await bootApp(page);
        await openFlowMapInPlace(page);
        await expect(page.locator('.flowmap-view-body .fm-node')).toHaveCount(6);

        const r = await readQueue(page);
        // 读数落盘（取证开关；缺省关闭 = 零副作用）：每条 = 主题/视口/各卡几何与文案。
        if (process.env.NEBFLOW_MEASURE_OUT) {
          const head = await page.evaluate(() => {
            const el = document.querySelector('.fm-node[data-node-id="n-q1"] .fm-node-head');
            const card = document.querySelector('.fm-node[data-node-id="n-q1"]');
            return {
              headScrollW: el.scrollWidth, headClientW: el.clientWidth,
              cardW: card.clientWidth, cardScrollW: card.scrollWidth, cardClientW: card.clientWidth,
            };
          });
          mkdirSync(process.env.NEBFLOW_MEASURE_OUT, { recursive: true });
          writeFileSync(join(process.env.NEBFLOW_MEASURE_OUT, `measure-${theme}-${vp.label}.json`),
            JSON.stringify({ theme, viewport: vp, head, cards: r }, null, 1));
        }

        // ① 徽标（head 行）形态「排队中 · 第 N 位 / 共 M」——N/M 取载荷 mergeQueuePos 逐字渲染
        expect(r['n-q1'].badge).toBe('排队中 · 第 2 位 / 共 4');
        expect(r['n-q1'].headCls).toContain('fm-head-queued');
        // ② 多持有者：位次 = 4（三名持有者全在前），脚注三名全列（载荷序 join ' · '）
        expect(r['n-q3'].badge).toBe('排队中 · 第 4 位 / 共 4');
        // ②b 位次互异（queuepos 批核心判据）：两个排队节点的数字**不同**，且与载荷一致
        expect(r['n-q1'].badge).not.toBe(r['n-q3'].badge);
        expect(Number(/第\s*(\d+)\s*位/.exec(r['n-q1'].badge)[1])).toBe(2);
        expect(Number(/第\s*(\d+)\s*位/.exec(r['n-q3'].badge)[1])).toBe(4);
        expect(r['n-q3'].noteText)
          .toBe('被 homebrew-generalize-merge · attach-merge · docs-merge 挡着');
        expect(r['n-q3'].noteCls).toContain('fm-wait-note-queue');
        // ③ 脚注形态「被 XX 挡着」（单持有者）
        expect(r['n-q1'].noteText).toBe('被 attach-merge 挡着');

        // ④ 降级红线：同键多项目 ⇒ 裸「排队中」无数字（且持有者清单照列）
        expect(r['n-qd'].badge).toBe('排队中');
        expect(r['n-qd'].badge).not.toMatch(/[0-9]/);
        expect(r['n-qd'].noteText).toContain('被 attach-merge 挡着');
        expect(r['n-qd'].badgeTitle).toContain('位次不可信');
        // ⑤ 降级红线：ahead 不可计算（缺键）⇒ 裸「排队中」无数字（🔴 禁编造数字）
        expect(r['n-qz'].badge).toBe('排队中');
        expect(r['n-qz'].badge).not.toMatch(/[0-9]/);

        // ⑥ 零漂移：运行态 merge（无键）与非 merge 节点（无键）零徽标零排队脚注
        for (const id of ['n-run', 'n-plain']) {
          expect(r[id].badge, id).toBeNull();
          expect(r[id].noteCls, id).toBeNull();
          expect(r[id].headCls, id).toBe('fm-node-head');
        }

        // ⑦ 几何：卡不溢出 + 徽标在卡内 + **数字未被截断**（作者双裁的可读性前提）
        for (const [id, v] of Object.entries(r)) {
          expect(v.cardOverflow, `${id} card overflow`).toBeLessThanOrEqual(1);
          if (v.badge !== null) {
            expect(v.badgeInsideCard, `${id} badge inside card`).toBe(true);
            expect(v.badgeTruncated, `${id} badge truncated`).toBe(false);
          }
        }

        // ⑧ 零 console 噪音（载荷形状漂移静默降级纪律）
        expect(pageErrors).toEqual([]);

        // ⑨ 明暗双主题下徽标配色实际生效（背景非透明 = CSS 命中）
        const bg = await page.evaluate(() => {
          const el = document.querySelector('.fm-node[data-node-id="n-q1"] .fm-flag-queue');
          const cs = getComputedStyle(el);
          return { bg: cs.backgroundColor, color: cs.color };
        });
        expect(bg.bg).not.toBe('rgba(0, 0, 0, 0)');
        expect(bg.bg).toContain('56, 189, 248');
      });
    });
  }
}

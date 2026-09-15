// queuepos-detail.spec.mjs — 合并窗「排队位次」**真渲染**验收（queuepos 批 2026-09-15）。
//
// 作者现场报（逐字）：「这个合并节点的排队显示也有问题，所以节点显示的前面还有几个都一样的。
// 根本不知道节点排队的顺序。点开的详情面板里也没有。」
// 预期语义：排队未执行的节点面板须显示「排队中 · 第 N 位 / 共 M」，**各位次互异、与真实执行
// 顺序一致**；**详情面板同样可见**（🔴 禁看起来像卡死）。
//
// 断言面（亮/暗两主题各跑一次；面板 + 详情面板各取读数；全部 DOM/文本断言 + 数值列表）：
//   P1 面板徽标：三个排队节点各显自己的位次（互异）——改前它们是**同一个数**；
//   P2 序一致性：显示位次的升序 == 载荷真源 `(readyAt, createdAt, id)` 升序（机械复算）；
//   P3 详情面板：「排队位次」行 = 「排队中 · 第 N 位 / 共 M（队列名）」；
//   P4 零漂移：临界区内（running）节点无徽标；被挡脚注「被 XX 挡着」逐字保留；
//   P5 几何：徽标不截断、不出卡界；
//   P6 零 console/页面错误。
//
// 🔴 载荷来源 = **引擎真实派生产物**（禁手抄、禁前端 mock 冒充实测）：
//   ① 先跑引擎面 spec 产出载荷 dump：
//      sbt "testOnly nebflow.core.project.MergeQueuePositionSpec"     （测试 ① 落盘）
//      产出 <repo>/target/queuepos-payload.json（可用 NEBFLOW_QUEUEPOS_DUMP=<abs> 改址）
//   ② 本 spec 读该文件（QUEUEPOS_PAYLOAD=<abs> 可覆盖）；文件缺失 ⇒ **硬失败**并打印命令
//      （缺省回落成手写夹具 = 正是本批要杜绝的「mock 冒充」）。
//
// 运行（仓库根，需先跑 ①）：
//   node node_modules/@playwright/test/cli.js test tests/queuepos-detail.spec.mjs --workers=1
// 改动前对照（验红，双向钉的「改前」组）：把 WEB 指向改动前的 web 树同一命令 ⇒ 本 spec 必红
//   NEBFLOW_WEB_ROOT=<改动前 web 树绝对路径> node node_modules/@playwright/test/cli.js test tests/queuepos-detail.spec.mjs --workers=1
// 读数落盘（取证开关；缺省关闭）：QUEUEPOS_READINGS_OUT=<dir> ⇒ readings-<theme>.json

import { test, expect } from '@playwright/test';
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = join(HERE, '..');
// 改动前/后对照开关：仅测试夹具用（缺省 = 本仓 src/main/resources/web）。
const WEB = process.env.NEBFLOW_WEB_ROOT || join(REPO, 'src', 'main', 'resources', 'web');
const PAYLOAD_PATH = process.env.QUEUEPOS_PAYLOAD || join(REPO, 'target', 'queuepos-payload.json');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

// ── 引擎真实载荷（🔴 禁手抄）─────────────────────────────────────────────
if (!existsSync(PAYLOAD_PATH)) {
  throw new Error(
    `queuepos: engine payload dump not found at ${PAYLOAD_PATH}\n` +
    '  Produce it first (the render face MUST be driven by the engine\'s real derivation):\n' +
    '    sbt "testOnly nebflow.core.project.MergeQueuePositionSpec"\n' +
    '  or point QUEUEPOS_PAYLOAD at an existing dump.');
}
const ENGINE_PAYLOAD = JSON.parse(readFileSync(PAYLOAD_PATH, 'utf8'));
/** 本套夹具里的排队节点（引擎 dump 测试 ①：1 个 running + 3 个排队，verdict 未过）。 */
const WAITERS = ['n-q1', 'n-q2', 'n-q3'];
const HOLDER = 'n-hold';
const ROOT_SID = 'fm-queuepos-root';

/** 载荷真源序（`(readyAt, createdAt, id)` 升序）——P2 的机械复算面。 */
function rankOrder(nodes) {
  const merge = nodes.filter((n) => n.merge === true && n.mergeQueuePos);
  return merge
    .map((n) => ({ id: n.id, key: [n.mergeQueuePos.readyAt, n.mergeQueuePos.createdAt, n.id] }))
    .sort((a, b) => (a.key[0] - b.key[0]) || (a.key[1] - b.key[1]) || (a.key[2] < b.key[2] ? -1 : 1))
    .map((x) => x.id);
}

const FM = () => ({
  nodes: ENGINE_PAYLOAD.nodes.map((n) => structuredClone(n)),
  chains: ENGINE_PAYLOAD.chains || [],
  worktrees: [],
  meta: { project: 'alpha', updatedAt: Date.now(), archived: 0 },
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
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
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
    json: { projects: [{ name: 'alpha', workspace: '/w/alpha', agentFile: 'alpha', description: '', createdAt: new Date().toISOString() }] },
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
  await page.waitForTimeout(250);
}

/** 面板读数：每个节点卡的排队徽标文案 / title / 脚注 / 几何（含截断与出界）。 */
const readCards = (page) => page.evaluate(() => {
  const out = {};
  document.querySelectorAll('.fm-node').forEach((card) => {
    const badge = card.querySelector('.fm-flag-queue');
    const head = card.querySelector('.fm-node-head');
    const note = card.querySelector('.fm-wait-note-text');
    const r = badge ? badge.getBoundingClientRect() : null;
    const c = card.getBoundingClientRect();
    out[card.dataset.nodeId] = {
      badge: badge ? badge.textContent : null,
      badgeTitle: badge ? badge.getAttribute('title') : null,
      headCls: head ? head.className : null,
      noteText: note ? note.textContent : null,
      badgeTruncated: badge ? badge.scrollWidth > badge.clientWidth + 1 : null,
      badgeInsideCard: r ? (r.right <= c.right + 1 && r.left >= c.left - 1) : null,
      cardOverflow: card.scrollWidth - card.clientWidth,
    };
  });
  return out;
});

/** 详情面板读数：面板开态 + 全部 KV 行（label → value）。 */
const readDetail = (page) => page.evaluate(() => {
  const panel = document.querySelector('[data-testid="detail-panel"]');
  const rows = {};
  document.querySelectorAll('.fm-detail-body .fm-detail-kv').forEach((kv) => {
    const labels = kv.querySelectorAll('.fm-detail-kv-label');
    const vals = kv.querySelectorAll('.fm-detail-kv-val');
    labels.forEach((l, i) => { rows[l.textContent] = vals[i] ? vals[i].textContent : null; });
  });
  return {
    open: !!panel && panel.classList.contains('open'),
    title: document.querySelector('.fm-detail-title') ? document.querySelector('.fm-detail-title').textContent : null,
    rows,
  };
});

/** 主图点节点（真实点击优先，覆盖拦截时 DOM 派发兜底——同既有 archive 面板 spec 口径）。 */
async function nodeClick(page, nodeId) {
  const body = page.locator('.flowmap-view-body');
  const node = body.locator(`.fm-node[data-node-id="${nodeId}"]`);
  await node.click({ timeout: 2500 }).catch(() => node.dispatchEvent('click', { bubbles: true }));
  await page.waitForSelector('[data-testid="detail-panel"].open', { timeout: 5000 });
  await page.waitForTimeout(120);
}

/** 徽标里的位次数值（「排队中 · 第 N 位 / 共 M」→ N）。 */
const posNum = (badge) => {
  const m = /第\s*(\d+)\s*位/.exec(badge || '');
  return m ? Number(m[1]) : null;
};

const THEMES = ['light', 'dark'];

for (const theme of THEMES) {
  test.describe(`排队位次真渲染 — ${theme}`, () => {
    test.use({ colorScheme: theme, viewport: { width: 1440, height: 900 } });

    test(`面板位次互异 + 与真源序一致 + 详情面板显示位次（引擎真实载荷）`, async ({ page }) => {
      const pageErrors = [];
      page.on('pageerror', (e) => pageErrors.push(e.message));
      await bootApp(page);
      await openFlowMapInPlace(page);
      await expect(page.locator('.flowmap-view-body .fm-node')).toHaveCount(ENGINE_PAYLOAD.nodes.length);

      // 主题确实生效（亮/暗两主题读数不是同一份）
      const themeProbe = await page.evaluate(() => ({
        prefersDark: window.matchMedia('(prefers-color-scheme: dark)').matches,
        bodyBg: getComputedStyle(document.body).backgroundColor,
      }));
      expect(themeProbe.prefersDark).toBe(theme === 'dark');

      const cards = await readCards(page);

      // ── 读数先行（面板 + 详情面板各一遍；**先收后断**——改动前的对照运行也能留下完整
      //    读数：徽标全同 + 详情面板无该行，正是「改前红」的两条原始证据）──
      const detail = {};
      for (const id of WAITERS) {
        await nodeClick(page, id);
        detail[id] = await readDetail(page);
      }
      const displayed = WAITERS.map((id) => ({ id, badge: cards[id].badge, n: posNum(cards[id].badge) }));
      if (process.env.QUEUEPOS_READINGS_OUT) {
        mkdirSync(process.env.QUEUEPOS_READINGS_OUT, { recursive: true });
        writeFileSync(join(process.env.QUEUEPOS_READINGS_OUT, `readings-${theme}.json`),
          JSON.stringify({
            theme, web: WEB, payload: PAYLOAD_PATH, themeProbe,
            badgeNumbers: displayed,
            shownOrder: displayed.slice().sort((a, b) => (a.n ?? 1e9) - (b.n ?? 1e9)).map((d) => d.id),
            srcOrder: rankOrder(ENGINE_PAYLOAD.nodes).filter((id) => WAITERS.includes(id)),
            cards,
            detailQueueRow: Object.fromEntries(Object.entries(detail).map(([k, v]) => [k, v.rows['排队位次'] ?? null])),
            detailPanelOpen: Object.fromEntries(Object.entries(detail).map(([k, v]) => [k, v.open])),
            pageErrors,
          }, null, 1));
      }

      // ── P1 面板徽标：三个排队节点各显**自己的**位次（改前 = 同一个数）──
      const nums = displayed.map((d) => d.n);
      expect(nums.every((n) => Number.isInteger(n)), `every waiter must show a position: ${JSON.stringify(displayed)}`).toBe(true);
      expect(new Set(nums).size, `positions MUST be pairwise distinct: ${JSON.stringify(displayed)}`).toBe(nums.length);
      // 逐节点具体读数（引擎 dump 测试 ①：running 者占位次 1 ⇒ 排队者 2/3/4 of 4）
      for (const id of WAITERS) {
        const want = ENGINE_PAYLOAD.nodes.find((n) => n.id === id).mergeQueuePos;
        expect(cards[id].badge, id).toBe(`排队中 · 第 ${want.position} 位 / 共 ${want.total}`);
      }

      // ── P2 序一致性：显示位次升序 == 真源 rank 序（机械复算，禁「看起来对」）──
      const srcOrder = rankOrder(ENGINE_PAYLOAD.nodes).filter((id) => WAITERS.includes(id));
      const shownOrder = displayed.slice().sort((a, b) => a.n - b.n).map((d) => d.id);
      expect(shownOrder).toEqual(srcOrder);

      // ── P4 零漂移：临界区内节点无徽标；被挡脚注逐字保留 ──
      expect(cards[HOLDER].badge).toBeNull();
      expect(cards[HOLDER].headCls).toBe('fm-node-head');
      for (const id of WAITERS) {
        // 被挡脚注（既有两批语义逐字保留）：合并窗段在前、上游等待段以后续接（两个独立
        // 成因禁互相吞并——本夹具的排队者同时挂着未过 verdict 的 verify 上游，故两段并存）。
        expect(cards[id].noteText, id).toBe('被 hold-merge 挡着 · 等待: verify-r1(n-ver)');
        expect(cards[id].headCls).toContain('fm-head-queued');
        expect(cards[id].badgeTitle, id).toContain('合并窗排队中');
      }

      // ── P3 详情面板：逐节点位次行（终局未执行节点的详情面板必须显示位次）──
      for (const id of WAITERS) {
        expect(detail[id].open, id).toBe(true);
        expect(detail[id].rows['排队位次'], `${id} detail panel must show the queue row: ${JSON.stringify(detail[id].rows)}`)
          .toBe(`排队中 · 第 ${ENGINE_PAYLOAD.nodes.find((n) => n.id === id).mergeQueuePos.position} 位 / 共 4（合并窗）`);
      }

      // ── P5 几何：徽标不截断、不出卡界、卡不溢出 ──
      for (const [id, v] of Object.entries(cards)) {
        expect(v.cardOverflow, `${id} card overflow`).toBeLessThanOrEqual(1);
        if (v.badge !== null) {
          expect(v.badgeInsideCard, `${id} badge inside card`).toBe(true);
          expect(v.badgeTruncated, `${id} badge truncated`).toBe(false);
        }
      }

      // ── P6 零页面错误 ──
      expect(pageErrors).toEqual([]);
    });
  });
}

// flowmap-archive-result-full.spec.mjs — 归档详情窗节点结果全文显示验收
//（20260904 全文完整显示 + 2026-09-05 载荷收敛改造）。
//
// 作者反馈「节点结果要能完整显示」。截断点定位（见 20260904_archive-panel-result-full-report.md）：
// 截断不在前端渲染链——NodePayload.buildNodeJson（ProjectTypes.scala）把 result 统一
// 截为 ≤500 字符摘要（快照/WS 事件/NodeList 工具共用单一序列化点）。修复 = 只读端点
// GET /api/projects/<name>/flow-map/nodes/<nodeId>/result（活动区优先归档区兜底，全文）
// + 前端详情窗按需取全文换装（渲染代守卫竞态）+ 结果区独立滚动
//（max-height:60vh + overflow-y:auto，overscroll-behavior:contain）。
//
// 2026-09-05 载荷收敛改写：默认载荷（快照/WS 事件）不再携带 result 本体——只有
// hasResult 布尔标记 + description（创建必写）；结果全文仅两条按需通道（REST result
// 端点 / NodeList detail 参数）。因此：
//   · 载荷模拟改为「剥 result、置 hasResult」新形态（生产 NodePayload 同构）；
//   · 详情打开 = hasResult 即无条件按需拉取（不再有「摘要 ≥500 才可能截断」判据，
//     新载荷根本没有摘要）；
//   · R2 由「短结果零请求」改写为「无结果成员零请求」——hasResult 缺/false 的成员
//     点开零请求、结果区落 noResult 占位（契约不扰的最小影响面证明）。
//
// 用例：
//   R1 长结果全文：5000+ 字符样例（载荷态=hasResult:true 无正文）→ 详情打开后按需
//     换装全文；断言 DOM 文本=原文（逐字符相等、无省略号截断、markdown 管线 <p> 输出）；
//     结果区可滚动（scrollHeight > clientHeight）；滚动到底后末尾文本 Range boundingBox
//     落在详情窗可视区内（区域滚动不动摇详情窗本体定位）。
//   R2 无结果成员零按需请求：c3b 特殊置无结果（hasResult:false）→ 详情打开零请求、
//     结果区 md-empty「暂无结果」占位——按需通道只服务 hasResult 节点。
//   R3 迟到响应竞态守卫：延迟端点响应 + 快速换节点（TARGET→c1c）→ 迟到全文被渲染代
//     守卫丢弃，不得串扰后开节点的内容。
// 夹具/route 拦截与 flowmap-archive-panel.spec.mjs 同构（零端口，localhost:1）；
// fixture.mjs 的 result 字段仅作端点 mock 数据源（全文仓），载荷层已剥。
//
// Run: node node_modules/@playwright/test/cli.js test tests/flowmap-archive-result-full.spec.mjs --workers=1

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  PROJECT, ROOT_SID, allNodes, fmPayload,
} from './fixtures/flowmap-archive-panel/fixture.mjs';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

test.setTimeout(90_000);

// ── 长结果样例（确定性生成；纯文本单段无换行无 markdown 元字符 → 渲染后
//    .fm-detail-result textContent 与原文逐字符相等，长度断言免于 markdown 变形）──
const FULL = Array.from({ length: 200 }, (_, i) =>
  `第${i + 1}句：用于验证归档详情窗节点结果全文完整渲染、无五百字符摘要截断，并验证结果区域独立滚动与末尾文本可达性的验收样例内容。`,
).join('') + '终行标记ZQX20260904END：本句为全文唯一终止行，专用于滚动到底后末尾文本可见性断言。';
const HEAD = FULL.slice(0, 12);
const TAIL = '终行标记ZQX20260904END'; // 全文唯一（上文编号句无此串）→ 定位必命中末行
const TARGET = 'c1b'; // C1 链成员（面板展开 → 成员行点击样例）
const NO_RESULT_NODE = 'c3b'; // R2：特殊置无结果的归档成员（C3 链，completed）

/** 生产 NodePayload 载荷模拟（2026-09-05 收敛形态）：剥 result 本体 → hasResult 标记。
 *  TARGET 注入长全文仓（仅端点侧可见）；c3b 特殊置无结果（fixture 原有短结果删除）。 */
function payloadNodes() {
  return allNodes().map((n) => {
    const c = structuredClone(n);
    if (c.id === TARGET) c.result = FULL;
    if (c.id === NO_RESULT_NODE) delete c.result;
    const has = !!c.result;
    const { result, ...rest } = c;
    return { ...rest, hasResult: has };
  });
}

/** 载荷层无结果节点集（端点 mock 对它们返回 result:null——生产端 hasResult=false
 *  的节点其 result 端点本就为空）。 */
const PAYLOAD_NO_RESULT = new Set(payloadNodes().filter((n) => !n.hasResult).map((n) => n.id));

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
      let msg; try { msg = JSON.parse(raw); } catch { return; }
      if (msg.type === 'getConfig') sendConfig();
      else if (msg.type === 'getSessions' || msg.type === 'listSessions') sendSessions();
      else if (msg.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: msg.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

/** 结果全文端点 mock（结果全文仓 = fixture 原文 + TARGET 长样例；payload 无结果节点
 *  返回 null。delayMs=0 即时；counter 按 nodeId 记请求数）。 */
async function mockResultEndpoint(page, { delayMs = 0, counter } = {}) {
  await page.route('**/api/projects/alpha/flow-map/nodes/*/result', async (route) => {
    const url = new URL(route.request().url());
    const segs = url.pathname.split('/').filter(Boolean);
    const id = segs[segs.length - 2]; // …/flow-map/nodes/<id>/result
    if (counter) counter.set(id, (counter.get(id) || 0) + 1);
    if (delayMs) await new Promise((r) => setTimeout(r, delayMs));
    const n = allNodes().find((x) => x.id === id);
    const full = id === TARGET ? FULL : (PAYLOAD_NO_RESULT.has(id) ? null : (n?.result ?? null));
    await route.fulfill({
      json: { id, name: n?.name || id, status: n?.status || 'completed', result: full },
    });
  });
}

async function mockApi(page) {
  await page.route('**/api/projects', (r) => r.fulfill({
    json: { projects: [{ name: PROJECT, workspace: '/w/alpha', agentFile: PROJECT, description: '', createdAt: new Date().toISOString() }] },
  }));
  await page.route('**/api/projects/alpha/flow-map', (r) => r.fulfill({
    json: fmPayload(payloadNodes()),
  }));
}

/** 打开 projects 标签页 → alpha Flow Map 就地视图 → 归档面板 → 展开 C1 → 点开 TARGET 成员详情。 */
async function openTargetDetail(page) {
  await page.click('#projects-btn');
  await page.waitForSelector('.project-card[data-project="alpha"]', { timeout: 8000 });
  await page.click('.project-card[data-project="alpha"] [data-open-flowmap="alpha"]');
  await page.waitForSelector('.flowmap-view-body .fm-node', { timeout: 8000 });
  await page.waitForTimeout(250);
  await page.click('[data-testid="archive-toggle"]');
  const entry = page.locator('[data-testid="archive-entry"][data-chain-id="chain-c1a"]');
  await expect(entry).toHaveCount(1);
  await entry.click();
  const member = page.locator(`.fm-member[data-node="${TARGET}"]`);
  await expect(member).toHaveCount(1);
  await member.click();
  await expect(page.locator('[data-testid="detail-panel"]')).toBeVisible();
}

// ═══════════ R1 · 长结果全文换装 + 独立滚动 + 末尾可见 ═══════════

test('R1 长结果：hasResult 按需换装全文（长度=原文，无截断）+ 结果区独立滚动 + 滚到底末尾可见', async ({ page }) => {
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await bootApp(page);
  await mockApi(page);
  await mockResultEndpoint(page);
  await openTargetDetail(page);

  const box = page.locator('.fm-detail-result');
  // 全文换装到达：textContent 长度 = 原文长度（载荷无正文 → 按需全文 10000+）。
  // marked 在 </p> 后恒补一个尾部换行（渲染产物非文本流）→ 归一化后比对。
  await expect.poll(async () => (((await box.textContent()) || '').replace(/\n+$/, '')).length, { timeout: 8000 })
    .toBe(FULL.length);
  const txt = ((await box.textContent()) || '').replace(/\n+$/, '');
  // 逐字符相等（纯文本单段：markdown 仅包 <p>，不改变文本流）
  expect(txt).toBe(FULL);
  // 头尾标记在列（全文完整）+ 无省略号截断
  expect(txt.startsWith(HEAD)).toBe(true);
  expect(txt.includes(TAIL)).toBe(true); // 唯一终止行在列（txt===FULL 已含，显式哨兵）
  expect(txt.endsWith('…')).toBe(false);
  // markdown 管线执行（fm-md + <p> 输出，非 <pre> 兜底）
  expect(await box.evaluate((el) => el.classList.contains('fm-md'))).toBe(true);
  expect(await box.locator('p').count()).toBeGreaterThanOrEqual(1);

  // 结果区独立滚动：内容溢出（scrollHeight > clientHeight），滚动被 contain 在结果区
  const scroll = await box.evaluate((el) => ({
    sh: el.scrollHeight, ch: el.clientHeight,
    overflowY: getComputedStyle(el).overflowY,
  }));
  expect(scroll.sh).toBeGreaterThan(scroll.ch);
  expect(scroll.overflowY).toBe('auto');

  // 滚动到底部 → 末尾文本（唯一终止行，精确子串 Range boundingBox）落在详情窗可视
  // 区内（详情窗本体零位移：前后两次 .fm-detail boundingBox 全等）
  const detailBefore = await page.locator('[data-testid="detail-panel"]').boundingBox();
  const tailView = await box.evaluate((el, tail) => {
    el.scrollTop = el.scrollHeight;
    const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
    let tn; let hit = null;
    while ((tn = walker.nextNode())) {
      if (tn.textContent.includes(tail)) { hit = tn; break; }
    }
    if (!hit) return { ok: false, reason: 'tail text node not found' };
    const idx = hit.textContent.indexOf(tail);
    if (idx < 0) return { ok: false, reason: 'tail not in node' };
    const range = document.createRange();
    range.setStart(hit, idx);
    range.setEnd(hit, Math.min(hit.textContent.length, idx + tail.length));
    const r = range.getBoundingClientRect();
    const d = el.closest('.fm-detail').getBoundingClientRect();
    return {
      ok: r.height > 0 && r.top >= d.top - 1 && r.bottom <= d.bottom + 1
        && r.left >= d.left - 1 && r.right <= d.right + 1,
      scrolled: el.scrollTop > 0,
      top: r.top, bottom: r.bottom, dtop: d.top, dbottom: d.bottom,
    };
  }, TAIL);
  expect(tailView.ok).toBe(true);
  expect(tailView.scrolled).toBe(true);
  const detailAfter = await page.locator('[data-testid="detail-panel"]').boundingBox();
  expect(detailAfter).toEqual(detailBefore); // 区域内滚动不动摇详情窗整体定位
  expect(errors).toEqual([]);
});

// ═══════════ R2 · 无结果成员零按需请求（最小影响面契约不扰）═══════════

test('R2 无结果成员：结果端点零请求、结果区 noResult 占位', async ({ page }) => {
  const counter = new Map();
  await bootApp(page);
  await mockApi(page);
  await mockResultEndpoint(page, { counter });
  await page.click('#projects-btn');
  await page.waitForSelector('.project-card[data-project="alpha"]', { timeout: 8000 });
  await page.click('.project-card[data-project="alpha"] [data-open-flowmap="alpha"]');
  await page.waitForSelector('.flowmap-view-body .fm-node', { timeout: 8000 });
  await page.waitForTimeout(250);
  await page.click('[data-testid="archive-toggle"]');
  const entry = page.locator('[data-testid="archive-entry"][data-chain-id="chain-c3a"]');
  await expect(entry).toHaveCount(1);
  await entry.click();
  // c3b hasResult:false → 无按需拉取判据，零请求
  await page.locator(`.fm-member[data-node="${NO_RESULT_NODE}"]`).click();
  await expect(page.locator('[data-testid="detail-panel"]')).toBeVisible();
  await page.waitForTimeout(600);
  expect(counter.get(NO_RESULT_NODE) || 0).toBe(0);
  // 结果区落 noResult 占位（md-empty「暂无结果」，非 '…' 拉取中占位、非全文）
  const box = page.locator('.fm-detail-result');
  expect(await box.locator('.md-empty').count()).toBe(1);
  const txt = ((await box.textContent()) || '').trim();
  expect(txt).toBe('暂无结果');
});

// ═══════════ R3 · 迟到响应竞态守卫（渲染代丢弃，不串扰）═══════════

test('R3 竞态守卫：延迟全文响应 + 快速换节点 → 迟到全文被丢弃', async ({ page }) => {
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await bootApp(page);
  await mockApi(page);
  await mockResultEndpoint(page, { delayMs: 1200 });
  await page.click('#projects-btn');
  await page.waitForSelector('.project-card[data-project="alpha"]', { timeout: 8000 });
  await page.click('.project-card[data-project="alpha"] [data-open-flowmap="alpha"]');
  await page.waitForSelector('.flowmap-view-body .fm-node', { timeout: 8000 });
  await page.waitForTimeout(250);
  await page.click('[data-testid="archive-toggle"]');
  const entry = page.locator('[data-testid="archive-entry"][data-chain-id="chain-c1a"]');
  await expect(entry).toHaveCount(1);
  await entry.click();
  // 开 TARGET（长结果，端点延迟 1.2s）→ 关详情（overlay 覆盖面板，真实点击通路）→ 换 c1c
  await page.locator(`.fm-member[data-node="${TARGET}"]`).click();
  await expect(page.locator('[data-testid="detail-panel"]')).toBeVisible();
  await page.locator('.fm-detail-close').click();
  await expect(page.locator('[data-testid="detail-panel"]')).toBeHidden();
  await page.locator('.fm-member[data-node="c1c"]').click();
  await page.waitForTimeout(2000); // 双方响应均已到达（1.2s）
  // c1c 详情保持自身内容（hasResult → 自身按需拉取照常换装）：TARGET 迟到全文未串扰
  // （若渲染代守卫失效，此处已被 FULL 覆盖）
  const txt = ((await page.locator('.fm-detail-result').textContent()) || '').trim();
  expect(txt).toBe('回归 PASS：控制台无 404。');
  expect(txt.includes(TAIL)).toBe(false);
  expect(errors).toEqual([]);
});

// flowmap-emoji-clean.spec.mjs — Flow Map 节点域 emoji 清零 + 脚注防溢出验收。
//
// 背景（作者 2026-09-04 07:52 反馈，既有裁定）：
//   ① 节点域禁止 emoji/符号字符——状态图标用内联 SVG 描边绘制（✓/✗/⚑/— → SVG；
//      等待脚注 ⏳ → SVG 沙漏；归档降级 ✦ → i18n 纯文字「已归档」）。
//   ② 所有文字保持在节点卡片边界内——超长「节点名+id」等待脚注 ellipsis 截断，
//      卡级 scrollWidth 口径（与 flowmap-cad-zoom 节点的卡级截断口径一致）。
//   白名单：✻ 状态标语条（件六域）——断言显式排除（本域 DOM 中本就不出现）。
//   TTL 倒计时徽章（⏱/fmtTtl/.fm-ttl）已随 v3 语义移除——断言其持续缺席作回归哨兵。
//
// Run: node node_modules/@playwright/test/cli.js test tests/flowmap-emoji-clean.spec.mjs

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

const ROOT_SID = 'fm-emoji-root';
const T0 = Date.now();
const LONG_NAME = '实施-侧边栏插件页迁移-超长节点名称用于卡片边界截断验证-FlowMapEmojiClean-E2E';
const GHOST_ID = 'ghost-upstream-id-8ac3ef';

// fixture 批次语义（flowMapArchive.refreshChains §3.5）：n-arch-old 独自早 600s →
// 独批全终态 → 整链归档（archivedIds，主图不可见）；其余同批含 pending → buffer 保留。
const N_OLD = {
  id: 'n-arch-old', name: '已归档上游节点', agent: 'researcher', status: 'completed',
  in: [], out: 'Nebula', hasWorktree: false, worktree: null,
  result: '早批完成', retries: 0, createdAt: T0 - 600_000, completedAt: T0 - 590_000, ttlLeftSec: null,
};
const N_A = {
  id: 'n-a', name: '已完成节点', agent: 'researcher', status: 'completed',
  in: [], out: 'n-w', hasWorktree: false, worktree: null,
  // 点 5 回归夹具：preset+plugins 齐备 → 副行 `preset · p1, p2` 合显；
  // description 进详情面板描述区块（点 3 渲染链验收）
  preset: 'research-deep', plugins: ['nebflow-web', 'nebflow-fs'],
  description: '调研并汇总竞品方案',
  result: '完成', retries: 0, createdAt: T0, completedAt: T0 + 100, ttlLeftSec: null,
};
const N_F = {
  id: 'n-f', name: '失败节点', agent: 'qa', status: 'failed',
  in: [], out: 'n-w', hasWorktree: false, worktree: null,
  result: null, retries: 1, createdAt: T0 + 1_000, completedAt: T0 + 1_100, ttlLeftSec: null,
};
const N_B = {
  id: 'n-b', name: '阻塞节点', agent: 'coder', status: 'blocked',
  in: [], out: 'n-w', hasWorktree: false, worktree: null,
  // 结构化阻塞反馈 → 详情面板 blocked 区块（验收 ⚑→SVG 旗替换路径）
  blockedFeedback: { category: 'missing-info', detail: '缺少接口定义', suggestion: '补充契约后重试' },
  blockCount: 2,
  result: null, retries: 0, createdAt: T0 + 2_000, completedAt: null, ttlLeftSec: null,
};
const N_X = {
  id: 'n-x', name: '已取消节点', agent: 'docs', status: 'cancelled',
  in: [], out: 'n-w', hasWorktree: false, worktree: null,
  result: null, retries: 0, createdAt: T0 + 3_000, completedAt: T0 + 3_100, ttlLeftSec: null,
};
const N_W = {
  id: 'n-w', name: LONG_NAME, agent: 'writer', status: 'pending',
  in: ['n-a'], deps: ['n-arch-old', GHOST_ID], out: 'Nebula', hasWorktree: false, worktree: null,
  result: null, retries: 0, createdAt: T0 + 4_000, completedAt: null, ttlLeftSec: null,
};
const FM = () => ({
  nodes: [structuredClone(N_OLD), structuredClone(N_A), structuredClone(N_F),
    structuredClone(N_B), structuredClone(N_X), structuredClone(N_W)],
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

/** 节点域 emoji 扫描（text 节点 + title 属性；✻=U+273B 白名单显式排除）。
 *  码点域：2300-23FF（⏰⏳⏱ 等 Misc Technical）、2600-27BF（Misc Symbol+Dingbats，
 *  含 ✓✗✦✻⚑✕）、2B00-2BFF、FE0F 变体选择符、2049/203C、1F000-1FAFF、1F1E6-1F1FF，
 *  另补 2190-21FF 箭头域（→↩ 等，2026-09-06 显示优化批禁渲染字符全覆盖）。
 *  roots 参数化（2026-09-06 哨兵扩展）：默认 .fm-node；扩域扫描传
 *  ['.flowmap-nav-bar', '.flowmap-view-body'] 覆盖顶栏摘要 + 动态打开的归档
 *  面板/详情窗（悬浮层挂在 view-body 内）。 */
const emojiScan = (page, roots = ['.fm-node']) => page.evaluate((sels) => {
  const EMOJI = /[\u2190-\u21FF\u2300-\u23FF\u2600-\u27BF\u2B00-\u2BFF\uFE0F\u2049\u203C]|[\u{1F000}-\u{1FAFF}\u{1F1E6}-\u{1F1FF}]/u;
  const WHITELIST = new Set(['\u273B']); // ✻ 状态标语条（件六域，作者接受沿用）
  const hits = [];
  document.querySelectorAll(sels.join(',')).forEach((root) => {
    const tag = String(root.className || root.tagName);
    const walk = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    while (walk.nextNode()) {
      const txt = walk.currentNode.textContent || '';
      for (const ch of txt) {
        if (EMOJI.test(ch) && !WHITELIST.has(ch)) {
          hits.push({ where: 'text', code: ch.codePointAt(0).toString(16), root: tag });
        }
      }
    }
    const titles = [root.getAttribute('title') || ''];
    root.querySelectorAll('[title]').forEach((el) => titles.push(el.getAttribute('title') || ''));
    for (const txt of titles) {
      for (const ch of txt) {
        if (EMOJI.test(ch) && !WHITELIST.has(ch)) {
          hits.push({ where: 'title', code: ch.codePointAt(0).toString(16), root: tag });
        }
      }
    }
  });
  return hits;
}, roots);

/** 卡级防溢出 + 脚注 ellipsis 口径：卡 scrollWidth<=clientWidth+1（内容不出卡界）；
 *  长脚注文字段 ellipsis 生效（scrollWidth>clientWidth = 截断已启用）。 */
const overflowScan = (page) => page.evaluate(() => {
  const cards = [];
  let note = null;
  document.querySelectorAll('.fm-node').forEach((card) => {
    const over = card.scrollWidth > card.clientWidth + 1;
    cards.push({ id: card.dataset.nodeId, sw: card.scrollWidth, cw: card.clientWidth, over });
    const txt = card.querySelector('.fm-wait-note-text');
    if (txt && card.dataset.nodeId === 'n-w') {
      const cs = getComputedStyle(txt);
      note = {
        ellipsis: cs.textOverflow === 'ellipsis',
        clip: cs.overflowX === 'hidden' || cs.overflow === 'hidden',
        nowrap: cs.whiteSpace === 'nowrap',
        engaged: txt.scrollWidth > txt.clientWidth, // 内容超宽 → ellipsis 实际生效
        sw: txt.scrollWidth, cw: txt.clientWidth,
      };
    }
  });
  return { cards, note };
});

test('节点域 emoji 清零：状态图标 SVG 化、等待脚注 SVG 沙漏、归档降级纯文字、卡级防溢出', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);
  await openFlowMapInPlace(page);

  const body = page.locator('.flowmap-view-body');
  // fixture 可见集：终态保留卡 ×4 + pending ×1；n-arch-old 随整链归档不可见
  await expect(body.locator('.fm-node')).toHaveCount(5);
  for (const id of ['n-a', 'n-f', 'n-b', 'n-x', 'n-w']) {
    await expect(body.locator(`.fm-node[data-node-id="${id}"]`)).toHaveCount(1);
  }
  await expect(body.locator('.fm-node[data-node-id="n-arch-old"]')).toHaveCount(0);

  // ① DOM 遍历零 emoji（✻ 白名单显式排除）
  expect(await emojiScan(page), JSON.stringify(await emojiScan(page))).toEqual([]);

  // ② 状态图标 = 内联 SVG（4 态全数），图标 span 内无文字字形残留
  await expect(body.locator('.fm-node[data-node-id="n-a"] .solar-node-status.ok svg')).toHaveCount(1);
  await expect(body.locator('.fm-node[data-node-id="n-f"] .solar-node-status.err svg')).toHaveCount(1);
  await expect(body.locator('.fm-node[data-node-id="n-b"] .solar-node-status.warn svg')).toHaveCount(1);
  await expect(body.locator('.fm-node[data-node-id="n-x"] .solar-node-status.cancelled svg')).toHaveCount(1);
  const iconTexts = await page.evaluate(() =>
    [...document.querySelectorAll('.fm-node .solar-node-status')].map((el) => el.textContent.trim()));
  expect(iconTexts).toEqual(['', '', '', '']);

  // ③ 等待脚注：SVG 沙漏 + 文字段结构；归档降级 = i18n 纯文字（三条解析路径全演练）
  const noteIcon = body.locator('.fm-node[data-node-id="n-w"] .fm-wait-note svg.fm-wait-note-icon');
  await expect(noteIcon).toHaveCount(1);
  const noteText = await body.locator('.fm-node[data-node-id="n-w"] .fm-wait-note-text').textContent();
  expect(noteText).toContain('等待: ');
  expect(noteText).toContain('已完成节点(n-a)');            // 路径1：图内可见 → 名(id)
  expect(noteText).toContain('已归档上游节点（已归档）');      // 路径2：快照内+已归档 → 名（已归档）
  expect(noteText).toContain(`${GHOST_ID}（已归档）`);        // 路径3：不在快照 → 裸id（已归档）
  expect(noteText).not.toContain('✦');
  const noteTitle = await body.locator('.fm-node[data-node-id="n-w"] .fm-wait-note').getAttribute('title');
  expect(noteTitle).toContain('已归档上游节点（已归档）');     // hover title 保留全量

  // ④ 卡级防溢出：所有卡 scrollWidth <= clientWidth（+1px 容差），长脚注截断生效
  const { cards, note } = await overflowScan(page);
  expect(cards.filter((c) => c.over), JSON.stringify(cards)).toEqual([]);
  expect(note).not.toBeNull();
  expect(note.ellipsis).toBe(true);
  expect(note.clip).toBe(true);
  expect(note.nowrap).toBe(true);
  expect(note.engaged, JSON.stringify(note)).toBe(true); // 长内容确实被截断（非侥幸放得下）

  // ⑤ TTL 倒计时徽章（⏱/fmtTtl 域）v3 已移除——回归哨兵：持续缺席
  await expect(body.locator('.fm-node .fm-ttl')).toHaveCount(0);

  // ⑥ 点5 回归（agent 退位 + preset/plugins 合显，前置批 07338482 已落地，本批断言）：
  //    卡面无 agent；preset·plugins 合显；空 preset → 「默认预设」；空 plugins → 无段
  await expect(body.locator('.fm-node[data-node-id="n-a"] .solar-node-sub'))
    .toHaveText('research-deep · nebflow-web, nebflow-fs');
  await expect(body.locator('.fm-node[data-node-id="n-w"] .solar-node-sub')).toHaveText('默认预设');
  const cardTextA = await body.locator('.fm-node[data-node-id="n-a"]').textContent();
  expect(cardTextA).not.toContain('researcher'); // agent 退位：卡面零 agent 名
  const cardTitleA = await body.locator('.fm-node[data-node-id="n-a"]').getAttribute('title');
  expect(cardTitleA).not.toContain('researcher');
  // 缺字段零 mock：n-f 无 preset/plugins/description → 副行落空态默认预设、无 desc 行
  await expect(body.locator('.fm-node[data-node-id="n-f"] .solar-node-sub')).toHaveText('默认预设');
  await expect(body.locator('.fm-node[data-node-id="n-f"] .fm-desc')).toHaveCount(0);

  // ⑦ 哨兵扩域（2026-09-06）：动态打开归档面板 + 详情窗（blocked 旗 SVG / 描述区块
  //    渲染路径全演练）后，顶栏 + 整个 view-body 零渲染字符
  await page.click('[data-testid="archive-toggle"]');
  await page.waitForSelector('[data-testid="archive-panel"]:not([hidden])', { timeout: 5000 });
  const nodeClick = async (id) => {
    const node = body.locator(`.fm-node[data-node-id="${id}"]`);
    await node.click({ timeout: 2500 }).catch(() => node.dispatchEvent('click', { bubbles: true }));
  };
  await nodeClick('n-b'); // blocked → 详情含结构化阻塞区块
  await page.waitForSelector('[data-testid="detail-panel"].open', { timeout: 5000 });
  await expect(body.locator('.flow-blocked-badge svg.flow-blocked-flag')).toHaveCount(1);
  const badgeText = await body.locator('.flow-blocked-badge').textContent();
  expect(badgeText).toContain('已阻断');
  await nodeClick('n-a'); // 详情描述区块（点 3 渲染链）
  await page.waitForTimeout(300);
  await expect(body.locator('.fm-detail-desc')).toHaveText('调研并汇总竞品方案');
  // 详情标题/preset 行仍正常（回归顺带）
  await expect(body.locator('.fm-detail-title')).toHaveText('已完成节点');
  const wideScan = await emojiScan(page, ['.flowmap-nav-bar', '.flowmap-view-body']);
  expect(wideScan, JSON.stringify(wideScan)).toEqual([]);

  expect(pageErrors).toEqual([]);
});

test('375px 窄卡：卡级 scrollWidth 口径不破 + 超长等待脚注 ellipsis 仍生效', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);
  await openFlowMapInPlace(page);
  await page.setViewportSize({ width: 375, height: 667 });
  await page.waitForTimeout(500); // 就地视图 host 收缩 + 过渡落定

  const body = page.locator('.flowmap-view-body');
  await expect(body.locator('.fm-node')).toHaveCount(5); // 窄视口下视图仍在、卡片仍全数渲染

  expect(await emojiScan(page)).toEqual([]);
  const { cards, note } = await overflowScan(page);
  expect(cards.filter((c) => c.over), JSON.stringify(cards)).toEqual([]);
  expect(note).not.toBeNull();
  expect(note.ellipsis).toBe(true);
  expect(note.clip).toBe(true);
  expect(note.engaged, JSON.stringify(note)).toBe(true);
  // 窄卡下截断应更紧（文字段可用宽度 < 默认视口时的可用宽度）
  expect(note.sw).toBeGreaterThan(note.cw);
  expect(pageErrors).toEqual([]);
});

// mobile-projects-single-column.spec.mjs — 手机端「项目」页单列纵向排布验收
//（作者令 2026-10-29 13:25 附手机截图：「手机端的项目界面 应该是一列往下翻的
// 排布 而不是这样可以往右边拉的排布」）。
//
// 缺陷机理（修复前实测，390×844 五项目探针）：P8 手机块只改了
// `flex-direction: column`，基座 FLOW_CSS `.team-scroll`（flowCss.js:20）的
// `flex-wrap: wrap` 原样存活——wrap 语义随方向转到纵轴：卡片总高超出滚动容器
// 高（≥4 张卡，一屏即触）即竖向换「列」，第二列整列落在 x≥viewport 外
// （scrollWidth 768 > clientWidth 390），页面可整体右拉、右列截断——作者截图
// 即右拉中段态。修复 = mobileLayer.css P8 块补 `flex-wrap: nowrap` +
// `overflow-x: hidden`（桌面 FLOW_CSS 零触碰）。
//
// 断言钉（本 spec）：
//   M1 单列 = 列数 1：≥5 张卡（修复前必触发竖向换列的量）同 x、y 严格递增；
//   M2 零横向 overflow：scroll.scrollWidth ≤ clientWidth ∧ 文档不横滚
//      ∧ 计算值 flex-wrap: nowrap ∧ overflow-x: hidden；
//   M3 卡片全宽：每卡宽 = 滚动容器内容宽（390 − 2×12 padding = 366）；
//   M4 字段行（工作区/AGENTS.md/运行中 agent/描述）单列全宽下无新截断：
//      超长 workspace 走 ellipsis（值列内收敛，不撑卡/不撑页）；
//      描述行手机层 = 样张 §6 口径单行 ellipsis（说明省略为既批设计非回归）；
//   D1 桌面断点回归：1280 视口（无手机层）`.team-scroll` 保持
//      flex-direction: row ∧ flex-wrap: wrap ∧ 双卡同排（横向网格原样）。
//
// 数据面 = GET /api/projects 夹具（5 项目、超长路径/描述）+ flow-map 空快照，
// 零后端、零 sbt。
// Run: node <stand>/node_modules/@playwright/test/cli.js test tests/mobile-projects-single-column.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { dockTo } from './_mobile-dock-nav.mjs';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const SID = 'projcol-root-session';

// 五项目：四张标准高卡（修复前 390×844 必竖向换列）+ 超长 workspace/描述各一。
function projectsFixture() {
  return { projects: [
    { name: 'CdZnTe 探测器研究', workspace: '/Users/kaiyu/.stand/projects/CdZnTe-detector-research-lab-notebook', description: 'CdZnTe（碲锌镉）探测器研究——探测器制备、能谱性能表征与读出电子学联调', agentFile: true, createdAt: Date.now() - 86400_000 },
    { name: 'SlideBlocks Studio', workspace: '/Users/kaiyu/Claude code/SlideBlocksStudio', description: 'SlideBlocks 演示引擎与课件编辑器', agentFile: true, createdAt: Date.now() - 7200_000 },
    { name: 'Slideblocks Skill Development', workspace: '/Users/kaiyu/Claude code/Slideblocks-Skill-Development-Long-Name-Path', description: '技能包开发与评测流水线', agentFile: true, createdAt: Date.now() - 3600_000 },
    { name: 'D0STEL', workspace: '/Users/kaiyu/Claude code/D0STEL', description: 'D0STEL——SiPM/D0STEL 探测器读出固件', agentFile: true, createdAt: Date.now() - 1800_000 },
    { name: 'chisel-fpga', workspace: '/Users/kaiyu/.stand/projects/RendererIsland-macOS-liuhai-display-driver-fpga', description: '', agentFile: true, createdAt: Date.now() - 600_000 },
  ] };
}

async function bootApp(page) {
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
      if (p === '/api/projects') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(projectsFixture()) });
      }
      if (p.startsWith('/api/projects/') && p.endsWith('/flow-map')) {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ nodes: [], worktrees: [], meta: { project: 'x', updatedAt: Date.now() } }) });
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
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'getHistory') {
        ws.send(JSON.stringify({
          type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0,
        }));
      }
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

/** 设置根页「工作台」行 → projects 推送页（dock 批 2026-10-29 换代：v3 双液态
 *  下拉列 `.mh-dd[data-dd="right"]` 整族退役，入口统一走设置 hub 行）。 */
async function pushProjectsPage(page) {
  await dockTo(page, 'settings');
  await page.locator('.mobile-page[data-page="settings"] .mf-hub-row[data-hub-page="projects"]').click();
  await expect(page.locator('.mobile-page[data-page="projects"]')).toHaveClass(/live/);
  const cards = page.locator('.mobile-page[data-page="projects"] .team-card.project-card');
  await expect(cards).toHaveCount(5);
  return cards;
}

test.use({ viewport: { width: 390, height: 844 }, hasTouch: true, colorScheme: 'light' });

test('M1+M2+M3 单列纵滚：列数=1、零横向 overflow、卡片全宽（≥5 卡，修复前必换列）', async ({ page }) => {
  await bootApp(page);
  const cards = await pushProjectsPage(page);

  const probe = await page.evaluate(() => {
    const scope = document.querySelector('.mobile-page[data-page="projects"]');
    const scroll = scope.querySelector('.team-scroll');
    const box = (el) => {
      const r = el.getBoundingClientRect();
      return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) };
    };
    const cs = getComputedStyle(scroll);
    return {
      scroll: box(scroll),
      scrollMetrics: { scrollW: scroll.scrollWidth, clientW: scroll.clientWidth },
      doc: { scrollW: document.documentElement.scrollWidth, clientW: document.documentElement.clientWidth },
      style: { flexDirection: cs.flexDirection, flexWrap: cs.flexWrap, overflowX: cs.overflowX, overflowY: cs.overflowY },
      cards: [...scope.querySelectorAll('.team-card.project-card')].map(box),
    };
  });

  // M2 零横向 overflow（先于形态断言：这是作者令的硬口径）。
  expect(probe.style.flexWrap).toBe('nowrap');
  expect(probe.style.overflowX).toBe('hidden');
  expect(probe.scrollMetrics.scrollW).toBeLessThanOrEqual(probe.scrollMetrics.clientW);
  expect(probe.doc.scrollW).toBeLessThanOrEqual(probe.doc.clientW);

  // M1 单列：全部卡片同一 x；y 随序严格递增（纵排，无任何并排对）。
  expect(probe.cards.length).toBeGreaterThanOrEqual(5);
  const xs = new Set(probe.cards.map((c) => c.x));
  expect(xs.size).toBe(1);
  for (let i = 1; i < probe.cards.length; i++) {
    expect(probe.cards[i].y).toBeGreaterThanOrEqual(probe.cards[i - 1].y + probe.cards[i - 1].h - 1);
  }

  // M3 全宽：卡宽 = 滚动容器内容宽（390 − 2×12 padding = 366），逐卡相等。
  const innerW = probe.scroll.w - 24;
  expect(innerW).toBe(366);
  for (const c of probe.cards) expect(c.w).toBe(innerW);
});

test('M4 字段行单列全宽核查：超长 workspace ellipsis 收敛、描述行无溢出、四行齐备', async ({ page }) => {
  await bootApp(page);
  const cards = await pushProjectsPage(page);

  // 推入动画（translateX(102%)→0，260ms）未终态时 getBoundingClientRect 带整体
  // 位移（P8 T1 同款注意）——绝对坐标断言前 poll 到页面 x=0 终态。
  const pageX = () => page.locator('.mobile-page[data-page="projects"]').evaluate((el) => Math.round(el.getBoundingClientRect().x));
  await expect.poll(pageX, { timeout: 5000 }).toBe(0);

  const c1 = cards.nth(0);
  // 四字段行齐备（工作区 / AGENTS.md / 运行中 agent / 描述）。
  await expect(c1.locator('.project-field-label')).toHaveCount(4);
  await expect(c1.locator('.project-field', { hasText: 'AGENTS.md' })).toHaveCount(1);
  await expect(c1.locator('.project-field', { hasText: '运行中 agent' })).toHaveCount(1);

  // 超长 workspace：值列 ellipsis 生效（确实在截）且不撑卡——卡片宽保持全宽、
  // 滚动容器无横向增量（单列全宽下的「省略」而非「新截断」）。
  const ws = c1.locator('.project-field-value.mono');
  const wsProbe = await ws.evaluate((el) => ({
    whiteSpace: getComputedStyle(el).whiteSpace,
    textOverflow: getComputedStyle(el).textOverflow,
    truncated: el.scrollWidth > el.clientWidth,
  }));
  expect(wsProbe.whiteSpace).toBe('nowrap');
  expect(wsProbe.textOverflow).toBe('ellipsis');
  expect(wsProbe.truncated).toBe(true);

  // 描述行（含 ✎ 编辑钮）：手机层 = 样张 §6 超长文本口径单行省略（既批设计，
  // 非「新截断」——P8 批注释明「workspace/说明 ellipsis」）。断言收敛面：
  // 行盒整体落在视口/卡内（右缘 ≤ 390）∧ 省略号生效（内容确实被截在值列内）
  // ∧ 滚动容器零横向增量。
  const desc = c1.locator('.project-field-value.desc');
  const descProbe = await desc.evaluate((el) => ({
    whiteSpace: getComputedStyle(el).whiteSpace,
    textOverflow: getComputedStyle(el).textOverflow,
    truncated: el.scrollWidth > el.clientWidth,
    right: Math.round(el.getBoundingClientRect().right),
  }));
  expect(descProbe.whiteSpace).toBe('nowrap');
  expect(descProbe.textOverflow).toBe('ellipsis');
  expect(descProbe.truncated).toBe(true);
  expect(descProbe.right).toBeLessThanOrEqual(390);

  const scrollMetrics = await page.evaluate(() => {
    const s = document.querySelector('.mobile-page[data-page="projects"] .team-scroll');
    return { scrollW: s.scrollWidth, clientW: s.clientWidth };
  });
  expect(scrollMetrics.scrollW).toBeLessThanOrEqual(scrollMetrics.clientW);
});

test('D1 桌面断点回归：宽视口 .team-scroll 保持 row+wrap、双卡同排（网格原样）', async ({ browser }) => {
  // 视口取 1720：桌面画板 pane 实测 1280 视口仅 ~470 宽（<双卡最小和 574，
  // 桌面网格本身即单卡/行——非本批改动面）；1720 下 pane 足宽，网格形态可观测。
  const ctx = await browser.newContext({ viewport: { width: 1720, height: 900 }, colorScheme: 'light' });
  const page = await ctx.newPage();
  await bootApp(page);

  // 桌面无手机层；直接走桌面导出 API 开 projects 页签（与侧栏键同一入口函数）。
  await page.evaluate(async () => {
    const m = await import('/js/projectTab.js');
    await m.openProjectsTab();
  });
  const scroll = page.locator('#canvas-content .canvas-tab-pane[data-tab-id="projects"] .team-scroll');
  await expect(scroll.locator('.team-card.project-card')).toHaveCount(5);

  const probe = await page.evaluate(() => {
    const scroll = document.querySelector('#canvas-content .canvas-tab-pane[data-tab-id="projects"] .team-scroll');
    const cs = getComputedStyle(scroll);
    const cards = [...scroll.querySelectorAll('.team-card.project-card')].map((el) => {
      const r = el.getBoundingClientRect();
      return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width) };
    });
    return {
      mobileLayer: document.body.classList.contains('mobile-layer'),
      flexDirection: cs.flexDirection, flexWrap: cs.flexWrap,
      cards,
    };
  });

  expect(probe.mobileLayer).toBe(false);
  // 桌面网格口径原样：row + wrap，前两卡同排（y 相等、x 递增）——本批零触碰。
  expect(probe.flexDirection).toBe('row');
  expect(probe.flexWrap).toBe('wrap');
  expect(probe.cards[0].y).toBe(probe.cards[1].y);
  expect(probe.cards[1].x).toBeGreaterThan(probe.cards[0].x + probe.cards[0].w - 1);
  await ctx.close();
});

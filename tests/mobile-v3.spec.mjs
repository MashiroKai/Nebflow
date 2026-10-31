// mobile-v3.spec.mjs — 手机层 v3 实施批验收（mobile-mockup-375 v3 落地 + 三扩展；
// dock 批 2026-10-29 判据换代：T1/T2 随液态下拉列/任务浮层退役改钉五栏底 dock
// 与任务页面板 —— 作者令「各节目都是独立界面 不再跟其他界面重叠」）。
//
// 覆盖（任务书验收①；dock 批换代注记）：
//   T1 【换代】五栏底 dock：五键序（消息/联系人/对话/canvas/设置逐字）、
//      图标+标签结构、切换 home/top、active 态、根页返回=聊天根、
//      退役浮层零残留（.mh-dd/.mh-scrim/body.mh-v3/#canvas-panel 不落地）
//   T2 【换代】任务页面板：聊天头任务键徽标 + push 页收编面板 + 分组序
//      （运行/排队——作者令 10-31：终态行不进手机端列表）+ 六级卡（状态点/标题/
//      进度/状态词/成员预览 +N 溢出）+ X 关闭对账（面板 hidden + 弹栈）
//   T3 profile 面板进出入 + 头像 localStorage 持久化 + 名称回车保存
//   T4 登录卡形态：单键制 + 官方横排 lockup（currentColor）+ 社交键退役
//   T5 顶栏无时间断言（v3① 口径守护）
//   T6 走廊退役：内容恒全宽（#main margin-right 0）+ 桌面壳退场
//   T7 附件条 in-flow：溢出收「+N」（真实 MutationObserver 链）
//
// 数据面 = stub 夹具缝（delegateChainStub.__setFixture，12 行 = 活跃 10 + 终态 2），
// 与 tasklist-chain-blocks.spec.mjs 同一 harness 族；零后端、零 sbt。
// Run: node node_modules/@playwright/test/cli.js test tests/mobile-v3.spec.mjs

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

const SID = 'mv3-root-session';
const T0 = Date.now();

// 手机层激活口径 = bootstrap.js 同源（≤768px 折叠点）：整文件手机视口。
test.use({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: 'dark' });

// 1×1 红色 PNG（头像持久化断言用；解码后的池 img 即证）。
const PX_PNG = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';

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

/** delegate 链夹具：12 行 = 运行 6（#61 富成员 5 链成员）+ 排队 3 + 终态 2
 * （completed/failed——作者令 10-31 过滤面的反例证）+ 中断 1（灰态保留面）。
 * 手机端面板只出活跃 10 卡（运行 7 + 排队 3），终态 2 行不得进列表。 */
async function seedTasks(page) {
  await page.evaluate(() => {
    const now = Date.now();
    const row = (taskNo, title, status, minsAgo, extra = {}) => ({
      taskNo: String(taskNo), delegateId: `delegate-nebflow-t${taskNo}`, title,
      executor: 'nebflow', status, external: false, project: 'Nebflow',
      createdAt: now - minsAgo * 60_000, orchestrationMode: '', archivedAt: null, ...extra,
    });
    const node = (id, label, status, deps = []) => ({
      id, kind: deps.length ? 'flow-step' : 'delegate', label, description: '', status, deps, executor: '',
    });
    const chain = (taskNo, nodes) => ({
      taskId: String(taskNo), delegateId: `delegate-nebflow-t${taskNo}`,
      nodes, worktree: null, verdict: null, mergeQueue: [], frames: [],
    });
    const rows = [
      row(61, '移动端 v3 落地', 'running', 3),
      row(62, '规格对齐回归', 'running', 9),
      row(63, '证据链补全', 'running', 17),
      row(64, '构建脚本梳理', 'running', 26),
      row(65, '键位映射审计', 'running', 41),
      row(66, '皮肤 token 巡检', 'running', 58),
      row(67, 'wiki 图谱 UX', 'ready-to-merge', 95, { mergeQueuePos: { position: 1, total: 3, readyAt: now - 40_000, branch: 'delegate/wiki-ux', baseBranch: 'main' } }),
      row(68, '侧栏重构收尾', 'ready-to-merge', 120, { mergeQueuePos: { position: 2, total: 3, readyAt: now - 30_000, branch: 'delegate/sidebar-2', baseBranch: 'main' } }),
      row(69, '会话头居中', 'ready-to-merge', 150, { mergeQueuePos: { position: 3, total: 3, readyAt: now - 20_000, branch: 'delegate/head-center', baseBranch: 'main' } }),
      row(70, '附件 in-flow', 'completed', 190),
      row(71, '登录卡单键', 'failed', 240),
      row(72, '走廊退役', 'interrupted', 320),
    ];
    const chains = [
      chain(61, [
        node('delegate-nebflow-t61', '#61', 'running'),
        node('s61a', '顶栏悬浮态', 'completed', ['delegate-nebflow-t61']),
        node('s61b', '液态下拉列', 'running', ['s61a']),
        node('s61c', '任务浮层六级卡', 'running', ['s61b']),
        node('s61d', 'profile 面板', 'pending', ['s61c']),
      ]),
      chain(62, [node('delegate-nebflow-t62', '#62', 'running'), node('s62a', 'spec 骨架', 'completed', ['delegate-nebflow-t62']), node('s62b', '开合断言', 'running', ['s62a'])]),
    ];
    import('/js/delegateChainStub.js').then((stub) => {
      stub.__setFixture({ rows, chains, children: {} });
      window.dispatchEvent(new Event('delegate-chain-mirror-updated'));
    });
  });
  await page.waitForTimeout(200);
}

test.beforeEach(async ({ page }) => {
  await bootApp(page);
});

test('T1 five-tab dock: author-order keys, icon+label structure, switching, active state, retired floats absent', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  // 结构钉：五键 = 作者逐字顺序（消息/联系人/对话/canvas/工作台——工作台批
  // 2026-10-30：设置自顶层导航降级为工作台成员）；探针同源。
  const dock = page.locator('#mobile-dock');
  await expect(dock).toBeVisible();
  const keys = page.locator('#mobile-dock .dock-key');
  await expect(keys).toHaveCount(5);
  const order = await keys.evaluateAll((els) => els.map((b) => b.dataset.dock));
  expect(order).toEqual(['messages', 'contacts', 'chat', 'canvas', 'workbench']);
  const probeDock = await page.evaluate(() => window.__nfMobileNav?.dock);
  expect(probeDock).toEqual(['messages', 'contacts', 'chat', 'canvas', 'workbench']);
  // 键结构 = 图标在上 + 文字标签在下（小红书基准）；标签为中文。
  // 中键改版批（2026-10-29 23:36）：第三栏 = chat home 键 —— 中心键容器
  // （.dock-center，无常规 .dock-ic 图标位）；其余四键 = 常规 icon+label。
  const labels = await keys.evaluateAll((els) => els.map((b) => b.querySelector('.dock-label')?.textContent));
  expect(labels).toEqual(['消息', '联系人', '对话', 'canvas', '工作台']);
  for (let i = 0; i < 5; i++) {
    if (i === 2) await expect(keys.nth(i).locator('.dock-center')).toBeVisible();
    else {
      await expect(keys.nth(i).locator('.dock-ic svg')).toBeVisible();
      await expect(keys.nth(i).locator('.dock-label')).toBeVisible();
    }
  }
  // boot 落聊天根：中键（对话 = chat home 键）激活。
  expect(await page.evaluate(() => window.__nfMobileNav?.home)).toBe('chat');
  await expect(page.locator('#mobile-dock .dock-key.active')).toHaveCount(1);
  await expect(page.locator('#mobile-dock .dock-key[data-dock="chat"]')).toHaveClass(/active/);

  // 切换：点「消息」→ home/top=messages + active 态 + 页面头标题；点中键 →
  // 详情全清回聊天根（输入框）+ 中键激活。
  await page.locator('#mobile-dock .dock-key[data-dock="messages"]').click();
  expect(await page.evaluate(() => window.__nfMobileNav?.home)).toBe('messages');
  await expect(page.locator('#mobile-dock .dock-key[data-dock="messages"]')).toHaveClass(/active/);
  await expect(page.locator('.mobile-page[data-page="messages"]')).toHaveClass(/live/);
  await expect(page.locator('.mobile-page[data-page="messages"] .mobile-page-title')).toHaveText('消息');
  await page.locator('#mobile-dock .dock-key[data-dock="chat"]').click();
  expect(await page.evaluate(() => window.__nfMobileNav)).toMatchObject({ home: 'chat', top: 'chat', stack: ['chat'] });
  await expect(page.locator('.mobile-page.live')).toHaveCount(0);
  await expect(page.locator('#mobile-dock .dock-key[data-dock="chat"]')).toHaveClass(/active/);
  // 五栏互斥替换（非堆叠）：再点「工作台」→ home 换根，栈深恒 1。
  await page.locator('#mobile-dock .dock-key[data-dock="workbench"]').click();
  expect(await page.evaluate(() => window.__nfMobileNav)).toMatchObject({ home: 'workbench', top: 'workbench' });
  expect((await page.evaluate(() => window.__nfMobileNav?.stack)).length).toBe(1);
  await expect(page.locator('#mobile-dock .dock-key[data-dock="chat"].active')).toHaveCount(0);
  await expect(page.locator('#mobile-dock .dock-key[data-dock="workbench"]')).toHaveClass(/active/);
  // 同根再点 = 清详情回根（栈已 1 → noop 不塌栈）。
  await page.locator('#mobile-dock .dock-key[data-dock="workbench"]').click();
  expect(await page.evaluate(() => window.__nfMobileNav?.home)).toBe('workbench');
  // Canvas 改造批②（2026-10-31）：dock 根页（消息/联系人/canvas/工作台）无「<」
  // ——返回语义由底 dock 承担（中键 = chat home）。根页返回 = 点中键回聊天根。
  expect(await page.locator('.mobile-page[data-page="workbench"] .mobile-back-btn')).toHaveCount(0);
  await page.locator('#mobile-dock .dock-key[data-dock="chat"]').click();
  expect(await page.evaluate(() => window.__nfMobileNav?.home)).toBe('chat');
  await expect(page.locator('#mobile-dock .dock-key.active')).toHaveCount(1);
  await expect(page.locator('#mobile-dock .dock-key[data-dock="chat"].active')).toHaveCount(1);

  // 退役浮层零残留（作者令「不再跟其他界面重叠」）：下拉列/幕布/任务浮层键/
  // 画板 bottom sheet 全不落地；探针版本换代。
  await expect(page.locator('.mh-dd')).toHaveCount(0);
  await expect(page.locator('.mh-scrim')).toHaveCount(0);
  await expect(page.locator('#mobile-task-key')).toHaveCount(0);
  await expect(page.locator('#canvas-panel')).toBeHidden();
  const panelDisplay = await page.locator('#canvas-panel').evaluate((el) => getComputedStyle(el).display);
  expect(panelDisplay).toBe('none');
  const ver = await page.evaluate(() => window.__nfMobileLayer?.version);
  expect(String(ver)).toContain('dock');
  expect(pageErrors).toEqual([]);
});

test('T2 tasks page: chrome key badge, page-adopted panel, active-only list (running/queued; terminal filtered out), six-level cards, X close reconcile', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await seedTasks(page);

  // 聊天头任务键（chatChrome）徽标 = 在册任务数（collectChains 同源：运行 7 + 排队 3；
  // 终态 #70/#71 不过现役闸，interrupt #72 在列）。
  await expect(page.locator('.mh-chrome-btn[data-chrome="tasks"] .mh-chrome-badge')).toHaveText('10');

  // 点任务键 → push「任务」详情页；面板收编进页面体（浮层形态退役）。
  await page.locator('.mh-chrome-btn[data-chrome="tasks"]').click();
  expect(await page.evaluate(() => window.__nfMobileNav)).toMatchObject({ home: 'chat', top: 'tasks' });
  const panel = page.locator('.mobile-page[data-page="tasks"] #mobile-task-panel');
  await expect(panel).toBeVisible();
  // 面板在页面体内（收编判据）而非 body 直挂浮层。
  expect(await panel.evaluate((el) => !!el.closest('.mobile-page-body'))).toBe(true);
  // 头部统计：16 单元（#61 链 5 成员 + #62 链 3 + 5 薄块 + 排队 3——终态 2 行不计）
  // · 7 运行 —— unitCount 与任务列表同一算法（chainProgressMeta 同源口径）。
  await expect(panel.locator('.tp-stats')).toContainText('16');
  await expect(panel.locator('.tp-stats')).toContainText('7');
  // 分组序：运行中 → 排队（作者令 10-31：已完成组整组退役，标签只出两组）。
  const labels = await panel.locator('.tp-grp').allTextContents();
  expect(labels.length).toBe(2);
  expect(labels[0]).toContain('运行中');
  expect(labels[1]).toContain('排队');
  // 各组卡数：7 / 3；终态行（completed/failed）零进列表。
  const cards = panel.locator('.tp-card');
  await expect(cards).toHaveCount(10);
  await expect(panel.locator('.tp-card.tp-running')).toHaveCount(7);
  await expect(panel.locator('.tp-card.tp-queued')).toHaveCount(3);
  await expect(panel.locator('.tp-card.tp-done')).toHaveCount(0);
  for (const gone of ['#70', '#71']) {
    await expect(panel.locator('.tp-ttl', { hasText: gone })).toHaveCount(0);
  }
  // 六级卡：#61 富成员链 = 标题 + 进度 1/5 + 状态词 + 成员预览 2 行 + 「+2」溢出。
  const c61 = panel.locator('.tp-card', { hasText: '#61' });
  await expect(c61.locator('.tp-meta')).toContainText('1/5');
  await expect(c61.locator('.tp-word')).not.toBeEmpty();
  await expect(c61.locator('.tp-mem')).toHaveCount(2);
  await expect(c61.locator('.tp-mem-more')).toHaveText('+2');
  // interrupted（#72）保留在运行组：灰点 tp-g-stop（重启对账批语义——尸体系可见、
  // 禁借 done 勾绿；活跃过滤批后不再随终态组退场）。
  const c72 = panel.locator('.tp-card', { hasText: '#72' });
  await expect(c72).toHaveCount(1);
  await expect(c72).toHaveClass(/tp-running/);
  await expect(c72.locator('.tp-g-stop')).toHaveCount(1);
  await expect(c72.locator('.tp-word')).not.toBeEmpty();
  // 页面壳「<」关闭 → 对账弹栈（面板 hidden + top 回聊天根）。面板头 X 已随
  // 重复返回钮清查批在页面壳内退场（手机层唯一出页键 = 页面壳「<」）。
  await expect(panel.locator('.tp-x')).toBeHidden();
  await page.locator('.mobile-page[data-page="tasks"] .mobile-back-btn').click();
  await expect(page.locator('#mobile-task-panel')).toBeHidden();
  expect(await page.evaluate(() => window.__nfMobileNav?.top)).toBe('chat');
  expect(pageErrors).toEqual([]);
});

test('T3 merged panel: avatar opens Agent+Memory panel (agent pane), avatar localStorage persistence, name Enter-save', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  // 头像持久化：先种 dataURL 再刷新 —— 槽位渲染池 img（avatarCache.js 键位镜像）。
  await page.evaluate((dataUrl) => {
    localStorage.setItem('nebflow_agent_avatar_Nebula', dataUrl);
  }, PX_PNG);
  await page.reload();
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await expect(page.locator('#agent-avatar-btn img.agent-avatar-img')).toHaveCount(1);

  // 进：点头像 → 合并面板（面板合并批 ①③：memory-overlay.on + 智能体分区；
  // 面板锚顶栏下居中（作者令 ⑤），不出屏；旧独立 #agent-profile-panel 不存在）。
  await page.locator('#agent-avatar-btn').click();
  await expect(page.locator('#memory-overlay')).toHaveClass(/on/);
  const panel = page.locator('#memory-modal');
  await expect(panel).toBeVisible();
  await expect(panel).toHaveAttribute('data-pane', 'agent');
  const vb = page.viewportSize();
  // 页面无动画直切（作者令 2026-10-30 动画退役批）—— 轮询等落定帧，
  // 不在渲染中途断言。
  await expect.poll(async () => {
    const b = await panel.boundingBox();
    return !!b && b.x >= 0 && b.x + b.width <= vb.width + 1 && b.y >= 0;
  }).toBe(true);
  const pb = await panel.boundingBox();
  expect(pb).toBeTruthy();
  expect(pb.x + pb.width).toBeLessThanOrEqual(vb.width + 1);

  // 名称回车保存：写穿 rootDisplayName + agentsData + 事件（会话头胶囊同帧更新）。
  await page.locator('.ap-name-input').fill('大笨');
  await page.locator('.ap-name-input').press('Enter');
  const check = await page.evaluate(() => {
    const s = (window.__mv3state = window.__mv3state) || null;
    return import('/js/state.js').then((m) => ({
      root: m.default.rootDisplayName,
      row: (m.default.agentsData || []).find((x) => x.name === 'Nebula'),
    }));
  });
  expect(check.root).toBe('大笨');
  // agentsData 行：harness 无 agents 清单时仅 override 写入（行缺席 = 合法路径）。
  expect(!check.row || check.row.displayName === '大笨').toBe(true);

  // 头像点击即换（作者令②）：面板头像盒 = 文件选择入口（无「设置头像」钮、
  // 无「重置头像」钮 —— 按钮族 DOM 不存在）。
  await expect(panel.locator('.ap-avatar-box')).toHaveAttribute('aria-label', '上传头像');
  await expect(panel.locator('.ap-btnrow, .ap-btn-primary, .ap-btn-ghost')).toHaveCount(0);

  // 出：点外（Esc → 桌面 overlay 收面路径）关闭 + overlay 态摘除。
  await page.keyboard.press('Escape');
  await expect(page.locator('#memory-overlay')).not.toHaveClass(/on/);
  expect(pageErrors).toEqual([]);
});

test('T4 login card: single unified key + horizontal lockup (currentColor), social keys retired', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await page.evaluate(async () => {
    const m = await import('/js/activityBar.js');
    m.openLoginModal({ deferPopup: true });
  });
  const modal = page.locator('.nebflow-login-modal');
  await expect(modal).toBeVisible();
  // 官方横排 lockup：standLockupSvg 内联 svg（fill/stroke 不带死色 → currentColor）。
  const lockup = modal.locator('.login-lockup svg');
  await expect(lockup).toHaveCount(1);
  const lockupHtml = await lockup.evaluate((el) => el.outerHTML);
  expect(lockupHtml).not.toMatch(/#[0-9a-fA-F]{3,8}/); // 无硬编码色值
  // 单键制：#login-unified 一枚；社交键退役（无 GITHUB/GOOGLE 入口）。
  await expect(modal.locator('#login-unified')).toHaveCount(1);
  await expect(modal.locator('#login-github, #login-google, .login-social-btn[href]')).toHaveCount(0);
  // 主键规格（作者补充令：官网登录卡对齐，绿实色键退役）—— 单色反白实色键：
  // 无图标纯文字、非绿底、暗主题下 = 前景色底（官网 dark 腿 --auth-btn-* 对位）。
  const key0 = modal.locator('#login-unified');
  await expect(key0.locator('svg')).toHaveCount(0);
  await expect(key0).toHaveClass(/login-primary-btn/);
  expect(await key0.evaluate((el) => !/cfg-btn-primary|glass-control/.test(el.className))).toBe(true);
  const bg0 = await key0.evaluate((el) => getComputedStyle(el).backgroundColor);
  expect(bg0).not.toMatch(/rgb\((?:34|22), 1[0-9]{2}, 4[0-9]/); // 非品牌绿实色
  expect(bg0).toBe('rgb(232, 234, 237)'); // dark = var(--color-text) 前景反白
  expect(pageErrors).toEqual([]);
});

test('T5 header retired on mobile (liquid-glass v2 2026-10-31); top edge = fade band + floating circles; header carries no time', async ({ page }) => {
  // v2 契约（作者 08:43 裁定② + 09:03 拍板）：手机端无 header 容器 —— #header
  // display:none（DOM 保留）；顶缘 = 88px 渐隐带 + 左右悬浮圆钮层；页标题
  // （头像/会话名/记忆钮）入流 .g2-chat-head。时间守护沿用到入流头与 header。
  const header = page.locator('#header');
  await expect(header).toBeHidden();
  const text = await header.innerText();
  expect(text).not.toMatch(/\d{1,2}:\d{2}/);
  // 时间元素不存在（历史时钟载体类名一并守护）。
  expect(await page.locator('#header .header-time, #header .clock, #header time').count()).toBe(0);
  // 顶缘渐隐带：存在、不拦截、契约材质（liquid-glass.css #g2-edge-fade）。
  const fade = page.locator('#g2-edge-fade');
  await expect(fade).toBeVisible();
  expect(await fade.evaluate((el) => getComputedStyle(el).pointerEvents)).toBe('none');
  expect(await fade.evaluate((el) => getComputedStyle(el).height)).toBe('88px');
  // 悬浮圆钮层：任务/搜索双键在场且可见（48px v2 液体玻璃正圆）。
  await expect(page.locator('#mobile-fab-layer .mh-chrome-btn')).toHaveCount(2);
  await expect(page.locator('.mh-chrome-btn[data-chrome="tasks"]')).toBeVisible();
  await expect(page.locator('.mh-chrome-btn[data-chrome="search"]')).toBeVisible();
  // 页标题入流：头像 + 会话名迁入 .g2-chat-head（首位、可见）。
  const head = page.locator('#chat > .g2-chat-head');
  await expect(head).toHaveCount(1);
  await expect(head.locator('#agent-avatar-btn')).toBeVisible();
  await expect(head.locator('#session-name')).toHaveCount(1);
});

test('T6 gutter retired: content full-width, desktop shells gone, task-list auto-bar gone', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  // 内容恒全宽：#main 无右缘走廊让位（v3③ 走廊退役）。
  await expect(page.locator('#main')).toHaveCSS('margin-right', '0px');
  // 桌面壳退场：活动栏/侧栏/右缘条/头侧按钮。
  for (const sel of ['#activity-bar', '#sidebar', '.edge-bar-right', '#sidebar-toggle', '#canvas-toggle-btn']) {
    await expect(page.locator(sel)).toBeHidden();
  }
  // r4 任务自动浮条退役（面板 face = mh-tpanel）。
  await expect(page.locator('#task-list')).toBeHidden();
  // 附件条 in-flow：不再是 fixed 让位带（首行入流）。
  const att = page.locator('#attachment-preview');
  const attPos = await att.evaluate((el) => getComputedStyle(el).position);
  expect(attPos).toBe('static');
  expect(pageErrors).toEqual([]);
});

test('T7 attachment row: overflow folds to +N via the real observer chain', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  // 注入足够长的 chips 触发真实溢出折叠（MutationObserver 驱动 syncAttachmentRow）。
  await page.evaluate(() => {
    const row = document.getElementById('attachment-preview');
    for (let i = 0; i < 6; i++) {
      const chip = document.createElement('div');
      chip.className = 'att-chip';
      chip.textContent = `附件文件名很长很长-${i + 1}.pdf`;
      row.appendChild(chip);
    }
  });
  const more = page.locator('#attachment-preview .mh-att-more');
  await expect(more).toBeVisible();
  const moreText = await more.textContent();
  expect(moreText).toMatch(/^\+\d+$/);
  // 折叠件在场：至少一枚 chip 落 .mh-att-collapsed。
  expect(await page.locator('#attachment-preview .mh-att-collapsed').count()).toBeGreaterThanOrEqual(1);
  // 点 +N 展开（横向滚动全员可见），再点收拢。
  await more.click();
  await expect(page.locator('#attachment-preview')).toHaveClass(/mh-att-expanded/);
  expect(await page.locator('#attachment-preview .mh-att-collapsed').count()).toBe(0);
  await more.click();
  expect(await page.locator('#attachment-preview .mh-att-collapsed').count()).toBeGreaterThanOrEqual(1);
  expect(pageErrors).toEqual([]);
});

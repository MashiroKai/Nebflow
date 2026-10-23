// mobile-v3.spec.mjs — 手机层 v3 实施批验收（mobile-mockup-375 v3 落地 + 三扩展）。
//
// 覆盖（任务书验收①）：
//   T1 双圆钮液态下拉开合：aria-expanded / 宽 40 恒定 / 子键数与可见性
//      （0 计数键 hidden 退场）/ 列高含头钮（--dh）／两列互斥
//   T2 任务二级液态：开合 + 形变类 + 分组序（运行/排队/已完成）+ 六级卡
//      （状态点/标题/进度/状态词/成员预览 +N 溢出）+ 徽标计数
//   T3 profile 面板进出入 + 头像 localStorage 持久化 + 名称回车保存
//   T4 登录卡形态：单键制 + 官方横排 lockup（currentColor）+ 社交键退役
//   T5 顶栏无时间断言（v3① 口径守护）
//   T6 走廊退役：内容恒全宽（#main margin-right 0）+ 桌面壳退场
//   T7 附件条 in-flow：溢出收「+N」（真实 MutationObserver 链）
//
// 数据面 = stub 夹具缝（delegateChainStub.__setFixture，12 卡三分组），
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

/** delegate 链夹具：12 卡 = 运行 6（#61 富成员 5 链成员）+ 排队 3 + 已完成 3。 */
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
      row(72, '走廊退役', 'completed', 320),
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

test('T1 liquid dropdowns: aria-expanded, width-40 constant, visible-key count, mutual exclusion', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  const hdL = page.locator('.mh-dd[data-dd="left"] .mh-dd-hd');
  const hdR = page.locator('.mh-dd[data-dd="right"] .mh-dd-hd');
  await expect(hdL).toHaveAttribute('aria-expanded', 'false');
  await expect(page.locator('.mh-dd[data-dd="left"]')).toHaveCSS('width', '40px');
  await expect(page.locator('.mh-dd[data-dd="right"]')).toHaveCSS('width', '40px');

  // 左列成员：任务键 + FLOAT_IDS 搬移键在列内；关闭的 memory 键 hidden 退场
  // （.mh-key[hidden]{display:none}）。手机小批（2026-10-21 作者令「与桌面端做
  // 区分」）后 bg/bgagent 键改**图标常驻**：0 计数不退场（.hidden 只藏角标），
  // 注入图标在场；待答胶囊内容驱动语义不变（0 退场）。
  await expect(page.locator('#mobile-task-key')).toBeVisible();
  for (const id of ['search-btn', 'reminder-btn', 'daemon-btn', 'bg-indicator', 'bgagent-indicator']) {
    await expect(page.locator(`.mh-dd[data-dd="left"] > .mh-dd-bd > #${id}`)).toBeVisible();
  }
  for (const id of ['bg-indicator', 'bgagent-indicator']) {
    await expect(page.locator(`.mh-dd[data-dd="left"] > .mh-dd-bd > #${id} svg.mh-persist-ico`)).toBeVisible();
  }
  await expect(page.locator('.mh-dd[data-dd="left"] > .mh-dd-bd > #memory-btn')).toBeHidden();

  await hdL.click();
  await expect(hdL).toHaveAttribute('aria-expanded', 'true');
  // 列高含头钮（40 + n*40 + (n-1)*6 + 6）；少算 ⇒ 末键被裁（回归护栏）。
  // spring 高度 0.55s + 末键 stagger(~0.68s) —— 等全部过渡终态再量。
  await page.waitForTimeout(1100);
  const box = await page.locator('.mh-dd[data-dd="left"]').boundingBox();
  const lastKey = await page.locator('.mh-dd[data-dd="left"] > .mh-dd-bd > .mh-key:visible').last().boundingBox();
  expect(lastKey).toBeTruthy();
  expect(box.height).toBeGreaterThan(120); // 展开终态（≥ 头钮 + 2 键）
  expect(lastKey.y + lastKey.height).toBeLessThanOrEqual(box.y + box.height + 1);

  // 互斥：开右列收左列。
  await hdR.click();
  await expect(hdR).toHaveAttribute('aria-expanded', 'true');
  await expect(hdL).toHaveAttribute('aria-expanded', 'false');
  // 右列 = 六页面键（画板/项目/图谱/插件/用量/设置），全可见。
  const faces = page.locator('.mh-dd[data-dd="right"] > .mh-dd-bd > [data-face]');
  await expect(faces).toHaveCount(6);
  for (let i = 0; i < 6; i++) await expect(faces.nth(i)).toBeVisible();
  // scrim 同步开。
  await expect(page.locator('.mh-scrim')).toHaveClass(/open/);
  // 点幕全收。
  await page.locator('.mh-scrim').click({ position: { x: 187, y: 500 } });
  await expect(hdR).toHaveAttribute('aria-expanded', 'false');
  await expect(page.locator('.mh-scrim')).not.toHaveClass(/open/);
  expect(pageErrors).toEqual([]);
});

test('T2 task panel: morph class, badge count, group order (running/queued/done), six-level cards', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await seedTasks(page);

  // 任务键徽标 = 在册任务数（collectChains 同源：运行 6 + 排队 3）。
  await expect(page.locator('#mobile-task-key .mh-key-badge')).toHaveText('9');

  await page.locator('.mh-dd[data-dd="left"] .mh-dd-hd').click();
  await page.locator('#mobile-task-key').click();
  const panel = page.locator('#mobile-task-panel');
  await expect(panel).toBeVisible();
  // 二级液态形变类（morphIn 注入；收毕移除 —— 开后立刻在场）。
  await expect(panel).toHaveClass(/mh-morph-anim|mh-morph-close|tp-static|mh-tpanel/);
  // 头部统计：18 单元（#61 链 5 成员 + #62 链 3 + 4 薄块 + 排队 3 + 已完成 3）
  // · 6 运行 —— unitCount 与任务面板同一算法（chainProgressMeta 同源口径）。
  await expect(panel.locator('.tp-stats')).toContainText('18');
  await expect(panel.locator('.tp-stats')).toContainText('6');
  // 分组序：运行中 → 排队 → 已完成（标签文本序）。
  const labels = await panel.locator('.tp-grp').allTextContents();
  expect(labels.length).toBe(3);
  expect(labels[0]).toContain('运行中');
  expect(labels[1]).toContain('排队');
  expect(labels[2]).toContain('已完成');
  // 各组卡数：6 / 3 / 3。
  const cards = panel.locator('.tp-card');
  await expect(cards).toHaveCount(12);
  await expect(panel.locator('.tp-card.tp-running')).toHaveCount(6);
  await expect(panel.locator('.tp-card.tp-queued')).toHaveCount(3);
  await expect(panel.locator('.tp-card.tp-done')).toHaveCount(3);
  // 六级卡：#61 富成员链 = 标题 + 进度 1/5 + 状态词 + 成员预览 2 行 + 「+2」溢出。
  const c61 = panel.locator('.tp-card', { hasText: '#61' });
  await expect(c61.locator('.tp-meta')).toContainText('1/5');
  await expect(c61.locator('.tp-word')).not.toBeEmpty();
  await expect(c61.locator('.tp-mem')).toHaveCount(2);
  await expect(c61.locator('.tp-mem-more')).toHaveText('+2');
  // 已完成卡整卡降透明（tp-dim）。
  await expect(panel.locator('.tp-card.tp-done').first()).toHaveClass(/tp-dim/);
  // X 逆向收回 → 面板隐藏。
  await panel.locator('.tp-x').click();
  await expect(panel).toBeHidden();
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
  const pb = await panel.boundingBox();
  const vb = page.viewportSize();
  expect(pb).toBeTruthy();
  expect(pb.x).toBeGreaterThanOrEqual(0);
  expect(pb.x + pb.width).toBeLessThanOrEqual(vb.width + 1);
  expect(pb.y).toBeGreaterThanOrEqual(0);

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

test('T5 header carries no time (v3① guard)', async ({ page }) => {
  const header = page.locator('#header');
  await expect(header).toBeVisible();
  const text = await header.innerText();
  expect(text).not.toMatch(/\d{1,2}:\d{2}/);
  // 时间元素不存在（历史时钟载体类名一并守护）。
  expect(await page.locator('#header .header-time, #header .clock, #header time').count()).toBe(0);
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

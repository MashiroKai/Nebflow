// desktop-search-field-shots.spec.mjs — 桌面「消息」顶部搜索框 + 联系人搜索块
// 统一（desktop-search-field 批 2026-11-17）的运行态证据 spec。
//
// 作者令逐字：「然后电脑端的消息 顶部应该要有搜索的框 同时优化一下样式
//  联系人搜索那边也是 风格和手机端的搜索框保持一致」。
//
// 判据（三面）：
//   A 桌面消息面板：#panel-messages 头下常驻 .fm-msg-search（role=search）——
//     输入行 = .sf-field.nf-glass（buildFieldShell 单一构建器产物）+ 范围 chips
//     （全部/好友/设备/群聊，aria-pressed）+ 状态行 + 结果流（mark 高亮、按会话
//     分组）+ 脚注；空态 = 列表照常，有查询 = 列表让位；Esc/清空键回列表；
//     Mod+Shift+K 聚焦（keymap 单源注册，仅面板激活时生效）；无结果态文案在位。
//   B 桌面联系人面板：.fm-search 行收编 .sf-field.nf-glass 壳 + 清空键 + Esc，
//     提交语义零改动（提交键仍在、Enter/1s 节流/结果卡不动）。
//   C 手机对照腿：.mh-search-bar（body.mobile-layer）读数与改前逐值相同
//     （44/12/16——mobileSearchFlat ①块改饮 --sfs-* 令牌为值等价替换）。
//
// 通路钉（与 js/socialSearchCore.js 同口径）：conversationSnapshot()（messages.js
// 列表同源）→ 范围过滤 → 逐会话 api.getMessages 前向分页（50/页）客户端匹配；
// 零新后端（/api/search/chat 是 agent 会话历史搜索，与此无关）。
//
// 改前树腿（红口径）：DSF_LEGACY=1 + DSF_WEB_ROOT=<基线 web 树> ⇒
//   · 桌面消息面板**无** .fm-msg-search（本批判据钉在改动上，不是恒真断言）；
//   · 联系人 .fm-search 行**无** .sf-field 壳/清空键（输入框+提交键仍在）；
//   · 手机条读数不变（44/12/16 两腿同绿 = 手机端零回归证据）。
// 改前树 = 基线 a6fa7fd274b3667f37dbc60f351f37bc9be7ae36 的 web 导出。
//
// Self-contained：route-intercepted 静态文件 + mocked REST/WS（夹具与
// mobile-search-flat.spec.mjs 同源：fm_api_mock 好友域种子），零后端、零端口监听。
// Run（会话沙箱不吃 env 前缀 ⇒ 模式开关 = /tmp 旗标文件，见 LEGACY_FLAG）：
//   改后：node node_modules/@playwright/test/cli.js test \
//           tests/desktop-search-field-shots.spec.mjs
//   改前：touch /tmp/dsf-legacy-flag &&（同上）&& rm /tmp/dsf-legacy-flag
//   两腿各落 PNG + readings.json（几何/材质/字色计算值 ⇒ 数值对照表数据源）：
//   改后 → /tmp/dsf-evid-after/，改前 → /tmp/dsf-evid-before/。

import { test, expect } from '@playwright/test';
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { dockTo } from './_mobile-dock-nav.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
/** 改前腿旗标（沙箱内免 env 的模式开关；DSF_LEGACY=1 亦可）。 */
const LEGACY_FLAG = '/tmp/dsf-legacy-flag';
const LEGACY = !!process.env.DSF_LEGACY || existsSync(LEGACY_FLAG);
/** 改前 web 树：基线 a6fa7fd274b3667f37dbc60f351f37bc9be7ae36 的导出
 *  （git archive → /tmp/dsf-baseline-check，见回执）。 */
const BASELINE_WEB = '/tmp/dsf-baseline-check/src/main/resources/web';
const WEB = process.env.DSF_WEB_ROOT
  ? normalize(process.env.DSF_WEB_ROOT)
  : (LEGACY ? normalize(BASELINE_WEB) : join(HERE, '..', 'src', 'main', 'resources', 'web'));
const EVID = process.env.DSF_EVID_DIR || (LEGACY ? '/tmp/dsf-evid-before' : '/tmp/dsf-evid-after');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const SID = 'dsf-search-session';
/** 与 mobile-search-flat.spec.mjs 同源夹具：2 个「部署」命中会话 + 30 行填充 +
 *  群 g-1（部署场地）+ 用户 xintongxue（联系人提交搜索腿）。 */
const QUERY = '部署';
const NOHIT = '查无此词七七七';

/** 静态路由 + WS 夹具（与 mobile-search-flat.spec.mjs 的 bootApp 同源，origin 档位
 *  错开 8382/8383 避免与在跑的其他 spec 撞）。 */
async function bootApp(page) {
  const engine = page.context().browser()?.browserType().name() || 'chromium';
  const ORIGIN = engine === 'webkit' ? 'http://localhost:8383/' : 'http://localhost:8382/';
  await page.addInitScript(() => {
    const NOW = Date.now();
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
    localStorage.setItem('fm_api_mock', '1');
    const friends = [
      { userId: 'u-p', neblinkId: 'pagfriend', name: '分页君', avatarUrl: '', since: NOW - 86400e3 },
      { userId: 'u-k', neblinkId: 'kaifriend', name: '阿凯', avatarUrl: '', since: NOW - 172800e3 },
    ];
    const conversations = [
      {
        conversationId: 'c-p', friend: { userId: 'u-p', neblinkId: 'pagfriend', name: '分页君', avatarUrl: '' },
        lastMessage: { id: 220, senderId: 'u-p', kind: 'text', body: '部署计划已经就绪', createdAt: NOW - 60e3 },
        unreadCount: 0,
      },
      {
        conversationId: 'c-k', friend: { userId: 'u-k', neblinkId: 'kaifriend', name: '阿凯', avatarUrl: '' },
        lastMessage: { id: 330, senderId: 'me', kind: 'text', body: '部署清单在这里', createdAt: NOW - 120e3 },
        unreadCount: 0,
      },
    ];
    for (let i = 0; i < 30; i++) {
      const uid = `u-r${i}`;
      friends.push({ userId: uid, neblinkId: `row${i}`, name: `好友${String(i + 1).padStart(2, '0')}`, avatarUrl: '', since: NOW - (i + 3) * 86400e3 });
      conversations.push({
        conversationId: `c-r${i}`,
        friend: { userId: uid, neblinkId: `row${i}`, name: `好友${String(i + 1).padStart(2, '0')}`, avatarUrl: '' },
        lastMessage: { id: 1000 + i, senderId: uid, kind: 'text', body: `普通消息 ${i + 1}`, createdAt: NOW - (i + 3) * 3600e3 },
        unreadCount: 0,
      });
    }
    localStorage.setItem('fm_api_mock_seed', JSON.stringify({
      self: { userId: 'me', neblinkId: 'me@example.com', name: '我自己', avatarUrl: '' },
      users: [
        { userId: 'u-new', username: 'xintongxue', email: 'xin@example.com', displayName: '新同学', avatar: '' },
      ],
      friends,
      incoming: [],
      outgoing: [],
      conversations,
      messages: {
        'c-p': [
          { id: 210, senderId: 'me', kind: 'text', body: '周五能交付吗', createdAt: NOW - 3600e3 },
          { id: 220, senderId: 'u-p', kind: 'text', body: '部署计划已经就绪', createdAt: NOW - 60e3 },
        ],
        'c-k': [
          { id: 330, senderId: 'me', kind: 'text', body: '部署清单在这里', createdAt: NOW - 120e3 },
        ],
        'g-1': [
          { id: 440, senderId: 'u-p', kind: 'text', body: '部署场地已经订好', createdAt: NOW - 1800e3 },
        ],
      },
      groups: [{
        groupId: 'g-1', title: '周末球局', role: 'owner', memberCount: 2,
        lastMessage: { id: 440, senderId: 'u-p', kind: 'text', body: '部署场地已经订好', createdAt: NOW - 1800e3 },
        unreadCount: 0, lastMessageId: 440, createdAt: NOW - 86400e3 * 7,
      }],
      groupInvites: [],
      groupMembers: {
        'g-1': [
          { userId: 'me', username: null, display_name: '我自己', avatar: null, role: 'owner', joinedAt: 0 },
          { userId: 'u-p', username: null, display_name: '分页君', avatar: null, role: 'member', joinedAt: 0 },
        ],
      },
    }));
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/agents/Nebula/agent.json') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    if (p.startsWith('/api/')) {
      if (p === '/api/projects') {
        const projects = Array.from({length: 12}, (_, i) => ({name:`fixture-project-${String(i + 1).padStart(2, '0')}`, workspace:`/tmp/appearance-project-${i + 1}`, agentFile:'', description:'', createdAt:Date.now() - i * 86400000}));
        return route.fulfill({status:200, contentType:'application/json', body:JSON.stringify({projects})});
      }
      if (p === '/api/sessions') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      }
      if (p === '/api/neblink/status') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '', email: 'me@example.com', displayName: '我自己' }, peers: [] }) });
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
      type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done',
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
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
    });
  });
  await page.goto(ORIGIN);
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.waitForTimeout(400);
}

function watchErrors(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
  return errors;
}

/** readings.json 合并写入（跨 test/跨腿累积，数值对照表数据源）。 */
function bumpReadings(key, data) {
  if (!EVID) return;
  mkdirSync(EVID, { recursive: true });
  const file = join(EVID, 'readings.json');
  let all = {};
  try { all = JSON.parse(readFileSync(file, 'utf8')); } catch { /* 首写 */ }
  all[key] = data;
  writeFileSync(file, JSON.stringify(all, null, 2));
}

/** 字段读数探针（桌面 .sf-field / 手机 .mh-search-bar 通用）：几何 + 材质 + 字色。
 *  数值对照表数据源（readings.json）。 */
function probeField(page, sel, scope = null) {
  return page.evaluate(([selector, scopeSel]) => {
    const shell = document.querySelector(selector);
    if (!shell) return null;
    const cs = getComputedStyle(shell);
    const input = shell.querySelector('input');
    const ics = input ? getComputedStyle(input) : null;
    const icon = shell.querySelector('svg');
    const clear = shell.querySelector('.sf-field-clear, .mh-search-clear');
    const ccs = clear ? getComputedStyle(clear) : null;
    const r = shell.getBoundingClientRect();
    const focus = scopeSel ? document.querySelector(scopeSel) : null;
    return {
      rect: { w: Math.round(r.width * 100) / 100, h: Math.round(r.height * 100) / 100 },
      radius: cs.borderTopLeftRadius,
      padX: [cs.paddingLeft, cs.paddingRight],
      gap: cs.columnGap,
      bg: cs.backgroundColor,
      border: cs.borderTopColor,
      filter: cs.webkitBackdropFilter || cs.backdropFilter || 'none',
      boxShadow: cs.boxShadow,
      icon: icon ? getComputedStyle(icon).width : null,
      inputFs: ics ? ics.fontSize : null,
      inputColor: ics ? ics.color : null,
      placeholder: ics ? getComputedStyle(input, '::placeholder').color : null,
      clear: ccs ? { w: ccs.width, h: ccs.height, radius: ccs.borderTopLeftRadius, display: ccs.display } : null,
      chipH: focus ? getComputedStyle(focus).height : null,
    };
  }, [sel, scope]);
}

async function shot(page, name) {
  if (!EVID) return;
  mkdirSync(EVID, { recursive: true });
  await page.screenshot({ path: join(EVID, name) });
}

// ── A+B：桌面消息/联系人面板（1280×800 × 深/浅 × 改后判据 / 改前红口径）──────
test.describe(() => {
  test.use({ viewport: { width: 1280, height: 800 } });

for (const [theme, themeName] of [['light', 'light'], ['dark', 'dark']]) {
  test(`dsf: desktop messages panel search (${themeName})`, async ({ page }) => {
    await page.emulateMedia({ colorScheme: theme });
    const errors = watchErrors(page);
    await bootApp(page);
    await page.locator('#messages-btn').click();
    await expect(page.locator('#panel-messages.active')).toBeVisible();

    const wrap = page.locator('#panel-messages .fm-msg-search');
    const convList = page.locator('#panel-messages #fm-conversations');

    if (LEGACY) {
      // 改前红口径：桌面消息面板头部**没有**搜索框（判据钉在改动上）。
      await expect(wrap).toHaveCount(0);
      await expect(convList).toBeVisible();
      await shot(page, `before-desktop-messages-${themeName}.png`);
      return;
    }

    // ── 结构：常驻搜索框（role=search + .sf-field.nf-glass 输入行 + chips）──
    await expect(wrap).toBeVisible();
    await expect(wrap).toHaveAttribute('role', 'search');
    const shell = wrap.locator('.sf-field');
    await expect(shell).toHaveClass(/nf-glass/);
    const input = wrap.locator('#fm-msg-search-input');
    await expect(input).toBeVisible();
    await expect(input).toHaveAttribute('placeholder', '搜索聊天记录');
    await expect(shell.locator('svg').first()).toBeVisible();
    const chips = wrap.locator('.mh-scope-chip');
    await expect(chips).toHaveCount(4);
    await expect(chips.first()).toHaveText('全部');
    await expect(chips.first()).toHaveAttribute('aria-pressed', 'true');
    await expect(chips.nth(3)).toHaveText('群聊');

    // 空态：列表照常（chips/状态/结果/脚注不占位），清空键隐藏。
    await expect(convList).toBeVisible();
    await expect(wrap.locator('.mh-search-status')).toBeHidden();
    await expect(wrap.locator('.mh-sr-row')).toHaveCount(0);
    const clearBtn = shell.locator('.sf-field-clear');
    await expect(clearBtn).toBeHidden();

    // ── 读数（数值对照表数据源）：桌面 .sf-field 计算值 ──────────────────
    const readings = await probeField(page, '#panel-messages .fm-msg-search .sf-field',
      '#panel-messages .mh-scope-chip');
    expect(readings.rect.h, '字段高 = --sfs-height 44').toBe(44);
    expect(readings.radius, '圆角 = --ds-radius-xl 12px').toBe('12px');
    expect(readings.padX, '水平内距 = --ds-space-4 16px').toEqual(['16px', '16px']);
    expect(readings.icon, '图标 = --sfs-icon 16px').toBe('16px');
    expect(readings.inputFs, '输入字号 = --ds-fs-body 13px').toBe('13px');
    expect(readings.chipH, '范围 chip 高 26px（220px 侧栏适配档）').toBe('26px');
    bumpReadings(`desktop-messages.${themeName}`, readings);
    await shot(page, `after-desktop-messages-${themeName}-empty.png`);

    // ── 输入即搜（300ms 去抖 → 快照 + 分页扫描）：结果分组 + mark 高亮 ──────
    await input.fill(QUERY);
    const rows = wrap.locator('.mh-sr-row');
    await expect(rows.first()).toBeVisible({ timeout: 10000 });
    expect(await rows.count(), `「${QUERY}」命中 ≥3 条（c-p + c-k + g-1）`).toBeGreaterThanOrEqual(3);
    await expect(wrap.locator('.mh-sr-group').first()).toBeVisible();
    await expect(wrap.locator('.mh-sr-snippet mark').first()).toHaveText(QUERY);
    const status = wrap.locator('.mh-search-status');
    await expect(status).toBeVisible();
    await expect(status).toContainText('条结果');
    // 有查询 = 会话列表让位（.has-query 版面闸）。
    await expect(convList).toBeHidden();
    await expect(clearBtn).toBeVisible();
    await shot(page, `after-desktop-messages-${themeName}-results.png`);

    // 范围 chips 换档即重搜：群聊档只留 g-1 组。
    await chips.nth(3).click();
    await expect(wrap.locator('.mh-sr-group').first()).toContainText('周末球局', { timeout: 8000 });
    await shot(page, `after-desktop-messages-${themeName}-scope-group.png`);
    await chips.first().click();

    // ── 无结果态 ───────────────────────────────────────────────────────
    await input.fill(NOHIT);
    await expect(status).toContainText('无匹配结果', { timeout: 8000 });
    await expect(rows).toHaveCount(0);
    await shot(page, `after-desktop-messages-${themeName}-noresults.png`);

    // ── 清空 = 回列表（清空键腿）─────────────────────────────────────────
    await clearBtn.click();
    await expect(rows).toHaveCount(0);
    await expect(convList).toBeVisible();

    // ── Mod+Shift+K 聚焦（keymap 单源注册）+ Esc 清场 ─────────────────────
    await input.fill(QUERY);
    await expect(rows.first()).toBeVisible({ timeout: 10000 });
    await page.locator('#panel-messages .panel-header').click();
    await page.keyboard.press('ControlOrMeta+Shift+k');
    await expect(input).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(input).toHaveValue('');
    await expect(convList).toBeVisible();

    // ── 点结果行跳转：openConversation + revealMessage（通路可走通）────────
    await input.fill(QUERY);
    await expect(rows.first()).toBeVisible({ timeout: 10000 });
    await rows.first().click();
    await page.waitForTimeout(600);
    await shot(page, `after-desktop-messages-${themeName}-jump.png`);

    expect(errors, `console/pageerror 干净：${errors.join(' | ')}`).toEqual([]);
  });

  test(`dsf: desktop contacts search field (${themeName})`, async ({ page }) => {
    await page.emulateMedia({ colorScheme: theme });
    const errors = watchErrors(page);
    await bootApp(page);
    await page.locator('#contacts-btn').click();
    await expect(page.locator('#panel-contacts.active')).toBeVisible();

    const row = page.locator('#fm-contacts-body .fm-search');
    const input = row.locator('.fm-search-input');
    const btn = row.locator('button.fm-search-btn');

    if (LEGACY) {
      // 改前红口径：输入框 + 提交键在，但无 .sf-field 玻璃壳 / 无清空键。
      await expect(input).toBeVisible();
      await expect(btn).toBeVisible();
      await expect(row.locator('.sf-field')).toHaveCount(0);
      await expect(row.locator('.sf-field-clear')).toHaveCount(0);
      await shot(page, `before-desktop-contacts-${themeName}.png`);
      return;
    }

    // ── 结构：行壳收编 .sf-field.nf-glass（同一 buildFieldShell）+ 提交键 ──
    const shell = row.locator('.sf-field');
    await expect(shell).toHaveClass(/nf-glass/);
    await expect(input).toBeVisible();
    await expect(btn).toBeVisible(); // 提交语义面零改动：提交键仍在
    await expect(shell.locator('svg').first()).toBeVisible();
    await expect(shell.locator('.sf-field-clear')).toBeHidden();

    // 读数：与消息面板同一 .sf-field 档（两端一致性证据）。
    const readings = await probeField(page, '#fm-contacts-body .fm-search .sf-field');
    expect(readings.rect.h, '字段高 = --sfs-height 44（与消息面板同值）').toBe(44);
    expect(readings.radius, '圆角 12px（与消息面板同值）').toBe('12px');
    expect(readings.icon, '图标 16px（与消息面板同值）').toBe('16px');
    bumpReadings(`desktop-contacts.${themeName}`, readings);
    await shot(page, `after-desktop-contacts-${themeName}-idle.png`);

    // ── 提交语义零改动：Enter 提交 → mock 命中 xintongxue 结果卡 ─────────
    await input.fill('xintongxue');
    await expect(shell.locator('.sf-field-clear')).toBeVisible();
    await shot(page, `after-desktop-contacts-${themeName}-typed.png`);
    await input.press('Enter');
    await expect(page.locator('#fm-contacts-body .fm-search-block').first(),
      '提交搜索出结果卡（既有六态卡语义不变）').toContainText('新同学', { timeout: 8000 });
    await shot(page, `after-desktop-contacts-${themeName}-result.png`);

    // ── Esc 清场（空串腿 = 既有清场流水）+ 清空键同语义 ──────────────────
    await input.focus();
    await page.keyboard.press('Escape');
    await expect(input).toHaveValue('');
    await expect(page.locator('#fm-contacts-body .fm-search-block').first())
      .not.toContainText('新同学');

    expect(errors, `console/pageerror 干净：${errors.join(' | ')}`).toEqual([]);
  });
}
});

// ── C：手机对照腿（375×812）——改饮 --sfs-* 令牌为值等价替换，两腿必须同绿 ──
test.describe(() => {
test.use({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: 'dark' });
test('dsf: mobile bar values unchanged (token adoption is value-identical)', async ({ page }) => {
  const errors = watchErrors(page);
  await bootApp(page);
  await dockTo(page, 'messages');
  const bar = page.locator('.mobile-page[data-page="messages"] .mh-search-bar');
  await expect(bar).toBeVisible();
  const readings = await probeField(page, '.mobile-page[data-page="messages"] .mh-search-bar');
  // 值等价判据（mobileSearchFlat.css ①块：字面量 → var(--sfs-*)，计算值不动）。
  expect(readings.rect.h, '手机条高仍 44').toBe(44);
  expect(readings.radius, '手机条圆角仍 12px').toBe('12px');
  expect(readings.padX, '手机条水平内距仍 16px').toEqual(['16px', '16px']);
  await expect(bar).toHaveClass(/nf-glass/);
  bumpReadings('mobile-messages-bar', readings);
  await shot(page, `mobile-messages-bar-${LEGACY ? 'before' : 'after'}.png`);

  // 手机消息页检索仍走同一引擎（本批上抽后行为回归腿）。
  await bar.locator('.mh-search-input').fill(QUERY);
  await expect(page.locator('.mobile-page[data-page="messages"] .mh-sr-row').first())
    .toBeVisible({ timeout: 10000 });
  await shot(page, `mobile-messages-results-${LEGACY ? 'before' : 'after'}.png`);

  await dockTo(page, 'contacts');
  const cbar = page.locator('.mobile-page[data-page="contacts"] .mh-search-bar');
  await expect(cbar).toBeVisible();
  const creadings = await probeField(page, '.mobile-page[data-page="contacts"] .mh-search-bar');
  expect(creadings.rect.h, '联系人条高仍 44').toBe(44);
  expect(creadings.radius, '联系人条圆角仍 12px').toBe('12px');
  bumpReadings('mobile-contacts-bar', creadings);
  await shot(page, `mobile-contacts-bar-${LEGACY ? 'before' : 'after'}.png`);

  expect(errors, `console/pageerror 干净：${errors.join(' | ')}`).toEqual([]);
});
});

// profile-dual-desc.spec.mjs — 件一「好友/群 Profile 双描述 + 描述进检索」机械验收
// （作者原话：「联系人和群是没有 Profile 的，类似于微信，我们应该要有 profile。
//  profile 里，有两个描述，一是用户给自己写的描述，二是我们给好友备注的描述，
//  这样你查询的时候，可以根据描述来做判断。」）
//
// 断言面（D 系列；④ 号机械验收 = 好友侧与群侧各一次「描述命中检索」）：
//   D0  壳：Profile 面 = `.cfg-modal-overlay > .cfg-modal.pf-modal`（既有弹层家族，
//       零新弹层）、两字段行独立（`[data-pf-field]`）、每行一个保存键（`[data-pf-save]`）。
//   D1  好友 Profile：改写「我的备注」与「自我介绍」→ 各自保存 → 回读一致；
//       **改 A 不影响 B**（互不覆盖）。
//   D2  好友侧描述进检索（④-a）：把独特串写进好友 `bio` ⇒ 桌面联系人页搜索框输入该串
//       ⇒ 好友行**仍命中**（且只命中应命中的那条）；输入无关串 ⇒ 该行被过滤掉。
//   D3  群 Profile：改写「我的群备注」与「群介绍」→ 各自保存 → 回读一致；互不覆盖。
//   D4  群侧描述进检索（④-b）：把独特串写进群 `description` 与群 `remark` ⇒ 手机消息页
//       常驻搜索条输入该串 ⇒ 出现「资料命中」组且含该群行（`.mh-sr-row-desc`）。
//   D5  「本地缓存·未同步」标记：bio / 群介绍两个字段行必须显式标注（禁假装已同步），
//       而两个本地真源字段（好友备注 / 我的群备注）没有该标记。
//   D6  i18n 对称：zh/en 的 `profile.*` + `mhsearch.descHits` 键全部双语齐全。
//
// 两段 boot：桌面 1280×800（D0/D1/D2/D3/D5/D6）+ 手机 375×812（D4，走真实 dock 导航）。
// 数据面 = `fm_api_mock=1` + `fm_api_mock_seed`（friendsApi 的 mock 腿与真机同形：
// 好友 bio 落 `friends[].bio`、群 description/remark 落 `groups[]`，写后回读同一份）。
//
// Self-contained: route-intercepted static files + mocked REST/WS, no backend.
// Run: node node_modules/@playwright/test/cli.js test tests/profile-dual-desc.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { dockTo, settlePage } from './_mobile-dock-nav.mjs';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const SID = 'profile-dual-desc-session';

// 独特串（互不为子串 —— 否则「互不覆盖」断言会被子串命中污染）
const REMARK0 = '备注甲-初始';
const BIO0 = '介绍乙-初始';
const REMARK1 = '备注甲-改后';
const BIO1 = '介绍乙-改后';
const GD0 = '群介绍丙-初始';
const GR0 = '群备注丁-初始';
const GD1 = '群介绍丙-改后';
const GR1 = '群备注丁-改后';
/** 检索用独特串（只在 bio / description 里出现，正文与显示名都不含）。 */
const BIO_NEEDLE = '量子花园';
const GROUP_NEEDLE = '陨石坑';

/** 好友（wire 内部形态；bio 走 `friends[].bio`，mock 与真机加性键同形）。 */
const F_MAIN = {
  userId: 'u-f1', neblinkId: 'buddy1', name: '老友', avatarUrl: '',
  remark: REMARK0, bio: BIO0, since: 1762000000000,
};
const F_OTHER = {
  userId: 'u-f2', neblinkId: 'buddy2', name: '次友', avatarUrl: '',
  remark: null, bio: null, since: 1762000000000,
};

/** 群（契约 GroupSummary 形态：groupId / title / description / remark）。 */
const G_MAIN = {
  groupId: 'g-1', title: '项目群', role: 'owner', memberCount: 2,
  lastMessage: { id: 101, senderId: 'u-f1', kind: 'text', body: '今天开会', createdAt: 1762000000000 },
  unreadCount: 0, lastMessageId: 101, createdAt: 1762000000000,
  description: GD0, remark: GR0,
};

function seed({ mobile = false } = {}) {
  const NOW = Date.now();
  return {
    self: { userId: 'me', neblinkId: 'me@example.com', name: '我自己', avatarUrl: '', bio: '' },
    users: [],
    friends: [F_MAIN, F_OTHER],
    incoming: [],
    outgoing: [],
    conversations: [
      { conversationId: 'c-f1', friend: { ...F_MAIN }, lastMessage: null, unreadCount: 0 },
      { conversationId: 'c-f2', friend: { ...F_OTHER }, lastMessage: null, unreadCount: 0 },
    ],
    messages: { 'c-f1': [], 'c-f2': [], 'g-1': [] },
    groups: [{ ...G_MAIN }],
    groupInvites: [],
    groupMembers: {
      'g-1': [
        { userId: 'me', username: null, display_name: '我自己', avatar: null, role: 'owner', joinedAt: 0 },
        { userId: 'u-f1', username: null, display_name: '老友', avatar: null, role: 'member', joinedAt: 0 },
      ],
    },
    _now: NOW,
    _mobile: mobile,
  };
}

/** 静态路由 + mock seed + WS 夹具（`mobile-pinned-search.spec.mjs` 同源）。 */
async function bootApp(page, { mobile = false } = {}) {
  const s = seed({ mobile });
  await page.addInitScript((seedJson) => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('neblink_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
    localStorage.setItem('fm_api_mock', '1');
    // `_now` / `_mobile` 只为序列化占位（mock store 不读它们）
    localStorage.setItem('fm_api_mock_seed', JSON.stringify(seedJson));
    localStorage.setItem('fm_api_mock_delay', '0');
  }, s);

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/agents/Nebula/agent.json') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }),
        });
      }
      if (p === '/api/neblink/status') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({
            loggedIn: true,
            device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '', email: 'me@example.com', displayName: '我自己' },
            peers: [],
          }),
        });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(normalize(WEB))) return route.fulfill({ status: 403, body: 'forbidden' });
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
      type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID,
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

  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const m = await import('/js/state.js');
    const st = m.default;
    return st.activeSessionId === rootSid && st.ws && st.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForTimeout(300);
}

function watchErrors(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
  return errors;
}

/** 打开好友 Profile（= 联系人行右键 → 「查看资料」菜单项）。 */
async function openFriendProfileViaMenu(page, rowName) {
  const row = page.locator(`.fm-friend-row:has(.fm-row-name:text-is("${rowName}"))`).first();
  await row.click({ button: 'right' });
  await page.waitForSelector('.fm-context-menu', { timeout: 5000 });
  await page.locator('.fm-context-menu button').filter({ hasText: '查看资料' }).first().click();
  await page.waitForSelector('#profile-face-overlay:not([hidden]) .pf-modal', { timeout: 5000 });
}

/** 打开群 Profile（= 群窗 → 群设置抽屉 → 「查看资料」）。
 *  幂等：群窗/抽屉已开着就直接往前走（重开 Profile 面的用例不必关窗再走一遍列表）。 */
async function openGroupProfileViaDrawer(page) {
  if ((await page.locator('#fm-chat-overlay').count()) === 0) {
    await page.locator('#fm-conversations .fm-conv-row[data-conversation-id="g-1"]').click();
    await page.waitForSelector('#fm-chat-overlay .fm-modal-header', { timeout: 8000 });
  }
  if ((await page.locator('#fm-chat-overlay .fm-group-settings').count()) === 0) {
    await page.locator('#fm-chat-overlay .fm-gs-open').click();
    await page.waitForSelector('#fm-chat-overlay .fm-group-settings', { timeout: 5000 });
  }
  await page.locator('.fm-group-settings .fm-gs-profile').click();
  await page.waitForSelector('#profile-face-overlay:not([hidden]) .pf-modal', { timeout: 5000 });
}

/** 写某个字段并保存，等回读完成（toast / 输入框值回填）。 */
async function setField(page, field, value) {
  const input = page.locator(`#profile-face-overlay [data-pf-input="${field}"]`);
  await input.fill(value);
  await page.locator(`#profile-face-overlay [data-pf-save="${field}"]`).click();
  // 保存 = PUT + 回读（mock 0ms 延迟）⇒ 等输入框值稳定到期望值
  await expect(input).toHaveValue(value, { timeout: 5000 });
}

/** Profile 面当前两字段的输入框值（回读读数）。 */
async function readFields(page) {
  return page.evaluate(() => {
    const out = {};
    for (const el of document.querySelectorAll('#profile-face-overlay [data-pf-input]')) {
      out[/** @type {HTMLElement} */ (el).dataset.pfInput] = /** @type {HTMLInputElement} */ (el).value;
    }
    return out;
  });
}

async function closeProfile(page) {
  await page.locator('#profile-face-close').click();
  await expect(page.locator('#profile-face-overlay')).toBeHidden();
}

/** 桌面联系人页：走**真实提交腿**（`contacts.submitSearch`，与搜索键同一条流水）后
 *  读出当前好友行名集合。`searchQ` 是渲染输入 ⇒ 只 fill 不提交不会触发重渲。 */
async function contactRowsFor(page, q) {
  await page.evaluate(async (query) => {
    const m = await import('/js/contacts.js');
    await m.submitSearch(query, { throttle: false });
  }, q);
  await page.waitForTimeout(120);
  return page.$$eval('#fm-contacts-body .fm-friend-row .fm-row-name', (els) => els.map((e) => e.textContent || ''));
}

test.describe('好友/群 Profile 双描述 + 描述进检索', () => {
  test.use({ viewport: { width: 1280, height: 800 } });

  test('D0/D1/D2/D5 好友 Profile：两字段独立可编辑 + bio 进检索 + 缓存标注', async ({ page }) => {
    const errors = watchErrors(page);
    await bootApp(page);
    await page.locator('#contacts-btn').click();
    await page.waitForSelector('#fm-contacts-body .fm-friend-row', { timeout: 15000 });

    // ── D0 壳形态 ──
    await openFriendProfileViaMenu(page, REMARK0);
    const shape = await page.evaluate(() => {
      const ov = document.querySelector('#profile-face-overlay');
      const modal = ov?.querySelector('.pf-modal');
      return {
        overlayClass: ov?.className || '',
        isCfgFamily: !!ov?.classList.contains('cfg-modal-overlay') && !!modal?.classList.contains('cfg-modal'),
        fields: [...document.querySelectorAll('#profile-face-overlay [data-pf-field]')].map((e) => e.dataset.pfField),
        saves: [...document.querySelectorAll('#profile-face-overlay [data-pf-save]')].map((e) => e.dataset.pfSave),
        labels: [...document.querySelectorAll('#profile-face-overlay .pf-label')].map((e) => e.textContent),
      };
    });
    expect(shape.isCfgFamily, 'Profile 面 = 既有弹层家族（cfg-modal-overlay + cfg-modal，零新弹层）').toBe(true);
    expect(shape.fields, '好友 Profile = 两个独立描述字段行').toEqual(['remark', 'bio']);
    expect(shape.saves, '每字段一个保存键（两次独立写，禁合并提交）').toEqual(['remark', 'bio']);
    expect(shape.labels).toEqual(['我的备注', '自我介绍']);

    // ── D1 回读一致 + 互不覆盖 ──
    const initial = await readFields(page);
    expect(initial, '初始值 = wire 带下来的两个描述（remark / bio 各读各的键）')
      .toEqual({ remark: REMARK0, bio: BIO0 });

    await setField(page, 'remark', REMARK1);
    const afterRemark = await readFields(page);
    expect(afterRemark.remark, '改备注：保存后回读一致').toBe(REMARK1);
    expect(afterRemark.bio, '改备注不得覆盖自我介绍（两字段独立）').toBe(BIO0);

    await setField(page, 'bio', BIO1);
    const afterBio = await readFields(page);
    expect(afterBio.bio, '改自我介绍：保存后回读一致').toBe(BIO1);
    expect(afterBio.remark, '改自我介绍不得覆盖备注（两字段独立）').toBe(REMARK1);

    // 关面重开 ⇒ 仍是落库值（不是「只活在输入框里」）
    await closeProfile(page);
    await page.waitForTimeout(400); // fm-friends-changed ⇒ contacts.refresh()
    await openFriendProfileViaMenu(page, REMARK1);
    const reopened = await readFields(page);
    expect(reopened, '重开后回读 = 落库值（关面不丢）').toEqual({ remark: REMARK1, bio: BIO1 });

    // ── D5 缓存标注：bio 标「本地缓存·未同步」，remark 不标 ──
    const hints = await page.evaluate(() => ({
      bio: [...document.querySelectorAll('#profile-face-overlay [data-pf-cache-hint="bio"]')].map((e) => e.textContent),
      remark: [...document.querySelectorAll('#profile-face-overlay [data-pf-cache-hint="remark"]')].map((e) => e.textContent),
    }));
    expect(hints.bio, '自我介绍真源在上游、上游无接口 ⇒ 必须标「本地缓存·未同步」').toEqual(['本地缓存·未同步']);
    expect(hints.remark, '我的备注是本地真源 ⇒ 不得标「未同步」').toEqual([]);

    await closeProfile(page);

    // ── D2 好友侧描述进检索（④-a）──
    // 换成一个只在 bio 里出现的独特串（正文/显示名/NL 号都不含）
    await openFriendProfileViaMenu(page, REMARK1);
    await setField(page, 'bio', BIO_NEEDLE);
    await closeProfile(page);
    await page.waitForTimeout(500); // fm-friends-changed ⇒ contacts 重取（mock 面同源回读）

    const hits = await contactRowsFor(page, BIO_NEEDLE);
    expect(hits, '把串写进好友「自我介绍」后，联系人页搜索该串 ⇒ 该好友命中（④-a 好友侧）')
      .toEqual([REMARK1]);

    const miss = await contactRowsFor(page, '不存在的串zzz');
    expect(miss, '无关串 ⇒ 全部条目被过滤（过滤真的在生效，不是恒显示）').toEqual([]);
    expect(
      await page.locator('#fm-contacts-body [data-fm-desc-filter-empty="1"]').count(),
      '无命中给可读空态（禁静默空白）',
    ).toBe(1);

    // 备注字段同样可命中（remark 与 bio 都在匹配面内，任一中即命中）
    const byRemark = await contactRowsFor(page, REMARK1);
    expect(byRemark, '「我的备注」同样是匹配面（两个描述字段独立可检索）').toEqual([REMARK1]);
    expect(errors).toEqual([]);
  });

  test('D3/D5 群 Profile：群介绍 + 我的群备注独立可编辑 + 群介绍带缓存标注', async ({ page }) => {
    const errors = watchErrors(page);
    await bootApp(page);
    await page.locator('#messages-btn').click();
    await page.waitForSelector('#fm-conversations .fm-conv-row[data-conversation-id="g-1"]', { timeout: 15000 });

    await openGroupProfileViaDrawer(page);
    const shape = await page.evaluate(() => ({
      kind: document.querySelector('#profile-face-overlay .pf-modal')?.dataset.pfKind,
      fields: [...document.querySelectorAll('#profile-face-overlay [data-pf-field]')].map((e) => e.dataset.pfField),
      labels: [...document.querySelectorAll('#profile-face-overlay .pf-label')].map((e) => e.textContent),
      hints: [...document.querySelectorAll('#profile-face-overlay [data-pf-cache-hint]')].map((e) => e.dataset.pfCacheHint),
    }));
    expect(shape.kind, '同一面按主体分档（禁第二套 Profile 面）').toBe('group');
    expect(shape.fields, '群 Profile = 两个描述字段（我的群备注 / 群介绍）').toEqual(['remark', 'description']);
    expect(shape.labels).toEqual(['我的群备注', '群介绍']);
    expect(shape.hints, '只有群介绍（上游真源）带「本地缓存·未同步」，我的群备注不带')
      .toEqual(['description']);

    const initial = await readFields(page);
    expect(initial, '初始值 = 群行带下来的 description / remark（两键各读各的）')
      .toEqual({ remark: GR0, description: GD0 });

    await setField(page, 'remark', GR1);
    const afterRemark = await readFields(page);
    expect(afterRemark.remark, '改我的群备注：回读一致').toBe(GR1);
    expect(afterRemark.description, '改我的群备注不得覆盖群介绍').toBe(GD0);

    await setField(page, 'description', GD1);
    const afterDesc = await readFields(page);
    expect(afterDesc.description, '改群介绍：回读一致').toBe(GD1);
    expect(afterDesc.remark, '改群介绍不得覆盖我的群备注').toBe(GR1);

    await closeProfile(page);
    await page.waitForTimeout(400);
    await openGroupProfileViaDrawer(page);
    expect(await readFields(page), '重开后回读 = 落库值').toEqual({ remark: GR1, description: GD1 });
    await closeProfile(page);
    expect(errors).toEqual([]);
  });
});

test.describe('手机层：群描述进检索（④-b）', () => {
  test.use({ viewport: { width: 375, height: 812 }, hasTouch: true });

  test('D4 群侧描述进检索：群介绍 / 我的群备注命中手机常驻搜索条的「资料命中」组', async ({ page }) => {
    const errors = watchErrors(page);
    await bootApp(page, { mobile: true });
    await expect.poll(async () => page.evaluate(() => document.body.classList.contains('mobile-layer')), { timeout: 20000 })
      .toBe(true);

    // 群 Profile 里把两个描述都改成独特串（手机层列表行被页面收编，用同一入口函数
    // `openConversation` 开会话窗 —— 与行点击同一条通路，禁第二套开窗实现）
    await dockTo(page, 'messages');
    await page.evaluate(async () => {
      const m = await import('/js/messages.js');
      await m.openConversation('g-1', null);
    });
    // 🔴 手机层：会话窗体被 navController **re-parent 进「会话」详情页**
    // （`.fm-modal` 已不在 `#fm-chat-overlay` 内）⇒ 窗体件一律按页面体定位。
    const convPage = '#mobile-pages .mobile-page[data-page="conversation"]';
    await page.waitForSelector(`${convPage} .fm-modal-header`, { timeout: 10000 });
    await page.locator(`${convPage} .fm-gs-open`).click();
    await page.waitForSelector(`${convPage} .fm-group-settings`, { timeout: 5000 });
    await page.locator(`${convPage} .fm-group-settings .fm-gs-profile`).click();
    await page.waitForSelector('#profile-face-overlay:not([hidden]) .pf-modal', { timeout: 5000 });
    await setField(page, 'description', GROUP_NEEDLE);
    await setField(page, 'remark', '群备注-独特串');
    await closeProfile(page);
    // 关群窗，回到消息页（常驻搜索条所在页）
    await page.locator(`${convPage} .fm-modal-close`).first().dispatchEvent('click');
    await expect(page.locator(`${convPage} .fm-modal`)).toHaveCount(0, { timeout: 8000 });
    await settlePage(page, 'messages');

    // ④-b 群介绍 → 资料命中
    const input = page.locator('.mobile-page[data-page="messages"] .mh-search-input');
    await input.fill(GROUP_NEEDLE);
    const descRow = page.locator('.mh-sr-row-desc').first();
    await expect(descRow, '群介绍命中 ⇒ 常驻条出现「资料命中」行（会话级命中，不混进正文结果）')
      .toBeVisible({ timeout: 8000 });
    await expect(page.locator('.mh-sr-group-desc'), '「资料命中」独立成组（与消息正文结果不互相污染）')
      .toHaveCount(1);
    await expect(descRow).toContainText('项目群');

    // ④-b 我的群备注 → 同一条匹配面
    await input.fill('群备注-独特串');
    await expect(page.locator('.mh-sr-row-desc').first(), '我的群备注同样命中')
      .toBeVisible({ timeout: 8000 });
    await expect(page.locator('.mh-sr-row-desc').first()).toContainText('项目群');

    // 反向：无关串 ⇒ 零资料命中（否定面，防「恒命中」假绿）
    await input.fill('绝无此串zzz');
    await expect(page.locator('.mh-sr-row-desc')).toHaveCount(0, { timeout: 8000 });

    expect(errors).toEqual([]);
  });
});

test.describe('i18n 对称（D6）', () => {
  test.use({ viewport: { width: 1280, height: 800 } });

  test('zh/en 的 profile.* 与 mhsearch.descHits 键双语齐全', async () => {
    const read = (f) => readFileSync(join(WEB, 'js', 'locales', f), 'utf8');
    const zh = read('zh-CN.js');
    const en = read('en.js');
    const keys = [
      'profile.open', 'profile.friendTitle', 'profile.groupTitle', 'profile.close',
      'profile.save', 'profile.saved', 'profile.friendRemarkLabel', 'profile.friendRemarkPlaceholder',
      'profile.friendBioLabel', 'profile.friendBioPlaceholder', 'profile.groupRemarkLabel',
      'profile.groupRemarkPlaceholder', 'profile.groupDescriptionLabel',
      'profile.groupDescriptionPlaceholder', 'profile.localCacheHint', 'profile.friendNote',
      'profile.groupNote', 'mhsearch.descHits',
    ];
    for (const k of keys) {
      expect(zh, `zh 缺键 ${k}`).toContain(`'${k}':`);
      expect(en, `en 缺键 ${k}`).toContain(`'${k}':`);
    }
    // 键在两侧都只出现一次（禁重复定义 ⇒ 后者静默覆盖前者）
    for (const k of keys) {
      expect(zh.split(`'${k}':`).length - 1, `zh 重复键 ${k}`).toBe(1);
      expect(en.split(`'${k}':`).length - 1, `en 重复键 ${k}`).toBe(1);
    }
  });
});

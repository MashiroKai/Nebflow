// friend-remark-ui.spec.mjs — ⑦ 好友备注 · 前端面验收（作者裁定 2026-09-12，
// 方案 §4.3(c) / §6.6 ⑦-D1..D3；冻结契约 §1/§2/§3/§4）。
//
// 断言面：
//   R1  入口唯一且可用：好友行右键菜单含「设置备注」（唯一菜单项）——并经静态扫描
//       证明前端全树只有一处入口（`contacts.menuSetRemark` / `.fm-remark-edit`
//       只出现在 contacts.js）
//   R2  行内编辑形态：点菜单项 → 行内 `.fm-remark-edit` 输入框（聚焦、maxlength=64、
//       placeholder/title 走 i18n、字体与行名同档 13px）
//   R3  PUT 形状逐字一致：`PUT /api/friends/<uid>/remark`，body `{"remark":"..."}`
//       （唯一键、已 trim）⇒ 200 `{"ok":true}`
//   R4  三处显示优先级「备注 > 显示名」同步：contacts 好友行 / messages 会话列表行 /
//       messages 会话窗头标题面
//   R5  `remark = null` ⇒ 回落显示名且**不渲染 null 字面**
//   R6  空格清除：提交 `{"remark":""}` ⇒ 三处回落显示名
//   R7  ≤64：maxlength 属性 + 真键盘输入 70 字符被截断到 64 + 程序化 70 字符提交前夹紧
//   R8  Esc / blur 取消不写（零 PUT、显示不变、编辑器关闭）
//   R9  PUT 403（未认证/非好友）失败面：显示不误改、零 page error
//   R10 i18n 双份 parity（zh/en 键数相等 + 三个备注键双语齐全）
//
// 直连模式（不设 `fm_api_mock`）：好友列表 / 会话走契约原形 wire 路由拦截，PUT 被
// 捕获后逐字断言形状。无静态服务器、无端口；跑完 browser.close()（无残留进程）。
// Run: node tests/friend-remark-ui.spec.mjs
import { chromium } from 'playwright-core';
import { readFileSync, readdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

const UID = 'u-f';
const CID = 'c-f';
const UID_NULL = 'u-n';
const CID_NULL = 'c-n';
const REMARK0 = '备注甲';
const NAME0 = '老友';

// 契约原形 wire（snake_case；remark 键恒在，null = 无备注）
const WIRE_FRIEND = { userId: UID, username: 'buddy7', display_name: NAME0, avatar: '', remark: REMARK0 };
const WIRE_FRIEND_NULL = { userId: UID_NULL, username: 'buddy8', display_name: '无备注乙', avatar: '', remark: null };

let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));

const browser = await chromium.launch();

/**
 * boot — 直连模式页面（契约 wire 路由拦截 + PUT 捕获）。
 * @param {{putStatus?: number}} opts
 */
async function boot({ putStatus = 200 } = {}) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.removeItem('fm_api_mock'); // 直连
  });
  const page = await ctx.newPage();
  const pageErrors = [];
  page.on('pageerror', e => pageErrors.push(e.message));
  const puts = [];

  // catch-all first — 后注册的 route 优先（Playwright 逆序匹配）
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(normalize(WEB))) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await page.route('**/api/neblink/status', r => r.fulfill({
    json: { loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '' }, peers: [] },
  }));
  await page.route('**/api/friends', r => r.fulfill({
    json: { friends: [{ ...WIRE_FRIEND, since: new Date().toISOString() }, { ...WIRE_FRIEND_NULL, since: new Date().toISOString() }], incoming: [], outgoing: [] },
  }));
  await page.route('**/api/conversations', r => r.fulfill({
    json: [
      { conversationId: CID, friend: WIRE_FRIEND, lastMessage: null, unreadCount: 0 },
      { conversationId: CID_NULL, friend: WIRE_FRIEND_NULL, lastMessage: null, unreadCount: 0 },
    ],
  }));
  await page.route('**/api/conversations/*/messages*', r => r.fulfill({ json: [] }));
  // PUT 备注：捕获（方法 / 路径 / body 逐字）+ 契约响应
  await page.route('**/api/friends/*/remark', async (route) => {
    const req = route.request();
    let body = null;
    try { body = req.postDataJSON(); } catch { /* non-JSON */ }
    puts.push({ method: req.method(), path: new URL(req.url()).pathname, body, raw: req.postData() });
    await route.fulfill({ status: putStatus, json: putStatus === 200 ? { ok: true } : { error: 'forbidden' } });
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage(() => {});
    ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 's1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 's1', folders: [] }));
  });
  await page.goto('http://localhost:1/index.html');
  await page.waitForSelector('#contacts-btn', { state: 'attached', timeout: 15000 });
  await sleep(800);
  return { ctx, page, pageErrors, puts };
}

/** 好友行定位（按行名文本）。 */
const friendRow = (name) => `.fm-friend-row:has(.fm-row-name:text-is("${name}"))`;

/** 打开备注行内编辑器（右键 → 菜单项）。
 *  `dispatch=true`：DOM 直派 contextmenu/click（会话窗开着时 overlay 覆盖整屏，
 *  Playwright 真点击会被它拦截；派发的事件与真实右键同形，仅绕过命中测试）。 */
async function openRemarkEditor(page, name, { dispatch = false } = {}) {
  const rowSel = friendRow(name);
  if (!(await page.locator('#panel-contacts.active').count())) {
    await page.click('#contacts-btn');
    await sleep(500);
  }
  if (dispatch) {
    await page.locator(rowSel).first().dispatchEvent('contextmenu', { clientX: 240, clientY: 320, bubbles: true });
  } else {
    await page.locator(rowSel).first().click({ button: 'right' });
  }
  await page.waitForSelector('.fm-context-menu', { timeout: 5000 });
  const item = page.locator('.fm-context-menu button:has-text("设置备注")');
  if (dispatch) await item.dispatchEvent('click');
  else await item.click();
  await page.waitForSelector('.fm-remark-edit', { timeout: 5000 });
  return page.locator('.fm-remark-edit').first();
}

/** 写编辑器值（overlay 场景下不经真点击；焦点仍落在输入框上）。 */
async function setEditorValue(page, value) {
  await page.evaluate((v) => {
    const el = /** @type {HTMLInputElement} */ (document.querySelector('.fm-remark-edit'));
    el.focus();
    el.value = v;
  }, value);
}

/** 三处显示读数（contacts 好友行 / 会话列表行 / 会话窗头）。 */
async function readThreePlaces(page) {
  return page.evaluate(([uid, name]) => {
    const contactsRow = document.querySelector(`.fm-friend-row:has(.fm-row-name)`);
    const all = [...document.querySelectorAll('.fm-friend-row')].map(r => ({ name: r.querySelector('.fm-row-name')?.textContent || '', sub: r.querySelector('.fm-row-sub')?.textContent || '' }));
    const convRow = [...document.querySelectorAll('#fm-conversations .fm-conv-row')].map(r => r.querySelector('.fm-row-name')?.textContent || '');
    return {
      contacts: all,
      convs: convRow,
      header: document.querySelector('.fm-modal-name')?.textContent ?? null,
      headerFor: name,
      uid,
    };
  }, [UID, NAME0]);
}

/** 三处显示文本拼串（「不渲染 null 字面」断言的判定对象 = 渲染面，不含结构里的
 *  `.fm-modal-name` 缺席 null 等非渲染字段）。 */
function shownText(t) {
  return [...t.contacts.map(r => `${r.name}|${r.sub}`), ...t.convs, ...(t.header == null ? [] : [t.header])].join(' ');
}

try {
  // ══ 主流程（PUT 200）═══════════════════════════════════════════
  const main = await boot();
  const { page, pageErrors, puts } = main;
  await page.click('#contacts-btn');
  await sleep(600);

  // ── R4 前置：wire 带备注 ⇒ 三处优先级生效 ──
  const three0 = await readThreePlaces(page);
  ok('R4-1 contacts 好友行：备注 > 显示名（wire remark 生效）',
    three0.contacts.some(r => r.name === REMARK0), JSON.stringify(three0.contacts));
  ok('R4-2 次行仍是 username（NL 号显示面不变）',
    three0.contacts.some(r => r.sub === 'buddy7'), JSON.stringify(three0.contacts.map(r => r.sub)));

  // ── R5 remark = null ⇒ 回落显示名、无 null 字面 ──
  ok('R5 remark=null ⇒ 显示名回落且无 null 字面',
    three0.contacts.some(r => r.name === '无备注乙') && !JSON.stringify(three0.contacts).includes('null'),
    JSON.stringify(three0.contacts));

  // 会话列表行（第二处；面板未激活时 DOM 已在，列表在 init/boot 即渲染）
  await page.click('#messages-btn');
  await sleep(800);
  const three1 = await readThreePlaces(page);
  ok('R4-3 messages 会话列表行：备注 > 显示名', three1.convs.includes(REMARK0), JSON.stringify(three1.convs));
  ok('R5-2 会话列表行：remark=null ⇒ 显示名（无 null 字面）',
    three1.convs.includes('无备注乙') && !three1.convs.some(v => v.includes('null')), JSON.stringify(three1.convs));

  // 会话窗头（第三处）
  await page.click(`#fm-conversations .fm-conv-row[data-conversation-id="${CID}"]`);
  await page.waitForSelector('.fm-modal-header', { timeout: 10000 });
  await sleep(600);
  const head0 = await page.evaluate(() => document.querySelector('.fm-modal-name')?.textContent ?? null);
  ok('R4-4 messages 会话窗头标题面：备注 > 显示名', head0 === REMARK0, `header=${JSON.stringify(head0)}`);
  await page.keyboard.press('Escape'); // 关窗，回到常规交互面
  await sleep(400);

  // ── R1 入口唯一：右键菜单里有「设置备注」，且只有一项 ──
  await page.click('#contacts-btn');
  await sleep(500);
  await page.locator(friendRow(REMARK0)).first().click({ button: 'right' });
  await page.waitForSelector('.fm-context-menu', { timeout: 5000 });
  const menuLabels = await page.$$eval('.fm-context-menu button', bs => bs.map(b => b.textContent.trim()));
  ok('R1-1 好友行右键菜单含「设置备注」（唯一一处入口）',
    menuLabels.filter(l => l === '设置备注').length === 1, JSON.stringify(menuLabels));
  ok('R1-2 对照：既有菜单项仍在（删除/拉黑）',
    menuLabels.includes('删除好友') && menuLabels.includes('加入黑名单'), JSON.stringify(menuLabels));
  ok('R1-3 会话窗头无第二入口（窗头内无备注编辑件）',
    await page.locator('.fm-modal-header .fm-remark-edit, .fm-modal-header [data-remark]').count() === 0);
  await page.keyboard.press('Escape'); // 收起右键菜单
  await sleep(200);

  // ── R4-5/R4-6 活体三处同步（窗从 contacts 行开 ⇒ contacts 面板保持激活，
  //    行内编辑器可见可交互；窗开着时提交/清除 ⇒ 窗头标题面就地重打）──
  {
    await page.locator(friendRow(REMARK0)).first().click();
    await page.waitForSelector('.fm-modal-header', { timeout: 10000 });
    await sleep(500);
    await openRemarkEditor(page, REMARK0, { dispatch: true });
    await setEditorValue(page, '实时备注');
    await page.keyboard.press('Enter');
    await sleep(600);
    const live = await readThreePlaces(page);
    ok('R4-5 会话窗开着时提交 ⇒ 三处同步更新（含窗头就地重打）',
      live.contacts.some(r => r.name === '实时备注') && live.convs.includes('实时备注') && live.header === '实时备注',
      JSON.stringify({ contacts: live.contacts.map(r => r.name), convs: live.convs, header: live.header }));
    // 清除 ⇒ 三处同时回落显示名（窗头亦就地回退）
    await openRemarkEditor(page, '实时备注', { dispatch: true });
    await setEditorValue(page, '   ');
    await page.keyboard.press('Enter');
    await sleep(600);
    const cleared = await readThreePlaces(page);
    ok('R4-6 清除后三处回落显示名（含窗头），无 null 字面',
      cleared.contacts.some(r => r.name === NAME0) && cleared.convs.includes(NAME0) && cleared.header === NAME0
        && !shownText(cleared).includes('null'),
      JSON.stringify({ contacts: cleared.contacts.map(r => r.name), convs: cleared.convs, header: cleared.header }));
  }
  await page.keyboard.press('Escape'); // 关窗（既有 escClose）
  await sleep(400);
  ok('R4-7 会话窗已关闭（后续面可常规交互）', await page.locator('#fm-chat-overlay').count() === 0);

  // ── R2 行内编辑形态 ──
  await openRemarkEditor(page, NAME0);
  const shape = await page.evaluate(() => {
    const el = /** @type {HTMLInputElement} */ (document.querySelector('.fm-remark-edit'));
    const cs = getComputedStyle(el);
    const row = el.closest('.fm-friend-row');
    return {
      tag: el.tagName, value: el.value, maxLength: el.maxLength, focused: document.activeElement === el,
      placeholder: el.placeholder, title: el.title, aria: el.getAttribute('aria-label'),
      fontSize: cs.fontSize, lineHeight: cs.lineHeight, borderWidth: cs.borderTopWidth,
      rowNameHidden: !!(row && row.querySelector('.fm-row-name') && row.querySelector('.fm-row-name').hidden),
      rowHeight: row ? Math.round(row.getBoundingClientRect().height) : null,
    };
  });
  ok('R2-1 行内编辑：input 就位、带当前备注（清除后为空）、聚焦、行名隐藏',
    shape.tag === 'INPUT' && shape.value === '' && shape.focused === true && shape.rowNameHidden === true,
    JSON.stringify(shape));
  ok('R2-2 maxlength=64 + i18n 文案（placeholder/title/aria 非空且走 t()）',
    shape.maxLength === 64 && !!shape.placeholder && !!shape.title && shape.aria === '设置备注',
    JSON.stringify({ maxLength: shape.maxLength, placeholder: shape.placeholder, title: shape.title, aria: shape.aria }));
  ok('R2-3 视觉同档：13px 字号 + 1px 边框（复用既有行内编辑形态）',
    shape.fontSize === '13px' && parseFloat(shape.borderWidth) === 1, JSON.stringify({ fontSize: shape.fontSize, borderWidth: shape.borderWidth, lineHeight: shape.lineHeight }));

  // ── R3 提交（PUT 形状逐字；起点 idx 记录避免活体阶段 PUT 占位）──
  const idxSubmit = puts.length;
  await page.fill('.fm-remark-edit', '新备注');
  await page.keyboard.press('Enter');
  await sleep(500);
  const put1 = puts[idxSubmit];
  ok('R3-1 PUT 形状：方法/路径/body 逐字（唯一键 remark）',
    !!put1 && put1.method === 'PUT' && put1.path === `/api/friends/${UID}/remark`
      && put1.raw === '{"remark":"新备注"}' && Object.keys(put1.body || {}).length === 1,
    JSON.stringify(put1));
  ok('R3-2 提交后编辑器关闭、行内显示新备注', await page.locator('.fm-remark-edit').count() === 0
    && await page.locator(friendRow('新备注')).count() === 1);
  ok('R3-3 提交 Enter 不穿透到行激活（未误开会话窗）', await page.locator('#fm-chat-overlay').count() === 0);
  const three2 = await readThreePlaces(page);
  ok('R3-4 会话列表行同步（第二处）', three2.convs.includes('新备注'), JSON.stringify(three2.convs));

  // ── R6 空格清除（提交形状 + 两处回落；窗头回落见 R4-6）──
  await openRemarkEditor(page, '新备注');
  await page.fill('.fm-remark-edit', '   ');
  await page.keyboard.press('Enter');
  await sleep(500);
  const put2 = puts[puts.length - 1];
  ok('R6-1 空格提交 ⇒ PUT body {"remark":""}（trim 后空串 = 清除）',
    !!put2 && put2.raw === '{"remark":""}', JSON.stringify(put2));
  const three3 = await readThreePlaces(page);
  ok('R6-2 清除后 contacts 行 / 会话列表行回落显示名（无 null 字面）',
    three3.contacts.some(r => r.name === NAME0) && three3.convs.includes(NAME0)
      && !shownText(three3).includes('null'),
    JSON.stringify({ contacts: three3.contacts.map(r => r.name), convs: three3.convs }));

  // ── R7 ≤64 ──
  await openRemarkEditor(page, NAME0);
  const beforeTyping = await page.evaluate(() => /** @type {HTMLInputElement} */ (document.querySelector('.fm-remark-edit')).value);
  await page.fill('.fm-remark-edit', '');
  await page.keyboard.type('x'.repeat(70)); // 真键盘输入 ⇒ maxlength 生效
  const typedLen = await page.evaluate(() => /** @type {HTMLInputElement} */ (document.querySelector('.fm-remark-edit')).value.length);
  await page.keyboard.press('Enter');
  await sleep(500);
  const put3 = puts[puts.length - 1];
  const put3Len = put3 && put3.body ? String(put3.body.remark).length : -1;
  ok('R7-1 maxlength=64 生效（真键盘 70 字符 ⇒ 输入值 64）', beforeTyping === '' && typedLen === 64, `typedLen=${typedLen}`);
  ok('R7-2 PUT 出口 ≤64（提交前夹紧）', put3Len === 64, `putLen=${put3Len} body=${put3 && put3.raw && put3.raw.length}`);
  // 程序化 70 字符（绕过 maxlength）⇒ 提交前夹紧仍成立
  await openRemarkEditor(page, 'x'.repeat(64));
  await page.evaluate(() => {
    const el = /** @type {HTMLInputElement} */ (document.querySelector('.fm-remark-edit'));
    el.value = 'y'.repeat(70);
  });
  await page.keyboard.press('Enter');
  await sleep(500);
  const put4 = puts[puts.length - 1];
  ok('R7-3 程序化超长值 ⇒ 提交前夹紧到 64（契约出口始终成立）',
    !!put4 && String(put4.body.remark).length === 64 && put4.body.remark === 'y'.repeat(64), JSON.stringify(put4 && put4.raw));

  // ── R8 Esc / blur 取消不写 ──
  const putsBeforeCancel = puts.length;
  const editA = await openRemarkEditor(page, 'y'.repeat(64));
  await page.fill('.fm-remark-edit', '取消不写A');
  await page.keyboard.press('Escape');
  await sleep(400);
  const afterEsc = {
    editor: await page.locator('.fm-remark-edit').count(),
    shown: await page.locator(friendRow('y'.repeat(64))).count(),
    puts: puts.length,
  };
  ok('R8-1 Esc 取消：编辑器关闭、显示不变、零 PUT',
    afterEsc.editor === 0 && afterEsc.shown === 1 && afterEsc.puts === putsBeforeCancel,
    JSON.stringify({ ...afterEsc, editA: !!editA }));
  await openRemarkEditor(page, 'y'.repeat(64));
  await page.fill('.fm-remark-edit', '取消不写B');
  await page.evaluate(() => /** @type {HTMLInputElement} */ (document.querySelector('.fm-remark-edit')).blur());
  await sleep(400);
  const afterBlur = {
    editor: await page.locator('.fm-remark-edit').count(),
    shown: await page.locator(friendRow('y'.repeat(64))).count(),
    puts: puts.length,
  };
  ok('R8-2 blur 取消：编辑器关闭、显示不变、零 PUT',
    afterBlur.editor === 0 && afterBlur.shown === 1 && afterBlur.puts === putsBeforeCancel, JSON.stringify(afterBlur));

  // ── R10 i18n parity + 备注键双语齐全 ──
  const i18n = await page.evaluate(async () => {
    const zh = (await import('/js/locales/zh-CN.js')).default;
    const en = (await import('/js/locales/en.js')).default;
    const keys = ['contacts.menuSetRemark', 'contacts.remarkPlaceholder', 'contacts.remarkHint'];
    return {
      zhN: Object.keys(zh).length, enN: Object.keys(en).length,
      zhKeys: keys.map(k => zh[k] || null), enKeys: keys.map(k => en[k] || null),
    };
  });
  ok('R10-1 i18n parity：zh/en 键数相等', i18n.zhN === i18n.enN, `zh=${i18n.zhN} en=${i18n.enN}`);
  ok('R10-2 备注三键双语齐全（菜单项 / placeholder / 清除提示）',
    i18n.zhKeys.every(Boolean) && i18n.enKeys.every(Boolean),
    JSON.stringify({ zh: i18n.zhKeys, en: i18n.enKeys }));

  ok('R1-R10 零 page error', pageErrors.length === 0, pageErrors.join('; '));
  await main.ctx.close();

  // ══ R9 失败面（PUT 403）════════════════════════════════════════
  {
    const { ctx, page: p403, pageErrors: errs403, puts: puts403 } = await boot({ putStatus: 403 });
    await p403.click('#contacts-btn');
    await sleep(600);
    await openRemarkEditor(p403, REMARK0);
    await p403.fill('.fm-remark-edit', '失败备注');
    await p403.keyboard.press('Enter');
    await sleep(600);
    const shown = await p403.locator(friendRow(REMARK0)).count();
    const leaked = await p403.locator(friendRow('失败备注')).count();
    ok('R9 PUT 403 ⇒ 显示不误改（仍为原备注）+ 请求确实发出',
      puts403.length === 1 && puts403[0].method === 'PUT' && shown === 1 && leaked === 0,
      JSON.stringify({ puts: puts403.length, shown, leaked }));
    ok('R9-2 403 零 page error（错误面走既有 toast 链）', errs403.length === 0, errs403.join('; '));
    await ctx.close();
  }

  // ══ 静态：入口唯一（前端全树只有 contacts.js 引这两处）══════════
  {
    const jsDir = join(WEB, 'js');
    const files = [];
    (function walk(d) {
      for (const e of readdirSync(d, { withFileTypes: true })) {
        const p = join(d, e.name);
        if (e.isDirectory()) walk(p);
        else if (e.name.endsWith('.js')) files.push(p);
      }
    })(jsDir);
    const menuHits = [], editHits = [];
    for (const f of files) {
      const rel = f.slice(WEB.length + 1).split('\\').join('/');
      if (rel === 'js/locales/zh-CN.js' || rel === 'js/locales/en.js') continue;
      const src = readFileSync(f, 'utf8');
      if (src.includes('contacts.menuSetRemark')) menuHits.push(rel);
      if (src.includes('fm-remark-edit')) editHits.push(rel);
    }
    ok('R1-4 静态：菜单项入口全树唯一（仅 contacts.js）', JSON.stringify(menuHits) === JSON.stringify(['js/contacts.js']), JSON.stringify(menuHits));
    ok('R1-5 静态：行内编辑器样式/类名全树唯一（仅 contacts.js）', JSON.stringify(editHits) === JSON.stringify(['js/contacts.js']), JSON.stringify(editHits));
  }
} finally {
  await browser.close();
}

console.log(failures === 0 ? '\nALL PASS' : `\n${failures} FAILED`);
process.exit(failures === 0 ? 0 : 1);

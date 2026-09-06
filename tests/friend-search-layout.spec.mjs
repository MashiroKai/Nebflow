// friend-search-layout.spec.mjs — 搜索结果区布局修复验收（0906 批）。
//
// 作者反馈（2026-09-06 00:14）：「搜索好友的时候结果在同一行，把其他内容挤到
// 一边」——根因：.fm-result-card 曾是 .fm-search 行（flex nowrap）的子项，与
// 输入框/按钮同行排布，把输入框挤成 ~22px 细条（改前几何留痕见
// /tmp/nebflow-friendsearch/before/）。修复后结果区 = .fm-search-block 列布局
// 中输入行下方的独立整宽块。
//
// 覆盖：
//   L1  结构：结果卡非输入行 flex 子项；位于输入行下方；整宽（无横向溢出）
//   L2  几何：row.bottom ≤ card.top；input 宽度占比 ≥ 50%；card 宽 ≥ 内容区 90%
//   L3  分层：头像 / 显示名 / @Username / 状态+动作区（.fm-result-foot）
//   L4  三态：加载（.fm-searching + 按钮搜索中）/ 空结果（未找到+提示）/
//       请求失败（搜索失败卡，role=alert——失败 ≠ 未找到，09-06 作者令）
//   L5  六态映射（复用契约对齐口径）：addable/already_friends/outgoing/
//       incoming/blocked/self
//   L6  多宽度：默认 sidebar 236 + 拖拽至最小 180 + 窄窗口 940——不溢出不换行错位
//   L7  en 抽查：already_friends → Already friends
//
// 截图留痕 → $TMPDIR/friendsearch-layout/<phase>/*.png（不进 git）。
// mock/直连双模式；静态服务器随机隔离端口，finally 必关（进程清理纪律）。
// Run: node tests/friend-search-layout.spec.mjs
import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, extname } from 'node:path';
import { tmpdir as osTmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const SHOTS = join(osTmpdir(), 'friendsearch-layout', process.env.TRACE_PHASE || 'after');
mkdirSync(SHOTS, { recursive: true });

let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));

// ── 内置静态服务器（随机隔离端口；finally 关闭）──────────────
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png', '.svg': 'image/svg+xml', '.json': 'application/json', '.woff2': 'font/woff2' };
const server = createServer(async (req, res) => {
  try {
    const path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    const file = join(WEB, path === '/' ? 'index.html' : path);
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch { res.writeHead(404); res.end('not found'); }
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${server.address().port}`;

const SEED = {
  self: { userId: 'me', username: 'selfme', email: 'self@example.com', displayName: '我自己', avatar: '' },
  users: [
    { userId: 'u-lin', username: 'lin', email: 'lin@example.com', displayName: '林小满', avatar: '' },      // addable
    { userId: 'u-f', username: 'buddy7', email: 'b7@example.com', displayName: '老友', avatar: '' },        // already_friends
    { userId: 'u-o', username: 'outgoing1', email: 'o1@example.com', displayName: '在途君', avatar: '' },   // outgoing_pending
    { userId: 'u-i', username: 'incoming1', email: 'i1@example.com', displayName: '来求君', avatar: '' },   // incoming_pending
    { userId: 'u-b', username: 'blocked1', email: 'b1@example.com', displayName: '拉黑君', avatar: '' },    // blocked_by_me
  ],
  friends: [
    { userId: 'u-f', neblinkId: 'buddy7', name: '老友', avatarUrl: '', since: new Date().toISOString() },
    { userId: 'u-b', neblinkId: 'blocked1', name: '拉黑君', avatarUrl: '', since: new Date().toISOString(), blocked: true },
  ],
  incoming: [{ requestId: 'rq-i1', from: { userId: 'u-i', neblinkId: 'incoming1', name: '来求君', avatarUrl: '' }, note: '加我', status: 'pending', createdAt: Math.floor(Date.now() / 1000) }],
  outgoing: [{ requestId: 'rq-o1', to: { userId: 'u-o', neblinkId: 'outgoing1', name: '在途君', avatarUrl: '' }, note: '', status: 'pending', createdAt: Math.floor(Date.now() / 1000) }],
  conversations: [], messages: {},
};

const browser = await chromium.launch();

async function bootPage({ locale = 'zh-CN', delay = '30', mock = true, width = 1440, height = 900 } = {}) {
  const ctx = await browser.newContext({ viewport: { width, height } });
  await ctx.addInitScript(([seedJson, loc, dly, useMock]) => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', loc); localStorage.setItem('neblink_locale', loc);
    if (useMock) localStorage.setItem('fm_api_mock', '1');
    localStorage.setItem('fm_api_mock_seed', seedJson);
    localStorage.setItem('fm_api_mock_delay', dly);
  }, [JSON.stringify(SEED), locale, delay, mock]);
  const page = await ctx.newPage();
  page.on('pageerror', e => console.log('[pageerror]', e.message));
  await page.route('**/api/**', r => r.fulfill({ json: {} }));
  await page.route('**/api/neblink/status', r => r.fulfill({ json: { loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '' }, peers: [] } }));
  await page.routeWebSocket(/\/ws/, ws => {
    ws.onMessage(raw => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#contacts-btn', { state: 'attached', timeout: 10000 });
  await sleep(900);
  await page.click('#contacts-btn');
  await sleep(700);
  return { ctx, page };
}

async function search(page, q) {
  await page.fill('.fm-search-input', q);
  await page.click('.fm-search-btn');
  await sleep(500);
}

async function geometry(page) {
  return page.evaluate(() => {
    const body = document.getElementById('fm-contacts-body');
    const block = body?.querySelector('.fm-search-block');
    const row = block?.querySelector('.fm-search');
    const card = block?.querySelector('.fm-result-card');
    const inp = row?.querySelector('.fm-search-input');
    const btn = row?.querySelector('.fm-search-btn');
    const r = (e) => { if (!e) return null; const b = e.getBoundingClientRect(); return { x: Math.round(b.x), y: Math.round(b.y), w: Math.round(b.width), h: Math.round(b.height), bottom: Math.round(b.bottom), right: Math.round(b.right) }; };
    const cs = (e, p) => e ? getComputedStyle(e)[p] : null;
    return {
      sidebarW: Math.round(document.getElementById('sidebar')?.getBoundingClientRect().width || 0),
      bodyW: r(body)?.w,
      overflowX: body ? body.scrollWidth > body.clientWidth + 1 : null,
      block: r(block), row: r(row), input: r(inp), btn: r(btn), card: r(card),
      rowWrap: cs(row, 'flexWrap'),
      cardInRow: !!card && !!row && row.contains(card),
      cardBelowRow: !!(card && row) && card.getBoundingClientRect().top >= row.getBoundingClientRect().bottom - 1,
      inputShare: inp && row ? inp.getBoundingClientRect().width / row.getBoundingClientRect().width : 0,
      cardFullWidth: !!(card && row) && card.getBoundingClientRect().width >= row.getBoundingClientRect().width * 0.98,
      person: !!card?.querySelector('.fm-result-person'),
      name: card?.querySelector('.fm-row-name')?.textContent || '',
      sub: card?.querySelector('.fm-row-sub')?.textContent || '',
      foot: !!card?.querySelector('.fm-result-foot'),
      cardText: card?.textContent || '',
    };
  });
}

try {
  // ══ L1/L2/L3 命中态结构 + 几何（默认 236 / 最小 180 / 窄窗 940）═══
  for (const [label, opts] of [['default-236', {}], ['min-sidebar-180', { sidebarMin: true }], ['narrow-window-940', { width: 940, height: 720 }]]) {
    const { ctx, page } = await bootPage(opts);
    if (opts.sidebarMin) {
      const handle = page.locator('.col-resizer[data-left="sidebar"]').first();
      const hb = await handle.boundingBox();
      if (hb) {
        await page.mouse.move(hb.x + hb.width / 2, hb.y + hb.height / 2);
        await page.mouse.down();
        await page.mouse.move(hb.x + hb.width / 2 - 62, hb.y + hb.height / 2, { steps: 8 });
        await page.mouse.up();
        await sleep(400);
      }
    }
    await search(page, 'lin');
    const g = await geometry(page);
    ok(`L1[${label}] 结果卡不在输入行内（独立块）`, g.card && !g.cardInRow, `cardParent=${g.cardInRow ? 'fm-search' : 'fm-search-block'}`);
    ok(`L1[${label}] 无横向溢出`, g.overflowX === false, `bodyW=${g.bodyW} scrollW overflow=${g.overflowX}`);
    ok(`L2[${label}] 结果卡位于输入行下方`, g.cardBelowRow, `row.bottom=${g.row?.bottom} card.y=${g.card?.y}`);
    ok(`L2[${label}] 输入框不被挤压（占比≥50%）`, g.inputShare >= 0.5, `inputShare=${Math.round(g.inputShare * 100)}% inputW=${g.input?.w}`);
    ok(`L2[${label}] 结果卡整宽（≥内容区90%）`, g.cardFullWidth, `cardW=${g.card?.w} bodyW=${g.bodyW}`);
    ok(`L3[${label}] 分层：person/name/@sub/foot`, g.person && g.name === '林小满' && g.sub === '@lin' && g.foot, `name=${g.name} sub=${g.sub}`);
    ok(`L3[${label}] 加好友按钮入 foot`, g.cardText.includes('加好友'), g.cardText.slice(0, 40));
    await page.screenshot({ path: join(SHOTS, `found-${label}.png`) });
    writeFileSync(join(SHOTS, `found-${label}.json`), JSON.stringify(g, null, 2));
    await ctx.close();
  }

  // ══ L5 六态映射（默认宽，mock）════════════════════════════
  {
    const { ctx, page } = await bootPage();
    await search(page, 'buddy7');
    let g = await geometry(page);
    let msgBtn = await page.$('.fm-result-card .fm-msg-btn');
    ok('L5a already_friends → 已是好友 + 发消息', g.cardText.includes('已是好友') && g.cardText.includes('发消息') && !!msgBtn, g.cardText.slice(0, 40));

    await sleep(700);
    await search(page, 'outgoing1');
    g = await geometry(page);
    ok('L5b outgoing_pending → 等待对方处理', g.cardText.includes('等待对方处理'), g.cardText.slice(0, 40));

    await sleep(700);
    await search(page, 'incoming1');
    g = await geometry(page);
    const acc = await page.$('.fm-result-card .fm-req-accept');
    const dec = await page.$('.fm-result-card .fm-req-decline');
    ok('L5c incoming_pending → 回应请求 + accept/decline', g.cardText.includes('回应请求') && !!acc && !!dec, g.cardText.slice(0, 50));

    await sleep(700);
    await search(page, 'blocked1');
    g = await geometry(page);
    const ub = await page.$('.fm-result-card .fm-unblock-btn');
    const addOnBlocked = await page.$('.fm-result-card .fm-add-btn');
    ok('L5d blocked_by_me → 已拉黑 + 取消拉黑（无添加钮）', g.cardText.includes('已拉黑') && g.cardText.includes('取消拉黑') && !!ub && !addOnBlocked, g.cardText.slice(0, 50));

    await sleep(700);
    await search(page, 'selfme');
    g = await geometry(page);
    ok('L5e self → 这是你自己', g.cardText.includes('这是你自己'), g.cardText.slice(0, 40));

    await sleep(700);
    await search(page, 'ghost404');
    g = await geometry(page);
    ok('L5f 空结果 → 未找到 + 常识提示', g.cardText.includes('未找到该用户') && g.cardText.includes('尚未设置'), g.cardText.slice(0, 50));
    ok('L5f 空结果卡仍在独立区域', !g.cardInRow && g.cardBelowRow, '');
    await page.screenshot({ path: join(SHOTS, 'miss-default.png') });
    await ctx.close();
  }

  // ══ L4 加载态（delay 350ms 可观察）═══════════════════════
  {
    const { ctx, page } = await bootPage({ delay: '350' });
    await page.fill('.fm-search-input', 'lin');
    await page.click('.fm-search-btn');
    await sleep(120);
    const loading = await page.evaluate(() => {
      const s = document.querySelector('.fm-searching');
      const btn = document.querySelector('.fm-search-btn');
      return {
        dots: s ? s.querySelectorAll('i').length : 0,
        role: s?.getAttribute('role') || '',
        btnTxt: btn?.textContent || '', btnDisabled: !!btn?.disabled,
      };
    });
    ok('L4a 加载态：区域三点 + role=status', loading.dots === 3 && loading.role === 'status', JSON.stringify(loading));
    ok('L4b 加载态：按钮「搜索中…」禁用', loading.btnDisabled && loading.btnTxt.includes('搜索中'), JSON.stringify(loading));
    await sleep(600);
    const g = await geometry(page);
    ok('L4c 加载结束 → 结果卡替代（无残留 loading）', !g.cardInRow && g.cardBelowRow && !g.cardText.includes('搜索中'), '');
    await ctx.close();
  }

  // ══ L4 请求失败态（真实链路 + search abort）══════════════
  {
    const { ctx, page } = await bootPage({ mock: false });
    await page.route('**/api/users/search*', r => r.abort());
    await search(page, 'anyone');
    const g = await geometry(page);
    ok('L4d 失败 → 搜索失败卡（非「未找到」伪装）', g.cardText.includes('搜索失败') && !g.cardText.includes('未找到该用户'), g.cardText.slice(0, 40));
    const alertRole = await page.$eval('.fm-result-card', e => e.getAttribute('role')).catch(() => null);
    ok('L4e 失败卡 role=alert', alertRole === 'alert', `role=${alertRole}`);
    await page.screenshot({ path: join(SHOTS, 'error-default.png') });
    await ctx.close();
  }

  // ══ L7 en 抽查 ═══════════════════════════════════════════
  {
    const { ctx, page } = await bootPage({ locale: 'en' });
    await search(page, 'buddy7');
    const g = await geometry(page);
    ok('L7 en already_friends → Already friends + @username', g.cardText.includes('Already friends') && g.sub === '@buddy7', `sub=${g.sub}`);
    await ctx.close();
  }
} finally {
  await browser.close();
  await new Promise(r => server.close(r));
}

console.log(failures ? `\nRESULT: FAIL (${failures} failures)` : '\nRESULT: PASS — all layout checks green');
console.log(`screenshots → ${SHOTS}`);
process.exit(failures ? 1 : 0);

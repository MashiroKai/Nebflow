#!/usr/bin/env node
// e2e-friends-req-error.cjs — 「新的朋友」请求区错误态离线验红钉（R4，2026-09-11）。
//
// 缺口：好友请求区（web/js/contacts.js buildRequests）此前无错误分支——GET
// /api/friends 失败时 incoming/outgoing 保持 []（refresh() catch 只写
// listErrorKind），请求区于是把「加载失败」折叠成「暂无好友请求」，用户以为
// 真的没有请求（= frienddiag 判决书掩盖机制 ④ 的同族缺口，只修了列表区）。
//
// 本 harness 自包含打桩：page.route + 从磁盘服务真实前端文件（不起端口、
// 不碰 8080；e2e-bgtask-output-card.cjs 同路线），桩 /api/neblink/status →
// {loggedIn:true}，/api/friends → 200 / 401 / 404 / 500 按场景切换，断言请求区
// 出现 [data-fm-req-error="<kind>"]（镜像列表区既有 [data-fm-list-error]
// 断言契约——与视觉文案解耦，文案另作抽查）。
//
// 场景：
//   A auth(401)      → kind=auth + 登录失效文案 + 「重新登录」按钮
//   B retryable(500) → kind=retryable + contacts.requestsError 文案 + 重试按钮；
//                      点击重试 → 请求重发 + 错误卡消失（转回空态）
//   C neblinkOff(404)→ kind=neblinkOff + Neblink 未启用文案（复用既有 key）
//   D keep-last-known→ 有缓存数据时失败不打扰（无错误卡，行仍在）
//   E en 抽查        → "Friend requests failed to load"
//
// 未改 contacts.js 时 A/B/C/D/E 全红（错误卡不出现）→ 改后全绿。
// Run（worktree 根）：node scripts/e2e-friends-req-error.cjs
const { chromium } = require('playwright');
const { readFileSync } = require('node:fs');
const { join, extname, resolve } = require('node:path');

// N6_WEB=<dir> 可指向另一份 web 树（验红用：指向「未改 contacts.js」的基线拷贝）
const WEB = process.env.N6_WEB
  ? resolve(process.env.N6_WEB)
  : join(__dirname, '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

// ── /api/friends 桩载荷 ──────────────────────────────────
const EMPTY = { friends: [], incoming: [], outgoing: [] };
const WITH_REQS = {
  friends: [],
  incoming: [{ requestId: 'rq-1', from: { userId: 'u-1', neblinkId: 'alice', name: 'Alice', avatarUrl: '' }, note: 'hi', status: 'pending', createdAt: Math.floor(Date.now() / 1000) }],
  outgoing: [],
};

(async () => {
  const browser = await chromium.launch();
  const results = [];
  const ok = (name, cond, extra) => {
    results.push([name, !!cond]);
    console.log(`${cond ? 'PASS' : 'FAIL'}: ${name}${extra !== undefined ? '  — ' + extra : ''}`);
  };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const pageErrors = [];

  /** @param {{friends?: 'ok'|'okWithReqs'|'auth'|'off'|'err', locale?: string}} opts */
  async function newPage(opts = {}) {
    const p = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    p.on('pageerror', (e) => pageErrors.push(e.message));
    const state = { friends: opts.friends || 'ok' };
    let friendsHits = 0;

    // catch-all：真实前端文件从磁盘服务；API 全部打桩
    await p.route('**/*', (route) => {
      const u = new URL(route.request().url());
      const path = u.pathname;
      if (path.startsWith('/api/')) {
        if (path === '/api/neblink/status') {
          return route.fulfill({ json: { loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '' }, peers: [] } });
        }
        if (path === '/api/friends') {
          friendsHits++;
          if (state.friends === 'auth') return route.fulfill({ status: 401, json: { error: 'unauthorized' } });
          if (state.friends === 'off') return route.fulfill({ status: 404, json: { error: 'NebLink not enabled' } });
          if (state.friends === 'err') return route.fulfill({ status: 500, contentType: 'text/plain', body: 'boom' });
          return route.fulfill({ json: state.friends === 'okWithReqs' ? WITH_REQS : EMPTY });
        }
        return route.fulfill({ json: {} });
      }
      if (path === '/' || path === '/index.html') {
        return route.fulfill({ body: readFileSync(join(WEB, 'index.html'), 'utf8'), contentType: 'text/html' });
      }
      try {
        const file = join(WEB, decodeURIComponent(path));
        return route.fulfill({ body: readFileSync(file), contentType: MIME[extname(file)] || 'application/octet-stream' });
      } catch { return route.fulfill({ status: 404, body: '' }); }
    });

    await p.routeWebSocket(/\/ws/, (ws) => {
      ws.onMessage((raw) => {
        let m; try { m = JSON.parse(raw); } catch { return; }
        if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      });
      ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
      ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
    });

    await p.addInitScript((loc) => {
      localStorage.setItem('nebflow_token', 't');
      localStorage.setItem('neblink_token', 't');
      localStorage.setItem('nebflow_locale', loc);
      localStorage.setItem('neblink_locale', loc);
    }, opts.locale || 'zh-CN');

    await p.goto('http://mock.local/', { waitUntil: 'domcontentloaded' });
    await p.waitForSelector('#contacts-btn', { state: 'attached', timeout: 20000 });
    await sleep(600);
    await p.click('#contacts-btn');   // 激活通讯录面板 → contacts.js refresh()
    await sleep(800);
    return { p, state, hits: () => friendsHits };
  }

  // 展开「新的朋友」（用 DOM click 而非鼠标：401 场景会弹登录弹窗，遮罩会挡命中测试）
  const expandRequests = async (p) => {
    await p.evaluate(() => { document.querySelector('.fm-nf-entry')?.click(); });
    await sleep(300);
  };

  const reqSnap = (p) => p.evaluate(() => {
    const wrap = document.querySelector('.fm-requests');
    const err = wrap?.querySelector('[data-fm-req-error]');
    return {
      hasWrap: !!wrap,
      kind: err ? err.getAttribute('data-fm-req-error') : null,
      errText: err ? err.textContent : null,
      btns: wrap ? [...wrap.querySelectorAll('button')].map((b) => b.textContent) : [],
      rows: wrap ? wrap.querySelectorAll('.fm-req-row').length : 0,
      wrapText: wrap ? wrap.textContent : null,
      listKind: document.querySelector('[data-fm-list-error]')?.getAttribute('data-fm-list-error') ?? null,
    };
  });

  const dump = (s) => JSON.stringify({ kind: s.kind, rows: s.rows, btns: s.btns, listKind: s.listKind, text: s.errText || s.wrapText });

  // 视觉留痕（可选）：N6_SHOTS=<dir> 时逐场景落 PNG（默认不写文件）
  const shot = async (S, name) => {
    if (!process.env.N6_SHOTS) return;
    await S.p.screenshot({ path: join(process.env.N6_SHOTS, name + '.png') });
  };

  // ── A auth(401) ────────────────────────────────────────
  {
    const S = await newPage({ friends: 'auth' });
    // N5 行为变更留痕：401 经 fm-auth-required 触发全局 openLoginModal（弹窗盖住面板）。
    // 注意断言顺序——登录弹窗自带 outside-click 关闭（showLoginModal），展开请求区的
    // 点击落在弹窗外会把它关掉，故先断言弹窗、再展开（后者顺带＝用户关弹窗的路径）。
    ok('A0 401 → 全局登录弹窗被触发（N5 预期行为：fm-auth-required → openLoginModal）',
      await S.p.evaluate(() => !!document.getElementById('nebflow-login-modal')));
    await shot(S, 'A-auth-modal-open');
    await expandRequests(S.p);
    const s = await reqSnap(S.p);
    ok('A1 401 → 请求区错误卡（data-fm-req-error=auth）', s.kind === 'auth', dump(s));
    ok('A2 401 → 登录失效文案', (s.errText || '').includes('登录已失效'), dump(s));
    ok('A3 401 → 「重新登录」按钮', s.btns.some((b) => b.includes('重新登录')), dump(s));
    ok('A4 401 → 列表区同源分态（不回归）', s.listKind === 'auth', dump(s));
    await shot(S, 'A-auth-after-modal-close');
    await S.p.close();
  }

  // ── B retryable(500) + 重试闭环 ────────────────────────
  {
    const S = await newPage({ friends: 'err' });
    await expandRequests(S.p);
    let s = await reqSnap(S.p);
    ok('B1 500 → 请求区错误卡（data-fm-req-error=retryable）', s.kind === 'retryable', dump(s));
    ok('B2 500 → contacts.requestsError 文案', (s.errText || '').includes('好友请求加载失败'), dump(s));
    ok('B3 500 → 「重试」按钮', s.btns.some((b) => b.includes('重试')), dump(s));
    await shot(S, 'B-retryable-error');
    const hits0 = S.hits();
    S.state.friends = 'ok';                       // 下一拍成功
    const clicked = await S.p.evaluate(() => {
      const wrap = document.querySelector('.fm-requests');
      const btn = [...wrap.querySelectorAll('button')].find((b) => b.textContent.includes('重试'));
      if (!btn) return false;
      btn.click();
      return true;
    });
    await sleep(800);
    s = await reqSnap(S.p);
    ok('B4 重试触发新请求', clicked && S.hits() > hits0, `clicked=${clicked} hits ${hits0} → ${S.hits()}`);
    ok('B5 成功后错误卡消失（转回空态）', clicked && s.kind === null && (s.wrapText || '').includes('暂无好友请求'), dump(s));
    await S.p.close();
  }

  // ── C neblinkOff(404) ──────────────────────────────────
  {
    const S = await newPage({ friends: 'off' });
    await expandRequests(S.p);
    const s = await reqSnap(S.p);
    ok('C1 404 → 请求区错误卡（data-fm-req-error=neblinkOff）', s.kind === 'neblinkOff', dump(s));
    ok('C2 404 → 「Neblink 未启用」文案（复用既有 key）', (s.errText || '').includes('Neblink 未启用'), dump(s));
    await shot(S, 'C-neblink-off');
    await S.p.close();
  }

  // ── D keep-last-known（有缓存数据不打扰）────────────────
  {
    const S = await newPage({ friends: 'okWithReqs' });
    await expandRequests(S.p);
    let s = await reqSnap(S.p);
    ok('D1 成功态：请求行渲染 + 无错误卡', s.rows === 1 && s.kind === null, dump(s));
    S.state.friends = 'err';                      // 之后失败
    await S.p.evaluate(() => window.dispatchEvent(new CustomEvent('fm-friends-changed')));
    await sleep(800);
    s = await reqSnap(S.p);
    ok('D2 失败但已有数据 → keep-last-known（行仍在、无错误卡）', s.rows === 1 && s.kind === null, dump(s));
    await shot(S, 'D-keep-last-known');
    await S.p.close();
  }

  // ── E en 抽查 ──────────────────────────────────────────
  {
    const S = await newPage({ friends: 'err', locale: 'en' });
    await expandRequests(S.p);
    const s = await reqSnap(S.p);
    ok('E1 en → "Friend requests failed to load"', (s.errText || '').includes('Friend requests failed to load'), dump(s));
    await shot(S, 'E-en-retryable');
    await S.p.close();
  }

  ok('Z1 零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors.slice(0, 3)));

  const failed = results.filter(([, c]) => !c);
  console.log(failed.length ? `\n${failed.length}/${results.length} assertion(s) FAILED → ${failed.map(([n]) => n).join(', ')}` : `\n${results.length}/${results.length} ALL PASS`);
  await browser.close();
  process.exit(failed.length ? 1 : 0);
})().catch((e) => { console.error('FATAL', e); process.exit(1); });

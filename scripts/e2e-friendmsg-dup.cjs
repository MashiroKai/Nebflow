#!/usr/bin/env node
// e2e-friendmsg-dup.cjs — 好友会话「发送的消息本地重复显示」离线验红钉（作者报障 2026-09-14）。
//
// 病灶（数据层，非渲染层）：`sendCurrent`（messages.js）的乐观项在 **POST 响应回来
// 之前** 用临时 id（`fm-tmp-N`）占位；而同一条服务端消息可以经**另外两条腿**
// 先一步进窗：
//   ① WS 自播帧 `message_new_self`（本机代发自播 / 他机 push）
//   ② REST keyset 增量 `after=<水位>`（beacon 帧静默回补 / 回唤醒 / 重开窗同步）
// 两条腿都按**真 messageId** 判「是不是已在窗口里」——乐观项的临时 id 让判据
// 失配 ⇒ 真 id 那条**新上一屏**；随后 POST 响应再把乐观节点改键成**同一个真 id**
// ⇒ 同一 messageId 两个 DOM 节点 + 两条 `chatMsgs` 条目 ⇒ 视觉重复。
// （截图实证：同文同刻、两个独立气泡、各自带复制/转发按钮。）
//
// 本 harness 自包含打桩（零端口、不碰 8080，与 e2e-friends-req-error.cjs 同路线）：
// `page.route` 从磁盘服务**真实 web 树** + `page.routeWebSocket` 收发真实帧形状，
// 驱动**真实** UI 路径（点会话行开窗 → 输入框发消息 → 帧/sync 到达），不重实现被测逻辑。
//
// 双读数口径（禁只看 DOM）：真渲染 DOM 气泡数 + `data-message-id` 序列唯一性
// （视觉面）∧ localStorage L2 缓存 JSON 的条数与 id 唯一性（数据面）。
// 两处同时给读数 ⇒ 可判「真双份数据」还是「单数据双绘制」。
//
// 反事实：`N6_WEB=<另一份 web 树>` 指向未修改的基线拷贝 ⇒ 同一 harness 应达红。
// Run（worktree 根）：
//   node scripts/e2e-friendmsg-dup.cjs
//   N6_WEB=.nebflow/evidence/20260914_friendmsg-dup/web-baseline node scripts/e2e-friendmsg-dup.cjs
const { chromium } = require('playwright');
const { readFileSync } = require('node:fs');
const { join, extname, resolve } = require('node:path');

// N6_WEB=<dir> 可指向另一份 web 树（验红用：指向「未改 messages.js」的基线拷贝）
const WEB = process.env.N6_WEB
  ? resolve(process.env.N6_WEB)
  : join(__dirname, '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

const FRIEND = { userId: 'u-1', neblinkId: 'alice', name: 'Alice', avatarUrl: '' };
const CONV_ID = 'c-1';
const PREV = { id: 100, senderId: 'u-1', kind: 'text', body: '上一条', createdAt: Math.floor(Date.now() / 1000) - 60 };
const SENT_TEXT = '你那是经纬的路演文稿'; // 作者截图原文（同文同刻两条气泡）

(async () => {
  const browser = await chromium.launch();
  const results = [];
  const ok = (name, cond, extra) => {
    results.push([name, !!cond]);
    console.log(`${cond ? 'PASS' : 'FAIL'}: ${name}${extra !== undefined ? '  — ' + extra : ''}`);
  };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const waitFor = async (fn, ms = 4000) => {
    const t0 = Date.now();
    while (Date.now() - t0 < ms) { if (fn()) return true; await sleep(25); }
    return false;
  };
  const dup = (arr) => arr.filter((v, i) => arr.indexOf(v) !== i);
  const pageErrors = [];

  /**
   * 打开一个打桩页面。零端口：真实 web 文件从磁盘读，全部 `/api/**` 打桩，
   * WS 走 `page.routeWebSocket` 内存通道。
   * @param {{postDelayMs?: number}} opts postDelayMs = `POST /api/friends/<id>/messages`
   *   的响应延迟（制造「服务端已落库、客户端尚未拿到响应」的窗口 —— 竞态成因形状）。
   */
  async function newPage(opts = {}) {
    const page = await browser.newPage({
      viewport: opts.viewport || { width: 1440, height: 900 },
      colorScheme: opts.colorScheme || 'dark',   // 主题 = prefers-color-scheme（无声明确切面）
    });
    page.on('pageerror', (e) => pageErrors.push(e.message));
    /** 服务端侧真源（桩）。 */
    const st = {
      msgs: [PREV],
      seq: 100,
      postDelayMs: opts.postDelayMs || 0,
      postFail: false,
      lastSent: null,
      ws: null,
      wsConns: 0,
      reqs: {},           // pathname → 命中次数（S7 判「handler 是否重复注册」用）
      sends: 0,
    };
    const hit = (path) => { st.reqs[path] = (st.reqs[path] || 0) + 1; };

    await page.route('**/*', async (route) => {
      const req = route.request();
      const u = new URL(req.url());
      const path = u.pathname;
      if (path.startsWith('/api/')) {
        hit(path);
        if (path === '/api/neblink/status') {
          return route.fulfill({ json: {
            loggedIn: true,
            device: { id: 'd1', name: '本机', platform: 'macos', email: 'me@example.com', userDescription: '', avatarUrl: '' },
            peers: [], relay: { available: true },
          } });
        }
        if (path === '/api/friends') return route.fulfill({ json: { friends: [FRIEND], incoming: [], outgoing: [] } });
        if (path === '/api/conversations') {
          const last = st.msgs[st.msgs.length - 1];
          return route.fulfill({ json: [{ conversationId: CONV_ID, friend: FRIEND, lastMessage: last, unreadCount: 0 }] });
        }
        if (path === `/api/conversations/${CONV_ID}/messages`) {
          const after = Number(u.searchParams.get('after') || 0);
          const limit = Math.max(1, Math.min(200, Number(u.searchParams.get('limit') || 50)));
          return route.fulfill({ json: st.msgs.filter((m) => Number(m.id) > after).slice(0, limit) });
        }
        if (path === `/api/conversations/${CONV_ID}/read`) return route.fulfill({ json: {} });
        if (path === `/api/friends/${FRIEND.userId}/messages`) {
          // 服务端**立刻**落库（真实语义），响应按 postDelayMs 延迟 ⇒ 制造
          // 「已落库、客户端还没拿到响应」的窗口。
          const body = JSON.parse(req.postData() || '{}').body || '';
          const m = { id: ++st.seq, senderId: 'me', kind: 'text', body, createdAt: Math.floor(Date.now() / 1000) };
          st.msgs.push(m);
          st.lastSent = m;
          st.sends++;
          if (st.postDelayMs) await sleep(st.postDelayMs);
          if (st.postFail) return route.fulfill({ status: 500, contentType: 'text/plain', body: 'boom' });
          return route.fulfill({ json: { messageId: m.id, conversationId: CONV_ID, createdAt: m.createdAt } });
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

    await page.routeWebSocket(/\/ws/, (ws) => {
      st.ws = ws;
      st.wsConns++;
      ws.onMessage((raw) => {
        let m; try { m = JSON.parse(raw); } catch { return; }
        if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      });
      ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
      ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
    });

    await page.addInitScript(() => {
      localStorage.setItem('nebflow_token', 't');
      localStorage.setItem('neblink_token', 't');
      localStorage.setItem('nebflow_locale', 'zh-CN');
      localStorage.setItem('neblink_locale', 'zh-CN');
    });

    await page.goto('http://mock.local/', { waitUntil: 'domcontentloaded' });
    await page.waitForSelector('#messages-btn', { state: 'attached', timeout: 20000 });
    await sleep(300);
    await page.click('#messages-btn');              // 激活消息面板 → refreshConversations()
    await page.waitForSelector(`.fm-conv-row[data-conversation-id="${CONV_ID}"]`, { timeout: 20000 });
    return { page, st };
  }

  /** 开会话窗（真实入口：点会话行）。 */
  const openConv = async (page) => {
    await page.evaluate((id) => document.querySelector(`.fm-conv-row[data-conversation-id="${id}"]`).click(), CONV_ID);
    await page.waitForSelector('.fm-flow', { timeout: 20000 });
    await sleep(400);
  };

  /** 从输入框发一条（真实入口：填值 + 点发送按钮 ⇒ 真实 sendCurrent）。 */
  const send = async (page, text) => {
    await page.fill('.fm-input', text);
    await page.click('.fm-send-btn');
  };

  /** 帧形状与 `FriendEvent.frontendFrame` 逐字段同形（payload 展平到帧顶层）。 */
  const selfFrame = (id) => JSON.stringify({
    type: 'friend_event', event: 'message_new_self', messageId: id,
    conversationId: CONV_ID, kind: 'text', body: SENT_TEXT, origin: 'agent',
    createdAt: Math.floor(Date.now() / 1000),
  });
  const inFrame = (id, body) => JSON.stringify({
    type: 'friend_event', event: 'message_new', messageId: id,
    conversationId: CONV_ID, senderId: FRIEND.userId, kind: 'text', body,
    createdAt: Math.floor(Date.now() / 1000),
  });

  /** 双读数：真渲染 DOM（气泡数 / id 序列）+ 数据面（L2 缓存 JSON 条数与 id）。 */
  const snap = (page) => page.evaluate(() => {
    const flow = document.querySelector('.fm-flow');
    const nodes = flow ? [...flow.querySelectorAll('.fm-msg')] : [];
    const ids = nodes.map((n) => String(n.dataset.messageId));
    const bodies = nodes.map((n) => (n.querySelector('.fm-msg-bubble') || {}).textContent || '');
    let cacheMsgs = [];
    try {
      const k = Object.keys(localStorage).find((x) => x.includes('fm_msg_cache'));
      const raw = k ? JSON.parse(localStorage.getItem(k)) : null;
      const entry = raw && raw.convs ? raw.convs['c-1'] : null;
      cacheMsgs = (entry && entry.msgs) || [];
    } catch { /* non-critical */ }
    const dataIds = cacheMsgs.map((m) => String(m.id));
    const dup = (arr) => arr.filter((v, i) => arr.indexOf(v) !== i);
    return {
      domCount: nodes.length,
      domIds: ids,
      domDup: dup(ids),
      domBodies: bodies,
      dataCount: dataIds.length,
      dataIds,
      dataDup: dup(dataIds),
    };
  });

  const dump = (s) => JSON.stringify({
    dom: { n: s.domCount, ids: s.domIds, dup: s.domDup, bodies: s.domBodies },
    data: { n: s.dataCount, ids: s.dataIds, dup: s.dataDup },
  });
  /** 同文气泡数（作者症状的直接读数）。 */
  const sentBubbles = (s) => s.domBodies.filter((b) => b === SENT_TEXT).length;

  /** 单场景断言组：同文气泡恰一条 + 无重复 messageId（DOM 面 ∧ 数据面）。 */
  const assertSingle = (tag, s, expectDom = 2) => {
    ok(`${tag}·DOM 气泡数 = ${expectDom}（真渲染读数）`, s.domCount === expectDom, dump(s));
    ok(`${tag}·同文气泡恰 1 条（作者症状读数）`, sentBubbles(s) === 1, `${sentBubbles(s)} 条`);
    ok(`${tag}·DOM messageId 无重复（M5 一致性门）`, s.domDup.length === 0, JSON.stringify(s.domDup));
    ok(`${tag}·数据面（L2 缓存 JSON）同 id 无重复`, s.dataDup.length === 0, JSON.stringify(s.dataDup));
  };

  // 视觉留痕（可选）：N6_SHOTS=<dir> 时逐场景落 PNG（默认不写文件）
  const shot = async (page, name) => {
    if (!process.env.N6_SHOTS) return;
    await page.screenshot({ path: join(process.env.N6_SHOTS, name + '.png') });
  };

  // ── S1 正常发送（单条）：响应先到、无竞态 ⇒ 前后都必须绿 ──────────
  {
    const { page, st } = await newPage({ postDelayMs: 0 });
    await openConv(page);
    const pre = await snap(page);
    ok('S1·前置：会话说历史 1 条', pre.domCount === 1, dump(pre));
    await send(page, SENT_TEXT);
    await sleep(700);
    const s = await snap(page);
    ok('S1·发送后 DOM 气泡数 = 2', s.domCount === 2, dump(s));
    ok('S1·同文气泡恰 1 条', sentBubbles(s) === 1, `${sentBubbles(s)} 条`);
    ok('S1·无重复 messageId（DOM/数据面）', s.domDup.length === 0 && s.dataDup.length === 0, dump(s));
    ok('S1·乐观项已锚定真 id（无 fm-tmp 残留）', !s.domIds.some((i) => i.startsWith('fm-tmp-')), JSON.stringify(s.domIds));
    await shot(page, 'S1-single-send');
    await page.close();
  }

  // ── S2 回显先到（WS 自播帧）· 响应后到 ⇒ 头号形态（预期红）──────
  {
    const { page, st } = await newPage({ postDelayMs: 600 });
    await openConv(page);
    await send(page, SENT_TEXT);
    const got = await waitFor(() => st.lastSent !== null);
    st.ws.send(selfFrame(st.lastSent.id));         // 服务端副本经 WS 腿先到窗口
    await sleep(1200);                             // 等 POST 响应回来改键
    const s = await snap(page);
    ok('S2·服务端已落库 1 条（桩读数，排除「服务端重复发送」）',
      got && st.sends === 1, `sends=${st.sends} lastSent=${st.lastSent && st.lastSent.id}`);
    assertSingle('S2', s);
    await shot(page, 'S2-echo-first-ws');

    // ── S6 缓存污染后重开窗仍不双份 ─────────────────────────────
    await page.evaluate(() => document.querySelector('.fm-modal-close').click());
    await sleep(300);
    await openConv(page);                          // 热路径：L2 缓存首屏
    const s6 = await snap(page);
    ok('S6·重开窗后 DOM 气泡数 = 2', s6.domCount === 2, dump(s6));
    ok('S6·重开窗后同文气泡恰 1 条', sentBubbles(s6) === 1, `${sentBubbles(s6)} 条`);
    ok('S6·重开窗后无重复 messageId（DOM/数据面）', s6.domDup.length === 0 && s6.dataDup.length === 0, dump(s6));
    await shot(page, 'S6-reopen-after-race');
    await page.close();
  }

  // ── S3 响应先到 · 回显后到（反向序列，回归位）────────────────────
  {
    const { page, st } = await newPage({ postDelayMs: 0 });
    await openConv(page);
    await send(page, SENT_TEXT);
    await waitFor(() => st.lastSent !== null && st.sends === 1);
    await sleep(400);                              // 先等响应把乐观项锚成真 id
    st.ws.send(selfFrame(st.lastSent.id));         // 再让帧到
    await sleep(500);
    assertSingle('S3', await snap(page));
    await shot(page, 'S3-response-first');
    await page.close();
  }

  // ── S4 REST 增量先到（fm-wake → keyset sync）· 响应后到 ⇒ 第二条腿 ──
  {
    const { page, st } = await newPage({ postDelayMs: 700 });
    await openConv(page);
    await send(page, SENT_TEXT);
    await waitFor(() => st.lastSent !== null);
    // 唤醒面（真实入口：ws.js 在 visibilitychange→visible / online 广播本事件）
    await page.evaluate(() => window.dispatchEvent(new CustomEvent('fm-wake')));
    await sleep(1600);
    const s = await snap(page);
    const hits = st.reqs[`/api/conversations/${CONV_ID}/messages`] || 0;
    ok('S4·REST keyset 增量确实命中新消息（桩读数）', hits >= 2, `GET messages 命中 ${hits} 次`);
    assertSingle('S4', s);
    await shot(page, 'S4-echo-first-rest');
    await page.close();
  }

  // ── S5 收到消息路径不回归（帧级去重 + 「不猜」口径）──────────────
  {
    const { page, st } = await newPage({ postDelayMs: 0 });
    await openConv(page);
    st.ws.send(inFrame(200, '对方第一条'));
    await sleep(500);
    let s = await snap(page);
    ok('S5·收到消息上屏（1 条新气泡）', s.domCount === 2 && s.domBodies[1] === '对方第一条', dump(s));
    st.ws.send(inFrame(200, '对方第一条'));        // 同 messageId 二次到达（重放/自播）
    await sleep(500);
    s = await snap(page);
    ok('S5·同 messageId 二次帧不二次上屏（U-a 帧级去重）', s.domCount === 2 && s.domDup.length === 0, dump(s));
    st.ws.send(JSON.stringify({                    // 缺席 messageId 的帧：不得被拦（「不猜」口径）
      type: 'friend_event', event: 'message_new', conversationId: CONV_ID,
      senderId: FRIEND.userId, kind: 'text', body: '无 id 帧', createdAt: Math.floor(Date.now() / 1000),
    }));
    await sleep(500);
    s = await snap(page);
    ok('S5·缺席 messageId 的帧仍上屏（「不猜」口径未收紧）', s.domCount === 3, dump(s));
    await shot(page, 'S5-inbound');
    await page.close();
  }

  // ── S7 B③ 读数：friend_event handler 是否被重复注册（重连 / configData 重入）──
  // 判据 = 一条「未知会话」帧 ⇒ handler 内一次 refreshConversations ⇒ 一次
  // GET /api/conversations（该早退分支在帧级去重**之前**，故每次调用必发）。
  // handler 数 = 请求增量。
  {
    const { page, st } = await newPage({ postDelayMs: 0 });
    const P = '/api/conversations';
    const unknownFrame = JSON.stringify({
      type: 'friend_event', event: 'message_new', messageId: 900, conversationId: 'c-unknown',
      senderId: 'u-9', kind: 'text', body: 'x', createdAt: Math.floor(Date.now() / 1000),
    });
    const base = st.reqs[P] || 0;
    st.ws.send(unknownFrame);
    await sleep(600);
    ok('S7·一条未知会话帧 ⇒ 恰好 1 次 refreshConversations（= 1 个 handler）',
      (st.reqs[P] || 0) - base === 1, `Δ=${(st.reqs[P] || 0) - base}`);
    // 模拟「WS 重连后服务端重发 configData」这一重入路径
    st.ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    await sleep(400);
    const base2 = st.reqs[P] || 0;
    st.ws.send(unknownFrame);
    await sleep(600);
    ok('S7·configData 重入后仍恰好 1 个 handler（initMessages latch）',
      (st.reqs[P] || 0) - base2 === 1, `Δ=${(st.reqs[P] || 0) - base2}`);
    ok('S7·重入后会话行未重复渲染',
      (await page.evaluate(() => document.querySelectorAll('#fm-conversations .fm-conv-row').length)) === 1);
    await page.close();
  }

  // ── S8 亮/暗双主题 × 三端视口矩阵（真渲染读数；逐格截图留痕）──────────
  // 同一竞态形态逐格复跑：气泡数/重复 id 读数 + 逐格 PNG（N6_SHOTS 时）。
  {
    const VIEWPORTS = [
      { name: 'narrow', width: 420, height: 780 },
      { name: 'mid', width: 900, height: 800 },
      { name: 'wide', width: 1440, height: 900 },
    ];
    for (const scheme of ['dark', 'light']) {
      for (const vp of VIEWPORTS) {
        const { page, st } = await newPage({
          postDelayMs: 500, colorScheme: scheme, viewport: { width: vp.width, height: vp.height },
        });
        await openConv(page);
        await send(page, SENT_TEXT);
        await waitFor(() => st.lastSent !== null);
        st.ws.send(selfFrame(st.lastSent.id));
        await sleep(1100);
        const s = await snap(page);
        ok(`S8·${scheme}/${vp.name}·同文气泡恰 1 条 + 无重复 id`,
          sentBubbles(s) === 1 && s.domDup.length === 0 && s.dataDup.length === 0 && s.domCount === 2, dump(s));
        // 气泡确实落在可视区内（三端都真的渲染出来了，不是被挤出视口）
        const inView = await page.evaluate(() => {
          const n = document.querySelector('.fm-flow .fm-msg:last-child');
          if (!n) return false;
          const r = n.getBoundingClientRect();
          return r.width > 0 && r.height > 0 && r.bottom > 0 && r.top < innerHeight;
        });
        ok(`S8·${scheme}/${vp.name}·末条气泡在可视区内（真渲染）`, inView);
        if (process.env.N6_SHOTS) {
          await page.screenshot({ path: join(process.env.N6_SHOTS, `S8-${scheme}-${vp.width}x${vp.height}.png`) });
        }
        await page.close();
      }
    }
  }

  // ── S9 同正文连发两条 · 回显先到 ⇒ 认领必须**按发送序**（FIFO）──────
  // 判据收紧后的边界场景：正文逐字相同时认领取最老一条未决项（服务端 id 升序 =
  // 发送序）；两条各自锚到自己的 id，且总气泡数 = 3（历史 + 2 条）。
  {
    const { page, st } = await newPage({ postDelayMs: 500 });
    await openConv(page);
    await send(page, SENT_TEXT);
    await waitFor(() => st.sends === 1);
    await send(page, SENT_TEXT);                  // 同正文第二条（未决项有两条）
    await waitFor(() => st.sends === 2);
    st.ws.send(selfFrame(101));                   // 服务端副本按 id 升序到达
    st.ws.send(selfFrame(102));
    await sleep(1400);
    const s = await snap(page);
    ok('S9·DOM 气泡数 = 3（历史 + 2 条同文）', s.domCount === 3, dump(s));
    ok('S9·同文气泡恰 2 条（两条都保留，未互相吞并）', sentBubbles(s) === 2, `${sentBubbles(s)} 条`);
    ok('S9·无重复 messageId（DOM/数据面）', s.domDup.length === 0 && s.dataDup.length === 0, dump(s));
    ok('S9·认领按发送序落位（id 升序 = 101,102）',
      s.domIds.join(',') === '100,101,102', JSON.stringify(s.domIds));
    await shot(page, 'S9-same-body-twice');
    await page.close();
  }

  // ── S10 发送失败 / 重试路径不回归（catch 腿被本批触及）──────────────
  {
    const { page, st } = await newPage({ postDelayMs: 200 });
    await openConv(page);
    st.postFail = true;
    await send(page, SENT_TEXT);
    await sleep(700);
    let s = await snap(page);
    ok('S10·失败态：气泡 1 条 + fm-failed + 重试按钮',
      s.domCount === 2 && s.domDup.length === 0
      && (await page.evaluate(() => !!document.querySelector('.fm-msg.fm-failed .fm-retry'))), dump(s));
    st.postFail = false;
    st.postDelayMs = 0;
    await page.evaluate(() => document.querySelector('.fm-msg.fm-failed .fm-retry').click());
    await sleep(800);
    s = await snap(page);
    ok('S10·重试成功后仍恰 2 条气泡（无失败幻影/无重复）', s.domCount === 2 && s.domDup.length === 0 && s.dataDup.length === 0, dump(s));
    ok('S10·重试后同文气泡恰 1 条', sentBubbles(s) === 1, `${sentBubbles(s)} 条`);
    await shot(page, 'S10-retry');
    await page.close();
  }

  ok('Z1 零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors.slice(0, 3)));

  const failed = results.filter(([, c]) => !c);
  console.log(failed.length
    ? `\n${failed.length}/${results.length} assertion(s) FAILED → ${failed.map(([n]) => n).join(', ')}`
    : `\n${results.length}/${results.length} ALL PASS`);
  console.log(`WEB=${WEB}`);
  await browser.close();
  process.exit(failed.length ? 1 : 0);
})().catch((e) => { console.error('FATAL', e); process.exit(1); });

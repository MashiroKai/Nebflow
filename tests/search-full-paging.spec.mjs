// search-full-paging.spec.mjs — 检索全覆盖批（2026-11-07）验收：搜索聊天记录必须能
// 一路翻到**最早一条**，而不是停在「前 200 条」。
//
// 作者报障（2026-11-07 12:26 逐字）：「还有就是我们主会话搜索聊天记录 为什么不是搜
// 全部的内容 可以分段显示 但是我要能查看呀」。
//
// 改前病灶（本 spec 的红锚）：chatSearch.js 搜索模式把命中 slice(0, MAX_RESULTS=200)
// 后渲染，服务端 `GET /api/search/chat` 虽然支持 limit+offset 真分页，前端却**只发
// limit 从不发 offset** ⇒ 第 201 条以后的命中「在库里、在 total 里，就是永远看不到」，
// 状态行还只写「仅显示前 200 条」。分段渲染本来是有的（RENDER_BATCH + 哨兵），缺的
// 是「取下一段」那一腿。
//
// 本 spec 钉住改后契约（数据面 = 520 条命中的分页夹具，跨 11 页）：
//   ① 首屏只取一页（50 条）——不一次拉全（手机不炸内存）；
//   ② 结果区滚到底 ⇒ 自动续取下一页（offset 顺序 0,50,…,500，禁跳页/禁重复）；
//   ③ 取尽后 DOM 里出现**最早那一条**（data-key 固定 + 文案「最早一条命中」）；
//   ④ 状态行全程报「已加载 n/520 条」，取尽报「520 条结果」，不再有截断尾注；
//   ⑤ 375×812 真滚动到底后末条可见（观感判据）；桌面视口同一条腿零分叉。
// 双模式：
//   · 默认（CI）= 上文的 520 条分页夹具，零后端、零 sbt、确定性；
//   · SEARCH_REAL_API=1 = **真数据验收**：`/api/search/chat` 与 `/api/sessions`
//     直连本机运行中的网关（读 `~/.stand/auth.json` 的本地令牌），web 静态面仍是
//     本工作树的**改后代码** ⇒ 用真实索引（真实 total / 真实最早一条）跑同一条
//     续取腿。scope 切到「全部会话」（手机默认 pin 当前会话，真数据验的是全史）。
//     跑法：SEARCH_REAL_API=1 node tests/search-full-paging.spec.mjs
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

const SID = 'paging-root-session';
const TOTAL = 520;             // 命中总数（= 服务端 total）
const PAGE = 50;               // 前端首屏/每页条数（chatSearch SEARCH_PAGE）
const EARLIEST_KEY = `${SID}:1`;

// ── 真数据模式（SEARCH_REAL_API=1）──────────────────────────────────────────
const REAL = process.env.SEARCH_REAL_API === '1';
const GATEWAY = process.env.SEARCH_REAL_GATEWAY || 'http://127.0.0.1:6138';
const REAL_Q = process.env.SEARCH_REAL_Q || '作者';
const REAL_TOKEN = REAL
  ? JSON.parse(readFileSync(join(process.env.HOME || '', '.stand', 'auth.json'), 'utf8')).replace(/"/g, '')
  : '';

/** 分页夹具：index 0 = 最新（ts 最大），末位 = 最早（ts 最小）——与索引
 *  `ORDER BY ts DESC, ord DESC` 同序。最早那一条带专属文案，供「翻到底」判据。 */
const HITS = Array.from({ length: TOTAL }, (_, i) => {
  const ord = TOTAL - i;                  // i=0 ⇒ ord=520 … i=519 ⇒ ord=1（最早）
  const ts = 1_760_000_000_000 + (TOTAL - i) * 60_000;
  const isEarliest = ord === 1;
  return {
    sid: SID,
    ord,
    kind: 'message',
    ts,
    snippet: isEarliest
      ? '最早一条命中：检索记录的最开头那句话'
      : `检索记录第 ${ord} 条命中（分段加载判据）`,
    extra: { role: 'user' },
  };
});

/** 路由侧读数：前端实际发出的 offset 序列（判据 ② 的证据面）。 */
let offsets = [];

async function bootApp(page) {
  offsets = [];
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', async (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === `/api/sessions/${SID}/history`) {
      return route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ messages: [], total: 0, sessionId: SID }),
      });
    }
    if (p === '/api/search/chat') {
      const offset = Number(url.searchParams.get('offset') || 0);
      const limit = Number(url.searchParams.get('limit') || PAGE);
      offsets.push(offset);
      if (REAL) {
        // 真数据腿：直连本机网关（真索引/真 total），只把 Authorization 补上。
        const qs = url.search || '';
        const real = await fetch(`${GATEWAY}/api/search/chat${qs}`, {
          headers: { Authorization: `Bearer ${REAL_TOKEN}` },
        });
        return route.fulfill({
          status: real.status,
          contentType: 'application/json',
          body: await real.text(),
          headers: { 'Access-Control-Allow-Origin': '*' },
        });
      }
      return route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({
          hits: HITS.slice(offset, offset + limit),
          total: TOTAL,
          degraded: false,
        }),
      });
    }
    if (p === '/api/sessions' && REAL) {
      const real = await fetch(`${GATEWAY}/api/sessions${url.search || ''}`, {
        headers: { Authorization: `Bearer ${REAL_TOKEN}` },
      });
      return route.fulfill({
        status: real.status, contentType: 'application/json', body: await real.text(),
      });
    }
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }),
        });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({
        status: 200,
        contentType: MIME[extname(file)] || 'application/octet-stream',
        body: readFileSync(file),
      });
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

/** 跑一次「输入关键词 → 首屏」，返回状态行文本与首屏行数。 */
async function openAndSearch(page) {
  await page.locator('#search-keyword').fill('检索');
  await page.waitForFunction(
    () => document.querySelectorAll('#search-results .search-result').length > 0,
    null, { timeout: 15000 });
  const rows = await page.locator('#search-results .search-result').count();
  const status = await page.locator('#search-status').textContent();
  return { rows, status: (status || '').trim() };
}

/** 结果区滚到底 ⇒ 等下一段加载，直到取满 TOTAL 或超时（观感判据同一条腿）。 */
async function scrollToLoadAll(page) {
  const count = () => page.locator('#search-results .search-result').count();
  const deadline = Date.now() + 60000;
  let last = await count();
  while (last < TOTAL && Date.now() < deadline) {
    await page.evaluate(() => {
      const el = document.getElementById('search-results');
      if (el) el.scrollTop = el.scrollHeight;
    });
    // 续取一段最多 50 行，给足渲染 + 续取往返时间。
    await page.waitForTimeout(250);
    const now = await count();
    if (now === last) {
      // 二次确认（哨兵 observer 回调是异步的，可能落在同一帧之后）。
      await page.waitForTimeout(350);
      const again = await count();
      if (again === last) break;
      last = again;
    } else {
      last = now;
    }
  }
  return last;
}

function watchErrors(page) {
  const errors = [];
  // 静态夹具里 favicon / 字体 / 图标 404 是 harness 噪声，不是本批代码缺陷
  // （其它 spec 不查 console 就绕过了它；这里显式白名单，仍抓真 JS 报错）。
  const NOISE = /Failed to load resource|An unknown error occurred when fetching the script/;
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => {
    if (m.type() === 'error' && !NOISE.test(m.text())) errors.push(`console: ${m.text()}`);
  });
  return errors;
}

test.describe('手机 375×812', () => {
  test.use({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: 'dark' });

  test('M1 分段续取：滚到底续取到最早一条（可以分段显示，但要能查看）', async ({ page }) => {
    const errors = watchErrors(page);
    await bootApp(page);
    // 手机层：聊天根 chrome 搜索键 → 搜索页（面收编进页面体）。
    await page.locator('[data-chrome="search"]').click();
    await expect(page.locator('#search-overlay')).toHaveClass(/on/);
    await expect(page.locator('#search-modal')).toBeVisible();

    const first = await openAndSearch(page);
    // ① 首屏只取一页（不一次拉全）＋ 状态行如实报「已加载 n/520」。
    expect(first.rows).toBe(PAGE);
    expect(first.status).toContain(`已加载 ${PAGE}/${TOTAL} 条`);
    expect(offsets).toEqual([0]);

    // ② 滚到底续取（offset 逐段前进、不跳页不重页）。
    const loaded = await scrollToLoadAll(page);
    expect(loaded, `续取到全部 ${TOTAL} 条（实际 ${loaded}）`).toBe(TOTAL);
    expect(offsets[0]).toBe(0);
    for (let i = 1; i < offsets.length; i++) {
      expect(offsets[i], `第 ${i} 页 offset 必须 +${PAGE} 前进（禁跳页/禁重复）`)
        .toBe(offsets[i - 1] + PAGE);
    }
    expect(Math.max(...offsets) + PAGE).toBeGreaterThanOrEqual(TOTAL);

    // ③ 最早那一条必须在 DOM 里且可读（这就是「能查看」的判据）。
    const earliest = page.locator(`#search-results .search-result[data-key="${EARLIEST_KEY}"]`);
    await expect(earliest).toHaveCount(1);
    await expect(earliest.locator('.search-result-preview')).toContainText('最早一条命中');

    // ④ 取尽后状态行 = 总数，且不再出现「仅显示前 N 条」的截断尾注。
    const status = (await page.locator('#search-status').textContent() || '').trim();
    expect(status).toContain(`${TOTAL} 条结果`);
    expect(status).not.toContain('仅显示前');

    // ⑤ 观感判据：滚到最底时末条在视口内可见（不是被容器切掉/被底栏盖住）。
    await page.evaluate(() => {
      const el = document.getElementById('search-results');
      if (el) el.scrollTop = el.scrollHeight;
    });
    await page.waitForTimeout(200);
    const box = await earliest.boundingBox();
    expect(box, '最早一条必须有可见盒子').toBeTruthy();
    expect(box.y).toBeGreaterThanOrEqual(0);
    expect(box.y + box.height).toBeLessThanOrEqual(812 + 1);
    // 观感留证（375×812 滚到底的实拍）
    await page.screenshot({ path: '/tmp/nb-search-paging/mobile-bottom-375x812.png' });

    expect(errors, errors.join('\n')).toEqual([]);
  });
});

test.describe('桌面 1280×800', () => {
  test.use({ viewport: { width: 1280, height: 800 }, colorScheme: 'dark' });

  test('D1 同一条腿：桌面搜索面板同样续取到最早一条', async ({ page }) => {
    const errors = watchErrors(page);
    await bootApp(page);
    await page.locator('#search-btn').click();
    await expect(page.locator('#search-overlay')).toHaveClass(/on/);

    const first = await openAndSearch(page);
    expect(first.rows).toBe(PAGE);
    expect(first.status).toContain(`已加载 ${PAGE}/${TOTAL} 条`);

    const loaded = await scrollToLoadAll(page);
    expect(loaded).toBe(TOTAL);
    await expect(page.locator(`#search-results .search-result[data-key="${EARLIEST_KEY}"]`)).toHaveCount(1);
    const status = (await page.locator('#search-status').textContent() || '').trim();
    expect(status).toContain(`${TOTAL} 条结果`);
    expect(errors, errors.join('\n')).toEqual([]);
  });
});

// ── 真数据验收（SEARCH_REAL_API=1）：本机真实索引 + 改后前端，取尽全部命中 ──────
test.describe('真数据（SEARCH_REAL_API=1）', () => {
  test.use({ viewport: { width: 1280, height: 900 }, colorScheme: 'dark' });
  test.skip(!REAL, '未设 SEARCH_REAL_API=1（夹具模式不跑真数据腿）');

  test('R1 真索引全史续取：取到 total 条并翻出最早一条（真 total / 真行号）', async ({ page }) => {
    const errors = watchErrors(page);
    await bootApp(page);
    await page.locator('#search-btn').click();
    await expect(page.locator('#search-overlay')).toHaveClass(/on/);
    // 真数据验的是「全部会话」全史（手机/桌面默认 current 只搜当前会话）。
    await page.evaluate(() => {
      const sel = /** @type {HTMLSelectElement} */ (document.getElementById('search-scope'));
      sel.value = 'all';
      sel.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await page.locator('#search-keyword').fill(REAL_Q);
    await page.waitForFunction(
      () => document.querySelectorAll('#search-results .search-result').length > 0,
      null, { timeout: 20000 });

    const first = await page.locator('#search-results .search-result').count();
    const status0 = (await page.locator('#search-status').textContent() || '').trim();
    expect(first).toBe(PAGE);
    const m = /已加载\s+(\d+)\/(\d+)\s*条/.exec(status0);
    expect(m, `状态行必须报「已加载 n/m 条」，实读：${status0}`).toBeTruthy();
    const total = Number(m[2]);
    expect(total).toBeGreaterThan(PAGE);   // 真数据必须真有第二页，判据才有意义

    // 直接问服务端「最后一页的最早一条是哪条」——这就是「最早一条」的权威读数。
    const lastOffset = Math.floor((total - 1) / PAGE) * PAGE;
    const raw = await fetch(
      `${GATEWAY}/api/search/chat?q=${encodeURIComponent(REAL_Q)}&kinds=message&limit=${PAGE}&offset=${lastOffset}`,
      { headers: { Authorization: `Bearer ${REAL_TOKEN}` } });
    const lastBody = await raw.json();
    const oldest = lastBody.hits[lastBody.hits.length - 1];
    expect(oldest, '服务端末页必须有行').toBeTruthy();

    const count = () => page.locator('#search-results .search-result').count();
    const deadline = Date.now() + 180000;
    let loaded = await count();
    while (loaded < total && Date.now() < deadline) {
      await page.evaluate(() => {
        const el = document.getElementById('search-results');
        if (el) el.scrollTop = el.scrollHeight;
      });
      await page.waitForTimeout(220);
      loaded = await count();
    }
    // eslint-disable-next-line no-console
    console.log(`[real] total=${total} loaded=${loaded} offsets=${offsets.length} 最早条目标识=${oldest.sid}:${oldest.ord}`);
    expect(loaded).toBe(total);
    await expect(page.locator(`#search-results .search-result[data-key="${oldest.sid}:${oldest.ord}"]`))
      .toHaveCount(1);
    const status = (await page.locator('#search-status').textContent() || '').trim();
    expect(status).toContain(`${total} 条结果`);
    await page.screenshot({ path: '/tmp/nb-search-paging/real-bottom.png', fullPage: false });
    expect(errors, errors.join('\n')).toEqual([]);
  });
});

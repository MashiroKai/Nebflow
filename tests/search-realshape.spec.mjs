// search-realshape.spec.mjs — 搜索面「真后端形状」集成腿
//（2026-10-20 搜索空结果修复批；作者报障「控制台搜消息记录零结果」的回归腿）。
//
// 为什么有这条腿：既有搜索家族 spec 全部走 4 条手工夹具（mobile-p2-search 的
// historyFixture 等）——键集合、字段在缺性、类型配比照不到真实数据路径。本次
// 报障的漏网机制正在于此：真实传输面上，中继隧道抖动窗把 /history 打成非 2xx
// （2026-10-20 16:50 实测：隧道断连，28 条在途 portal http cycle 同时
// "Output closed"，控制台收 502/504/截断体），而 fetchHistory 把一切非 2xx 吞
// 成 [] ⇒ 搜索面假装「0 条结果」。手工夹具永不发非 2xx ⇒ 这条路径零覆盖。
//
// 形状来源：在役网关（8080 实例）真实 API payload 原样抓取后**脱敏保形**——
//   · 每行的键集合、字段在缺性、类型、嵌套形状（attachments/fileChanges/
//     askUser.items/system.i18nKey 等）逐行保持真实原样（构建脚本校验：夹具
//     的每个键集合都必须在真实 payload 键集合普查中存在）；
//   · 字符串值替换为确定性占位文本，作者私有内容零进仓；
//   · 类型配比保持（systematic 1/8 抽样：ai/tool/user/system/askUser 五类
//     107 行，injected=true 注入行、带附件行、无 timestamp 行均在场）。
//   夹具：tests/fixtures/search-realshape/{history,sessions}.json
//
// Run: node <stand>/node_modules/@playwright/test/cli.js test tests/search-realshape.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { settlePanel } from './_mobile-panel-settle.mjs';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const FIX = join(dirname(fileURLToPath(import.meta.url)), 'fixtures', 'search-realshape');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const SID = 'realspec-root-session';
const KW = '样例关键词'; // 种植行关键词（一条真实非注入 user + 一条 ai + 一条 tool）

// 视口由各测试经 bootApp 的 setViewportSize 控制（桌面 1680×900 / 手机 375×812，
// 手机层激活判据 = matchMedia max-width:768px）；触面/配色文件级统一。
// 2026-10-24 CI 存量红分诊：桌面视口由 1400 提到 1680 —— 默认 boot 开 Canvas
// 面板（openPlugins 默认页）+ 侧栏展开把 #main 压到 ~528px，而 uifix「会话头中簇
// 收纳」批把 header-center 由纵排（内禀 177px）改横排紧凑（内禀 245px = 头像 28 +
// gap 6 + 名 177 + gap 6 + 记忆 28），碰撐藏链（main.js initHeaderResizeObserver
// v2，冻结 spec「仅自动隐藏」）实测放不下 search-btn（差 11px）⇒ 开机即藏、
// #search-btn click 永超时。链判定正确（设计行为：藏而非重叠），本 spec 测的是搜索面
// 真形状而非碰撞链 ⇒ 换 1680 桌面视口（实测 cut 4/4 零隐藏），断言面零改动。
test.use({ hasTouch: true, colorScheme: 'dark' });

/** 真实 payload 脱敏保形夹具（见文件头「形状来源」）。 */
const realSessions = JSON.parse(readFileSync(join(FIX, 'sessions.json'), 'utf8'));
const realHistory = JSON.parse(readFileSync(join(FIX, 'history.json'), 'utf8'));

/** 传输故障注入档：real | http502 | malformed | notfound。 */
let historyMode = 'real';

async function bootApp(page, { viewport } = {}) {
  if (viewport) await page.setViewportSize(viewport);
  await page.addInitScript(() => {
    localStorage.setItem('stand_token', 'e2e-token');
    localStorage.setItem('stand_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === `/api/sessions/${SID}/history`) {
      if (historyMode === 'http502') {
        return route.fulfill({ status: 502, contentType: 'application/json', body: '{"error":"bad gateway"}' });
      }
      if (historyMode === 'malformed') {
        // 截断体同族：200 + messages 键缺失（"Output closed" 半截响应的 API 面）。
        return route.fulfill({ status: 200, contentType: 'application/json', body: '{"total":0}' });
      }
      if (historyMode === 'notfound') {
        return route.fulfill({ status: 404, contentType: 'application/json', body: '{"error":"not found"}' });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(realHistory) });
    }
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions' || p.startsWith('/api/sessions?')) {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(realSessions) });
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
      sessions: realSessions.sessions,
      folders: realSessions.folders || [],
      activeId: SID,
    }));
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
    sendConfig();
    sendSessions();
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

/** 开面板（桌面 = 顶栏搜索键直开； morph 面族终态守护同 P2 腿）。 */
async function openSearchDesktop(page) {
  await page.locator('#search-btn').click();
  await expect(page.locator('#search-overlay')).toHaveClass(/on/);
  await settlePanel(page, { overlaySel: '#search-overlay', panelSel: '#search-modal' });
}

/** 开面板（手机 = 左列液态下拉 → 搜索键，floatController 观察路径播 morph）。 */
async function openSearchMobile(page) {
  await page.locator('.mh-dd[data-dd="left"] .mh-dd-hd').click();
  await page.locator('#search-btn').click();
  await expect(page.locator('#search-overlay')).toHaveClass(/on/);
  await settlePanel(page, { overlaySel: '#search-overlay', panelSel: '#search-modal' });
}

// ── T1 真形状关键词搜索（桌面）──────────────────────────────
// 真实形状数据（含 injected 注入行/无 ts 工具行/system·askUser 不可检索行）
// 上：三类种植行（user/ai/tool）全部命中，kind 词表 = data-kind 同款。
test('T1 real-shape keyword search hits user+ai+tool across real payload shapes (desktop)', async ({ page }) => {
  historyMode = 'real';
  await bootApp(page, { viewport: { width: 1680, height: 900 } });
  await openSearchDesktop(page);

  await page.locator('#search-keyword').fill(KW);
  await expect(page.locator('#search-status')).toHaveText(/^3 条结果/, { timeout: 8000 });

  const rows = page.locator('#search-results .search-result');
  await expect(rows).toHaveCount(3);
  const kinds = await rows.evaluateAll((els) => els.map((el) => el.getAttribute('data-kind')).sort());
  expect(kinds).toEqual(['ai', 'tool', 'user']); // 三类种植行全中 = 真形状三腿可检索

  // 工具行的真实形状字段（label 换行富串/input 为 JSON 字符串）进检索文本：
  const toolRow = page.locator('#search-results .search-result[data-kind="tool"]');
  await expect(toolRow.locator('.search-result-preview')).toContainText(KW);
});

// ── T2 浏览流（真形状）───────────────────────────────────────
// 打开即 browse（open-into-browse v3.1）：真实形状全量分页渲染，system/
// askUser 不可检索行被滤除（真实 payload 里它们占 6/107）。
test('T2 real-shape browse stream renders searchable rows only (desktop)', async ({ page }) => {
  historyMode = 'real';
  await bootApp(page, { viewport: { width: 1680, height: 900 } });
  await openSearchDesktop(page);

  // 真形状可检索行 = 107 - 4 system - 2 askUser = 101；首页 cap 100。
  await expect(page.locator('#search-status')).toHaveText(/^100 条结果/, { timeout: 8000 });
  const rows = page.locator('#search-results .search-result');
  await expect(rows).toHaveCount(50); // RENDER_BATCH 首批
  const kinds = new Set(await rows.evaluateAll((els) => els.map((el) => el.getAttribute('data-kind'))));
  for (const k of kinds) expect(['user', 'agent', 'ai', 'tool']).toContain(k);
});

// ── T3 传输诚实化（本次报障的回归腿）─────────────────────────
// 非 2xx / 截断体 ⇒ 「加载失败: … + 重试」钮（既有 showError 面），绝不伪装
// 「0 条结果」；404（会话已删）⇒ 合法空态，无错误面。
test('T3 transport failure surfaces error+retry, never fake zero (the reported bug)', async ({ page }) => {
  historyMode = 'http502';
  await bootApp(page, { viewport: { width: 1680, height: 900 } });
  await openSearchDesktop(page);

  await page.locator('#search-keyword').fill(KW);
  const status = page.locator('#search-status');
  await expect(status).toContainText('搜索失败', { timeout: 8000 });
  await expect(status).toContainText('502');
  await expect(status).not.toContainText('条结果');        // 不再假装 0 条
  await expect(status.locator('button')).toHaveCount(1);   // 重试钮在场

  // 重试腿：故障解除 ⇒ 同一面板内直接恢复（真实使用 = 隧道回连后点重试）。
  historyMode = 'real';
  await status.locator('button').click();
  await expect(status).toHaveText(/^3 条结果/, { timeout: 8000 });

  // 截断体（200 + messages 缺失）同罪同罚。
  historyMode = 'malformed';
  await page.locator('#search-keyword').fill('占位');
  await expect(status).toContainText('搜索失败', { timeout: 8000 });
  await expect(status).toContainText('malformed history payload');
  await expect(status).not.toContainText('条结果');

  // 404 = 会话已删 ⇒ 合法空（无错误面、无重试钮）。
  historyMode = 'notfound';
  await page.locator('#search-keyword').fill(KW);
  await expect(status).toHaveText(/^0 条结果/, { timeout: 8000 });
  await expect(status.locator('button')).toHaveCount(0);
});

// ── T4 手机面真形状 + 角色筛选缝（P2 裁定②面在真形状上仍成立）─
test('T4 mobile P2 face on real shapes: main-session search + kind filter narrow', async ({ page }) => {
  historyMode = 'real';
  await bootApp(page, { viewport: { width: 375, height: 812 } });
  expect(await page.evaluate(() => document.body.classList.contains('mobile-layer'))).toBe(true);
  await openSearchMobile(page);

  await page.locator('#search-keyword').fill(KW);
  await expect(page.locator('#search-status')).toHaveText(/^主会话 · 3 条结果/, { timeout: 8000 });
  const rows = page.locator('#search-results .search-result');
  await expect(rows).toHaveCount(3);

  // 角色行（P2 二筛选行）在真形状数据上收窄：工具档 ⇒ 仅 tool 种植行。
  await page.locator('#mh-search-kind').selectOption('tool');
  await expect(rows).toHaveCount(1, { timeout: 8000 });
  await expect(rows.first()).toHaveAttribute('data-kind', 'tool');
  await page.locator('#mh-search-kind').selectOption('');
  await expect(rows).toHaveCount(3, { timeout: 8000 });
});

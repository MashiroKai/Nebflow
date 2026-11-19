// mobile-conv-search-full.spec.mjs — 会话档（好友/群/设备）聊天记录检索的**分段续查**
// 验收（检索全覆盖批 2026-11-07）。
//
// 作者报障（2026-11-07 12:26）：「搜索聊天记录 为什么不是搜全部的内容 可以分段显示
// 但是我要能查看呀」。会话档的旧实现有两处静默截断（mobile/socialSearch.js）：
//   · PAGE_CAP=20 × PAGE_SIZE=50 = 每会话只看前 1000 条消息就停；
//   · MATCH_CAP_PER_CONV=30 = 每会话最多收 30 条命中，其余直接丢。
// ⇒ 关键词只出现在「第 1000 条之后」的会话，搜索结果永远是 0 条，且没有任何入口
// 能往下看（不是「空」，是「没扫到」）。
//
// 改后契约（本 spec 钉住）：段尾给「加载更多（本会话）」，点它从游标续扫下一段；
// 命中数不再设上限；扫尽即摘钮。数据面 = 1100 条消息的单会话，关键词只在
// id 1050–1055（第一段 1..1000 扫不到）。
//
// Run: node node_modules/@playwright/test/cli.js test tests/mobile-conv-search-full.spec.mjs
import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { dockTo } from './_mobile-dock-nav.mjs';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const SID = 'conv-search-root';
const CONV = 'c-big';
const MSG_TOTAL = 1100;
const HIT_IDS = [1050, 1051, 1052, 1053, 1054, 1055];   // 只在「第 1000 条之后」

test.use({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: 'dark' });

/** 静态面 + WS 夹具 + 好友域客户端 mock 种子（好友域开、单会话 1100 条消息）。 */
async function bootApp(page) {
  await page.addInitScript((cfg) => {
    const { total, hits } = cfg;
    const NOW = Date.now();
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('fm_api_mock', '1');
    const messages = [];
    for (let id = 1; id <= total; id++) {
      const hit = hits.indexOf(id) >= 0;
      messages.push({
        id,
        senderId: id % 2 ? 'u-k' : 'me',
        kind: 'text',
        body: hit ? `归档件第 ${hits.indexOf(id) + 1} 号：这条只在第二段扫得到` : `日常消息 ${id}`,
        createdAt: NOW - (total - id) * 60e3,
      });
    }
    localStorage.setItem('fm_api_mock_seed', JSON.stringify({
      self: { userId: 'me', neblinkId: 'me@example.com', name: '我自己', avatarUrl: '' },
      users: [],
      friends: [{ userId: 'u-k', neblinkId: 'kaifriend', name: '阿凯', avatarUrl: '', since: NOW - 86400e3 }],
      incoming: [], outgoing: [],
      conversations: [{
        conversationId: cfg.convId,
        friend: { userId: 'u-k', neblinkId: 'kaifriend', name: '阿凯', avatarUrl: '' },
        lastMessage: messages[messages.length - 1],
        unreadCount: 0,
      }],
      messages: { [cfg.convId]: messages },
      groups: [], groupInvites: [], groupMembers: {},
    }));
  }, { convId: CONV, total: MSG_TOTAL, hits: HIT_IDS });

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
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
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({
        status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file),
      });
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
  await page.goto('http://localhost:8390/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.waitForTimeout(400);
}

function watchErrors(page) {
  const errors = [];
  const NOISE = /Failed to load resource|An unknown error occurred when fetching the script/;
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error' && !NOISE.test(m.text())) errors.push(`console: ${m.text()}`); });
  return errors;
}

test('C1 会话档分段续查：第一段扫不到的命中，点「加载更多（本会话）」能查到', async ({ page }) => {
  const errors = watchErrors(page);
  await bootApp(page);
  await dockTo(page, 'messages');

  const sel = '.mobile-page[data-page="messages"]';
  await page.locator(`${sel} .mh-search-input`).fill('归档');

  // 第一段（1..1000 条）扫完：命中 0 条，但会话未扫完 ⇒ 段尾必须有「加载更多」。
  const more = page.locator(`${sel} .mh-sr-more`);
  await expect(more).toBeVisible({ timeout: 20000 });
  await expect(more).toHaveText('完整扫描消息（可能较慢）');
  await more.click();
  await expect(more).toHaveText('加载更多（本会话）', { timeout: 20000 });
  expect(await page.locator(`${sel} .mh-sr-row`).count()).toBe(0);
  await expect(page.locator(`${sel} .mh-sr-group`)).toContainText('阿凯');

  // 续段：第二段（1001..1100）扫到 6 条命中 ⇒ 行上屏、计数更新、扫尽摘钮。
  await more.click();
  await expect(page.locator(`${sel} .mh-sr-row`)).toHaveCount(HIT_IDS.length, { timeout: 20000 });
  await expect(page.locator(`${sel} .mh-sr-group`)).toContainText(`${HIT_IDS.length} 条结果`);
  await expect(page.locator(`${sel} .mh-sr-more`)).toHaveCount(0);
  await expect(page.locator(`${sel} .mh-sr-snippet`).first()).toContainText('归档件第 1 号');
  const status = (await page.locator(`${sel} .mh-search-status`).textContent() || '').trim();
  expect(status).toContain(`${HIT_IDS.length} 条结果`);
  await page.screenshot({ path: '/tmp/nb-search-paging/conv-continue-375x812.png' });
  expect(errors, errors.join('\n')).toEqual([]);
});

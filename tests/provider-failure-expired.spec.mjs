// provider-failure-expired.spec.mjs — 失败卡「恢复即过期」闸（项 3 · 2026-11-23 作者令收尾）。
//
// 断言面（真实 WS 桩 + 真实渲染，驱动 main.js 的 delegateFreezeDigest / delegateWoken 处理器）：
//   E1 失败摘要到达（两会话，均冻结）⇒ 卡存在、data-expired-count=0、无过期类。
//   E2 恢复信号只到其中一个会话 ⇒ data-expired-count=1，卡仍存活（非整卡过期）。
//   E3 两个会话都恢复 ⇒ 卡带 llm-failure-card--expired，标题为「已恢复 · 失败状态已过期」，
//      「等待恢复中」文案撤掉，按钮 .is-expired 且 aria-disabled=true。
//   E4 负对照：行 frozen 从未被显式清零（缺字段）时，即使摘要在场也不得判过期（保守，不误灰）。
//
// Run: npx playwright test tests/provider-failure-expired.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const ROOT_SID = 'pfe-root-session';
const CHILD_A = 'pfe-child-a';
const CHILD_B = 'pfe-child-b';
const CARD = '[data-card-type="providerFailureGroup"]';

async function bootApp(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/') || p.startsWith('/agents/')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'nf' });
    }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList', sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: ROOT_SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

function inject(page, obj) {
  return page.evaluate(async (o) => {
    const s = (await import('/js/state.js')).default;
    s.ws.onmessage({ data: JSON.stringify(o) });
  }, obj);
}

function rootStart(agentId) {
  return {
    type: 'agentStart', sessionId: ROOT_SID, rootSessionId: ROOT_SID,
    agentId, nodeSessionId: agentId, name: agentId, taskDescription: '', status: 'Processing',
  };
}

function digest(childIds) {
  return {
    type: 'delegateFreezeDigest', total: childIds.length, recoverable: childIds.length, needsHuman: 0, windowMs: 0,
    items: childIds.map((id) => ({ sessionId: id, parentSessionId: ROOT_SID, title: `任务 ${id}`, detail: '', autoResumes: 0, autoResumesExhausted: false })),
    providerGroups: [{
      provider: 'IricalClaude', presentation: 'availability', reason: 'provider unavailable',
      sessionIds: childIds,
    }],
  };
}

async function seedRows(page, { frozen }) {
  for (const id of [CHILD_A, CHILD_B]) {
    await inject(page, rootStart(id));
    if (frozen) {
      await inject(page, { type: 'delegateFrozen', sessionId: id, rootSessionId: ROOT_SID, frozenSince: Date.now(), frozenReason: 'provider' });
    }
  }
}

const card = (page) => page.locator(CARD).first();

test('E1 摘要到达，两会话均冻结 ⇒ 卡存活、无过期标记', async ({ page }) => {
  await bootApp(page);
  await seedRows(page, { frozen: true });
  await inject(page, digest([CHILD_A, CHILD_B]));
  await expect(card(page)).toHaveCount(1);
  await expect(card(page)).toHaveAttribute('data-expired-count', '0');
  await expect(card(page)).not.toHaveClass(/llm-failure-card--expired/);
});

test('E2 恢复信号只到其一 ⇒ 计数 1，卡仍存活（非整卡过期）', async ({ page }) => {
  await bootApp(page);
  await seedRows(page, { frozen: true });
  await inject(page, digest([CHILD_A, CHILD_B]));
  await inject(page, { type: 'delegateWoken', sessionId: CHILD_A, rootSessionId: ROOT_SID, status: 'Processing' });
  await expect(card(page)).toHaveAttribute('data-expired-count', '1');
  await expect(card(page)).not.toHaveClass(/llm-failure-card--expired/);
  await expect(card(page).locator('.option-cancel.is-expired')).toHaveCount(1);
});

test('E3 两会话都恢复 ⇒ 整卡转灰、标题「已恢复」、撤掉等待文案', async ({ page }) => {
  await bootApp(page);
  await seedRows(page, { frozen: true });
  await inject(page, digest([CHILD_A, CHILD_B]));
  await inject(page, { type: 'delegateWoken', sessionId: CHILD_A, rootSessionId: ROOT_SID, status: 'Processing' });
  await inject(page, { type: 'delegateWoken', sessionId: CHILD_B, rootSessionId: ROOT_SID, status: 'Processing' });
  await expect(card(page)).toHaveClass(/llm-failure-card--expired/);
  await expect(card(page).locator('.llm-failure-title')).toContainText('已恢复');
  await expect(card(page).locator('.llm-failure-summary')).toHaveCount(0);
  await expect(card(page).locator('.option-cancel.is-expired')).toHaveCount(2);
  await expect(card(page).locator('.option-cancel').first()).toHaveAttribute('aria-disabled', 'true');
});

test('E4 负对照：行 frozen 从未显式清零（缺字段）⇒ 摘要在场也不判过期', async ({ page }) => {
  await bootApp(page);
  await seedRows(page, { frozen: false });
  await inject(page, digest([CHILD_A, CHILD_B]));
  await expect(card(page)).toHaveAttribute('data-expired-count', '0');
  await expect(card(page)).not.toHaveClass(/llm-failure-card--expired/);
});

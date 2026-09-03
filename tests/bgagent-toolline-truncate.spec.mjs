// bgagent-toolline-truncate.spec.mjs — bg-agent 弹窗 footer 工具调用状态行截断验收
// (2026-09-03 toolline truncate fix)。
//
// 现象：popup footer 的 .fa-phase（"Using tool: <label>"）带后端无上限的参数
// 摘要（summarizeToolCall 原样内嵌参数值），且旧 CSS 给了 flex-shrink:0 → 长参数
// 把右侧管理簇（uptime/retries/stop/retry，margin-left:auto + flex-shrink:0）
// 推出被 overflow:hidden 裁剪的 modal 区。
//
// 修复契约（断言锁定）：
//  - .fa-phase 渲染层中段截断（truncateMiddle 96/24）→ 渲染文本长度有硬上限；
//  - 数据层 meta.toolLabel 保留完整参数 → title/tooltip 展示全文；
//  - .fa-phase min-width:0 + nowrap + hidden + ellipsis → 任何视口下单行不撑破；
//  - 右侧 .fa-manage 任何参数长度下不被压缩/挤出（boundingBox 完整落在 footer 内）；
//  - footer 单行高度恒定（长短参数高度相等）。
//
// 自包含：route 拦截服务 src/main/resources/web + mock WS 握手，无后端、无 sbt。
// Run: node node_modules/@playwright/test/cli.js test tests/bgagent-toolline-truncate.spec.mjs

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

const ROOT_SID = 'e2e-root-session';
const AGENT_SID = 'delegate-trunc'; // isBgAgentId 认可的前缀

/** 560+ 字符 Edit 参数（真实形态：绝对路径超长场景）。 */
function longLabel() {
  const deep = Array.from({ length: 40 }, (_, i) => `dir${i}withsomechars`).join('/');
  return `Edit(/Users/dev/${deep}/AgentCore.scala)`;
}

async function bootApp(page) {
  await page.addInitScript(() => localStorage.setItem('nebflow_token', 'e2e-token'));

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      const body = readFileSync(file);
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body });
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
      sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: ROOT_SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let msg;
      try { msg = JSON.parse(raw); } catch { return; }
      if (msg.type === 'getConfig') sendConfig();
      else if (msg.type === 'getSessions' || msg.type === 'listSessions') sendSessions();
      else if (msg.type === 'getHistory') {
        ws.send(JSON.stringify({
          type: 'historyPage', sessionId: msg.sessionId, messages: [], hasMore: false, offset: 0,
        }));
      }
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

/** 打开 bg-agent 弹窗（真实 openStepPopup 管线）并注入 agentStart。 */
async function openPopup(page) {
  await inject(page, {
    type: 'agentStart',
    agentId: AGENT_SID,
    name: 'Coder',
    agentType: 'delegate',
    taskDescription: 'toolline truncate demo',
    nodeSessionId: AGENT_SID,
    rootSessionId: ROOT_SID,
    sessionId: ROOT_SID,
  });
  await page.evaluate(async (sid) => {
    const m = await import('/js/bgAgentPopup.js');
    m.openStepPopup(sid, 'Coder', 'toolline truncate demo');
  }, AGENT_SID);
  await page.waitForSelector('#bgagent-footer .fa-manage', { timeout: 5000 });
}

function toolStart(page, label) {
  return inject(page, {
    type: 'agentToolStart',
    agentId: AGENT_SID,
    label,
    nodeSessionId: AGENT_SID,
    rootSessionId: ROOT_SID,
    sessionId: ROOT_SID,
  });
}

function footerMetrics(page) {
  return page.evaluate(() => {
    const footer = document.querySelector('#bgagent-footer');
    const phase = footer.querySelector('.fa-phase');
    const manage = footer.querySelector('.fa-manage');
    const stop = footer.querySelector('.fa-mgmt-stop');
    const fr = footer.getBoundingClientRect();
    const mr = manage.getBoundingClientRect();
    return {
      footerH: footer.offsetHeight,
      footerScrollW: footer.scrollWidth,
      footerClientW: footer.clientWidth,
      phaseText: phase.textContent,
      phaseTitle: phase.title,
      phaseH: phase.offsetHeight,
      phaseRight: phase.getBoundingClientRect().right,
      phaseWs: getComputedStyle(phase).whiteSpace,
      phaseOv: getComputedStyle(phase).overflowX,
      phaseTe: getComputedStyle(phase).textOverflow,
      phaseShrink: getComputedStyle(phase).flexShrink,
      manageRight: mr.right,
      manageWidth: mr.width,
      footerRight: fr.right,
      stopWidth: stop.getBoundingClientRect().width,
    };
  });
}

test('short label: phase renders verbatim (no truncation regression)', async ({ page }) => {
  await bootApp(page);
  await openPopup(page);

  const short = 'Edit(src/main/scala/App.scala)';
  await toolStart(page, short);
  const m = await footerMetrics(page);
  expect(m.phaseText).toBe(`Using tool: ${short}`);
  expect(m.phaseText).not.toContain('…');
  // tooltip 短文本也带全文（§7 title 全文）
  expect(m.phaseTitle).toBe(`Using tool: ${short}`);
});

test('560+ char label: single-line footer, truncated render, right cluster intact', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);
  await openPopup(page);

  // 基线：短参数下的 footer 高度 + manage 状态
  const short = 'Edit(/tmp/a.scala)';
  await toolStart(page, short);
  const base = await footerMetrics(page);

  // 超长参数
  const label = longLabel();
  expect(label.length).toBeGreaterThan(500);
  await toolStart(page, label);
  const m = await footerMetrics(page);

  // ① 单行不换行不撑破。判据注明：ellipsis 盒的 scrollWidth 按定义包含被
  // overflow 裁剪的内容（即被省略号替代的部分），故「容器不撑破」用块高恒定
  // （基线=短参数态）+ footer 级无横向溢出 + phase 盒右缘不越 footer 三条判定；
  // CSS nowrap/hidden/ellipsis 三件套保证任何视口下同为单行。
  expect(m.phaseH).toBe(base.phaseH);
  expect(m.phaseWs).toBe('nowrap');
  expect(m.phaseOv).toBe('hidden');
  expect(m.phaseTe).toBe('ellipsis');
  expect(m.footerScrollW).toBeLessThanOrEqual(m.footerClientW + 1);
  expect(m.phaseRight).toBeLessThanOrEqual(m.footerRight + 0.5);

  // ② 截断生效：渲染文本 < 原文，含省略号，硬上限 96+12
  const full = `Using tool: ${label}`;
  expect(m.phaseText).not.toBe(full);
  expect(m.phaseText.length).toBeLessThan(full.length);
  expect(m.phaseText.length).toBeLessThanOrEqual(96 + 'Using tool: '.length);
  expect(m.phaseText).toContain('…');
  expect(m.phaseText.startsWith('Using tool: Edit(/Users/dev/')).toBe(true);

  // ③ 数据层完整：tooltip 携带全文（meta.toolLabel 未被动过）
  expect(m.phaseTitle).toBe(full);

  // ④ 右侧状态簇完整可见：不被挤出、不被压缩
  expect(m.manageWidth).toBeGreaterThan(0);
  expect(m.manageRight).toBeLessThanOrEqual(m.footerRight + 0.5);
  expect(m.stopWidth).toBe(28); // fa-mgmt-btn 28px 固定几何未被压缩

  // ⑤ footer 单行高度恒定
  expect(m.footerH).toBe(base.footerH);

  expect(pageErrors).toEqual([]);
});

test('truncateMiddle pure function: cap, head/tail preservation, edge cases', async ({ page }) => {
  await bootApp(page);
  const r = await page.evaluate(async () => {
    const u = await import('/js/utils.js');
    return {
      short: u.truncateMiddle('abc', 96, 24),
      exact: u.truncateMiddle('x'.repeat(96), 96, 24),
      long: u.truncateMiddle('h'.repeat(200) + 'T'.repeat(24), 96, 24),
      tiny: u.truncateMiddle('y'.repeat(300), 5, 24),
      nullish: u.truncateMiddle(null),
    };
  });
  expect(r.short).toBe('abc');
  expect(r.exact).toBe('x'.repeat(96));
  expect(r.long.length).toBe(96);
  expect(r.long.startsWith('h'.repeat(71))).toBe(true);
  expect(r.long.endsWith('T'.repeat(24))).toBe(true);
  expect(r.long).toContain('…');
  expect(r.tiny).toBe('yyyyy');
  expect(r.nullish).toBe('');
});

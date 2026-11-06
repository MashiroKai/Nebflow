// delegate-standby-shots.mjs — delegate 状态矩阵批（2026-11-01）视觉证据。
//
// 验收③：任务行/详情卡显「待命」态（standbySince 透出）——
//   ① complete×standby 行：badge =「待命（等作者）」，tooltip = 申报的「在等什么」；
//   ② 候合并对照行：badge =「等待合并轮 · 队列段」（既有形态不回归）；
//   ③ 快照复原脸：activeAgents 行带 standbySince ⇒ 刷新后徽标复原（delegateWaiting
//      实时帧的恢复脸）。
//
// 用法：node tests/delegate-standby-shots.mjs
// 产出 = evidence/20261101_delegate-standby-matrix__delegate-nebflow-c6c62891/shots/*.png
import { chromium } from '/Users/kaiyu/Claude code/Stand/node_modules/playwright/index.mjs';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = process.env.WEB_DIR
  ? process.env.WEB_DIR
  : join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = join(HERE, '..', 'evidence', '20261101_delegate-standby-matrix__delegate-nebflow-c6c62891', 'shots');
mkdirSync(OUT, { recursive: true });
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const ROOT_SID = 'sbm-root-session';
const T0 = Date.now();

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};

async function makePage(browser, colorScheme, mobile) {
  const page = await (await browser.newContext({
    viewport: mobile ? { width: 375, height: 812 } : { width: 1280, height: 800 },
    colorScheme, deviceScaleFactor: 2,
  })).newPage();
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    if (p.startsWith('/agents/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(normalize(WEB))) return route.fulfill({ status: 403, body: 'forbidden' });
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'nf' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: ROOT_SID,
    }));
    ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  return page;
}

/** 种矩阵批场景：待命行（快照 standbySince 复原脸）+ 候合并对照行 + 活运行对照行。 */
async function seedScene(page) {
  await page.evaluate(({ sid, t0 }) => import('/js/state.js').then((m) => {
    m.default.sessionBgAgents[sid] = {
      // ① complete×standby：快照行 standbySince 在场 ⇒ 徽标「待命（等作者）」随刷新
      //    复原（waiting.detail = 申报的「在等什么」——delegateWaiting 实时帧写入形）。
      'delegate-standby-1': { name: 'nebflow', kind: 'Delegate', sessionId: 'delegate-standby-1',
        status: 'Waiting', startedAt: t0 - 1800_000,
        task: 'delegate 状态矩阵批（作者令 2026-11-01：完成判定改状态矩阵）', parentSessionId: sid,
        standbySince: t0 - 240_000,
        waiting: { mode: 'standby', detail: '等作者下一条 Mail 再续——已申报 delegate_report(standby)' } },
      // ② 候合并对照：既有「等待合并轮」形态不回归。
      'delegate-merge-2': { name: 'nebflow', kind: 'Delegate', sessionId: 'delegate-merge-2',
        status: 'Waiting', startedAt: t0 - 900_000,
        task: '弹窗退场批（重启后新派，作者 10-31 15:13 原令三件）', parentSessionId: sid },
      // ③ 活运行对照：无徽标（矩阵未申报腿的行外观零变化——提醒面承担可见性）。
      'delegate-running-3': { name: 'nebflow', kind: 'Delegate', sessionId: 'delegate-running-3',
        status: 'Processing', startedAt: t0 - 300_000, currentTool: 'Bash("sbt test")', parentSessionId: sid },
    };
    m.default.updateBgAgentIndicator();
  }), { sid: ROOT_SID, t0: T0 });
}

const browser = await chromium.launch();

for (const colorScheme of ['light', 'dark']) {
  for (const mobile of [false, true]) {
    const label = `${mobile ? 'mobile-375' : 'desktop'}-${colorScheme}`;
    const page = await makePage(browser, colorScheme, mobile);
    const errors = [];
    page.on('pageerror', (e) => errors.push(e.message));
    page.on('console', (msg) => { if (msg.type() === 'error') errors.push(msg.text()); });
    await seedScene(page);
    // JS click（bg-panel harness 同款）：手机端 header 自适应会把指示器 priority-hide，
    // playwright 可见性点击会超时——JS click 绕过（面板照常渲染）。
    await page.evaluate(() => document.getElementById('bgagent-indicator').click());
    await page.waitForTimeout(400);

    const checks = await page.evaluate(() => {
      const c = document.getElementById('bg-dropdown');
      const row = c.querySelector('[data-node-session-id="delegate-standby-1"]');
      const mergeRow = c.querySelector('[data-node-session-id="delegate-merge-2"]');
      const runRow = c.querySelector('[data-node-session-id="delegate-running-3"]');
      const badge = row?.querySelector('.bg-task-waiting');
      const mergeBadge = mergeRow?.querySelector('.bg-task-waiting');
      return {
        standbyBadge: badge?.textContent || '',
        standbyTip: badge?.getAttribute('title') || '',
        mergeBadge: mergeBadge?.textContent || '',
        runBadge: runRow?.querySelector('.bg-task-waiting')?.textContent || '',
      };
    });
    ok(`${label}: complete×standby 行 badge =「待命（等作者）」`, checks.standbyBadge.includes('待命'), `got: "${checks.standbyBadge}"`);
    ok(`${label}: tooltip = 申报的「在等什么」`, checks.standbyTip.includes('等作者下一条 Mail'), `got: "${checks.standbyTip}"`);
    ok(`${label}: 候合并对照行保持「等待合并轮」`, checks.mergeBadge.includes('等待合并轮'), `got: "${checks.mergeBadge}"`);
    ok(`${label}: 活运行行零等待徽标`, checks.runBadge === '', `got: "${checks.runBadge}"`);
    ok(`${label}: 零 console 错误`, errors.length === 0, errors.slice(0, 3).join(' | '));

    await page.screenshot({ path: join(OUT, `${label}-standby-badge.png`), fullPage: false });
    page.close();
  }
}

// 详情卡（行 hover 弹窗 / 详情视图）形态：waiting detail 进 title 属性已在上方断言；
// 另补一张行级特写（桌面 light）——待命行 + tooltip 悬停态。
{
  const page = await makePage(browser, 'light', false);
  await seedScene(page);
  await page.evaluate(() => document.getElementById('bgagent-indicator').click());
  await page.waitForTimeout(400);
  const row = page.locator('[data-node-session-id="delegate-standby-1"]');
  await row.hover();
  await page.waitForTimeout(300);
  await row.screenshot({ path: join(OUT, 'desktop-light-standby-row-closeup.png') });
  page.close();
}

await browser.close();
console.log(failures === 0 ? 'ALL PASS' : `FAILURES: ${failures}`);
process.exit(failures === 0 ? 0 : 1);

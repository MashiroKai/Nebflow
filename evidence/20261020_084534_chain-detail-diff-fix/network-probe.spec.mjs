// network-probe.spec.mjs — ⑫B chain-detail-diff 修复的 §四.1 运行时复现探针
// （非验收件：只记录事实，零断言；同一探针在修复前/后各跑一次，产出
// before/ 与 after/ 的 Network 对照 JSON + 详情面板截图）。
//
// 场景 = 审计报告 §四.1：委托链 L1 → 点 completed flow-step 卡 → 记录全部
// /file-changes 网络调用（URL + 状态码）与 Changes 段是否挂载。
// 运行：CDD_OUT=<json 落盘路径> node node_modules/@playwright/test/cli.js test \
//        evidence/20261020_084534_chain-detail-diff-fix/network-probe.spec.mjs
import { test } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const ROOT_SID = 'cdd-probe-session';
const T0 = Date.now();
const DIFF_TEXT = [
  '@@ -3,3 +3,4 @@ context around the change',
  ' unchanged line',
  '-removed line',
  '+added line',
  '+added line two',
  ' tail context',
].join('\n');
const REPORT = {
  nodeId: 's1', generatedAt: T0, root: '/w',
  files: [{ path: 'src/main/resources/web/js/flowMapArchive.js', changeType: 'modified', added: 2, removed: 1, source: 'git', diffText: DIFF_TEXT }],
};

test('probe: delegate chain completed-node detail network face', async ({ page }, testInfo) => {
  const fc = [];
  page.on('response', async (res) => {
    if (res.url().includes('/file-changes')) {
      fc.push({ url: decodeURIComponent(res.url()), status: res.status() });
    }
  });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      const m = p.match(/^\/api\/projects\/([^/]+)\/flow-map\/nodes\/([^/]+)\/file-changes$/);
      if (m) {
        const project = decodeURIComponent(m[1]);
        if (project === 'Nebflow') {
          return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(REPORT) });
        }
        return route.fulfill({ status: 404, contentType: 'application/json', body: '{"error":"no such project"}' });
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
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: ROOT_SID,
    }));
    sendConfig(); sendSessions();
    ws.onMessage((raw) => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });

  const row = {
    taskNo: '41', delegateId: 'delegate-nebflow-a41', title: '链甲任务',
    executor: 'nebflow', status: 'running', external: false, project: 'Nebflow',
    createdAt: T0 - 60_000,
  };
  const chain = {
    taskId: '41', delegateId: 'delegate-nebflow-a41',
    nodes: [
      { id: 'delegate-nebflow-a41', kind: 'delegate', label: '#41', description: '', status: 'running', deps: [], executor: '' },
      { id: 's1', kind: 'flow-step', label: '步骤一', description: '第一步描述', status: 'completed', deps: [], executor: '' },
    ],
    worktree: { branch: 'delegate/task-41', worktree: '/wt/41', cwd: '/wt/41', baseWorkspace: '/w', baseBranch: 'main', baseSha: 'abc123', plugins: [], allocation: '' },
    verdict: null, mergeQueue: [], frames: [],
  };
  await page.evaluate(async (fx) => {
    const stub = await import('/js/delegateChainStub.js');
    stub.__setFixture(fx);
  }, { rows: [row], chains: [chain], children: {} });
  await page.evaluate(async () => {
    const { openDelegateChainTask } = await import('/js/delegateChainView.js');
    await openDelegateChainTask('41', {
      taskNo: '41', delegateId: 'delegate-nebflow-a41', title: '链甲任务',
      status: 'running', executor: 'nebflow', project: 'Nebflow',
    });
  });
  await page.waitForFunction(
    () => !!document.querySelector('.canvas-tab-pane[data-delegate-mode="task"] .delegate-flowmap-host .fm-node'),
    null, { timeout: 10000 },
  );
  await page.locator('.canvas-tab-pane[data-delegate-mode="task"] .fm-node[data-node-id="s1"]').click();
  await page.waitForSelector('.fm-detail.open', { timeout: 5000 });
  await page.waitForTimeout(800); // 惰性拉取窗

  const sectionMounted = await page.evaluate(() => {
    const el = document.querySelector('.fm-detail .nf-filechanges');
    if (!el) return false;
    const row = el.querySelector('.nf-fc-path');
    return row ? row.textContent : true;
  });
  const shot = `${testInfo.outputDir}/detail.png`;
  await page.locator('.fm-detail').screenshot({ path: shot });

  const out = {
    scenario: 'delegate chain L1 → completed flow-step node detail (report §四.1)',
    fileChangesRequests: fc,
    changesSectionMounted: sectionMounted,
    screenshot: shot,
    capturedAt: new Date().toISOString(),
  };
  const dest = process.env.CDD_OUT;
  if (dest) writeFileSync(dest, JSON.stringify(out, null, 2));
  console.log('[cdd-probe] ' + JSON.stringify(out, null, 2));
});

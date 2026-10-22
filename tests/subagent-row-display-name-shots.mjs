// subagent-row-display-name-shots.mjs — 子智能体面板行显示名修复批（2026-10-22 作者令）
// 「为什么现在还是老是会有一个收缩过程，先是一个长的名词，然后换成这个短的」
// 验收证据：delegate 行显示名全生命周期 = 批次标题/任务描述首行；sessionId 降为
// title tooltip 次要信息；恢复脸（快照/重启）不退化。
//
// 用法：
//   MODE=before node tests/subagent-row-display-name-shots.mjs   # 修前复现（断言按退化形翻转）
//   node tests/subagent-row-display-name-shots.mjs               # 修后验收（全 PASS）
//
// 场景：
//   S1 收缩触发点：live 长名行（agentStart：name=executor + task=批次标题）→
//      开面板（getActiveAgents 快照回填）被覆盖 → 修前退化成 sessionId，修后仍批名。
//   S2 重启恢复脸：清本地行（模拟重启后无 live 帧）→ 纯快照恢复（agentName=批名
//      后端修复形 + agentName=sessionId 存量退化形）→ 两行均批名（provider 镜像兜底）。
//   S3 密度：8 批并行，每行一眼可辨（各自长批名首行，无一行裸 id）。
import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = join(HERE, '..', 'evidence', '20261022_subagent-row-display-name__delegate-nebflow-6aae2fd2', 'shots');
mkdirSync(OUT, { recursive: true });
const MODE = process.env.MODE === 'before' ? 'before' : 'after';
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
};
const ROOT_SID = 'sn-root-session';

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};

const TITLES = [
  '【delegate 监督链自动恢复机制批】（作者令，引擎机制补全）',
  '【canvas 顶部胶囊居中+材质+动画节奏批】（重派——前会话被机器重启杀死）',
  '【wiki 图谱侧栏改造批】（左缘停靠 + 折叠态 + 窄宽降级）',
  '【对话层活性流式批】（长回合活动流 + 插话即时确认 + 断连横幅）',
  '【diff 摘要 chip 右对齐微调批】（作者回访令）',
  '【attach 附件条折叠批】（多附件溢出收纳 + 计数徽标）',
  '【topband 裙摆退役批】（顶栏装饰条移除 + 回归清扫）',
  '【任务列表行重启对账批】（boot 孤儿收殓 + 打断旗徽标）',
];
const SIDS = TITLES.map((_, i) => `delegate-nebflow-s${String(i + 1).padStart(8, '0')}`);

const rowOf = (i) => ({
  taskNo: String(730 + i), delegateId: SIDS[i], title: TITLES[i],
  executor: 'nebflow', status: 'running', external: false, project: 'stand',
  orchestrationMode: 'single', archivedAt: null,
  mergeQueue: null, mergeQueuePos: null, createdAt: Date.now() - 300_000,
});
const snapEntry = (i, agentName) => ({
  sessionId: SIDS[i], agentId: SIDS[i], agentName,
  rootSessionId: ROOT_SID, kind: 'Delegate', task: '',
  status: 'Processing', startedAt: Date.now() - 300_000, retryCount: 0,
  project: 'stand', taskId: String(730 + i), taskTitle: TITLES[i],
});
const liveStart = (i) => ({
  type: 'agentStart', sessionId: ROOT_SID, rootSessionId: ROOT_SID,
  agentId: SIDS[i], nodeSessionId: SIDS[i],
  name: 'nebflow', taskDescription: `${TITLES[i]}\n\n## 诊断方向\n多行任务正文不应上屏`,
});

const browser = await chromium.launch();

async function boot() {
  const page = await (await browser.newContext({
    viewport: { width: 480, height: 640 }, deviceScaleFactor: 2,
  })).newPage();
  page.on('pageerror', (e) => { console.log('PAGEERROR', e.message); failures++; });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    let p = decodeURIComponent(new URL(route.request().url()).pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      // provider 镜像面（DelegateRegistry.Record.title 的同源 REST）——真实挂行夹具。
      if (p === '/api/chains' || p === '/api/chains-v2') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ rows: [] }),
        });
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
    ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: ROOT_SID,
    }));
    ws.onMessage((raw) => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'getConfig') ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') {
        ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: ROOT_SID }));
      }
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(300);
  return page;
}

const inject = (page, obj) => page.evaluate(async (o) => {
  const s = (await import('/js/state.js')).default;
  s.ws.onmessage({ data: JSON.stringify(o) });
}, obj);

const setRows = (page, rows) => page.evaluate(async (rs) => {
  const stub = await import('/js/delegateChainStub.js');
  stub.__setFixture({ rows: rs });
}, rows);

const openPanel = async (page) => {
  await page.waitForSelector('#bgagent-indicator:not(.hidden)', { timeout: 8000 });
  await page.click('#bgagent-indicator');
  await page.waitForSelector('#bgagent-dropdown:not(.hidden)', { timeout: 8000 });
};

const headName = (page, i) =>
  page.locator(`#bgagent-dropdown .bg-task-row-head[data-node-session-id="${SIDS[i]}"] .bg-task-name`);

// ── S1：收缩触发点（live 长名 → 开面板快照覆盖）────────────────────────────
{
  const page = await boot();
  await setRows(page, [rowOf(0), rowOf(1)]);
  await inject(page, liveStart(0));
  await inject(page, liveStart(1));
  await openPanel(page);
  const liveName0 = ((await headName(page, 0).innerText()) || '').trim();
  // 开面板即触发 getActiveAgents 回填——退化形快照（agentName=sessionId, task=''）覆盖 live 行。
  await inject(page, { type: 'activeAgents', agents: [snapEntry(0, SIDS[0]), snapEntry(1, SIDS[1])] });
  await page.waitForTimeout(150);
  const after0 = ((await headName(page, 0).innerText()) || '').trim();
  const tip0 = await headName(page, 0).getAttribute('title');
  console.log(`\nS1 live 名（快照前）: ${liveName0}`);
  console.log(`S1 快照覆盖后行名: ${after0}`);
  if (MODE === 'before') {
    ok('S1 修前复现：快照覆盖后行名退化为 sessionId', after0 === SIDS[0], `got "${after0}"`);
  } else {
    ok('S1 收缩修复：快照覆盖后行名仍 = 批次标题首行', after0.includes('【delegate 监督链自动恢复机制批】'), `got "${after0}"`);
    ok('S1 sessionId 降级 tooltip：title 含完整名 + sessionId', (tip0 || '').includes(SIDS[0]) && (tip0 || '').includes('监督链'), `got "${tip0}"`);
    ok('S1 行名不含裸 sessionId', !after0.includes('delegate-nebflow-'));
  }
  await page.screenshot({ path: join(OUT, `s1-shrink-${MODE}.png`) });
  await page.close();
}

// ── S2：重启恢复脸（无 live 帧，纯快照）────────────────────────────────────
{
  const page = await boot();
  await setRows(page, [rowOf(2), rowOf(3)]);
  // 后端修复形：agentName 已回填批名；存量退化形：agentName=sessionId（provider 镜像兜底）。
  await inject(page, {
    type: 'activeAgents',
    agents: [snapEntry(2, TITLES[2]), snapEntry(3, SIDS[3])],
  });
  await openPanel(page);
  const n2 = ((await headName(page, 2).innerText()) || '').trim();
  const n3 = ((await headName(page, 3).innerText()) || '').trim();
  console.log(`\nS2 后端回填形行名: ${n2}`);
  console.log(`S2 存量退化形行名: ${n3}`);
  if (MODE === 'before') {
    ok('S2 修前复现：存量退化形行名 = sessionId', n3 === SIDS[3], `got "${n3}"`);
  } else {
    ok('S2 后端回填形：行名 = 批次标题首行', n2.includes('【wiki 图谱侧栏改造批】'), `got "${n2}"`);
    ok('S2 存量退化形：provider 镜像兜底仍 = 批次标题首行', n3.includes('【对话层活性流式批】'), `got "${n3}"`);
    ok('S2 两行均无裸 sessionId', !n2.includes('delegate-nebflow-') && !n3.includes('delegate-nebflow-'));
  }
  await page.screenshot({ path: join(OUT, `s2-restart-restore-${MODE}.png`) });
  await page.close();
}

// ── S3：8 批并行密度（每行一眼可辨）────────────────────────────────────────
{
  const page = await boot();
  await setRows(page, TITLES.map((_, i) => rowOf(i)));
  await inject(page, {
    type: 'activeAgents',
    agents: TITLES.map((_, i) => snapEntry(i, SIDS[i])), // 全部退化形——最劣输入
  });
  await openPanel(page);
  const rows = page.locator('#bgagent-dropdown .bg-task-row-head');
  const count = await rows.count();
  let distinguishable = 0;
  for (let i = 0; i < Math.min(count, 8); i++) {
    const t = ((await rows.nth(i).locator('.bg-task-name').innerText()) || '').trim();
    if (t.includes('【') && !t.includes('delegate-nebflow-')) distinguishable++;
  }
  if (MODE === 'before') {
    ok('S3 修前复现：8 批并行全部退化为 sessionId', distinguishable === 0, `${distinguishable}/8 可辨`);
  } else {
    ok('S3 密度：8 批并行每行一眼可辨（标题首行，无裸 id）', count === 8 && distinguishable === 8, `${distinguishable}/8 可辨`);
  }
  await page.screenshot({ path: join(OUT, `s3-density-8-${MODE}.png`) });
  await page.close();
}

await browser.close();
console.log(`\n[${MODE}] ${failures === 0 ? 'ALL PASS' : failures + ' FAIL'}`);
process.exit(failures === 0 ? 0 : 1);

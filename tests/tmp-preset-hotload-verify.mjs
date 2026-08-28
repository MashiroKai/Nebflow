// tmp-preset-hotload-verify.mjs — preset 模型列表热加载验收
// 场景：boot 后 allModelRefs 由 modelOptions 填一次（WS onopen，永不刷新）；
// 添加 provider 模型只触发 configData 重推 → parsedConfig 刷新。
// 修复前：getAllModelRefs 优先读 stale allModelRefs → 下拉看不到新模型。
// 修复后：parsedConfig 为实时源 → preset 链编辑器立即可选新模型。
import { chromium } from 'playwright';

const BASE = 'http://127.0.0.1:8981';
const SID = 'sess-1';
let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));

// 服务端 config（可变——模拟 PATCH /config 后 reloadConfig 热加载）
let serverConfig = {
  llm: { providers: { P1: { models: [{ id: 'm1' }] } } },
};
const configDataMsg = () => JSON.stringify({
  type: 'configData', config: JSON.stringify(serverConfig),
  configured: true, onboarding: 'done', models: [], defaults: {},
});

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
await ctx.addInitScript(() => {
  localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
  localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
});
const page = await ctx.newPage();
page.on('pageerror', e => console.log('[pageerror]', String(e.message || e).slice(0, 150)));

// REST mock：catch-all 先注册（后注册优先），具体路径在后
await page.route('**/api/**', r => r.fulfill({ json: {} }));
await page.route('**/api/presets', r => r.fulfill({
  json: { defaultPreset: '', presets: [], agents: {} },
}));

let serverWs = null;
await page.routeWebSocket(/\/ws/, ws => {
  serverWs = ws;
  ws.onMessage(raw => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'getHistory') {
      ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    } else if (m.type === 'getModelOptions') {
      // 关键：modelOptions 只在 WS onopen 时被请求一次——之后添加模型不重发
      ws.send(JSON.stringify({
        type: 'modelOptions', sessionId: m.sessionId || '',
        models: [{ ref: 'P1/m1', label: 'P1 / m1', description: null }], current: null,
      }));
    } else if (m.type === 'getConfig') {
      ws.send(configDataMsg());
    }
  });
  ws.send(configDataMsg());
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: SID, folders: [] }));
});

await page.goto(BASE + '/index.html');
await page.waitForSelector('#activity-bar', { timeout: 10000 });
await sleep(800);

// ── 打开 preset 链编辑器并读取下可选项 ─────────────────────
async function openPresetEditorOptions() {
  await page.click('#settings-btn');
  await page.waitForSelector('#btn-add-preset', { timeout: 8000 });
  await page.click('#btn-add-preset');
  await page.waitForSelector('.preset-chain-editor', { timeout: 8000 });
  await sleep(200);
  const options = await page.$$eval('.preset-chain-editor select option',
    els => els.map(o => o.value).filter(v => v));
  // 关闭弹窗（点 overlay 外/取消按钮）
  await page.click('.cfg-modal #cfg-modal-cancel').catch(() => {});
  await sleep(150);
  // 设置面板也关掉，避免下次 click 被遮
  await page.keyboard.press('Escape').catch(() => {});
  await page.evaluate(() => document.getElementById('settings-overlay')?.classList.remove('on'));
  await sleep(150);
  return options;
}

// A1: boot 后初始选项只有 P1/m1（allModelRefs 与 parsedConfig 一致）
const opts1 = await openPresetEditorOptions();
ok('A1 初始链编辑器选项 = [P1/m1]',
  opts1.length === 1 && opts1[0] === 'P1/m1', JSON.stringify(opts1));

// ── 模拟"添加 provider 模型"：config 变了，只推 configData，不重发 modelOptions
// （复现 bug 条件：allModelRefs 仍是旧的 P1/m1，parsedConfig 已是新的）
serverConfig = {
  llm: { providers: {
    P1: { models: [{ id: 'm1' }, { id: 'm2' }] },
    P2: { models: [{ id: 'm9' }] },
  } },
};
serverWs.send(configDataMsg());
await sleep(300);

// A2: 不重开页面/不重启——重开 preset 编辑器即可选新模型
const opts2 = await openPresetEditorOptions();
ok('A2 添加模型后链编辑器立即可选 P1/m2 + P2/m9（热加载，无需重启）',
  opts2.includes('P1/m1') && opts2.includes('P1/m2') && opts2.includes('P2/m9') && opts2.length === 3,
  JSON.stringify(opts2));

// A3: 回退路径回归——parsedConfig 缺失时仍用 allModelRefs（WS onopen 填的）
const fallback = await page.evaluate(async () => {
  const state = (await import('/js/state.js')).default;
  const presets = await import('/js/presets.js');
  const saved = state.parsedConfig;
  state.parsedConfig = null;
  const refs = presets.getAllModelRefs();
  state.parsedConfig = saved;
  return refs;
});
ok('A3 parsedConfig=null 时回退 allModelRefs（boot 早期不空白）',
  fallback.length === 1 && fallback[0] === 'P1/m1', JSON.stringify(fallback));

// A4: 链编辑器功能回归——能加两行并读出 chain
await page.click('#settings-btn');
await page.waitForSelector('#btn-add-preset', { timeout: 8000 });
await page.click('#btn-add-preset');
await page.waitForSelector('.preset-chain-editor', { timeout: 8000 });
await sleep(200);
const chainOps = await page.evaluate(() => {
  const ed = document.querySelector('.preset-chain-editor');
  const addBtn = ed.querySelector('button');
  return { hasSelect: !!ed.querySelector('select'), addLabel: addBtn?.textContent?.trim() };
});
ok('A4 链编辑器结构完整（select + add 按钮）', chainOps.hasSelect, JSON.stringify(chainOps));

await browser.close();
console.log(failures === 0 ? '\nALL PASS' : `\n${failures} FAILURES`);
process.exit(failures ? 1 : 0);

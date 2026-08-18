// tmp-usage-display-verify.mjs — usage/模型显示修复验收（纯 mock，无后端）
// 链路：WS usageUpdate/done/modelChanged/compactComplete → state.sessionModelInfo
//       → updateHeaderModelInfo（锚定 primary #header-model-info）
import { chromium } from 'playwright-core';

const BASE = 'http://127.0.0.1:8983';
const SID1 = 'sess-main';
const SID2 = 'sess-other';
let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
await ctx.addInitScript(() => {
  localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
  localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
});
const page = await ctx.newPage();
page.on('pageerror', e => console.log('[pageerror]', String(e.message || e).slice(0, 150)));
await page.route('**/api/**', r => r.fulfill({ json: {} }));

let serverWs = null;
await page.routeWebSocket(/\/ws/, ws => {
  serverWs = ws;
  ws.onMessage(raw => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
  });
  ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [
    { id: SID1, agentName: 'Nebula', title: 'Main', updatedAt: Date.now() },
    { id: SID2, agentName: 'Nebula', title: 'Other', updatedAt: Date.now() - 1000 },
  ], activeId: SID1, folders: [] }));
});
await page.goto(BASE + '/index.html');
await page.waitForSelector('#activity-bar', { timeout: 10000 });
await sleep(600);

const send = (msg) => serverWs.send(JSON.stringify(msg));
const headerState = () => page.evaluate(() => {
  const el = document.getElementById('header-model-info');
  return {
    display: el.style.display,
    mode: el.dataset.mode || '',
    label: el.querySelector('.ctx-model-label')?.textContent || '',
    labelTitle: el.querySelector('.ctx-model-label')?.title || '',
    pct: el.querySelector('.ctx-ring-pct')?.textContent || '',
    tooltip: el.querySelector('.ctx-ring-wrap')?.title || '',
  };
});
const modelInfo = () => page.evaluate(async () => {
  const s = (await import('/js/state.js')).default;
  return JSON.parse(JSON.stringify(s.sessionModelInfo));
});

// ── U1: 初始无数据 → 隐藏 ──
let h = await headerState();
ok('U1 无数据时 header 隐藏', h.display === 'none', JSON.stringify(h));

// ── U2: usageUpdate（含 outputTokens）→ 环出现 + tooltip 含 out ──
send({ type: 'usageUpdate', sessionId: SID1, inputTokens: 50000, contextWindow: 200000, compactThreshold: 0.8, outputTokens: 1200 });
await sleep(250);
h = await headerState();
ok('U2 usageUpdate → 环显示 pct=25', h.display === 'inline-flex' && h.pct === '25', JSON.stringify(h));
ok('U2 tooltip 含 input/window + output', h.tooltip.includes('50k / 200k') && h.tooltip.includes('+1k out'), h.tooltip);
ok('U2 无 model 时无模型标签', !h.label, h.label);

// ── U3: done（model + outputTokens）→ 模型标签出现 ──
send({ type: 'done', sessionId: SID1, model: 'zhipu/glm-5.1', contextWindow: 200000, inputTokens: 62000, compactThreshold: 0.8, outputTokens: 3400 });
await sleep(250);
h = await headerState();
ok('U3 done → 模型短名标签 glm-5.1', h.label === 'glm-5.1' && h.labelTitle === 'zhipu/glm-5.1', JSON.stringify(h));
ok('U3 pct 更新为 31 且 tooltip 含模型与 out', h.pct === '31' && h.tooltip.includes('zhipu/glm-5.1') && h.tooltip.includes('+3k out'), h.tooltip);

// ── U4: modelChanged（fallback）→ 标签切换为实际模型 ──
send({ type: 'modelChanged', sessionId: SID1, oldModel: 'zhipu/glm-5.1', newModel: 'deepseek/deepseek-v4-pro' });
await sleep(250);
h = await headerState();
ok('U4 fallback → 标签切换 deepseek-v4-pro', h.label === 'deepseek-v4-pro', JSON.stringify(h));
let mi = await modelInfo();
ok('U4 sessionModelInfo 落 per-session 实际模型', mi[SID1]?.model === 'deepseek/deepseek-v4-pro', JSON.stringify(mi[SID1]));
const ls = await page.evaluate(() => localStorage.getItem('neblink_model_info') || localStorage.getItem('nebflow_model_info') || '');
ok('U4 持久化到 localStorage', ls.includes('deepseek-v4-pro'), ls.slice(0, 120));

// ── U5: 新一轮 usageUpdate（不带 outputTokens，老后端兼容）→ 保留旧 out ──
send({ type: 'usageUpdate', sessionId: SID1, inputTokens: 80000, contextWindow: 200000, compactThreshold: 0.8 });
await sleep(250);
h = await headerState();
ok('U5 流式轮更新 pct=40（就地更新）', h.pct === '40', JSON.stringify(h));
ok('U5 outputTokens 缺省时保留旧值', h.tooltip.includes('+3k out'), h.tooltip);

// ── U6: compactComplete → 环立即降到压缩后值、out 清除 ──
send({ type: 'compactComplete', sessionId: SID1, before: 160000, after: 42000 });
await sleep(250);
h = await headerState();
ok('U6 compaction 后 pct=21（立即刷新）', h.pct === '21', JSON.stringify(h));
ok('U6 outputTokens 已清除（tooltip 无 out）', !h.tooltip.includes('out'), h.tooltip);
mi = await modelInfo();
ok('U6 inputTokens=42000 落 state', mi[SID1]?.inputTokens === 42000, JSON.stringify(mi[SID1]));

// ── U7: 切会话 → header 换成目标会话的数据 ──
send({ type: 'done', sessionId: SID2, model: 'kimi/k3-256k', contextWindow: 256000, inputTokens: 128000, compactThreshold: 0.8, outputTokens: 500 });
await sleep(200);
await page.evaluate(async (sid) => {
  const sb = await import('/js/sidebar.js');
  sb.switchToSession(sid);
}, SID2);
await sleep(400);
h = await headerState();
ok('U7 切会话 → k3-256k + pct=50', h.label === 'k3-256k' && h.pct === '50', JSON.stringify(h));
// 切回 SID1 → 恢复其压缩后数据
await page.evaluate(async (sid) => {
  const sb = await import('/js/sidebar.js');
  sb.switchToSession(sid);
}, SID1);
await sleep(400);
h = await headerState();
ok('U7 切回 → deepseek-v4-pro + pct=21', h.label === 'deepseek-v4-pro' && h.pct === '21', JSON.stringify(h));

await page.screenshot({ path: process.env.HOME + '/.nebflow/docs/Nebflow/assets/usage-header-after.png', clip: { x: 350, y: 0, width: 800, height: 110 } });

await browser.close();
console.log(failures === 0 ? '\nALL PASS' : `\n${failures} FAILURES`);
process.exit(failures ? 1 : 0);

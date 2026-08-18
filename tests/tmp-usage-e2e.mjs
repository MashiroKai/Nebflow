// tmp-usage-e2e.mjs — 隔离实例真实 turn E2E：usage/模型/outputTokens 端到端
// 前置：8095 隔离实例已启动（NEBFLOW_HOME=/tmp/nb-usage-e2e）
import { chromium } from 'playwright-core';
import { readFileSync } from 'node:fs';

const HOME_DIR = '/tmp/nb-usage-e2e';
const token = readFileSync(HOME_DIR + '/auth.json', 'utf8').trim().replace(/^"|"$/g, '');
const BASE = 'http://127.0.0.1:8095';
let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}

const browser = await chromium.launch();
const page = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage();
await page.addInitScript(([t]) => { localStorage.setItem('nebflow_token', t); localStorage.setItem('neblink_token', t); }, [token]);
page.on('pageerror', e => console.log('[pageerror]', String(e.message || e).slice(0, 120)));

// 记录 WS 帧（usageUpdate/done/modelChanged）以验证真实 wire 形状
const frames = [];
await page.routeWebSocket(/\/ws/, ws => {
  const server = ws.connectToServer();
  ws.onMessage(m => server.send(m));
  server.onMessage(m => {
    try {
      const j = JSON.parse(m);
      if (['usageUpdate', 'done', 'modelChanged', 'compactComplete'].includes(j.type)) frames.push(j);
    } catch {}
    ws.send(m);
  });
});

await page.goto(BASE + '/');
await page.waitForSelector('#input', { timeout: 15000 });
await page.waitForTimeout(2500);

// 真实发一条消息（走默认 preset；若 preferred 挂会真实触发 fallback——正好验证模型切换）
await page.fill('#input', 'Reply with exactly: PONG');
await page.press('#input', 'Enter');

// 等 turn 完成（done 帧出现），最长 180s（LowCost/fallback 可能慢）
const t0 = Date.now();
let doneFrame = null;
while (Date.now() - t0 < 180000) {
  doneFrame = frames.find(f => f.type === 'done');
  if (doneFrame) break;
  await page.waitForTimeout(1000);
}
ok('E1 真实 turn 完成（done 帧）', !!doneFrame, doneFrame ? `model=${doneFrame.model} in=${doneFrame.inputTokens} out=${doneFrame.outputTokens}` : 'timeout');
if (doneFrame) {
  ok('E2 done 帧含 outputTokens（后端 c31d1928 实发）', doneFrame.outputTokens != null && doneFrame.outputTokens > 0, `outputTokens=${doneFrame.outputTokens}`);
  ok('E3 done 帧含 model + contextWindow', !!doneFrame.model && !!doneFrame.contextWindow, `model=${doneFrame.model} cw=${doneFrame.contextWindow}`);
}
const usageFrames = frames.filter(f => f.type === 'usageUpdate');
ok('E4 usageUpdate 帧到达（每轮）', usageFrames.length >= 1, `frames=${usageFrames.length} ot=${usageFrames.map(f => f.outputTokens).join(',')}`);

const fb = frames.filter(f => f.type === 'modelChanged');
console.log(`  [info] modelChanged frames: ${fb.length}${fb.length ? ' — fallback 真实发生: ' + JSON.stringify(fb[0]) : '（无 fallback）'}`);

// header 最终状态
await page.waitForTimeout(500);
const h = await page.evaluate(() => {
  const el = document.getElementById('header-model-info');
  return {
    display: el.style.display,
    label: el.querySelector('.ctx-model-label')?.textContent || '',
    pct: el.querySelector('.ctx-ring-pct')?.textContent || '',
    tooltip: el.querySelector('.ctx-ring-wrap')?.title || '',
  };
});
ok('E5 header 显示模型标签 + 环', h.display === 'inline-flex' && !!h.label && !!h.pct, JSON.stringify(h));
ok('E6 tooltip 含模型 + out', h.tooltip.includes('out') && !!h.tooltip, h.tooltip);
// 模型一致性：header 模型 === done 帧实际模型
if (doneFrame?.model) {
  const short = doneFrame.model.split('/').pop();
  ok('E7 显示与实际使用模型一致', h.label === short, `${h.label} vs ${short}`);
}
await page.screenshot({ path: process.env.HOME + '/.nebflow/docs/Nebflow/assets/usage-e2e-header.png', clip: { x: 300, y: 0, width: 900, height: 110 } });

await browser.close();
console.log(failures === 0 ? '\nALL PASS' : `\n${failures} FAILURES`);
process.exit(failures ? 1 : 0);

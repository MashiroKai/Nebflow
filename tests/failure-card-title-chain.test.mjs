// failure-card-title-chain.test.mjs — 失败卡点开会话的标题回填契约（panel-defects 批 ③⑤ 防回归，静态档）。
//
// 缺陷形状：失败卡按钮只传 sessionId ⇒ 弹窗头退回 agentStart 的定义名「subagent」。
// 契约链（源码级，node --test 无浏览器）：
//   ① providerFailureStatus.js 卡按钮把 digest 条目 title 作为 openTask(id, title) 传出；
//   ② 三处调用方（chatView.js / main.js / bgAgentPopup.js）把 title 作为第三参传给
//      openStepPopup(id, '', title)；
//   ③ bgAgentPopup.js 头名 fallback 顺序 = agentName || taskDescription || meta.agentName || 'Sub-agent'
//      （第三参 taskDescription 即批次名，必须排在定义名之前）。
// 任一环断开 ⇒ 红。

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web', 'js');
const read = (f) => readFileSync(join(WEB, f), 'utf8');

test('① 失败卡按钮把批次标题随 sessionId 一并交给 openTask', () => {
  const src = read('providerFailureStatus.js');
  // 按钮 click 必须携带 title（函数签名 openTask(id, title)）。
  assert.match(src, /const title = items\.find\(\(i\) => i\.sessionId === id\)\?\.title \|\| ''/);
  assert.match(src, /'on:click': \(\) => openTask\(id, title\)/);
});

test('② 三处调用方都把 title 作为 openStepPopup 第三参传入', () => {
  for (const f of ['chatView.js', 'main.js', 'bgAgentPopup.js']) {
    const src = read(f);
    assert.match(src, /openStepPopup\(id, '', title\)/, `${f} 未传 title（标题回填断开）`);
  }
});

test('③ 弹窗头名 fallback 顺序：批次名（taskDescription）先于定义名', () => {
  const src = read('bgAgentPopup.js');
  assert.match(
    src,
    /esc\(agentName \|\| taskDescription \|\| entry\.meta\.agentName \|\| 'Sub-agent'\)/,
    '弹窗头 fallback 顺序被改动',
  );
});

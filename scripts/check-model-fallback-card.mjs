#!/usr/bin/env node
/**
 * check-model-fallback-card.mjs — model-fallback-card 批静态闸（2026-11-18）.
 *
 * 断言（作者裁定 2026-11-18 14:37 的四项机制面，任一缺失即红）：
 *   ① 统一卡片：存在**一个** kind（InteractionKind.ModelUnavailable）+ 一个
 *      「模型不可用」载荷拼帧点，且**全槽共用**（槽名是载荷字段，不是每槽一套）；
 *   ② 切换动作：卡片带 switch 动作 + 就地写回该槽 `model.preferred`（writeSlotModel）；
 *   ③ 测试动作：卡片带 test 动作 + 一次最小真实调用的探针缝；
 *   ④ 超时回报：默认 300s（可配置）+ 超时回报根会话（reportOnly 卡，不静默）；
 * 外加「不许静默降级」不变式：fail-closed 缺省仍是 false（不得被本批改掉）。
 *
 * 用法：node scripts/check-model-fallback-card.mjs [--root <dir>]（默认 = 本仓根）
 * 退出码：0 = 全绿；1 = 有缺失项（逐条打印缺什么）。
 */
import fs from 'node:fs';
import path from 'node:path';

const args = process.argv.slice(2);
const rootIdx = args.indexOf('--root');
const ROOT = path.resolve(rootIdx >= 0 ? args[rootIdx + 1] : process.cwd());

const read = (rel) => {
  const p = path.join(ROOT, rel);
  return fs.existsSync(p) ? fs.readFileSync(p, 'utf8') : '';
};

const CARD = 'src/main/scala/nebflow/core/model/ModelUnavailableCard.scala';
const EVENTS = 'src/main/scala/nebflow/actor/AgentEvent.scala';
const HUB = 'src/main/scala/nebflow/agent/InteractionHub.scala';
const INJECT = 'src/main/scala/nebflow/core/project/PluginInjection.scala';
const WIRE = 'src/main/resources/web/js/ws.js';
const RENDER = 'src/main/resources/web/js/chat.js';
const PAYLOAD = 'src/main/scala/nebflow/shared/ProtocolPayloads.scala';

const failures = [];
const check = (name, ok, detail) => {
  if (!ok) failures.push(`${name} — ${detail}`);
};

const card = read(CARD);
const events = read(EVENTS);
const hub = read(HUB);
const inject = read(INJECT);
const wire = read(WIRE);
const render = read(RENDER);
const payload = read(PAYLOAD);

// ⓪ 单一实现件必须在场
check('unified-card-module', card.length > 0, `missing ${CARD}`);

// ① 一套 kind + 一套拼帧点，全槽共用
check(
  'unified-card-kind',
  /case ModelUnavailable/.test(events),
  'InteractionKind.ModelUnavailable (the ONE kind every slot uses) is absent'
);
check(
  'unified-card-type-wire',
  /"type"\s*->\s*"modelUnavailable"/.test(card),
  'card payload does not stamp type=modelUnavailable'
);
check(
  'unified-card-render-branch',
  /InteractionKind\.ModelUnavailable\s*=>\s*renderModelUnavailable/.test(hub),
  'InteractionHub has no ModelUnavailable render branch'
);
check(
  'unified-card-no-per-slot-duplication',
  (card.match(/def cardPayload/g) || []).length === 1 &&
    (card.match(/def raiseAndAwait/g) || []).length === 1 &&
    (card.match(/def withUnavailableCard/g) || []).length === 1,
  'more than one card builder / await / combinator (per-slot duplication is forbidden)'
);
check(
  'unified-card-slot-field',
  /"slot"\s*->\s*r\.slot\.asJson/.test(card),
  'card payload does not report WHICH slot'
);
check(
  'unified-card-failure-class',
  /enum FailureClass/.test(card) &&
    /HttpStatus/.test(card) &&
    /ProviderCode/.test(card) &&
    /case Timeout/.test(card),
  'failure classification (HTTP status / provider error code / timeout) is incomplete'
);

// ② 切换动作 + 就地写回
check('action-switch', /case Test, Switch, Dismiss/.test(payload) && /=> "switch"/.test(payload), 'no switch action on the card');
check(
  'action-switch-writes-slot',
  /def writeSlotModel/.test(card) && /model\.preferred|"model"\s*->\s*chain\.asJson/.test(card),
  'switch does not write the slot model.preferred in place'
);
check(
  'action-switch-retries-original',
  /case Outcome\.Switched\(ref\)/.test(card) && /retrying the original request/.test(card),
  'the original request is not retried after a switch'
);
check(
  'action-switch-single-choke-point',
  /withUnavailableCard/.test(inject),
  'the allocation choke point does not go through the unified card combinator'
);

// ③ 测试动作 + 真实调用缝
check('action-test', /case Test, Switch, Dismiss/.test(payload) && /=> "test"/.test(payload), 'no test action on the card');
check(
  'action-test-real-call-seam',
  /type Probe\s*=/.test(card) && /TestResult/.test(card) && /latencyMs/.test(card),
  'the test action has no minimal-real-call seam reporting success/failure + latency'
);

// ④ 300s 超时 + 回报根会话
check(
  'timeout-default-300',
  /DefaultTimeoutSec:\s*Int\s*=\s*300/.test(card),
  'the default card timeout is not 300s'
);
check(
  'timeout-configurable',
  /unavailableCardTimeoutSec/.test(card),
  'the card timeout is not configurable'
);
check(
  'timeout-reports-to-root',
  /reportTimeout\(\)/.test(card) && /reportOnly/.test(card) && /Outcome\.TimedOut/.test(card),
  'timeout does not report to the root session (silent loss)'
);

// 前端：卡片得能被注册与渲染
check('frontend-registered', /'modelUnavailable'/.test(wire), 'modelUnavailable is not in the frontend message-type registration');
check('frontend-renderer', /modelUnavailable/.test(render), 'no frontend renderer for the model-unavailable card');

// 「不许静默降级」不变式：fail-closed 缺省不得被本批翻面
const pluginsConfig = read('src/main/scala/nebflow/core/plugin/PluginsConfig.scala');
check(
  'fail-closed-unchanged',
  /def failOpenOnAllocationError[\s\S]{0,200}default\s*=\s*false/.test(pluginsConfig),
  'plugins.failOpenOnAllocationError default is no longer false (fail-closed red line)'
);

if (failures.length) {
  console.error(`✖ model-fallback-card gate: ${failures.length} missing assertion(s)`);
  failures.forEach((f) => console.error(`   - ${f}`));
  process.exit(1);
}
console.log('✔ model-fallback-card gate: unified card + switch + test-llm + 300s root report all present');
process.exit(0);

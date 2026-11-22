// todo-ask-separation.test.mjs — 待办分离纪律的节点级断言（2026-11-22 作者令）。
// 运行：node --test scripts/tests/todo-ask-separation.test.mjs
// 零依赖、不跑 sbt、不起引擎：只读源码断言分组命名与判据。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (p) => readFileSync(join(root, p), 'utf8');
const sched = read('src/main/resources/web/js/scheduled-task.js');
const zh = read('src/main/resources/web/js/locales/zh-CN.js');
const en = read('src/main/resources/web/js/locales/en.js');

test('AskUser 转入组使用独立命名键，旧 grpDecision 键已退役', () => {
  for (const [name, src] of [['zh-CN', zh], ['en', en]]) {
    assert.match(src, /'todo\.grpAskConverted':/, `${name} 缺 todo.grpAskConverted`);
    assert.doesNotMatch(src, /'todo\.grpDecision':/, `${name} 仍残留 todo.grpDecision`);
  }
});

test('分组渲染把 decision 判据映到转入组键，计划组键与之分开', () => {
  assert.match(sched, /\['todo\.grpAskConverted', visible\.filter\(\(r\) => todoGroupOf\(r\) === 'decision'\)\]/);
  assert.match(sched, /\['todo\.grpPlan', visible\.filter\(\(r\) => todoGroupOf\(r\) === 'plan'\)\]/);
  assert.doesNotMatch(sched, /todo\.grpDecision/);
});

test('decision 组只收 ask- 镜像行（即时裁定），建议/计划待办不得落入', () => {
  assert.match(sched, /if \(askRequestIdOf\(r\)\) return 'decision';/);
  // 计划组判据不得吞 ask 镜像：plan 只在 decision / approval 之后兜底
  const fn = sched.slice(sched.indexOf('function todoGroupOf'), sched.indexOf('function todoGroupOf') + 400);
  assert.ok(fn.indexOf("return 'decision'") < fn.indexOf("return 'plan'"));
});

test('转入组与计划组的中文文案不同形（分离可见）', () => {
  const pick = (k) => zh.match(new RegExp(`'${k}': '([^']+)'`))?.[1];
  assert.ok(pick('todo.grpAskConverted'));
  assert.notEqual(pick('todo.grpAskConverted'), pick('todo.grpPlan'));
});

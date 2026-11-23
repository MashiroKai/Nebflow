// provider-label-resolve.test.mjs — 失败卡服务商名读回（panel-defects 批 ④ 的防回归，静态档）。
//
// 契约（providerFailureStatus.js resolveProviderLabel）：后端 providerGroups[].provider 为
// `unknown` 时，按与后端 ProviderRef 同一判据（[tried]/[skipped]/受限：/failed: 前置 +
// provider/model 形状）从 reason 读回 provider 段；非 unknown 原样；全未命中维持 unknown。
// 纯展示，不改后端分组键。node --test 直跑，无浏览器。

import { test } from 'node:test';
import assert from 'node:assert/strict';

// i18n.js 在 import 期读 localStorage；给最小桩（无 DOM 依赖）。
globalThis.localStorage ??= { getItem: () => null, setItem() {}, removeItem() {} };
globalThis.window ??= globalThis;

const { resolveProviderLabel } = await import('../src/main/resources/web/js/providerFailureStatus.js');

test('provider 已解析时原样返回（不被正文覆盖）', () => {
  assert.equal(resolveProviderLabel({ provider: 'anthropic', reason: '[skipped] zhipu/glm-5 x' }), 'anthropic');
});

test('unknown + 处置表形 [skipped] provider/model ⇒ 读回 provider 段', () => {
  assert.equal(resolveProviderLabel({ provider: 'unknown', reason: '[skipped] zhipu/glm-5 quota exhausted' }), 'zhipu');
});

test('unknown + [tried] 形 ⇒ 读回', () => {
  assert.equal(resolveProviderLabel({ provider: 'unknown', reason: '[tried] openai/gpt-4o timeout' }), 'openai');
});

test('unknown + 时段窗「受限：」形 ⇒ 读回（UTSC 样例）', () => {
  assert.equal(resolveProviderLabel({ provider: 'unknown', reason: '受限：UTSC/glm-5.3-flash 06:00-01:00' }), 'UTSC');
});

test('unknown + failed: 前缀形 ⇒ 读回', () => {
  assert.equal(resolveProviderLabel({ provider: 'unknown', reason: 'failed: deepseek/deepseek-chat 503' }), 'deepseek');
});

test('unknown + 无前置判据的正文 ⇒ 维持 unknown（不臆测）', () => {
  assert.equal(resolveProviderLabel({ provider: 'unknown', reason: 'All providers failed (2 attempt(s))' }), 'unknown');
  assert.equal(resolveProviderLabel({ provider: 'unknown', reason: '' }), 'unknown');
});

test('缺省 group 不抛错 ⇒ unknown', () => {
  assert.equal(resolveProviderLabel(undefined), 'unknown');
  assert.equal(resolveProviderLabel({}), 'unknown');
});
